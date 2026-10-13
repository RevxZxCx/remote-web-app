package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Typert RPC 客户端 —— 只负责 **unary** 调用（一问一答）。
 *
 * 线上形状（源码已核实）：
 * ```
 * POST {base}/remote/api/{namespace}/{method}
 * content-type: application/json
 * x-dsh-remote-device: {deviceId}
 *
 * { "type":"client-request", "rpcId":"<uuid-v4>",
 *   "method":"{namespace}/{method}", "payload":{"args":{...}} }
 * ```
 *
 * 为什么是 `/remote/api`：插件 `@linxin666/dsh-remote-web-ui` 把 `/remote/<x>`
 * 改写成 `/<x>` 再以进程自身凭证转发到 127.0.0.1（`src/remote-api.ts:100-110`
 * `innerPathOf` + `:181` 前缀路由），而设备头是唯一的**无 cookie** 凭证
 * （`src/remote-methods.ts:61-62`）。直接打 `/api` 会被 harness 围栏拦成 401。
 *
 * 流式（`session/follow` 等）不走这里，见 [RemoteStreamMux]。
 */
class TypertClient(
    /** 配对时记录的基址，形如 `http://192.168.1.5:19387` 或 `https://xxx.dsh-market.com`。 */
    private val baseUrl: String,
    /** 配对得到的设备 id，作为 `x-dsh-remote-device` 头。 */
    private val deviceId: String,
    private val http: OkHttpClient,
    private val json: Json,
) {

    /**
     * 发一次 unary RPC 并把 `result.value` 反序列化为 [T]。
     *
     * @param endpoint 形如 `session/list`、`workspace/create`（`namespace/method`）。
     * @param args 调用参数，键名必须是各参数的 `wire` 名；**未定义的参数不要放进来**
     *   （`prepareInvocation` 会省略 undefined，`dsh-api-gateway/lib/client.js:1817-1846`）。
     * @throws TypertException 传输失败、信封非法、rpcId 不匹配，或服务端返回 `ok:false`。
     */
    suspend fun <T> call(
        endpoint: String,
        args: JsonObject,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val value = callRaw(endpoint, args)
        json.decodeFromJsonElement(serializer, value)
    }

    /** 同 [call]，但服务端结果无返回值（`undefined`），例如 `workspace/initializeDefault`。 */
    suspend fun callUnit(endpoint: String, args: JsonObject) = withContext(Dispatchers.IO) {
        callRaw(endpoint, args)
        Unit
    }

    /**
     * 同 [callRaw]，但把成功的响应体当作**原始字节流**返回，并携带响应的 `content-type`。
     *
     * 用于 `workspaceFiles/readBytes` —— 它的成功响应不是 JSON 信封，而是
     * `multipart/form-data`（实测 `Content-Type: multipart/form-data; boundary=----formdata-undici-…`，
     * 单个 part：`Content-Disposition: form-data; name="bytes-0"; filename="blob"` +
     * `Content-Type: application/octet-stream`）。失败时仍然是普通 JSON 信封（例如
     * `workspace-file/not-found`），所以这里对非 2xx 与「2xx 但其实是错误信封」都要处理。
     */
    suspend fun callBinary(endpoint: String, args: JsonObject): BinaryResponse = withContext(Dispatchers.IO) {
        val rpcId = UUID.randomUUID().toString()
        val envelope = ClientRequestEnvelope(
            rpcId = rpcId,
            method = endpoint,
            payload = JsonObject(mapOf(ARGS to args)),
        )
        val body = json.encodeToString(ClientRequestEnvelope.serializer(), envelope)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(urlFor(endpoint))
            .header(DEVICE_HEADER, deviceId)
            .post(body)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw TypertException(TypertException.CODE_TRANSPORT, "网络请求失败：${e.message}", cause = e)
        }

        response.use { resp ->
            val contentType = resp.header("content-type").orEmpty()
            val bytes = try {
                resp.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                throw TypertException(TypertException.CODE_TRANSPORT, "读取响应失败：${e.message}", cause = e)
            }
            if (!resp.isSuccessful) {
                throw httpFailure(resp.code, String(bytes, Charsets.UTF_8))
            }
            // 2xx 也可能是错误信封：readBytes 失败时服务端回 200 + 正常信封（ok:false）。
            if (contentType.contains("json", ignoreCase = true)) {
                throw decodeResultError(String(bytes, Charsets.UTF_8), rpcId)
            }
            BinaryResponse(contentType, bytes)
        }
    }

    /** 解析一个「本该是成功响应、实际是错误信封」的 JSON 文本，返回应抛出的异常。 */
    private fun decodeResultError(text: String, rpcId: String): TypertException {
        val parsed = runCatching {
            json.decodeFromString(ServerResponseEnvelope.serializer(), text)
        }.getOrNull()
            ?: return TypertException(
                TypertException.CODE_BAD_ENVELOPE,
                "响应不是合法的 server-response 信封：${text.take(200)}",
            )
        if (parsed.rpcId.isNotEmpty() && parsed.rpcId != rpcId) {
            return TypertException(
                TypertException.CODE_RPC_ID_MISMATCH,
                "rpcId 不匹配：发送 $rpcId，收到 ${parsed.rpcId}",
            )
        }
        val error = parsed.result?.error
        return TypertException(
            code = error?.code ?: TypertException.CODE_BAD_ENVELOPE,
            message = error?.message ?: "响应缺少 result 字段",
            details = error?.details?.let { json.encodeToString(JsonElement.serializer(), it) },
        )
    }

    /** 同 [call]，但返回原始 `JsonElement`，用于结构松散、按需解析的返回值。 */
    suspend fun callRaw(endpoint: String, args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val rpcId = UUID.randomUUID().toString()
        val envelope = ClientRequestEnvelope(
            rpcId = rpcId,
            method = endpoint,
            payload = JsonObject(mapOf(ARGS to args)),
        )
        val body = json.encodeToString(ClientRequestEnvelope.serializer(), envelope)
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(urlFor(endpoint))
            .header(DEVICE_HEADER, deviceId)
            .post(body)
            .build()

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

            if (!resp.isSuccessful) throw httpFailure(resp.code, text)

            val parsed = try {
                json.decodeFromString(ServerResponseEnvelope.serializer(), text)
            } catch (e: Exception) {
                throw TypertException(
                    TypertException.CODE_BAD_ENVELOPE,
                    "响应不是合法的 server-response 信封：${text.take(200)}",
                    cause = e,
                )
            }

            // `dsh-client-connection/lib/client.js:1230` —— 客户端必须校验回包 rpcId。
            if (parsed.rpcId.isNotEmpty() && parsed.rpcId != rpcId) {
                throw TypertException(
                    TypertException.CODE_RPC_ID_MISMATCH,
                    "rpcId 不匹配：发送 $rpcId，收到 ${parsed.rpcId}",
                )
            }

            val result = parsed.result
                ?: throw TypertException(TypertException.CODE_BAD_ENVELOPE, "响应缺少 result 字段")
            if (result.ok) return@withContext result.value ?: JsonNull
            val error = result.error
            throw TypertException(
                code = error?.code ?: "unknown",
                message = error?.message.orEmpty(),
                details = error?.details?.let { json.encodeToString(JsonElement.serializer(), it) },
            )
        }
    }

    /**
     * 非 2xx 的失败。
     *
     * 围栏拒绝时 body 是**纯文本**（`dsh-client-connection/lib/index.js:586-594`
     * 写 `unauthorized` / `forbidden`），不是 JSON 信封，所以这里做兼容解析。
     */
    private fun httpFailure(status: Int, body: String): TypertException =
        decodeHttpFailure(json, status, body)

    /** `{base}/remote/api/{namespace}/{method}` —— 两个端点段都不做 URL 编码（段字符集受限）。 */
    private fun urlFor(endpoint: String): String =
        "${baseUrl.trimEnd('/')}$REMOTE_API_PREFIX/$endpoint"

    companion object {
        /** `src/remote-methods.ts:19` `REMOTE_API_PREFIX = '/remote/api'`。 */
        const val REMOTE_API_PREFIX: String = "/remote/api"

        /** `src/remote-methods.ts:61` `REMOTE_DEVICE_HEADER = 'x-dsh-remote-device'`。 */
        const val DEVICE_HEADER: String = "x-dsh-remote-device"

        /** `remoteRequest` 要求 payload 的唯一字段是 `args`（`dsh-api-gateway/lib/index.js:1225-1237`）。 */
        const val ARGS: String = "args"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 便捷构造：`JsonObject` 里只放非空值，跳过 null。 */
        fun argsOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
            JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

        fun str(value: String?): JsonElement? = value?.let { JsonPrimitive(it) }

        fun int(value: Int?): JsonElement? = value?.let { JsonPrimitive(it) }

        fun bool(value: Boolean?): JsonElement? = value?.let { JsonPrimitive(it) }
    }
}

/**
 * [TypertClient.callBinary] 的成功响应：尚未解析的 `multipart/form-data` 原始字节。
 *
 * 之所以不在协议层解析 multipart：multipart 的边界与 part 数量由服务端决定，
 * 解析属于「文件内容」的语义，放在 `core/files` 更合适（见 `WorkspaceFileRepository`）。
 */
data class BinaryResponse(
    val contentType: String,
    val bytes: ByteArray,
) {
    // ByteArray 在 data class 里必须自定义 equals/hashCode，否则是引用比较。
    override fun equals(other: Any?): Boolean =
        this === other || (other is BinaryResponse && contentType == other.contentType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * contentType.hashCode() + bytes.contentHashCode()
}

/** 便于从 `JsonElement` 安全取字段。 */
internal fun JsonElement?.objOrNull(): JsonObject? = (this as? JsonObject)

internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

/** `projections` 这类松散结构统一按 `JsonObject` 承接，避免服务端加字段就崩。 */
internal fun JsonElement?.asLooseObject(): JsonObject =
    runCatching { this?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())

/**
 * 把一次非 2xx 的响应体翻成 [TypertException]。
 *
 * [TypertClient] 与 [RemoteRoutesClient] 共用，因为**同一台服务端在两条通道上的拒绝长得很像
 * 但不完全一样**：
 *  - 围栏拒绝是纯文本 `unauthorized` / `forbidden`（`dsh-client-connection/lib/index.js:586-594`）；
 *  - 插件自己的拒绝是 JSON 信封（`src/remote-api.ts:88-94` 的 `unpaired`）；
 *  - 普通路由还有自己的纯文本错误（404 `Change summary unavailable.`）。
 *
 * 只认纯文本会让**缺 device header 的普通路由请求得到 `http-403`**，而不是 `unpaired`，
 * 于是 [TypertException.isUnpaired] 为 false，退避重试永远不会停、UI 也不会引导重新配对
 * （R005 数据层审查 P2）。
 */
internal fun decodeHttpFailure(json: Json, status: Int, body: String): TypertException {
    val trimmed = body.trim()
    // 先试 JSON 信封：`unpaired` 这类码必须原样透出，不能被 `http-403` 盖掉。
    val envelope = runCatching {
        json.decodeFromString(ServerResponseEnvelope.serializer(), trimmed)
    }.getOrNull()
    envelope?.result?.error?.let { rpcError ->
        return TypertException(
            code = rpcError.code,
            message = rpcError.message,
            details = rpcError.details?.let { json.encodeToString(JsonElement.serializer(), it) },
        )
    }
    val code = when (trimmed) {
        "unauthorized" -> TypertException.CODE_FORBIDDEN_UNAUTH
        "forbidden" -> "forbidden"
        else -> "http-$status"
    }
    return TypertException(code, "HTTP $status：${trimmed.take(200)}")
}
