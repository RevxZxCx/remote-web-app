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

    /**
     * 「这一轮改了哪些文件」的**占位卡片**（需求 FR-5.4）。
     *
     * ⚠️ 这条事件（`workspace/changes`）的 `data` **只有 `{turn}`**，文件清单并不在事件里：
     * 摘要由桌面端 Host 插件留在内存中，必须拿这条事件的 **`seq`** 去调
     * `GET /remote/api/changes.summary?sessionId=&seq=` 才能取到（形态见
     * [com.revxzxcx.remotewebapp.core.changes.ChangesSummary]）。
     *
     * 所以这里只承载「卡片该出现在哪个位置」，真正的数据由
     * [com.revxzxcx.remotewebapp.ui.chat.ChatViewModel] 异步填进
     * `changesBySeq[seq]`。这样会话日志的解析保持纯函数，不需要网络。
     */
    data class Changes(
        override val seq: Long,
        override val time: Long,
        override val turn: Long,
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
 * 一条**本地排队回声**：消息已经发给服务端、但还没进会话日志。
 *
 * 为什么需要它（用户 m20380 的亲身观察）：会话正在生成时发送，`session/prompt` 用
 * `mode:'queue'` 把消息挂到服务端队列，**只有 `{accepted:true}` 回来**
 * （`typert.host.js:803-805`），不带 itemId；`session/follow` 快照的投影白名单里
 * **没有任何 queue 投影**（`typert.remote-client.js:222-341` 逐行读完），也没有
 * queue 事件。SDK 里的 `pendingSubmissions` 是**进程内**客户端的本地回声
 * （`client.js:1684-1707` `beginSubmission`，桌面 UI 靠它乐观上屏），走 wire 的
 * 远程客户端拿不到。`session/updateQueue` 又必须要 `itemId` 才能删/改/插队
 * （`typert.host.js:843-862`）——**对纯远程客户端实际上不可用**。
 *
 * 结论：排队期间这条消息在 App 里完全不可见，用户只看到输入框被清空，无法判断
 * 发没发出去。所以这里复刻桌面端的本地回声思路：发送成功即上屏，服务端的
 * `user/message` 到达时由 [ChatReducer] 换掉。
 *
 * ⚠️ 这是**纯本地乐观状态**：没有 `seq`（服务端还没给它编号），所以绝不能进
 * `state.items`（那会让 `session/page` 的游标语义和 `distinctBy{seq}` 全部错乱），
 * 也不能写进 Room 缓存——冷启动后它本来就该消失（服务端队列与它无关）。
 *
 * @param id 本地单调计数，只用作 Compose 的 `key` 与失败回滚的身份。
 */
data class QueuedMessage(
    val id: Long,
    val text: String,
    val attachmentCount: Int = 0,
    /** 本地发送时刻（epoch 毫秒），仅用于气泡脚注。 */
    val enqueuedAt: Long = 0L,
)

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
