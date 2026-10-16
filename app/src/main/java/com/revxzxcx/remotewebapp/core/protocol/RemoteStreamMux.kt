package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条 WebSocket 承载**全部**逻辑流（mux）。
 *
 * 依据（源码已核实）：
 * - `@deepseek-ai/dsh-api-gateway/lib/client.js:106` `REMOTE_STREAM_MUX_PATH = "/api/remote.mux"`
 * - `:728-733` `remoteStreamUrl()` —— 由 `document.baseURI` 推导，http→ws / https→wss
 * - `:374-423` `open()` —— 生成 `streamId` 并发 `{type:'open',streamId,endpoint,payload}`
 * - `:160-179` `parseRemoteStreamServerMessage` —— 服务端只发 item / end / error 三种帧
 * - `:590-595` 收到非法帧时客户端必须主动以 **4002** 关闭
 *
 * 客户端 → 服务端帧：
 * ```
 * { "type":"open",   "streamId":"<uuid>", "endpoint":"session/follow", "payload":{"args":{...}} }
 * { "type":"item",   "streamId":"<uuid>", "value": <json> }   // 上行（本期不用）
 * { "type":"end",    "streamId":"<uuid>" }                    // 半关闭上行
 * { "type":"cancel", "streamId":"<uuid>" }
 * ```
 * 服务端 → 客户端帧：`{type:'item',streamId[,value]}` / `{type:'end',streamId}` /
 * `{type:'error',streamId,error:{code,message,details}}`。
 *
 * 握手：`wss://{host}/remote/api/remote.mux?device={deviceId}`
 * （`src/remote-methods.ts:62` `REMOTE_DEVICE_QUERY = 'device'` —— WS 无法带自定义头，
 * 插件在转发前会把这个参数删掉，见 `src/remote-api.ts:199-211`）。
 *
 * 本类线程安全；并发调用只会建立一条 socket。
 */
class RemoteStreamMux(
    private val baseUrl: String,
    private val deviceId: String,
    private val http: OkHttpClient,
    private val json: Json,
) {

    private val mutex = Mutex()

    /**
     * 当前已 OPEN 的 socket；断线时清空。
     *
     * [socket] / [attempt] / [generation] 三个字段同时被两类线程访问：协程线程（经 [mutex]
     * 读写的 [waitForSocket] / [close]）与 OkHttp 的 WebSocket 回调线程（`onOpen` /
     * `onFailure` / `failCarrier`，它们**不能**去抢 mutex——回调是同步的，加锁会和
     * `waitForSocket` 里的 `ws.send` 形成死锁风险）。因此这里用 `@Volatile` 保证可见性：
     * 否则 `close()` 与并发 `onOpen` 竞争时，`onOpen` 可能读到过期的 [generation] 并把
     * 新 socket 存进已关闭的 mux，此后没有任何路径会关闭它（连接泄漏）。
     */
    @Volatile
    private var socket: WebSocket? = null

    /** 进行中的连接尝试；同一代际内只允许一个。 */
    @Volatile
    private var attempt: Attempt? = null

    /** 代际号：`close()` 或断线时递增，用于丢弃旧 socket 的帧。 */
    @Volatile
    private var generation: Int = 0

    /** streamId → 该流的入站通道。 */
    private val streams = ConcurrentHashMap<String, Channel<MuxEvent>>()

    /** mux 的实际地址，供调试页展示。 */
    val url: String
        get() {
            val scheme = if (baseUrl.startsWith("https", ignoreCase = true)) "wss" else "ws"
            val hostAndPath = baseUrl.substringAfter("://").trimEnd('/')
            return "$scheme://$hostAndPath${TypertClient.REMOTE_API_PREFIX}/remote.mux?device=$deviceId"
        }

    /** 当前是否有可用连接（调试页用）。 */
    val isConnected: Boolean get() = socket != null

    /**
     * 故障记录的自增序号（R018 P1-2）。
     *
     * `AtomicLong` 而不是 `Long`：`recordFailure` 可能被 WebSocket 回调线程与
     * 订阅方协程同时调用。进程内单调即可，不需要持久化。
     */
    private val errorSeq = java.util.concurrent.atomic.AtomicLong(0L)

    private val _stats = MutableStateFlow(MuxStats())
    private val _errors = MutableStateFlow<List<MuxError>>(emptyList())

    /** 帧收发计数、握手成败、当前载波状态（需求 FR-4.4 与「可观测」非功能需求）。 */
    val stats: StateFlow<MuxStats> = _stats.asStateFlow()

    /** 最近 [ERROR_RING_SIZE] 条故障，新的在前（调试页）。 */
    val errors: StateFlow<List<MuxError>> = _errors.asStateFlow()

    /**
     * 打开一条服务端流。
     *
     * 冷流：每收集一次就新开一条服务端流；收集结束或协程取消时，若该流未正常
     * 终止则自动发 `cancel`（对应 `client.js:417-420`）。服务端 `end` 正常结束，
     * `error` 抛出 [TypertException]。
     *
     * 🔴 **调用方必须给收集链加 `.catch`**（并原样重抛 `CancellationException`）。
     * 这不是可选的防御，而是本类与调用方之间的硬性契约：
     *  - 载体一断，[failCarrier] 会用**同一个 cause** 关闭所有流的 Channel
     *    （`channels.forEach { it.close(cause) }`），`callbackFlow` 把它当作**流的终止原因**
     *    抛给收集方；
     *  - 收集方通常在 `viewModelScope`（`Dispatchers.Main.immediate`）上，未接住的异常
     *    直接变成 `FATAL EXCEPTION: main` + 进程死。
     *
     * 真机实录（OnePlus PKG110 / Android 16）：桌面端进程退出 → TCP 断开且**没有关闭帧**
     * → OkHttp 读循环抛 `java.io.EOFException`（栈顶
     * `RealWebSocket.loopReader → WebSocketReader.processNextFrame`）→ `loopReader` 自己
     * catch 后走 `failWebSocket(e, null)` → [MuxListener.onFailure] → `failCarrier` →
     * `live.deferred.completeExceptionally` + `channel.close(cause)` → 收集方在 Main 上重抛
     * → 用户看到「点开一个对话就自动退出」。
     *
     * 注意 `MuxListener` 那层 `runCatching` **挡不住这条路径**（异常并不从 OkHttp 回调线程
     * 逃逸），所以第 N 个消费方漏写 `.catch` 就会重新引入同一个进程崩溃。
     */
    fun open(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<JsonElement> =
        callbackFlow {
            val streamId = UUID.randomUUID().toString()
            val inbox = Channel<MuxEvent>(Channel.UNLIMITED)
            streams[streamId] = inbox

            var terminal = false
            try {
                val ws = waitForSocket()
                ws.sendCounted(
                    buildJsonObject {
                        put("type", JsonPrimitive(MuxFrame.TYPE_OPEN))
                        put("streamId", JsonPrimitive(streamId))
                        put("endpoint", JsonPrimitive(endpoint))
                        put("payload", buildJsonObject { put(TypertClient.ARGS, args) })
                    }.toString(),
                )

                for (event in inbox) {
                    when (event) {
                        is MuxEvent.Item -> event.value?.let { trySend(it) }
                        is MuxEvent.End -> {
                            terminal = true
                            close()
                            return@callbackFlow
                        }
                        is MuxEvent.Error -> {
                            terminal = true
                            close(TypertException(event.code, event.message, event.details))
                            return@callbackFlow
                        }
                    }
                }
            } finally {
                streams.remove(streamId)
                inbox.close()
                if (!terminal) {
                    socket?.sendCounted(
                        buildJsonObject {
                            put("type", JsonPrimitive(MuxFrame.TYPE_CANCEL))
                            put("streamId", JsonPrimitive(streamId))
                        }.toString(),
                    )
                }
            }
        }

    /**
     * 发一帧并计数。
     *
     * 为什么用扩展函数而不是在调用点各自 `_stats.update`：`open()`（含 `cancel`）是这个类里
     * 唯一的出站路径，把计数钉在唯一入口上，调试页的「发出 N 帧」就不会因为将来加一个
     * 上行 `item` 而漏记。
     */
    private fun WebSocket.sendCounted(text: String): Boolean {
        val sent = send(text)
        // 只有真的写出去了才计数：`send()` 在连接已关闭/队列满时返回 false，此时把
        // 「发出帧」加一会让调试页的计数与真实链路行为脱节（断线后计数仍在涨）。
        if (sent) _stats.update { it.copy(framesOut = it.framesOut + 1L) }
        return sent
    }

    /** 关闭整条 mux 与其上所有流。 */
    suspend fun close() {
        val doomed = mutex.withLock {
            generation++
            val current = socket
            socket = null
            attempt?.let { it.dead = true }
            attempt?.deferred?.cancel()
            attempt = null
            current
        }
        doomed?.close(1000, "disposed")
        val channels = streams.values.toList()
        streams.clear()
        channels.forEach { it.close() }
        // 主动关闭不是故障：把载波退回 Idle，并清掉上次的故障记忆与连续失败计数。
        // 不清 `consecutiveFailures` 的话，下次断线会从前一轮的计数继续往上加
        //（「第 5 次失败」而实际上刚才是第 1 次）。
        _stats.update { it.copy(carrier = CarrierState.Idle, lastFailure = null, consecutiveFailures = 0L) }
    }

    /**
     * 由持有重试计划的一方（`HomeViewModel` / `ChatViewModel` 的 `retryWhen`）汇报
     * 「我打算在 [delayMillis] 之后重试，原因是什么」。
     *
     * 为什么由调用方汇报而不是 mux 自己重试：mux 是**被动**的（`open()` 是冷流，
     * 收完就结束），重试语义属于订阅方。把重试计划投影回载波状态，既保证状态条与真实
     * 行为一致，又不需要把 `Flow` 的生命周期塞进协议层。
     *
     * **本方法不改 `consecutiveFailures`**（R006 P2-6）：连续失败次数由 [recordFailure]
     * 维护（每次真实故障 +1，建连成功归零），这里是纯投影。早前这里也做 +1，于是
     * 「一次故障」会因为 `failCarrier` 的 recordFailure 与订阅方的 noteRetry 各算一次而
     * 变成 2，同一逻辑故障被重订阅两次报成「第 4 次失败」。改成纯投影后天然幂等。
     */
    fun noteRetry(reason: String, delayMillis: Long, unpaired: Boolean = false) {
        val now = System.currentTimeMillis()
        _stats.update { current ->
            val attempt = current.consecutiveFailures.coerceAtLeast(1L)
            current.copy(
                carrier = if (unpaired) {
                    CarrierState.Unpaired(reason)
                } else {
                    CarrierState.Reconnecting(attempt = attempt, retryAtMillis = now + delayMillis, reason = reason)
                },
                lastFailure = reason,
            )
        }
    }

    /** 记录一次故障（同时进计数与环形缓冲）。 */
    private fun recordFailure(code: String, summary: String) {
        val now = System.currentTimeMillis()
        _stats.update {
            it.copy(
                failures = it.failures + 1L,
                consecutiveFailures = it.consecutiveFailures + 1L,
                lastFailure = summary,
                carrier = when (it.carrier) {
                    is CarrierState.Connected -> CarrierState.Connecting
                    else -> if (it.carrier is CarrierState.Unpaired) it.carrier else CarrierState.Connecting
                },
            )
        }
        // id 单调递增（R018 P1-2）：`(atMillis, code, summary)` 不保证唯一，
        // 同毫秒的并发失败路径能产出重复项 → LazyColumn 抛「Key was already used」。
        _errors.update { previous ->
            (listOf(MuxError(id = errorSeq.incrementAndGet(), atMillis = now, summary = summary, code = code)) + previous)
                .take(ERROR_RING_SIZE)
        }
    }

    /**
     * 取得一条 OPEN 状态的 socket，必要时建立连接。
     *
     * 单飞（single-flight）：同一代际内只有一个调用者负责建连，其余等待同一个
     * [CompletableDeferred]（对应 `client.js:553-580` `waitForSocket` 的 waiters 语义）。
     * 若连接在等待期间因断线作废，循环重试。
     *
     * **必须带超时**：`HttpClients.stream()` 的 `readTimeout(0)` 是为了让长连接的读不超时，
     * 但它同时把 OkHttp 的**握手**等待也变成了无限等待。隧道/中继链路下 WS 升级偶发挂死
     * （实测：手机侧 t=35.3s 发出 `startConnection` 后 20s 内 `onOpen`/`onFailure`/`onClosed`
     * 一个都没回调，App 就此永久停在「连接中…」；主机侧同 URL 只需 1.3s）。没有超时就没有
     * 失败、没有失败就没有 `retryWhen` 重试，UI 卡在「连接中…」而服务端 `onlineCount` 恒 0。
     */
    private suspend fun waitForSocket(): WebSocket {
        while (true) {
            val (mine, ws) = mutex.withLock {
                socket?.let { return it }
                val gen = generation
                val live = attempt?.takeIf { it.generation == gen && !it.dead }
                if (live != null) {
                    false to live
                } else {
                    val fresh = Attempt(gen, CompletableDeferred())
                    attempt = fresh
                    true to fresh
                }
            }
            @Suppress("UNCHECKED_CAST")
            val live = ws as Attempt
            if (mine) {
                // 首次连接期间状态条要显示「连接中…」而不是「未连接」：早前只有
                // `recordFailure` 会写 `Connecting`，于是**成功**的首次握手全程显示「未连接」
                //（R006 P2-7）。载波是全局唯一事实，这里如实投影。
                _stats.update {
                    if (it.carrier is CarrierState.Connected) it else it.copy(carrier = CarrierState.Connecting)
                }
                startConnection(live)
            }
            try {
                withTimeout(HANDSHAKE_TIMEOUT_MS) { live.deferred.await() }
            } catch (cause: TimeoutCancellationException) {
                // 作废这次尝试：置 `dead` 让被抛弃的 socket 的后续回调全部失效，同时递增
                // `generation`，使「旧 socket 回调 `failCarrier`」不可能拆掉重试后已经建好的
                // 健康连接（R006 P1-1）。这里必须动 `generation` 而不只是 `attempt`：回调线程
                // 只按 `live.generation != generation` 判活。
                val mineTimedOut = mutex.withLock {
                    if (attempt === live) attempt = null
                    val mineNow = live.generation == generation
                    if (mineNow) generation++
                    live.dead = true
                    live.socket?.cancel()
                    live.socket = null
                    mineNow
                }
                // 握手超时以前从不 `recordFailure`，于是隧道挂死这条**唯一**的失败路径下
                // `stats.failures` 不增、错误环里什么都没有（R006 P2-5）。抛异常前先记一笔，
                // 并且只记一次（`firstReport`），与 `failCarrier` 的去重规则一致。
                val firstReport = !live.reported
                live.reported = true
                if (firstReport) {
                    recordFailure(
                        TypertException.CODE_TRANSPORT,
                        "WebSocket 握手超时（${HANDSHAKE_TIMEOUT_MS / 1000}s）",
                    )
                }
                if (!mineTimedOut) continue
                throw TypertException(
                    TypertException.CODE_TRANSPORT,
                    "WebSocket 握手超时（${HANDSHAKE_TIMEOUT_MS / 1000}s）：${cause.message ?: "无响应"}",
                    cause = cause,
                )
            }
        }
    }

    /** 由负责建连的协程调用。 */
    private fun startConnection(live: Attempt) {
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, MuxListener(live))
        live.socket = ws
    }

    /** 一次连接尝试。`generation` 用于判断是否已被 `close()` 作废。 */
    private class Attempt(
        val generation: Int,
        val deferred: CompletableDeferred<WebSocket>,
    ) {
        /** 回调线程写入、`close()` 读取，故需 `@Volatile`。 */
        @Volatile
        var socket: WebSocket? = null

        /**
         * 这次尝试是否已经记过一次故障。
         *
         * 一次逻辑故障会触发**多个**回调：非法帧会先 `failCarrier(4002 帧)`，紧接着
         * `onClosed` 又会带着关闭码回调一次。若不做去重，调试页会把同一次断线显示成
         * 「失败 2 次」，重连退避的「第 N 次失败」也会被夸大 —— 诊断数据必须与
         * 用户感知的一次故障一一对应。
         */
        @Volatile
        var reported: Boolean = false

        /**
         * 这次尝试是否已被作废（握手超时 / 整条 mux 关闭）。
         *
         * 与 [reported] 分开：`reported` 只挡**计数**，`dead` 挡**全部副作用**。被作废的
         * 尝试上仍然活着的 socket 之后一定会回调 `onClosed`，那时它已经不代表当前连接了；
         * 只挡计数的话，`failCarrier` 里的 `socket = null` 与 `streams.clear()` 会把重试后
         * 建好的健康连接和新开的流一并拆掉（连接泄漏 + 同一故障记两次）。
         */
        @Volatile
        var dead: Boolean = false
    }

    /** 服务端帧的规范化形状。 */
    private sealed interface MuxEvent {
        data class Item(val value: JsonElement?) : MuxEvent
        data object End : MuxEvent
        data class Error(val code: String, val message: String, val details: String?) : MuxEvent
    }

    /** 通过严格校验的服务端帧（对应 `client.js:160-179` `parseRemoteStreamServerMessage`）。 */
    private sealed interface ServerFrame {
        val streamId: String

        data class Item(override val streamId: String, val value: JsonElement?) : ServerFrame
        data class End(override val streamId: String) : ServerFrame
        data class Error(
            override val streamId: String,
            val code: String,
            val message: String,
            val details: String?,
        ) : ServerFrame
    }

    /**
     * 严格解析一帧。形状与官方 `parseRemoteStreamServerMessage` 逐字对应：
     * - `item`  —— 键**恰好**是 `{type,streamId}` 或 `{type,streamId,value}`；
     * - `end`   —— 键恰好是 `{type,streamId}`；
     * - `error` —— 键恰好是 `{type,streamId,error}`，且 `error` 的键恰好是
     *   `{code,message,details}`，其中 `code`/`message` 是字符串、`details` 是对象。
     *
     * 任何偏差（含未知 `type`、缺键、多键、`streamId` 为空）都返回 null，
     * 调用方据此以 4002 关闭。
     */
    private fun validate(text: String): ServerFrame? {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = frame.strOrNull("type") ?: return null
        val streamId = frame.strOrNull("streamId")?.takeIf { it.isNotEmpty() } ?: return null
        return when (type) {
            MuxFrame.TYPE_ITEM -> {
                val keys = frame.keys
                if (keys != setOf("type", "streamId") && keys != setOf("type", "streamId", "value")) {
                    null
                } else {
                    ServerFrame.Item(streamId, frame["value"])
                }
            }

            MuxFrame.TYPE_END ->
                if (frame.keys == setOf("type", "streamId")) ServerFrame.End(streamId) else null

            MuxFrame.TYPE_ERROR -> {
                if (frame.keys != setOf("type", "streamId", "error")) return null
                val error = frame["error"] as? JsonObject ?: return null
                if (error.keys != setOf("code", "message", "details")) return null
                val code = error.strOrNull("code") ?: return null
                val message = error.strOrNull("message") ?: return null
                if (error["details"] !is JsonObject) return null
                ServerFrame.Error(streamId, code, message, error["details"].toString())
            }

            else -> null
        }
    }

    /**
     * OkHttp 的 WebSocket 回调。
     *
     * **五个覆写全部自己接住异常**（`onOpen` / `onMessage(String)` / `onMessage(ByteString)`
     * / `onFailure` / `onClosed`）。这是**纵深防御**，不是某次具体崩溃的修复点：真机上
     * 杀死进程的是另一条路径（见 [open] 的 KDoc），异常经流的终止原因传到收集协程后
     * 在 `Dispatchers.Main.immediate` 上重抛。这一层挡的是「回调体自身抛异常」——
     * 例如 `_stats.update` 或 `recordFailure` 内部的意外，那会让 OkHttp 的回调线程
     * 直接崩，比协程里崩更早、更难归因。
     */
    private inner class MuxListener(private val live: Attempt) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 两道门：generation 变了说明整条 mux 已被 close() 作废；dead 说明这次 attempt
            // 已被握手超时或已结束的故障作废。迟到的 socket 在这里就关掉，绝不让它覆盖
            // 后继连接（R006 P1-1 / P2-2）。两道门合起来保证了「同代际的旧 socket 覆盖新
            // socket」不可达：作废一定会递增 generation 或置 dead。
            runCatching {
                if (live.generation != generation || live.dead) {
                    webSocket.close(1000, "stale generation")
                    return
                }
                socket = webSocket
                if (attempt === live) attempt = null
                live.deferred.complete(webSocket)
                // 连上了：故障计数归零。不清 [_errors]——调试页需要看到「曾经失败过几次」。
                _stats.update {
                    it.copy(
                        opens = it.opens + 1L,
                        carrier = CarrierState.Connected(System.currentTimeMillis()),
                        lastFailure = null,
                        consecutiveFailures = 0L,
                    )
                }
            }.onFailure { cause ->
                // 兜底（D6）：这层 `runCatching` 里的语句实际都不抛，但一旦真抛了，
                // 不处理会让等待方干等到 `HANDSHAKE_TIMEOUT_MS` 并**被记成一条假的握手超时**
                // （`waitForSocket` 的超时分支只看「等不到 deferred」，不知道握手其实成功了）。
                // 这里立刻把失败交给 `failCarrier`：唤醒等待者、让所有流以真原因结束，
                // 而不是 15 秒后以一个编造的「握手超时」收场。
                //
                // 只在 `deferred` 还没完成时才作废：`complete()` 一旦成功，这条 socket 就是
                // 可用的，后续 `_stats.update` 抛异常不该反过来把健康连接拆掉。
                noteCallbackCrash(cause)
                if (!live.deferred.isCompleted) failCarrier(live, cause)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                if (live.generation != generation || live.dead) return
                _stats.update { it.copy(framesIn = it.framesIn + 1L) }

                // 1) 先严格校验帧形状 —— 与 `client.js:585` 完全同序：parseRemoteStreamServerMessage
                //    先抛错，之后才做 streamId 查表。非法帧（含未知 type）必须 4002 关闭整条连接，
                //    即使它的 streamId 本机并不认识。
                val frame = validate(text) ?: run {
                    _stats.update { it.copy(invalidFrames = it.invalidFrames + 1L) }
                    // 顺序（D5）：**先 close(4002) 再 failCarrier**。反过来的话，`failCarrier`
                    // 一旦抛异常（`recordFailure` 内部），4002 就永远发不出去，而 `failCarrier`
                    // 已经把 `socket = null` 丢掉引用——socket 既不关闭也不可达 = 连接泄漏，
                    // 且异常会被外层 `runCatching` 静默吞掉。协议要求「认不出的帧必须 4002 关闭」，
                    // 这条不该被一个诊断计数路径的意外挡住。
                    webSocket.close(MuxFrame.CLOSE_INVALID_FRAME, INVALID_FRAME_REASON)
                    failCarrier(
                        live,
                        TypertException(TypertException.CODE_INVALID_STREAM_FRAME, INVALID_FRAME_REASON),
                        // 关闭帧刚发出去，别 cancel 掉它（见 failCarrier 的 @param）。
                        cancelSocket = false,
                    )
                    return
                }

                // 2) 再查表；未知 streamId 静默忽略（`client.js:586-587`）。
                val channel = streams[frame.streamId] ?: run {
                    _stats.update { it.copy(orphanFrames = it.orphanFrames + 1L) }
                    return
                }
                when (frame) {
                    is ServerFrame.Item -> channel.trySend(MuxEvent.Item(frame.value))
                    is ServerFrame.End -> channel.trySend(MuxEvent.End)
                    is ServerFrame.Error -> channel.trySend(MuxEvent.Error(frame.code, frame.message, frame.details))
                }
            }.onFailure { noteCallbackCrash(it) }
        }

        /**
         * 二进制帧。
         *
         * 协议只发文本帧（`src/remote-api.ts` 的转发路径全部走文本）。既然「任何认不出的帧
         * 都必须以 4002 关闭整条连接」（`client.js:590-595`），二进制帧就属于认不出的帧：
         * 早前没有这个覆写，OkHttp 的默认实现是**静默忽略**，于是一个代理/中间设备插入的
         * 二进制帧会被无声吞掉，连接状态仍显示健康（R006 P2-7）。
         */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching {
                if (live.generation != generation || live.dead) return
                _stats.update { it.copy(framesIn = it.framesIn + 1L, invalidFrames = it.invalidFrames + 1L) }
                // 同文本分支：先 4002 再 failCarrier（D5）。
                webSocket.close(MuxFrame.CLOSE_INVALID_FRAME, INVALID_FRAME_REASON)
                failCarrier(
                    live,
                    TypertException(TypertException.CODE_INVALID_STREAM_FRAME, INVALID_FRAME_REASON),
                )
            }.onFailure { noteCallbackCrash(it) }
        }

        /**
         * 连接失败 / 连接被对端关闭。
         *
         * `EOFException` 是**最常见的一种 `t`**：服务端进程退出或代理断流时，OkHttp 的读循环
         * 在没收到关闭帧的情况下读到流尾，就抛 `EOFException` 并回调这里。
         *
         * ⚠️ OkHttp **自己**会 catch 读循环里的异常再走 `failWebSocket(e, null)` 进来，
         * 所以这里不是「异常从 OkHttp 回调线程逃逸」的入口。真机上杀死进程的链条是
         * `failCarrier` → `deferred.completeExceptionally` + `channel.close(cause)` →
         * 收集协程在 Main 上重抛（见 [open] 的 KDoc）。本方法的 `runCatching` 只防
         * `failCarrier` 自身抛异常。
         */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runCatching { failCarrier(live, handshakeFailure(t, response)) }
                .onFailure { noteCallbackCrash(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runCatching {
                failCarrier(
                    live,
                    TypertException(TypertException.CODE_TRANSPORT, "WebSocket 已关闭：$code $reason"),
                )
            }.onFailure { noteCallbackCrash(it) }
        }
    }

    /**
     * 记录一次「WebSocket 回调体自己抛了异常」（D4）。
     *
     * 为什么不直接 `Log.w`：本工程**只有** `core/credentials/KeystoreCredentialStore.kt`
     * 允许 `import android.util.Log`（JVM 单测里 `android.util.Log` 是 not-mocked 的桩，
     * 在会被单测走到的代码路径上调它会直接让测试炸）。这个类恰恰是单测覆盖最密的一个。
     *
     * 所以走本工程自己的可观测通道：塞进调试页的故障环（`_errors`）。
     * 只改 `_errors`、**不碰** `_stats.failures` 与 `consecutiveFailures`：
     * 这两项由 [recordFailure] 维护，语义是「载波失败次数」；回调体自身抛异常是
     * 编程错误而不是链路故障，混进去会让「第 N 次失败」的退避提示失真。
     *
     * 自身全程 `runCatching`：这个函数的唯一职责是「不要让一个已经在兜底的路径
     * 再抛出新的异常」。
     */
    private fun noteCallbackCrash(cause: Throwable) {
        runCatching {
            val now = System.currentTimeMillis()
            val summary = "WebSocket 回调异常：${cause.javaClass.simpleName}：${cause.message ?: ""}"
            _errors.update { previous ->
                (
                    listOf(
                        MuxError(
                            id = errorSeq.incrementAndGet(),
                            atMillis = now,
                            summary = summary,
                            code = TypertException.CODE_TRANSPORT,
                        ),
                    ) + previous
                    ).take(ERROR_RING_SIZE)
            }
        }
    }

    /**
     * 把握手失败翻译成可判定的异常。
     *
     * 为什么必须做这一步：WS 握手被插件拒绝时，`src/remote-api.ts:226-243` 直接写
     * `HTTP/1.1 403 Forbidden` + `Connection: close`，OkHttp 只会抛出
     * `ProtocolException("Expected HTTP 101 response but was '403 Forbidden'")`——
     * 一个**没有 code 的普通异常**。上层（`HomeViewModel.retryWhen`）只认
     * `TypertException.isUnpaired`，于是设备被撤销后界面会永远停在「重连中…」，
     * 永远不给用户「重新配对」的引导。所以这里按状态码补出 [TypertException]。
     */
    private fun handshakeFailure(t: Throwable, response: Response?): Throwable {
        val status = response?.code ?: return t
        return when (status) {
            // 插件对未配对 / 已撤销设备的升级一律回 403（`src/remote-api.ts:226-243`）。
            403 -> TypertException(
                TypertException.CODE_UNPAIRED,
                "设备未配对或已被桌面端撤销（HTTP 403）",
                cause = t,
            )

            401 -> TypertException(
                TypertException.CODE_FORBIDDEN_UNAUTH,
                "桌面端要求浏览器鉴权（HTTP 401）",
                cause = t,
            )

            else -> TypertException(
                TypertException.CODE_TRANSPORT,
                "WebSocket 握手失败：HTTP $status",
                cause = t,
            )
        }
    }

    /**
     * socket 失效：唤醒等待者、让所有流以异常结束。
     *
     * **副作用必须幂等**（R006 P1-1 / P2-1）：一次逻辑故障会触发多个回调 —— 非法帧会先
     * `failCarrier` 再 `close(4002)`，随后 `onClosed` 又进来一次；握手超时作废 attempt 后，
     * 被抛弃的旧 socket 稍后也会回调 `onClosed`。早前只有**计数**被 `reported` 挡住，
     * `socket = null` 与 `streams.clear()` 却是无条件的：一个已经作废、本该被忽略的迟到回调
     * 会把重试后刚建好的健康连接和新开的流一起拆掉（旧 socket 引用丢失、永不 close =
     * 连接泄漏），并把同一次故障记成第二次 failures。
     *
     * 因此顺序是：先按 `generation` / `dead` 判活并**提前返回**；然后只在
     * `socket === live.socket` 时才清 mux 级引用（不能把后来者的 socket 清掉）。
     *
     * @param cancelSocket 是否在丢引用时 `cancel()` 掉 socket。默认 `true`（socket 已经
     *   死了或必须立刻拆掉）。**非法帧路径必须传 `false`**：它刚调用 `close(4002, …)`，
     *   优雅关闭的帧还在路上，`cancel()` 会把 TCP 立刻拆掉让 4002 发不出去（R027 首跑
     *   即挂掉两条 4002 用例）。见 [MuxListener.onMessage]。
     */
    private fun failCarrier(live: Attempt, cause: Throwable, cancelSocket: Boolean = true) {
        if (live.generation != generation || live.dead) return
        live.dead = true
        // 同一次断线会有多个回调（非法帧 → onClosed、onFailure → onClosed），只记第一条。
        val firstReport = !live.reported
        live.reported = true
        if (socket === live.socket) socket = null
        if (attempt === live) attempt = null
        // 丢引用必须伴随关闭（D5），但**不能无条件 cancel**：
        // `WebSocket.close(code, reason)` 是优雅关闭（排入关闭帧、等对端回），紧接着
        // `cancel()` 会立刻拆掉 TCP，关闭帧根本发不出去——非法帧路径要求「必须以 4002
        // 关闭整条连接」，无条件 cancel 会让这条协议要求失效（首跑即挂两条 4002 用例）。
        // 所以只在**放弃一条还活着的 socket**时 cancel；`onFailure`/`onClosed` 路径
        // 的 socket 本来就已经死了，`close(4002)` 路径的关闭帧正在飞。
        if (cancelSocket) {
            live.socket?.cancel()
        }
        live.socket = null
        val code = (cause as? TypertException)?.code ?: TypertException.CODE_TRANSPORT
        if (firstReport) recordFailure(code, cause.message ?: cause.javaClass.simpleName)
        live.deferred.completeExceptionally(cause)
        val channels = streams.values.toList()
        streams.clear()
        channels.forEach { it.close(cause) }
    }

    private companion object {
        const val INVALID_FRAME_REASON: String = "invalid Remote stream frame"

        /**
         * 调试页保留的故障条数。
         *
         * 取 20：够看出「是偶发抖动还是每次都挂在同一个地方」，又不至于让内存里的字符串
         * 随会话无限增长（每次失败都会带上 message，含超时时的异常文本）。
         */
        const val ERROR_RING_SIZE = 20

        /**
         * WS 升级（握手）超时。
         *
         * 为什么必须有：`HttpClients.stream()` 为了让长连接的读不超时设了 `readTimeout(0)`，
         * 这同时让 OkHttp 的**握手**等待变成无限。隧道/中继下升级偶发挂死且**不触发任何回调**，
         * 于是 `waitForSocket` 永久挂起、`retryWhen` 永不重试、UI 停在「连接中…」。
         * 取 15 s：公网隧道实测升级约 1.3 s，留足余量又远小于用户可感知的「卡死」。
         */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }
}
