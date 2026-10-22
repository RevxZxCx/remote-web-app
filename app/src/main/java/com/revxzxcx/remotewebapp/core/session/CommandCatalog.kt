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
     * 参数提示，来自 `input.hint`。为 `null` 表示这条命令**不吃参数**，
     * 选中即发送——此时面板不该给参数输入框（否则用户以为必须填点什么）。
     */
    val hint: String?,
    /** `input.attachments == true`：这条命令允许同时挂附件。 */
    val acceptsAttachments: Boolean,
    /** 核心命令的注册 id；插件命令没有。 */
    val definitionId: String?,
) {
    /** 输入框里该显示的文本（`/goal `，带尾空格方便直接接参数）。 */
    val trigger: String get() = "/$name"
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
                // hint 为空串等于没提示：命令吃不吃参数看的是有没有 hint，
                // 空串当「有 hint」会让面板给一个什么都不说的输入框。
                hint = input?.string("hint")?.takeIf { it.isNotBlank() },
                acceptsAttachments = (input?.get("attachments") as? JsonPrimitive)?.content == "true",
                definitionId = obj.string("definitionId"),
            )
        }
    }

    /** 把选中的命令拼成要发出去的那条文本。 */
    fun compose(command: SlashCommand, argument: String): String {
        val arg = argument.trim()
        return if (arg.isEmpty()) command.trigger else "${command.trigger} $arg"
    }
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
