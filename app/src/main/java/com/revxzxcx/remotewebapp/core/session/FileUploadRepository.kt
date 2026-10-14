package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `fileUploads/upload` 成功后服务端认下的文件。
 *
 * 线级形状（`.../dsh-api-session-controller/lib/typert.remote-client.js` 的
 * `_deepseek_ai_dsh_client_file_upload_fileUploads_upload_result$schema`）：
 * ```
 * { attachmentId: string, name: string, bytes: number }
 * ```
 */
@Serializable
data class UploadedFile(
    val attachmentId: String = "",
    val name: String = "",
    val bytes: Long = 0L,
)

/**
 * `fileUploads/upload` 的返回值。
 *
 * ```
 * { receiptId: string, file: {attachmentId, name, bytes} }
 * ```
 * [receiptId] 才是 `session/prompt` 的 `{type:'file', receiptId}` 要用的东西——
 * **不是** `attachmentId`（两者是不同的 id，混用会报 `attachment-not-found`）。
 */
@Serializable
data class FileUploadResult(
    val receiptId: String = "",
    val file: UploadedFile = UploadedFile(),
)

/**
 * 文件上传（需求 FR-5 的「文件」）。
 *
 * 为什么文件**不能**像图片那样内联：`fileDescriptor` 的内容块只有一个键 `receiptId`
 * （`dsh-api-session-controller/lib/typert.remote-client.js`），没有 `data` 字段。
 * 所以必须先调本仓库拿到回执，再把回执放进 `session/prompt`。
 *
 * wire 细节（逐字核实）：
 * ```
 * POST {base}/remote/api/fileUploads/upload
 * { "type":"client-request", "rpcId":…, "method":"fileUploads/upload",
 *   "payload":{"args":{
 *     "agentId": "<sessionId>",                 ← 第一个形参，lookup 源
 *     "request": { "data": "<base64>", "name": "<可选>" }
 *   }}}
 * ```
 * `agentId` 这个名字容易误解成「Agent 的 id」，它其实就是**会话 id**
 * （`scope: {context:'agent', wire:'agentId'}`，与 `session/attachment` 的
 * `workspaceFileScopeId` 传 sessionId 是同一套约定）。
 */
class FileUploadRepository(private val client: TypertClient) {

    /**
     * 上传一个文件，返回带 `receiptId` 的结果。
     *
     * @param sessionId 目标会话 id（wire 名 `agentId`）。
     * @param data base64 编码的文件内容。
     * @param name 文件名，可为 null。
     */
    suspend fun upload(sessionId: String, data: String, name: String?): FileUploadResult =
        client.call(
            endpoint = "fileUploads/upload",
            args = buildJsonObject {
                put("agentId", sessionId)
                put(
                    "request",
                    buildJsonObject {
                        put("data", data)
                        if (!name.isNullOrBlank()) put("name", name)
                    },
                )
            },
            serializer = FileUploadResult.serializer(),
        )
}
