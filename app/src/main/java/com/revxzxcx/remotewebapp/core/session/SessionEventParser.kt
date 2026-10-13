package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.DshTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话事件的宽容解析器。
 *
 * 设计原则与 [SessionListParser] / `WorkspaceFrameParser` 一致：**任何字段缺失或类型不符都
 * 退化成默认值，绝不抛异常**。一条畸形事件不该让整条聊天流崩掉——最多少显示一行。
 *
 * 依据（`SessionEventMap`，见 `需求文档.md` §3.8）：
 * - `user/message`   → `UserMessage = {id, role:'user', content: ContentBlock[], source}`
 * - `assistant/message` → `{turn, step, message: AssistantMessage, stream: AssistantStreamRecord[], usage?, interrupted?}`
 * - `tool/call`      → `{turn, step, callId, name, arguments}`（`arguments` 是 **JSON 字符串**）
 * - `tool/result`    → `{turn, step, message: ToolResultMessage, error?, meta?}`
 * - `turn/start` / `turn/end` / `step/start` / `step/end` → `{turn[, step][, reason]}`
 *
 * ⚠️ `turn/start`、`turn/end`、`step/start`、`step/end`、`request` 系列、`compaction` 系列等大量事件
 * 是**日志噪声**，不该出现在聊天气泡里。本解析器只把上面列出的几类变成 [ChatItem]，
 * 其余返回 `null`（调用方直接忽略）。
 *
 * ⚠️ 写 KDoc 时不要出现「斜杠紧接星号」的序列（例如 `request` 后面紧跟斜杠星号）：Kotlin 允许
 * **嵌套**块注释，那个序列会开一个永不闭合的内层注释，整个文件语法崩。本项目已踩过两次。
 */
class SessionEventParser {

    /**
     * 解析一帧事件信封。无法解析或不属于可渲染类型时返回 `null`。
     *
     * @param element 服务端 `{type, seq, time, data, ...}` 原始 JSON。
     */
    fun parse(element: JsonElement): ChatItem? {
        val obj = element as? JsonObject ?: return null
        val event = parseEvent(obj) ?: return null
        return toChatItem(event)
    }

    /**
     * 解析事件信封本身（不判断是否可渲染）。
     *
     * `seq` 缺失或为负数时视为非法——它是排序键与分页游标，错了会让整页顺序乱掉。
     */
    fun parseEvent(obj: JsonObject): SessionEvent? {
        val type = obj.string("type")?.takeIf { it.isNotBlank() } ?: return null
        val seq = obj.long("seq") ?: return null
        if (seq < 0) return null
        return SessionEvent(
            type = type,
            seq = seq,
            time = DshTime.epochMillis((obj["time"] as? JsonPrimitive)?.content),
            data = obj["data"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** 把一条事件折叠成 UI 卡片；不属于聊天内容的事件返回 `null`。 */
    fun toChatItem(event: SessionEvent): ChatItem? = when (event.type) {
        "user/message" -> userItem(event)
        "assistant/message" -> assistantItem(event)
        "tool/call" -> toolCallItem(event)
        "tool/result" -> toolResultItem(event)
        "turn/end" -> turnEndNotice(event)
        "workspace/changes" -> changesCard(event)
        "session/title" -> titleNotice(event)
        "compaction/summary" -> ChatItem.Notice(
            event.seq,
            event.time,
            text = "上下文已压缩",
            eventType = event.type,
        )
        else -> null
    }

    /**
     * `workspace/changes` —— 一轮结束时桌面端插件追加的「改动摘要就绪」标记。
     *
     * ⚠️ 它的 `data` **只有 `{turn}`**（实测），文件清单不在事件里：摘要留在 Host 内存，
     * 必须拿这条事件的 `seq` 去查 `changes.summary` 路由。所以我们只产出一张
     * **占位卡片**，真实的文件列表由 ViewModel 异步填入
     * [com.revxzxcx.remotewebapp.core.session.ChatUiState.changes]。
     */
    private fun changesCard(event: SessionEvent): ChatItem =
        ChatItem.Changes(
            seq = event.seq,
            time = event.time,
            turn = event.data.long("turn") ?: 0L,
        )

    private fun userItem(event: SessionEvent): ChatItem? {
        // `user/message` 的 data 就是 UserMessage 本身（不是 {message: ...} 包裹）。
        val blocks = contentBlocks(event.data["content"])
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        if (text.isBlank() && blocks.isEmpty()) return null
        return ChatItem.User(
            seq = event.seq,
            time = event.time,
            text = text,
            blocks = blocks,
        )
    }

    private fun assistantItem(event: SessionEvent): ChatItem? {
        // 形状：{turn, step, message: AssistantMessage, stream: [...], usage?, interrupted?}
        val turn = event.data.long("turn") ?: return null
        val step = event.data.long("step") ?: 0L
        val message = event.data["message"] as? JsonObject
        val blocks = contentBlocks(message?.get("content"))
        // 只带工具调用、没有文本的助手消息也要显示（它就是那次工具调用的载体）。
        if (blocks.isEmpty()) return null
        return ChatItem.Assistant(
            seq = event.seq,
            time = event.time,
            turn = turn,
            step = step,
            blocks = blocks,
            interrupted = event.data.boolean("interrupted") == true,
        )
    }

    private fun toolCallItem(event: SessionEvent): ChatItem? {
        val turn = event.data.long("turn") ?: return null
        val callId = event.data.string("callId") ?: return null
        return ChatItem.ToolCall(
            seq = event.seq,
            time = event.time,
            turn = turn,
            step = event.data.long("step") ?: 0L,
            callId = callId,
            name = event.data.string("name").orEmpty(),
            arguments = event.data.string("arguments").orEmpty(),
        )
    }

    private fun toolResultItem(event: SessionEvent): ChatItem? {
        val turn = event.data.long("turn") ?: return null
        val message = event.data["message"] as? JsonObject
        val callId = message?.string("toolCallId") ?: event.data.string("callId") ?: return null
        val blocks = contentBlocks(message?.get("content"))
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        val isError = message?.boolean("isError") == true || event.data["error"] is JsonObject
        return ChatItem.ToolResult(
            seq = event.seq,
            time = event.time,
            turn = turn,
            step = event.data.long("step") ?: 0L,
            callId = callId,
            text = text,
            isError = isError,
        )
    }

    private fun turnEndNotice(event: SessionEvent): ChatItem? {
        val turn = event.data.long("turn") ?: return null
        val reason = event.data.string("reason").orEmpty()
        return ChatItem.Notice(
            seq = event.seq,
            time = event.time,
            turn = turn,
            text = if (reason.isEmpty()) "第 ${turn + 1} 轮结束" else "第 ${turn + 1} 轮结束（$reason）",
            eventType = event.type,
        )
    }

    private fun titleNotice(event: SessionEvent): ChatItem? {
        val title = event.data.string("title")?.takeIf { it.isNotBlank() } ?: return null
        return ChatItem.Notice(event.seq, event.time, text = "会话标题：$title", eventType = event.type)
    }

    /**
     * 解析 `content: ContentBlock[]`。
     *
     * 认不出的块类型落进 [ContentBlock.Unknown]，不丢弃——否则 UI 会静默少一段内容，
     * 而「少一段」比「显示一行不支持」更难排查。
     */
    fun contentBlocks(element: JsonElement?): List<ContentBlock> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { blockOf(it as? JsonObject ?: return@mapNotNull null) }
    }

    /** 解析一个内容块。 */
    fun blockOf(obj: JsonObject): ContentBlock? = when (obj.string("type")) {
        "text" -> obj.string("text")?.let { ContentBlock.Text(it) }

        "reasoning" -> obj.string("text")?.let { ContentBlock.Reasoning(it) }

        "image" -> {
            val attachment = obj["attachment"] as? JsonObject
            val id = attachment?.string("attachmentId")
            if (id == null) {
                null
            } else {
                ContentBlock.Image(
                    attachmentId = id,
                    mediaType = attachment.string("mediaType").orEmpty(),
                    name = attachment.string("name"),
                    offloaded = obj.boolean("offloaded") == true,
                )
            }
        }

        "file" -> {
            val attachment = obj["attachment"] as? JsonObject
            val id = attachment?.string("attachmentId")
            if (id == null) {
                null
            } else {
                ContentBlock.File(
                    attachmentId = id,
                    name = attachment.string("name").orEmpty(),
                )
            }
        }

        "tool-call" -> {
            val id = obj.string("id")
            if (id == null) {
                null
            } else {
                ContentBlock.ToolCall(
                    id = id,
                    name = obj.string("name").orEmpty(),
                    arguments = obj.string("arguments").orEmpty(),
                )
            }
        }

        else -> obj.string("type")?.let { ContentBlock.Unknown(it) }
    }

    // ------------------------------------------------------------------ 小工具
    //
    // 宽容取值统一用文件末尾那组 internal 扩展（ChatFrameParser / AssistantStreamExpander
    // 也共用同一套），避免两个解析器对「什么算合法值」判断不一致。
    // 例如 `JsonPrimitive(true)` 是布尔，而 `JsonPrimitive("true")` 不是——`isString`
    // 这个判断绝不能各写一份。

    private fun JsonObject.string(key: String): String? = stringOrNull(key)

    private fun JsonObject.long(key: String): Long? = longOrNull(key)

    private fun JsonObject.boolean(key: String): Boolean? = booleanOrNull(key)
}

/**
 * 便于别处复用同一套宽容取值。
 *
 * 这几个扩展原先只有 [SessionEventParser] 自己用，审查指出「定义了却没人调用」；
 * 现在 [ChatFrameParser] 与 [AssistantStreamExpander] 也用同一套，保证两个解析器对
 * 「什么算合法值」的判断一致（例如 `JsonPrimitive(true)` 是布尔、`JsonPrimitive("true")`
 * 不是——`isString` 这个判断不能各写一份）。
 */
internal fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
