package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 打开一条服务端流。
     *
     * 冷流：每收集一次就新开一条服务端流；收集结束或协程取消时，若该流未正常
     * 终止则自动发 `cancel`（对应 `client.js:417-420`）。服务端 `end` 正常结束，
     * `error` 抛出 [TypertException]。
     */
    fun open(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<JsonElement> =
        callbackFlow {
            val streamId = UUID.randomUUID().toString()
            val inbox = Channel<MuxEvent>(Channel.UNLIMITED)
            streams[streamId] = inbox

            var terminal = false
            try {
                val ws = waitForSocket()
                ws.send(
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
                    socket?.send(
                        buildJsonObject {
                            put("type", JsonPrimitive(MuxFrame.TYPE_CANCEL))
                            put("streamId", JsonPrimitive(streamId))
                        }.toString(),
                    )
                }
            }
        }

    /** 关闭整条 mux 与其上所有流。 */
    suspend fun close() {
        val doomed = mutex.withLock {
            generation++
            val current = socket
            socket = null
            attempt?.deferred?.cancel()
            attempt = null
            current
        }
        doomed?.close(1000, "disposed")
        val channels = streams.values.toList()
        streams.clear()
        channels.forEach { it.close() }
    }

    /**
     * 取得一条 OPEN 状态的 socket，必要时建立连接。
     *
     * 单飞（single-flight）：同一代际内只有一个调用者负责建连，其余等待同一个
     * [CompletableDeferred]（对应 `client.js:553-580` `waitForSocket` 的 waiters 语义）。
     * 若连接在等待期间因断线作废，循环重试。
     */
    private suspend fun waitForSocket(): WebSocket {
        while (true) {
            val (mine, ws) = mutex.withLock {
                socket?.let { return it }
                val gen = generation
                val live = attempt?.takeIf { it.generation == gen }
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
                startConnection(live)
            }
            live.deferred.await()
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

    private inner class MuxListener(private val live: Attempt) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (live.generation != generation) {
                webSocket.close(1000, "stale generation")
                return
            }
            socket = webSocket
            if (attempt === live) attempt = null
            live.deferred.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (live.generation != generation) return

            // 1) 先严格校验帧形状 —— 与 `client.js:585` 完全同序：parseRemoteStreamServerMessage
            //    先抛错，之后才做 streamId 查表。非法帧（含未知 type）必须 4002 关闭整条连接，
            //    即使它的 streamId 本机并不认识。
            val frame = validate(text) ?: run {
                failCarrier(
                    live,
                    TypertException(TypertException.CODE_INVALID_STREAM_FRAME, INVALID_FRAME_REASON),
                )
                webSocket.close(MuxFrame.CLOSE_INVALID_FRAME, INVALID_FRAME_REASON)
                return
            }

            // 2) 再查表；未知 streamId 静默忽略（`client.js:586-587`）。
            val channel = streams[frame.streamId] ?: return
            when (frame) {
                is ServerFrame.Item -> channel.trySend(MuxEvent.Item(frame.value))
                is ServerFrame.End -> channel.trySend(MuxEvent.End)
                is ServerFrame.Error -> channel.trySend(MuxEvent.Error(frame.code, frame.message, frame.details))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failCarrier(live, handshakeFailure(t, response))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failCarrier(
                live,
                TypertException(TypertException.CODE_TRANSPORT, "WebSocket 已关闭：$code $reason"),
            )
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

    /** socket 失效：唤醒等待者、让所有流以异常结束。 */
    private fun failCarrier(live: Attempt, cause: Throwable) {
        if (live.generation != generation) return
        socket = null
        if (attempt === live) attempt = null
        live.deferred.completeExceptionally(cause)
        val channels = streams.values.toList()
        streams.clear()
        channels.forEach { it.close(cause) }
    }

    private companion object {
        const val INVALID_FRAME_REASON: String = "invalid Remote stream frame"
    }
}
