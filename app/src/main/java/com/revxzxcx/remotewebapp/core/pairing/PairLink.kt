package com.revxzxcx.remotewebapp.core.pairing

import java.net.URI

/**
 * 从二维码 / 剪贴板文本里解析出的配对意图。
 *
 * 桌面端 `POST /api/pair/issue` 返回的是
 * `url: "<base>/pair-accept?pair=<token>"`（`routes.ts:712`），二维码内容就是这个 URL，
 * 所以 [Accept] 是主路径：拿到 `token` 后由 App 自己
 * `POST <base>/api/pair/accept {token}` 换取 `deviceId`。
 *
 * [Device] 对应插件 `/pair-app?device=<id>` 形式（`src/remote-methods.ts:61-62` 的启动补丁
 * 就是从这个 URL 读设备 id）——设备已经配对过、只是把 id 交给 App。
 *
 * [BareToken] 是用户只粘贴了 token 的情况：没有 base，需要另外提供地址。
 */
sealed interface PairLink {

    /** 形如 `https://host/pair-accept?pair=<token>`。 */
    data class Accept(val baseUrl: String, val token: String) : PairLink

    /** 形如 `http://host:19387/pair-app?device=<deviceId>`。 */
    data class Device(val baseUrl: String, val deviceId: String) : PairLink

    /** 只有 token，没有地址。 */
    data class BareToken(val token: String) : PairLink
}

/** 解析结果。失败时带一句可展示给用户的原因。 */
sealed interface PairParse {
    data class Ok(val link: PairLink) : PairParse
    data class Failed(val reason: String) : PairParse
}

/**
 * 配对文本解析器。纯函数，无 Android 依赖，可单测。
 *
 * 支持：
 * 1. `<base>/pair-accept?pair=<token>` → [PairLink.Accept]
 * 2. `<base>/pair-app?device=<deviceId>` → [PairLink.Device]
 * 3. 裸 token → [PairLink.BareToken]
 *
 * 明确不支持：`/pair-app?grant=<g>`。那个 grant 是一次性、由**浏览器**在 `/pair-app` 页消费的
 * （`routes.ts:953-993`），App 拿不到 `deviceId`，只能提示用户回到配对页重新生成。
 */
object PairLinkParser {

    /** 二维码里可能带引号或空白，先清一遍。 */
    private fun clean(raw: String): String =
        raw.trim().trim('"', '\'', ' ', '\n', '\r', '\t')

    fun parse(raw: String): PairParse {
        val text = clean(raw)
        if (text.isEmpty()) return PairParse.Failed("内容为空")

        // 裸 token：不含 URL 结构字符。
        if (BARE_TOKEN.matches(text)) return PairParse.Ok(PairLink.BareToken(text))

        val uri = runCatching { URI(text) }.getOrNull()
            ?: return PairParse.Failed("不是合法的链接")

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return PairParse.Failed("只支持 http/https 链接")
        }
        val base = baseUrlOf(uri) ?: return PairParse.Failed("链接缺少主机名")

        val path = uri.path.orEmpty().trimEnd('/')
        val query = parseQuery(uri.rawQuery)

        return when {
            path.endsWith("/pair-accept") -> {
                val token = query["pair"].orEmpty()
                if (token.isEmpty()) PairParse.Failed("链接里没有 pair 参数")
                else PairParse.Ok(PairLink.Accept(base, token))
            }

            path.endsWith("/pair-app") -> {
                if (query.containsKey("grant")) {
                    PairParse.Failed("这是浏览器专用的一次性链接，请回到桌面端重新生成二维码")
                } else {
                    val deviceId = query["device"].orEmpty()
                    if (deviceId.isEmpty()) PairParse.Failed("链接里没有 device 参数")
                    else PairParse.Ok(PairLink.Device(base, deviceId))
                }
            }

            // 允许只给 `<base>?pair=<token>` 或 `<base>#pair=<token>` 这种省略路径的写法。
            query.containsKey("pair") -> {
                val token = query["pair"].orEmpty()
                if (token.isEmpty()) PairParse.Failed("链接里没有 pair 参数")
                else PairParse.Ok(PairLink.Accept(base, token))
            }

            query.containsKey("device") -> {
                val deviceId = query["device"].orEmpty()
                if (deviceId.isEmpty()) PairParse.Failed("链接里没有 device 参数")
                else PairParse.Ok(PairLink.Device(base, deviceId))
            }

            else -> PairParse.Failed("无法识别的配对链接")
        }
    }

    /**
     * 规范化用户手输的地址：`192.168.1.5:19387` → `http://192.168.1.5:19387`。
     *
     * 局域网默认明文 http；用户显式写了 `https://` 就按 https（需求文档 FR-1 的安全约定：
     * 仅 HTTPS 隧道或用户显式允许的明文局域网地址）。
     */
    fun normalizeBaseUrl(raw: String): String? {
        val text = clean(raw)
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            text
        } else {
            "http://$text"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        return baseUrlOf(uri)
    }

    /** `scheme://host[:port]` —— 丢掉路径、查询与片段。 */
    private fun baseUrlOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }

    /** 极简 query 解析（Android 的 `Uri` 也行，但这里保持纯 JVM 可单测）。 */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                if (pair.isEmpty()) return@mapNotNull null
                val idx = pair.indexOf('=')
                val key = if (idx < 0) pair else pair.substring(0, idx)
                val value = if (idx < 0) "" else pair.substring(idx + 1)
                decode(key) to decode(value)
            }
            .toMap()
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** token 由 `clock.randomToken()` 生成（`pairing.ts`），按十六进制放行；同时容忍 base64url 风格。 */
    private val BARE_TOKEN = Regex("^[A-Za-z0-9_-]{8,256}$")
}
