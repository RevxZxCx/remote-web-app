package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 输入区「+」面板里的一段内容（R043，用户 m26743 第 2 条）。
 *
 * 桌面端的「+」打开的不是附件菜单，而是 **`/` 命令菜单**
 * （`aria-label = 添加文件或调用指令`；onClick 走 `toggleSource("command", {trigger:"/"})`，
 * 与在输入框里打 `/` 完全是同一个东西）。命令清单由 `commands/list` 提供，
 * 选中后**不是**调某个 invoke RPC，而是把 `/name 参数` 拼成一条普通用户消息发出去——
 * 服务端 `session/prompt` 自己解析斜杠命令。
 *
 * 数据形状（`commands/list` 实测 19 条，参数 `{agentId: "session-<id>"}`）：
 * ```
 * [{"name":"goal","description":"Set or view the goal …",
 *   "definitionId":"@deepseek-ai/dsh-command-goal",
 *   "input":{"hint":"[<objective>|clear|…]","attachments":true}}, …]
 * ```
 * `definitionId` 只有核心命令才有（插件注册的命令没有），所以**不能**把它当必需字段。
 */
data class SlashCommand(
    /** 不含斜杠的命令名，例如 `goal`。 */
    val name: String,
    val description: String,
    /**
     * 参数提示，来自 `input.hint`。为 `null` 通常意味着这条命令不吃参数——这是**启发式**，
     * 不是保证：`/n`、`/y` 没有 `input.hint` 却要求 `#编号`（见 [insertion] 的「已知缺口」）。
     * 面板只据它决定是否显示那行参数提示；选中命令只写草稿，从不直接发送。
     */
    val hint: String?,
    /** `input.attachments == true`：这条命令允许同时挂附件。 */
    val acceptsAttachments: Boolean,
    /** 核心命令的注册 id；插件命令没有。 */
    val definitionId: String?,
) {
    /** 显示用的命令文本（`/goal`）。**不带尾空格**：面板列表按行渲染，尾部空白只会让对齐发虚。 */
    val trigger: String get() = "/$name"

    /**
     * 插进输入框的文本：吃参数的命令带一个**尾空格**，不吃参数的不带。
     *
     * 为什么要有这一格空白（R048，用户 m32662「这些都需要打一个空格的，你的设计中会自动完成
     * 这个空格操作吗」）：选中命令后光标落在末尾、用户紧接着要打参数，若正文是 `/goal` 而光标
     * 贴着 `l`，第一下敲出来的字符会粘成 `/goal完成`——既不是命令也不是参数。
     *
     * 判据取 [hint]，是一层**启发式**而不是保证：多数命令用 `input.hint` 声明参数形态，但
     * 插件命令可以不声明却仍然吃参数。实测（`commands/list`，19 条）里就有反例——`/n` 与 `/y`
     * 的描述写着「多项时 /n #编号」，却没有 `input` 字段，于是这里按「不吃参数」处理，用户从
     * 面板选中它们再打 `#1` 会粘成 `/n#1`。这是**已知缺口**，唯一干净的方向是服务端在
     * `input.hint` 之外补一个「是否吃参数」的显式标记——靠猜描述文本（正则匹配 `#` 或
     * `<…>`）不可靠：描述是给人看的，措辞随时会变，而且一旦猜错就是静默多发一个空格。
     * 在服务端补上标记之前，这里保持现状（宁可少数几条命令少一个空格，也不引入猜测）。
     *
     * 空格的去处：发送走 `composedText().trim()`、命令识别走 `trim()`，服务端拿到的行永远不带
     * 这个尾空格。但**草稿落盘不 trim**（`DshCache.writeDraft` 只做 `take(DRAFT_MAX_CHARS)`
     * 与判空），所以尾空格会随草稿存进 SQLite。
     *
     * 不要指望「冷启动恢复后光标停在这个空格之后」：`restoreDraft()` 只写 `_draft`、不碰
     * `draftSelection`，而后者是 `ChatScreen` 里 `rememberSaveable(TextRange.Zero)` 的，真正的
     * 冷启动下取 0——恢复出来的草稿光标在**开头**。只有 `onApplyCommand` / `addReference` /
     * `removeReference` 这三条路径才会把光标送到末尾。
     */
    val insertion: String get() = if (hint != null) "/$name " else "/$name"
}

/**
 * `fileReferences/list` 的一条结果：工作区内的文件或目录。
 *
 * 实测参数 `{agentId: "session-<id>", query: "R04"}`，返回**相对工作区根**的路径
 * （`{"path":"remote-web-app/docs/R043-效果图.html","kind":"file"}`）。
 */
data class FileReference(
    val path: String,
    val isDirectory: Boolean,
)

/**
 * 把 `commands/list` 的原始响应解析成 [SlashCommand]。
 *
 * 健壮性优先于严格：服务端把命令注册成什么样都有（核心命令带 `definitionId`、
 * 插件命令不带；`input` 有时缺 `attachments`），**任何一条解析失败都不能让整份清单消失**
 * ——那样用户点开「+」看到空列表，比看到 18 条少 1 条糟得多。
 */
internal object CommandCatalogParser {

    fun parse(element: JsonElement?): List<SlashCommand> {
        // 两种形态都认：包一层（`{value: […]}` / `{result: […]}`）与裸数组。
        // 实测 `commands/list` 是前者，但同一套 typert 网关里两种都出现过，
        // 而这里给不出任何「哪种更对」的理由，多一个分支的成本可以忽略。
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                val value = element["value"] ?: element["result"] ?: element
                value as? JsonArray ?: return emptyList()
            }
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name")?.trim()?.removePrefix("/") ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val input = obj["input"] as? JsonObject
            SlashCommand(
                name = name,
                description = obj.string("description").orEmpty(),
                // hint 为空串等于没提示：面板只据 hint 是否为空决定要不要显示那行参数提示，
                // 空串当「有 hint」会让面板显示一行什么都没说的提示。
                hint = input?.string("hint")?.takeIf { it.isNotBlank() },
                acceptsAttachments = (input?.get("attachments") as? JsonPrimitive)?.content == "true",
                definitionId = obj.string("definitionId"),
            )
        }
    }

    /**
     * 把选中的命令拼成落进输入框的那条文本。
     *
     * 空参分支返回 [SlashCommand.insertion]（吃参数的命令带尾空格），不接受「裸 trigger」——
     * 见那条属性的 KDoc：没有这个空格，用户接着打字会把参数粘到命令名上。
     */
    fun compose(command: SlashCommand, argument: String): String {
        val arg = argument.trim()
        return if (arg.isEmpty()) command.insertion else "${command.trigger} $arg"
    }
}

/**
 * 输入区「＋」面板里两个快捷入口的兜底命令。
 *
 * 快捷入口**先查服务端清单**（`commands.firstOrNull { it.name == … }`），只在清单还没拉回来时
 * 用这里的定义兜底。兜底必须与服务端一致，否则 `insertion` 的判据会分叉——R048 审查 P1 就是
 * 这么来的：兜底里 `plan` 写成 `hint = null`，于是「计划模式」插入的 `/plan` 不带尾空格，
 * 用户打参数就得到 `/plan重构登录`，整行被当普通消息发出去。
 *
 * 放在这里（而不是留在 `ChatScreen` 的 lambda 里）是为了让单测能直接断言这两条命令吃参数、
 * 插入文本带尾空格——写在 Composable 里就只能靠设备验收。
 */
internal object CommandShortcuts {
    /** `/goal`：服务端 `input.hint = "[<objective>|clear|edit <objective>|pause|resume]"`、`attachments = true`。 */
    val goal = SlashCommand(
        name = "goal",
        description = "设置或查看目标",
        hint = "[<objective>|clear|edit <objective>|pause|resume]",
        acceptsAttachments = true,
        definitionId = "@deepseek-ai/dsh-command-goal",
    )

    /** `/plan`：服务端 `input.hint = "[off|message]"`、`attachments = true`。 */
    val plan = SlashCommand(
        name = "plan",
        description = "计划模式",
        hint = "[off|message]",
        acceptsAttachments = true,
        definitionId = "@deepseek-ai/dsh-plan-mode",
    )
}

/** `fileReferences/list` 的解析。形状同 [CommandCatalogParser]：外面套一层 `value`。 */
internal object FileReferenceParser {

    fun parse(element: JsonElement?): List<FileReference> {
        // 与 [CommandCatalogParser.parse] 同一件事：`callRaw` 已经把回包的
        // `result.value` 拆出来了，所以这里拿到的**就是数组**。首版照抄了
        // 「先当对象再摸 value」的写法，结果裸数组被 `as? JsonObject` 丢掉，
        // 引用选择器永远显示「这个工作区没有可引用的文件」（设备验收 m27494 抓到）。
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                val value = element["value"] ?: element["result"] ?: element
                value as? JsonArray ?: return emptyList()
            }
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val path = obj.string("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FileReference(path = path, isDirectory = obj.string("kind") == "directory")
        }
    }
}

/** 取字符串字段；非字符串原始值不算（与 `SessionListParser` 同一口径）。 */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
