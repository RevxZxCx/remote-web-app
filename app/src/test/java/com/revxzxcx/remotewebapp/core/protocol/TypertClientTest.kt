package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [TypertClient] 的单测 —— 用 MockWebServer 校验**真实线上形状**。
 *
 * 校验点来自源码（已核实）：
 * - `dsh-client-connection/lib/client.js:1215-1226`：POST `{base}/remote/api/{ns}/{method}`，
 *   body 是 `{type:'client-request', rpcId, method, payload:{args}}`。
 * - `dsh-client-connection/lib/index.js:673-702`：服务端要求 `method` 与 URL 推出的 endpoint 一致，
 *   不一致会返回 `gateway/bad-request`。
 * - `:1230`：客户端必须校验回包 rpcId。
 */
class TypertClientTest {

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

    private fun client() = TypertClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        deviceId = "dev-1",
        http = http,
        json = json,
    )

    /** 用请求里真实的 rpcId 回一个信封，模拟服务端行为。 */
    private fun respondWith(envelopeJson: (String) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                val rpcId = (json.parseToJsonElement(body) as JsonObject)["rpcId"]
                    .let { (it as JsonPrimitive).content }
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("content-type", "application/json")
                    .body(envelopeJson(rpcId))
                    .build()
            }
        }
    }

    @Serializable
    private data class WorkspaceValue(val workspaceId: String, val path: String = "")

    @Test
    fun callSendsExactWireShapeAndDecodesValue() = runTest {
        respondWith { rpcId ->
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,
                "value":{"workspaceId":"w1","path":"/p"}}}"""
        }

        val result = client().call(
            endpoint = "workspace/create",
            args = TypertClient.argsOf("request" to JsonObject(mapOf("path" to JsonPrimitive("/p")))),
            serializer = WorkspaceValue.serializer(),
        )

        assertEquals(WorkspaceValue("w1", "/p"), result)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // 端点必须在 /remote/api 下（D17：只走 /remote 前缀）。
        assertEquals("/remote/api/workspace/create", recorded.url.encodedPath)
        assertEquals("dev-1", recorded.headers["x-dsh-remote-device"])

        val sent = json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("client-request", (sent["type"] as JsonPrimitive).content)
        // method 必须与 URL 推出的 endpoint 逐字一致，否则服务端报 gateway/bad-request。
        assertEquals("workspace/create", (sent["method"] as JsonPrimitive).content)
        assertTrue((sent["rpcId"] as JsonPrimitive).content.isNotEmpty())
        val payload = sent["payload"] as JsonObject
        assertEquals(setOf("args"), payload.keys)
        val args = payload["args"] as JsonObject
        assertEquals(setOf("request"), args.keys)
        assertEquals("/p", ((args["request"] as JsonObject)["path"] as JsonPrimitive).content)
    }

    @Test
    fun callOmitsNullArgs() = runTest {
        respondWith { rpcId ->
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"workspaceId":"w1"}}}"""
        }
        client().call(
            endpoint = "workspace/create",
            args = TypertClient.argsOf(
                "request" to JsonObject(mapOf("path" to JsonPrimitive("/p"))),
                "beforeWorkspaceId" to null,
            ),
            serializer = WorkspaceValue.serializer(),
        )
        val sent = json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        val args = (sent["payload"] as JsonObject)["args"] as JsonObject
        // 未定义的参数不能出现在 args 里（prepareInvocation 会省略 undefined）。
        assertEquals(setOf("request"), args.keys)
    }

    @Test
    fun callThrowsServerErrorWithCode() = runTest {
        respondWith { rpcId ->
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":false,
                "error":{"code":"workspace-file/not-found","message":"missing","details":{}}}}"""
        }
        try {
            client().callRaw("workspaceFiles/read", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals("workspace-file/not-found", e.code)
            assertEquals("missing", e.message)
            assertTrue(!e.isUnpaired)
        }
    }

    @Test
    fun rpcIdMismatchIsRejected() = runTest {
        respondWith { """{"type":"server-response","rpcId":"someone-else","result":{"ok":true,"value":{}}}""" }
        try {
            client().callRaw("session/list", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_RPC_ID_MISMATCH, e.code)
        }
    }

    @Test
    fun nonJsonBodyIsBadEnvelope() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("content-type", "text/plain")
                .body("not json at all")
                .build(),
        )
        try {
            client().callRaw("session/list", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_BAD_ENVELOPE, e.code)
        }
    }

    @Test
    fun plainTextUnauthorizedIsMappedToUnpairedCode() = runTest {
        server.enqueue(
            MockResponse.Builder().code(401).body("unauthorized").build(),
        )
        try {
            client().callRaw("session/list", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_FORBIDDEN_UNAUTH, e.code)
            assertTrue(e.isUnpaired)
        }
    }

    @Test
    fun forbiddenUnpairedEnvelopeOn403IsDetected() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body(
                    """{"type":"server-response","rpcId":"x","result":{"ok":false,
                        "error":{"code":"unpaired","message":"this device is not paired with the desktop",
                        "details":{"issues":[]}}}}""",
                )
                .build(),
        )
        try {
            client().callRaw("session/list", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_UNPAIRED, e.code)
            assertTrue(e.isUnpaired)
        }
    }

    @Test
    fun callUnitAcceptsUndefinedResult() = runTest {
        respondWith { rpcId -> """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true}}""" }
        client().callUnit("workspace/initializeDefault", JsonObject(emptyMap()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun argsOfDropsOnlyNulls() {
        val args = TypertClient.argsOf(
            "a" to JsonPrimitive("x"),
            "b" to null,
            "c" to JsonPrimitive(1),
        )
        assertEquals(setOf("a", "c"), args.keys)
        assertNull(args["b"])
    }

    @Test
    fun transportFailureIsReportedAsTransportCode() = runTest {
        // 关掉服务端再请求 → 连接被拒。
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        val client = TypertClient(url, "dev-1", http, json)
        try {
            client.callRaw("session/list", JsonObject(emptyMap()))
            fail("应当抛出 TypertException")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_TRANSPORT, e.code)
        }
    }
}
