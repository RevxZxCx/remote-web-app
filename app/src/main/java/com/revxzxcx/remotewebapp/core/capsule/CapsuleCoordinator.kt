package com.revxzxcx.remotewebapp.core.capsule

import android.util.Log
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.notify.SubagentRegistry
import com.revxzxcx.remotewebapp.core.events.RemoteEvents
import com.revxzxcx.remotewebapp.core.events.QuestionRequest
import com.revxzxcx.remotewebapp.core.session.AskQuestion
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TodoList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 把三路数据合成一条胶囊（R059）。
 *
 * ## 为什么是「拉平到一个 recompute」而不是各订阅各发
 * 真机实测（调研文档 3.7-A）：多条 promoted 通知不会变成多个胶囊，系统只给
 * **一个展示位**。所以任何一路数据变化后都必须**重新选举一次焦点**，
 * 再**原地更新同一条通知**。若各订阅各发，会出现「审批把执行中顶掉、
 * 一秒钟后执行中又把审批顶回来」的闪烁——而 ColorOS 轻打扰恰恰会把频繁变动的
 * 胶囊缩成气泡，且气泡不会自动展开。
 *
 * ## 数据来源与各自的缺口（必须如实处理，不能编）
 * | 来源 | 给什么 | 缺什么 |
 * |---|---|---|
 * | `session/list`（[setSessions]） | 全部会话的运行态、未读标记 | **没有 todo** |
 * | `approvals` / `questions` | 谁在等人、等的是什么 | 与 todo 无关联 |
 * | 正在看的会话（[setLive]） | todo 进度、汇报正文 | 只有这一个会话有 |
 *
 * 所以：**只有正在看的那个会话能画出进度条**。后台会话选成焦点时，
 * 卡片只有汇报行和计时器，没有进度条——这是诚实的降级，
 * 比编一个假的 `n/m` 好（用户一旦发现进度条是假的，整条胶囊都不可信了）。
 */
internal class CapsuleCoordinator(
    private val scope: CoroutineScope,
    private val center: CapsuleCenter,
    /**
     * 子代理登记表（R059-B012）。
     *
     * 与通知层**共用同一份实例**（见 `AppContainer.subagentRegistry`）：
     * 两处各记一本的话，会出现「通知说这是子代理、胶囊却把它当选民」这种
     * 自相矛盾，而那种矛盾在真机上表现为「有时多出一个会话、有时不多」。
     *
     * 默认 `null` 便于单测：绝大多数用例只关心焦点选举，不关心子代理。
     */
    private val subagents: SubagentRegistry? = null,
) {

    /** 全量会话摘要，由首页在每次 `session/list` 落地后推进来。 */
    @Volatile
    private var sessions: List<SessionSummary> = emptyList()

    /** 正在等的审批，**保持到达顺序**（FIFO 防饥饿，见 [CapsuleFocus.elect]）。 */
    @Volatile
    private var approvals: List<ApprovalRequest> = emptyList()

    /** 正在等的提问，同上。 */
    @Volatile
    private var questions: List<QuestionRequest> = emptyList()

    /** 用户正看着的会话（胶囊是「我不在 App 里」的替代品，看着时不抢镜）。 */
    /**
     * 已经 [detach]。去抖任务醒来时必须先看这个标志。
     *
     * 反例：`detach()` 里 `recomputeJob?.cancel()` 只能取消**已经排队**的那一个，
     * 而 `scheduleRecompute()` 在 `delay(300)` 之后无条件调 `recompute()`。
     * 只要 UI 在 detach 之后又发来一次 `setSessions`/`setLive`（`CapsuleBridge`
     * 的 StateFlow 订阅就会这么做），胶囊就会被重新发出来——
     * 用户明明已经断开连接，状态栏却还挂着一条。
     */
    @Volatile
    private var detached = false

    @Volatile
    private var viewingSessionId: String? = null

    /**
     * 正在看的那个会话的实时快照。
     *
     * 只有它有 todo 与汇报正文（见类 KDoc 的表格）。`null` 表示没有会话在前台。
     */
    @Volatile
    private var live: LiveSnapshot? = null

    /** 上一轮焦点，用于粘滞（第 3 层）。 */
    private var previousSessionId: String? = null

    /** 当前这条胶囊是什么时候开始算的（驱动 SystemUI 计时器）。 */
    private var startedAt: Long = 0L

    /** 记住「这一轮已经为哪些会话起过算」，焦点不变时不重置计时器。 */
    private var startedForSession: String? = null

    private var recomputeJob: Job? = null

    /** 保护 [viewingSessionId] 与 [live] 这对必须同时成立的状态（R059 审查 P3-9）。 */
    private val stateLock = Any()

    /** 上一次发布是否成功。把「发不出」的日志从每次降级成每状态转换一次。 */
    private var lastPostOk: Boolean = true

    /**
     * 推入全量会话摘要。
     *
     * 由首页在 `session/list` 成功后调用。**不在这里自己拉**：胶囊要跟着列表的
     * 权威口径走（列表上红点用的也是这份数据），自己再拉一份会出现
     * 「列表红、胶囊绿」的自相矛盾。
     */
    fun setSessions(items: List<SessionSummary>) {
        detached = false
        // 子代理不是会话（R059-B012）。
        //
        // `session/list` 是**全量**，里面既有主会话也有它们派生的子代理
        // （`origin == "subagent"`，实测一份列表里 47 条有 20 条是子代理）。
        // 不滤掉的话，「一个对话 + 它的子代理一起跑」会显示成「执行中 2 个」，
        // 而且子代理跑完还会让胶囊切到另一个会话——用户会以为多出了一个任务。
        //
        // 在**入口**过滤而不是在选举里过滤：`sessions` 同时被焦点选举和
        // `countOthersRunning` 用，只在一处过滤就会漏掉另一处的计数。
        // 两道判据：摘要自带 `origin`（`session/list` 全量与 `added` 帧都有），
        // 以及旁路登记表（缓存里流出来的条目可能丢了 origin 字段，见 SubagentRegistry KDoc）。
        //
        // ## 唯一例外：正在等用户处理的那几个必须留下（第三轮审查 P3-2）
        //
        // 子代理会向用户发起审批与提问（实测：那三个选项的提问就是从子代理那条路上来的）。
        // 如果把它们一并滤掉，`CapsuleFocus.elect` 的第一层 `byId[id]?.let { ... }`
        // 找不到人就直接落到下一层——**卡片既不显示这条待处理，也不提示任何异常**。
        // 用户被一条永远不会出现的确认卡住，任务就那么停着，谁都不知道为什么。
        //
        // 这不是「极端场景」：只要那次会话派生了子代理、并让子代理去申请权限，就 100% 复现。
        // 而它恰好落在本轮要修的同一类问题上（子代理数据混进主会话口径），
        // 只是方向相反——前面是「不该显示的显示了」，这里是「该显示的没显示」。
        val waiting = pendingSessionIds().toSet()
        sessions = items.filterNot { summary ->
            val sub = summary.isSubagent || subagents?.isSubagent(summary.sessionId) == true
            sub && summary.sessionId !in waiting
        }
        scheduleRecompute()
    }

    /** 推入审批列表（来自 `RemoteEventsClient.approvals`）。 */
    fun setApprovals(items: List<ApprovalRequest>) {
        // 新的待处理事项必须能顶掉用户上一次的手动隐藏，否则一次误划会
        // 让后续所有审批静默（见 CapsuleDismissal）。
        if (items.any { request -> approvals.none { it.eventId == request.eventId } }) {
            CapsuleDismissal.clear()
        }
        approvals = items
        scheduleRecompute()
    }

    /** 推入提问列表（来自 `RemoteEventsClient.questions`）。 */
    fun setQuestions(items: List<QuestionRequest>) {
        if (items.any { request -> questions.none { it.eventId == request.eventId } }) {
            CapsuleDismissal.clear()
        }
        questions = items
        scheduleRecompute()
    }

    /** 用户进/出某个会话（`null` = 回到列表或设置页）。 */
    fun setViewingSession(sessionId: String?) {
        // `viewingSessionId` 与 `live` 是一对必须一起成立的状态，而这两个 setter
        // 由 `watchCapsule` 里两条独立的协程驱动，可能落在不同线程上（R059 审查 P3-9）。
        // 只靠 @Volatile 的话，`live` 的检查与赋值之间有一个窗口：
        // 刚为 A 推入的快照可能被并发的「切到 B」清掉，或反过来留在错误的会话上——
        // 这正是「进度条偶尔卡在上一段」那种极难复现的现象的来源。
        synchronized(stateLock) {
            if (viewingSessionId == sessionId) return
            viewingSessionId = sessionId
            // 离开会话时立刻丢掉它的 todo：那是「我看着时才成立」的数据，
            // 留着会让后台卡片显示一个已经过期的进度。
            if (live?.sessionId != sessionId) live = null
        }
        scheduleRecompute()
    }

    /**
     * 推入正在看的会话的实时快照。
     *
     * 由 `ChatViewModel` 在 todo/汇报变化时调用。**只在这个会话真的在前台时**有效：
     * 后台会话拿不到这些数据（见类 KDoc），传进来的会被忽略。
     *
     * ## 为什么校验放在这里而不是只靠调用方（R059 审查 P2-5）
     * `CapsuleBridge.publishLive` 已经有 `viewing` 判断，但它是**另一层**。
     * 第一版这里无条件赋值，于是任何一条绕过 Bridge 的路径（当时真有一条
     * `AppContainer.publishLiveForCapsule`）都能把 A 会话的速度塞给 B。
     * 后果不是崩，而是 `stateOf` 里 `phase` 判错——「看着 A 却按 B 的 running
     * 状态显示已完成」，这类错比崩溃更难查。把校验下沉到唯一的状态入口，
     * 调用方就无法绕过。
     */
    fun setLive(snapshot: LiveSnapshot?) {
        // 不是当前正在看的会话，直接丢弃（null 例外：那表示「清掉」，任何会话都该生效）。
        synchronized(stateLock) {
            if (snapshot != null && snapshot.sessionId != viewingSessionId) return
            live = snapshot
        }
        scheduleRecompute()
    }

    /** 连接断开 / 关闭胶囊功能时撤掉并清状态。 */
    fun detach() {
        detached = true
        recomputeJob?.cancel()
        recomputeJob = null
        // `CapsuleDismissal` 是进程级单例，`detach()` 不清它就会出现
        // 「划掉胶囊 → 断连 → 连另一台机器，那台有会话在跑」这条路径上全程静默：
        // recompute 每次都命中 `dismissed && pending == None` 直接 cancel，
        // 用户既看不到胶囊也不知道为什么，直到新机器冒出第一个审批/提问才恢复。
        // 与 CapsuleBridge.reset() 是同一类残留，必须一起清。
        CapsuleDismissal.clear()
        sessions = emptyList()
        approvals = emptyList()
        questions = emptyList()
        live = null
        viewingSessionId = null
        previousSessionId = null
        startedForSession = null
        center.cancel()
    }

    /**
     * 合并短时间内的多次变化。
     *
     * 300ms：`session/list` 一次返回几十条、每条都可能触发 Status/Activity 事件，
     * 不合并的话计算与 `notify()` 会连成串。但**不能太长**——审批是 waterfall，
     * 用户晚 300ms 看到是可以接受的，晚 3 秒就可能已经超时了。
     */
    private fun scheduleRecompute() {
        if (detached) return
        recomputeJob?.cancel()
        recomputeJob = scope.launch {
            delay(DEBOUNCE_MS)
            recompute()
        }
    }

    /** 立刻重算（单测直接调它，跳过 debounce）。 */
    internal fun recompute() {
        val pendingIds = pendingSessionIds()
        val focus = CapsuleFocus.elect(
            candidates = sessions,
            pendingSessionIds = pendingIds,
            viewingSessionId = viewingSessionId,
            previousSessionId = previousSessionId,
        )

        if (focus == null) {
            previousSessionId = null
            startedForSession = null
            center.cancel()
            return
        }

        // 焦点变了才重置计时器。同一条会话原地刷新时必须沿用原起点，
        // 否则计时器会每 300ms 归零——那是比不显示更糟的「在动但没意义」。
        if (startedForSession != focus.sessionId) {
            startedForSession = focus.sessionId
            startedAt = System.currentTimeMillis()
        }
        previousSessionId = focus.sessionId

        val others = CapsuleFocus.countOthersRunning(sessions, focus.sessionId)
        val state = stateFor(focus.sessionId, focus.title, others, phaseOf(focus))
        if (!state.visible) {
            center.cancel()
            return
        }

        // 用户手动划掉过，且没有新的待处理事项：尊重他的选择，不再自动弹回。
        // 但有待处理时必须出现——那是「任务卡住了」的唯一提示。
        if (CapsuleDismissal.isDismissed() && state.pending == PendingKind.None) {
            center.cancel()
            return
        }

        // 发布失败不能静默（R059 审查 P3-8）：CapsuleCenter.post 明确约定
        // 「返回 false 时调用方不该认为已经在显示了」，而这里原本把布尔值丢了。
        // 真机上表现为「胶囊该出现却没出现」，没有任何线索可查。
        // 只在「从能发变成发不出」时打一条（第三轮审查 P3-1）。
        //
        // `recompute()` 由 300ms 去抖驱动，todo 流式更新会频繁触发；而 `post()`
        // 在用户没开「流体云显示实时活动」时恒返回 false——那是个**长期**状态。
        // 无条件打日志的话，一次长任务能刷出几百行一模一样的内容，
        // 把真正有用的线索冲掉，而用户根本不会去看 logcat。
        val posted = center.post(state, actionsFor(focus.sessionId))
        if (!posted && lastPostOk) {
            Log.w(
                CapsuleCenter.TAG,
                "capsule post failed: session=${focus.sessionId} pending=${state.pending}",
            )
        }
        lastPostOk = posted
    }

    /**
     * 有待处理事项的会话 id，**两类交错排列**。
     *
     * ## 为什么是交错而不是「先排完审批再排提问」（R059 审查 P2-1）
     * 第一版写成两段 `forEach`，注释却写着「否则审批永远压着提问会让提问饿死」——
     * 实现和注释正好相反。只要挂着一条未处理的审批，新来的提问就永远选不上焦点，
     * 而提问是有超时的，超时后任务直接失败。
     *
     * 这里**没有可靠的时间戳**可用：[ApprovalRequest] 与 [QuestionRequest] 都只有
     * `eventId`，不含到达时刻，拿 `hashCode` 冒充时间是自欺欺人。所以退一步，
     * 按「轮流取」交错排列——这不能保证严格 FIFO，但能保证**任何一类都不可能被
     * 另一类无限期压住**，这正是注释承诺的那件事。真正的严格 FIFO 需要上游在事件里
     * 带上时间戳，那是协议层的事，本类不强求。
     */
    private fun pendingSessionIds(): List<String> =
        capsulePendingOrder(approvals, questions)

    /**
     * 给某个会话构造卡片内容。
     *
     * @param phase 由 [phaseOf] 从会话摘要算出，**不要**在这里再看 live 快照判相位：
     * 后台会话没有快照，旧写法会把「已经跑完」当成「还在跑」。
     */
    private fun stateFor(
        sessionId: String,
        title: String?,
        otherRunning: Int,
        phase: CapsulePhase,
    ): CapsuleState {
        // 待处理优先：它决定按钮，也决定用户在通知栏能做的最有价值的一件事。
        approvals.firstOrNull { it.agentId == sessionId }?.let { request ->
            return CapsuleContent.approval(
                sessionId = sessionId,
                title = title,
                // `displayReason` 是多语种字典（见 `ApprovalRequest` 的 KDoc）：
                // 取 zh，回落 en，再回落 reason。直接把 en 摆给中文用户就是「没翻译」。
                toolName = request.displayReason["zh"]
                    ?: request.displayReason["en"]
                    ?: request.reason,
                report = null,
                otherRunning = otherRunning,
                startedAt = startedAt,
            )
        }
        questions.firstOrNull { it.agentId == sessionId }?.let { request ->
            val first = request.questions.firstOrNull()
            return CapsuleContent.question(
                sessionId = sessionId,
                title = title,
                firstQuestion = first?.question,
                count = request.questions.size,
                otherRunning = otherRunning,
                startedAt = startedAt,
            )
        }

        // 没有待处理：走执行中/已完成/出错三态。
        val snapshot = live?.takeIf { it.sessionId == sessionId }
        return CapsuleContent.running(
            sessionId = sessionId,
            title = title,
            todos = snapshot?.todos,
            report = snapshot?.report,
            action = snapshot?.action,
            otherRunning = otherRunning,
            startedAt = startedAt,
            phase = phase,
            thinking = snapshot?.thinking == true,
        )
    }

    /**
     * 从会话摘要判断相位。
     *
     * 优先级：出错未读 > 在跑 > 完成未读 > 空闲。与
     * [com.revxzxcx.remotewebapp.core.session.statusOf] 的口径一致——
     * **两处不一致会出现「列表说完成了、胶囊说执行中」**。
     *
     * 为什么 `failureUnread` 排在 `running` 前面：失败也可能是「跑完了但失败」，
     * 用户需要知道的是失败，不是「它还在动」。
     */
    private fun phaseOf(summary: SessionSummary): CapsulePhase = when {
        summary.failureUnread -> CapsulePhase.Failed
        summary.running -> CapsulePhase.Running
        summary.completionUnread -> CapsulePhase.Done
        else -> CapsulePhase.Idle
    }

    /**
     * 卡片底部的按钮。
     *
     * 三态各自不同，且**总数不超过 3**（真机上限）：
     * - 待批准：勾 / 叉 两个纯图标（含义明确，不需要文字）
     * - 待回答：前两个选项的文字按钮（用户 m01450 明确要求，不用对错）
     * - 其余：一个「⋯」打开 App
     */
    private fun actionsFor(sessionId: String): List<CapsuleCenter.CapsuleAction> {
        approvals.firstOrNull { it.agentId == sessionId }?.let { request ->
            return listOf(
                CapsuleCenter.CapsuleAction(
                    label = null,
                    iconRes = com.revxzxcx.remotewebapp.R.drawable.ic_capsule_approve,
                    intentFactory = { ctx ->
                        CapsuleActionIntents.answer(ctx, request.eventId, allow = true)
                    },
                ),
                CapsuleCenter.CapsuleAction(
                    label = null,
                    iconRes = com.revxzxcx.remotewebapp.R.drawable.ic_capsule_reject,
                    intentFactory = { ctx ->
                        CapsuleActionIntents.answer(ctx, request.eventId, allow = false)
                    },
                ),
            )
        }

        questions.firstOrNull { it.agentId == sessionId }?.let { request ->
            val first = request.questions.firstOrNull()
            val options = first?.options.orEmpty().take(MAX_OPTION_ACTIONS)
            val callId = request.capsuleCallId()
            // 多题组**照样给选项按钮**——这条曾被第二轮审查建议关掉，但没有采纳。
            //
            // 审查者的理由是「UI 路径 `answerQuestionGroup` 要求整组答完才提交，
            // 胶囊只提交一道，两者矛盾」。但真机证据反驳了它：2026-11-09 21:48，
            // 一个 3 题组在胶囊上显示「修改 / 排查 / ⋯」，点第一个后卡片**原地
            // 更新成下一道题的「blue / green」**——服务端接受单条答案并把剩下的
            // 推回来。也就是说「答一道 → 下一道」是真的可用，不是矛盾。
            //
            // 再说用户 m01450 就是要「待回答显示前两个选项按钮」；为了一句文案
            // 的自洽把这个入口砍掉，等于拿需求换整齐。
            if (first != null && options.isNotEmpty()) {
                // 前两个选项 + 一个「⋯」进 App。第三个选项如果塞进来，用户就失去了
                // 「进 App 处理」的入口，而多选题与自定义输入本来就必须进 App。
                // 三个正好用满系统上限（真机实测第 4 个不渲染）。
                val optionActions = options.map { option ->
                    CapsuleCenter.CapsuleAction(
                        // 只截显示文案；提交给服务端的仍是 `option.label` 原值。
                        label = CapsuleContent.clip(option.label, OPTION_TITLE_MAX_CHARS),
                        iconRes = null,
                        intentFactory = { ctx ->
                            CapsuleActionIntents.answerOption(
                                ctx,
                                eventId = request.eventId,
                                callId = callId,
                                questionId = first.id,
                                label = option.label,
                            )
                        },
                    )
                }
                return optionActions + openAction(sessionId)
            }
            return listOf(openAction(sessionId))
        }

        return listOf(openAction(sessionId))
    }

    private fun openAction(sessionId: String): CapsuleCenter.CapsuleAction =
        CapsuleCenter.CapsuleAction(
            label = null,
            iconRes = com.revxzxcx.remotewebapp.R.drawable.ic_capsule,
            intentFactory = { ctx -> CapsuleActionIntents.open(ctx, sessionId) },
        )

    /**
     * 正在看的会话的实时快照。
     *
     * @param todos 任务列表（画进度条用）。
     * @param report 最近一次汇报（title 第 1 行）。
     * @param action 当前正在做的事（title 第 2 行）。
     */
    internal data class LiveSnapshot(
        val sessionId: String,
        val todos: TodoList?,
        val report: String?,
        val action: String?,
        /** 见 [CapsuleState.thinking]。 */
        val thinking: Boolean = false,
    )

    companion object {

        /** 合并窗口。见 [scheduleRecompute] 里的取舍说明。 */
        const val DEBOUNCE_MS: Long = 300L

        /**
         * 待回答态最多放几个选项按钮。
         *
         * 2 而不是 3：第三个位置留给「进 App」。多选题、带自定义输入的题、
         * 超过两个选项的单选题都必须进 App 才能正确处理，把三个位置全占了
         * 用户就无路可走。
         */
        const val MAX_OPTION_ACTIONS: Int = 2

        /**
         * 选项按钮上最多显示几个字。
         *
         * 真机 1080 宽、三个按钮各 268px。用 `probe.fc` 的 `mode=acts3` 逐档量过：
         *
         * | 字数 | 渲染结果 |
         * |---|---|
         * | 4 | 「代码修改」完整显示 |
         * | 5 | 「代码编」「代码修」——两侧已开始渐隐 |
         * | 6 | 「代码纟」——被切掉一截 |
         * | 8 | 「修改啊」——前缀全丢 |
         *
         * 系统做的是**两端省略**而不是尾部省略，字数一超就把前缀吃光，
         * 而前缀往往才是区分项（「代码编写与修改」/「代码编写与测试」去掉前缀就分不出来）。
         * 所以主动截到 4 个字、由我们加尾部省略号，让用户至少能看出这里被截过。
         */
        const val OPTION_TITLE_MAX_CHARS: Int = 4
    }
}

/**
 * 这个问题组在胶囊侧用的 id。
 *
 * `$events` waterfall 帧**允许不带 `callId`**（`RemoteEvents.kt` 里 `QuestionRequest.callId`
 * 是可空的，注释写明「取不到时两条路各弹一张卡」）。真机上第一个被验到的提问就是这种：
 * `callId=null` 但 `options` 有三个。
 *
 * 若这里直接要求 `callId != null` 才给选项按钮，用户看到的就是一张「有问题、有三个
 * 选项、却只有一个『⋯』」的卡片——最该一键答完的场景反而要多点两下进 App。
 *
 * 所以回落成与 `ChatViewModel.waterfallCallId` **同一个**合成 id：
 * `RemoteEventsClient.answerQuestions` 会 `removePrefix(WATERFALL_PREFIX)` 剥掉它再按
 * `eventId` 找条目，因此传合成 id 进去是它能认的写法之一。
 * 两边共用一个常量（[RemoteEvents.WATERFALL_PREFIX]），不各写一份字面量。
 */
internal fun QuestionRequest.capsuleCallId(): String =
    callId ?: (RemoteEvents.WATERFALL_PREFIX + eventId)

/** 某道题的一个选项。 */
internal fun AskQuestion.optionLabels(): List<String> = options.map { it.label }

/**
 * 待处理会话 id 的排列顺序：两类**交错**，再 `distinct()`。
 *
 * 抽成顶层纯函数只为一件事：**能被单测直接钉住**。
 * 第一版把它写成「先排完审批再排提问」，而它自己的 KDoc 写着「否则审批永远压着
 * 提问会让提问饿死」——实现与注释相反。子代理审查抓到并修好了，但当时没留下用例；
 * 后来变异验证把实现改回两段 `forEach`（M2），2394 条用例**一条都没红**，
 * 才暴露这个修复是裸奔的。
 *
 * 详细理由（为什么不是严格 FIFO、为什么没有时间戳可用）见
 * [CapsuleCoordinator.pendingSessionIds] 的 KDoc。
 */
internal fun capsulePendingOrder(
    approvals: List<ApprovalRequest>,
    questions: List<QuestionRequest>,
): List<String> {
    val ordered = buildList {
        val max = maxOf(approvals.size, questions.size)
        for (index in 0 until max) {
            approvals.getOrNull(index)?.agentId?.let(::add)
            questions.getOrNull(index)?.agentId?.let(::add)
        }
    }
    return ordered.distinct()
}
