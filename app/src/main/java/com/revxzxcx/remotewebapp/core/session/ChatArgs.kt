package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 聊天相关 RPC 的**参数构造**，与网络无关，因此可以脱离 [ChatRepository] 单独测。
 *
 * 为什么要把它们抽出来：参数 wire 名是本项目最容易出错的地方。Typert 是严格 schema，
 * 名字拼错不会被宽容处理，而是直接
 * `gateway/arguments-invalid: args fields do not match the descriptor: missing "x"; unexpected "y"`。
 * M1/M2 已踩过两次（`session/list` 用 `_request` 而非 `request`；`assistantStream` 只接受
 * 字面量 `true`）。把它们做成纯函数，`ChatArgsTest` 就能不建网络连接直接钉死形状。
 *
 * 依据（`dsh-api-session-controller/lib/typert.remote-client.js` 的 descriptor）：
 * | 方法 | 参数 name/wire | 参数 schema |
 * | --- | --- | --- |
 * | `session/follow` | `request` | `{address, assistantStream?: literal(true), maxMessages?, turnWindow?}` |
 * | `session/page` | `request` | `{address, throughSeq, beforeSeq?, maxMessages?, turnWindow?}` |
 * | `session/prompt` | `request` | `{requestId, sessionId, mode:'queue'\|'steer', content, clientTimeZone?}` |
 * | `session/cancel` | `request` | `{sessionId}` |
 */
object ChatArgs {

    /** 服务端默认窗口（`history.js:59` `DEFAULT_MAX_MESSAGES = 50`）。 */
    const val DEFAULT_MAX_MESSAGES: Int = 50

    /** `session/prompt` 的投递方式。 */
    enum class PromptMode(val wire: String) {
        /** 排到当前轮之后（`mode:'queue'`）。 */
        Queue("queue"),

        /** 插进当前轮（`mode:'steer'`）。 */
        Steer("steer"),
    }

    /**
     * `session/follow` 的 args。
     *
     * `assistantStream` 是 `z.literal(true)`——只能传 `true` 或不传，传 `false` 会被拒，
     * 所以这里**恒为 true** 而不是做成参数。
     */
    fun follow(sessionId: String, maxMessages: Int = DEFAULT_MAX_MESSAGES): JsonObject = request {
        put("address", sessionAddress(sessionId))
        put("assistantStream", JsonPrimitive(true))
        put("maxMessages", JsonPrimitive(maxMessages))
    }

    /**
     * `session/page` 的 args。
     *
     * @param throughSeq 日志**尾部** seq（`session/follow` 快照的 `cursor`）。
     *   ⚠️ 实测传 `-1` 得到的是**空页**，不是「到日志头」：服务端
     *   `paginate` 里 `end = min(throughSeq + 1, beforeSeq ?? throughSeq + 1)`，
     *   `-1` 算出 `end = 0`，`slice(cut, 0)` 恒为空。`validatePageRequest` 允许 `>= -1`
     *   只是说它不报错，不代表语义是「从头开始」。**必须传真实游标。**
     * @param beforeSeq 只返回**严格早于**它的那一页。
     */
    fun page(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
    ): JsonObject = request {
        put("address", sessionAddress(sessionId))
        put("throughSeq", JsonPrimitive(throughSeq))
        put("beforeSeq", JsonPrimitive(beforeSeq))
        put("maxMessages", JsonPrimitive(maxMessages))
    }

    /**
     * `session/prompt` 的 args。
     *
     * @param clientTimeZone IANA 时区；为 null 时**不发送该键**（未定义的可选参数不能出现，
     *   否则服务端报 `unexpected "clientTimeZone"`）。
     */
    fun prompt(
        requestId: String,
        sessionId: String,
        text: String,
        mode: PromptMode = PromptMode.Queue,
        clientTimeZone: String? = null,
    ): JsonObject = request {
        put("requestId", JsonPrimitive(requestId))
        put("sessionId", JsonPrimitive(sessionId))
        put("mode", JsonPrimitive(mode.wire))
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    },
                ),
            ),
        )
        if (clientTimeZone != null) put("clientTimeZone", JsonPrimitive(clientTimeZone))
    }

    /** `session/cancel` 的 args（参数只有 `sessionId`）。 */
    fun cancel(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /**
     * 包一层 `{request: {...}}`。
     *
     * 四个 chat 方法的参数 wire 名**都是 `request`**（只有 `session/list` 是 `_request`）。
     * 集中在这里是为了让这条实测结论只有一个落点。
     */
    private fun request(body: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("request", buildJsonObject(body))
        }

    /** `address` 的会话形态（`{kind:'session', sessionId}`）。 */
    private fun sessionAddress(sessionId: String): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("session"))
        put("sessionId", JsonPrimitive(sessionId))
    }
}
