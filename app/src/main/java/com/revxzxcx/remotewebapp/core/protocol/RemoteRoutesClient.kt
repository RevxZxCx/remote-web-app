package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * 插件注册的**普通鉴权 HTTP 路由**（不是 Typert RPC）。
 *
 * 为什么需要单独一个类：桌面端「已编辑 N 个文件」卡片的数据来自
 * `@deepseek-ai/dsh-client-ui-deliverables` 注册的三个路由
 * （`lib/index.js:4-19`）：
 * ```
 * GET  /api/changes.summary?sessionId=&seq=
 * GET  /api/changes.diff?sessionId=&seq=&index=
 * POST /api/changes.open?sessionId=&seq=&index=
 * ```
 * 它们是 `ctx.connection.fetch.register(...)` 注册的普通路由，**不走 Typert 信封**：
 * 成功回**裸 JSON**，失败回**纯文本**（例如 `Change summary unavailable.` 配 404）。
 * 所以既不能复用 [TypertClient]（它会强制解析 `server-response` 信封并报
 * `bad-envelope`），也不能复用流式 mux。
 *
 * 经 `/remote` 前缀后形如 `GET {base}/remote/api/changes.summary?...`，仅需
 * `x-dsh-remote-device` 头（与 unary 同一张凭证）。
 */
class RemoteRoutesClient(
    private val baseUrl: String,
    private val deviceId: String,
    private val http: OkHttpClient,
    private val json: Json,
) {

    /**
     * GET 一个裸 JSON 路由。
     *
     * @param path 相对于 `/remote/api` 的路径，如 `changes.summary`。
     * @param query 查询参数，值为 null 的项会被跳过。
     * @throws TypertException 网络失败、非 2xx（`http-404` 等），或响应不是合法 JSON。
     */
    suspend fun getJson(path: String, query: Map<String, String?> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(urlFor(path, query)).header(
                TypertClient.DEVICE_HEADER,
                deviceId,
            ).get().build()
            execute(request)
        }

    /**
     * POST 一个无响应体的路由（成功回 204）。
     *
     * @throws TypertException 网络失败或非 2xx。
     */
    suspend fun post(path: String, query: Map<String, String?> = emptyMap()) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlFor(path, query))
            .header(TypertClient.DEVICE_HEADER, deviceId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        execute(request)
        Unit
    }

    private fun execute(request: Request): JsonElement {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw TypertException(TypertException.CODE_TRANSPORT, "网络请求失败：${e.message}", cause = e)
        }
        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: IOException) {
                throw TypertException(TypertException.CODE_TRANSPORT, "读取响应失败：${e.message}", cause = e)
            }
            if (!resp.isSuccessful) {
                // 与 unary 通道共用同一套翻译：围栏的纯文本 `unauthorized`、插件的
                // JSON `unpaired` 信封、以及这些路由自己的纯文本错误（404 →
                // `Change summary unavailable.`）都要能分辨。早前这里只比对纯文本，
                // 于是「缺 device header」得到的是 `http-403` 而不是 `unpaired`，
                // 上层既不会停退避、也不会引导重新配对。
                throw decodeHttpFailure(json, resp.code, text)
            }
            if (text.isBlank()) return JsonPrimitive(true)
            return runCatching { json.parseToJsonElement(text) }.getOrElse { cause ->
                throw TypertException(
                    TypertException.CODE_BAD_ENVELOPE,
                    "响应不是合法 JSON：${text.take(200)}",
                    cause = cause,
                )
            }
        }
    }

    private fun urlFor(path: String, query: Map<String, String?>): String {
        val base = "${baseUrl.trimEnd('/')}${TypertClient.REMOTE_API_PREFIX}/$path"
        val parts = query.entries
            .mapNotNull { (k, v) -> v?.let { "${encode(k)}=${encode(it)}" } }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) base else "$base?${parts.joinToString("&")}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
