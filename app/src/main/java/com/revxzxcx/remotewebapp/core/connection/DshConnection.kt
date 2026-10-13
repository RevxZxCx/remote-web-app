package com.revxzxcx.remotewebapp.core.connection

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.protocol.RemoteStreamMux
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
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

    /** 关闭 mux（unary 是无状态请求，无需关闭）。 */
    suspend fun close() {
        mux.close()
    }
}
