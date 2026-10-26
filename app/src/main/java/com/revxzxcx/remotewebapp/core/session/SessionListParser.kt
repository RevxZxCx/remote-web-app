package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.DshTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 `session/list` 的 `result.value` 解析成 [SessionListResult]。
 *
 * 为什么手写而不是 `@Serializable`：`projections.values` 有 23 个键
 * （`title` / `goal` / `tokenUsage` / `contextPressure` / `contextBreakdown` / `inbox` /
 * `sessionStats` / `turnOutline` / `agentPreset` / `contextTimeline` / `contextHeaders` /
 * `contextActivity` / `userQuestions` / `subagentCatalog` / `subagentTiming` / `subagent` /
 * `agentTeam` / `permissions` / `todos` / `plan` / `modelSelection` / `sessionListMetadata` /
 * `imageLimits`，实测 47/47 条都有），其中大部分嵌套很深且与 M2 的列表 UI 无关。
 * 用严格 schema 承接它们意味着服务端每加一个投影字段就要改客户端；这里只**按需取**我们
 * 真正展示的三个值（title / lastPromptAt / lastModel），其余原样忽略。
 *
 * 解析策略是**宽容**的：任何字段缺失、类型不符都退化成默认值，绝不抛异常。理由与
 * [com.revxzxcx.remotewebapp.core.workspace.WorkspaceFrameParser] 相同——列表少显示一个
 * 副标题远好过整条流因为一个字段崩掉。
 */
class SessionListParser {

    /**
     * 解析 `result.value`（形如 `{items:[…]}`）。
     *
     * T076：顺手把四条用量投影也切出来。它们就在**同一次响应的同一条记录**里，不需要额外
     * 请求——这正是「首页洞察零新增 RPC」能成立的原因（实测 32 条主会话 32/32 命中）。
     */
    fun parse(element: JsonElement): SessionListResult {
        val obj = element as? JsonObject ?: return SessionListResult()
        val summaryList = ArrayList<SessionSummary>()
        val usageMap = LinkedHashMap<String, SessionUsage>()
        (obj["items"] as? JsonArray)?.forEach { item ->
            val raw = item as? JsonObject ?: return@forEach
            val summary = summaryOf(raw) ?: return@forEach
            summaryList += summary
            // 用量与摘要**分开遍历**是刻意的：摘要要留给列表长期复用，用量只在打开洞察
            // 抽屉时读一次。两者放在同一个 data class 里会互相拖累（见 `usages` 的 KDoc）。
            // 读不到用量的会话不进 map —— 它不是「0 用量」，是「没有数据」。
            val values = raw["projections"].asObject()?.get("values").asObject()
            SessionUsage.of(summary.sessionId, values)?.let { usageMap[summary.sessionId] = it }
        }
        return SessionListResult(items = summaryList, usages = usageMap)
    }

    /** 解析一条 `SessionSummary`；缺 `sessionId` 视为无效条目，返回 `null`。 */
    fun summaryOf(obj: JsonObject): SessionSummary? {
        val id = obj.string("sessionId")?.takeIf { it.isNotBlank() } ?: return null
        val values = obj["projections"].asObject()?.get("values").asObject()
        return SessionSummary(
            sessionId = id,
            updatedAt = obj.time("updatedAt"),
            agentAvailable = obj.boolean("agentAvailable") ?: false,
            running = obj.boolean("running") ?: false,
            blank = obj.boolean("blank") ?: false,
            parentSessionId = obj.string("parentSessionId"),
            origin = obj.string("origin"),
            cwd = obj.string("cwd"),
            title = values?.string("title").orEmpty(),
            // `lastPromptAt` 可为 null（`SessionListMetadata.lastPromptAt: number | null`）。
            lastPromptAt = values?.get("sessionListMetadata").asObject()?.time("lastPromptAt")
                ?.takeIf { it > 0L },
            lastModel = values?.get("modelSelection").asObject()
                ?.get("lastUsed").asObject()?.string("model"),
            // R049：两个「有东西在等人」的信号。都是**投影里的数组**，非空即有。
            // `userQuestions.active` 收的是「已经开始等待回答」的提问组；
            // `inbox` 的两条队列是排队但还没发出去的用户消息。
            awaitingAttention = values?.get("userQuestions").asObject()
                ?.hasNonEmptyArray("active") == true ||
                values?.get("inbox").asObject()?.let { inbox ->
                    inbox.hasNonEmptyArray("next-turn") || inbox.hasNonEmptyArray("next-step")
                } == true,
        )
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 这个键是不是一个非空数组；键缺失或不是数组都算 false。 */
    private fun JsonObject.hasNonEmptyArray(key: String): Boolean =
        (this[key] as? JsonArray)?.isNotEmpty() == true

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /** 时间：`session/list` 实测给数字，但走 [DshTime] 以兼容字符串形态。 */
    private fun JsonObject.time(key: String): Long =
        DshTime.epochMillis((this[key] as? JsonPrimitive)?.content)
}
