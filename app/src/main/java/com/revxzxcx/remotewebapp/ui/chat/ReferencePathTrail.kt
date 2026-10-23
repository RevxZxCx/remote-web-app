package com.revxzxcx.remotewebapp.ui.chat

/**
 * 引用选择器的「当前在哪个目录」。
 *
 * 服务端 `fileReferences/list` 的 `query` 是**路径子串 + 前缀补全**的混合语义，实测
 * （`F:\bot` 工作区，2026-10）：
 *  - `query = "todo-nudge"` → 18 条，含 `todo-nudge` 自身与它的所有子孙；
 *  - `query = "todo-nudge/"`（带尾斜杠）→ 8 条，**只有直接子项**，不含 `todo-nudge` 自身；
 *  - `query = "todo-nudge/scripts/"` → 5 条，全是该目录下的文件。
 *
 * 也就是说「进入下一层」不需要新接口，只要把 [query] 换成 `dir + "/"`。用户 m27926 第 2 条
 * 报的「点文件夹会直接选中，无法选择文件夹里的文件」，根因是把目录行当成了可选项，而没有
 * 用它去下钻。
 *
 * 这个类把这些路径运算抽出来，好在纯 JVM 单测里钉住——目录前缀的拼接规则一旦写错，
 * 表现是「点进目录后列表空了」，在 UI 测试里很难看出是哪一层错。
 */
internal object ReferencePathTrail {
    /** 进入 [directory] 之后应该用的 query：`a/b` → `a/b/`，根目录 → 空串。 */
    fun childQuery(directory: String): String {
        val trimmed = directory.trim().trimEnd('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /** 从 `a/b/` 回到上一级：`a/b/` → `a/`；`a/` → `""`。已经在根上就停在根。 */
    fun parentQuery(query: String): String {
        val trimmed = query.trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) "" else trimmed.substring(0, cut + 1)
    }

    /**
     * 面包屑：把 `a/b/` 拆成 `[(a, "a/"), (b, "a/b/")]`，第一项代表根目录由调用方单独画。
     *
     * 末段保留原始大小写与空格——它是用户能看见的唯一「我点了哪个目录」的凭据。
     */
    fun crumbs(query: String): List<Crumb> {
        val trimmed = query.trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        val out = ArrayList<Crumb>(parts.size)
        val acc = StringBuilder()
        parts.forEach { part ->
            acc.append(part).append('/')
            out += Crumb(label = part, query = acc.toString())
        }
        return out
    }

    /**
     * 列表里这一行该怎么响应点击。
     *
     * - 目录 → [RowAction.Descend]（永远是进入，不是选中：桌面端的目录 mention 带尾斜杠，
     *   选中它只是把 `@a/b/` 塞进正文，对模型没有意义，用户真正想要的是里面的文件）；
     * - 文件 → [RowAction.ToggleSelect]；
     * - 已选中的文件 → [RowAction.RemoveSelect]。
     */
    fun rowAction(reference: FileReferenceView): RowAction = when {
        reference.isDirectory -> RowAction.Descend
        reference.selected -> RowAction.RemoveSelect
        else -> RowAction.ToggleSelect
    }

    /** [rowAction] 需要的最小信息，避免把 Compose 层的类型拖进纯 JVM 测试。 */
    data class FileReferenceView(val path: String, val isDirectory: Boolean, val selected: Boolean)

    data class Crumb(val label: String, val query: String)

    sealed interface RowAction {
        data object Descend : RowAction
        data object ToggleSelect : RowAction
        data object RemoveSelect : RowAction
    }
}
