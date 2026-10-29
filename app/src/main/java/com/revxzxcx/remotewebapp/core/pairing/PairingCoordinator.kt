package com.revxzxcx.remotewebapp.core.pairing

import com.revxzxcx.remotewebapp.core.credentials.CredentialStore
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.net.HostPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「用户给的配对链接」变成「落盘的设备凭证」。
 *
 * 支持两条路径（见 [PairLink]）：
 * 1. `/pair-accept?pair=<token>` → 调 `POST /api/pair/accept` 换 `deviceId`（主路径）；
 * 2. `/pair-app?device=<id>` → 桌面端已经配好，直接把 id 记下来。
 *
 * 为什么不能反过来（App 发起配对）：`POST /api/pair/issue` 是 **loopback-only**
 * （`src/routes.ts:688` `loopbackFence(req)`），非本机请求直接 403。所以二维码只能在桌面端
 * 生成、App 只能扫或粘贴（需求文档 Q7）。
 */
class PairingCoordinator(
    private val store: CredentialStore,
    private val api: PairingApi,
) {

    /** 读取已保存的凭证；没有则 `null`。 */
    suspend fun currentCredential(): DeviceCredential? = store.load()

    /**
     * 用一段文本完成配对（二维码内容或粘贴的链接/token）。
     *
     * @param rawText 扫码结果或剪贴板内容。
     * @param fallbackBaseUrl 只粘贴了裸 token 时，用这个地址补上 origin（通常是上次用过的地址）。
     * @param label 展示名，一般是设备型号。
     */
    suspend fun pairWithText(
        rawText: String,
        fallbackBaseUrl: String? = null,
        label: String = "",
    ): DeviceCredential {
        val link = when (val parsed = PairLinkParser.parse(rawText)) {
            is PairParse.Ok -> parsed.link
            is PairParse.Failed -> throw PairingException(
                PairingException.CODE_BAD_RESPONSE,
                parsed.reason,
            )
        }
        return pairWithLink(link, fallbackBaseUrl, label)
    }

    /**
     * 用解析好的链接配对。
     *
     * ## 本方法**不落盘**（B005）
     *
     * 早先本方法末尾有一行 `store.save(credential)`，而 `save()` 内的 `withUpsert`
     * 默认 `makeActive = true`。结果是：**配对阶段就把 active 换成了新那台，而连接根本没动**。
     * 调用方随后无论走 `onPaired`（首次配对，本来就该激活）还是走 `addHost`
     * （添加远程，明确要求不激活），读到的簿都已经是「新主机已激活」的形状——
     * `addHost` 的 `makeActive = false` 因此形同虚设；而 `installConnection` 尚未来得及
     * 重建连接，UI 就在这一瞬间看到了「簿是新的、连接是旧的」（与 B004 同源的半新半旧）。
     *
     * 现在写入凭证簿的唯一入口是 `AppContainer.onPaired` / `AppContainer.addHost`，
     * 由它们决定要不要激活、要不要判重复；本方法只负责「把链接换成一条可用的凭证」。
     */
    suspend fun pairWithLink(
        link: PairLink,
        fallbackBaseUrl: String? = null,
        label: String = "",
    ): DeviceCredential {
        // ⚠️ 顺序很重要：地址规范化 + 明文准入校验必须在**任何网络调用之前**完成。
        //
        // 配对 token 是一次性凭据；`AndroidManifest.xml` 的 `usesCleartextTraffic="true"`
        // 已放开平台层限制，HostPolicy 是唯一的代码级闸门。如果先 `api.accept(...)` 再校验，
        // 扫到一个 `http://<非私有主机>/pair-accept?pair=…` 链接时 token 会**先被明文发出去**，
        // 之后才报「明文只允许局域网」——那时 token 已经泄露了。
        val baseUrl = when (link) {
            is PairLink.Accept -> link.baseUrl
            is PairLink.Device -> link.baseUrl
            is PairLink.BareToken -> fallbackBaseUrl?.let(PairLinkParser::normalizeBaseUrl)
                ?: throw PairingException(
                    PairingException.CODE_BAD_RESPONSE,
                    "只拿到 token，还缺服务器地址：请粘贴完整配对链接",
                )
        }
        HostPolicy.rejectionReason(baseUrl)?.let {
            throw PairingException(PairingException.CODE_BAD_RESPONSE, it)
        }

        val deviceId = when (link) {
            is PairLink.Accept -> api.accept(baseUrl, link.token)
            is PairLink.Device -> link.deviceId
            is PairLink.BareToken -> api.accept(baseUrl, link.token)
        }

        val credential = DeviceCredential(
            baseUrl = baseUrl,
            deviceId = deviceId,
            pairedAt = System.currentTimeMillis(),
            label = label,
        )
        return credential
    }

    /** 退出配对（清本地凭证）。 */
    suspend fun unpair() = store.clear()

    /**
     * 用「地址 + 设备 id」手工登记（调试 / 恢复用）。
     *
     * 不校验服务端是否认识这个 id —— 校验发生在第一次真实调用时（403 `unpaired`），
     * 那时 UI 会提示重新配对。
     */
    suspend fun pairManually(baseUrl: String, deviceId: String, label: String = ""): DeviceCredential =
        withContext(Dispatchers.IO) {
            val normalized = PairLinkParser.normalizeBaseUrl(baseUrl)
                ?: throw PairingException(PairingException.CODE_BAD_RESPONSE, "地址格式不对")
            HostPolicy.rejectionReason(normalized)?.let {
                throw PairingException(PairingException.CODE_BAD_RESPONSE, it)
            }
            if (deviceId.isBlank()) {
                throw PairingException(PairingException.CODE_BAD_RESPONSE, "设备 id 不能为空")
            }
            val credential = DeviceCredential(
                baseUrl = normalized,
                deviceId = deviceId.trim(),
                pairedAt = System.currentTimeMillis(),
                label = label,
            )
            credential
        }
}
