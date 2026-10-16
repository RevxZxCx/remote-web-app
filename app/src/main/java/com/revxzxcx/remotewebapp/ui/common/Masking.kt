package com.revxzxcx.remotewebapp.ui.common

/**
 * 敏感串打码（R028 ① 从 `ui/debug` 搬来）。
 *
 * 为什么搬：诊断页与顶栏连接面板都要显示 `deviceId`，两处各自实现一次的话，
 * 迟早有一处忘了打码——而截图外传是求助时的常态。
 */

/**
 * 设备 id 打码：只留前 6 位与后 4 位。
 *
 * 为什么不显示完整值：`deviceId` 是**完全控制权凭证**（`DeviceCredential` 的 KDoc
 * 记载了插件源码里的原文），而截图/录屏常被拿去求助——打码后仍然足够区分「是不是同一个
 * 设备」，但截出去不等于把钥匙交出去。
 */
internal fun maskDeviceId(deviceId: String): String = when {
    deviceId.isEmpty() -> "（无）"
    deviceId.length <= 12 -> deviceId.first() + "…" + deviceId.last()
    else -> deviceId.take(6) + "…" + deviceId.takeLast(4)
}

/**
 * `$events` 的 clientId 打码。
 *
 * 它不是凭证（只是本次连接的句柄），但同样没有完整显示的价值：截图里露出一串 uuid
 * 只会让人以为漏了什么重要信息。留头尾足够判断「是不是同一次连接」。
 */
internal fun maskClientId(clientId: String): String = when {
    clientId.isEmpty() -> "（无）"
    clientId.length <= 12 -> clientId.first() + "…" + clientId.last()
    else -> clientId.take(8) + "…" + clientId.takeLast(4)
}
