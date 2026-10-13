package com.revxzxcx.remotewebapp.ui.files

/**
 * 文件查看页的导航目标。
 *
 * 为什么把 [seq]/[index] 和 [path] 放在一起：从**改动卡片**点进来时我们知道是「哪一轮的
 * 哪个文件」，可以直接显示 diff；从**工具调用卡片**或正文里的路径点进来时没有轮次信息，
 * 只能显示当前全文。用一个数据类表示「打开什么」，比给 ViewModel 传五个可空参数清楚。
 *
 * @param path 传给 `workspaceFiles` 各方法的路径。实测四种写法都能用：相对工作区根、
 *   相对会话 cwd、Windows 绝对路径（`F:\bot\…`）、正斜杠形式。所以工具参数里的
 *   `file_path` 可以原样透传，只有 `dsh-resource://` 地址不支持。
 * @param display 标题里显示的名字（改动卡片给的 [com.revxzxcx.remotewebapp.core.changes.ChangedFile.display]
 *   已由桌面端裁短，更适合显示）。
 * @param seq `workspace/changes` 事件的 seq；非 null 时可以去取该轮 diff。
 * @param index 该文件在 `changes.summary.files` 里的下标（`changes.diff` 需要）。
 */
data class FileTarget(
    val path: String,
    val display: String = path,
    val seq: Long? = null,
    val index: Int? = null,
) {
    /** 有没有可取的 diff。 */
    val hasDiff: Boolean get() = seq != null && index != null
}
