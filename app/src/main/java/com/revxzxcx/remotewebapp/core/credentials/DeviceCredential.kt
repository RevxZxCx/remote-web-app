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
    /**
     * 这台主机在「凭证簿」里的稳定身份（R054）。
     *
     * 独立随机 id，**不随 [label] / [baseUrl] 变化**，见 [CredentialBook] 的 KDoc。
     * 默认空串是为了让旧版本（v1 单凭证文件）解出来的对象也能通过反序列化——
     * 那一份数据里根本没有这个字段，迁移时由 [CredentialBook.ofSingle] 补上。
     */
    val hostId: String = "",
) {
    /** 是不是一个能用的凭证（防住空串和半截数据）。 */
    val isUsable: Boolean
        get() = (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) &&
            deviceId.isNotBlank()

    /**
     * 列表里显示的名字：自定义名优先，否则用地址（去掉协议与默认端口）。
     *
     * 为什么回退到地址而不是 [label]：`label` 是配对时桌面端报来的设备型号（例如
     * `PKG110`），同一个用户的两台机器可能同名；而对多主机来说「哪台机器」比「什么型号」
     * 更有辨识度。用户改过名就尊重他的命名（[CredentialBook.withRenamed] 正常路径）。
     */
    val displayName: String
        get() = label.trim().ifBlank { endpointLabel }

    /**
     * 地址的短形态：去掉 `http://` / `https://` 与末尾斜杠，保留端口。
     *
     * 端口要保留：同一台机器可能用 LAN 地址（`:19387`）和隧道域名两种方式各配一次，
     * 抹掉端口会让两行看起来完全一样。
     */
    val endpointLabel: String
        get() = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
}
