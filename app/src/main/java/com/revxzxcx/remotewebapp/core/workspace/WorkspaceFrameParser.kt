package com.revxzxcx.remotewebapp.core.workspace

import com.revxzxcx.remotewebapp.core.protocol.DshTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 `workspace/follow` 的一帧 `JsonElement` 解析成 [WorkspaceFrame]。
 *
 * 为什么手写而不是直接 `@Serializable` 多态：服务端的判别字段是 `type`，但帧里同时有
 * `value` / `workspace` / `workspaceIds` 等**互不相同**的载荷键，用 `JsonClassDiscriminator`
 * 也能做，只是要为每种帧单独建类，字段名还得完全对齐。手写解析在这里更短、更宽容
 * （未知 type 返回 `null` 而不是抛异常），也更容易单测。
 *
 * 服务端形状（`dsh-api-workspace-controller/lib/typert.remote-client.js`）：
 * ```
 * {type:'baseline', value:{items:[Workspace], archivedSessionIds, pinnedSessionIds}}
 * {type:'upsert',   workspace:{...}}
 * {type:'remove',   workspaceId:'...'}
 * {type:'order',    workspaceIds:['...']}
 * {type:'archived', archivedSessionIds:['...']}
 * {type:'pinned',   pinnedSessionIds:['...']}
 * ```
 */
class WorkspaceFrameParser {

    /** 解析一帧；无法识别时返回 `null`（调用方忽略该帧）。 */
    fun parse(element: JsonElement): WorkspaceFrame? {
        val obj = element as? JsonObject ?: return null
        return when (obj.string("type")) {
            "baseline" -> {
                val value = obj["value"] as? JsonObject ?: return null
                WorkspaceFrame.Baseline(
                    WorkspaceSnapshot(
                        items = value.workspaces("items"),
                        archivedSessionIds = value.stringSet("archivedSessionIds"),
                        pinnedSessionIds = value.stringSet("pinnedSessionIds"),
                    ),
                )
            }

            "upsert" -> {
                val ws = obj["workspace"] as? JsonObject ?: return null
                workspaceOf(ws)?.let { WorkspaceFrame.Upsert(it) }
            }

            "remove" -> obj.string("workspaceId")?.let { WorkspaceFrame.Remove(it) }

            "order" -> WorkspaceFrame.Order(obj.stringList("workspaceIds"))

            "archived" -> WorkspaceFrame.Archived(obj.stringList("archivedSessionIds"))

            "pinned" -> WorkspaceFrame.Pinned(obj.stringList("pinnedSessionIds"))

            else -> null
        }
    }

    /** 解析一个 workspace 对象；缺 `workspaceId` 视为无效。 */
    fun workspaceOf(obj: JsonObject): Workspace? {
        val id = obj.string("workspaceId") ?: return null
        return Workspace(
            workspaceId = id,
            path = obj.string("path").orEmpty(),
            title = obj.string("title").orEmpty(),
            sessionIds = obj.stringList("sessionIds"),
            createdAt = obj.time("createdAt"),
            updatedAt = obj.time("updatedAt"),
        )
    }

    private fun JsonObject.workspaces(key: String): List<Workspace> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::workspaceOf) }
            .orEmpty()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 时间字段：线上是 ISO-8601 字符串，类型声明里是数字。两种都接受。
     *
     * 注意不能只取 `isString` 的分支——数字形态时 `content` 也能拿到数字文本，
     * 所以这里直接读 `content` 交给 [DshTime]。
     */
    private fun JsonObject.time(key: String): Long =
        DshTime.epochMillis((this[key] as? JsonPrimitive)?.content)

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    private fun JsonObject.stringSet(key: String): Set<String> = stringList(key).toSet()
}
