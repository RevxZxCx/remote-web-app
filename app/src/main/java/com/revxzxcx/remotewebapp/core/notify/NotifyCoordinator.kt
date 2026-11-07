package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.RemoteEventsClient
import com.revxzxcx.remotewebapp.core.events.SessionNotification
import com.revxzxcx.remotewebapp.core.session.SessionListParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 把「连接上发生的事」翻译成「系统通知」（R056）。
 *
 * ## 它订阅什么
 * 只订阅 [RemoteEventsClient.sessionNotificationFeed]——那条**广播**流，不是
 * `sessionNotifications` 那条 `Channel`。后者是 `HomeViewModel` 的独占收件箱，
 * 多一个收集方就会互相抢事件（见 `RemoteEventsClient` 里 `_sessionNotificationFeed`
 * 的 KDoc）。审批则读 `approvals` 这个 `StateFlow`：它本来就是状态，不是队列。
 *
 * ## 生命周期
 * 与 `AppContainer` 同寿（`appScope`）。**不随任何页面生死**：用户在聊天页跑任务、
 * 按 Home 切出去、Task 被回收……只要进程还在，事件就还在流。这也是 Q1 只做
 * 「事件→通知」的前提：**进程被系统杀掉之后什么都收不到**，那种情况由设置页的
 * 「上次是被杀掉的」提示来兜（见 `NoticeSettings`）。
 *
 * ## 为什么「当前在看哪个会话」要由 UI 推过来
 * 协调器跑在 `appScope`（IO），看不到 Compose 状态；而「用户此刻是否正盯着这个会话」
 * 是判断该不该压掉通知的关键。所以由 `AppRoot` 在会话切换时调 [setViewingSession]，
 * 前后台由 `DshApplication` 的 `ProcessLifecycleOwner` 调 [setForeground]。
 * 两边都是**单值状态**而不是事件流——晚到的订阅者读到最新值即可，不需要补历史。
 */
class NotifyCoordinator(
    private val scope: CoroutineScope,
    private val center: NotificationCenter,
    private val settings: NoticeSettings,
    private val names: SessionNameTable,
) {

    /**
     * 用户此刻是否正看着某个会话。
     *
     * `@Volatile`：写在主线程（Compose 副作用），读在 IO（事件收集协程）。
     * 读到的值最多晚一帧，而这一帧的差异只影响「发不发一条通知」，
     * 不影响任何持久状态——用 `StateFlow` 反而要多一层调度。
     */
    @Volatile
    private var viewingSessionId: String? = null

    /** App 是否在前台。由 `ProcessLifecycleOwner` 推。 */
    @Volatile
    private var foreground: Boolean = false

    /**
     * 当前连接的 `RemoteEventsClient`；换主机时由 [attach] 换掉。
     *
     * 必须能换：`AppContainer` 每次重建连接都会新建一个 `DshConnection` 和它的
     * `RemoteEventsClient`。若只在构造时订一次，切过主机之后通知就永久失效——
     * 而那种「静默失效」最难被发现（用户只会觉得「最近怎么不响了」）。
     */
    @Volatile
    private var current: RemoteEventsClient? = null

    private var linkJob: kotlinx.coroutines.Job? = null

    private var approvalJob: kotlinx.coroutines.Job? = null

    /**
     * 已经为哪些 `eventId` 发过审批通知。
     *
     * 用有界环：一段长任务里可能有成百上千次审批，全留着是无界增长；但留太少会
     * 重复发（服务端补发同一条 waterfall 时 `approvals` 会重新出现该 eventId，
     * 于是又弹一条一模一样的通知）。
     *
     * 与 `RemoteEventsClient` 里 `handledApprovals` 的区别：那里记的是「已经回答过」，
     * 这里记的是「已经通知过」。两个集合独立，因为一条请求可能「通知过但还没回答」。
     */
    private val notifiedApprovals = ArrayDeque<String>()

    private val notifiedApprovalsSet = HashSet<String>()

    private val parser = SessionListParser()

    /**
     * 订阅一条连接上的事件。可重复调用：会先退订旧的。
     *
     * @param client 新建连接的那个客户端；传 `null` 表示断开（未配对），此时退订即可。
     */
    fun attach(client: RemoteEventsClient?) {
        if (current === client) return
        current = client
        linkJob?.cancel()
        approvalJob?.cancel()
        // 换连接 = 换了一台机器（或同一台机器的另一次连接）。两本按 eventId 记的账
        // 必须一起清：不清的话，新连接上「同 eventId 的请求」会被当成已经通知过而跳过
        // ——用户永远收不到它；反过来，旧账里的 id 在新连接上根本不存在，
        // `syncApprovals` 的「已消失」分支会去撤一批撤不掉的通知。
        //
        // `edge`（跑没跑完的边沿账）同样要清：新连接第一次看到的 running=false 若撞上
        // 旧账里的 `true`，就会凭空发一条「任务结束」——而那个会话可能正在跑。
        // 清掉后第一次见到的状态按「未知」处理（`CompletionEdge.observe` 的既有约定）。
        forgetAllNotified()
        edge.reset()
        names.clear()
        if (client == null) return

        linkJob = scope.launch {
            client.sessionNotificationFeed.collect { handle(it) }
        }
        approvalJob = scope.launch {
            // 用 `collect` 而不是 `collectLatest`：通知是**逐条**该发的，
            // 取消前一条会丢掉中间某个请求（waterfall 丢一条就永久卡住）。
            client.approvals.collect { list -> syncApprovals(list) }
        }
    }

    /** 界面推「用户此刻在看哪个会话」，`null` = 在首页列表或设置页。 */
    fun setViewingSession(sessionId: String?) {
        viewingSessionId = sessionId
    }

    /** 界面推「App 是否在前台」。 */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    /** 用首页那份会话列表播种标题表（`session/list` 每次刷新调一次）。 */
    fun rememberTitles(pairs: List<Pair<String, String>>) {
        names.remember(pairs)
    }

    /** 用户点进某个会话时，把它挂着的那条完成通知撤掉（他已经看见了）。 */
    fun onSessionOpened(sessionId: String) {
        viewingSessionId = sessionId
        if (!hasPendingDone(sessionId)) return
        center.cancelDone(sessionId)
    }

    /**
     * 会话是否还有一条「任务结束」通知挂着。
     *
     * 单独判一次是为了**不给没通知过的会话分配 id**：`cancelDone` 内部会
     * `allocate` 一个再撤销，虽然结果无害，但池子会因此多一次 LRU 触碰，
     * 把真正挂着通知的会话挤出去。
     */
    private fun hasPendingDone(sessionId: String): Boolean = center.peekId(sessionId) != null

    /** 收到一条会话转发事件。 */
    private fun handle(notification: SessionNotification) {
        when (notification) {
            is SessionNotification.Added -> {
                // added 帧带完整摘要，顺手把标题记下来：新会话第一次跑完时
                // `session/list` 可能还没刷新，没有标题就只能显示 id 后 8 位。
                val summary = parser.summaryOf(notification.summary)
                if (summary != null && summary.title.isNotBlank()) {
                    names.remember(summary.sessionId, summary.title)
                }
                // added 也可能带着 `running = true`（新建即开跑）。
                // 这里**不**判完成边沿：added 表示「刚出现」，不可能是「刚跑完」。
                // 但仍然要把它喂给 edge 记账，否则下一帧 status(false) 会因为
                // previous == null 被判成「第一次见」而漏掉一次完成。
                summary?.let { edge.observe(it.sessionId, it.running) }
            }

            is SessionNotification.Removed -> {
                // 会话被删了：撤掉它挂着的通知，并把它从两本账里清掉——
                // 不清的话，同 id 会话被重建时边沿会判错（旧账说它在跑）。
                center.cancelDone(notification.sessionId)
                edge.forget(notification.sessionId)
                names.forget(notification.sessionId)
                // 审批通知不在这里撤：`eventId` 与 `sessionId` 没有稳定映射，
                // 硬要按会话找会误撤别人的。它们会在 `syncApprovals` 下一次对账时
                // 因「不在 approvals 列表里」被统一撤掉。
            }

            is SessionNotification.Status -> {
                val edgeHit = edge.observe(notification.sessionId, notification.running)
                if (edgeHit == true) {
                    postDone(notification.sessionId, failed = false, reason = null)
                }
            }

            is SessionNotification.Error -> {
                // 失败是**独立的**语义：它不是「跑完了」，而是「这一轮没跑成」。
                // 服务端在 error 帧之后就未必再发 status(false)，所以这里直接发，
                // 不依赖边沿——但会顺手把 edge 标成不在跑，避免紧随其后的
                // status(false) 再发一条「任务结束」（同一件事响两次）。
                edge.observe(notification.sessionId, false)
                postDone(notification.sessionId, failed = true, reason = notification.message)
            }

            is SessionNotification.Activity -> {
                // 「有活动」只影响列表排序与红点，不产生通知。显式列出而不写 else，
                // 是为了将来加第五种事件时编译能提醒到这里。
            }
        }
    }

    /** 发一条完成/失败通知（若门控允许）。 */
    private fun postDone(sessionId: String, failed: Boolean, reason: String?) {
        val channel = NoticeChannels.DONE
        val delivery = NoticeGate.decide(
            visibility = NoticeVisibility(
                foreground = foreground,
                viewingSessionId = viewingSessionId,
                completedSessionId = sessionId,
            ),
            channelId = channel,
            permissionGranted = center.canPost(),
            channelEnabled = settings.effectiveEnabled(channel, center.isChannelEnabled(channel)),
        )
        if (delivery == NoticeDelivery.Skip) return
        val name = NoticeText.sessionName(names.titleOf(sessionId), sessionId)
        center.postDone(sessionId, name, failed, reason, silent = delivery == NoticeDelivery.Silent)
    }

    /**
     * 同步审批列表：新出现的发通知，消失的撤通知。
     *
     * 用「当前列表」与「已通知集合」双向对账，而不是只听新增事件：`approvals` 是
     * `StateFlow`，重连后可能整体换一批（旧的被清空），只判新增会留下一排永远
     * 撤不掉的旧通知。
     */
    private fun syncApprovals(list: List<ApprovalRequest>) {
        val alive = list.map { it.eventId }.toHashSet()
        // 已经不在了 → 撤掉通知（回答成功、服务端撤回、重连清空都走这条）。
        val gone = notifiedApprovalsSet - alive
        for (eventId in gone) {
            center.cancelAskByEventId(eventId)
            forgetNotified(eventId)
        }
        // 新出现的 → 发通知。
        for (request in list) {
            if (request.eventId in notifiedApprovalsSet) continue
            val channel = NoticeChannels.ASK
            val delivery = NoticeGate.decide(
                visibility = NoticeVisibility(
                    foreground = foreground,
                    viewingSessionId = viewingSessionId,
                    completedSessionId = request.agentId,
                ),
                channelId = channel,
                permissionGranted = center.canPost(),
                channelEnabled = settings.effectiveEnabled(channel, center.isChannelEnabled(channel)),
            )
            if (delivery == NoticeDelivery.Skip) continue
            markNotified(request.eventId)
            center.postAsk(request)
        }
    }

    private fun markNotified(eventId: String) {
        if (!notifiedApprovalsSet.add(eventId)) return
        notifiedApprovals.addLast(eventId)
        while (notifiedApprovals.size > NOTIFIED_RING) {
            notifiedApprovalsSet.remove(notifiedApprovals.removeFirst())
        }
    }

    private fun forgetNotified(eventId: String) {
        if (!notifiedApprovalsSet.remove(eventId)) return
        notifiedApprovals.remove(eventId)
    }

    /** 清空「已通知过」的环（换连接时用，见 [attach]）。 */
    private fun forgetAllNotified() {
        notifiedApprovals.clear()
        notifiedApprovalsSet.clear()
    }

    /**
     * 「这一轮是不是刚跑完」的边沿账。
     *
     * 与 `HomeViewModel` 里 `CompletionLedger` 的判据一致（第一次见不算跑完），
     * 但**生命周期与容器同级**：ViewModel 会随 Activity 销毁重建，重建后「上一态」
     * 是空白的，下一次 `status(false)` 就会被当成第一次见 → 漏发通知。
     */
    private val edge = CompletionEdge()

    /** 边沿账里的会话数（测试与诊断用）。 */
    val edgeSize: Int get() = edge.size

    companion object {
        /** 已通知过的 `eventId` 环容量。 */
        const val NOTIFIED_RING = 64
    }
}
