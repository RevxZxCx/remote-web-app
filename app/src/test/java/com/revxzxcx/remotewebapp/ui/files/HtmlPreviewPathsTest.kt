package com.revxzxcx.remotewebapp.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HtmlPane] 的四个纯函数单测（R040）。
 *
 * 抽成顶层函数的理由与 [FileViewerHelpersTest] 一样：这几个函数决定「WebView 发出的
 * 那个请求该读到工作区里的哪个文件」。算错的表现是**图片安静地不显示**——页面仍然
 * 渲染成功、没有任何报错，只有眼睛能发现，所以必须用显式字符串钉死。
 */
class HtmlPreviewPathsTest {

    // ------------------------------------------------------------------ htmlBaseUrl

    @Test
    fun `根目录下的 HTML 的 baseUrl 就是假域名`() {
        assertEquals(HTML_ORIGIN, htmlBaseUrl("index.html"))
    }

    @Test
    fun `子目录下的 HTML 的 baseUrl 带上目录且以斜杠结尾`() {
        // 末尾的斜杠是**语义**的，不是装饰：WebView 拿它当「基准目录」，
        // 少了斜杠 `logo.png` 会被解析成 `<目录名>logo.png`。
        assertEquals("${HTML_ORIGIN}docs/", htmlBaseUrl("docs/index.html"))
    }

    @Test
    fun `多层目录与反斜杠写法等价`() {
        assertEquals("${HTML_ORIGIN}a/b/", htmlBaseUrl("a/b/c.html"))
        assertEquals("${HTML_ORIGIN}a/b/", htmlBaseUrl("a\\b\\c.html"))
    }

    // ------------------------------------------------------------------ htmlAssetPath

    @Test
    fun `相对当前文件的引用按目录解析`() {
        // 页面 baseUrl 是 `.../docs/`，所以 WebView 发出的就是 `.../docs/logo.png`；
        // 去掉域名后这里要还回来 `docs/logo.png`（相对工作区根）。
        assertEquals("docs/logo.png", htmlAssetPath("docs/logo.png"))
    }

    @Test
    fun `点斜杠写法与裸写法等价`() {
        assertEquals("docs/logo.png", htmlAssetPath("./docs/logo.png"))
    }

    @Test
    fun `绝对路径按工作区根解析`() {
        assertEquals("docs/logo.png", htmlAssetPath("/docs/logo.png"))
    }

    @Test
    fun `查询串与锚点被剥掉`() {
        assertEquals("docs/a.css", htmlAssetPath("docs/a.css?v=2"))
        assertEquals("docs/a.css", htmlAssetPath("docs/a.css#top"))
        assertEquals("docs/a.css", htmlAssetPath("docs/a.css?v=2#top"))
    }

    @Test
    fun `百分号编码会被解码`() {
        // 仓库里真实的文件名就带中文（`R040-效果图.html`），WebView 请求时是编码过的。
        assertEquals(
            "docs/R040-效果图.html",
            htmlAssetPath("docs/R040-%E6%95%88%E6%9E%9C%E5%9B%BE.html"),
        )
    }

    @Test
    fun `工作区内部的上级引用被归一化`() {
        assertEquals("logo.png", htmlAssetPath("docs/../logo.png"))
    }

    @Test
    fun `越出工作区根的上级引用返回 null`() {
        // 这是安全边界：页面里写 `../../../../etc/passwd` 不该被翻译成一个可读路径。
        assertNull(htmlAssetPath("../secret.png"))
        assertNull(htmlAssetPath("docs/../../secret.png"))
    }

    @Test
    fun `空串与纯锚点返回 null`() {
        assertNull(htmlAssetPath(""))
        assertNull(htmlAssetPath("#top"))
        assertNull(htmlAssetPath("/"))
    }

    // ------------------------------------------------------------------ normalizeRelative

    @Test
    fun `重复斜杠与点段被折叠`() {
        assertEquals("a/b/c", normalizeRelative("//a//./b//c"))
    }

    @Test
    fun `顶层越界返回 null`() {
        assertNull(normalizeRelative(".."))
        assertNull(normalizeRelative("../a"))
    }

    // ------------------------------------------------------------------ mimeOf

    @Test
    fun `常见前端资源的 MIME 正确`() {
        assertEquals("text/html", mimeOf("a/b.html"))
        assertEquals("text/css", mimeOf("a/b.css"))
        assertEquals("application/javascript", mimeOf("a/b.js"))
        assertEquals("application/json", mimeOf("a/b.json"))
        assertEquals("image/svg+xml", mimeOf("a/b.svg"))
        assertEquals("image/png", mimeOf("a/b.PNG"))
        assertEquals("image/webp", mimeOf("a/b.webp"))
        assertEquals("font/woff2", mimeOf("a/b.woff2"))
    }

    @Test
    fun `未知扩展名按二进制流给出`() {
        // 这个返回值不是「猜一个像的」而是「明确不是文本」：WebView 拿到
        // application/octet-stream 不会当 HTML 执行它，等于拒绝把任意文件当页面跑。
        assertEquals("application/octet-stream", mimeOf("a/b.bin"))
        assertEquals("application/octet-stream", mimeOf("noext"))
    }

    @Test
    fun `盘符绝对路径一律拒绝`() {
        // 服务端把路径原样交给工作区层，而那一层认 Windows 绝对路径。
        // 页面里 `<img src="/C:/Users/x/.dsh/.credentials.yaml">` 就能读到工作区之外，
        // 而这里 JS 是开着的——预览器不是文件管理器。
        assertEquals(null, htmlAssetPath("/C:/Users/26903/.dsh/.credentials.yaml"))
        assertEquals(null, htmlAssetPath("D:/work/secret.txt"))
        assertEquals(null, htmlAssetPath("/c:/windows/win.ini"))
        // 正常相对路径不受影响
        assertEquals("docs/a.png", htmlAssetPath("/docs/a.png"))
    }
}
