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
        "command/run" -> commandRunItem(event)
        "command/done" -> commandDoneItem(event)
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
     * `command/run` —— 一条斜杠命令开始执行（R045）。
     *
     * 先落一张「执行中」的卡：`command/done` 可能几毫秒后才到，也可能永远不到
     * （用户中途取消、命令内部抛出）。没有这张占位卡，用户点完 `/export` 到结果
     * 回来之间界面完全静止，与「按钮没反应」无法区分。
     */
    private fun commandRunItem(event: SessionEvent): ChatItem? {
        val run = CommandExecutionParser.parseRun(event.data) ?: return null
        return ChatItem.CommandResult(
            seq = event.seq,
            time = event.time,
            commandId = run.commandId,
            line = run.line,
            running = true,
            succeeded = false,
            text = null,
        )
    }

    /**
     * `command/done` —— 命令结算。
     *
     * ⚠️ 这条事件**不带 `name`**（只有 `commandId` / `kind` / `text`），所以这里无法
     * 自己拼出 `/xxx`：卡片靠 [ChatReducer] 按 `commandId` 与 `command/run` 那条合并
     * （见 `ChatReducer.onEvent` 的命令合并分支）。解析器仍要为「只看到 done、没看到 run」
     * 的情况产出条目——例如中途连上 follow 流时窗口正好从 done 开始，此时显示结果本身
     * 仍比什么都不显示好。
     */
    private fun commandDoneItem(event: SessionEvent): ChatItem? {
        val done = CommandExecutionParser.parseDone(event.data) ?: return null
        return ChatItem.CommandResult(
            seq = event.seq,
            time = event.time,
            commandId = done.commandId,
            line = "",
            running = false,
            succeeded = done.succeeded,
            text = done.text,
        )
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

    /**
     * 这条事件是否值得写进离线缓存（需求 FR-4.3）。
     *
     * 判据**只针对 `user/message`**：注入物不写，其余事件一律写。
     *
     * 为什么不能按「能不能渲染成卡片」过滤：`turn/start`、`turn/end`、`step/start`
     * 这类事件解析不出卡片，却是 `ChatUiState.generating` 与断线补齐窗口下界
     * （`ChatFrame.Snapshot.windowMinSeq`）的权威来源。按渲染性过滤会让
     * `windowMinSeq` 退化成「首个可渲染事件的 seq」，与条目最小 seq 重合，
     * 于是旧缓存里 seq 低于新窗口起点的条目会被误判成「窗口之外的旧历史」而
     * prepend 回列表（R012 P2-A）。
     *
     * `user/message` 之所以可以单独挑出来：注入只经由这一种事件抵达
     * （实测 187/227 条注入全是 `user/message`，141 条 `time-context`）。
     */
    fun isCacheable(event: SessionEvent): Boolean =
        event.type != "user/message" || userItem(event) != null

    /**
     * `user/message` → 用户气泡。
     *
     * ⚠️ **只有 `source.kind === 'user'` 才渲染**，其余一律返回 null。
     *
     * 根因（用户 m22856 反馈「聊天界面中出现很多不是我发送的提示词，看起来是插件构建的
     * prompt」）：`user/message` 这个事件类型被**所有**往会话日志里追加用户可见文本的
     * 发起方共用，`source.kind` 才是区分「人输入的」与「系统注入的」的字段。
     *
     * 权威定义在 `typert.host.js:1913` 的 `MessageSourceMap`：`user` 与 `user-rpc`
     * 的 kind 都是 `'user'`（前者桌面端人工输入，后者远程客户端 RPC —— 也就是本 App
     * 发出的消息，实测 `source = {kind:'user', rpcId:'…'}`），其余 16 种 kind 全是
     * 注入物：`model` / `tool` / `system-prompt` / `model-selection` /
     * `user-approval` / `ptc-mode` / `tool-registry` / `agent-message` /
     * `subagent-settled` / `skill-invocation` / `cordis-host-runner` / `goal` /
     * `schedule` / `compact-checkpoint` / `session-reference` / `user-question-reply`。
     * 宿主插件还会往里加这张表里没有的 kind（实测有 `time-context` /
     * `runtime-context` / `skill-catalog` / `tool-jobs` / `tool-goal`），
     * 所以必须**白名单**而不是黑名单。
     *
     * 实测规模（`app开发 main`，31361 条事件里 227 条 `user/message`）：
     * 只有 **40 条** kind 是 `user`，其余 **187 条**是注入物，其中 `time-context`
     * （「Time sampled while preparing turn N, step M…」，约每 10 分钟一条）**141 条**、
     * `subagent-settled` 17、`agent-message` 13、`model-selection` 5、`tool-jobs` 4、
     * `goal` 3，`user-approval` / `runtime-context` / `skill-catalog` / `tool-goal`
     * 各 1。全部渲染出来会把聊天刷满，且用户分不清哪条是自己发的。
     *
     * 服务端自己也用同一条判据（`lib/types/index.js:264`、`lib/index.js:2895` 的
     * `api-session/activity`、`lib/types/list.js:29` 的 `lastPromptAt` 全是
     * `event.data.source.kind !== 'user'` 就跳过），所以这不是 UI 偏好而是数据语义。
     *
     * 刻意严格：`source` 缺席时也返回 null。本工程面对的所有会话都带 source
     * （实测 227/227），而「多显示一行」的代价远大于「少显示一行」。
     */
    private fun userItem(event: SessionEvent): ChatItem? {
        // `user/message` 的 data 就是 UserMessage 本身（不是 {message: ...} 包裹）。
        if ((event.data["source"] as? JsonObject)?.string("kind") != "user") return null
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

    /**
     * `turn/end` → 轮次结果。
     *
     * ⚠️ R043 之前这里是 `event.data.string("reason")`：`reason` 是**对象**，
     * `stringOrNull` 只认 `JsonPrimitive` 字符串 → **恒为 null** → 所有结局（包括模型报错、
     * 输出被截断）都渲染成同一行灰字「第 N 轮结束」，用户 m26743 的「中断时什么都看不到」
     * 就是这么来的。现在按 [parseTurnOutcome] 解析出结构化结局。
     *
     * `completed` 与「解析不出 reason」都**不产卡片**：正常结束每轮都有一条，
     * 画出来只是噪声；后者退化成原来的分隔线（由 [ChatReducer] 自己维护的轮次边界负责）。
     */
    private fun turnEndNotice(event: SessionEvent): ChatItem? {
        // `turn` 只喂卡片上的「第 N 轮」文案，**不能**当成出卡的前提：字段缺失时
        // 提前 return 会把一条带着上游错误原文的结局一起丢掉，退化成 R043 之前的
        // 静默状态——正好丢掉了本需求最要紧的信息（R043 审查 P2-2）。
        val turnValue = event.data.long("turn")
        val outcome = parseTurnOutcome(event.data["reason"]) ?: return null
        if (outcome is TurnOutcome.Completed) return null
        return ChatItem.TurnResult(
            seq = event.seq,
            time = event.time,
            turn = turnValue ?: 0L,
            outcome = outcome,
            turnKnown = turnValue != null,
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
