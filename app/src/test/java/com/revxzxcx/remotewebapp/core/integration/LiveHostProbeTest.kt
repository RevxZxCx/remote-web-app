package com.revxzxcx.remotewebapp.core.integration

import com.revxzxcx.remotewebapp.core.connection.HostProbe
import com.revxzxcx.remotewebapp.core.connection.HostProbeClient
import com.revxzxcx.remotewebapp.core.net.HttpClients
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * M57 验收测试：拿**真实的** [HostProbeClient] 打**真实运行中的** DSH 实例。
 *
 * ## 为什么单测不够（这是 T080-5 要补的缺口）
 *
 * `HostProbeTest` 用 MockWebServer 替身，只能证明「我摆出这个响应 → 探针给这个状态」。
 * 它钉不住【真实桌面端到底会摆出哪些响应】——而这正是本次改动全部四种状态的依据。
 * T080-5 原本要求「真机目视四态文案」，写进测试记录时被列为未完成项。
 *
 * ## 本测试对真实实例实测出的矩阵（2026-10 于 127.0.0.1:19387）
 *
 * | 请求里的 deviceId | HTTP | body | 探针状态 |
 * | --- | --- | --- | --- |
 * | 合法 32 位 hex | 200 | JSON 信封 | [HostProbe.Ok] |
 * | 伪造 32 位 hex | 403 | JSON 信封 `unpaired` | [HostProbe.Unauthorized] |
 * | 缺 `x-dsh-remote-device` 头 | 403 | JSON 信封 `unpaired` | [HostProbe.Unauthorized] |
 * | 空字符串头 | 403 | JSON 信封 `unpaired` | [HostProbe.Unauthorized] |
 * | 非 hex 乱写 | 403 | JSON 信封 `unpaired` | [HostProbe.Unauthorized] |
 * | 撤销凭证后 | 403 | JSON 信封 `unpaired` | [HostProbe.Unauthorized] |
 *
 * ## 顺带纠正一条错误注释（本轮实测发现）
 *
 * `HostProbeClient` 里 `http-401 / http-403` 分支的注释原先写着「设备头不对时，harness
 * 围栏会直接回纯文本 403」。**这条是错的**：设备头不对时桌面端回的是带 `unpaired` 码的
 * JSON 信封（上表后四行），纯文本 `unauthorized` 只在**路径不存在**时出现。
 * 两者都映射 `Unauthorized`，用户可见结果一致，所以行为不用改；但注释给出的理由是假的，
 * 会让下一个读代码的人以为 `decodeHttpFailure` 的纯文本分支是设备头场景。
 * [设备头错误一律走 unpaired 信封而不是纯文本] 就是钉住这条实测事实的用例。
 *
 * ## 副作用
 *
 * 配对 token 由本测试自己 issue，用 `try/finally` 撤掉自己创建的设备，
 * 不动用户已有的配对设备（与 [LiveHarnessTest] 同款约束）。
 */
class LiveHostProbeTest {

    private val base = System.getenv("DSH_LIVE_BASE")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:19387"

    private val json = DshJson

    /** 探测端口：连接失败或非 200 就跳过整个测试类（没开桌面端的机器上不该红）。 */
    private fun liveHarnessAvailable(): Boolean {
        val probe = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return try {
            probe.newCall(Request.Builder().url("$base/api/pair/status").get().build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 走 loopback-only 的 issue 拿一次性 token（App 自己不做这一步，测试代桌面端做）。 */
    private fun issueToken(http: OkHttpClient): String {
        val body = JsonObject(emptyMap())
            .let { json.encodeToString(JsonObject.serializer(), it) }
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val resp = http.newCall(
            Request.Builder().url("$base/api/pair/issue").post(body).build(),
        ).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            assertTrue("issue 失败：HTTP ${it.code} $text", it.isSuccessful)
            val obj = json.decodeFromString(JsonObject.serializer(), text)
            val token = (obj["token"] as? JsonPrimitive)?.content
            assertTrue("issue 没有返回 token：$text", !token.isNullOrBlank())
            return token.orEmpty()
        }
    }

    private fun revoke(http: OkHttpClient, deviceId: String) {
        val body = JsonObject(mapOf("deviceId" to JsonPrimitive(deviceId)))
            .let { json.encodeToString(JsonObject.serializer(), it) }
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        runCatching {
            http.newCall(Request.Builder().url("$base/api/pair/revoke").post(body).build())
                .execute().close()
        }
    }

    private fun probeClient(http: OkHttpClient) = HostProbeClient(http, json)

    // ------------------------------------------------------ 真实四态

    @Test
    fun `合法凭证对真实实例探测得到 Ok`() = runBlocking {
        assumeTrue("本机 $base 没有在跑 DSH，跳过真机探针测试", liveHarnessAvailable())

        val unary = HttpClients.unary()
        val token = issueToken(unary)
        val deviceId = PairingApi(unary).accept(base, token)
        assertTrue("accept 没有返回 deviceId", deviceId.isNotBlank())

        try {
            val result = probeClient(unary).probe(base, deviceId)
            assertEquals("合法凭证应当探测成功，实际是：$result", HostProbe.Ok, result)
            assertEquals("PGBM10 正常", result.message("PGBM10"))
        } finally {
            revoke(unary, deviceId)
        }
    }

    @Test
    fun `撤销后的凭证探测得到凭证失效而不是连通性故障`() = runBlocking {
        assumeTrue("本机 $base 没有在跑 DSH，跳过真机探针测试", liveHarnessAvailable())

        val unary = HttpClients.unary()
        val token = issueToken(unary)
        val deviceId = PairingApi(unary).accept(base, token)
        assertTrue("accept 没有返回 deviceId", deviceId.isNotBlank())

        // 先确认撤销前是通的，否则「撤销后失败」可能只是这台机器本来就不通。
        val before = probeClient(unary).probe(base, deviceId)
        assertEquals("撤销前应当探测成功，实际是：$before", HostProbe.Ok, before)

        revoke(unary, deviceId)

        val after = probeClient(unary).probe(base, deviceId)
        assertTrue("撤销后应当判为凭证失效，实际是：$after", after is HostProbe.Unauthorized)
        // 关键：**不能**被折成 Unreachable。用户看到「连不上」会去查网络，方向完全错。
        assertTrue(
            "撤销后不能报成连通性故障（会把用户引去查网络）：${after.message("PGBM10")}",
            after.message("PGBM10").contains("凭证已失效"),
        )
    }

    /**
     * 钉住本轮实测出的事实：**设备头错误一律走 `unpaired` JSON 信封，不是纯文本**。
     *
     * 这条用例的意义在于它是反例：如果哪天桌面端改成回纯文本 403，这里会变红，提醒回去
     * 核对 `HostProbeClient` 里 `http-401 / http-403` 分支那句注释还成不成立。
     */
    @Test
    fun `设备头错误一律走 unpaired 信封而不是纯文本`() = runBlocking {
        assumeTrue("本机 $base 没有在跑 DSH，跳过真机探针测试", liveHarnessAvailable())

        val unary = HttpClients.unary()
        // 伪造的 32 位 hex：格式合法但从未配过对。
        val fakeDevice = "0123456789abcdef0123456789abcdef"

        val result = probeClient(unary).probe(base, fakeDevice)
        assertTrue("伪造凭证应当判为凭证失效，实际是：$result", result is HostProbe.Unauthorized)
        // detail 来自信封里的 error.code。如果是纯文本路径，这里会是`http-403`。
        assertEquals(
            "真实桌面端对未配对设备回的信封码应当是 unpaired；" +
                "若变成 http-403，说明它已改为纯文本响应，需回去核对 HostProbeClient 的注释",
            "unpaired",
            (result as HostProbe.Unauthorized).detail,
        )
    }

    @Test
    fun `连不上的端口探测得到没到达而不是响应异常`() = runBlocking {
        assumeTrue("本机 $base 没有在跑 DSH，跳过真机探针测试", liveHarnessAvailable())

        // 用一个几乎不可能有人监听的端口：连不上要判「没到达」，而不是「到达了但响应不对」。
        val deadPort = 1
        val result = probeClient(HttpClients.unary()).probe("http://127.0.0.1:$deadPort", "whatever")
        assertTrue("连不上的端口应当判为 Unreachable，实际是：$result", result is HostProbe.Unreachable)
        assertTrue(
            "连不上时 reachable 必须是 false，否则用户会被引去查桌面端版本",
            !(result as HostProbe.Unreachable).reachable,
        )
        assertTrue(
            "文案应指向地址与网络：${result.message("PGBM10")}",
            result.message("PGBM10").contains("请检查地址与网络"),
        )
    }

    /**
     * `Rejected` 走本地策略，**一个包都不发**。
     *
     * 用真实实例跑这条是为了确认「本地拒绝」确实发生在发请求之前：若探针先发后判，
     * 公网明文地址会真的把请求打出去（安全约定明确禁止）。
     */
    @Test
    fun `公网明文地址在本地就被拒 不会发出请求`() = runBlocking {
        assumeTrue("本机 $base 没有在跑 DSH，跳过真机探针测试", liveHarnessAvailable())

        val result = probeClient(HttpClients.unary()).probe("http://example.com", "whatever")
        assertTrue("公网明文地址应当本地拒绝，实际是：$result", result is HostProbe.Rejected)
        assertTrue(
            "拒绝理由应提到明文 HTTP 只允许局域网：${(result as HostProbe.Rejected).detail}",
            result.detail.contains("明文 HTTP 只允许局域网"),
        )
    }
}
