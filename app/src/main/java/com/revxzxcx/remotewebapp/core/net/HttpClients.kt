package com.revxzxcx.remotewebapp.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端的两个形态。
 *
 * 为什么是**两个**客户端而不是一个：
 * - unary 调用（`session/list` 等）需要**有**读超时，否则服务端卡住会永久挂起协程。
 * - mux WebSocket 是**长连接**，必须 `readTimeout = 0`（无读超时），否则空闲超过读超时就被
 *   自己掐断；它的存活依赖 `pingInterval` 心跳。
 *
 * 两者共用连接池会互相影响（OkHttp 的连接池按 address 复用，超时是 client 级），所以分开建。
 */
object HttpClients {

    private const val UNARY_READ_TIMEOUT_SEC = 30L
    private const val UNARY_WRITE_TIMEOUT_SEC = 30L
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val PING_INTERVAL_SEC = 30L

    /** unary RPC 用：读超时 30 s。 */
    fun unary(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(UNARY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(UNARY_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * mux WebSocket 用：无读超时 + 30 s ping。
     *
     * 注意 `retryOnConnectionFailure` 在 WS 上不会自动重连——重连由 [RemoteStreamMux] 的代际
     * 机制负责（见 `RemoteStreamMux.waitForSocket`）。
     */
    fun stream(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(UNARY_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
