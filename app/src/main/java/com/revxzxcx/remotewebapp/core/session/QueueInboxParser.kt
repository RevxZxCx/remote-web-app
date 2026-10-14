package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一条 `agent/inbox/spliced` 事件解析后的形状。
 *
 * 线上 data（`SessionEventMap` + `_inboxp4.mjs` 实测）：
 * ```
 * {target:'next-turn'|'next-step', start:number, removedCount?:number,
 *  inserted:UserMessage[], outcome?:'canceled'}
 * ```
 * 三种操作对应三种组合：
 *  - 插入：`{target, start, inserted:[m]}`
 *  - 编辑：`{target, start, removedCount:1, inserted:[m'], outcome:'canceled'}`（**id 不变**，
 *    因为 `index.js:969` 是 `inbox.replace(itemId, freezeMessage({...message, content}))`）
 *  - 删除：`{target, start, removedCount:1, inserted:[], outcome:'canceled'}`
 *
 * `start` 是**目标数组内的下标**，所以应用规则必须按 target 分组、按下标操作，
 * 不能把两个 target 混成一个列表（这正是官方 `client.js:2158-2173` 的做法）。
 */
data class InboxSplice(
    val target: String,
    val start: Int,
    val removedCount: Int,
    val inserted: List<QueuedMessage>,
    val canceled: Boolean,
)

/**
 * 解析 `inbox` 投影与 `agent/inbox/spliced` 事件。
 *
 * 全部手写、宽容、绝不抛异常——理由同 [SessionListParser]：服务端会加字段，
 * 客户端崩了用户什么都看不到。
 */
object QueueInboxParser {

    /**
     * 解析 `projections.values.inbox`。
     *
     * 线上（`typert.remote-client.js:263-266`）：
     * `z.object({'next-turn': z.array(JsonValue).readonly(), 'next-step': z.array(JsonValue).readonly()}).optional()`
     * —— 整个投影可缺席，两个 target 也可能缺席，缺的都当空数组。
     */
    fun parseInbox(values: JsonObject?): List<QueuedMessage> {
        val inbox = values?.get("inbox") as? JsonObject ?: return emptyList()
        return parseTarget(inbox["next-turn"], INBOX_NEXT_TURN) +
            parseTarget(inbox["next-step"], INBOX_NEXT_STEP)
    }

    private fun parseTarget(element: JsonElement?, target: String): List<QueuedMessage> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { parseMessage(it, target) }
    }

    /**
     * 解析一条 `UserMessage`。
     *
     * `id` / `rpcId` 都可能缺席（例如别的客户端塞进来的消息没有我们的 rpcId）：
     * 缺 `id` 的条目无法操作，但仍应显示——用户需要看见队列里有什么。
     */
    fun parseMessage(element: JsonElement?, target: String): QueuedMessage? {
        val obj = element as? JsonObject ?: return null
        val blocks = obj["content"] as? JsonArray ?: return null
        val text = blocks.mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            if (b.stringOrNull("type") != "text") null else b.stringOrNull("text")
        }.joinToString("\n")
        val source = obj["source"] as? JsonObject
        val rpcId = source?.stringOrNull("rpcId") ?: ""
        val id = obj.stringOrNull("id") ?: return QueuedMessage(
            // 没有 id 的条目用 rpcId 兜底作 key，保证 Compose key 唯一。
            id = "no-id:${rpcId.ifEmpty { text.hashCode() }}",
            rpcId = rpcId,
            text = text,
            target = target,
            confirmed = false,
        )
        val attachmentCount = blocks.count { block ->
            val b = block as? JsonObject ?: return@count false
            val type = b.stringOrNull("type")
            type == "image" || type == "file"
        }
        return QueuedMessage(
            id = id,
            rpcId = rpcId,
            text = text,
            attachmentCount = attachmentCount,
            target = target,
            // 有 id 就是服务端条目，可以编辑/删除/插队。
            confirmed = true,
        )
    }

    /** 解析 `agent/inbox/spliced` 的 data。形状不符时返回 `null`（调用方忽略这一条）。 */
    fun parseSplice(data: JsonElement?): InboxSplice? {
        val obj = data as? JsonObject ?: return null
        val target = obj.stringOrNull("target") ?: return null
        if (target != INBOX_NEXT_TURN && target != INBOX_NEXT_STEP) return null
        val start = obj.longOrNull("start")?.coerceAtLeast(0L)?.toInt() ?: return null
        val removed = (obj.longOrNull("removedCount") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val inserted = (obj["inserted"] as? JsonArray)
            ?.mapNotNull { parseMessage(it, target) }
            .orEmpty()
        return InboxSplice(
            target = target,
            start = start,
            removedCount = removed,
            inserted = inserted,
            canceled = obj.stringOrNull("outcome") == "canceled",
        )
    }
}
