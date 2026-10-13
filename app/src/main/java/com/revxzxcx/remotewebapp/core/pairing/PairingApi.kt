package com.revxzxcx.remotewebapp.core.pairing

import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 配对接口客户端。
 *
 * **这一条不走 `/remote` 通道**：`/api/pair` 在插件的 `LOCAL_ONLY_PREFIXES` 里
 * （`src/remote-methods.ts:71-75`），经 `/remote` 会被 `localOnlyDenial` 直接拒掉，必须直连
 * `{base}/api/pair/accept`。之所以直连也能通，是因为 `handleAccept` 的围栏是
 * `lanFence(req) || privateLanHostOf(req)`（`routes.ts:735-739`），而 `lanFence` 的可信主机
 * 列表里包含 `publicHostOf(service.publicBaseUrl)`（`routes.ts:583-619`），所以公网隧道地址
 * 与局域网地址都在放行范围内。
 *
 * 另一个前提：插件注册的 `/api/pair/` 系列路由**不经过 harness 的浏览器鉴权围栏**——实测
 * `GET /api/pair/status` 未带任何 cookie 返回 200（见需求文档 §3.3 的实测记录）。
 *
 * 请求也**不带 Origin / Sec-Fetch-\***：`isTrustedApiRequest` 在 Origin 缺失时直接放行
 * （`routes.ts:47-61`），OkHttp 默认不会加这些头，正合适。
 */
class PairingApi(
    private val http: OkHttpClient,
) {

    /**
     * `POST {baseUrl}/api/pair/accept`，body `{"token":"..."}`。
     *
     * @return 成功时返回 `deviceId`。
     * @throws PairingException 网络失败、响应非法、或服务端返回 `{ok:false,code}`。
     */
    suspend fun accept(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        val body = DshJson.encodeToString(
            AcceptRequest.serializer(),
            AcceptRequest(token = token),
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$ACCEPT_PATH")
            .post(body)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PairingException(
                PairingException.CODE_NETWORK,
                "连接不上 ${hostOf(baseUrl)}：${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }

        response.use { resp ->
            val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            if (!resp.isSuccessful) throw httpFailure(resp.code, text)

            val parsed = runCatching {
                DshJson.decodeFromString(AcceptResponse.serializer(), text)
            }.getOrNull() ?: throw PairingException(
                PairingException.CODE_BAD_RESPONSE,
                "配对响应不是合法 JSON：${text.take(160)}",
            )

            if (parsed.ok && !parsed.deviceId.isNullOrEmpty()) return@withContext parsed.deviceId
            throw PairingException(
                code = parsed.code ?: PairingException.CODE_REFUSED,
                message = refusalMessage(parsed.code),
            )
        }
    }

    /**
     * `GET {baseUrl}/api/pair/status` —— 用来判断地址是否可达、以及桌面端是否已开启配对。
     *
     * 该路由未鉴权（实测 200），所以可以在配对前调用做「地址探测」。
     */
    suspend fun status(baseUrl: String): PairStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$STATUS_PATH")
            .get()
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PairingException(
                PairingException.CODE_NETWORK,
                "连接不上 ${hostOf(baseUrl)}：${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
        response.use { resp ->
            val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            if (!resp.isSuccessful) throw httpFailure(resp.code, text)
            val obj = runCatching {
                DshJson.decodeFromString(JsonObject.serializer(), text)
            }.getOrNull() ?: throw PairingException(
                PairingException.CODE_BAD_RESPONSE,
                "状态响应不是合法 JSON：${text.take(160)}",
            )
            PairStatus(
                paired = (obj["paired"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                requirePairingForLan = (obj["requirePairingForLan"] as? JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false,
                publicUrl = (obj["publicUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                lanAddresses = runCatching {
                    (obj["lanAddresses"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .orEmpty()
                }.getOrDefault(emptyList()),
            )
        }
    }

    /**
     * 非 2xx 的处理。
     *
     * 403 有两种含义，必须区分（`routes.ts:735-739`）：
     * - body 是 `{"ok":false,"code":"forbidden"}` → 主机不在可信列表（地址不对）。
     * - body 是 Typert 信封的 `unpaired` → 不会出现在 accept 上，但防御性处理。
     */
    private fun httpFailure(status: Int, body: String): PairingException {
        val trimmed = body.trim()
        val code = runCatching {
            DshJson.decodeFromString(AcceptResponse.serializer(), trimmed).code
        }.getOrNull()
        return PairingException(
            code = code ?: "http-$status",
            message = when {
                code == "forbidden" -> "桌面端拒绝了此地址：该地址不在它的可信主机列表里"
                code == "rate-limited" -> "尝试过于频繁，请稍后再试"
                trimmed == "unauthorized" -> "桌面端要求浏览器鉴权（地址可能不是配对入口）"
                trimmed == "forbidden" -> "桌面端拒绝了此地址"
                else -> "HTTP $status：${trimmed.take(160)}"
            },
        )
    }

    /**
     * 把服务端返回的 code 翻译成给用户看的话。
     *
     * 注意服务端实际只发 **`invalid`** 一个码：`src/pairing.ts:452-456` 对「token 未知 / 已过期 /
     * 已停止」统一返回 `{ok:false, code:'invalid'}`（有意不区分，避免给攻击者一个有效性预言机），
     * routes 再以 404 原样回写（`src/routes.ts:757`）。所以这里必须把 `invalid` 映射成
     * 「回桌面端重新生成二维码」这类**可执行**的引导，而不是把内部码丢给用户看。
     *
     * 其余分支（`expired` / `unknown` / `stopped`）保留为防御：一旦插件未来细分错误码，
     * 无需改 App 就能给出更精确的提示。
     */
    private fun refusalMessage(code: String?): String = when (code) {
        "invalid" -> "二维码已失效或已过期，请在桌面端重新生成"
        "expired" -> "二维码已过期，请在桌面端重新生成"
        "unknown" -> "二维码无效，请在桌面端重新生成"
        "stopped" -> "桌面端已停止配对"
        "already-paired" -> "该桌面端已有配对设备，请先在桌面端撤销"
        "bad-payload" -> "请求格式不对"
        else -> "配对被拒绝${code?.let { "（$it）" }.orEmpty()}"
    }

    private fun hostOf(baseUrl: String): String =
        baseUrl.substringAfter("://").substringBefore('/')

    @Serializable
    private data class AcceptRequest(val token: String)

    /** `{ok:true, deviceId}` 或 `{ok:false, code}`（`routes.ts:737-768`）。 */
    @Serializable
    private data class AcceptResponse(
        val ok: Boolean = false,
        val deviceId: String? = null,
        val code: String? = null,
    )

    companion object {
        /** `PAIR_PATHS.accept`（`routes.ts:425-443`）。 */
        const val ACCEPT_PATH: String = "/api/pair/accept"

        /** `PAIR_PATHS.status`。 */
        const val STATUS_PATH: String = "/api/pair/status"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** `GET /api/pair/status` 的可读投影。 */
@Serializable
data class PairStatus(
    @SerialName("paired") val paired: Boolean = false,
    @SerialName("requirePairingForLan") val requirePairingForLan: Boolean = false,
    @SerialName("publicUrl") val publicUrl: String? = null,
    @SerialName("lanAddresses") val lanAddresses: List<String> = emptyList(),
)

/** 配对过程的失败。`code` 尽量沿用服务端码，便于上层分支。 */
class PairingException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        const val CODE_NETWORK: String = "network"
        const val CODE_BAD_RESPONSE: String = "bad-response"
        const val CODE_REFUSED: String = "refused"
    }
}
