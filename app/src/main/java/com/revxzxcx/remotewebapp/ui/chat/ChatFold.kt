package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.visibleAssistantBlocks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面端「标准模式」的折叠逻辑（用户 m24940 的需求）。
 *
 * 权威来源全部从 `F:\DSH\resources\app.asar` 里的 `@deepseek-ai/dsh-client-ui-chat`
 * 逐字读出（asar header 的 `JSON.parse` 在本机 Node 24 上会挂死，改用「定位包名 +
 * 括号配平截子树」的办法 dump 出 bundle，见 `F:\bot\_chatbundle.txt`）：
 *
 *  - `lib/types/client/presentation-policy.js:16231` 的四档策略表：
 *    ```
 *    compact  { foldCompletedTurns:true,  stepGrouping:"collapsed", liveProcessDetail:false, settledReasoningPreview:false }
 *    standard { foldCompletedTurns:true,  stepGrouping:"collapsed", liveProcessDetail:true,  settledReasoningPreview:true  }
 *    detailed { foldCompletedTurns:true,  stepGrouping:"history",   liveProcessDetail:true,  settledReasoningPreview:true  }
 *    verbose  { foldCompletedTurns:false, stepGrouping:"none",      liveProcessDetail:false, settledReasoningPreview:true  }
 *    ```
 *    用户点名要 **standard**：完成的轮次折叠、步骤分组收起、进行中仍显示细节、
 *    已沉淀的思考给一行预览。
 *  - `lib/types/client/conversation-nodes/process-activity.js:14625` 的 `activity(name)`：
 *    工具名 → 活动类别的映射表（本文件 [activityKind] 逐字移植）。
 *  - `lib/types/client/chat/step-process.js:5951` 的 `processTitle(summary, t)`：
 *    折叠行标题由「出现次数最多的前三个类别」拼成（本文件 [foldTitle]）。
 *  - `lib/types/client/conversation-nodes/process-activity.js:14706` 的 `liveToolDetail`：
 *    进行中那一行显示什么（本文件 [liveToolDetail]）。
 *  - `lib/types/client/chat/ReasoningRow.js:9898` 的 `firstLine` /
 *    `latestCompletedParagraphFirstLine`：思考折叠时的预览行（本文件 [reasoningSummary]）。
 *  - `lib/types/client/conversation-nodes/process-groups.js:14764` 的分组规则
 *    （本文件 [foldChatItems]）。
 *
 * **刻意只实现 standard 一档**：桌面端另外三档（compact / detailed / verbose）需要一套
 * 可持久化的偏好设置 + 设置页入口，而用户 m24940 的原话只是「采用这样的方式」。
 * 策略表在这里以 [FoldPolicy] 常量固化，将来要加档位只改这一处。
 */

/** 折叠策略（桌面端 `POLICIES.standard`，`presentation-policy.js:16239`）。 */
object FoldPolicy {
    /** 已结束的轮次折叠成摘要行。 */
    const val FOLD_COMPLETED_TURNS = true

    /** 步骤分组收起。 */
    const val STEP_GROUPING_COLLAPSED = true

    /** 进行中的分组仍显示实时细节。 */
    const val LIVE_PROCESS_DETAIL = true

    /** 已沉淀的思考给一行预览（而不是只写「思考」两个字）。 */
    const val SETTLED_REASONING_PREVIEW = true
}

/**
 * 活动类别——桌面端 `message.stepProcess.done.*` 那一族文案的键。
 *
 * 顺序即 [foldTitle] 里的并列顺序依据无关，仅按出现次数排序；声明顺序只为了方便读。
 */
enum class WorkKind {
    THINKING,
    READ,
    READ_IMAGE,
    WRITE,
    SEARCH,
    EDIT,
    COMMANDS,
    CODE,
    WEB_SEARCH,
    WEB_FETCH,
    SUBAGENTS,
    PLAN,
    QUESTIONS,
    TOOLS,
}

/** 一个类别在分组里出现的次数。 */
data class WorkCount(val kind: WorkKind, val count: Int)

/** 桌面端 zh 字典里 `message.stepProcess.done.*` 的原文（`locale.js`，asar 内）。 */
private val DONE_LABELS: Map<WorkKind, String> = mapOf(
    WorkKind.THINKING to "已完成分析",
    WorkKind.READ to "已读取文件",
    WorkKind.READ_IMAGE to "已读取图片",
    WorkKind.WRITE to "已写入文件",
    WorkKind.SEARCH to "已搜索代码",
    WorkKind.EDIT to "修改了文件",
    WorkKind.COMMANDS to "执行了命令",
    WorkKind.CODE to "运行了代码",
    WorkKind.WEB_SEARCH to "已搜索网页",
    WorkKind.WEB_FETCH to "已访问网页",
    WorkKind.SUBAGENTS to "已协调子智能体",
    WorkKind.PLAN to "更新了计划",
    WorkKind.QUESTIONS to "向用户提出了问题",
    WorkKind.TOOLS to "已调用工具",
)

/** 桌面端 `message.stepProcess.sharedPrefix`（`locale.js`）。 */
private const val SHARED_PREFIX = "已"

/** 桌面端 `message.stepProcess.comma`（`locale.js`）。 */
private const val COMMA = "，"

/**
 * 工具名 → 活动类别。
 *
 * `process-activity.js:14625-14649` 的逐字移植。**顺序有意义**：`name.endsWith("_inspect")`
 * 与 `name.startsWith("terminal_")` 这类前缀/后缀判断必须落在精确判断之后，
 * 否则 `terminal_read` 会被误判成 read。
 */
fun activityKind(toolName: String): WorkKind = when {
    toolName == "read" -> WorkKind.READ
    toolName == "read_image" -> WorkKind.READ_IMAGE
    toolName == "grep" || toolName == "glob" || toolName.endsWith("_inspect") -> WorkKind.SEARCH
    toolName == "write" -> WorkKind.WRITE
    toolName == "edit" || toolName == "apply_patch" -> WorkKind.EDIT
    toolName == "bash" || toolName == "pwsh" || toolName == "exec_command" ||
        toolName == "write_stdin" || toolName.startsWith("terminal_") -> WorkKind.COMMANDS
    toolName == "run_code" -> WorkKind.CODE
    toolName == "web_search" -> WorkKind.WEB_SEARCH
    toolName == "web_fetch" -> WorkKind.WEB_FETCH
    toolName == "subagent" || toolName.startsWith("subagent_") -> WorkKind.SUBAGENTS
    toolName == "todo_write" || toolName == "create_goal" ||
        toolName == "update_goal" || toolName == "get_goal" -> WorkKind.PLAN
    toolName == "ask_user_question" || toolName == "request_user_input" -> WorkKind.QUESTIONS
    else -> WorkKind.TOOLS
}

/**
 * 折叠分组的一行标题。
 *
 * `step-process.js:5951-5967` 的逐字移植：取次数最多的**前三个**类别，
 *  - 0 个 → `已完成分析`（`done.thinking`）；
 *  - 1 个 → 该类别标签；
 *  - 2 个 → `{first}并{second}`，且两边都以「已」开头时把第二个的「已」去掉
 *    （`已读取文件并写入文件`，而不是 `已读取文件并已写入文件`）；
 *  - 3 个 → 用「，」并列；
 *  - 超过 3 个类别 → 末尾加「等」。
 *
 * 并列顺序按次数降序；次数相同则保持首次出现顺序（桌面端用
 * `Array.prototype.sort`，V8 的 sort 稳定，Kotlin 的 `sortedByDescending` 同样稳定）。
 */
fun foldTitle(counts: List<WorkCount>): String {
    val ranked = counts.sortedByDescending { it.count }
    val labels = ranked.take(3).map { DONE_LABELS.getValue(it.kind) }
    val first = labels.firstOrNull() ?: return DONE_LABELS.getValue(WorkKind.THINKING)
    val second = labels.getOrNull(1)
    if (second == null) return first
    if (labels.size == 2) {
        val trimmed =
            if (SHARED_PREFIX.isNotEmpty() && first.startsWith(SHARED_PREFIX) && second.startsWith(SHARED_PREFIX)) {
                second.substring(SHARED_PREFIX.length)
            } else {
                second
            }
        return "${first}并$trimmed"
    }
    val title = (listOf(first) + labels.drop(1)).joinToString(COMMA)
    return if (ranked.size > 3) "${title}等" else title
}

/**
 * 实时细节行的候选键，按桌面端 `LIVE_TOOL_DETAIL_KEYS`（`process-activity.js:14652`）的
 * 原顺序逐个试。
 */
private val LIVE_TOOL_DETAIL_KEYS = listOf(
    "title", "description", "objective", "task", "task_name", "name", "question", "questions",
    "prompt", "message", "command", "cmd", "queries", "query", "pattern", "url", "uri",
    "file_path", "path", "target", "action", "status",
)

/** 桌面端 `LIVE_TOOL_DETAIL_MAX_CHARS`（`process-activity.js:14650`）。 */
private const val LIVE_TOOL_DETAIL_MAX_CHARS = 160

/**
 * 归一化一段细节文本：字符串数组用「, 」连接、折叠连续空白、裁剪到 160 字符。
 *
 * 与桌面端的唯一偏差：桌面端用 `Intl.Segmenter` 按**字素簇**计数（一个 emoji 算 1），
 * 这里按 UTF-16 长度计数。细节行几乎全是路径与命令，差异不可见；真要按照字素簇算得引
 * 第三方库，为一行小字不值。
 */
internal fun normalizeDetail(value: String): String {
    val normalized = value.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= LIVE_TOOL_DETAIL_MAX_CHARS) {
        normalized
    } else {
        normalized.substring(0, LIVE_TOOL_DETAIL_MAX_CHARS - 1).trimEnd() + "…"
    }
}

/** 桌面端 `questionDetail`（`process-activity.js:14681`）：从问题数组里取第一条。 */
private fun questionDetail(value: JsonElement): String {
    if (value !is JsonArray) return ""
    for (item in value) {
        if (item !is JsonObject) continue
        val detail = normalizeDetail((item["question"] as? JsonPrimitive)?.content ?: "")
        if (detail.isNotEmpty()) return detail
    }
    return ""
}

/**
 * 进行中的工具该显示的那一行。
 *
 * `process-activity.js:14706-14720` 的逐字移植：按 [LIVE_TOOL_DETAIL_KEYS] 的顺序取第一个
 * 非空值；JSON 解析失败或不是对象时退回工具名本身（桌面端同样如此——参数还没到齐时
 * `arguments` 是半截 JSON，`JSON.parse` 必炸）。
 */
fun liveToolDetail(toolName: String, argumentsJson: String?): String {
    val args = argumentsJson?.let { raw ->
        try {
            DetailJson.json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
    if (args == null) return normalizeDetail(toolName)
    for (key in LIVE_TOOL_DETAIL_KEYS) {
        if (key !in args) continue
        val value = args[key] ?: continue
        val detail = when (key) {
            "questions" -> questionDetail(value)
            else -> when (value) {
                is JsonPrimitive -> normalizeDetail(value.content)
                is JsonArray -> {
                    val parts = value.mapNotNull { (it as? JsonPrimitive)?.content }
                    if (parts.size == value.size) normalizeDetail(parts.joinToString(", ")) else ""
                }
                else -> ""
            }
        }
        if (detail.isNotEmpty()) return detail
    }
    return normalizeDetail(toolName)
}

/**
 * 本文件专用的宽松 JSON 解析：与线上 `DshJson` 同一套配置（`ignoreUnknownKeys = true`）。
 *
 * 不直接复用 [com.revxzxcx.remotewebapp.core.protocol.DshJson] 是因为这里只需要
 * `parseToJsonElement`，为一个调用把整个协议对象拖进 UI 层不值。
 */
private object DetailJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}

/**
 * 思考过程折叠时的那一行预览。
 *
 * `ReasoningRow.js:9898-9917` 的逐字移植：
 *  - 已沉淀（`running == false`）→ 全文**第一行**；
 *  - 还在流式 → **最后一个完整段落**的第一行（段落之间以空行分隔）。
 *
 * 两种都去掉 `**` 加粗标记——折叠行只有一行高，渲染 Markdown 会折行甚至溢出。
 */
fun reasoningSummary(text: String, running: Boolean): String {
    val raw = if (running) latestCompletedParagraphFirstLine(text) else firstLine(text)
    return raw.replace("**", "").trim()
}

/** `ReasoningRow.js:9898` 的 `firstLine`。 */
internal fun firstLine(text: String): String {
    val newline = text.indexOf('\n')
    return if (newline < 0) text else text.substring(0, newline)
}

/** 桌面端段落分隔符：一个或多个空行（`ReasoningRow.js:9905`）。 */
private val PARAGRAPH_SEPARATOR = Regex("""\r?\n(?:[\t ]*\r?\n)+""")

/** `ReasoningRow.js:9902-9917` 的 `latestCompletedParagraphFirstLine`。 */
internal fun latestCompletedParagraphFirstLine(text: String): String {
    var summary = ""
    var paragraphStart = 0
    for (match in PARAGRAPH_SEPARATOR.findAll(text)) {
        val paragraphEnd = match.range.first + match.value.indexOf('\n')
        val newline = text.indexOf('\n', paragraphStart)
        if (newline in 0..paragraphEnd) {
            val candidate = text.substring(paragraphStart, newline).trim()
            if (candidate.isNotEmpty()) summary = candidate
        }
        paragraphStart = match.range.last + 1
    }
    // 最后一段没有后继分隔符，对应桌面端 `nextParagraph === null` 那次循环体。
    val newline = text.indexOf('\n', paragraphStart)
    if (newline >= 0) {
        val candidate = text.substring(paragraphStart, newline).trim()
        if (candidate.isNotEmpty()) summary = candidate
    }
    return summary
}

/**
 * 消息列表的一行。
 *
 * 抽成行模型而不是在 `LazyColumn` 里就地判断，是因为三处都按行算下标：
 * [com.revxzxcx.remotewebapp.ui.chat.ChatScrollbar] 的标记与滑块、
 * `ChatScrollFollow` 的 `lastIndex`、以及 `ChatHistoryFollow` 的首项判定。
 * 三处各写一遍「哪些条目算一行」必然漂移。
 */
sealed interface ChatRow {

    /** `LazyColumn` 的 key。必须是稳定且全局唯一的值。 */
    val key: Any

    /** 单独一行。 */
    data class Single(val item: ChatItem) : ChatRow {
        override val key: Any get() = item.seq
    }

    /**
     * 折叠成一组的一段连续过程（若干工具调用/结果 + 纯思考的助手消息）。
     *
     * @param anchor 组内第一条，仅用于展示与诊断，**不作为 key**。
     * @param closingSeq 封住这一组的那条独立条目的 `seq`；`null` 表示这一组一直延伸到列表
     *   末尾（当前正在生成的那一组）。
     */
    data class Group(
        val anchor: ChatItem,
        val members: List<ChatItem>,
        val closingSeq: Long?,
    ) : ChatRow {
        /**
         * key 取「后一条独立条目的 seq」而不是 anchor 的 seq，是为了对 `loadMore` 的
         * **前插**稳定：前插页最新一条若是非独立项（tool-call / tool-result / 只含思考的
         * 助手消息），它会与现第一个分组合并，anchor 变成更早条目的 seq。若 key 跟着变，
         * 原槽位被销毁 → `rememberSaveable` 的展开态丢失，且 `LazyColumn` 的 key 锚点失效
         * → 用户停在顶部等历史时列表位置会跳（R015 审查报告 P2-1）。
         *
         * 封住这条组的独立条目在追加过程中不变，所以流式增长时 key 也稳定；只有这一组
         * 真正被合上（下一条独立条目到达）时才变一次 key，而那一刻它已不再是「进行中」。
         *
         * 用 `String` 而不是 `Long`：封住条目本身也是一条 [Single] 行，直接用它的 seq
         * 会与那条行的 key 相撞（`LazyColumn` 要求 key 全局唯一）。
         */
        override val key: Any get() = "group-${closingSeq ?: "tail"}"
    }
}

/**
 * 这条助手消息是否含有**该单独成行**的正文。
 *
 * 判据是「去掉工具调用块之后还剩不含思考的块」——`reasoning` **不算正文**：
 * 一条只承载思考过程的助手消息属于过程的一部分，必须留在折叠分组里，否则每一步的
 * 思考都会单独撑出一行，桌面端「标准模式」的折叠就白做了。
 *
 * 中断的消息 `visibleAssistantBlocks` 会保留工具调用块，于是它有正文 → 单独成行，
 * 与桌面端一致（那时可能没有对应的 `tool/call` 事件，滤掉就真丢内容了）。
 */
internal fun ChatItem.Assistant.hasReply(): Boolean =
    visibleAssistantBlocks(blocks, interrupted).any { it !is ContentBlock.Reasoning }

/** 这条助手消息是否只承载思考过程。 */
internal fun ChatItem.Assistant.hasReasoning(): Boolean =
    blocks.any { it is ContentBlock.Reasoning && it.text.isNotBlank() }

/**
 * 把条目列表折叠成行列表。
 *
 * `process-groups.js:14764-14966` 的移植。桌面端的 `INDEPENDENT` 集合是
 * `user / steering / turn-trigger / model-retry / turn-error / turn-max-tokens / turn-tail`，
 * 在本工程的 [ChatItem] 上对应：
 *
 *  - [ChatItem.User] → `user`（用户自己发的话，永远单独成行）；
 *  - [ChatItem.Notice] → `turn/end`、`turn-error`、`model-retry`、`turn-tail` 一族；
 *  - [ChatItem.Changes] → 桌面端没有对应物，但它是独立卡片，同样必须单独成行；
 *  - 含正文的 [ChatItem.Assistant] → `assistant-step` 的 `response` 部分；
 *  - 其余（工具调用、工具结果、只含思考的助手消息）→ 累积进当前分组。
 *
 * 分组的边界只有三种：遇到独立条目、遇到含正文的助手消息、列表结束。
 *
 * key 的选取见 [ChatRow.Group]：取封住该组的独立条目的 seq，对 `loadMore` 的前插稳定。
 */
fun foldChatItems(items: List<ChatItem>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var pending = mutableListOf<ChatItem>()

    fun flush(closingSeq: Long?) {
        if (pending.isNotEmpty()) {
            rows += ChatRow.Group(pending.first(), pending.toList(), closingSeq)
            pending = mutableListOf()
        }
    }

    for (item in items) {
        val independent = when (item) {
            is ChatItem.User, is ChatItem.Notice, is ChatItem.Changes -> true
            is ChatItem.Assistant -> item.hasReply()
            else -> false
        }
        if (independent) {
            flush(item.seq)
            rows += ChatRow.Single(item)
        } else {
            pending += item
        }
    }
    flush(null)
    return rows
}

/**
 * 一个折叠分组展示用的摘要。
 *
 * @param counts 各类别的出现次数，按降序。
 * @param detail 进行中时要显示的那一行（非空才占一行高度）。
 * @param count 组内条目总数（工具调用数；纯思考的分组为 0）。R017 用它做收起态右侧的
 *     `×N`：不展开也能看出一组里有几条，`counts` 只按类别聚合、看不出总数。
 */
data class FoldSummary(
    val counts: List<WorkCount>,
    val title: String,
    val detail: String = "",
    val count: Int = 0,
)

/**
 * 汇总一个分组。
 *
 * 次数只统计**工具调用**（桌面端 `process-activity.js:14749` 只访问 `tool-call` 节点），
 * 所以纯思考的分组次数为空、标题落到 `已完成分析`。
 */
fun summarizeGroup(members: List<ChatItem>, running: Boolean): FoldSummary {
    val counts = mutableMapOf<WorkKind, Int>()
    var order = 0
    val firstSeen = mutableMapOf<WorkKind, Int>()
    for (member in members) {
        if (member !is ChatItem.ToolCall) continue
        val kind = activityKind(member.name)
        counts[kind] = (counts[kind] ?: 0) + 1
        firstSeen.getOrPut(kind) { order++ }
    }
    val ranked = counts.entries
        .sortedWith(compareByDescending<Map.Entry<WorkKind, Int>> { it.value }.thenBy { firstSeen[it.key] ?: 0 })
        .map { WorkCount(it.key, it.value) }

    val detail = if (running && FoldPolicy.LIVE_PROCESS_DETAIL) {
        val lastTool = members.lastOrNull { it is ChatItem.ToolCall } as? ChatItem.ToolCall
        if (lastTool != null) {
            liveToolDetail(lastTool.name, lastTool.arguments)
        } else {
            val lastReasoning = members.lastOrNull { item ->
                item is ChatItem.Assistant && item.hasReasoning()
            } as? ChatItem.Assistant
            val text = lastReasoning?.blocks
                ?.filterIsInstance<ContentBlock.Reasoning>()
                ?.lastOrNull { it.text.isNotBlank() }
                ?.text
                ?: ""
            reasoningSummary(text, running = true)
        }
    } else {
        ""
    }

    return FoldSummary(
        counts = ranked,
        title = foldTitle(ranked),
        detail = detail,
        count = counts.values.sum(),
    )
}
