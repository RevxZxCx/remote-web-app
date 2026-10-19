package com.revxzxcx.remotewebapp.core.pairing

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PairingApi] 的错误文案映射。
 *
 * 存在的理由是一次**设备验收才抓到**的缺陷（R036）：拿一条已被消费的 token 点「开始配对」，
 * 界面上出现的不是「二维码已失效或已过期」，而是
 * `HTTP 404: {"ok":false,"code":"invalid"}` —— 一句用户不可能读懂的原始报文。
 *
 * 根因不在映射表（`refusalMessage` 里 `invalid` 早就翻译好了），而在**入口**：
 * `accept()` 先判 `!resp.isSuccessful` 就抛 `httpFailure(...)`，而服务端对
 * 「token 未知 / 已过期 / 已停止」恰恰是**以 404 下发** `{"ok":false,"code":"invalid"}`
 * （`src/pairing.ts:452-456` 有意不区分，避免给攻击者有效性预言机；`src/routes.ts:757` 原样回写）。
 * 于是响应体里明明白白有 `code`，却永远走不到 `refusalMessage`。
 *
 * 所以这些用例都走**真的 HTTP 往返**（MockWebServer）：`httpFailure` 是 private，而真正
 * 要钉住的是「服务端这么回 → 用户看到那句话」这条端到端链路，不是某个函数的入参出参。
 */
class PairingApiErrorTest {

    private lateinit var server: MockWebServer
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

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private suspend fun acceptFailure(body: String, status: Int): PairingException {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body(body)
                .build(),
        )
        return try {
            PairingApi(http).accept(baseUrl(), "abcdefghijklmnop")
            throw AssertionError("应当失败")
        } catch (e: PairingException) {
            e
        }
    }

    @Test
    fun `token 失效的 404 被翻译成可读文案而不是原始 JSON`() {
        // 这是设备上真实抓到的响应形状（HTTP 404 + 这一段 body）。
        val e = runBlocking { acceptFailure("""{"ok":false,"code":"invalid"}""", 404) }

        // `code` 保留服务端原值（`invalid`），不改成笼统的 `refused`——它会被上层
        // 日志与「知道了」之后的重试路径判断用到，翻译只发生在文案这一层。
        assertEquals("invalid", e.code)
        assertEquals("二维码已失效或已过期，请在桌面端重新生成", e.message)
        // 关键：用户界面里不能再出现 JSON 片段。
        assertTrue(
            "错误文案里不该带原始报文：${e.message}",
            e.message?.contains("\"ok\"") != true && e.message?.contains("HTTP") != true,
        )
    }

    @Test
    fun `已配对的拒绝被翻译成先撤销`() {
        val e = runBlocking { acceptFailure("""{"ok":false,"code":"already-paired"}""", 409) }
        assertEquals("该桌面端已有配对设备，请先在桌面端撤销", e.message)
    }

    @Test
    fun `限流被翻译成稍后再试`() {
        val e = runBlocking { acceptFailure("""{"ok":false,"code":"rate-limited"}""", 429) }
        assertEquals("尝试过于频繁，请稍后再试", e.message)
    }

    @Test
    fun `纯文本 forbidden 不会被误报成 JSON 解析错误`() {
        // 这条路径的 body 不是 JSON，`code` 解析为 null。文案只能是「拒绝了此地址」这个
        // 保守说法——纯文本 `forbidden` 可能来自反向代理、也可能来自路由，无法断定
        // 一定是 trustedHosts 的问题（那种情况服务端回的是 JSON `{"code":"forbidden"}`）。
        val e = runBlocking { acceptFailure("forbidden", 403) }
        assertEquals("桌面端拒绝了此地址", e.message)
        assertTrue("不该暴露原始报文：${e.message}", e.message?.contains("forbidden") != true)
    }

    @Test
    fun `JSON 的 forbidden 才说清是可信主机问题`() {
        // 这条才是「改桌面端 trustedHosts」的指路牌，必须保住。
        val e = runBlocking { acceptFailure("""{"ok":false,"code":"forbidden"}""", 403) }
        assertEquals("桌面端拒绝了此地址：该地址不在它的可信主机列表里", e.message)
    }

    @Test
    fun `认不出的响应体退回状态码并截断`() {
        // 兜底分支必须还在：服务端将来加一个新的失败形状时，界面要能显示足够定位问题的
        // 信息，而不是一句「配对失败」。
        val e = runBlocking { acceptFailure("<html>502 Bad Gateway</html>", 502) }
        assertTrue("应带状态码：${e.message}", e.message?.contains("HTTP 502") == true)
        assertTrue("应带响应体片段：${e.message}", e.message?.contains("Bad Gateway") == true)
    }

    @Test
    fun `成功响应返回 deviceId`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("content-type", "application/json")
                .body("""{"ok":true,"deviceId":"717de5233c6e5c3b853a9c05cdf70f25"}""")
                .build(),
        )
        val deviceId = runBlocking { PairingApi(http).accept(baseUrl(), "abcdefghijklmnop") }
        assertEquals("717de5233c6e5c3b853a9c05cdf70f25", deviceId)
    }
}
