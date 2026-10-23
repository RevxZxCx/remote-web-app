package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次 `commands/execute` 的结果（R045）。
 *
 * 服务端 descriptor 的 result 是 `union([undefined, {commandId, result}])`：
 * - 认得这条命令 → `{commandId, result: {kind:'success'|'error', text?}}`
 * - 认不得 → **没有 value**（`callRaw` 给 `JsonNull`）
 *
 * 第二种**不是**异常：用户手打一个不存在的 `/foo`，服务端就是什么都不做。
 * 所以解析返回可空，把「认不得」与「执行失败」分开表达。
 */
data class CommandExecution(
    val commandId: String,
    val succeeded: Boolean,
    val text: String?,
)

/**
 * `commands/execute` 与 `command/run` / `command/done` 两条事件的共同解析。
 *
 * 事件形状（实测，`F:/bot` 会话 seq 35989 起）：
 * ```
 * command/run  {"commandId":"cmd-661b45a5-1","name":"skill-review","args":"","source":{"kind":"user"}}
 * command/done {"commandId":"cmd-661b45a5-1","kind":"success","text":"No skill candidates…"}
 * ```
 * 注意 `command/done` **没有** `name`：App 要把两条按 `commandId` 拼起来才能显示
 * 「执行了哪条命令、结果是什么」。
 */
internal object CommandExecutionParser {

    /** 解析 `commands/execute` 的返回值。`null` = 服务端不认这条命令。 */
    fun parse(element: JsonElement?): CommandExecution? {
        val obj = element as? JsonObject ?: return null
        val commandId = obj.string("commandId")?.takeIf { it.isNotBlank() } ?: return null
        val result = obj["result"] as? JsonObject
        val kind = result?.string("kind").orEmpty()
        val text = result?.string("text")?.takeIf { it.isNotBlank() }
        return CommandExecution(
            commandId = commandId,
            // 只有明确的 `success` 才算成功。其它（含 kind 缺失）一律按失败对待——
            // 反过来会把「执行出错」渲染成绿勾，比不显示更糟。
            succeeded = kind == "success",
            text = text,
        )
    }

    /** `command/run`：拿到命令名与参数，用于在占位卡片上先显示「正在执行 /xxx」。 */
    fun parseRun(data: JsonObject): CommandRun? {
        val commandId = data.string("commandId")?.takeIf { it.isNotBlank() } ?: return null
        return CommandRun(
            commandId = commandId,
            name = data.string("name").orEmpty(),
            args = data.string("args").orEmpty(),
        )
    }

    /** `command/done`：拿到结果。`name` 不在这一条里，由 [CommandRun] 补。 */
    fun parseDone(data: JsonObject): CommandDone? {
        val commandId = data.string("commandId")?.takeIf { it.isNotBlank() } ?: return null
        return CommandDone(
            commandId = commandId,
            succeeded = data.string("kind") == "success",
            text = data.string("text")?.takeIf { it.isNotBlank() },
        )
    }
}

/** `command/run` 的载荷。 */
data class CommandRun(
    val commandId: String,
    val name: String,
    val args: String,
) {
    /** `/skill-review 参数` 这种可读形式；没有参数时不带尾空格。 */
    val line: String get() = if (args.isBlank()) "/$name" else "/$name $args"
}

/** `command/done` 的载荷。 */
data class CommandDone(
    val commandId: String,
    val succeeded: Boolean,
    val text: String?,
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 判断一条待发文本是不是**可执行的斜杠命令**（R045，纯函数便于单测）。
 *
 * 返回 `true` 时调用方必须走 `commands/execute`，**不能**再走 `session/prompt`；
 * 否则命令被当普通文本投进模型上下文，而服务端还会顺手执行一遍——同一句话既污染
 * 对话、又产生一份用户看不见的结果（用户 m27926 期间实测到的现象）。
 *
 * 判据是三条同时成立：
 * 1. 去掉首尾空白后以 `/` 开头；
 * 2. 后面紧跟的名字**在已知命令清单里**——清单来自 `commands/list`。不认得的
 *    `/foo` 必须当普通文本发出去：用户可能就是想讨论一个路径或写一段以斜杠开头
 *    的文本，服务端那边也只会回 `{ok:true}` 空结果，把它吞掉等于丢消息；
 * 3. 命令清单非空（没拉到清单时无从判断，一律按普通文本发，行为与 R043 一致）。
 *
 * @param knownNames 只要名字，不含前导斜杠（[SlashCommand.name]）。
 */
internal fun isExecutableCommand(text: String, knownNames: Set<String>): Boolean =
    parseCommandWord(text)?.let { it in knownNames } == true

/**
 * 取出斜杠行里的命令名（不含斜杠）；不是斜杠行返回 `null`。
 *
 * 与桌面端一致：名字在第一个空白前结束，`/export extra args` 的命令名是 `export`。
 */
internal fun parseCommandWord(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val word = trimmed.drop(1).takeWhile { !it.isWhitespace() }
    return word.ifEmpty { null }
}
