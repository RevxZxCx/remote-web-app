package com.revxzxcx.remotewebapp.core.net

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * TLS 握手被中间网络掐断（Connection reset）的识别与用户引导。
 *
 * ## 背景（2026-10-17 实测定案，见 docs/dev/测试记录.md T052）
 *
 * 公网入口 `https://zcsb.ltd:38443`（阿里云 frp 中转 + Caddy）所在链路存在**对 TLS
 * ClientHello 的指纹过滤**：实测同一份 openssl ClientHello，323 字节被 RST、用 RFC 7685
 * padding 补到 1500 字节就能拿到 ServerHello；带后量子 key_share 的浏览器指纹（Chrome /
 * node ≥22，约 1.5 KB）直接放行。Android 的系统 TLS 栈（Conscrypt，含 Android 15/16）
 * 不发送后量子组、也没有 padding 扩展，ClientHello 只有约 250 字节，于是**握手第一个
 * 回包之前就被 RST**——表现为「手机浏览器能打开 remote web ui，App 却配对 Connection
 * reset」。
 *
 * 这不是配对逻辑的 bug：同一台模拟器换 Tailscale Funnel 入口（`*.ts.net`，无此过滤）
 * 一次配对成功。App 侧也无法伪装指纹——Conscrypt 不暴露追加扩展 / padding 的接口。
 * 所以 App 层能做的、也是该做的，是把这种失败**说清楚并给出可执行的出路**，而不是把
 * 一句裸的「Connection reset」丢给用户。
 *
 * 本对象是纯 JVM 函数，无 Android 依赖，可单测。
 */
object TlsFingerprintHint {

    /**
     * 判断一次网络失败是否「像」TLS 握手阶段被掐断。
     *
     * 判定沿 cause 链找两类证据：
     * 1. [SSLException] 家族（`SSLHandshakeException` / `SSLProtocolException`）——
     *    握手失败的直接信号；
     * 2. 消息文本里的 `Connection reset` / `Read error` / `connection aborted`——
     *    Conscrypt 在部分版本把握手期 RST 包成裸 [java.net.SocketException]，
     *    消息只有 "Connection reset"（模拟器实测即此形态）。
     *
     * 注意这是**启发式**：只用于决定「要不要追加引导文案」，不用于任何安全决策。
     */
    fun isHandshakeReset(cause: Throwable?): Boolean {
        // 🔴 必须**先走完整条 cause 链排除证书失败，再判定「像不像握手被掐断」**
        // （R028b 审查 P2-8）。早先的写法是在循环里 `if (t is SSLException) return true`，
        // 而 `SSLHandshakeException` 本身就是 `SSLException`——于是还没走到
        // `CertPathValidatorException`（`CertificateException` 子类）就已经返回 true。
        // OkHttp 最典型的证书错误消息是
        //   `Trust anchor for certification path not found.`
        // 里面既没有 "certificate"（只有 "certification"）也没有 "PKIX"，字符串黑名单
        // 兜不住，结果「证书不受信」被说成「链路掐断了你的握手」，把用户引去换入口。
        //
        // 现在分两趟：第一趟只看证书类证据（任何一层出现就立刻 false），第二趟才看
        // 「SSLException / Connection reset」这类握手期信号。
        var t: Throwable? = cause
        var depth = 0
        while (t != null && depth < 8) {
            if (t is CertificateException || t is SSLPeerUnverifiedException) return false
            val msg = t.message.orEmpty()
            if (msg.contains("subject alternative names", ignoreCase = true) ||
                msg.contains("PKIX", ignoreCase = true) ||
                msg.contains("certificate", ignoreCase = true) ||
                // `Trust anchor for certification path not found` 是 OkHttp 的原文，
                // 注意是 "certification" 而不是 "certificate"——早先的匹配漏了它。
                msg.contains("certification path", ignoreCase = true) ||
                msg.contains("trust anchor", ignoreCase = true) ||
                msg.contains("certpath", ignoreCase = true) ||
                msg.contains("hostname", ignoreCase = true)
            ) {
                return false
            }
            t = t.cause
            depth++
        }

        t = cause
        depth = 0
        while (t != null && depth < 8) {
            val msg = t.message.orEmpty()
            if (t is SSLException) return true
            if (msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Read error", ignoreCase = true) ||
                msg.contains("connection aborted", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * 为 [baseUrl] 上的一次握手失败生成追加给用户看的引导；不适用时返回 `null`。
     *
     * 只对 `https://` 生效：明文 http 的 RST 与 TLS 指纹无关（局域网直连被防火墙拒等），
     * 套这套说辞会误导。
     */
    fun hintFor(baseUrl: String, cause: Throwable?): String? {
        if (!isHandshakeReset(cause)) return null
        val scheme = runCatching { java.net.URI(baseUrl.trim()).scheme?.lowercase() }.getOrNull()
        if (scheme != "https") return null
        return "TLS 握手在收到服务端回包前被网络掐断（Connection reset）。" +
            "该公网入口所在链路会丢弃非浏览器指纹的握手包：手机浏览器能打开同一地址、" +
            "App 却连不上，就是这个原因。请改用桌面端的另一个公网入口" +
            "（例如 Tailscale Funnel 的 *.ts.net 地址）重新生成二维码配对；" +
            "或在桌面端调整隧道/入口配置后重试。"
    }

    /**
     * 把引导拼进原有错误消息（[hintFor] 不适用时原样返回 [message]）。
     *
     * 供 [com.revxzxcx.remotewebapp.core.pairing.PairingApi] 的 `CODE_NETWORK` 分支使用：
     * 配对与地址探测共用同一条文案出口。
     */
    fun augment(baseUrl: String, message: String, cause: Throwable?): String {
        val hint = hintFor(baseUrl, cause) ?: return message
        return "$message\n$hint"
    }

    /** [isHandshakeReset] 的便捷重载：直接吃 [IOException]。 */
    fun isHandshakeResetIo(e: IOException): Boolean = isHandshakeReset(e as Throwable?)
}
