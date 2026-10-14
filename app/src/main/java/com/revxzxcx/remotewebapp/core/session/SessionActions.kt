package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 会话管理动作的**参数构造**（归档 / 取消归档 / 置顶 / 取消置顶）。
 *
 * 依据（`dsh-api-workspace-controller/lib/typert.host.js` 的 descriptor 逐字提取）：
 * | 方法 | 参数 wire | 参数 schema | 结果 schema |
 * | --- | --- | --- | --- |
 * | `workspace/archiveSession` | `request` | `{sessionId, stopActivity?}` | `{archivedSessionIds}` |
 * | `workspace/unarchiveSession` | `request` | `{sessionId}` | `{archivedSessionIds}` |
 * | `workspace/pinSession` | `request` | `{sessionId}` | `{pinnedSessionIds}` |
 * | `workspace/unpinSession` | `request` | `{sessionId}` | `{pinnedSessionIds}` |
 *
 * ⚠️ **服务端没有「删除会话」这个 API**。已把整个 SDK dump（`F:\bot\_sdk`，173 个文件）
 * 与桌面端 `dsh-client-ui-*` 全量 grep 过 `deleteSession` / `session\/delete` /
 * `removeSession`，**零命中**；`typert.host.js` 的 invocation 清单里 session-controller
 * 只有 21 个方法（见 `_methods.js` 输出），其中与「移除」相关的只有 archive/unarchive。
 * 桌面端 Web UI 提供的也就是「归档」。所以本类**不提供**删除，UI 上也不出现「删除」字样，
 * 避免承诺一个做不到的操作（需求文档 Q14 同类的诚实原则）。
 *
 * 四个方法的参数 wire 名**都是 `request`**（源码签名 `async archiveSession(request)`），
 * 与 [ChatArgs] 的约定一致；只有 `session/list` 是 `_request`。
 */
object SessionActions {

    /**
     * `workspace/archiveSession`。
     *
     * @param stopActivity 同时停止该会话正在跑的活动（`index.js:299`
     *   `request.stopActivity === true ? { stopActivity: true } : {}`）。默认 true：
     *   归档一个还在生成中的会话却不叫停，会留下一条没人看的流。
     */
    fun archive(sessionId: String, stopActivity: Boolean = true): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
        if (stopActivity) put("stopActivity", JsonPrimitive(true))
    }

    /** `workspace/unarchiveSession`（从「已归档」段恢复）。 */
    fun unarchive(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /** `workspace/pinSession`（置顶）。 */
    fun pin(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /** `workspace/unpinSession`（取消置顶）。 */
    fun unpin(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /**
     * 包一层 `{request: {...}}`。
     *
     * 与 [ChatArgs.request] 同形但故意不共用：那个是 `private` 的，而这里的调用方在
     * 另一个包里。抽成顶层 `internal` 会让两个包的测试都能直接钉形状。
     */
    internal fun request(body: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("request", buildJsonObject(body))
        }
}
