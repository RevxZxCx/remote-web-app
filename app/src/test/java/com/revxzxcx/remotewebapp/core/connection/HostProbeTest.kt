package com.revxzxcx.remotewebapp.core.connection

import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HostProbe] 四态文案 + [HostProbeClient.probe] 异常映射的单测（M57）。
 *
 * 存在的理由：本轮改动把探针从「地址通不通」的二元判断改成四态（可达 / 凭证 / 响应异常 / 本地拒绝），
 * 但 `app/src/test` 与 `app/src/androidTest` 里**没有任何**文件引用 [HostProbe]，
 * 等于本次改动零覆盖。其中第 2 轮复审抓到的 P2 缺陷——用户看到
 * 「桌面端响应异常（桌面端响应异常（bad-envelope））」——正是一句没人钉住的文案。
 *
 * ## 两组用例的分工
 *
 * 1. **文案契约**（[message] 是纯函数，不起服务器）：四态的措辞必须互相可区分，
 *    且 `reachable` 两支不能共用一句话——那正是四态退化成两态的老毛病。
 * 2. **异常映射**（走 MockWebServer 的真实 HTTP 往返）：`TypertClient` 在 [HostProbeClient.probe]
 *    内部构造，无法注入替身，而真正要钉住的是「服务端这么回 → 探针给出这个状态」这条端到端链路。
 *    这与 [com.revxzxcx.remotewebapp.core.pairing.PairingApiErrorTest] 的取向一致。
 *
 * 取消原样上抛那条是 P2 修复点：`CancellationException` 是 `Exception` 的子类，被兜底分支
 * 接住就会把「用户已经切走」谎报成「连不上，请检查地址与网络」。
 */
class HostProbeTest {

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
        // 传输层失败那条用例会提前关掉服务器，这里不能因为重复 close 而炸掉别的用例。
        runCatching { server.close() }
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun probeClient() = HostProbeClient(http, json)

    /** 用请求里真实的 rpcId 回一个信封，模拟服务端行为（与 `TypertClientTest` 同款）。 */
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

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + 1)
        }
        return count
    }

    // ---------------------------------------------------------------- 文案契约

    @Test
    fun `Ok 的文案说正常`() {
        val text = HostProbe.Ok.message("办公室主机")
        assertTrue("应包含「正常」，实际是：$text", text.contains("正常"))
        assertTrue("应带上主机名，实际是：$text", text.contains("办公室主机"))
    }

    @Test
    fun `凭证失效的文案指向重新配对`() {
        val text = HostProbe.Unauthorized("unpaired").message("办公室主机")
        assertTrue("应包含「凭证已失效」，实际是：$text", text.contains("凭证已失效"))
        assertTrue("必须明确下一步是重新配对，实际是：$text", text.contains("重新配对"))
        assertTrue("应带上主机名，实际是：$text", text.contains("办公室主机"))
    }

    @Test
    fun `没连上时只给固定结论且不引用 detail`() {
        // detail 用一个绝不会出现在文案里的哨兵值：这个分支的契约是「message() 不读 detail」，
        // 用真实值（恰好也是「连不上，请检查地址与网络」）测不出任何东西。
        val text = HostProbe.Unreachable("detail-should-not-appear", reachable = false).message("办公室主机")
        assertEquals("办公室主机 连不上，请检查地址与网络", text)
        assertTrue(
            "reachable=false 时 message() 不得引用 detail，实际是：$text",
            !text.contains("detail-should-not-appear"),
        )
    }

    @Test
    fun `连上了但响应异常时把服务端码放进括号`() {
        val text = HostProbe.Unreachable("bad-envelope", reachable = true).message("办公室主机")
        assertEquals("办公室主机 已连上，但桌面端响应异常（bad-envelope）", text)
    }

    /**
     * 回归断言：第 2 轮复审抓到的 P2 缺陷。
     *
     * 构造点曾自己拼了一遍「桌面端响应异常（…）」，`message()` 又套一层，用户看到
     * 「桌面端响应异常（桌面端响应异常（bad-envelope））」，括号还嵌套。
     * 修法是把前缀唯一地留在 `message()`，构造点只给裸码——这两条断言就是那个修法的钉子。
     */
    @Test
    fun `响应异常只出现一次且没有嵌套括号`() {
        val text = HostProbe.Unreachable("bad-envelope", reachable = true).message("办公室主机")

        assertEquals("「桌面端响应异常」只能出现 1 次，实际是：$text", 1, countOccurrences(text, "桌面端响应异常"))
        assertTrue("不得出现嵌套括号「（（」，实际是：$text", !text.contains("（（"))
        assertTrue("不得出现嵌套括号「））」，实际是：$text", !text.contains("））"))
        // 双重前缀即使不嵌套括号也是 bug，单独钉一次。
        assertTrue(
            "不得出现双重前缀，实际是：$text",
            !text.contains("桌面端响应异常（桌面端响应异常"),
        )
    }

    @Test
    fun `Rejected 原样带上本地拒绝原因`() {
        val text = HostProbe.Rejected("地址格式不对").message("办公室主机")
        assertEquals("办公室主机 地址格式不对", text)
    }

    // ---------------------------------------------------------------- 异常映射

    @Test
    fun `合法信封返回 Ok`() = runTest {
        respondWith { rpcId ->
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{}}}"""
        }

        assertEquals(HostProbe.Ok, probeClient().probe(baseUrl(), "dev-1"))
    }

    @Test
    fun `unpaired 错误码映射为凭证失效`() = runTest {
        respondWith { rpcId ->
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":false,
                "error":{"code":"unpaired","message":"设备未配对"}}}"""
        }

        assertEquals(HostProbe.Unauthorized("unpaired"), probeClient().probe(baseUrl(), "dev-1"))
    }

    /**
     * 纯文本 403：harness 围栏拒绝时 body 不是 JSON 信封（`dsh-client-connection/lib/index.js:586-594`），
     * 只能退化成按状态码造的 `forbidden`。它和 `unpaired` 是同一件事的两个表现，对用户都该说重新配对。
     */
    @Test
    fun `纯文本 403 映射为凭证失效而不是响应异常`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("content-type", "text/plain")
                .body("forbidden")
                .build(),
        )

        assertEquals(HostProbe.Unauthorized("forbidden"), probeClient().probe(baseUrl(), "dev-1"))
    }

    /**
     * 端到端版本的回归断言：HTTP 通了但回的不是这个协议时，探针给出的状态 + 它的文案
     * 必须同时满足「可达=true」和「前缀只出现一次」。
     */
    @Test
    fun `非法信封映射为连上了但响应异常`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("content-type", "application/json")
                .body("not-a-server-response-envelope")
                .build(),
        )

        val result = probeClient().probe(baseUrl(), "dev-1")
        assertEquals(HostProbe.Unreachable("bad-envelope", reachable = true), result)

        val text = result.message("办公室主机")
        assertEquals("办公室主机 已连上，但桌面端响应异常（bad-envelope）", text)
        assertEquals("「桌面端响应异常」只能出现 1 次，实际是：$text", 1, countOccurrences(text, "桌面端响应异常"))
        assertTrue("不得出现嵌套括号「（（」，实际是：$text", !text.contains("（（"))
    }

    @Test
    fun `传输层失败映射为连不上`() = runTest {
        val url = baseUrl()
        // 端口关掉 → ConnectException(IOException) → TypertException(CODE_TRANSPORT)。
        server.close()

        val result = probeClient().probe(url, "dev-1")
        assertEquals(HostProbe.Unreachable("连不上，请检查地址与网络", reachable = false), result)
        assertEquals("办公室主机 连不上，请检查地址与网络", result.message("办公室主机"))
    }

    /**
     * P2 修复点：取消必须原样上抛。
     *
     * 用 OkHttp 拦截器直接抛 [CancellationException]——它是 `Exception` 的子类，没有专门那个
     * catch 分支就会被兜底折成 `Unreachable(reachable = false)`，用户明明已经切走了却收到假警报，
     * 同时破坏结构化并发。断言「没返回结果」比断言异常类型更本质：折成状态才是要防的那个 bug。
     */
    @Test
    fun `取消原样上抛而不是折成连不上`() = runBlocking {
        val cancellation = CancellationException("用户切走了这一屏")
        val cancellingHttp = OkHttpClient.Builder()
            .addInterceptor { throw cancellation }
            .build()

        var result: HostProbe? = null
        var caught: Throwable? = null
        try {
            result = HostProbeClient(cancellingHttp, json).probe("http://192.168.1.5:19387", "dev-1")
        } catch (e: Throwable) {
            caught = e
        }

        assertNull("取消不能被折成结果（实际返回 $result），必须原样上抛", result)
        assertTrue(
            "取消必须原样上抛 CancellationException，实际抛出的是 ${caught?.let { it::class.java.name }}",
            caught is CancellationException,
        )
        assertEquals("用户切走了这一屏", caught?.message)
    }

    @Test
    fun `地址格式不对时不发请求直接拒绝`() = runTest {
        val result = probeClient().probe("   ", "dev-1")
        assertEquals(HostProbe.Rejected("地址格式不对"), result)
        assertEquals("没发过任何请求", 0, server.requestCount)
    }
}
