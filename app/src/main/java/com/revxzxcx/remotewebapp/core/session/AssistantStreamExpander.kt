package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `AssistantStreamRecord[]` 的展开器 —— 把**压缩**的流记录还原成逐个 delta。
 *
 * 为什么需要这一步：`assistant/message` 事件里的 `stream` 字段（以及
 * `assistant-stream` 帧里可能出现的压缩形态）不是逐条 delta，而是按块压缩的
 * `AssistantStreamRecord`：
 * ```
 * {type:'text-chunks',      time0, index, dt:number[], texts:string[]}
 * {type:'reasoning-chunks', time0, index, dt:number[], texts:string[]}
 * {type:'tool-call-chunks', time0, index, dt:number[], id, name?, args:string[]}
 * {type:'chunk',            time, chunk: StreamChunk}
 * ```
 * `texts[i]` 是第 i 个 delta 的文本，`dt[i-1]` 是它与前一个的时间差，`time0` 是第一个的时间。
 *
 * 本类逐字对齐官方 `expandAssistantStream`
 * （`dsh-api-session-controller/lib/client.js:1251-1291`）与其校验器 `validateRun`
 * （同文件 `:1330+`）：
 * - `dt.length` 必须恰好是 `members.length - 1`（`validateRun` 里 `dt length must be one less than its members`）
 * - 时间递推 `time = time0; for (gap of dt) time += gap`
 * - 成员数组必须非空且全为字符串
 *
 * **宽容策略**：官方在越界时抛 `TypeError`。客户端解析历史时不该因为一条畸形记录让整页
 * 崩掉，所以这里对不合规的记录返回空列表（跳过该记录）而不是抛异常——服务端已在自己的
 * 边界上校验过，客户端只需保证自己不炸。
 */
class AssistantStreamExpander {

    /** 一个展开后的 delta。 */
    sealed interface Delta {
        val time: Long
        /** 内容块下标（同一轮里 `block-start` 定义的顺序号）。 */
        val index: Int

        data class Text(override val time: Long, override val index: Int, val text: String) : Delta

        data class Reasoning(override val time: Long, override val index: Int, val text: String) : Delta

        data class ToolCall(
            override val time: Long,
            override val index: Int,
            val id: String,
            val name: String?,
            val argumentsDelta: String,
        ) : Delta

        /** `{type:'chunk', time, chunk}` 里的原生 `StreamChunk`。 */
        data class Raw(override val time: Long, val chunk: JsonObject) : Delta {
            override val index: Int get() = (chunk["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        }
    }

    /**
     * 展开一串 `AssistantStreamRecord`。
     *
     * @param element 服务端 `stream` 字段（`JsonArray`）；非数组时返回空列表。
     */
    fun expand(element: JsonElement?): List<Delta> {
        val array = element as? JsonArray ?: return emptyList()
        return array.flatMap { record ->
            val obj = record as? JsonObject ?: return@flatMap emptyList()
            expandRecord(obj)
        }
    }

    /** 展开单条记录；不合规时返回空列表。 */
    fun expandRecord(record: JsonObject): List<Delta> = when (record.string("type")) {
        "text-chunks" -> expandChunks(record, "texts") { time, index, member ->
            Delta.Text(time, index, member)
        }

        "reasoning-chunks" -> expandChunks(record, "texts") { time, index, member ->
            Delta.Reasoning(time, index, member)
        }

        "tool-call-chunks" -> {
            val id = record.string("id")?.takeIf { it.isNotEmpty() }
            if (id == null) {
                emptyList()
            } else {
                expandChunks(record, "args") { time, index, member ->
                    Delta.ToolCall(time, index, id, record.string("name"), member)
                }
            }
        }

        "chunk" -> {
            val chunk = record["chunk"] as? JsonObject
            val time = record.long("time")
            if (chunk == null || time == null) emptyList() else listOf(Delta.Raw(time, chunk))
        }

        else -> emptyList()
    }

    /**
     * 展开一条 `*-chunks` 记录。
     *
     * 校验（与官方 `validateRun` 一致）：
     * - `time0` / `index` 必须是整数
     * - `members` 非空且全为字符串
     * - `dt` 长度恰好是 `members.size - 1` 且全为整数
     */
    private inline fun expandChunks(
        record: JsonObject,
        membersKey: String,
        make: (time: Long, index: Int, member: String) -> Delta,
    ): List<Delta> {
        val time0 = record.long("time0") ?: return emptyList()
        val index = record.long("index")?.toInt() ?: return emptyList()
        if (index < 0) return emptyList()

        val members = (record[membersKey] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: return emptyList()
        if (members.isEmpty()) return emptyList()

        val dt = (record["dt"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            ?: return emptyList()
        if (dt.size != members.size - 1) return emptyList()

        val out = ArrayList<Delta>(members.size)
        var time = time0
        for (i in members.indices) {
            if (i > 0) time += dt[i - 1]
            out += make(time, index, members[i])
        }
        return out
    }

    /**
     * 把 `assistant-stream` 帧里 `chunk.chunk` 的原生 `StreamChunk` 直接转成 [Delta]。
     *
     * 与 [expandRecord] 的区别：这里收到的是**已经展开**的单条 chunk（不是压缩记录），
     * 形状是 `{type:'text-delta'|'reasoning-delta'|'tool-call-delta'|'block-start'|…, index, …}`。
     *
     * @param time 帧上的 `time`（毫秒）。
     * @param chunk 帧上的 `chunk` 对象。
     */
    fun deltaOf(time: Long, chunk: JsonObject): Delta? = when (chunk.string("type")) {
        "text-delta" -> chunk.string("text")?.let {
            Delta.Text(time, chunk.indexOr(-1), it)
        }

        "reasoning-delta" -> chunk.string("text")?.let {
            Delta.Reasoning(time, chunk.indexOr(-1), it)
        }

        "tool-call-delta" -> chunk.string("id")?.let {
            Delta.ToolCall(
                time = time,
                index = chunk.indexOr(-1),
                id = it,
                name = chunk.string("name"),
                argumentsDelta = chunk.string("argumentsDelta").orEmpty(),
            )
        }

        // block-start / block-end / usage / finish 不产生可见文本，但保留成 Raw，
        // 让 reducer 有机会处理块边界（例如工具调用参数收尾）。
        else -> Delta.Raw(time, chunk)
    }

    private fun JsonObject.indexOr(fallback: Int): Int =
        (this["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}
