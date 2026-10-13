package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RemoteStreamMux] 的单测 —— 用 MockWebServer 的 WebSocket 升级模拟 mux。
 *
 * 校验点来自 `dsh-api-gateway/lib/client.js`：
 * - `:160-179` `parseRemoteStreamServerMessage` —— 服务端只发 item / end / error 三种帧，
 *   且键必须**恰好**匹配（多一个键都算非法）。
 * - `:581-595` `receive()` —— **先严格校验帧，再按 streamId 查表**；未知 streamId 静默忽略；
 *   任何校验失败都以 **4002** 关闭整条连接。
 * - `:374-423` `open()` —— 客户端发 `{type:'open',streamId,endpoint,payload}`，收到 `item`
 *   产出 `value`，`end` 正常结束，`error` 抛异常，未终止则发 `cancel`。
 * - `src/remote-methods.ts:62` `REMOTE_DEVICE_QUERY = 'device'` —— 握手用 `?device=<id>`。
 *
 * 两个必须遵守的测试写法（都踩过坑）：
 * 1. 用**真实时间**（[runBlocking]），不要用 `runTest` —— 后者会把 `delay` 跳到虚拟时间，
 *    而 MockWebServer 跑在真实线程上，帧顺序就乱了。
 * 2. 服务端的 `onOpen` 里**不能 sleep**（会堵住 MockWebServer 的监听线程，导致
 *    `server.close()` 抛 "Gave up waiting for queue to shut down"）；需要延迟推帧时开独立线程。
 *    同理，`MockWebServer.close()` 会等所有活动 socket 关闭，所以服务端要主动回应关闭握手，
 *    并且每个测试创建的 mux 都要在 [tearDown] 里关掉。
 */
class RemoteStreamMuxTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().build()

    /** 本测试创建过的 mux，[tearDown] 统一关闭，避免 socket 泄漏。 */
    private val muxes = mutableListOf<RemoteStreamMux>()

    /** 服务端收到的客户端文本帧。 */
    private val clientFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 服务端观察到的客户端关闭码。 */
    private val clientCloseCode = AtomicInteger(0)

    @Before
    fun setUp() {
        muxes.clear()
        clientFrames.clear()
        clientCloseCode.set(0)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runBlocking { muxes.toList().forEach { runCatching { it.close() } } }
        muxes.clear()
        // 给服务端一点时间感知关闭握手。
        Thread.sleep(150)
        server.close()
    }

    private fun mux(): RemoteStreamMux = RemoteStreamMux(
        baseUrl = server.url("/").toString().trimEnd('/'),
        deviceId = "dev-42",
        http = http,
        json = DshJson,
    ).also { muxes += it }

    /** 装一个升级到 WebSocket 的服务端；[onOpen] 里不要阻塞。 */
    private fun upgrade(onOpen: (WebSocket) -> Unit) {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        onOpen(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        clientCloseCode.set(code)
                        // 回应关闭握手，否则服务端 socket 一直开着，MockWebServer.close() 会超时。
                        webSocket.close(1000, null)
                    }
                })
                .build(),
        )
    }

    /** 在一个独立线程里，等客户端发出第一帧后再执行 [action]（避免阻塞服务端线程）。 */
    private fun afterFirstClientFrame(action: (streamId: String) -> Unit) {
        Thread {
            pollUntil(5_000) { firstStreamId() }?.let(action)
        }.apply { isDaemon = true }.start()
    }

    private fun firstStreamId(): String? =
        synchronized(clientFrames) { clientFrames.firstOrNull() }
            ?.let { Regex("\"streamId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    /** 轮询等待条件成立并返回值；超时返回 null。 */
    private fun <T> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /** 在 IO 调度器上轮询，避免阻塞 runBlocking 的线程。 */
    private suspend fun <T> awaitOn(timeoutMs: Long, block: () -> T?): T? =
        withContext(Dispatchers.IO) { pollUntil(timeoutMs, block) }

    /** 在协程里收集一条流，返回 job 以便取消。 */
    private fun collectAsync(target: RemoteStreamMux, endpoint: String) =
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { withTimeout(20_000) { target.open(endpoint).toList() } }
        }

    @Test
    fun handshakeUrlCarriesDeviceQueryParameter() = runBlocking {
        upgrade { /* 保持连接打开即可 */ }

        val target = mux()
        // 握手 URL 形状：ws(s)://host/remote/api/remote.mux?device=<id>
        assertTrue(target.url.startsWith("ws://"))
        assertTrue(target.url.endsWith("/remote/api/remote.mux?device=dev-42"))

        val job = collectAsync(target, "workspace/follow")
        val recorded = awaitOn(10_000) { server.takeRequest(1, TimeUnit.SECONDS) }
            ?: error("10 秒内没有收到 WebSocket 握手请求")
        job.cancel()

        assertEquals("GET", recorded.method)
        assertEquals("/remote/api/remote.mux", recorded.url.encodedPath)
        assertEquals("dev-42", recorded.url.queryParameter("device"))
    }

    @Test
    fun openSendsExactOpenFrameAndEmitsItemValues() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame { id ->
                ws.send("""{"type":"item","streamId":"$id","value":{"type":"upsert","workspaceId":"w1"}}""")
                ws.send("""{"type":"item","streamId":"$id","value":{"type":"remove","workspaceId":"w2"}}""")
                ws.send("""{"type":"end","streamId":"$id"}""")
            }
        }

        val values = withTimeout(20_000) {
            mux().open("workspace/follow", TypertClient.argsOf("x" to JsonPrimitive(1))).toList()
        }

        assertEquals(2, values.size)
        assertEquals("upsert", ((values[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("remove", ((values[1] as JsonObject)["type"] as JsonPrimitive).content)

        // 客户端帧必须是 {type:'open',streamId,endpoint,payload:{args}}，不多不少。
        val open = synchronized(clientFrames) { clientFrames.first() }
        val openObj = DshJson.parseToJsonElement(open) as JsonObject
        assertEquals(setOf("type", "streamId", "endpoint", "payload"), openObj.keys)
        assertEquals("open", (openObj["type"] as JsonPrimitive).content)
        assertEquals("workspace/follow", (openObj["endpoint"] as JsonPrimitive).content)
        assertEquals(setOf("args"), (openObj["payload"] as JsonObject).keys)
        assertEquals(
            "1",
            (((openObj["payload"] as JsonObject)["args"] as JsonObject)["x"] as JsonPrimitive).content,
        )
        // 正常 end 之后不能再发 cancel。
        assertTrue(synchronized(clientFrames) { clientFrames.none { it.contains("\"cancel\"") } })
    }

    @Test
    fun serverErrorFrameFailsTheFlowWithServerCode() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame { id ->
                ws.send(
                    """{"type":"error","streamId":"$id","error":
                        {"code":"unpaired","message":"not paired","details":{}}}""",
                )
            }
        }

        try {
            withTimeout(20_000) { mux().open("session/control").toList() }
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals("unpaired", e.code)
            assertTrue(e.isUnpaired)
        }
    }

    @Test
    fun cancellingCollectionSendsCancelFrame() = runBlocking {
        upgrade { /* 保持打开，不推帧 */ }

        val target = mux()
        val job = collectAsync(target, "session/control")
        assertTrue("客户端没有发出 open 帧", awaitOn(10_000) { firstStreamId() } != null)

        job.cancel()
        val sawCancel = awaitOn(10_000) {
            synchronized(clientFrames) { clientFrames.any { it.contains("\"cancel\"") } }.takeIf { it }
        }

        assertTrue("取消收集后必须补发 cancel 帧", sawCancel == true)
    }

    @Test
    fun malformedServerFrameClosesClientWith4002() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame {
                // 未知 type：按 client.js:581-595 客户端必须自行以 4002 关闭整条连接。
                ws.send("""{"type":"bogus","streamId":"whatever"}""")
            }
        }

        val target = mux()
        val job = collectAsync(target, "session/control")
        val code = awaitOn(10_000) { clientCloseCode.get().takeIf { it != 0 } }
        job.cancel()

        assertEquals(MuxFrame.CLOSE_INVALID_FRAME, code)
        // 连接作废后不能再被认为可用。
        assertTrue(!target.isConnected)
    }

    @Test
    fun malformedFrameWithUnknownStreamIdStillClosesWith4002() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame {
                // 关键回归：帧形状非法时，即使 streamId 本机不认识，也必须 4002 关闭。
                ws.send("""{"type":"item","streamId":"ghost","value":{},"extra":1}""")
            }
        }

        val target = mux()
        val job = collectAsync(target, "session/control")
        val code = awaitOn(10_000) { clientCloseCode.get().takeIf { it != 0 } }
        job.cancel()

        assertEquals(MuxFrame.CLOSE_INVALID_FRAME, code)
    }

    @Test
    fun frameForUnknownStreamIsIgnoredNotFatal() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame { id ->
                // 形状合法但 streamId 未知 → 静默忽略（client.js:586-587）。
                ws.send("""{"type":"item","streamId":"ghost","value":{"x":1}}""")
                ws.send("""{"type":"end","streamId":"$id"}""")
            }
        }

        val values = withTimeout(20_000) { mux().open("workspace/follow").toList() }

        assertTrue(values.isEmpty())
        assertEquals(0, clientCloseCode.get())
    }
}
