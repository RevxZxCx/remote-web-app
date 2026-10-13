package com.revxzxcx.remotewebapp.core.net

import java.net.URI

/**
 * 地址准入策略（明文流量只在局域网放行）。
 *
 * 背景：桌面端插件既可能给出 `https://<id>.dsh-market.com`（Cloudflare 隧道），也可能给出
 * `http://192.168.x.x:19387`（局域网直连，`lanBaseUrlFor(address)`）。Android 从 targetSdk 28
 * 起默认**禁止明文 HTTP**，而 `network-security-config` 的 `<domain>` 无法表达任意私有网段，
 * 所以 manifest 里放开 `usesCleartextTraffic`，再由本对象在**代码层**收紧：
 *
 * - `https://` 一律放行；
 * - `http://` 只放行**私有/回环地址字面量**与 `.local/.lan/.internal/.home.arpa` 主机名。
 *
 * 这正好对应需求文档的安全约定：「仅 HTTPS（隧道）或用户显式允许的明文局域网地址」。
 * 判定规则与插件侧 `isPrivateOrLocalHostname`（`src/routes.ts:120-145`）保持一致，避免
 * App 放行了一个桌面端不认的地址。
 */
object HostPolicy {

    /** 是否允许把 [baseUrl] 当作服务端地址使用。 */
    fun isAllowed(baseUrl: String): Boolean = rejectionReason(baseUrl) == null

    /** 不允许时返回给用户看的原因；允许时返回 `null`。 */
    fun rejectionReason(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?: return "地址格式不对"
        val scheme = uri.scheme?.lowercase() ?: return "地址缺少 http/https 前缀"
        val host = uri.host?.lowercase() ?: return "地址缺少主机名"

        return when (scheme) {
            "https" -> null
            "http" -> if (isPrivateOrLocalHostname(host)) {
                null
            } else {
                "明文 HTTP 只允许局域网地址（当前主机 $host 不是私有地址），请改用 HTTPS 隧道地址"
            }
            else -> "只支持 http/https，收到的是 $scheme"
        }
    }

    /**
     * 私有/本地主机名判定。
     *
     * 与 `src/routes.ts:120-145` 的 `isPrivateOrLocalHostname` 对齐：localhost、`.local`、
     * `.lan`、`.internal`、`.home.arpa`、`::1`、`127.x`、`fc*`/`fd*`/`fe80*`、`10.x`、
     * `169.254.x`、`172.16-31.x`、`192.168.x`。
     */
    fun isPrivateOrLocalHostname(rawHost: String): Boolean {
        // IPv6 字面量在 URI 里带方括号，去掉。
        val host = rawHost.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (host.isEmpty()) return false

        if (host == "localhost" || host == "::1") return true
        if (host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) {
            return true
        }

        // IPv6：ULA fc00::/7 与链路本地 fe80::/10。
        if (host.contains(':')) {
            return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        }

        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]

        return when {
            a == 127 -> true                     // 回环
            a == 10 -> true                      // 10/8
            a == 192 && b == 168 -> true         // 192.168/16
            a == 172 && b in 16..31 -> true      // 172.16/12
            a == 169 && b == 254 -> true         // 链路本地
            else -> false
        }
    }
}
