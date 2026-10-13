package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typert RPC 的线上信封（wire envelope）。
 *
 * 依据（源码已核实）：
 * - `@deepseek-ai/dsh-client-connection/lib/index.js:504-509` `clientRequestSchema`
 * - `@deepseek-ai/dsh-client-connection/lib/index.js:511-515` `serverResponseSchema`
 * - `@deepseek-ai/dsh-client-connection/lib/client.js:1215-1226` 组装并 POST
 *
 * 客户端 → 服务端：
 * ```
 * POST {base}/remote/api/{namespace}/{method}
 * x-dsh-remote-device: {deviceId}
 * { "type": "client-request", "rpcId": "<uuid-v4>", "method": "{namespace}/{method}",
 *   "payload": { "args": { ... } } }
 * ```
 *
 * 注意 `payload` 的**唯一**字段必须是 `args`，且 `args` 必须是普通对象
 * （`dsh-api-gateway/lib/index.js:1225-1237` `remoteRequest` 会强校验）。
 */
@Serializable
internal data class ClientRequestEnvelope(
    val type: String = TYPE,
    val rpcId: String,
    val method: String,
    val payload: JsonElement,
) {
    companion object {
        const val TYPE: String = "client-request"
    }
}

/**
 * 服务端 → 客户端。`result` 形如 `{ok:true, value:...}` 或 `{ok:false, error:{...}}`。
 *
 * 容错说明：`result` 声明为可空，因为某些失败（例如 harness 围栏返回的 401 纯文本
 * `unauthorized`）根本不是 JSON，此时反序列化会直接失败，由调用方转成传输层异常。
 */
@Serializable
data class ServerResponseEnvelope(
    val type: String = "server-response",
    val rpcId: String = "",
    val result: RpcResult? = null,
)

/** `dsh-client-connection/lib/index.js:717-754` 的 `fullResponse` / `errorResponse`。 */
@Serializable
data class RpcResult(
    val ok: Boolean = false,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

/** 服务端错误体。`details.issues` 通常是校验问题的数组。 */
@Serializable
data class RpcError(
    val code: String = "unknown",
    val message: String = "",
    val details: JsonElement? = null,
)

/**
 * Remote 流多路复用（mux）的帧。
 *
 * 依据 `@deepseek-ai/dsh-api-gateway/lib/client.js:160-179`
 * `parseRemoteStreamServerMessage` —— 只有这三种形状，收到别的必须主动以
 * **4002** 关闭连接（`client.js:590-595`）。
 */
object MuxFrame {
    const val TYPE_OPEN: String = "open"
    const val TYPE_ITEM: String = "item"
    const val TYPE_END: String = "end"
    const val TYPE_ERROR: String = "error"
    const val TYPE_CANCEL: String = "cancel"

    /** 服务端不认识任何客户端帧时使用的关闭码。 */
    const val CLOSE_INVALID_FRAME: Int = 4002
}
