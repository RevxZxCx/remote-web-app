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

    /** 在一个独立线程里，等客户端发出第 [nth] 帧后再执行 [action]（避免阻塞服务端线程）。 */
    private fun afterNthClientFrame(nth: Int, action: (streamId: String) -> Unit) {
        Thread {
            pollUntil(5_000) { nthStreamId(nth) }?.let(action)
        }.apply { isDaemon = true }.start()
    }

    private fun firstStreamId(): String? = nthStreamId(1)

    private fun nthStreamId(nth: Int): String? =
        synchronized(clientFrames) { clientFrames.getOrNull(nth - 1) }
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

    // ---------------------------------------------------------------- M4 诊断（调试页数据源）

    @Test
    fun lateCallbackFromDeadSocketDoesNotTearDownNewConnection() = runBlocking {
        // R006 P1-1：一次逻辑故障会触发**多个**回调 —— 非法帧先 `failCarrier`，随后
        // `close(4002)` 又带进一个 `onClosed`。旧实现只让 `reported` 挡住计数，`socket = null`
        // 与 `streams.clear()` 却是无条件的：一个已经作废、本该被忽略的迟到回调会把重试后刚
        // 建好的健康连接和新开的流一起拆掉（旧 socket 引用丢失、永不 close = 连接泄漏）。
        //
        // 本用例把两条路径都走到：第一次连接因非法帧作废，之后重开一条流拿到**新**连接，
        // 再让旧 socket 的迟到 `onClosed` 发生——新流必须不受影响。
        upgrade { ws ->
            afterNthClientFrame(1) {
                ws.send("""{"type":"bogus","streamId":"whatever"}""")
            }
        }
        // 第二条连接：正常服务。**必须按第 2 帧取 streamId** —— `clientFrames` 是累计的，
        // 取「第一帧」会拿到已作废那条连接的旧 id，客户端只会把它当孤儿帧忽略。
        upgrade { ws ->
            afterNthClientFrame(2) { id ->
                ws.send("""{"type":"item","streamId":"$id","value":{"n":42}}""")
                ws.send("""{"type":"end","streamId":"$id"}""")
            }
        }

        val target = mux()

        // 第一条流：被非法帧打死。
        val first = collectAsync(target, "session/control")
        awaitOn(10_000) { clientCloseCode.get().takeIf { it != 0 } }
        first.cancel()

        // 第二条流：走新连接，必须能正常收到数据。
        val values = withTimeout(20_000) { target.open("workspace/follow").toList() }

        assertEquals(1, values.size)
        assertEquals(JsonPrimitive(42), (values.single() as JsonObject)["n"])
        // 故障只被记一次（迟到回调没有重复 recordFailure）。
        assertEquals(1L, target.stats.value.failures)
        assertTrue("新连接应当是健康的，实际 carrier=${target.stats.value.carrier}", target.isConnected)
    }

    @Test
    fun statsCountOpenAndFramesInOutAfterSuccess() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame { id ->
                ws.send("""{"type":"item","streamId":"$id","value":{"n":1}}""")
                ws.send("""{"type":"end","streamId":"$id"}""")
            }
        }

        val target = mux()
        withTimeout(20_000) { target.open("workspace/follow").toList() }

        val stats = target.stats.value
        assertEquals(1L, stats.opens)
        assertEquals(2L, stats.framesIn)
        // open + cancel 之外的正常路径只有 open 一帧（end 由服务端发起）。
        assertTrue("应当至少发出 open 帧，实际 ${stats.framesOut}", stats.framesOut >= 1L)
        assertEquals(0L, stats.invalidFrames)
        assertEquals(0L, stats.orphanFrames)
        assertTrue(stats.carrier is CarrierState.Connected)
    }

    @Test
    fun statsCountInvalidFramesAndClearConnectedCarrier() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame {
                ws.send("""{"type":"item","streamId":"x","value":{},"extra":1}""")
            }
        }

        val target = mux()
        val job = collectAsync(target, "session/control")
        awaitOn(10_000) { clientCloseCode.get().takeIf { it != 0 } }
        job.cancel()

        val stats = target.stats.value
        assertEquals(1L, stats.invalidFrames)
        assertEquals(1L, stats.failures)
        // 非法帧让 socket 作废，状态不能再声称已连接。
        assertTrue("实际 carrier=${stats.carrier}", stats.carrier !is CarrierState.Connected)
        assertTrue(!target.isConnected)
        assertEquals(1, target.errors.value.size)
    }

    @Test
    fun statsCountOrphanFramesSeparatelyFromInvalid() = runBlocking {
        upgrade { ws ->
            afterFirstClientFrame { id ->
                // 合法但没人认领：不能算非法（否则会把正常的多路复用误判成协议违规）。
                ws.send("""{"type":"item","streamId":"ghost","value":{"a":1}}""")
                ws.send("""{"type":"end","streamId":"$id"}""")
            }
        }

        val target = mux()
        withTimeout(20_000) { target.open("workspace/follow").toList() }

        val stats = target.stats.value
        assertEquals(1L, stats.orphanFrames)
        assertEquals(0L, stats.invalidFrames)
        assertEquals(0, clientCloseCode.get())
    }

    @Test
    fun handshakeFailureOnHttpErrorIsRecordedAndCarrierNotConnected() = runBlocking {
        // 普通 403（没有 101 升级）→ OkHttp 抛的是没有 code 的 ProtocolException，
        // mux 必须把它翻译成 unpaired（否则上层永远停在「重连中…」，见 M1 R002 P1-1）。
        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())

        val target = mux()
        val job = collectAsync(target, "workspace/follow")
        val failed = awaitOn(15_000) {
            target.stats.value.takeIf { it.failures > 0L }
        }
        job.cancel()

        assertTrue("15 秒内没有记录握手失败", failed != null)
        assertTrue("实际 carrier=${failed!!.carrier}", failed.carrier !is CarrierState.Connected)
        assertEquals(0L, failed.opens)
        assertEquals(1, target.errors.value.size)
        assertTrue(
            "故障码应当是 unpaired，实际 ${target.errors.value.first().code}",
            target.errors.value.first().code == TypertException.CODE_UNPAIRED,
        )
    }

    @Test
    fun noteRetryProjectsIntoCarrierStateAndDoesNotAccumulate() = runBlocking {
        val target = mux()

        target.noteRetry(reason = "transport：网络请求失败", delayMillis = 2_000L)
        val first = target.stats.value
        assertTrue(first.carrier is CarrierState.Reconnecting)
        assertEquals("transport：网络请求失败", first.lastFailure)
        val reconnecting = first.carrier as CarrierState.Reconnecting
        assertTrue(
            "重试时刻应当已按 delayMillis 推到未来：${reconnecting.retryAtMillis}",
            reconnecting.retryAtMillis >= System.currentTimeMillis() + 1_000L,
        )

        // **noteRetry 是纯投影，不累加 consecutiveFailures**（R006 P2-6）：连续失败次数由
        // recordFailure 维护。早前这里也 +1，于是同一次故障会被 failCarrier 与订阅方各算一次，
        // 报成「第 4 次失败」而实际只是第 2 次。
        val before = target.stats.value.consecutiveFailures
        target.noteRetry(reason = "transport：网络请求失败", delayMillis = 4_000L)
        assertEquals(before, target.stats.value.consecutiveFailures)
    }

    @Test
    fun noteRetryIsIdempotentForTheSameLogicalFailure() = runBlocking {
        val target = mux()

        // 同一逻辑故障被两个 ViewModel（或同一 ViewModel 重订阅）汇报两次。
        target.noteRetry(reason = "transport：网络请求失败", delayMillis = 2_000L)
        target.noteRetry(reason = "transport：网络请求失败", delayMillis = 2_000L)
        target.noteRetry(reason = "transport：网络请求失败", delayMillis = 2_000L)

        val stats = target.stats.value
        assertEquals(0L, stats.consecutiveFailures)
        assertEquals(1L, (stats.carrier as CarrierState.Reconnecting).attempt)
    }

    @Test
    fun noteRetryWithUnpairedIsTerminalAndDoesNotAccumulate() = runBlocking {
        val target = mux()

        target.noteRetry(reason = "设备未配对", delayMillis = 0L, unpaired = true)
        val stats = target.stats.value

        assertTrue(stats.carrier is CarrierState.Unpaired)
        // 终态：再重试多少次都不会成功，不该被计成「第 N 次失败」。
        assertEquals(0L, stats.consecutiveFailures)
        assertEquals("设备未配对", (stats.carrier as CarrierState.Unpaired).reason)
    }

    @Test
    fun closeReturnsCarrierToIdleAndClearsLastFailure() = runBlocking {
        val target = mux()
        target.noteRetry(reason = "transport：挂了", delayMillis = 1_000L)

        target.close()

        val stats = target.stats.value
        assertEquals(CarrierState.Idle, stats.carrier)
        // 主动关闭不是故障。
        assertEquals(null, stats.lastFailure)
        assertTrue(!target.isConnected)
    }

    @Test
    fun errorRingKeepsNewestFirstAndIsBounded() = runBlocking {
        val target = mux()

        // 直接打 noteRetry 不会进环形缓冲（它不是故障记录），所以用真实失败路径：
        // 连续 403 握手失败，每次一条。
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())
        }
        repeat(3) { index ->
            val job = collectAsync(target, "workspace/follow")
            awaitOn(15_000) { target.stats.value.takeIf { it.failures > index } }
            job.cancel()
            Thread.sleep(100)
        }

        val errors = target.errors.value
        assertEquals(3, errors.size)
        // 新的在前。
        assertTrue(errors[0].atMillis >= errors[2].atMillis)
        assertTrue(errors.all { it.code == TypertException.CODE_UNPAIRED })
    }
}
