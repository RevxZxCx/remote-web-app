package com.revxzxcx.remotewebapp.core.credentials

import kotlinx.serialization.Serializable

/**
 * 一条已配对的设备凭证。
 *
 * 这是 App 的**唯一**身份：`deviceId` 作为 `x-dsh-remote-device` 头（HTTP）或 `?device=`
 * 查询参数（WebSocket 握手）发给插件的 `/remote` 通道。
 *
 * ⚠️ 安全等级：`src/remote-api.ts:1-27` 的注释明确写着 —— 配对设备是一个
 * **FULL-CONTROL credential**，插件会用它把请求以**进程自身的浏览器鉴权凭证**重新签发到
 * `127.0.0.1`。也就是说它等价于「完全控制权」，不能明文落盘（决策 D16）。
 */
@Serializable
data class DeviceCredential(
    /** 配对时用的基址，形如 `http://192.168.1.5:19387` 或 `https://xxx.dsh-market.com`。 */
    val baseUrl: String,
    /** 配对得到的设备 id。 */
    val deviceId: String,
    /** 配对成功的时间戳（毫秒）。 */
    val pairedAt: Long,
    /** 展示用的名称，默认取设备型号；仅用于 UI。 */
    val label: String = "",
) {
    /** 是不是一个能用的凭证（防住空串和半截数据）。 */
    val isUsable: Boolean
        get() = (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) &&
            deviceId.isNotBlank()
}
