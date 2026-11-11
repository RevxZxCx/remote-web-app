package com.revxzxcx.remotewebapp.core.notify

import android.util.Log

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.QuestionRequest
import com.revxzxcx.remotewebapp.core.events.RemoteEventsClient
import com.revxzxcx.remotewebapp.core.events.SessionNotification
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.session.ChatFrame
import com.revxzxcx.remotewebapp.core.session.SessionListParser
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TurnProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * 查询一轮结局的入口（R056 缺口 5）。
     *
     * 返回该会话窗口内最后一条 `turn/end`；查不到（连接断了、服务端拒绝、超时）
     * 返回 `null`，调用方退回「这一轮结束了」的旧口径。
     *
     * **做成参数而不是让协调器自己造一个 `ChatRepository`**：协调器只认识
     * `RemoteEventsClient`，不认识 `DshConnection`；把「怎么查」留在容器里，
     * 协调器就仍然是可测的纯编排。默认实现 `null` = 不查，此时行为与 R056
     * 首版完全一致（只按 running 翻转发「任务结束」）。
     */
    private val fetchLastTurn: (suspend (String) -> TurnProbe?)? = null,
    /**
     * 子代理登记表（R059-B011）。
     *
     * 判定子代理靠 `SessionSummary.origin == "subagent"`，而 `origin` **只在
     * `added` 帧与全量列表里出现**，`status` / `error` 帧只有 id。
     * 不记这张表，子代理跑完与主会话跑完在事件层完全同形，通知层无从分辨。
     *
     * 做成参数而不是内部 new 一个：`AppContainer` 需要把同一份登记表
     * 也给胶囊协调器用，两处判据必须同源，否则会出现「通知说完成了、
     * 胶囊说还在跑」这种自相矛盾。
     */
    private val subagents: SubagentRegistry = SubagentRegistry(),
    /**
     * 与胶囊层共享的覆盖账（R062-B019）。
     *
     * 用户报的「重复通知」不是某一处忘了判断，而是两层**结构上缺一个交汇点**：
     * 它们各自独立订阅同一批事件，谁也不知道对方刚发过什么。这份账就是那个交汇点
     * ——胶囊层写入「我正在替谁说话」，这里据此压掉重复的那条普通通知。
     *
     * 默认 `null` 便于单测：绝大多数用例只关心门控与结局账，与去重无关。
     */
    private val coverage: CapsuleCoverageRegistry? = null,
) {

    /**
     * 结局查询的串行闸。
     *
     * 存在的理由与 `ChatViewModel` 里 `goalMutex` 相同：一次 running 翻转可能同时
     * 从 `status` 与 `error` 两条路径进来，两次查询会各自开一条 `session/follow`，
     * 拿到同一条 `turn/end` 再各发一条通知。串行化之后第二次查到同一个 `seq`，
     * 被 [OutcomeLedger] 认成「已经报过」。
     */
    private val outcomeMutex = Mutex()

    /** 已就哪些 `turn/end` 发过通知（见 [OutcomeLedger]）。 */
    private val outcomes = OutcomeLedger()

    /** 账号问题的提醒账（R056 缺口 4）。 */
    private val accounts = AccountNoticeGate()

    /** 连接状态的判定器（R056 缺口 3）。 */
    private val link = LinkNoticeGate()

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

    private var questionJob: kotlinx.coroutines.Job? = null

    /** 盯着覆盖账的变化，负责补发被压掉的完成通知（R062-B019）。 */
    private var coverageJob: kotlinx.coroutines.Job? = null

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

    /**
     * 已经为哪些 `eventId` 发过**提问**通知。
     *
     * 与审批那对本账分开，不共用：审批与提问是两套独立的 waterfall 事件流，
     * `eventId` 由服务端各自生成。共用一本的坏处是「一条审批通知把提问的记账挤掉」，
     * 于是同一条提问被重复通知。
     */
    private val notifiedQuestions = ArrayDeque<String>()

    private val notifiedQuestionsSet = HashSet<String>()

    private val parser = SessionListParser()

    /**
     * 订阅一条连接上的事件。可重复调用：会先退订旧的。
     *
     * @param client 新建连接的那个客户端；传 `null` 表示断开（未配对），此时退订即可。
     */
    /**
     * @param dropDeferred 是否连欠账一起丢掉。**换到另一台主机时必须给 true**
     *   （第二轮审查 P2-1）：欠账里的 sessionId 是上一台机器的，而 `attach` 新建的
     *   `state.collect` 会立刻收到 StateFlow 重放的当前值（`NONE`）→ `flushDeferred`
     *   当场按**新主机**的身份把它们发出去，标题还是刚被 `names.clear()` 抹掉的
     *   「未命名会话 xxx」，点进去是一条不存在的会话。
     *   普通重连（同一台主机）给 false —— 那些欠账在同一台机器上仍然有效，必须补发。
     */
    fun attach(client: RemoteEventsClient?, dropDeferred: Boolean = client == null) {
        if (current === client) return
        current = client
        linkJob?.cancel()
        approvalJob?.cancel()
        questionJob?.cancel()
        coverageJob?.cancel()
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
        // 结局账按 `turn/end.seq` 记，`seq` 是**每台主机各自**的编程序号：
        // 拿 A 机器的 seq 去比 B 机器的 seq，会把一条从没报过的结局判成已报过。
        outcomes.clear()
        // 连接判定器同理：它记着「上一台断多久了、提醒过没有」。
        // 不复位的话，切到一台连得好好的主机之后，那条「连不上」还挂在通知栏里
        // ——因为新连接的第一帧被订阅处刻意跳过（不构成「变化」），
        // 判定器永远不会知道自己已经恢复，`takeSettle` 也就永远不触发。
        link.reset()
        // 账号是**每台桌面端机器各自的**：切过去之后那台的账号可能是好的。
        accounts.reset()
        // 子代理登记表同理：不同主机上同一个 sessionId 完全可能是另一条会话，
        // 留着旧结论会让新连接上的主会话被误判成子代理而静默不提醒。
        subagents.clear()
        // 覆盖账的「状态」每次 attach 都要复位：胶囊在断连/重启后已经没了，
        // 留着 `coversDone = true` 会让通知层替一个根本没显示的卡片压掉通知
        // （R062-B019）。
        coverage?.resetCoverage()
        // 丢欠账的判据不是「client 是不是 null」，而是「是不是换了一台机器」
        // （第二轮审查 P2-1）。原先绑在 `client == null` 上，于是「换主机」这条
        // 走 `Rebuild` → `attach(新client)` 的路径漏了出去：上一台的欠账活着，
        // 而下面那个 `state.collect` 一订阅就重放当前值，把它们当场补发成
        // 新主机的僵尸通知。改由调用方显式声明（默认值保持「退配对就丢」）。
        if (dropDeferred) coverage?.clearDeferred()
        if (client == null) return

        linkJob = scope.launch {
            client.sessionNotificationFeed.collect { handle(it) }
        }
        approvalJob = scope.launch {
            // 用 `collect` 而不是 `collectLatest`：通知是**逐条**该发的，
            // 取消前一条会丢掉中间某个请求（waterfall 丢一条就永久卡住）。
            client.approvals.collect { list -> syncApprovals(list) }
        }
        questionJob = scope.launch {
            // 同审批：waterfall 丢一条就永久卡住，所以不能 `collectLatest`。
            client.questions.collect { list -> syncQuestions(list) }
        }
        // 胶囊的覆盖范围一变，就把「它不再负责的那几条」的欠账补发出去
        // （R062-B019）。没有这条收集，被压掉的通知就永远发不出来了。
        coverage?.let { registry ->
            coverageJob = scope.launch {
                // 每轮吞掉异常（第二轮审查 P3-2）：这是一条**常驻**收集，不像另外三条
                // 事件协程那样崩了只损失一条。`postDone` → `nm.notify` 在权限被收回时
                // 会抛 `SecurityException`（`CapsuleCenter.post` 正为同一个窗口加了
                // catch），这里一崩，整个补发机制就停摆到下次 attach，所有欠账静默积压。
                registry.state.collect { coverageNow ->
                    runCatching {
                        // 先撤后补（第四轮审查 P3-1）。KDoc 从一开始就说这条收集器
                        // 要负责「先发后接管」那一半：完成通知先落地、胶囊随后
                        // 翻成已完成。那时两条会并存在通知栏与卡片上，正是 B019
                        // 要消灭的重复。`deliverUnlessCovered` 只覆盖了「胶囊先到」
                        // 的顺序，反过来的顺序靠这里。
                        withdrawCoveredDone(coverageNow)
                        flushDeferred()
                    }.onFailure { Log.w(TAG, "coverage reconcile failed", it) }
                }
            }
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

    /**
     * 容器推「载波现在是什么状态」（R056 缺口 3）。
     *
     * 由 `AppContainer` 订阅 `connection.mux.stats` 后喂进来，而不是协调器自己去
     * 订阅——它只认识 `RemoteEventsClient`，不认识 `DshConnection`，多一样依赖就
     * 多一样测试要造的东西。
     *
     * @param nowMillis 当前墙钟。由调用方给而不是内部读时钟：判定里有 90 秒宽限，
     *   测试必须能瞬间跨过它。
     */
    fun onCarrier(state: CarrierState?, nowMillis: Long) {
        val phase = linkPhaseOf(state)
        when (val action = link.onCarrier(phase, nowMillis)) {
            is LinkAction.Silent -> Unit
            is LinkAction.Warn -> deliverLinkWarning(action.phase, nowMillis)
            // 之前提醒过「收不到了」，现在连回来了：换成一条「已经连回来」而不是
            // 直接撤掉。撤掉的话用户只看到通知莫名消失，无从判断是恢复了、
            // 还是自己不小心划掉了——而这两件事对他的行动含义完全不同。
            is LinkAction.Clear -> center.postLink(
                offline = false,
                title = NoticeText.onlineTitle(),
                text = NoticeText.onlineText(),
            )
            is LinkAction.Settle -> Unit
        }
        // 补账与「撤提醒」是两条独立的出口：断 30 秒没到宽限就恢复的，
        // 上面拿到的是 `Silent`（没发过警告所以没得撤），但事件照样丢了、照样要补。
        if (phase == LinkPhase.Up && link.takeSettle()) settleMissedOutcomes()
    }

    /** 发「连不上 / 需要重新配对」的提醒。 */
    private fun deliverLinkWarning(phase: LinkPhase, nowMillis: Long) {
        if (phase == LinkPhase.Unpaired) {
            center.postLink(
                offline = true,
                title = NoticeText.unpairedTitle(),
                text = NoticeText.unpairedText(),
            )
            return
        }
        val since = link.downSinceMillis() ?: nowMillis
        val seconds = ((nowMillis - since) / 1000L).coerceAtLeast(0L)
        center.postLink(
            offline = true,
            title = NoticeText.offlineTitle(),
            text = NoticeText.offlineText(seconds),
        )
    }

    /**
     * 重连之后补账：把断线期间正在跑的会话逐个查一遍结局。
     *
     * ## 为什么不只是「重发那些丢失的通知」
     * 断线期间的事件是**永久丢了**（旁路是 `replay = 0` + `DROP_OLDEST`），
     * 补不回来。能补的是**结果**：那些会话现在是什么状态。所以这里不重放事件，
     * 而是对每个「断线前在跑」的会话重新查一次 `turn/end`。
     *
     * ## 为什么只补「断线前在跑」的
     * 没在跑的会话没有正在进行的一轮，结局早就在账上了。`edge` 里记着跑没跑完
     * （[CompletionEdge.runningIds]），正好就是这份名单。
     */
    private fun settleMissedOutcomes() {
        val running = edge.runningIds()
        if (running.isEmpty()) return
        for (sessionId in running) {
            scope.launch { postSettledOutcome(sessionId) }
        }
    }

    /**
     * 补账路径的结局查询：与 [postOutcome] 的区别是**必须确认没有轮次开着**。
     *
     * 分开写而不是给 [postOutcome] 加参数：即时路径的语义是「结束是既定事实」，
     * 补账路径是「结束只是可能」；两条判断一旦合并，后来者很难分清哪个分支在防谁。
     */
    private suspend fun postSettledOutcome(sessionId: String) {
        // 与 [postOutcome] 相反的顺序：**先判门控，再查结局**。
        //
        // 补账路径什么账都还没记（连结局都没查），所以 Skip 时直接走人最干净：
        // 不留任何「已报」痕迹，将来真有事件再来时还能正常报。
        //
        // 不能像原先那样「先 `decide` 再判门控」：`decide` 会写 `postedSeq`，
        // 而门控 Skip 时什么都不发——用户在后台期间这一轮跑完了、他此刻正看着这个
        // 会话（所以不发），但他随后切到后台时 `running` **不会再翻转第二次**，
        // 这次结局就永久丢了。这与 [postOutcome] 里 A4 那条是同一类错误。
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
        val decision = outcomeMutex.withLock {
            val probe = fetchLastTurn?.let { fetch ->
                withTimeoutOrNull(OUTCOME_QUERY_TIMEOUT_MS) { fetch(sessionId) }
            }
            // ⚠️ 补账路径**查不到就不报**，与即时路径相反。
            //
            // 即时路径知道「这一轮确实停了」（running 翻转是事实），查不到结局只是
            // 不知道**怎么**停的，所以退回「任务结束」是诚实的。补账路径什么都不知道：
            // 它只有一份「断线前记着在跑」的名单。断线期间那个会话可能已经跑完、
            // 已经被删除（`Removed` 事件也在断线里丢了，`edge` 无从得知）、或者
            // 根本是另一台机器上的旧账。此时查不到结局，凭空报一条「任务结束 · xxx」
            // 会让用户点进一个**不存在的会话**。
            if (probe == null) {
                OutcomeDecision.Skip
            } else if (!probe.settled) {
                // 还有一轮开着：结论不成立，不发。
                OutcomeDecision.Skip
            } else {
                outcomes.decide(sessionId, probe.lastEnd, name)
            }
        }

        when (decision) {
            is OutcomeDecision.AlreadyNotified -> Unit
            is OutcomeDecision.Skip -> Unit
            is OutcomeDecision.Notify -> deliverUnlessCovered(sessionId, name, decision.notice, delivery)
        }
    }

    /** 用首页那份会话列表播种标题表（`session/list` 每次刷新调一次）。 */
    fun rememberSessions(items: List<SessionSummary>) {
        if (items.isEmpty()) return

        // ## 为什么标题与登记表必须同源（R059-B011 冷启动边界）
        //
        // 服务端**不为已存在的会话补发 `api-session/added`**（真机实测：App 冷启动后
        // logcat 里零条 added，直到新建会话才出现一条）。于是冷启动后
        // `subagents` 登记表是空的，而此时子代理的 `status` 帧一到，
        // [SubagentRegistry.isSubagent] 查不到就按设计返回 false（当成主会话）——
        // B011 在这个窗口里复发：子代理跑完也弹一条「任务结束」。
        //
        // 而全量列表里 `origin` 是有的（实测 66 条里 20 条 `subagent`）。
        // 所以这里把同一份 `List<SessionSummary>` 同时喂给两张表：
        // 标题表用来写「任务结束 · 某某会话」，登记表用来判该不该发。
        // 分成两次调用就会出现「标题表更新了、登记表没跟上」这种半截状态。
        //
        // 标题为空的条目不影响登记表：[SessionNameTable.remember] 自己会跳过空标题，
        // 而子代理的 title 恰好全是空的——这不是 bug，不要把它们当成“没收到”。
        names.remember(items.map { it.sessionId to it.title })
        subagents.remember(items)
    }

    /** 用户点进某个会话时，把它挂着的那条完成通知撤掉（他已经看见了）。 */
    fun onSessionOpened(sessionId: String) {
        viewingSessionId = sessionId
        // 用户已经亲眼看到这条会话了，它那条「任务结束」不必再补（第三轮审查 P3）：
        // 点开时若胶囊还覆盖着，flush 会被 `coversDone` 跳过；等胶囊让位后照样补发，
        // 而用户其实早就看过了。与 `Removed` 分支对齐。
        coverage?.dropDeferred(sessionId)
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
                // 顺手把「是不是子代理」记下来：`status`/`error` 帧不带 origin，
                // 只有这里能拿到（R059-B011）。
                summary?.let { subagents.remember(it) }
                summary?.let { edge.observe(it.sessionId, it.running) }
            }

            is SessionNotification.Removed -> {
                // 会话被删了：撤掉它挂着的通知，并把它从两本账里清掉——
                // 不清的话，同 id 会话被重建时边沿会判错（旧账说它在跑）。
                center.cancelDone(notification.sessionId)
                edge.forget(notification.sessionId)
                names.forget(notification.sessionId)
                outcomes.forget(notification.sessionId)
                subagents.forget(notification.sessionId)
                // 会话被删了，欠着的那条也不用补了——补出去只会是一条点不开的
                // 僵尸通知（R062-B019）。
                coverage?.dropDeferred(notification.sessionId)
                // 审批通知不在这里撤：`eventId` 与 `sessionId` 没有稳定映射，
                // 硬要按会话找会误撤别人的。它们会在 `syncApprovals` 下一次对账时
                // 因「不在 approvals 列表里」被统一撤掉。
            }

            is SessionNotification.Status -> {
                val edgeHit = edge.observe(notification.sessionId, notification.running)
                // 子代理跑完不发「任务结束」（R059-B011）。
                //
                // 为什么仍然喂 `edge`：边沿账必须跟真实状态同步，否则子代理的
                // running 翻转会在账里留一条永不过期的 `true`，将来这个 id 若被
                // 复用就会漏掉一次真正的完成。
                if (subagents.isSubagent(notification.sessionId)) return
                if (edgeHit == true) {
                    // 不再直接发「任务结束」：先查这一轮的结局。查不到才退回旧口径
                    // （会话确实停了，只是我们看不到它是怎么停的）。
                    launchOutcome(notification.sessionId, errorMessage = null)
                }
            }

            is SessionNotification.Error -> {
                // 失败是**独立的**语义：它不是「跑完了」，而是「这一轮没跑成」。
                // 服务端在 error 帧之后就未必再发 status(false)，所以这里直接发，
                // 不依赖边沿——但会顺手把 edge 标成不在跑，避免紧随其后的
                // status(false) 再发一条「任务结束」（同一件事响两次）。
                edge.observe(notification.sessionId, false)
                // 子代理失败同样不通知（R059-B011）：主会话那一轮会把失败
                // 一并报出来，两处都弹就是一次故障响两次。
                if (subagents.isSubagent(notification.sessionId)) return
                // 仍然交给结局查询：若这一轮有 `turn/end` 且带具体错误（429 之类），
                // 用它比用 error 帧的一句话更准；查不到才用 error 帧的原文。
                launchOutcome(notification.sessionId, errorMessage = notification.message)
            }

            is SessionNotification.Activity -> {
                // 「有活动」只影响列表排序与红点，不产生通知。显式列出而不写 else，
                // 是为了将来加第五种事件时编译能提醒到这里。
            }

            is SessionNotification.AccountExpired ->
                onAccountProblem(AccountProblem.SessionExpired)

            is SessionNotification.AccountSignIn ->
                onAccountProblem(AccountProblem.ModelSignIn)

            is SessionNotification.CredentialsChanged -> {
                // 凭据变了：之前那条「去重新登录」已经过期。撤掉它，并清账，
                // 否则下次再失效时用户会一声不吭。
                if (accounts.onCredentialsChanged()) {
                    center.postAccount(
                        title = NoticeText.accountFixedTitle(),
                        text = NoticeText.accountFixedText(),
                        resolved = true,
                    )
                }
            }
        }
    }

    /**
     * 账号侧问题：账号授权失效 / 某个模型要重新登录（R056 缺口 4）。
     *
     * 这两条都是**整个桌面端**级的，不属于任何会话，所以不走 [NoticeGate]：
     * 门控里那些条件（前台、正看着这个会话）对它没有意义——用户正在 App 里看着
     * 某个会话，也不代表他知道账号已经掉了。
     *
     * 唯一该判的是权限与频道：没给通知权限、或用户把这个频道关了，就不发。
     */
    private fun onAccountProblem(problem: AccountProblem) {
        if (!accounts.onProblem(problem)) return
        val channel = NoticeChannels.DONE
        val delivery = NoticeGate.decide(
            visibility = NoticeVisibility(foreground = foreground),
            channelId = channel,
            permissionGranted = center.canPost(),
            channelEnabled = settings.effectiveEnabled(channel, center.isChannelEnabled(channel)),
        )
        if (delivery == NoticeDelivery.Skip) return
        when (problem) {
            AccountProblem.SessionExpired -> center.postAccount(
                title = NoticeText.accountExpiredTitle(),
                text = NoticeText.accountExpiredText(),
            )
            AccountProblem.ModelSignIn -> center.postAccount(
                title = NoticeText.accountSignInTitle(),
                text = NoticeText.accountSignInText(),
            )
        }
    }

    /**
     * 查这一轮的结局，然后按结局发通知（R056 缺口 5）。
     *
     * 为什么不在 [handle] 里同步做：`handle` 是事件收集协程里的普通函数，里面
     * 发一个网络请求会把后续所有事件一起卡住（审批、提问的通知都会迟到）。
     * 所以这里只启动一个协程，收集循环立刻返回。
     *
     * 查询失败（超时 / 断连 / 服务端拒绝）一律当「查不到」，退回 R056 首版的
     * 口径：error 帧照着发失败、否则发「任务结束」。**不因为查不到就不发**——
     * 用户已经知道那一轮停了，静默才是最坏的结果。
     */
    private fun launchOutcome(sessionId: String, errorMessage: String?) {
        scope.launch { postOutcome(sessionId, errorMessage) }
    }

    private suspend fun postOutcome(sessionId: String, errorMessage: String?) {
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
        // ⚠️ 门控只决定「发不发」，**不能顺手跳过记账**。
        //
        // 原来这里在 `delivery == Skip` 时直接 return，连 `outcomes.noteError` 都不做。
        // 后果：App 在前台、用户正看着这个会话时（最常见的路径——用户刚发完消息就
        // 盯着等），这一轮的结局永远不会进账；而他随后切到后台时，`running` **已经
        // 不会再翻转第二次**，于是「这一轮被截断了」「这一轮被 blocked 了」这类提示
        // 一条都收不到——只有下次有新的事件时才可能被补上。
        //
        // 仍然要省下网络流：Skip 时不去查（查询只为拼通知文案），但要记账。
        val name = NoticeText.sessionName(names.titleOf(sessionId), sessionId)
        val decision: OutcomeDecision? = outcomeMutex.withLock {
            if (errorMessage != null) outcomes.noteError(sessionId, errorMessage)
            // 注定不发通知时不再去查结局（省一条 `session/follow`），错误原文已经
            // 记进账里了（上面那行 noteError），下一条真正要发的通知会把它带上。
            //
            // 不在这里调 `decide`：没查到结局的 `decide` 会往账上记一笔「已就这次
            // 翻转发过通知」，而我们并没有发——那会把之后真正该发的那条压掉。
            if (delivery == NoticeDelivery.Skip) return@withLock null
            val probe = fetchLastTurn?.let { fetch ->
                withTimeoutOrNull(OUTCOME_QUERY_TIMEOUT_MS) { fetch(sessionId) }
            }
            // ⚠️ 即时路径也要看 `settled`，只在**窗口末尾确实有一轮开着**时才不认这条结局。
            //
            // 原先这里无条件用 `probe?.lastEnd`，理由是「从 running 已翻转进来，结束是
            // 既定事实」。这句话对「跑完了」成立，对 `api-session/status` 的**全部语义**
            // 不成立：服务端是 `agent.status = phase.kind in (idle, maintenance) ? idle :
            // running` 的翻转，进入 `maintenance` 阶段同样会发一条 `running = false`，
            // 而那一轮并没有结束。更常见的是竞态——`running=false` 到达时用户已经又发了
            // 一条，新一轮 `turn/start` 已在窗口里，此时拿到的 `turn/end` 属于**上一轮**。
            //
            // 那种情况下报「任务结束」是把正在跑的任务说成跑完了；更糟的是上一轮的结局
            // 若是 `Aborted(USER)` 会被 `outcomeNotice` 判成 `Skip`，于是这一轮真正该发的
            // 通知**一条都没有**。
            val found = if (probe != null && probe.settled) probe.lastEnd else null
            outcomes.decide(sessionId, found, name)
        }

        when (decision) {
            null -> Unit
            is OutcomeDecision.AlreadyNotified -> Unit
            is OutcomeDecision.Skip -> Unit
            is OutcomeDecision.Notify -> deliverUnlessCovered(sessionId, name, decision.notice, delivery)
        }
    }

    /**
     * 投递前先问一句：胶囊是不是已经在替这条会话说话了（R062-B019）。
     *
     * 只有 [OutcomeNotice.Done]（跑完 / 跑挂）会被压掉，[OutcomeNotice.Attention]
     * （被截断 / 被 blocked / 被中断）一律照发——那几条讲的是「这一轮没跑成」，
     * 胶囊上只有一个「出错」词，装不下原因，而原因恰恰是用户唯一需要的东西。
     *
     * 压掉**必须记账**（[CapsuleCoverageRegistry.deferUnlessCovered]）：胶囊只有一个展示位，
     * 下一秒可能就被别的会话的审批抢走，那时这条会话从胶囊上消失，
     * 而它的完成通知从头到尾没发过——那是比重复推送严重得多的「丢通知」。
     */
    private fun deliverUnlessCovered(
        sessionId: String,
        sessionName: String,
        notice: OutcomeNotice,
        delivery: NoticeDelivery,
    ) {
        val registry = coverage
        if (notice is OutcomeNotice.Done && registry != null) {
            // 判定与记账在账本内部一步完成（第二轮审查 P2-2）：分成两步的话，
            // 中间胶囊刚好让位（用户划掉就会），这条欠账会落在「已经跑完的那一轮
            // flushDeferred」之后，等不到下一次 emit，永久丢失。
            if (registry.deferUnlessCovered(sessionId, DeferredDone(notice.failed, notice.reason, sessionName))) {
                // 上一轮可能已经在通知栏里留了一条同 id 的「任务结束」（同一会话固定 id、
                // 原地替换）。既然胶囊接管了，那条也该跟着撤——否则用户看到的正是
                // 「胶囊说已完成 + 通知栏还说已完成」这副重复景象。
                center.cancelDone(sessionId)
                return
            }
        }
        deliver(sessionId, sessionName, notice, delivery)
    }

    /**
     * 胶囊正覆盖某个会话的结束态时，把通知栏里那条同 id 的完成通知撤掉。
     *
     * ## 为什么需要（第四轮审查 P3-1）
     * 这是「先发后接管」那一半：`Status` 帧先到、通知先落地，随后 `session/list`
     * 才刷新、胶囊才翻成已完成。反过来（胶囊先到）的顺序由
     * [deliverUnlessCovered] 挡住，但挡不住已经发出去的那条。
     * 两条并存正是 B019 要消灭的重复。
     *
     * `cancelDone` 是幂等的，覆盖期间每轮重算都调一次也不会出问题；
     * 但只在 [CapsuleCoverage.coversDone] 为真时才调——否则会把用户
     * 还没看的完成通知误撤。
     */
    private fun withdrawCoveredDone(state: CapsuleCoverage) {
        val sessionId = state.sessionId ?: return
        if (!state.coversDone(sessionId)) return
        center.cancelDone(sessionId)
    }

    /**
     * 把「胶囊不再覆盖」的那几条欠账补发出去。
     *
     * ## 为什么要重新过一遍门控
     * 压掉时的门控结论已经过期了：那之后用户可能打开 App、可能正看着这条会话。
     * 直接照搬当时的 `Alert` 会在用户盯着屏幕时弹一条横幅。所以这里重新算一次
     * [NoticeGate]；`Skip` 就直接丢掉欠账而不是留到下次——留着只会在某个
     * 不相干的时刻突然弹出来，比不发更糟。
     */
    private suspend fun flushDeferred() {
        val registry = coverage ?: return
        for (sessionId in registry.deferredIds()) {
            // 每轮重读而不是循环外取一次快照（第二轮审查 P3-1）：
            // 循环体里的 `postDone` 是 binder 调用，期间胶囊可能已经重新覆盖了
            // 后面某条会话。用旧快照会把它也补发出去——正是本 patch 要消灭的重复推送。
            if (registry.state.value.coversDone(sessionId)) continue
            val pending = registry.takeDeferred(sessionId) ?: continue
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
            if (delivery == NoticeDelivery.Skip) continue
            // 优先用最新的标题（可能改名了），回落记账时那个（第四轮审查 P3-4）。
            val name = NoticeText.sessionName(
                names.titleOf(sessionId) ?: pending.sessionName,
                sessionId,
            )
            // 摘账在前、投递在后，所以投递失败必须把账**放回去**（第三轮审查 P2-1）。
            // `NotificationCenter.postDone` 末尾是裸 `nm.notify`，权限在 `canPost()`
            // 之后被收回就会抛 `SecurityException`。异常被外层 `runCatching` 吞掉之后，
            // 这条账已经摘了却没发出去 —— 胶囊早已让位、通知栏也没有，
            // 是这个 patch 里唯一「彻底无信息」的路径。放回去至少下次还有机会。
            val ok = runCatching {
                center.postDone(
                    sessionId = sessionId,
                    sessionName = name,
                    failed = pending.failed,
                    reason = pending.reason,
                    silent = delivery == NoticeDelivery.Silent,
                )
            }.onFailure { Log.w(TAG, "flushDeferred post failed", it) }.isSuccess
            if (!ok) registry.deferDone(sessionId, pending)
        }
    }

    /** 把一条结局按门控结果投递出去。 */
    private fun deliver(
        sessionId: String,
        sessionName: String,
        notice: OutcomeNotice,
        delivery: NoticeDelivery,
    ) {
        val silent = delivery == NoticeDelivery.Silent
        when (notice) {
            // 走到这里的情况：`decide` 已把 `OutcomeNotice.Skip` 转成
            // `OutcomeDecision.Skip`，所以这一支正常情况下到不了。保留它是因为
            // `when` 必须穷尽，且 [deliver] 是 public 之外唯一入口、将来若有人
            // 绕过 `decide` 直接投递，这里静默反而比崩掉安全（通知层不该抛）。
            is OutcomeNotice.Skip -> Unit
            is OutcomeNotice.Done ->
                center.postDone(sessionId, sessionName, notice.failed, notice.reason, silent)
            is OutcomeNotice.Attention ->
                center.postAttention(sessionId, notice.title, notice.text, silent)
        }
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

    /**
     * 同步提问列表：新出现的发通知，消失的撤通知。
     *
     * 与 [syncApprovals] 是同一套双向对账、同一个理由（`questions` 也是 `StateFlow`，
     * 重连后可能整体换一批，只判新增会留下撤不掉的旧通知）。**分开两本账**而不是
     * 合并成一个「待回答请求」列表：审批与提问的 `eventId` 由服务端各自生成，
     * 混在一本里会让两边的环容量互相挤占，而且撤通知时要按不同的 id 段撤。
     *
     * 为什么提问必须发通知：`user-questions/request` 与 `approval/request` 在
     * `API_REMOTE_FORWARDED_EVENTS` 里**都是 `waterfall`**，服务端抛给第一个愿意
     * 回答的远程监听者然后等。没人答就是 `NO_PROVIDER`，任务直接失败——而那个失败
     * 原因（`no user-questions answerer accepted the request`）对用户毫无信息量。
     */
    private fun syncQuestions(list: List<QuestionRequest>) {
        val alive = list.map { it.eventId }.toHashSet()
        val gone = notifiedQuestionsSet - alive
        for (eventId in gone) {
            center.cancelQuestionByEventId(eventId)
            forgetNotifiedQuestion(eventId)
        }
        for (request in list) {
            if (request.eventId in notifiedQuestionsSet) continue
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
            markNotifiedQuestion(request.eventId)
            center.postQuestion(
                eventId = request.eventId,
                sessionId = request.agentId,
                first = request.questions.firstOrNull(),
                count = request.questions.size,
            )
        }
    }

    private fun markNotifiedQuestion(eventId: String) {
        if (!notifiedQuestionsSet.add(eventId)) return
        notifiedQuestions.addLast(eventId)
        while (notifiedQuestions.size > NOTIFIED_RING) {
            notifiedQuestionsSet.remove(notifiedQuestions.removeFirst())
        }
    }

    private fun forgetNotifiedQuestion(eventId: String) {
        if (!notifiedQuestionsSet.remove(eventId)) return
        notifiedQuestions.remove(eventId)
    }

    /** 清空「已通知过」的环（换连接时用，见 [attach]）。 */
    private fun forgetAllNotified() {
        notifiedApprovals.clear()
        notifiedApprovalsSet.clear()
        notifiedQuestions.clear()
        notifiedQuestionsSet.clear()
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
        /** 日志标签。补发失败必须留痕：静默失败的「少一条通知」最难被发现。 */
        const val TAG = "DshNotice"

        /** 已通知过的 `eventId` 环容量。 */
        const val NOTIFIED_RING = 64

        /**
         * 查一次结局最多等多久。
         *
         * 为什么不设更长：这条查询发生在「用户已经被通知要响一声」的路径上，
         * 卡住只会让通知迟迟不来。4 秒够一次 `session/follow` 的首帧到达
         * （局域网内通常几十毫秒），超时就按「查不到」处理——退回旧口径照样
         * 会有一条「任务结束」，只是说不出具体是怎么结束的。
         */
        const val OUTCOME_QUERY_TIMEOUT_MS = 4_000L
    }
}
