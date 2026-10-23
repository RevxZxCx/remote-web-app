package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
     * 一轮结束且**结局异常**时的结果卡（R043，用户 m26743）。
     *
     * 与 [Notice] 的区别：Notice 是「第 N 轮结束」这种中性分隔行；本条目承载的是
     * **用户需要知道并可能要处理**的结局——模型报错、输出被截断、被谁中止、策略拦下。
     * 正常结束（[TurnOutcome.Completed]）不产生本条目，避免每轮多一行噪声。
     *
     * [outcome] 决定卡片视觉与可执行动作（见 `TurnResultCard`）：只有
     * [TurnOutcome.Failed] 里那些「重试有意义」的 code 才给「重试本轮」。
     */
    data class TurnResult(
    override val seq: Long,
    override val time: Long,
    override val turn: Long,
    val outcome: TurnOutcome,
    /** `turn/end` 里是否真的带了 `turn` 字段；缺失时卡片不显示「第 N 轮」。 */
    val turnKnown: Boolean = true,
) : ChatItem

    /**
     * 一条斜杠命令的执行记录（R045，用户 m27926 期间的连带发现）。
     *
     * 为什么必须自己渲染：`command/run` / `command/done` 是**独立的落盘事件**，
     * 不挂在任何 turn 上（实测 `data` 只有 `commandId` / `name` / `args` / `source`，
     * 没有 `turn`）。R043 之前 App 完全不认这两个事件，于是用户从输入区发 `/export`
     * 时，命令确实被执行了（服务端事件里能看到 `kind:"success"` 与输出），但
     * **界面上一个字都没有**——用户以为按钮没反应。
     *
     * [text] 是 `command/done` 的输出；[running] 为真表示只收到了 `command/run`，
     * 结果还没回来（渲染成「执行中」）。
     */
    data class CommandResult(
        override val seq: Long,
        override val time: Long,
        override val turn: Long = 0L,
        val commandId: String,
        val line: String,
        val running: Boolean,
        val succeeded: Boolean,
        val text: String?,
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
 * 一轮结束时服务端给出的**结局**（`turn/end` 的 `data.reason`）。
 *
 * ⚠️ R043 之前的实现是 `event.data.string("reason")`——而 `reason` 是**对象**，
 * `stringOrNull` 只认 `JsonPrimitive` 字符串，于是**恒返回 null**，所有结局都被渲染成
 * 同一行灰字「第 N 轮结束」。用户 m26743：模型报错、输出被截断时「app 端看不到任何内容」。
 *
 * 线上形状（实测 130 个会话、646 次 `turn/end`）：
 * ```
 * {kind:'completed'}
 * {kind:'error', error:{message, code, status?}}
 * {kind:'max-tokens'}
 * {kind:'aborted', reason:{kind:'user'|'parent'|'disposed'}}
 * {kind:'blocked'}
 * {kind:'interrupted'}
 * ```
 * `data` 里只有 `{turn, reason}` 两个键，没有别的东西可捡。
 *
 * 六种 `kind` 折成三种**视觉**（见 `TurnResultCard`）：失败（红）、被截断（琥珀）、中性
 * （`aborted` / `blocked` / `interrupted`）。但文案必须按 `kind` 分开——「你点了停止」和
 * 「进程重启把它掐了」对用户是完全不同的两件事。
 */
sealed interface TurnOutcome {

    /** `{kind:'completed'}` —— 正常结束。**不出卡片**（否则每轮都多一行噪声）。 */
    data object Completed : TurnOutcome

    /** `{kind:'error', error:{message, code, status?}}` —— 模型/网关报错，[message] 是给用户看的原文。 */
    data class Failed(
        val message: String,
        val code: String?,
        val status: Int?,
    ) : TurnOutcome

    /** `{kind:'max-tokens'}` —— 输出被截断，内容**不完整**但可续写。 */
    data object Truncated : TurnOutcome

    /** `{kind:'aborted', reason:{kind}}` —— 被主动中止；[by] 区分是谁动的手。 */
    data class Aborted(val by: AbortCause) : TurnOutcome

    /** `{kind:'blocked'}` —— 权限/策略拦下，需要用户处理（例如去桌面端批准）。 */
    data object Blocked : TurnOutcome

    /** `{kind:'interrupted'}` —— 上游断流或进程重启，内容可能只有半截。 */
    data object Interrupted : TurnOutcome

    /** 认不出的 `kind`：保留原文，渲染成中性卡，不猜语义。 */
    data class Unknown(val kind: String) : TurnOutcome
}

/**
 * 中止动作的来源。
 *
 * 实测分布：`user` 73 次、`disposed` 2 次、`parent` 1 次。文案必须分开——
 * 用户自己按的停止键不该显示「异常中止」，那会让人以为出了故障。
 */
enum class AbortCause(val raw: String) {
    /** 用户点了停止。 */
    USER("user"),

    /** 父会话把它掐了（子代理场景）。 */
    PARENT("parent"),

    /** 进程/连接销毁（App 被杀、桌面端重启）。 */
    DISPOSED("disposed"),

    /** 未知来源。 */
    UNKNOWN(""),
    ;

    companion object {
        fun of(raw: String?): AbortCause = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * 解析 `turn/end` 的 `data.reason`。
 *
 * 容忍两种退化形态：
 *  - `reason` 是字符串（老版本服务端 / 手工构造的测试数据）→ 当作 [TurnOutcome.Unknown]，
 *    但保留原文，不丢信息；
 *  - `reason` 缺失 → 返回 `null`，调用方回退到「第 N 轮结束」分隔线。
 *
 * 抽成顶层函数是为了能脱离 Compose 单测——本项目 M2 的教训是「数据层断言对了、
 * UI 层照样可能渲染错」，所以判据必须放在能被纯 JVM 测试钉住的位置。
 */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 读一个数字字段。
 *
 * ⚠️ 不能照抄 [string] 的 `isString` 判据：`error.status` 在线上是 **JSON 数字**
 * （`"status":429`），而 `isString` 只对带引号的字面量为真。R043 首版就是抄了
 * `string` 的写法，导致 status 永远是 null——`TurnOutcomeTest` 立刻抓到。
 * 同时接受字符串形态（服务端偶尔把数字序列化成字符串），两种都认才稳。
 */
private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

fun parseTurnOutcome(reason: JsonElement?): TurnOutcome? {
    // 字符串形态的 reason 在旧格式/回放历史里真的存在（`{"turn":0,"reason":"completed"}`，
    // 见 `ChatReducerTest` 里的夹具）。首版一律当 `Unknown`，于是**正常结束也会冒出
    // 一张红色「本轮结束异常」卡**——把好轮次报成故障（R043 审查 P2-1）。
    // 已知 kind 按对象分支同一套语义翻译，匹配不上才退回 `Unknown`。
    val raw = reason?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    if (raw != null) {
        return when (raw.trim().lowercase()) {
            "completed", "complete", "done" -> TurnOutcome.Completed
            "max-tokens", "max_tokens", "truncated" -> TurnOutcome.Truncated
            "blocked" -> TurnOutcome.Blocked
            "interrupted" -> TurnOutcome.Interrupted
            "user" -> TurnOutcome.Aborted(AbortCause.USER)
            "parent" -> TurnOutcome.Aborted(AbortCause.PARENT)
            "disposed" -> TurnOutcome.Aborted(AbortCause.DISPOSED)
            else -> TurnOutcome.Unknown(raw)
        }
    }
    val obj = reason as? JsonObject ?: return null
    val kind = obj.string("kind") ?: return null
    return when (kind) {
        "completed" -> TurnOutcome.Completed
        "max-tokens" -> TurnOutcome.Truncated
        "blocked" -> TurnOutcome.Blocked
        "interrupted" -> TurnOutcome.Interrupted
        "aborted" -> TurnOutcome.Aborted(
            AbortCause.of((obj["reason"] as? JsonObject)?.string("kind")),
        )
        "error" -> {
            val err = obj["error"] as? JsonObject
            TurnOutcome.Failed(
                message = err?.string("message").orEmpty(),
                code = err?.string("code")?.takeIf { it.isNotBlank() },
                status = err?.long("status")?.toInt(),
            )
        }
        else -> TurnOutcome.Unknown(kind)
    }
}

/** `agent/inbox/spliced` 事件的两个收件箱（`InboxTarget`）。 */
const val INBOX_NEXT_TURN = "next-turn"
const val INBOX_NEXT_STEP = "next-step"

/**
 * 一条**排队中**的消息（服务端 inbox 里还没进会话日志的用户消息）。
 *
 * 权威来源有两个，都必须接（用户 m21177 的第 4 条反馈）：
 *  1. `session/follow` 快照的 `projections.values.inbox`，形状
 *     `{next-turn: UserMessage[], next-step: UserMessage[]}`（`typert.remote-client.js:263-266`）；
 *  2. 实时事件 `agent/inbox/spliced`，data = `{target, start, removedCount?, inserted, outcome?}`
 *     （`SessionEventMap`，`index.js:661` 的推进条件里就有它）。
 *
 * **`UserMessage` 的线上形状**（`_inboxp4.mjs` 实测）：
 * ```
 * {content:[{type:'text',text}], source:{kind:'user', rpcId:'<我方 requestId>'}, role:'user', id:'<uuid>'}
 * ```
 * `id` 就是 `session/updateQueue` 要的 `itemId`（`index.js:955-956` 按 `m.id === request.itemId` 查找），
 * `source.rpcId` 则是**我们自己生成并传出去的 requestId**——服务端原样带回，所以它是把
 * 「本地回声」与「服务端条目」对应起来的唯一可靠键（比按文本匹配稳得多）。
 *
 * 由此 R009 记录的「远程客户端拿不到 itemId、`updateQueue` 不可用」结论**被推翻**：
 * itemId 就在 `inbox` 投影与 splice 事件里，`updateQueue` 的三种操作
 * （`edit` / `remove` / `steer`）对远程客户端完全可用。
 *
 * ⚠️ 这是**纯本地乐观状态**：没有 `seq`（服务端还没给它编号），所以绝不能进
 * `state.items`（那会让 `session/page` 的游标语义和 `distinctBy{seq}` 全部错乱），
 * 也不能写进 Room 缓存——冷启动后它本来就该消失（服务端队列与它无关）。
 *
 * @param id 服务端 `itemId`；本地回声还没被 `agent/inbox/spliced` 确认前是 `local:<n>`。
 * @param rpcId 我方 `session/prompt` 的 requestId，用来把回声与服务端条目配对。
 * @param target [INBOX_NEXT_TURN]（等当前轮结束）或 [INBOX_NEXT_STEP]（已插队进当前轮）。
 * @param confirmed 是否已拿到服务端 itemId（拿到才能编辑/删除/插队）。
 */
data class QueuedMessage(
    val id: String,
    val rpcId: String,
    val text: String,
    val attachmentCount: Int = 0,
    /** 本地发送时刻（epoch 毫秒），仅用于气泡脚注。 */
    val enqueuedAt: Long = 0L,
    val target: String = INBOX_NEXT_TURN,
    val confirmed: Boolean = false,
) {
    /** 是否还在等当前轮结束（而不是已经插队进当前轮）。 */
    val isAwaitingTurn: Boolean get() = target == INBOX_NEXT_TURN
}

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
