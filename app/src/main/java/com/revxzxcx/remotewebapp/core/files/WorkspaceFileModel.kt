package com.revxzxcx.remotewebapp.core.files

/**
 * `workspaceFiles/read` 的返回。
 *
 * 线上形状（真机探针实测，T016）：
 * ```
 * {"absolutePath":"F:\\bot\\remote-web-app\\settings.gradle.kts",
 *  "version":"2296164758:1688849860631842:539:1791865110422149900:1791865110430414700",
 *  "bytes":539,"offset":1,"text":"pluginManagement {\n...","lines":4,"eof":false}
 * ```
 * `bytes` 只在部分情况下出现；[version] 是不透明字符串（**不是** 数字，别试着解析），
 * 它只用于「文件变了吗」的比对。
 */
data class WorkspaceFileContent(
    val absolutePath: String,
    val version: String,
    val offset: Int,
    val text: String,
    /** 本页返回的行数。 */
    val lines: Int,
    /** 已经读到文件末尾（没有下一页）。 */
    val eof: Boolean,
    val bytes: Long? = null,
)

/** `workspaceFiles/stat` 的返回。`bytes` 可选。 */
data class WorkspaceFileStat(
    val absolutePath: String,
    val version: String,
    val bytes: Long? = null,
)

/** `workspaceFiles/list` 的一个目录项。 */
data class WorkspaceFileEntry(
    val name: String,
    /** `file` / `directory` / `other` —— 服务端给的枚举，未知值也原样保留。 */
    val type: String,
    val size: Long? = null,
) {
    val isDirectory: Boolean get() = type == "directory"
    val isFile: Boolean get() = type == "file"
}

/** `workspaceFiles/list` 的返回。 */
data class WorkspaceFileListing(
    val path: String,
    val entries: List<WorkspaceFileEntry>,
    /** 目录项超过 `maxEntries`（默认 2000）被截断。 */
    val truncated: Boolean,
)

/**
 * 一次「文件内容」读取的结果，把二进制与文本统一起来。
 *
 * 判定走 `workspaceFiles/readBytes` 的响应 `content-type`：实测成功的二进制读取返回
 * `multipart/form-data`，而**失败依然是普通 JSON 信封**（`workspace-file/not-found` 等），
 * 所以这里只需要在 multipart 的上层再剥一层边界。
 */
sealed interface FileBytes {
    /** 服务端回的是 `multipart/form-data`，[data] 是第一个 part 的裸字节。 */
    data class Multipart(val contentType: String, val data: ByteArray) : FileBytes {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Multipart && contentType == other.contentType && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * contentType.hashCode() + data.contentHashCode()
    }
}

/**
 * `workspaceFiles` 的**错误码**（`dsh-api-workspace-files/lib/types/index.js`）。
 *
 * 全部是 `ok:false` 信封里的 `error.code`。UI 要按码给不同文案——把它们混成
 * 「读取失败」会让「文件太大」和「根本没有这个文件」看起来一样。
 */
object WorkspaceFileError {
    const val NOT_FOUND = "workspace-file/not-found"
    const val NOT_REGULAR_FILE = "workspace-file/not-regular-file"
    const val NOT_DIRECTORY = "workspace-file/not-directory"
    const val OUTSIDE_WORKSPACE = "workspace-file/outside-workspace"
    const val NOT_TEXT = "workspace-file/not-text"
    const val TOO_LARGE = "workspace-file/too-large"
    const val UNSUPPORTED_ADDRESS = "workspace-file/unsupported-address"
    const val UNKNOWN_WORKSPACE = "workspace-file/unknown-workspace"

    /** 给用户看的中文说明；未知码原样返回。 */
    fun describe(code: String): String = when (code) {
        NOT_FOUND -> "文件不存在（可能在桌面端被删除或重命名）"
        NOT_REGULAR_FILE -> "这不是普通文件"
        NOT_DIRECTORY -> "这不是目录"
        OUTSIDE_WORKSPACE -> "文件不在工作区目录内"
        NOT_TEXT -> "这是二进制文件，不能按文本打开"
        TOO_LARGE -> "文件超过服务端上限（32 MiB）"
        UNSUPPORTED_ADDRESS -> "不支持的文件地址"
        UNKNOWN_WORKSPACE -> "找不到这个会话对应的工作区"
        else -> code
    }
}
