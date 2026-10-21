package com.revxzxcx.remotewebapp.ui.files

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * HTML 预览页（用户 m21945 拍板：**要 WebView 渲染**，且 JS 默认开启）。
 *
 * ## 为什么用假域名 + `shouldInterceptRequest`
 * 原型 HTML 常见引用同目录资源（`<img src="R031-app-icon.png">`、`<link href="x.css">`）。
 * `loadDataWithBaseURL(null, …)` 下这些相对路径无处可去，只能 404。而工作区文件没有
 * 可用的 `file://` 或 `content://` 地址——它们只存在于桌面端，要经 `workspaceFiles/readBytes`
 * 一个文件一个文件地取。所以这里给页面一个**不存在的域名** `https://dsh-file.local/`，
 * 把 `<真实目录>/` 拼在它后面当 baseUrl，再在 `shouldInterceptRequest` 里把落在这个域名
 * 下的请求翻译回工作区相对路径去取字节。
 *
 * ## 为什么外链一律拦掉
 * 预览的是「工作区里的一个文件」，不是浏览器：放它出网会带来三个说不清的问题——页面
 * 里嵌了第三方统计脚本、CDN 上的 CSS 把排版带偏、用户以为在看本地文件其实在联网。
 * 拦掉之后页面仍然可用（图片/CSS 都在本地时），被拦的地址会在下方显示一行提示，
 * 让人知道「这里少了东西」而不是默默显示空白。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlPane(
    html: String?,
    path: String,
    loading: Boolean,
    error: String?,
    notice: String?,
    readAsset: (String) -> ByteArray?,
    onRetry: () -> Unit,
) {
    when {
        loading && html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        error != null && html == null -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "这个 HTML 打不开\n$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        html == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "（空文件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            val currentReader by rememberUpdatedState(readAsset)
            var blocked by remember(path) { mutableStateOf<String?>(null) }
            // `shouldInterceptRequest` 跑在 WebView 自己的后台线程上，直接往 Compose
            // 状态里写是在非主线程改快照；而且一个页面被打断十几次就会被写十几次。
            // 统一 post 回主线程，并且只报**第一条**——用户需要知道的是「有东西被拦了」，
            // 不是逐条流水。
            val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
            // 按 path 取键：`blocked` 也是 remember(path) 出来的，不跟着一起换就会写到
            // 上一个文件的状态对象里，提示永远不显示。
            val reportBlocked: (String) -> Unit = remember(path) {
                { url -> mainHandler.post { if (blocked == null) blocked = url } }
            }

            // 释放只走 AndroidView 的 onRelease：一个 WebView 持有渲染进程里的整个引擎，
            // 只从组合里摘掉不会释放，来回看几个 HTML 会一路涨内存。
            //
            // 早先这里还有一个 DisposableEffect 也去 destroy 一遍，两处都会命中同一次
            // 销毁（onDispose 与 onRelease 都在视图离开组合时调用）——重复 destroy 本身
            // 未定义，且那次实现为了拿引用在 factory 里写 Compose 状态（组合期写状态）。
            // 现在只留 onRelease 一处。
            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            // 不允许 JS 打开本地文件/content 提供者：页面里故意写的
                            // `file:///etc/passwd` 之类不该被这个 WebView 读出来。
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            settings.blockNetworkLoads = false
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webViewClient = InterceptingClient(
                                readAsset = { currentReader(it) },
                                onBlocked = reportBlocked,
                            )
                            loadDataWithBaseURL(
                                htmlBaseUrl(path),
                                html,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )
                val note = notice ?: blocked?.let { "已拦截外部资源：$it" }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 只为「本地工作区」服务的 WebViewClient。
 *
 * `shouldInterceptRequest` 可能跑在 WebView 自己的后台线程，所以 [readAsset] 允许阻塞
 * （它内部是 `runBlocking` + 超时）。返回 null 表示「这次没有内容」，WebView 会当作
 * 资源加载失败——比返回一个空 200 更诚实，页面里 `img` 的 `onerror` 才会被触发。
 */
private class InterceptingClient(
    private val readAsset: (String) -> ByteArray?,
    private val onBlocked: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // 内部锚点（`#section`）与同源的页面内跳转允许。
        if (url.startsWith(HTML_ORIGIN)) return false
        onBlocked(url)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith(HTML_ORIGIN)) {
            onBlocked(url)
            return blockedResponse()
        }
        val relative = htmlAssetPath(url.removePrefix(HTML_ORIGIN))
            ?: return blockedResponse()
        val bytes = readAsset(relative) ?: return null
        return WebResourceResponse(mimeOf(relative), null, ByteArrayInputStream(bytes))
    }
}

/**
 * 假域名。用一个**不可能真实存在**的域名，`shouldInterceptRequest` 与
 * `shouldOverrideUrlLoading` 才能靠前缀一次性分清「本地资源」与「外链」。
 */
internal const val HTML_ORIGIN = "https://dsh-file.local/"

/**
 * 拼页面 baseUrl：假域名 + 该 HTML 所在目录。
 *
 * 末尾的 `/` 不能省——省掉会让相对路径按「同级文件」解析而不是「同目录下的文件」。
 */
internal fun htmlBaseUrl(filePath: String): String {
    val directory = filePath.replace('\\', '/').substringBeforeLast('/', "")
    return if (directory.isEmpty()) HTML_ORIGIN else "$HTML_ORIGIN$directory/"
}

/**
 * 把去掉域名后的请求路径翻译成工作区相对路径。
 *
 * 三种写法都要认（页面里的引用不挑写法）：
 *  - `logo.png`（相对当前文件）
 *  - `./logo.png`
 *  - `/docs/logo.png`（相对工作区根）
 *
 * 越出工作区根的 `../` 一律返回 null（不让页面读到工作区外面）。
 */
internal fun htmlAssetPath(relativeUrl: String): String? {
    val raw = relativeUrl.substringBefore('#').substringBefore('?')
    val decoded = percentDecode(raw).replace('\\', '/')
    if (decoded.isEmpty()) return null
    val normalized = if (decoded.startsWith("/")) decoded else "/$decoded"
    val relative = normalizeRelative(normalized) ?: return null
    // 服务端把路径原样交给工作区读写层，而那一层**认 Windows 绝对路径**（`FileTarget`
    // 支持四种写法）。于是页面里一句 `<img src="/C:/Users/x/.dsh/.credentials.yaml">`
    // 就能把工作区之外的文件读进预览——而这里 JS 是开着的。预览器不是文件管理器，
    // 盘符开头一律拒绝。
    if (hasDriveLetter(relative)) return null
    return relative
}

/**
 * `%E6%95%88` → `效`。
 *
 * 不用 `android.net.Uri.decode`：它在纯 JVM 单测里抛
 * `Method decode in android.net.Uri not mocked`，而这个函数的全部意义就是被单测钉死。
 * 也不用 `java.net.URLDecoder`：它把 `+` 当空格，对**路径**是错的（文件名里真的有 `+`）。
 */
internal fun percentDecode(input: String): String {
    if (input.indexOf('%') < 0) return input
    val bytes = java.io.ByteArrayOutputStream(input.length)
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val hex = input.substring(index + 1, index + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                index += 3
                continue
            }
        }
        // 非 `%xx` 的字符按 UTF-8 写回，保证中文原样通过。
        bytes.write(char.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/** 归一化：`//a/./b/../c` → `a/c`；越界（`..` 超过根）返回 null。 */
internal fun normalizeRelative(path: String): String? {
    val out = ArrayList<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> continue
            ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
            else -> out.add(part)
        }
    }
    if (out.isEmpty()) return null
    return out.joinToString("/")
}

/**
 * 路径是不是盘符绝对路径（`C:…`）。
 *
 * `normalizeRelative` 拦不住它：`C:/Users/x` 里没有任何 `..`，冒号在路径中间也合法。
 * 不用 `Regex("^[A-Za-z]:")`——`Regex.matches` 要求**整串**匹配，短模式会静默地
 * 永远返回 false（这里踩过一次，单测才抓到）。
 */
internal fun hasDriveLetter(path: String): Boolean =
    path.length >= 2 && path[1] == ':' && path[0].isLetter()

/** 扩展名 → MIME。识别不出来按二进制流给（WebView 不会当 HTML 执行它）。 */
internal fun mimeOf(path: String): String {
    val extension = path.substringAfterLast('/').substringAfterLast('.').lowercase()
    return when (extension) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** 被拦掉的外部请求给一个空响应，让页面继续渲染而不是整页失败。 */
private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
