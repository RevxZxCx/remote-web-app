package com.revxzxcx.remotewebapp.core.events

import com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.AskAnswer
import com.revxzxcx.remotewebapp.core.session.wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 转发事件订阅：**等待回答的审批请求与模型提问**从这里来，也在这里回答。
 *
 * ## 为什么挂在连接上而不是聊天页上
 * 这两类请求都是 **waterfall**：服务端把请求抛给「第一个愿意回答的远程监听者」，然后**等**。
 * 如果订阅只存在于聊天页，那么用户在工作区列表页、文件查看页、甚至聊天页正在加载的
 * 那几秒里收到的请求就**没有监听者**——服务端会一直等到请求自己的 `signal` 超时，
 * 表现是「桌面端问我要不要允许，手机毫无反应，过了一会儿工具自己失败了」。
 *
 * 所以订阅必须是**应用级、长驻、随连接生存**的：[start] 由 `AppContainer` 在连接建立后
 * 调用一次，[close] 随连接销毁。这也与 `RemoteStreamMux` 的生存期一致（同一条 WebSocket）。
 *
 * ## 为什么不用 `session/follow` 捎带
 * 审批请求**不是**会话日志事件。`approval/asked` / `approval/decided` 确实会进日志，
 * 但实测它们的 data 里**没有** request 的身份（`eventId` / `toolName` / `reason` 都不在），
 * 所以从日志里读不出「现在有人在等我回答」。只有转发事件流带得动完整请求。
 */
class RemoteEventsClient(
    private val mux: RemoteStreamMux,
    private val typert: TypertClient,
    private val scope: CoroutineScope,
) {

    private val _clientId = MutableStateFlow<String?>(null)

    /** 服务端分配的本连接 id；回答 waterfall 时必须带回去。 */
    val clientId: StateFlow<String?> = _clientId.asStateFlow()

    private val _stats = MutableStateFlow(RemoteEventsStats())

    /**
     * 转发事件流的收发计数。
     *
     * 存在的理由：`$events` 是**另一条**流（与 `session/follow`、`workspace/follow`
     * 共用同一条 WebSocket），它通不通从外面看不出来——`MuxStats.framesIn` 把三条流
     * 混在一起，聊天页照样刷消息时也可能是审批通道早就断了。所以诊断页要能单独看它。
     */
    val stats: StateFlow<RemoteEventsStats> = _stats.asStateFlow()

    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())

    /**
     * 正在等待回答的请求，按到达顺序。
     *
     * 只由 [handle] 增删，**没有**「按会话过滤」——过滤是 UI 的事（`ChatViewModel`
     * 按 `sessionId` 取子集），因为同一个连接上多个会话可能同时有请求。
     */
    val approvals: StateFlow<List<ApprovalRequest>> = _approvals.asStateFlow()

    private val _questions = MutableStateFlow<List<QuestionRequest>>(emptyList())

    /**
     * 正在等待回答的**模型提问**（`user-questions/request` waterfall）。
     *
     * 与 [approvals] 同源同生命周期，但回答方式不同：审批只回
     * `{kind:'result', value:'allowed-once'}`，提问回的是业务答案
     * `{kind:'result', value:{answers:[…]}}`，取消则按桌面端
     * `questionError('ASK_CANCELLED')` 的形状拒绝。
     *
     * 这条路上的提问只是「立刻弹卡片」；**权威来源仍是 `userQuestions` 投影**
     * （`ChatViewModel` 会把两条路合并）。所以 [questions] 里的条目可能没有
     * `callId`（`wait?` 可选），那种条目由投影那条路兜底。
     */
    val questions: StateFlow<List<QuestionRequest>> = _questions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 订阅或回答失败的中文说明。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 「有新的提问请求到达」的信号：每收到一次 `user-questions/request` waterfall 就 +1。
     *
     * 与 [questions] 并存的原因：[questions] 只在 `wait.callId` 存在时才能直接回答，
     * 而**权威的提问列表来自 `userQuestions` 投影**（轮询 + `session/control`）。
     * 这个信号就是「立刻去重读投影」的触发器——投影刷新几秒一次的节奏对
     * 「模型刚发问」来说太慢。
     */
    private val _questionSignal = MutableStateFlow(0L)

    /** 单调递增的提问到达计数；UI 观察到变化就去重读一次投影。 */
    val questionSignal: StateFlow<Long> = _questionSignal.asStateFlow()

    /**
     * 会话列表相关转发事件的队列。
     *
     * 与 [approvals] / [questions] 一样挂**应用级**：会话列表页随时会被销毁重建（切到
     * 聊天页再回来），如果订阅随页面走，那这段期间的新建/运行态变化就永久丢了——
     * 而 `session/list` 是全量重拉（1.78 MB），不能靠它补。
     *
     * ## 为什么是 Channel 而不是 StateFlow（审查 P1）
     *
     * 早先这里用 `MutableStateFlow<SessionNotification?>`「只留最新一条」，理由是
     * 「三条增量操作本身幂等，折叠中间值不丢信息」。**那个理由不成立**：
     * 幂等的前提是每条都**单独应用过**。`api-session/added` 紧跟
     * `api-session/status(X, …)` 时，若只剩后者，`patchRunning` 找不到 id 就原样返回，
     * 那条新会话**永远不会出现**；反过来两个会话近乎同时 `status(true)`/`status(false)`，
     * 丢掉任一条就有一个「进行中」小柱永久卡住。这正是用户报的两个现象本身。
     *
     * 容量 [SESSION_NOTIFICATION_BUFFER]。超出时丢**最旧**的：那时消费方（`HomeViewModel`）
     * 要么正忙要么已经不在了；后者下次进来会走一次全量 `session/list` 自愈，
     * 而 `Channel` 有界保证不会因为没人收就无限涨。
     */
    private val _sessionNotifications = kotlinx.coroutines.channels.Channel<SessionNotification>(
        capacity = SESSION_NOTIFICATION_BUFFER,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 每一条会话转发事件，按到达顺序逐条送达；**不折叠**（理由见 [_sessionNotifications]）。 */
    val sessionNotifications: kotlinx.coroutines.flow.Flow<SessionNotification> =
        _sessionNotifications.receiveAsFlow()

    private var job: Job? = null

    /** 保护 [approvals] 与 [stats] 的「读-改-写」复合操作（见 [handle] 的 waterfall 分支）。 */
    private val lock = Any()

    /**
     * 已经回答过 / 主动弃置过的 `eventId`（**审批与提问各一本账**）。
     *
     * [answer] 与 [answerQuestions] 都会立刻把卡片移除，于是 [handle] 里那句
     * 「`current.none { it.eventId == eventId }`」的去重就失效了——服务端补发同一条
     * waterfall 时卡片会被重新插回来，而且**永远点不动**（服务端早已视为已回答）。
     * 所以「已处理」必须独立于「当前在列表里」记账。
     *
     * 上限 `HANDLED_RING`：一条流里可能有成百上千次请求，全留着就是无界增长。
     *
     * **为什么分开两本**（R026 审查 P3）：共用一本时，提问的 eventId 会把审批的挤出环外
     * （反之亦然），被挤掉的那条一旦被服务端补发就会复活成一张点不动的卡片。
     */
    private val handledApprovals = ArrayDeque<String>()

    private val handledApprovalsSet = HashSet<String>()

    private val handledQuestions = ArrayDeque<String>()

    private val handledQuestionsSet = HashSet<String>()

    /**
     * 每收到一次 `ready` 帧（且 clientId 真的变了）就 +1。
     *
     * 回答必须带**当前**连接 id（`clientId`）；重连后服务端会分配一个新 id，
     * 旧 id 上的 `eventId` 永远答不了（实测：假 id → `gateway/internal:
     * ... identifies no active event stream`）。所以 [answer] 要把发起时的 epoch 记下来，
     * 发现流已经重建过就不要把卡片放回去——那条请求在新的流里早就不存在了。
     *
     * `@Volatile`：写在 IO 线程（`handle` 的 ready 分支），读在主线程（`submit`），
     * 没有 happens-before 的话理论上会漏判流重建、把已失效的卡片放回来。
     */
    @Volatile
    private var epoch: Long = 0L

    /**
     * `true` = 上次结束是因为凭证失效（`unpaired`）。
     *
     * 这时**不能**自己重试：重试只是每 5 秒打一次 403。等用户重新配对，
     * `AppContainer` 会建新连接、给新对象调 [start]。
     */
    private var stoppedByUnpair: Boolean = false

    /** 启动订阅。幂等——已有一条活跃订阅时直接返回。 */
    fun start() {
        if (job?.isActive == true) return
        stoppedByUnpair = false
        job = scope.launch {
            // `subscribe()` 返回只可能是两种原因：unpaired（[stoppedByUnpair]）或
            // 异常被 `catch` 吞掉。后者没有任何调用方会再调一次 `start()`
            // （`AppContainer` 只在新建连接时调），所以这里必须自己转起来，
            // 否则审批通道会静默死亡到进程重启。
            while (isActive && !stoppedByUnpair) {
                subscribe()
                if (!isActive || stoppedByUnpair) break
                delay(RESTART_DELAY_MS)
            }
        }
    }

    /**
     * 订阅转发事件流，断线自动重订。
     *
     * 退避与 `HomeViewModel` / `ChatViewModel` 一致（1→2→4→…→30s）。`unpaired`
     * 不重试：那意味着凭证失效，重试只是每 5 秒打一次 403。
     */
    private suspend fun subscribe() {
        _stats.value = RemoteEventsStats()
        // `open(endpoint, args)` 的第二参是 **args 的内容**，mux 会自己包成
        // `payload = {"args": <第二参>}`（`RemoteStreamMux.kt:139`）。
        // 所以这里必须传空对象，传 `{"args":{}}` 会变成 `{"args":{"args":{}}}`，
        // 服务端 exactKeys 校验直接拒——而 mock 不校验 payload，单测全绿也看不出来。
        mux.open(RemoteEvents.EVENT_STREAM_ENDPOINT, JsonObject(emptyMap()))
            .retryWhen { cause, attempt ->
                if (cause is TypertException && cause.isUnpaired) {
                    stoppedByUnpair = true
                    _clientId.value = null
                    // 订阅失效时把在途请求清空：已经没有监听者，它们不可能再被回答，
                    // 留着只会让 UI 显示一堆点不动的按钮。
                    synchronized(lock) {
                        _approvals.value = emptyList()
                        _questions.value = emptyList()
                        handledApprovals.clear()
                        handledApprovalsSet.clear()
                        handledQuestions.clear()
                        handledQuestionsSet.clear()
                    }
                    false
                } else {
                    _clientId.value = null
                    _stats.value = RemoteEventsStats()
                    // 代次一起递增：这条流已经拆了，在途回答即使失败也不该把卡片放回一个
                    // 没有订阅者的列表（要等下一次 ready 才被清掉，那期间用户能看到它们）。
                    epoch += 1
                    synchronized(lock) {
                        _approvals.value = emptyList()
                        _questions.value = emptyList()
                        handledApprovals.clear()
                        handledApprovalsSet.clear()
                        handledQuestions.clear()
                        handledQuestionsSet.clear()
                    }
                    delay(backoffMillis(attempt))
                    true
                }
            }
            .catch { cause ->
                if (cause is CancellationException) throw cause
                _error.value = describe(cause)
            }
            .collect { element -> handle(element) }
    }

    /** 处理一帧。 */
    private fun handle(element: kotlinx.serialization.json.JsonElement) {
        val obj = element as? JsonObject ?: return
        _stats.update { it.copy(framesIn = it.framesIn + 1) }
        when (obj.text("type")) {
            "ready" -> {
                val id = obj.text("clientId")
                if (id != null) {
                    // 重连后服务端会分配**新** id。旧 id 上的 eventId 再也答不了，
                    // 所以这些卡片必须清掉：留着就是一排点一次错一次的按钮。
                    if (_clientId.value != id) {
                        _clientId.value = id
                        // 新流 = 新一批 eventId。旧流上「已处理」的记账没有意义了，
                        // 全部清掉（否则新流里同一个 eventId 会被误判为已处理）。
                        synchronized(lock) {
                            _approvals.value = emptyList()
                            _questions.value = emptyList()
                            handledApprovals.clear()
                            handledApprovalsSet.clear()
                            handledQuestions.clear()
                            handledQuestionsSet.clear()
                        }
                        // 代次只在**真的换了流**时递增：重复的 ready 帧（同一个 clientId）
                        // 也递增的话，一次在途回答失败时 `startedEpoch != epoch`，
                        // 卡片就被错误地判定为「属于旧流」而不回滚。
                        epoch += 1
                    }
                    _error.value = null
                }
            }

            "cancel" -> {
                val eventId = obj.text("eventId") ?: return
                synchronized(lock) {
                    _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
                    _questions.value = _questions.value.filterNot { it.eventId == eventId }
                }
                // 服务端撤回的请求不会再被投递，但记一笔更稳：万一同一 eventId 又被
                // 补发一次，也不该弹出一张已经取消的卡片。撤回帧不带类型，两本账都记。
                markApprovalHandled(eventId)
                markQuestionHandled(eventId)
            }

            "emit" -> {
                _stats.update { it.copy(emits = it.emits + 1) }
                // 会话列表相关的五条（`api-session/…`）是本 App 唯一的增量来源：
                // `session/list` 是全量 unary（实测 1.78 MB），不能拿它轮询。
                val notification = SessionNotification.parse(
                    event = obj.text("event"),
                    args = obj["args"] as? JsonArray,
                )
                // `trySend` 不会挂起（`handle` 跑在收帧线程上），缓冲满时由
                // `DROP_OLDEST` 决定丢谁，语义见 [_sessionNotifications]。
                if (notification != null) _sessionNotifications.trySend(notification)
            }

            "waterfall" -> {
                _stats.update { it.copy(waterfalls = it.waterfalls + 1) }
                val eventId = obj.text("eventId") ?: return
                if (obj.text("event") == "approval/request") {
                    val request = ApprovalRequest.parse(
                        eventId = eventId,
                        agentId = obj.text("agentId"),
                        request = obj["request"] as? JsonObject,
                    ) ?: return
                    // 同一个 eventId 可能被重复投递（服务端补发）。去重而不是追加，
                    // 否则 UI 上会出现两张一模一样的卡片。
                    //
                    // 这段必须整块加锁：`handle()` 跑在 IO 调度器上，而 `answer()` /
                    // `dismiss()` 跑在主线程——「先读列表再写回」若不加锁，一次并发删除
                    // 会被随后的写回盖掉，用户刚允许通过的卡片又冒出来。
                    synchronized(lock) {
                        val current = _approvals.value
                        if (current.none { it.eventId == eventId } && eventId !in handledApprovalsSet) {
                            _approvals.value = current + request
                            _stats.update { it.copy(approvalsSeen = it.approvalsSeen + 1) }
                        }
                    }
                }
                if (obj.text("event") == "user-questions/request") {
                    synchronized(lock) {
                        _questionSignal.value = _questionSignal.value + 1
                    }
                    // 请求本身也放出来：弹卡片要的是「立刻」，而 `userQuestions` 投影
                    // 是轮询出来的（最快也要等下一次刷新）。投影仍是权威来源——
                    // `ChatViewModel` 把两条路合并，投影缺席时才用这里的内容。
                    val request = QuestionRequest.parse(
                        eventId = eventId,
                        agentId = obj.text("agentId"),
                        request = obj["request"] as? JsonObject,
                    )
                    if (request != null) {
                        synchronized(lock) {
                            val current = _questions.value
                            if (current.none { it.eventId == eventId } && eventId !in handledQuestionsSet) {
                                _questions.value = current + request
                                // 与 `approvalsSeen` 同口径：数**去重后真正认出来的请求**，
                                // 不是数帧。数帧会让诊断页在「收到过一帧但什么都没渲染」时
                                // 也显示 N≥1，用户对着一张不存在的卡片。
                                _stats.update { it.copy(questionsSeen = it.questionsSeen + 1) }
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * 回答一个审批请求。
     *
     * @param allow true = 允许一次；false = 拒绝。
     *
     * 失败时把请求**放回**列表并留下错误说明：网络抖动不该让用户失去再一次回答的机会，
     * 而「请求已经没了」（服务端撤回/超时）才是真的答不了。区分这两者只能靠再答一次，
     * 所以这里的策略是「保留卡片 + 说明原因」，由用户决定是否再点。
     */
    suspend fun answer(eventId: String, allow: Boolean) {
        val outcome = if (allow) ApprovalOutcome.ALLOW_ONCE else ApprovalOutcome.REJECT
        submit(
            eventId = eventId,
            outcome = buildJsonObject {
                put("kind", JsonPrimitive("result"))
                put("value", JsonPrimitive(outcome.wire))
            },
            kind = AnswerKind.APPROVAL,
            optimisticRemoval = {
                val hit = _approvals.value.firstOrNull { it.eventId == eventId }
                if (hit != null) {
                    _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
                }
                hit
            },
            restore = { removed ->
                val current = _approvals.value
                if (current.none { it.eventId == eventId }) {
                    _approvals.value = current + removed
                }
            },
            failureMessage = "授权没能送出",
        )
    }

    /**
     * 回答一个模型提问（`user-questions/request` 的 waterfall）。
     *
     * 与 [answer] 的区别只有 `outcome` 的形状：提问回的是业务答案
     * `{answers:[{id, selected, custom?}]}`，取消则按桌面端
     * `questionError('ASK_CANCELLED')` 的形状拒绝。乐观移除 / 失败回滚 / epoch 守卫
     * 三者完全一致，所以走同一个 [submit]。
     *
     * @param callId 题组的 callId。**这里收 callId 而不是 eventId**：waterfall 帧带
     *   `callId` 时 [ChatViewModel] 用它当卡片身份与草稿键，只有取不到时才回落合成
     *   eventId。参数与调用方保持同一种身份，才不会出现「卡片按 callId 显示、
     *   回答按 eventId 发」的错位。
     */
    suspend fun answerQuestions(callId: String, answers: List<AskAnswer>) {
        // 身份要**同时**认三种写法：投影/卡片用的真实 callId、waterfall 帧里的 eventId、
        // 以及 [ChatViewModel] 在拿不到 callId 时合成的 `waterfall:<eventId>`。
        // 少认任一种都会走到下面的空返：那时候没有任何 outcome 送出、没有任何报错、
        // 卡片也不退，用户点「提交」看到的是「什么都没发生」（R025 协议层 P1-1）。
        val key = callId.removePrefix(RemoteEvents.WATERFALL_PREFIX)
        val hit = synchronized(lock) {
            _questions.value.firstOrNull {
                it.callId == callId || it.callId == key || it.eventId == key || it.eventId == callId
            }
        } ?: throw IllegalStateException("这一组问题已经不在等待回答了")
        submit(
            eventId = hit.eventId,
            outcome = QuestionOutcome.Answered(answers.wire()).wire(),
            kind = AnswerKind.QUESTION,
            optimisticRemoval = {
                val removed = _questions.value.firstOrNull { it.eventId == hit.eventId }
                if (removed != null) {
                    _questions.value = _questions.value.filterNot { it.eventId == hit.eventId }
                }
                removed
            },
            restore = { removed ->
                val current = _questions.value
                if (current.none { it.eventId == hit.eventId }) {
                    _questions.value = current + removed
                }
            },
            failureMessage = "回答没能送出",
        )
    }

    /**
     * 用户取消提问（等价于桌面端 `ASK_CANCELLED`）。
     *
     * 走 [submit] 而不是自己写一遍「取 id / 记 epoch / 移除 / 回滚」：
     * 这条路上曾经漏了失败回滚，卡片消失后用户既不能取消也不能回答，只能等模型超时。
     */
    suspend fun dismissQuestion(eventId: String) {
        submit(
            eventId = eventId,
            outcome = QuestionOutcome.Cancelled.wire(),
            kind = AnswerKind.QUESTION,
            optimisticRemoval = {
                val hit = _questions.value.firstOrNull { it.eventId == eventId }
                if (hit != null) {
                    _questions.value = _questions.value.filterNot { it.eventId == eventId }
                }
                hit
            },
            restore = { removed ->
                val current = _questions.value
                if (current.none { it.eventId == eventId }) {
                    _questions.value = current + removed
                }
            },
            failureMessage = "取消没能送出",
            counted = false,
        )
    }

    /**
     * 提问已经在别处被回答（投影路 `userQuestions/answer`）时，把 waterfall 上的
     * 同一条请求摘掉。
     *
     * **这条是 P1 级的**：投影组与 waterfall 条目是同一个问题的两个视图
     * （用 `callId` 对上）。用户答投影组时只动 `_questions`（`ChatViewModel` 那一侧），
     * waterfall 条目就留在这里；投影组一消失，`interactive` 回退到这条，
     * 用户看到一张**刚刚亲口答过**的卡片，再点一次就是对同一个 eventId 发第二次 outcome。
     * 这正是 R020/R024 修过两遍的「卡片复活」，只是这次从两条路之间漏进来。
     *
     * @param callId 投影组的 callId。**也认 `waterfall:` 合成键**（R026 审查 P2）：
     *   waterfall 帧不带 `wait.callId` 时 `ChatViewModel` 那一侧从头到尾用的都是合成键，
     *   只认真实 callId 的话这一半永远摘不掉。匹配不上任何条目时是 no-op。
     */
    fun forgetQuestion(callId: String) {
        synchronized(lock) {
            val eventId = _questions.value
                .firstOrNull {
                    it.callId == callId ||
                        it.eventId == callId.removePrefix(RemoteEvents.WATERFALL_PREFIX)
                }
                ?.eventId
                ?: return
            _questions.value = _questions.value.filterNot { it.eventId == eventId }
            markQuestionHandled(eventId)
        }
    }

    /** 主动丢弃一条审批请求（不回答）。仅在用户明确划掉时调用。 */
    fun dismiss(eventId: String) {
        synchronized(lock) {
            _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
        }
        markApprovalHandled(eventId)
    }

    /**
     * 送出一个 outcome，附带「乐观移除 → 失败回滚」的完整语义。
     *
     * 抽出来是因为审批与提问的唯一差别就是 outcome 形状与列表，其余四步
     * （取 clientId、记 epoch、乐观移除、按 epoch 守卫回滚）必须逐字一致——
     * 早先两条路各写一遍，改一处漏一处。
     */
    private suspend fun <T> submit(
        eventId: String,
        outcome: JsonObject,
        kind: AnswerKind,
        optimisticRemoval: () -> T?,
        restore: (T) -> Unit,
        failureMessage: String,
        /** false = 不计入「已回答」（取消 / 弃置走这条）。 */
        counted: Boolean = true,
    ) {
        val id = _clientId.value
        if (id == null) {
            _error.value = "还没连上审批通道，请稍候再试"
            return
        }
        val startedEpoch = epoch
        // 乐观移除：点了就该立刻消失，否则网络往返的那几百毫秒里用户会再点一次。
        // 同样要整块加锁（见 [handle] 里 waterfall 分支的说明）。
        val removed = synchronized(lock) { optimisticRemoval() }
        try {
            sendOutcome(id, eventId, outcome)
            _error.value = null
            if (counted) {
                _stats.update { current ->
                    when (kind) {
                        AnswerKind.APPROVAL -> current.copy(approvalAnswers = current.approvalAnswers + 1)
                        AnswerKind.QUESTION -> current.copy(questionAnswers = current.questionAnswers + 1)
                    }
                }
            }
            markHandled(eventId, kind)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            // 流在这几百毫秒里重建过（重连 / 新 ready）→ 旧 eventId 在新流里本来就不存在，
            // 放回去只会多一张永远点不动的卡片。
            if (startedEpoch == epoch && removed != null) {
                synchronized(lock) { restore(removed) }
            }
            _error.value = "$failureMessage：${describe(cause)}"
        }
    }

    /** 走 unary RPC 把 outcome 送到 `$events/result`。 */
    private suspend fun sendOutcome(clientId: String, eventId: String, outcome: JsonObject) {
        val args = buildJsonObject {
            put("clientId", JsonPrimitive(clientId))
            put("eventId", JsonPrimitive(eventId))
            put("outcome", outcome)
        }
        typert.callUnit(RemoteEvents.EVENT_RESULT_ENDPOINT, args)
    }

    /**
     * 记下「这个 eventId 已经处理过了」，并维持环形上限。
     *
     * **可重入**：内部会拿 [lock]，Kotlin 的 `synchronized` 是可重入的，所以在持锁状态下
     * 调用也安全（`forgetQuestion` 就那么做——它要在同一个临界区里「摘卡片 + 记账」，
     * 拆成两步反而给并发留了窗口）。调用点必须已经持有结果或已经移除卡片。
     *
     * 按 [kind] 记到对应的那一本账上（审批与提问分开，见字段 KDoc）。
     */
    private fun markHandled(eventId: String, kind: AnswerKind) {
        when (kind) {
            AnswerKind.APPROVAL -> markApprovalHandled(eventId)
            AnswerKind.QUESTION -> markQuestionHandled(eventId)
        }
    }

    private fun markApprovalHandled(eventId: String) {
        synchronized(lock) {
            if (handledApprovalsSet.add(eventId)) {
                handledApprovals.addLast(eventId)
                while (handledApprovals.size > HANDLED_RING) {
                    handledApprovalsSet.remove(handledApprovals.removeFirst())
                }
            }
        }
    }

    private fun markQuestionHandled(eventId: String) {
        synchronized(lock) {
            if (handledQuestionsSet.add(eventId)) {
                handledQuestions.addLast(eventId)
                while (handledQuestions.size > HANDLED_RING) {
                    handledQuestionsSet.remove(handledQuestions.removeFirst())
                }
            }
        }
    }

    /** 清掉错误说明（用户看过之后）。 */
    fun clearError() {
        _error.value = null
    }

    /** 关闭订阅。随连接销毁。幂等——重复调用不抛异常、不重复重置。 */
    fun close() {
        stoppedByUnpair = true
        job?.cancel()
        job = null
        _clientId.value = null
        synchronized(lock) {
            _approvals.value = emptyList()
            _questions.value = emptyList()
            handledApprovals.clear()
            handledApprovalsSet.clear()
            handledQuestions.clear()
            handledQuestionsSet.clear()
        }
        _stats.value = RemoteEventsStats()
        _error.value = null
        _questionSignal.value = 0L
    }

    private companion object {

        /** 终端失败后多久再试一次（只在 `while` 循环里用，退避仍归 `retryWhen` 管）。 */
        const val RESTART_DELAY_MS = 5_000L

        /**
         * 「已处理」记账的环形上限。
         *
         * 只需要覆盖「服务端补发同一条 waterfall」的窗口（秒级），
         * 64 条足够；再大就是无界增长，与审批次数成正比。
         */
        const val HANDLED_RING = 64

        /**
         * 会话转发事件的缓冲深度。
         *
         * 64 条的余量足够覆盖「列表页正在重建、消费方还没开始收」这一段；再多就是
         * 让一次突发把内存顶起来，而超出部分本来也能靠下次全量 `session/list` 自愈。
         */
        const val SESSION_NOTIFICATION_BUFFER = 64
    }

    private fun backoffMillis(attempt: Long): Long =
        (1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L)

    private fun describe(cause: Throwable): String {
        val typert = cause as? TypertException
        if (typert != null) return "${typert.code}：${typert.message}"
        return cause.message ?: "未知错误"
    }
}
