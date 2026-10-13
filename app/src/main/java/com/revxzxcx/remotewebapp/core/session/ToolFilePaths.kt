package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从工具调用的 `arguments` 里提取**这个工具动了哪个文件**。
 *
 * 依据（真机实测，2026-10-13，51 个会话的日志全量统计）：
 * ```
 *   139 pwsh | 81 read | 75 write | 50 edit | 21 grep
 *    10 compress | 2 job_kill | 2 search_context | 1 todo_write | 1 decompress | 1 glob
 * ```
 * 实际会带文件路径的只有 `read` / `write` / `edit` 三个，**参数名统一是 `file_path`**
 * （`write`：`{"file_path": "...", "content": "..."}`；`edit`：`{"file_path": "...", "old_string": ..., "new_string": ...}`）。
 *
 * ⚠️ **`arguments` 是 JSON 字符串，且键序不固定** —— 同一次实测里，两个 `write` 调用的样本
 * 一个把 `file_path` 放在最前、另一个把 `content` 放在最前。所以**必须走 JSON 解析，
 * 绝不能用正则去捞 `"file_path"` 前面的内容**（那正是官方文档 §3.8 里强调「arguments 是
 * 字符串不是对象」的原因）。
 *
 * ⚠️ 桌面端把 `str_replace_editor` 也算文件工具（`dsh-workspace-changes` 在它执行前做整文件
 * 捕获），但**本项目实测的 51 个会话里一次都没出现过**；这里仍保留它的多种参数名（`path`），
 * 因为它一旦被启用，参数名与 `file_path` 不同——宁可多认一个键，也不要静默丢一行。
 */
internal object ToolFilePaths {

    /**
     * 会改动文件的工具（用于「这一轮改了哪些文件」的**实时推断**）。
     *
     * 只含写类工具，不含 `read` —— `read` 执行成功不代表文件被改动，
     * 把它算进去会让改动列表凭空多出一堆只读过的文件。
     */
    private val MUTATING = setOf("write", "edit", "str_replace_editor", "create_file", "multi_edit", "apply_patch")

    /** 会读到文件的工具（用于「工具卡片上的文件行可点开」）。 */
    private val READING = setOf("read", "read_file")

    /** 依次尝试的参数名。顺序无关紧要，只是把已知的都列全。 */
    private val PATH_KEYS = listOf("file_path", "path", "filePath", "notebook_path")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 这个工具是否**改动**文件。 */
    fun isMutating(toolName: String): Boolean = toolName.lowercase() in MUTATING

    /** 这个工具是否**读取**文件。 */
    fun isReading(toolName: String): Boolean = toolName.lowercase() in READING

    /** 只关心这两个工具里的路径（其余工具即使有 `path` 也不是文件）。 */
    fun isFileTool(toolName: String): Boolean = isMutating(toolName) || isReading(toolName)

    /**
     * 从 `arguments` 中取出文件路径；解析不出时返回 `null`。
     *
     * 宽容策略与其余解析器一致：`arguments` 不是 JSON、不是对象、路径为空字符串，
     * 一律返回 `null` 而不是抛异常——畸形的一条工具调用不该让整个聊天流崩掉。
     */
    fun extract(toolName: String, arguments: String?): String? {
        if (!isFileTool(toolName)) return null
        val raw = arguments?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        for (key in PATH_KEYS) {
            // 必须要求 `isString`：`JsonNull` 也是 `JsonPrimitive`，不守卫时
            // `{"file_path":null}` 会返回字面量 `"null"`、`{"file_path":123}` 会返回
            // `"123"`，下游拿它们当路径去请求文件，报一个谁也看不懂的 not-found。
            // 与 `ChangesParser.string()` 保持同一守卫（R005 审查发现）。
            val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
