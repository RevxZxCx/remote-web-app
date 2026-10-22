package com.revxzxcx.remotewebapp.core.files

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * `workspaceFiles/changes` 的一帧。
 *
 * 服务端是 `WorkspaceFileWatchFrame = {kind:'ready'} | {kind:'change', change:{…}}`。
 * `ready` 只表示「监视已建立」，不携带数据，解析时**故意丢掉**：调用方关心的是
 * 「目录内容可能变了」，而不是握手本身。未知帧（服务端将来加字段）同样返回 null，
 * 宁可少一次刷新也不要因为不认识一帧就把监听流打断。
 */
data class WorkspaceFileChange(
    /** 变化目标的绝对路径；服务端给的是 `WorkspaceFileChange.absolutePath`。 */
    val absolutePath: String,
    /** 变化后的版本串；目标被删除时为 null。 */
    val version: String?,
) {
    /** 目标是否已不存在（服务端给的是 `{absent:true}` 而不是 version）。 */
    val absent: Boolean get() = version == null

    companion object {
        /** 解析一帧；`ready` 与未知帧返回 null。 */
        fun parse(raw: JsonElement): WorkspaceFileChange? {
            val obj = raw as? JsonObject ?: return null
            if ((obj["kind"] as? JsonPrimitive)?.content != "change") return null
            val change = obj["change"] as? JsonObject ?: return null
            val path = (change["absolutePath"] as? JsonPrimitive)?.content ?: return null
            // `absent:true` 时服务端不给 version；`version` 存在就取，缺了按删除处理。
            val version = (change["version"] as? JsonPrimitive)?.content
            return WorkspaceFileChange(absolutePath = path, version = version)
        }
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
