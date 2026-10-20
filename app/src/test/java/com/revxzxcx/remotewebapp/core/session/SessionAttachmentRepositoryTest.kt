package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SessionAttachmentRepository] 的 wire 形状回归测试。
 *
 * **被守护的缺陷（R039 用户报「发图片报错」/ R040 修复）**：旧实现把 `sessionId` 与
 * `attachmentId` **扁平**放进 `payload.args`，而 `session/attachment` 的 descriptor 只有
 * 一个形参 `request`。真机实测回：
 * ```
 * gateway/arguments-invalid: typert gateway: session/attachment: args fields do not
 * match the descriptor: missing "request"; unexpected "sessionId", "attachmentId"
 * ```
 * 于是会话里所有图片都取不回来，界面上表现为「发图片失败」。
 *
 * 这条测试**不看 UI、不看异常文案**，只断言实际发出去的 JSON 里有一层 `request`——
 * 因为这是唯一能提前发现「参数被摊平」的手段：服务端拒绝发生在网络往返之后，而
 * 单测里如果没有 MockWebServer 就只能靠真机才能撞见（R031 D88 的教训）。
 *
 * 同时断言 `data` 里的 base64 能解回原始字节，避免「参数对了但解码路径写错」。
 *
 * ⚠️ 必须挂在 Robolectric 下跑：`AttachmentPayload.decode()` 用的是
 * `android.util.Base64`，纯 JVM 单测里那是**空桩**（`decode` 返回 null → `runCatching`
 * 兜成空数组），断言会以「解不出字节」的形式假失败，与 wire 形状无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionAttachmentRepositoryTest {

    private lateinit var server: MockWebServer
    private val json = DshJson
    private val http = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun repository(): SessionAttachmentRepository = SessionAttachmentRepository(
        TypertClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "dev-1",
            http = http,
            json = json,
        ),
    )

    /** 用请求里真实的 rpcId 回一个信封，模拟服务端行为。 */
    private fun respondWith(valueJson: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                val rpcId = ((json.parseToJsonElement(body) as JsonObject)["rpcId"] as JsonPrimitive).content
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("content-type", "application/json")
                    .body("""{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$valueJson}}""")
                    .build()
            }
        }
    }

    @Test
    fun readWrapsArgumentsInRequestEnvelope() = runTest {
        respondWith(
            """{"attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":3,
                "width":1,"height":1},"data":"AAEC"}""",
        )

        val payload = repository().read(sessionId = "session-1", attachmentId = "att-1")

        assertEquals("att-1", payload.attachment.attachmentId)
        assertEquals("image/png", payload.attachment.mediaType)
        // 3 字节 base64 → 必须原样解回，证明 DTO 的字段名与线级 descriptor 对得上。
        assertEquals(listOf<Byte>(0, 1, 2), payload.decode().toList())

        val recorded = server.takeRequest()
        assertEquals("/remote/api/session/attachment", recorded.url.encodedPath)

        val sent = json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        val args = (sent["payload"] as JsonObject)["args"] as JsonObject

        // 核心断言：args 的唯一顶层键是 `request`。
        assertEquals(setOf("request"), args.keys)
        val request = args["request"] as JsonObject
        assertEquals(setOf("sessionId", "attachmentId"), request.keys)
        assertEquals("session-1", (request["sessionId"] as JsonPrimitive).content)
        assertEquals("att-1", (request["attachmentId"] as JsonPrimitive).content)
    }

    /**
     * 命中缓存时不得再打网络：同一 `attachmentId` 读两次，服务端只应收到一次请求。
     *
     * 这条与缺陷无关，但同一个文件里最省事：缓存是 `LinkedHashMap(accessOrder = true)`，
     * 命中路径容易在后续改动中被 `synchronized` 块改坏（例如把删除也从命中分支里做掉）。
     */
    @Test
    fun secondReadHitsCacheInsteadOfNetwork() = runTest {
        respondWith(
            """{"attachment":{"attachmentId":"att-2","mediaType":"image/png","bytes":1,
                "width":1,"height":1},"data":"AA=="}""",
        )
        val repository = repository()

        repository.read(sessionId = "session-1", attachmentId = "att-2")
        repository.read(sessionId = "session-1", attachmentId = "att-2")

        server.takeRequest() // 第一次
        // 第二次若打了网络，这里会立刻拿到请求而不是超时为空。
        assertEquals(0, server.requestCount - 1)
    }
}
