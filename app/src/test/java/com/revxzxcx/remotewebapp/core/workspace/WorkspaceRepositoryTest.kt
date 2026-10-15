package com.revxzxcx.remotewebapp.core.workspace

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * [WorkspaceRepository] 的回归测试。
 *
 * **被守护的缺陷（M3 真机定位）**：旧实现用 `.scan(WorkspaceSnapshot()) { … }` 折叠帧，
 * 而 `scan` 会把**种子值立刻发射一次**，于是订阅者在服务端第一帧到达之前就先收到一个**空快照**。
 * 真机 logcat 实测到的后果：
 * ```
 * DSH-HOME: snapshot collected items=0 loading=true      ← scan 的种子空快照
 * DSH-SESS: load() ok items=51 in 2625ms
 * DSH-HOME: snapshot collected items=5 loading=false     ← 真快照 2.9s 后才到
 * ```
 * 1. 冷启动时主区立刻显示「已连接 / 这个桌面端还没有工作区。」，把「还没读到」谎报成
 *    「确实没有」（真机 5 连击冷启动里 2 次命中空列表）；
 * 2. 断线重连会重新订阅，种子空快照把列表清空一次——正是 [WorkspaceRepository] 的 KDoc
 *    声称要避免的「重连清空 UI」，当时的结论只防了 `onStart`，却被 `scan` 的初始发射抵消。
 *
 * 所以本测试的第一条断言就是：**服务端第一帧到达之前必须一帧都不发射**。
 *
 * 测试写法沿用 [com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMuxTest] 的两条纪律：
 * 用真实时间（[runBlocking]，不用 `runTest`），且服务端 `onOpen` 里不要 sleep
 * （需要延迟推帧时由测试自己等）。
 *
 * ⚠️ 服务端必须按官方 mux 线格式推帧：工作区帧要包在
 * `{"type":"item","streamId":"<客户端 open 帧里的 id>","value":<工作区帧>}` 里。
 * 直接把工作区帧裸发会被 mux 判为非法帧并以 4002 关闭（`RemoteStreamMux.validate`）。
 */
class WorkspaceRepositoryTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().build()

    /** 服务端侧持有的 WebSocket，用于测试主动推帧。 */
    private val sockets: MutableList<WebSocket> = Collections.synchronizedList(mutableListOf())

    /** 服务端收到的客户端文本帧。 */
    private val clientFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val jobs = mutableListOf<Job>()

    /** R019：`DshConnection` 要一个 scope 供 `$events` 长驻订阅用，测试里逐个登记逐个取消。 */
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        sockets.clear()
        clientFrames.clear()
        jobs.clear()
        scopes.forEach { it.cancel() }
        scopes.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        scopes.forEach { it.cancel() }
        runBlocking {
            withContext(Dispatchers.IO) {
                sockets.toList().forEach { runCatching { it.close(1000, null) } }
            }
        }
        Thread.sleep(150)
        server.close()
    }

    private fun connection(): DshConnection = DshConnection(
        credential = DeviceCredential(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "dev-42",
            pairedAt = 0L,
        ),
        unaryHttp = http,
        streamHttp = http,
        // R019：`$events` 的长驻订阅要一个应用级 scope（`DshConnection` 的 KDoc 说明）。
        // 测试里给一个独立的 SupervisorJob 并登记，`tearDown` 里 cancel 掉，避免泄漏。
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it },
    )

    private fun upgrade() {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets += webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
                .build(),
        )
    }

    /** 收集快照流；每一帧都追加到 [sink]。 */
    private fun collectAsync(sink: MutableList<WorkspaceSnapshot>) {
        val repository = WorkspaceRepository(connection())
        jobs += CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                withTimeout(20_000) {
                    repository.snapshotFlow().collect { snapshot -> sink += snapshot }
                }
            }
        }
    }

    private suspend fun <T> awaitOn(timeoutMs: Long, block: () -> T?): T? =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                block()?.let { return@withContext it }
                Thread.sleep(25)
            }
            null
        }

    /** 等服务端拿到 socket 并读到客户端的第一条 open 帧，返回其中的 streamId。 */
    private suspend fun awaitStreamId(): String {
        val frame = awaitOn(10_000) { synchronized(clientFrames) { clientFrames.firstOrNull() } }
            ?: error("10 秒内没有收到客户端的 open 帧")
        val obj = DshJson.parseToJsonElement(frame).jsonObject
        assertEquals("open", obj.getValue("type").jsonPrimitive.content)
        assertEquals(
            "workspace/follow",
            obj.getValue("endpoint").jsonPrimitive.content,
        )
        return obj.getValue("streamId").jsonPrimitive.content
    }

    private suspend fun socket(): WebSocket =
        awaitOn(10_000) { synchronized(sockets) { sockets.firstOrNull() } }
            ?: error("10 秒内没有建立 WebSocket")

    /** 把一条工作区帧按官方 mux 线格式包成服务端 item 帧。 */
    private fun item(streamId: String, payload: String): String =
        """{"type":"item","streamId":"$streamId","value":$payload}"""

    private fun baseline(): String = """
        {"type":"baseline","value":{"items":[
          {"workspaceId":"w1","path":"F:\\bot","title":"bot","sessionIds":["s1","s2"],
           "createdAt":"2026-10-12T15:52:26.030Z","updatedAt":"2026-10-12T15:52:26.030Z"}
        ],"archivedSessionIds":[],"pinnedSessionIds":[]}}
    """.trimIndent()

    /**
     * 核心回归：服务端还没发第一帧时，流必须**一帧都不发射**。
     *
     * 旧实现（`.scan(WorkspaceSnapshot())`）会在这里立刻拿到 1 个空快照。
     */
    @Test
    fun emitsNothingUntilFirstServerFrame() = runBlocking {
        upgrade()
        val seen = Collections.synchronizedList(mutableListOf<WorkspaceSnapshot>())
        collectAsync(seen)

        val ws = socket()
        val streamId = awaitStreamId()

        // 等满 2 秒：足够 scan 的种子发射出来，也足够证明「不发」是稳定行为而不是刚好没跑到。
        withContext(Dispatchers.IO) { Thread.sleep(2_000) }
        assertEquals("第一帧之前不得发射任何快照", 0, synchronized(seen) { seen.size })

        // 真帧到了才开始发射。
        ws.send(item(streamId, baseline()))
        val got = awaitOn(10_000) { synchronized(seen) { seen.firstOrNull() } }
            ?: error("收到 baseline 后仍未发射快照")

        assertEquals(1, got.items.size)
        assertEquals("w1", got.items[0].workspaceId)
        assertEquals("bot", got.items[0].displayName)
        assertEquals(2, got.items[0].sessionIds.size)

        // 后续帧继续折叠（证明换成 null 种子没有破坏累加语义）。
        ws.send(
            item(
                streamId,
                """{"type":"upsert","workspace":{"workspaceId":"w2","path":"F:\\new work","title":"new work","sessionIds":[]}}""",
            ),
        )
        val two = awaitOn(10_000) {
            synchronized(seen) { seen.lastOrNull()?.takeIf { it.items.size == 2 } }
        } ?: error("upsert 之后快照没有变成 2 个工作区")
        assertEquals("w2", two.items[1].workspaceId)
    }

    /**
     * `baseline` 之后是 `upsert` 累加，再来一个 `baseline` 必须**整表替换**而不是合并。
     */
    @Test
    fun baselineAfterUpsertReplacesWholeSnapshot() = runBlocking {
        upgrade()
        val seen = Collections.synchronizedList(mutableListOf<WorkspaceSnapshot>())
        collectAsync(seen)
        val ws = socket()
        val streamId = awaitStreamId()

        ws.send(
            item(
                streamId,
                """{"type":"upsert","workspace":{"workspaceId":"w9","path":"/tmp","title":"t9","sessionIds":[]}}""",
            ),
        )
        awaitOn(10_000) { synchronized(seen) { seen.lastOrNull()?.takeIf { it.items.size == 1 } } }
            ?: error("首个 upsert 没有生效")

        ws.send(item(streamId, baseline()))
        val replaced = awaitOn(10_000) {
            synchronized(seen) {
                seen.lastOrNull()?.takeIf { it.items.size == 1 && it.items[0].workspaceId == "w1" }
            }
        } ?: error("baseline 之后没有整表替换")

        assertEquals("w1", replaced.items[0].workspaceId)
    }

    /** 未知的工作区帧类型必须被忽略（服务端加新帧类型不能让 App 崩或断流）。 */
    @Test
    fun unknownFrameTypeIsIgnored() = runBlocking {
        upgrade()
        val seen = Collections.synchronizedList(mutableListOf<WorkspaceSnapshot>())
        collectAsync(seen)
        val ws = socket()
        val streamId = awaitStreamId()

        ws.send(item(streamId, """{"type":"something-new","payload":{}}"""))
        ws.send(item(streamId, baseline()))
        val got = awaitOn(10_000) { synchronized(seen) { seen.firstOrNull() } }
            ?: error("baseline 没有到达")

        assertEquals(1, got.items.size)
        // 未知帧不该产生额外发射。
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertEquals(1, synchronized(seen) { seen.size })
    }

    /** 订阅必须打 `workspace/follow`、无参数（多一个键服务端会 `gateway/arguments-invalid`）。 */
    @Test
    fun subscribesWithExactEndpointAndNoArguments() = runBlocking {
        upgrade()
        val seen = Collections.synchronizedList(mutableListOf<WorkspaceSnapshot>())
        collectAsync(seen)

        val recorded = awaitOn(10_000) { server.takeRequest(1, TimeUnit.SECONDS) }
            ?: error("10 秒内没有收到 WebSocket 握手请求")
        val streamId = awaitStreamId()
        assertTrue("streamId 必须是 UUID 形状", streamId.length == 36)

        assertEquals("/remote/api/remote.mux", recorded.url.encodedPath)
        assertEquals("dev-42", recorded.url.queryParameter("device"))

        val obj = DshJson.parseToJsonElement(
            synchronized(clientFrames) { clientFrames.first() },
        ).jsonObject
        // 确认 payload.args 是空对象而不是漏字段（漏了会解析成 JsonNull，服务端报 arguments-invalid）。
        val args = obj.getValue("payload").jsonObject["args"]
        assertTrue("args 必须是 JsonObject 而不是 JsonNull", args is JsonObject)
        assertTrue("workspace/follow 无参数，args 必须为空对象", (args as JsonObject).isEmpty())
    }
}
