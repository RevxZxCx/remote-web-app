package com.revxzxcx.remotewebapp.core.changes

/**
 * 一轮改动的**摘要**，来自 `GET /remote/api/changes.summary?sessionId=&seq=`。
 *
 * 线上形状（真机探针实测 T016，seq=6237）：
 * ```
 * {"turn":6,"total":19,"added":2314,"deleted":110,
 *  "files":[{"path":"_dexcheck.js","display":"_dexcheck.js","added":55,"deleted":0}, …]}
 * ```
 * 注意 [total] 是**完整文件数**（截断前），[files] 最多 `maxFiles`（默认 500）条。
 */
data class ChangesSummary(
    val turn: Long,
    /** 截断前的文件总数。 */
    val total: Int,
    val added: Int,
    val deleted: Int,
    val files: List<ChangedFile>,
)

/**
 * 一轮改动的**读取结果**（三态，R005 P1-2 / P2-8）。
 *
 * 为什么不是 `ChangesSummary?`：`null` 会把三种完全不同的情况压成一种，
 * 于是「桌面端重启后记录没了」（确定性、不该重试）和「网络抖了一下」（临时性、
 * 必须能重试）在界面上长得一模一样，而且失败一旦写进缓存就再没有重试机会。
 */
sealed interface ChangesEntry {

    /** 拿到了摘要（`total == 0` 表示这一轮确实没改文件）。 */
    data class Loaded(val summary: ChangesSummary) : ChangesEntry

    /** 服务端 404：摘要是 Host 进程内存里的，重启之后旧轮次就真没了。 */
    data object Unavailable : ChangesEntry

    /** 传输/协议/解析失败——**临时**的，卡片要给出重试入口。 */
    data class Failed(val reason: String) : ChangesEntry
}

/** 摘要里的一行。 */
data class ChangedFile(
    /** 相对工作区根的路径（用于后续 `changes.diff` / `workspaceFiles` 调用）。 */
    val path: String,
    /** 展示用路径（相对工作区或家目录，已由桌面端裁短）。 */
    val display: String,
    val added: Int,
    val deleted: Int,
    /** 二进制文件：diff 不可用，行数恒为 0。 */
    val binary: Boolean = false,
    /** 超出 `maxFileBytes`（2 MiB）没做行统计。 */
    val oversized: Boolean = false,
)

/**
 * 单文件 diff，来自 `GET /remote/api/changes.diff?sessionId=&seq=&index=`。
 *
 * 抽成 sealed：服务端三种形态（实测确认 text，另两种在会话太长 `diffTimeoutMs` 超时时出现）。
 */
sealed interface ChangesDiff {

    /** 服务端给了可渲染的文本 diff。 */
    data class Text(
        val path: String,
        val display: String,
        /** 该文件在本轮之前是否已存在（false = 新建）。 */
        val beforeExists: Boolean,
        /** 该文件在本轮之后是否存在（false = 被删除）。 */
        val afterExists: Boolean,
        val hunks: List<DiffHunk>,
        /**
         * 行统计因超时降级为「整文件替换」的一整个 hunk。
         *
         * 为 true 时 [hunks] 里只有一条、且 `oldLines` 或 `newLines` 为 0——
         * UI 应提示「文件较大，未做精确行对比」。
         */
        val coarse: Boolean,
    ) : ChangesDiff

    /** 二进制文件，没有 diff。 */
    data class Binary(val path: String, val display: String) : ChangesDiff

    /** 文件超过 2 MiB，跳过 diff。 */
    data class Oversized(val path: String, val display: String) : ChangesDiff
}

/**
 * 一段 hunk。
 *
 * [lines] 每行都以 `+` / `-` / ` `（空格）开头——这是服务端的约定，
 * 与 `dsh-client-ui-deliverables/lib/client.js:52-61` 的 `isHunk` 校验一致。
 */
data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
) {
    /** hunk 标题，形如 `@@ -1,3 +1,5 @@`。 */
    val header: String get() = "@@ -$oldStart,$oldLines +$newStart,$newLines @@"
}

/**
 * 三条限制必须在 UI 里讲清楚（`需求文档.md` §3.7）：
 * 1. 摘要随 Session 存活在**同一个 Host 进程内存**里，桌面端重启后旧轮次返回 404；
 * 2. 不在 git 仓库里时只列文件工具（`write`/`edit`/`str_replace_editor`）的改动，
 *    纯 shell 命令的改动看不到；
 * 3. 单文件超过 `maxFileBytes`（2 MiB）不做 diff。
 */
object ChangesLimitations {
    const val HOST_RESTART = "桌面端重启后，这个轮次的改动记录会丢失（记录只存在内存里）。"
    const val NOT_A_REPO = "不在 git 仓库里的目录，只有文件工具的改动会被记录；纯命令行的改动看不到。"
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val UI_MAX_FILE_NOTE = "超过 2 MiB 的文件不做差异对比。"
}
