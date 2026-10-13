package com.revxzxcx.remotewebapp.core.integration

import com.revxzxcx.remotewebapp.core.net.HttpClients
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceFrameParser
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * M1 验收测试：拿**真实的 Kotlin 客户端代码**打**真实运行中的 DSH 实例**。
 *
 * 为什么放在单元测试里而不是只写一次性探针脚本：探针只能证明「协议对」，证明不了
 * 「我们的实现对」。这个测试跑的是 `PairingApi` / `TypertClient` / `RemoteStreamMux`
 * 本体，所以它同时验证协议与实现。
 *
 * 自适配：先探测 `127.0.0.1:19387` 是否在跑，不在就 `assumeTrue` 跳过——这样在 CI 或
 * 没开 DSH 的机器上不会红，只有本机开着 harness 时才真正执行（M1 的验收就是在这台机器上做的）。
 *
 * 副作用可控：配对用的一次性 token 由本测试自己 issue，结束时 `revoke` 掉自己创建的设备，
 * 不会动用户已有的配对设备。
 */
class LiveHarnessTest {

    private val base = System.getenv("DSH_LIVE_BASE")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:19387"

    private val json = DshJson

    /** 探测端口：连接失败或非 200 就跳过整个测试类。 */
    private fun liveHarnessAvailable(): Boolean {
        val probe = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return try {
            probe.newCall(
                Request.Builder().url("$base/api/pair/status").get().build(),
            ).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 走 loopback-only 的 issue 拿一次性 token（App 自己不做这一步，测试代桌面端做）。
     *
     * 不传 `address`：`handleIssue` 的 `address` 必须是桌面端已知的 LAN 地址，随便传一个会
     * 被判 `unknown-address`（400）。留空则服务端用 `publicBaseUrl ?? lanBaseUrl` 自己挑。
     */
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

    /**
     * 全链路：issue → PairingApi.accept → TypertClient（session/list，验证 `_request` 这个
     * 特殊 wire 名）→ RemoteStreamMux（workspace/follow 首帧 baseline）→ revoke。
     */
    @Test
    fun `配对 + unary + mux 全链路对真实实例可用`() = runBlocking {
        assumeTrue("本机 127.0.0.1:19387 没有在跑 DSH，跳过真机链路测试", liveHarnessAvailable())

        val unary = HttpClients.unary()
        val stream = HttpClients.stream()

        val token = issueToken(unary)
        val deviceId = PairingApi(unary).accept(base, token)
        assertTrue("accept 没有返回 deviceId", deviceId.isNotBlank())

        try {
            // --- unary：session/list 的 wire 名是 `_request`（唯一带下划线的方法） ---
            val typert = TypertClient(base, deviceId, unary, json)
            val list = typert.callRaw(
                DshEndpoints.SESSION_LIST,
                JsonObject(mapOf(DshEndpoints.ARG_REQUEST_UNDERSCORE to JsonObject(emptyMap()))),
            )
            assertTrue(
                "session/list 返回结构异常：${list.toString().take(300)}",
                list is JsonObject && list.containsKey("items"),
            )

            // 反证：用错名字必须被服务端拒掉，否则说明 `_request` 的结论没被真正验证
            val wrong = runCatching {
                typert.callRaw(
                    DshEndpoints.SESSION_LIST,
                    JsonObject(mapOf(DshEndpoints.ARG_REQUEST to JsonObject(emptyMap()))),
                )
            }
            assertTrue("传 `request` 竟然成功了，wire 名结论需要重新确认", wrong.isFailure)

            // --- mux：workspace/follow 的首帧必须是 baseline，且能解析出真实工作区 ---
            val mux = RemoteStreamMux(base, deviceId, stream, json)
            try {
                val frames = withTimeout(20_000) {
                    mux.open(DshEndpoints.WORKSPACE_FOLLOW, JsonObject(emptyMap()))
                        .take(1)
                        .toList()
                }
                assertEquals("workspace/follow 没有收到首帧", 1, frames.size)

                val parser = WorkspaceFrameParser()
                val frame = parser.parse(frames.first())
                assertTrue("首帧不是 baseline：${frames.first()}", frame is WorkspaceFrame.Baseline)

                val snapshot = (frame as WorkspaceFrame.Baseline).snapshot
                assertTrue("baseline 里没有工作区", snapshot.items.isNotEmpty())
                // 时间字段线上是 ISO 字符串，必须被折成非 0 的毫秒（否则 M1 的时间解析是坏的）
                val first = snapshot.items.first()
                assertTrue("workspaceId 为空", first.workspaceId.isNotBlank())
                assertTrue(
                    "createdAt 没被解析成毫秒（实际 ${first.createdAt}）——ISO 字符串解析有问题",
                    first.createdAt > 0L,
                )
            } finally {
                mux.close()
            }
        } finally {
            revoke(unary, deviceId)
        }
    }

    /** 未配对的设备必须被 `/remote` 拒掉（403 `unpaired`），这是安全模型的核心断言。 */
    @Test
    fun `伪造设备 id 会被 remote 通道拒绝`() = runBlocking {
        assumeTrue("本机 127.0.0.1:19387 没有在跑 DSH，跳过", liveHarnessAvailable())

        val unary = HttpClients.unary()
        val typert = TypertClient(base, "0".repeat(32), unary, json)
        val result = runCatching {
            typert.callRaw(DshEndpoints.SESSION_LIST, JsonObject(emptyMap()))
        }
        assertTrue("伪造 deviceId 竟然通过了 /remote 通道", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        val code = (result.exceptionOrNull() as? com.revxzxcx.remotewebapp.core.protocol.TypertException)?.code
        assertTrue(
            "拒绝原因不是预期的 unpaired/forbidden：code=$code message=$message",
            code == "unpaired" || code == "forbidden" || message.contains("403"),
        )
    }
}
