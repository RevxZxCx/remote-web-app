package com.revxzxcx.remotewebapp.core.files

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.protocol.objOrNull
import com.revxzxcx.remotewebapp.core.protocol.strOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工作区文件读取（**只读**）。
 *
 * 为什么 `workspaceFileScopeId` 传 sessionId：它是 Typert 的 `lookup`，服务端拿它会话
 * 头部里的 `cwd ?? sandboxPolicy.workspaceRoot` 推导出**工作区根**，然后把这个根当成
 * 路径的基准（实测：会话 cwd 是 `F:\bot`，传 `remote-web-app/settings.gradle.kts` 得到
 * `F:\bot\remote-web-app\settings.gradle.kts`）。传 workspaceId 不会工作。
 *
 * 服务端没有任何写接口（`dsh-api-workspace-files/lib/types/index.js:1-20`），所以本类
 * 只有读方法；「编辑文件」在 App 里不存在，这是设计决定 D15。
 */
class WorkspaceFileRepository(private val connection: DshConnection) {

    /**
     * 读一段文本。
     *
     * ⚠️ `range` 是**必需参数**（不是可选的）：漏掉会得到
     * `gateway/arguments-invalid: missing "range"`。传空对象 `{}` 等同于默认值
     * （`offset = 1`、`limit = 5000`）。
     *
     * ⚠️ 服务端会**硬拒**越界值（实测 `offset: 0` → `gateway/bad-request:
     * offset must be a safe integer of at least 1`；`limit: 99999` →
     * `limit must be at most 5000`），所以这里先 coerce 再发，
     * 免得调用方一个 off-by-one 就把整个文件页变成错误页。
     */
    suspend fun read(
        sessionId: String,
        path: String,
        offset: Int? = null,
        limit: Int? = null,
    ): WorkspaceFileContent {
        val range = buildJsonObject {
            offset?.let { put("offset", it.coerceAtLeast(MIN_OFFSET)) }
            limit?.let { put("limit", it.coerceIn(1, MAX_LIMIT)) }
        }
        val value = connection.typert.callRaw(
            DshEndpoints.WORKSPACE_FILES_READ,
            buildJsonObject {
                put(DshEndpoints.ARG_FILE_SCOPE, sessionId)
                put(DshEndpoints.ARG_PATH, path)
                put(DshEndpoints.ARG_RANGE, range)
            },
        )
        return parseContent(value as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** 取元信息（版本、字节数）。 */
    suspend fun stat(sessionId: String, path: String): WorkspaceFileStat {
        val value = connection.typert.callRaw(
            DshEndpoints.WORKSPACE_FILES_STAT,
            buildJsonObject {
                put(DshEndpoints.ARG_FILE_SCOPE, sessionId)
                put(DshEndpoints.ARG_PATH, path)
            },
        )
        val obj = value as? JsonObject ?: JsonObject(emptyMap())
        return WorkspaceFileStat(
            absolutePath = obj.strOrNull("absolutePath").orEmpty(),
            version = obj.strOrNull("version").orEmpty(),
            bytes = obj.longOrNull("bytes"),
        )
    }

    /** 列一个目录。 */
    suspend fun list(sessionId: String, path: String): WorkspaceFileListing {
        val value = connection.typert.callRaw(
            DshEndpoints.WORKSPACE_FILES_LIST,
            buildJsonObject {
                put(DshEndpoints.ARG_FILE_SCOPE, sessionId)
                put(DshEndpoints.ARG_PATH, path)
            },
        )
        val obj = value as? JsonObject ?: JsonObject(emptyMap())
        val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.strOrNull("name") ?: return@mapNotNull null
            WorkspaceFileEntry(
                name = name,
                type = entry.strOrNull("type").orEmpty(),
                size = entry.longOrNull("size"),
            )
        }
        return WorkspaceFileListing(
            path = obj.strOrNull("path").orEmpty(),
            entries = entries,
            truncated = entryBoolean(obj, "truncated"),
        )
    }

    /**
     * 读整块字节（图片、PDF）。
     *
     * ⚠️ `options` 同样**必需**（漏掉报 `missing "options"`）；成功响应是
     * `multipart/form-data`，不是 JSON 信封。
     *
     * **解析不出 part 就抛异常，绝不退回整包字节**（R005 P3/P1）：响应体里除了 part 内容
     * 还有 boundary、part 头与尾部那段 JSON 元数据（实测 PNG 的魔数在偏移 143），
     * 把它当文件内容交给 `BitmapFactory` 只会得到一句「图片解码失败」，真正的原因
     * （boundary 缺失、body 为空、服务端换了封装）全被掩盖。
     *
     * @throws com.revxzxcx.remotewebapp.core.protocol.TypertException 失败时（例如
     *   `workspace-file/too-large`、`workspace-file/not-found`），或响应无法解析。
     */
    suspend fun readBytes(sessionId: String, path: String): FileBytes {
        val response = connection.typert.callBinary(
            DshEndpoints.WORKSPACE_FILES_READ_BYTES,
            buildJsonObject {
                put(DshEndpoints.ARG_FILE_SCOPE, sessionId)
                put(DshEndpoints.ARG_PATH, path)
                put(DshEndpoints.ARG_OPTIONS, JsonObject(emptyMap()))
            },
        )
        val parts = MultipartParser.parse(response.contentType, response.bytes)
        // 单 part 是实测的常态；多 part 时取第一个（服务端目前只用 bytes-0）。
        val data = parts.firstOrNull()?.data
            ?: throw TypertException(
                TypertException.CODE_BAD_ENVELOPE,
                "文件响应无法解析（content-type=${response.contentType.take(120)}，" +
                    "共 ${response.bytes.size} 字节）",
            )
        return FileBytes.Multipart(response.contentType, data)
    }

    // ------------------------------------------------------------------ 解析

    private fun parseContent(obj: JsonObject): WorkspaceFileContent = WorkspaceFileContent(
        absolutePath = obj.strOrNull("absolutePath").orEmpty(),
        version = obj.strOrNull("version").orEmpty(),
        offset = obj.longOrNull("offset")?.toInt() ?: 1,
        text = obj.strOrNull("text").orEmpty(),
        lines = obj.longOrNull("lines")?.toInt() ?: 0,
        eof = entryBoolean(obj, "eof"),
        bytes = obj.longOrNull("bytes"),
    )

    private fun entryBoolean(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    companion object {
        /** 服务端要求 `offset >= 1`（1-based 行号）。 */
        const val MIN_OFFSET: Int = 1

        /** 服务端 `maxLines` 默认值也是上限：`limit must be at most 5000`。 */
        const val MAX_LIMIT: Int = 5000
    }
}

/** 取一个可空 Long（`JsonPrimitive` 的数字形式）。 */
private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

/** `JsonArray?` 的便捷空值展开。 */
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
