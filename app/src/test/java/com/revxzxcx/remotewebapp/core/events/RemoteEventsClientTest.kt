package com.revxzxcx.remotewebapp.core.events

import com.revxzxcx.remotewebapp.core.session.AskAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * [RemoteEventsClient] 的单测 —— 用 MockWebServer 同时模拟 mux WebSocket 与 unary RPC。
 *
 * 校验点来自实测（`F:\bot\_evprobe.mjs` 打真实桌面端）：
 * - `$events` 流的首帧是 `{type:'ready',clientId,host:{home}}`，**`clientId` 必须记住**，
 *   回答时原样带回（`F:\bot\_approval_probe2.mjs` 证实传假 clientId 回
 *   `gateway/internal: ... identifies no active event stream`）。
 * - 回答走 unary `POST {base}/remote/api/$events/result`，args 键集必须**恰好**
 *   `{clientId,eventId,outcome}`（实测多一个键回 `api gateway: invalid Remote event result`）。
 * - waterfall 帧形状 `{type:'waterfall',event,eventId,agentId,request}`；
 *   **同一个 eventId 可能重复投递**（服务端补发），必须去重。
 * - `cancel` 帧把请求摘掉（模型自己放弃了）。
 *
 * 写法约束与 [com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMuxTest] 相同：
 * 用**真实时间**（`runBlocking` + 真实 `delay`），不用 `runTest`；服务端 `onOpen` 里不阻塞。
 */
class RemoteEventsClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().build()

    /** 服务端收到的客户端文本帧（mux）。 */
    private val clientFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 服务端收到的 unary 请求体。 */
    private val unaryBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val clients = mutableListOf<RemoteEventsClient>()
    private val jobs = mutableListOf<kotlinx.coroutines.Job>()

    /** WebSocket 升级成功后的回调；空表示没人接（升级仍然成功）。 */
    private var wsListener: (WebSocket) -> Unit = {}

    /** true = 用 403 拒绝升级，模拟设备已被桌面端撤销。 */
    private var wsReject = false

    /** unary 请求的响应工厂；测试按需切换成功/失败信封。 */
    private var unaryResponse: () -> MockResponse = { okEnvelope() }

    /** WebSocket 升级尝试次数。用来证明「unpaired 之后不再重试」。 */
    private var wsAttempts = 0

    /** unary 响应的人为延迟（毫秒）。用来在 answer 的往返窗口里制造并发。 */
    private var unaryDelayMs = 0L

    private fun okEnvelope(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body("""{"type":"server-response","result":{"ok":true,"value":{}}}""")
            .build()

    @Before
    fun setUp() {
        clientFrames.clear()
        unaryBodies.clear()
        jobs.clear()
        clients.clear()
        wsListener = {}
        wsReject = false
        wsAttempts = 0
        unaryDelayMs = 0L
        unaryResponse = { okEnvelope() }
        server = MockWebServer()
        // 必须自备 dispatcher：默认 QueueDispatcher 只认 enqueue，既不记录请求体，
        // 也无法让「同一条 WS 连接」与「多个 unary 响应」共存于一个服务端上。
        // 首版测试用 enqueue 装升级响应、又指望 unaryBodies 被填满 —— unary 走的是
        // dispatcher，QueueDispatcher 不会把请求体交出来，于是断言必然失败。
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.headers["Upgrade"]?.contains("websocket") == true) {
                    wsAttempts += 1
                    if (wsReject) {
                        return MockResponse.Builder()
                            .code(403)
                            .body(
                                """{"type":"server-response","result":{"ok":false,"error":{"code":"unpaired","message":"nope"}}}""",
                            )
                            .build()
                    }
                    return MockResponse.Builder()
                        .webSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    wsListener(webSocket)
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    clientFrames += text
                                }

                                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                    webSocket.close(1000, null)
                                }
                            },
                        )
                        .build()
                }
                unaryBodies += request.body?.utf8().orEmpty()
                if (unaryDelayMs > 0) Thread.sleep(unaryDelayMs)
                return unaryResponse()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        clients.forEach { runCatching { it.close() } }
        Thread.sleep(150)
        server.close()
    }

    private fun mux(url: String) = com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux(
        baseUrl = url,
        deviceId = "dev-42",
        http = http,
        json = com.revxzxcx.remotewebapp.core.protocol.DshJson,
    )

    private fun typert(url: String) = com.revxzxcx.remotewebapp.core.protocol.TypertClient(
        baseUrl = url,
        deviceId = "dev-42",
        http = http,
        json = com.revxzxcx.remotewebapp.core.protocol.DshJson,
    )

    private fun client(): RemoteEventsClient {
        val base = server.url("/").toString().trimEnd('/')
        return RemoteEventsClient(
            mux = mux(base),
            typert = typert(base),
            scope = CoroutineScope(Dispatchers.IO),
        ).also { clients += it }
    }

    /**
     * 服务端帧必须回客户端 open 帧里那个 streamId。
     *
     * mux 的 open() 自己生成 streamId，认不出来的 streamId 会被当孤儿帧静默丢弃 ——
     * 早期版本在测试里硬编码 "ev-1"，结果 9 条用例 8 条收不到任何帧。
     */
    private fun serverStreamId(): String =
        clientFrames.firstNotNullOfOrNull { frame ->
            Regex("\"streamId\":\"([^\"]+)\"").find(frame)?.groupValues?.get(1)
        } ?: "ev-1"

    /** 包一条 $events 服务端帧（item + streamId + value）。 */
    private fun serverFrame(value: String): String =
        """{"type":"item","streamId":"${serverStreamId()}","value":$value}"""

    /**
     * 装一个升级到 WebSocket 的服务端；[onOpen] 里不要阻塞。
     *
     * 升级本身由 [setUp] 的 dispatcher 处理，这里只登记拿到 socket 之后的动作。
     */
    private fun upgrade(onOpen: (WebSocket) -> Unit) {
        wsListener = onOpen
    }

    /** 让下一个 unary 回成功信封。 */
    private fun expectUnaryOk() {
        unaryResponse = { okEnvelope() }
    }

    /** 让下一个 unary 回失败信封（网络抖动 / 服务端拒绝）。 */
    private fun expectUnaryFailure(message: String = "boom") {
        unaryResponse = {
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"type":"server-response","result":{"ok":false,"error":{"code":"transport","message":"$message"}}}""",
                )
                .build()
        }
    }

    private suspend fun awaitOn(timeoutMs: Long, block: () -> Boolean) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return@withContext true
            Thread.sleep(20)
        }
        false
    }

    private fun startAsync(target: RemoteEventsClient) {
        target.start()
    }

    /** 一条审批 waterfall 帧的 JSON。 */
    private fun waterfall(eventId: String, tool: String = "pwsh", agentId: String = "session-a"): String =
        buildJsonObject {
            put("type", JsonPrimitive("waterfall"))
            put("event", JsonPrimitive("approval/request"))
            put("eventId", JsonPrimitive(eventId))
            put("agentId", JsonPrimitive(agentId))
            put(
                "request",
                buildJsonObject {
                    put("toolName", JsonPrimitive(tool))
                    put("callId", JsonPrimitive("call_1"))
                    put("reason", JsonPrimitive("escalate sandbox to danger-full-access"))
                    put(
                        "displayReason",
                        buildJsonObject {
                            put("zh", JsonPrimitive("要提权到完全权限"))
                            put("en", JsonPrimitive("Escalate to full access"))
                        },
                    )
                },
            )
        }.toString()

    @Test
    fun readyFramePublishesClientIdAndCountsTheFrame() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null })
        // 客户端必须先发 open，服务端才回 ready —— 顺序反了就是协议理解错了。
        assertTrue(awaitOn(8_000) { clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1","host":{"home":"C:\\x"}}"""))

        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        assertEquals(1L, target.stats.value.framesIn)
        // 错误说明必须在 ready 时清掉：上一次订阅失败不该在重连成功后继续挂着。
        assertNull(target.error.value)
    }

    @Test
    fun approvalRequestAppearsOnceEvenWhenDeliveredTwice() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })
        // 服务端补发同一 eventId（实测会发生）——去重而不是追加。
        socket.send(serverFrame(waterfall("ev-a")))
        Thread.sleep(300)

        assertEquals(1, target.approvals.value.size)
        val request = target.approvals.value.single()
        assertEquals("pwsh", request.toolName)
        assertEquals("session-a", request.agentId)
        // 中文优先：桌面端按浏览器语言填 displayReason，中文用户看 en 就是「没翻译」。
        assertEquals("要提权到完全权限", request.reasonText())
        assertTrue(request.belongsTo("session-a"))
        assertTrue(!request.belongsTo("session-b"))
        assertEquals(1L, target.stats.value.approvalsSeen)
        // 收到两帧就计两次 —— waterfalls 数的是「帧」，approvalsSeen 数的是「请求」。
        // 二者不相等恰好证明去重发生在渲染层而不是计数层。
        assertEquals(2L, target.stats.value.waterfalls)
    }

    @Test
    fun cancelFrameRemovesTheRequest() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.isNotEmpty() })

        socket.send(serverFrame("""{"type":"cancel","eventId":"ev-a"}"""))
        assertTrue(awaitOn(8_000) { target.approvals.value.isEmpty() })
        // 被取消的 eventId 也要记账：服务端补发一次不该弹出一张已经取消的卡片。
        socket.send(serverFrame(waterfall("ev-a")))
        Thread.sleep(300)
        assertTrue(target.approvals.value.isEmpty())
    }

    @Test
    fun answeredEventIdIsNotResurrectedByARedelivery() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        expectUnaryOk()
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })
        target.answer("ev-a", allow = true)
        assertTrue(awaitOn(8_000) { target.approvals.value.isEmpty() })

        // 服务端补发同一条 waterfall。回答已经把卡片摘走了，若去重只看「当前列表」，
        // 这张卡片会被插回来，而且永远点不动（服务端早已视为已回答）。
        socket.send(serverFrame(waterfall("ev-a")))
        Thread.sleep(300)

        assertTrue(target.approvals.value.isEmpty())
        assertEquals(1L, target.stats.value.approvalsSeen)
        assertEquals(1L, target.stats.value.approvalAnswers)
    }

    @Test
    fun dismissedEventIdIsNotResurrectedByARedelivery() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })
        target.dismiss("ev-a")
        assertTrue(awaitOn(8_000) { target.approvals.value.isEmpty() })

        socket.send(serverFrame(waterfall("ev-a")))
        Thread.sleep(300)

        assertTrue(target.approvals.value.isEmpty())
        assertEquals(1L, target.stats.value.approvalsSeen)
    }

    @Test
    fun newStreamForgivesHandledEventIds() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })
        target.dismiss("ev-a")

        // 新流 = 新一批 eventId。旧流上「已处理」的记账必须清掉，
        // 否则同一 eventId 在新流里会被误判为已处理而静默丢弃。
        socket.send(serverFrame("""{"type":"ready","clientId":"cid-2"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-2" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })
    }

    @Test
    fun closeIsIdempotent() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        target.close()
        target.close()
        target.close()

        assertNull(target.clientId.value)
        assertTrue(target.approvals.value.isEmpty())
        assertNull(target.error.value)
    }

    @Test
    fun emitFramesAreCountedButIgnored() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame("""{"type":"emit","event":"commands/change","args":{}}"""))
        assertTrue(awaitOn(8_000) { target.stats.value.emits == 1L })

        // 刻意不接：白名单里其余事件与本 App 无关（聊天页的 session/follow 已覆盖）。
        assertTrue(target.approvals.value.isEmpty())
        assertNull(target.error.value)
    }

    @Test
    fun nonApprovalWaterfallIsNotRenderedAsApproval() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        // 没有 `questions` 数组的提问帧解析不出任何题 → 既不渲染成审批，也不渲染成提问卡。
        socket.send(
            serverFrame("""{"type":"waterfall","event":"user-questions/request","eventId":"q-1","request":{"question":"hi"}}"""),
        )
        Thread.sleep(300)

        assertTrue(target.approvals.value.isEmpty())
        assertTrue(target.questions.value.isEmpty())
        assertEquals(1L, target.stats.value.waterfalls)
        assertEquals(0L, target.stats.value.approvalsSeen)
    }

    // ---------------------------------------------------------------- 模型提问（user-questions/request）

    /** 一条 `user-questions/request` waterfall 帧的 JSON（带 `wait.callId`）。 */
    private fun questionWaterfall(
        eventId: String,
        agentId: String = "session-a",
        callId: String? = "call-42",
    ): String = buildJsonObject {
        put("type", JsonPrimitive("waterfall"))
        put("event", JsonPrimitive("user-questions/request"))
        put("eventId", JsonPrimitive(eventId))
        put("agentId", JsonPrimitive(agentId))
        put(
            "request",
            buildJsonObject {
                put(
                    "questions",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", JsonPrimitive("q1"))
                                put("question", JsonPrimitive("用哪个方案？"))
                                put("multiSelect", JsonPrimitive(false))
                                put(
                                    "options",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("label", JsonPrimitive("方案 A"))
                                                put("description", JsonPrimitive("快"))
                                            },
                                            buildJsonObject { put("label", JsonPrimitive("方案 B")) },
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )
                if (callId != null) {
                    put("wait", buildJsonObject { put("callId", JsonPrimitive(callId)) })
                }
            },
        )
    }.toString()

    @Test
    fun questionWaterfallPublishesTheQuestionsAndTheSignal() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        val request = target.questions.value.single()
        assertEquals("q-1", request.eventId)
        assertEquals("session-a", request.agentId)
        assertEquals("call-42", request.callId)
        // 瀑布帧带 callId 时卡片就是「可回答」的：`$events/result` 那条路认 eventId，
        // 而 callId 用来与投影组对上（同一条问题的两个视图）。
        assertEquals("call-42", request.callId)
        assertEquals("用哪个方案？", request.questions.single().question)
        assertEquals(listOf("方案 A", "方案 B"), request.questions.single().options.map { it.label })
        // 信号与卡片是两条独立的出口：投影那条路只认信号，卡片那条路只认 questions。
        assertEquals(1L, target.questionSignal.value)
        assertEquals(1L, target.stats.value.questionsSeen)
        // 提问绝不是审批。
        assertTrue(target.approvals.value.isEmpty())
    }

    @Test
    fun questionWaterfallWithoutCallIdIsStillPublishedButNotAnswerable() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1", callId = null)))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        // `wait?` 是可选的。拿不到 callId 时与投影组对不上（`ChatViewModel` 那边按
        // callId 匹配），但卡片仍然要弹——投影刷新会补上真正的 callId，
        // 而 `answerQuestions` 收 callId、内部回落到 eventId，两种都答得出去。
        val request = target.questions.value.single()
        assertNull(request.callId)
        assertEquals(1, request.questions.size)
    }

    @Test
    fun answeringAQuestionSendsTheAnswersAsAResultOutcome() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        expectUnaryOk()
        unaryBodies.clear()
        withTimeout(10_000) {
            // 参数是 **callId**（`call-42`）而不是 eventId：`ChatViewModel` 用 callId
            // 当卡片身份与草稿键，客户端内部自己回落去匹配 eventId。
            target.answerQuestions(
                "call-42",
                listOf(AskAnswer(id = "q1", selected = listOf("方案 A"))),
            )
        }

        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        // 提问与审批的回答必须分开计数，否则诊断页会把「答过一次提问」
        // 显示成「答过一次审批」。
        assertEquals(1L, target.stats.value.questionAnswers)
        assertEquals(0L, target.stats.value.approvalAnswers)
        val args = Json.parseToJsonElement(unaryBodies.single()).jsonObject["payload"]!!
            .jsonObject["args"]!!.jsonObject
        // 键集必须恰好三个（服务端 exactKeys 校验）。
        assertEquals(setOf("clientId", "eventId", "outcome"), args.keys)
        assertEquals("cid-1", args["clientId"]!!.jsonPrimitive.content)
        assertEquals("q-1", args["eventId"]!!.jsonPrimitive.content)
        val outcome = args["outcome"]!!.jsonObject
        assertEquals("result", outcome["kind"]!!.jsonPrimitive.content)
        val answers = outcome["value"]!!.jsonObject["answers"]!!.jsonArray
        assertEquals(1, answers.size)
        assertEquals("q1", answers.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "方案 A",
            answers.single().jsonObject["selected"]!!.jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun cancellingAQuestionRejectsWithTheDesktopCancellationError() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        expectUnaryOk()
        unaryBodies.clear()
        withTimeout(10_000) { target.dismissQuestion("q-1") }

        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        val outcome = Json.parseToJsonElement(unaryBodies.single()).jsonObject["payload"]!!
            .jsonObject["args"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("rejected", outcome["kind"]!!.jsonPrimitive.content)
        val error = outcome["error"]!!.jsonObject
        // 三个字段必须与桌面端 `questionError('ASK_CANCELLED')` 逐字一致，
        // 否则模型在桌面端与手机上看到的失败原因不同。
        assertEquals("UserQuestionError", error["name"]!!.jsonPrimitive.content)
        assertEquals("the user cancelled ask_user_question", error["message"]!!.jsonPrimitive.content)
        assertEquals("ASK_CANCELLED", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun answeringAQuestionLeavesTheApprovalCounterAlone() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        // 先答一次审批：approvalAnswers 必须动，questionAnswers 必须不动。
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.isNotEmpty() })
        expectUnaryOk()
        withTimeout(10_000) { target.answer("ev-a", allow = true) }
        assertTrue(awaitOn(8_000) { target.approvals.value.isEmpty() })
        assertEquals(1L, target.stats.value.approvalAnswers)
        assertEquals(0L, target.stats.value.questionAnswers)

        // 再答一次提问：反过来。
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })
        expectUnaryOk()
        withTimeout(10_000) {
            target.answerQuestions("call-42", listOf(AskAnswer(id = "q1", selected = listOf("方案 A"))))
        }
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        assertEquals(1L, target.stats.value.approvalAnswers)
        assertEquals(1L, target.stats.value.questionAnswers)
    }

    @Test
    fun answeredQuestionIsNotResurrectedByARedelivery() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        expectUnaryOk()
        withTimeout(10_000) {
            target.answerQuestions("call-42", listOf(AskAnswer(id = "q1")))
        }
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })

        // 服务端补发同一条 waterfall：已经答过了，不能再弹一张点不动的卡片。
        socket.send(serverFrame(questionWaterfall("q-1")))
        Thread.sleep(300)

        assertTrue(target.questions.value.isEmpty())
        // 信号仍然要发——投影可能还没读到「已落定」。
        assertEquals(2L, target.questionSignal.value)
    }

    @Test
    fun questionWaterfallIsDroppedWhenTheStreamIsRebuilt() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        socket.send(serverFrame("""{"type":"ready","clientId":"cid-2"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-2" })

        // 新流 = 新一批 eventId。旧流上的提问带着旧 eventId，答不了。
        assertTrue(target.questions.value.isEmpty())
        // 且同一 eventId 在新流里可以重新出现（记账已清）。
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })
    }

    @Test
    fun cancelFrameAlsoRemovesAPendingQuestion() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        socket.send(serverFrame("""{"type":"cancel","eventId":"q-1"}"""))
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        assertNull(target.error.value)
    }

    /**
     * R025 P1-2：从**投影**那条路答掉一组提问后，waterfall 上同一条卡片必须消失。
     *
     * 投影组与 waterfall 条目是同一个问题的两个视图（用 `callId` 对上）。
     * 用户答投影组时只动 `ChatViewModel` 的 `_questions`，waterfall 条目留在这里；
     * 投影组一消失，`interactive` 回退到这条 → 用户看到一张刚刚亲口答过的卡片，
     * 再点一次就是对同一个 eventId 发第二次 outcome。
     *
     * 修复前这条用例第二段断言失败（卡片复活）。
     */
    @Test
    fun answeringThroughTheProjectionDropsTheWaterfallCard() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1", callId = "call-42")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        // `ChatViewModel.answerQuestionGroup` 走投影路成功后会调这一下。
        target.forgetQuestion("call-42")
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })

        // 服务端补发同一条 waterfall：已经答过了，不能再弹回来。
        socket.send(serverFrame(questionWaterfall("q-1", callId = "call-42")))
        Thread.sleep(300)
        assertTrue(target.questions.value.isEmpty())
    }

    /** callId 对不上任何条目时是 no-op，不是崩溃。 */
    @Test
    fun forgettingAnUnknownCallIdIsANoOp() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1", callId = "call-42")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        target.forgetQuestion("call-does-not-exist")
        assertTrue(target.questions.value.isNotEmpty())

        // 真的 callId 照样摘得掉。
        target.forgetQuestion("call-42")
        assertTrue(target.questions.value.isEmpty())
    }

    /**
     * waterfall 帧不带 `wait.callId` 时，`ChatViewModel` 从头到尾用的是
     * `waterfall:<eventId>` 合成键 —— [RemoteEventsClient.forgetQuestion] 也必须认它。
     *
     * R026 审查 P2：只认真实 callId 的话这一半永远摘不掉，投影组一消失就回退到一张
     * 刚刚亲口答过的卡片。
     */
    @Test
    fun forgettingByWaterfallKeyDropsTheCard() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1", callId = null)))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        target.forgetQuestion("waterfall:q-1")
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })

        // 记了账，补发不会再复活。
        socket.send(serverFrame(questionWaterfall("q-1", callId = null)))
        Thread.sleep(300)
        assertTrue(target.questions.value.isEmpty())
    }

    /**
     * R025 P2-4：按 callId 回答时，卡片身份与草稿键都必须是同一个 callId。
     *
     * waterfall 帧带 `callId` 时 [ChatViewModel] 用它当卡片身份（不再套
     * `waterfall:` 前缀），所以客户端这边也要收 callId、内部回落到 eventId。
     * 这条用例同时钉住「回落匹配」：传 eventId 也答得出去。
     */
    @Test
    fun answeringByCallIdOrByEventIdBothReachTheSameCard() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(questionWaterfall("q-1", callId = "call-42")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })

        expectUnaryOk()
        unaryBodies.clear()
        withTimeout(10_000) { target.answerQuestions("call-42", listOf(AskAnswer(id = "q1"))) }
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        val args = Json.parseToJsonElement(unaryBodies.single()).jsonObject["payload"]!!
            .jsonObject["args"]!!.jsonObject
        // 回答送出去的仍然是 eventId——服务端只认它。
        assertEquals("q-1", args["eventId"]!!.jsonPrimitive.content)
        // 而答案按桌面端 `answer.answers` 的形状走（自定义答案空白时不发该键）。
        val answers = args["outcome"]!!.jsonObject["value"]!!.jsonObject["answers"]!!.jsonArray
        assertEquals(1, answers.size)
        assertEquals("q1", answers[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), answers[0].jsonObject["selected"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(answers[0].jsonObject["custom"])

        // 回落：直接传 eventId 也匹配同一条卡片。
        socket.send(serverFrame(questionWaterfall("q-2", callId = "call-42")))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })
        expectUnaryOk()
        unaryBodies.clear()
        withTimeout(10_000) { target.answerQuestions("q-2", listOf(AskAnswer(id = "q1"))) }
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        assertEquals("q-2", Json.parseToJsonElement(unaryBodies.single()).jsonObject["payload"]!!
            .jsonObject["args"]!!.jsonObject["eventId"]!!.jsonPrimitive.content)
    }

    /**
     * R025 协议层 P1-1：`ChatViewModel` 在 waterfall 帧**拿不到 callId** 时用
     * `waterfall:<eventId>` 当卡片身份与草稿键，`answerQuestions` 必须认这个合成键。
     *
     * 不认的后果不是报错而是**静默空返**：没有任何 outcome 送出、没有任何错误、卡片也不退，
     * 用户点「提交」看到的是「什么都没发生」，而且可以无限重复。这条用例钉住的就是这个。
     */
    @Test
    fun answeringBySyntheticWaterfallKeyReachesTheSameCard() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        // 不带 callId 的 waterfall 帧（`wait` 可选，schema 里带 `?`）。
        socket.send(serverFrame(questionWaterfall("q-9", callId = null)))
        assertTrue(awaitOn(8_000) { target.questions.value.isNotEmpty() })
        assertNull(target.questions.value.single().callId)

        expectUnaryOk()
        unaryBodies.clear()
        withTimeout(10_000) {
            target.answerQuestions("waterfall:q-9", listOf(AskAnswer(id = "q1")))
        }
        assertTrue(awaitOn(8_000) { target.questions.value.isEmpty() })
        assertEquals(
            "q-9",
            Json.parseToJsonElement(unaryBodies.single()).jsonObject["payload"]!!
                .jsonObject["args"]!!.jsonObject["eventId"]!!.jsonPrimitive.content,
        )
    }

    /**
     * 同一个 P1 的另一半：匹配不到任何卡片时**必须抛**，不能 `?: return`。
     *
     * 抛出来才会被 `ChatViewModel.answerQuestionGroup` 的 catch 变成「回答没能送出」显示给
     * 用户；静默返回则让调用方以为成功了，把草稿清掉、卡片留着。
     */
    @Test
    fun answeringAnUnknownQuestionReportsInsteadOfSilentlyDoingNothing() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })

        val failure = runCatching {
            withTimeout(10_000) { target.answerQuestions("waterfall:gone", listOf(AskAnswer(id = "q1"))) }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue("实际抛的是 ${failure?.javaClass?.simpleName}: ${failure?.message}", failure is IllegalStateException)
        // 没送出任何 RPC。
        assertTrue(unaryBodies.isEmpty())
    }

    @Test
    fun answerSendsExactArgsAndRemovesTheCard() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.isNotEmpty() })

        expectUnaryOk()
        withTimeout(10_000) { target.answer("ev-a", allow = true) }

        assertTrue(target.approvals.value.isEmpty())
        assertEquals(1L, target.stats.value.approvalAnswers)
        assertNull(target.error.value)

        val body = awaitOn(8_000) { unaryBodies.isNotEmpty() }
        assertTrue("unary 请求没到", body)
        val text = unaryBodies.single()
        // 端点形状：/remote/api/$events/result（不是 /remote/api/api/$events/result）
        assertTrue(text.contains("\"method\":\"\$events/result\""))
        // 键集必须恰好三个（服务端 exactKeys 校验，多一个键整体拒绝）
        assertTrue(text.contains("\"clientId\":\"cid-1\""))
        assertTrue(text.contains("\"eventId\":\"ev-a\""))
        assertTrue(text.contains("\"kind\":\"result\""))
        assertTrue(text.contains("\"value\":\"allowed-once\""))
    }

    @Test
    fun answerWithoutReadyFrameReportsInsteadOfSilentlyDropping() = runBlocking {
        upgrade { /* 连接开着，但永不发 ready */ }
        val target = client()
        startAsync(target)

        // 没有 clientId 就答不了——必须给一句人话，而不是静默丢弃。
        withTimeout(10_000) { target.answer("ev-a", allow = true) }
        assertEquals("还没连上审批通道，请稍候再试", target.error.value)
        assertTrue("没有 clientId 时不该发 RPC", unaryBodies.isEmpty())
    }

    @Test
    fun failedAnswerPutsTheCardBack() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null && clientFrames.isNotEmpty() })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.isNotEmpty() })

        // 服务端回 ok:false —— 网络抖动不该让用户失去再一次回答的机会。
        // 注意契约：answer() 自己吞掉异常（错误写进 error 流、卡片放回），
        // 由 ChatViewModel.answerApproval 的 catch 只做安全网。这里断言的是
        // 「不抛 + 卡片回来 + 计数不加」，而不是「抛出去」。
        expectUnaryFailure()
        target.answer("ev-a", allow = false)

        assertEquals(1, target.approvals.value.size)
        assertTrue(target.error.value?.contains("授权没能送出") == true)
        assertEquals(0L, target.stats.value.approvalAnswers)
        // 放回的是原来那条（工具名不变），不是一条空壳。
        assertEquals("pwsh", target.approvals.value.single().toolName)
        assertEquals("ev-a", target.approvals.value.single().eventId)
    }

    @Test
    fun unpairedFailureDoesNotRetryAndClearsPendingRequests() = runBlocking {
        // 403 unpaired：凭证失效，重试只是每 30 秒打一次 403。必须停下来并清空在途请求，
        // 否则 UI 上留着一排点不动的按钮。
        wsReject = true
        val target = client()
        startAsync(target)

        // 403 发生在 WebSocket 握手上，mux 会抛 unpaired；订阅随之结束。
        assertTrue(awaitOn(15_000) { target.error.value != null })
        assertTrue("错误必须点名 unpaired", target.error.value?.contains("unpaired") == true)
        assertTrue(target.approvals.value.isEmpty())
        // 只握手一次：unpaired 之后若再退避重试，等于每 30 秒打一次 403。
        assertEquals(1, wsAttempts)
    }

    /**
     * open 帧的 payload 必须是 `{"args":{}}`，不是 `{"args":{"args":{}}}`。
     *
     * 这条断言是 R020 P2-1 的守护：`RemoteStreamMux.open(endpoint, args)` 会把第二参
     * 自己包成 `payload = {"args": <第二参>}`，所以第二参必须传**空对象**。
     * 曾经传了 `{"args":{}}`，线上变成双层嵌套 → 服务端 exactKeys 拒绝 → 整条审批
     * 通道不可用，而原有 9 条用例（mock 不校验 payload）全绿。
     */
    @Test
    fun openFrameCarriesAnEmptyArgsObject() = runBlocking {
        val target = client()
        startAsync(target)
        assertTrue(awaitOn(8_000) { clientFrames.any { it.contains("\"type\":\"open\"") } })

        val open = clientFrames.first { it.contains("\"type\":\"open\"") }
        assertTrue("endpoint 必须是 events，实际：$open", open.contains("\"endpoint\":\"\$events\""))
        assertTrue(
            "payload 必须是 args 空对象，实际：$open",
            open.contains("\"payload\":{\"args\":{}}"),
        )
        assertTrue(
            "不允许出现双层嵌套，实际：$open",
            !open.contains("\"args\":{\"args\""),
        )
        // streamId 必须是非空字符串：服务端会拒数字。
        val streamId = Regex("\"streamId\":\"([^\"]+)\"").find(open)?.groupValues?.get(1)
        assertTrue("streamId 必须是非空字符串，实际：$open", !streamId.isNullOrEmpty())
    }

    /**
     * 普通重连后，旧连接上的审批卡片必须被清掉。
     *
     * 回答必须带**当前**连接 id；服务端重连后会分配新 id，旧 eventId 永远答不了
     * （实测：假 id → `gateway/internal: ... identifies no active event stream`）。
     * 清不掉就是一排点一次错一次的按钮（R020 P2-3）。
     */
    @Test
    fun reconnectDropsCardsFromThePreviousStream() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })

        // 重连：新 ready 带一个新 clientId。
        socket.send(serverFrame("""{"type":"ready","clientId":"cid-2"}"""))

        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-2" })
        assertTrue("旧连接的卡片必须被清掉", target.approvals.value.isEmpty())
    }

    /**
     * 回答失败时，若期间流已经重建过，就**不能**把卡片放回去。
     *
     * 放回去只会多一张永远点不动的卡片（R020 P2-2 与 P2-3 的交界）。
     */
    @Test
    fun failedAnswerDoesNotResurrectACardAfterReconnect() = runBlocking {
        var socket: WebSocket? = null
        upgrade { socket = it }
        val target = client()
        startAsync(target)

        assertTrue(awaitOn(8_000) { socket != null })
        socket!!.send(serverFrame("""{"type":"ready","clientId":"cid-1"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-1" })
        socket.send(serverFrame(waterfall("ev-a")))
        assertTrue(awaitOn(8_000) { target.approvals.value.size == 1 })

        // 让 answer 的 unary 挂住，期间重建流。
        unaryDelayMs = 400
        expectUnaryFailure()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            target.answer("ev-a", allow = true)
        }
        assertTrue(awaitOn(8_000) { target.approvals.value.isEmpty() })
        socket.send(serverFrame("""{"type":"ready","clientId":"cid-2"}"""))
        assertTrue(awaitOn(8_000) { target.clientId.value == "cid-2" })

        assertTrue(awaitOn(8_000) { target.error.value?.contains("授权没能送出") == true })
        assertTrue("重建过的流上的卡片不该被放回", target.approvals.value.isEmpty())
        job.cancel()
    }
}
