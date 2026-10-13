package com.revxzxcx.remotewebapp.core.changes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 解析 `changes.summary` / `changes.diff` 的裸 JSON。
 *
 * 全部手写、全部宽容：这些路由回的是**裸 JSON**（不是 Typert 信封），字段由桌面端
 * 插件拼装，加字段不能崩、缺字段要能降级。任何解析不出的输入都返回 null，由调用方决定
 * 是显示「无法解析」还是走重试。
 */
internal object ChangesParser {

    fun summary(element: JsonElement): ChangesSummary? {
        val obj = element as? JsonObject ?: return null
        val turn = obj.long("turn") ?: return null
        val files = (obj["files"] as? JsonArray).orEmpty().mapNotNull { fileOf(it) }
        // `total` 是「截断前的完整数」，不可能小于已经列出来的条数；服务端给了个更小的值
        // 只会让 UI 显示「还有 -3 个文件未列出」。增删行数同理不该为负（R005 P3）。
        val total = (obj.int("total") ?: files.size).coerceAtLeast(files.size)
        return ChangesSummary(
            turn = turn,
            total = total,
            added = (obj.int("added") ?: files.sumOf { it.added }).coerceAtLeast(0),
            deleted = (obj.int("deleted") ?: files.sumOf { it.deleted }).coerceAtLeast(0),
            files = files,
        )
    }

    private fun fileOf(element: JsonElement): ChangedFile? {
        val obj = element as? JsonObject ?: return null
        // path 与 display 都必须是非空字符串：display 为空时退回 path（服务端理应都给，
        // 但 UI 的点击需要 path，二者缺一不可）。
        val path = obj.string("path")?.takeIf { it.isNotEmpty() } ?: return null
        val display = obj.string("display")?.takeIf { it.isNotEmpty() } ?: path
        return ChangedFile(
            path = path,
            display = display,
            added = (obj.int("added") ?: 0).coerceAtLeast(0),
            deleted = (obj.int("deleted") ?: 0).coerceAtLeast(0),
            binary = obj.boolean("binary") == true,
            oversized = obj.boolean("oversized") == true,
        )
    }

    /**
     * 解析 diff。
     *
     * `kind` 是判别键：`text` / `binary` / `oversized`。未知 `kind` 返回 null
     * （宁可显示「这个文件没有可预览的差异」，也不要把它误当文本渲染）。
     */
    fun diff(element: JsonElement): ChangesDiff? {
        val obj = element as? JsonObject ?: return null
        val path = obj.string("path")?.takeIf { it.isNotEmpty() } ?: return null
        val display = obj.string("display")?.takeIf { it.isNotEmpty() } ?: path
        return when (obj.string("kind")) {
            "text" -> ChangesDiff.Text(
                path = path,
                display = display,
                beforeExists = obj.boolean("before") == true,
                afterExists = obj.boolean("after") == true,
                hunks = (obj["hunks"] as? JsonArray).orEmpty().mapNotNull { hunkOf(it) },
                coarse = obj.boolean("coarse") == true,
            )

            "binary" -> ChangesDiff.Binary(path, display)
            "oversized" -> ChangesDiff.Oversized(path, display)
            else -> null
        }
    }

    private fun hunkOf(element: JsonElement): DiffHunk? {
        val obj = element as? JsonObject ?: return null
        // 四个数字都必须存在且非负（与 `dsh-client-ui-deliverables/lib/client.js:52-61`
        // 的 isHunk 一致）；行数组允许为空（纯删除/纯插入可能只有 0 行上下文）。
        val oldStart = obj.int("oldStart") ?: return null
        val oldLines = obj.int("oldLines") ?: return null
        val newStart = obj.int("newStart") ?: return null
        val newLines = obj.int("newLines") ?: return null
        if (oldStart < 0 || oldLines < 0 || newStart < 0 || newLines < 0) return null
        val lines = (obj["lines"] as? JsonArray).orEmpty().mapNotNull { line ->
            (line as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        // 空串按「上下文里的空行」处理（补一个前导空格）而不是丢掉：丢行会让渲染出来的
        // 行数少于 hunk 头声明的 oldLines/newLines，用户看到的行号就对不上了（R005 P2）。
        // 仍然丢弃以别的前缀开头的行——服务端保证只有 `+`/`-`/空格三种。
        val clean = lines
            .map { if (it.isEmpty()) " " else it }
            .filter { it[0] == '+' || it[0] == '-' || it[0] == ' ' }
        return DiffHunk(oldStart, oldLines, newStart, newLines, clean)
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
