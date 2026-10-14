package com.revxzxcx.remotewebapp.core.connection

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.protocol.RemoteRoutesClient
import com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.session.FileUploadRepository
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * 一个已配对连接的运行时句柄：把「凭证 + 两个 HTTP 客户端 + 协议层」绑在一起。
 *
 * 上层（Repository / ViewModel）只依赖本对象，换连接就是换一个 [DshConnection] 实例
 * （例如切换 LAN ↔ 隧道地址时重建），旧实例 [close] 掉即可。
 *
 * 两个 OkHttp 客户端必须分开：unary 要有读超时，mux 是长连接必须无读超时
 * （见 `core/net/HttpClients.kt` 的说明）。
 */
class DshConnection(
    val credential: DeviceCredential,
    unaryHttp: OkHttpClient,
    streamHttp: OkHttpClient,
    json: Json = DshJson,
) {

    val baseUrl: String get() = credential.baseUrl

    val deviceId: String get() = credential.deviceId

    /** unary 调用（`session/list`、`workspace/create`…）。 */
    val typert: TypertClient = TypertClient(credential.baseUrl, credential.deviceId, unaryHttp, json)

    /** 全部流式端点共用的一条 WebSocket。 */
    val mux: RemoteStreamMux = RemoteStreamMux(credential.baseUrl, credential.deviceId, streamHttp, json)

    /**
     * 插件注册的**普通鉴权 HTTP 路由**（`changes.summary` / `changes.diff` / `changes.open`）。
     *
     * 这些不是 Typert RPC，回的是裸 JSON 或纯文本，必须与 [typert] 分开解析。
     */
    val routes: RemoteRoutesClient = RemoteRoutesClient(credential.baseUrl, credential.deviceId, unaryHttp, json)

    /**
     * 附件读取（`session/attachment`）。
     *
     * 之所以挂在连接上而不是每次 new：会话里图片可能很多，共享同一个客户端才能共用
     * OkHttp 的连接池与线程池（见 [typert] 同理）。
     */
    val attachments: SessionAttachmentRepository =
        SessionAttachmentRepository(typert)

    /** 文件上传（`fileUploads/upload`）——发文件前必须先换回执。 */
    val uploads: FileUploadRepository = FileUploadRepository(typert)

    /** 关闭 mux（unary 是无状态请求，无需关闭）。 */
    suspend fun close() {
        mux.close()
    }
}
