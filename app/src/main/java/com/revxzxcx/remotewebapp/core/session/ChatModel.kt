package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject

/**
 * 会话日志里的一条**事件信封**。
 *
 * 线上形状（`dsh-api-session-controller/lib/types/client/session-wire-event.js:10-42`
 * `assertSessionWireEvent`）：
 * ```
 * { type: string, seq: number, time: number, data: <任意 JSON>,
 *   ignorable?: true, surfaceOp?: <任意>, sourceEventSeqs?: <任意> }
 * ```
 *
 * ⚠️ 该信封的键是**封闭集合**：服务端只允许上面这七个键，多一个就抛
 * `session wire event has unexpected field X`。反过来，客户端也必须对未知的 `type`
 * 保持宽容——`SessionEventMap` 是 merge-extensible 的（同文件 `:38` 注释），
 * 服务端加新事件类型时旧客户端不能崩。
 *
 * `data` 故意保留成 [JsonObject] 而不是解析好的模型：不同 `type` 的载荷差异极大，
 * 且我们只关心其中几个，按需取比建十几个类更省事、更抗变更。
 */
data class SessionEvent(
    val type: String,
    /** 会话日志内的单调递增序号，从 0 开始且**无空洞**（`history.js:244-249` 会校验）。 */
    val seq: Long,
    /** epoch 毫秒。 */
    val time: Long,
    val data: JsonObject,
)

/**
 * 消息正文里的一块内容。
 *
 * 依据 `ContentBlockMap`（`typert.host.js`）：
 * `{text, reasoning, image, file, 'tool-call', 'tool-addition', 'tool-removal'}`。
 * 这里只建我们真正会渲染的几种，其余落进 [Unknown] 而不是丢弃——这样 UI 能显示
 * 「不支持的内容块（类型）」而不是静默少一段。
 */
sealed interface ContentBlock {

    /** `{type:'text', text}` —— 助手回复的 markdown 就是这里的纯文本。 */
    data class Text(val text: String) : ContentBlock

    /** `{type:'reasoning', text}` —— 思考过程，UI 折叠展示。 */
    data class Reasoning(val text: String) : ContentBlock

    /** `{type:'image', attachment:{attachmentId, mediaType, bytes, width, height, name?}, offloaded?}`。 */
    data class Image(
        val attachmentId: String,
        val mediaType: String,
        val name: String?,
        /** 服务端已把像素卸载到附件里（`offloaded: true`），需要另调 `session/attachment` 取回。 */
        val offloaded: Boolean,
    ) : ContentBlock

    /** `{type:'file', attachment:{attachmentId, name, bytes}}`。 */
    data class File(
        val attachmentId: String,
        val name: String,
    ) : ContentBlock

    /**
     * `{type:'tool-call', id, name, arguments}`。
     *
     * ⚠️ `arguments` 是 **JSON 字符串**（不是对象），要用 [SessionEventParser] 再解析一次。
     * 这是「看到编辑了哪些文件」在 M3.5 的实时推断路径的数据来源。
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    ) : ContentBlock

    /** 认不出的块类型（`tool-addition` / `tool-removal` 及未来新增）。 */
    data class Unknown(val type: String) : ContentBlock
}

/**
 * 一条可渲染的消息/事件。
 *
 * 这是 UI 层的统一视图：把会话日志里七八种 `type` 折叠成四种卡片。
 * [seq] 是排序键，也是「加载更早历史」的游标（`session/page` 的 `beforeSeq`）。
 */
sealed interface ChatItem {

    val seq: Long
    val time: Long

    /** 归属的轮次；`user/message` 的载荷里**没有** turn（`SessionEventMap`），故为 0。 */
    val turn: Long

    /** 用户发的话。 */
    data class User(
        override val seq: Long,
        override val time: Long,
        override val turn: Long = 0L,
        val text: String,
        val blocks: List<ContentBlock> = emptyList(),
    ) : ChatItem

    /**
     * 助手的一条**持久化**回复（`assistant/message`）。
     *
     * [interrupted] 为 true 表示这轮被中止/打断（`assistant/message` 的 `interrupted?: true`）。
     */
    data class Assistant(
        override val seq: Long,
        override val time: Long,
        override val turn: Long,
        val step: Long,
        val blocks: List<ContentBlock>,
        val interrupted: Boolean = false,
    ) : ChatItem

    /** 一次工具调用（`tool/call`）。结果由 [ToolResult] 单独一条承载，M3 不做配对折叠。 */
    data class ToolCall(
        override val seq: Long,
        override val time: Long,
        override val turn: Long,
        val step: Long,
        val callId: String,
        val name: String,
        val arguments: String,
    ) : ChatItem

    /** 一次工具结果（`tool/result`）。 */
    data class ToolResult(
        override val seq: Long,
        override val time: Long,
        override val turn: Long,
        val step: Long,
        val callId: String,
        val text: String,
        val isError: Boolean,
    ) : ChatItem

    /**
     * 一条轻量提示（轮次结束、命令、审批等），渲染成细分隔行。
     *
     * [eventType] 保留原始事件类型，让 [ChatReducer] 能据此判断生成状态（例如
     * `turn/end` 表示这一轮结束），而不是去匹配展示文案——文案是会改的，类型不会。
     */
    data class Notice(
        override val seq: Long,
        override val time: Long,
        override val turn: Long = 0L,
        val text: String,
        val eventType: String = "",
    ) : ChatItem
}

/**
 * 助手消息里**真正该作为正文渲染**的内容块。
 *
 * 同一次工具调用在会话日志里出现两遍：一遍是独立的 `tool/call` 事件（渲染成
 * [ChatItem.ToolCall]），一遍是 `assistant/message` 的 `content` 里的
 * `{type:'tool-call'}` 块。实测某会话 182 个 `tool/call` 事件对应 182 个 tool-call 块、
 * `callId` 完全一致——不去重就会每次工具调用渲染两遍。
 *
 * 规则与桌面端一致（`dsh-client-ui-chat` 用 `blocks.some(b => b.kind !== 'tool-call')`
 * 决定该助手节点是否保留）：**被中止的消息保留全部块**，因为那时可能没有对应的
 * `tool/call` 事件，滤掉就真的丢内容了。
 *
 * 抽成顶层函数是为了让这条规则能脱离 Compose 单测——M2 的教训是「测试全绿不等于没有
 * 缺陷」，数据层断言对了、UI 层照样可能重复渲染。
 */
fun visibleAssistantBlocks(blocks: List<ContentBlock>, interrupted: Boolean): List<ContentBlock> =
    if (interrupted) blocks else blocks.filterNot { it is ContentBlock.ToolCall }

/**
 * 正在流式生成的一轮（瞬时状态，不来自持久化日志）。
 *
 * 对应 `session/follow` 的 `assistant-stream` 帧序列。按 `(turn, step)` 索引：
 * 一旦同 `(turn, step)` 的 `assistant/message` 持久化事件到达，这一项就被丢弃
 * （见 [ChatReducer]），所以不会和权威内容重复显示。
 */
data class LiveAttempt(
    val attemptId: String,
    val turn: Long,
    val step: Long,
    /** 已到达的 chunk 数，用于校验 `index` 连续性。 */
    val nextIndex: Int = 0,
    /** 拼接中的正文。 */
    val text: String = "",
    /** 拼接中的思考过程。 */
    val reasoning: String = "",
    /** 工具调用的参数增量，按 `tool-call` 的 id 归并。 */
    val toolArgs: Map<String, String> = emptyMap(),
    val toolNames: Map<String, String> = emptyMap(),
)
