package com.revxzxcx.remotewebapp.core.events

import com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 转发事件订阅：**等待回答的审批请求**从这里来，也在这里回答。
 *
 * ## 为什么挂在连接上而不是聊天页上
 * 审批请求是 **waterfall**：服务端把请求抛给「第一个愿意回答的远程监听者」，然后**等**。
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

    private val _error = MutableStateFlow<String?>(null)

    /** 订阅或回答失败的中文说明。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    /** 保护 [approvals] 与 [stats] 的「读-改-写」复合操作（见 [handle] 的 waterfall 分支）。 */
    private val lock = Any()

    /**
     * 每收到一次 `ready` 帧就 +1。
     *
     * 回答必须带**当前**连接 id（`clientId`）；重连后服务端会分配一个新 id，
     * 旧 id 上的 `eventId` 永远答不了（实测：假 id → `gateway/internal:
     * ... identifies no active event stream`）。所以 [answer] 要把发起时的 epoch 记下来，
     * 发现流已经重建过就不要把卡片放回去——那条请求在新的流里早就不存在了。
     */
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
                    _approvals.value = emptyList()
                    false
                } else {
                    _clientId.value = null
                    _stats.value = RemoteEventsStats()
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
                        _approvals.value = emptyList()
                    }
                    epoch += 1
                    _error.value = null
                }
            }

            "cancel" -> {
                val eventId = obj.text("eventId") ?: return
                _approvals.update { list -> list.filterNot { it.eventId == eventId } }
            }

            "emit" -> {
                // 本期只接 waterfall；emit 全部忽略。留这个分支是为了让「刻意不接」
                // 在代码里可见，而不是看起来像漏了。
                _stats.update { it.copy(emits = it.emits + 1) }
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
                        if (current.none { it.eventId == eventId }) {
                            _approvals.value = current + request
                            _stats.update { it.copy(approvalsSeen = it.approvalsSeen + 1) }
                        }
                    }
                }
                // `user-questions/request`（模型提问）也是 waterfall，本期未实现：
                // 不接就意味着它会一直等。已知缺口，记录在测试记录里。
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
        val id = _clientId.value
        if (id == null) {
            _error.value = "还没连上审批通道，请稍候再试"
            return
        }
        val startedEpoch = epoch
        val outcome = if (allow) ApprovalOutcome.ALLOW_ONCE else ApprovalOutcome.REJECT
        val args = buildJsonObject {
            put("clientId", JsonPrimitive(id))
            put("eventId", JsonPrimitive(eventId))
            put(
                "outcome",
                buildJsonObject {
                    put("kind", JsonPrimitive("result"))
                    put("value", JsonPrimitive(outcome.wire))
                },
            )
        }
        // 乐观移除：点了就该立刻消失，否则网络往返的那几百毫秒里用户会再点一次。
        // 同样要整块加锁（见 [handle] 里 waterfall 分支的说明）。
        val removed = synchronized(lock) {
            val hit = _approvals.value.firstOrNull { it.eventId == eventId }
            if (hit != null) {
                _approvals.value = _approvals.value.filterNot { it.eventId == eventId }
            }
            hit
        }
        try {
            typert.callUnit(RemoteEvents.EVENT_RESULT_ENDPOINT, args)
            _error.value = null
            _stats.update { it.copy(answers = it.answers + 1) }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            // 流在这几百毫秒里重建过（重连 / 新 ready）→ 旧 eventId 在新流里本来就不存在，
            // 放回去只会多一张永远点不动的卡片。
            if (startedEpoch == epoch && removed != null) {
                synchronized(lock) {
                    val current = _approvals.value
                    if (current.none { it.eventId == eventId }) {
                        _approvals.value = current + removed
                    }
                }
            }
            _error.value = "授权没能送出：${describe(cause)}"
        }
    }

    /** 主动丢弃一条请求（不回答）。仅在用户明确划掉时调用。 */
    fun dismiss(eventId: String) {
        _approvals.update { list -> list.filterNot { it.eventId == eventId } }
    }

    /** 清掉错误说明（用户看过之后）。 */
    fun clearError() {
        _error.value = null
    }

    /** 关闭订阅。随连接销毁。 */
    fun close() {
        stoppedByUnpair = true
        job?.cancel()
        job = null
        _clientId.value = null
        _approvals.value = emptyList()
        _stats.value = RemoteEventsStats()
        _error.value = null
    }

    private companion object {

        /** 终端失败后多久再试一次（只在 `while` 循环里用，退避仍归 `retryWhen` 管）。 */
        const val RESTART_DELAY_MS = 5_000L
    }

    private fun backoffMillis(attempt: Long): Long =
        (1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L)

    private fun describe(cause: Throwable): String {
        val typert = cause as? TypertException
        if (typert != null) return "${typert.code}：${typert.message}"
        return cause.message ?: "未知错误"
    }
}
