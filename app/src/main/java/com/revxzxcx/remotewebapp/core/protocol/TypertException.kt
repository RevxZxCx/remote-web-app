package com.revxzxcx.remotewebapp.core.protocol

/**
 * 协议层统一异常。
 *
 * [code] 直接使用服务端 `error.code`（例如 `unpaired`、`gateway/bad-request`、
 * `workspace-file/not-found`），便于上层按码分支；传输层失败用 [Transport] 系列码。
 */
class TypertException(
    val code: String,
    message: String,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 是否是「设备未配对 / 配对失效」——上层应据此跳回配对页。 */
    val isUnpaired: Boolean get() = code == CODE_UNPAIRED || code == CODE_FORBIDDEN_UNAUTH

    companion object {
        /** `src/remote-api.ts:88-94` 未配对时的服务端错误码。 */
        const val CODE_UNPAIRED: String = "unpaired"

        /** HTTP 401 —— harness 围栏或凭证失效。 */
        const val CODE_FORBIDDEN_UNAUTH: String = "unauthorized"

        /** 传输层：网络不可达 / DNS / 超时。 */
        const val CODE_TRANSPORT: String = "transport"

        /** 传输层：响应不是合法 JSON 信封。 */
        const val CODE_BAD_ENVELOPE: String = "bad-envelope"

        /** 传输层：响应 rpcId 与请求不一致（`dsh-client-connection/lib/client.js:1230`）。 */
        const val CODE_RPC_ID_MISMATCH: String = "rpc-id-mismatch"

        /** mux 收到非法帧（`dsh-api-gateway/lib/client.js:160-179`）。 */
        const val CODE_INVALID_STREAM_FRAME: String = "invalid-stream-frame"

        /**
         * 插队被拒：该条目已经不在 `next-turn`，或会话已不在运行。
         *
         * `index.js:966` 抛这个码。**它不是故障**，是「你这条已经插过队了」的正常答复——
         * 所以调用方不该把它挂到 `sendError`（那条错误条上的「重试」绑的是重发消息，
         * 与插队毫无关系，B009）。
         */
        const val CODE_STEER_UNAVAILABLE: String = "session/steer-unavailable"
    }
}
