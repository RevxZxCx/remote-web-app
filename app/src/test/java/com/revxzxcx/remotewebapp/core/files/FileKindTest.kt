package com.revxzxcx.remotewebapp.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileKind] 的单测。
 *
 * 判定的唯一输入是**路径字符串**，规则与桌面端 documentpreview 的类型矩阵对齐。
 * 这里最容易出错的两点都是真实存在过的坑：
 *  1. **按 basename 而不是整条路径取扩展名** —— 目录名里带点（`F:\a.b\readme`）不该影响判定；
 *  2. Windows 反斜杠路径必须也能切——移动端拿到的 `file_path` 大量是 `F:\bot\…` 形式。
 *
 * [FileKind.of] 对未知扩展名与无扩展名一律兜底 [FileKind.TEXT]（服务端说不是文本时再报错），
 * 所以「都是 TEXT」的用例不是废话：它们钉死的是**不许猜成 BINARY**。
 */
class FileKindTest {

    // ------------------------------------------------------------------ of

    @Test
    fun `markdown 扩展名`() {
        assertEquals(FileKind.MARKDOWN, FileKind.of("README.md"))
        assertEquals(FileKind.MARKDOWN, FileKind.of("docs/guide.markdown"))
        assertEquals(FileKind.MARKDOWN, FileKind.of("a/b/c.mdown"))
    }

    @Test
    fun `图片扩展名`() {
        assertEquals(FileKind.IMAGE, FileKind.of("logo.png"))
        assertEquals(FileKind.IMAGE, FileKind.of("photo.JPG"))
        assertEquals(FileKind.IMAGE, FileKind.of("a.jpeg"))
        assertEquals(FileKind.IMAGE, FileKind.of("anim.webp"))
        assertEquals(FileKind.IMAGE, FileKind.of("anim.gif"))
        assertEquals(FileKind.IMAGE, FileKind.of("scan.bmp"))
    }

    @Test
    fun `pdf 扩展名`() {
        assertEquals(FileKind.PDF, FileKind.of("spec.pdf"))
        assertEquals(FileKind.PDF, FileKind.of("F:\\bot\\docs\\需求文档.pdf"))
    }

    @Test
    fun `代码扩展名`() {
        assertEquals(FileKind.CODE, FileKind.of("Main.kt"))
        assertEquals(FileKind.CODE, FileKind.of("build.gradle"))
        assertEquals(FileKind.CODE, FileKind.of("script.py"))
        assertEquals(FileKind.CODE, FileKind.of("main.go"))
        assertEquals(FileKind.CODE, FileKind.of("lib.rs"))
        assertEquals(FileKind.CODE, FileKind.of("index.tsx"))
    }

    @Test
    fun `纯文本扩展名`() {
        assertEquals(FileKind.TEXT, FileKind.of("notes.txt"))
        assertEquals(FileKind.TEXT, FileKind.of("data.json"))
        assertEquals(FileKind.TEXT, FileKind.of("conf.yaml"))
        assertEquals(FileKind.TEXT, FileKind.of("conf.yml"))
        assertEquals(FileKind.TEXT, FileKind.of("app.log"))
        assertEquals(FileKind.TEXT, FileKind.of("table.csv"))
        assertEquals(FileKind.TEXT, FileKind.of("build.toml"))
        assertEquals(FileKind.TEXT, FileKind.of("local.properties"))
    }

    @Test
    fun `未知扩展名与无扩展名都兜底为 TEXT`() {
        // 不许猜成 BINARY：客户端猜二进制的判据不如服务端「真的读不到文本」准。
        assertEquals(FileKind.TEXT, FileKind.of("archive.zip"))
        assertEquals(FileKind.TEXT, FileKind.of("weird.zzz"))
        assertEquals(FileKind.TEXT, FileKind.of("Makefile"))
        assertEquals(FileKind.TEXT, FileKind.of("LICENSE"))
        assertEquals(FileKind.TEXT, FileKind.of("."))
    }

    @Test
    fun `Windows 反斜杠路径按 basename 判定`() {
        assertEquals(FileKind.MARKDOWN, FileKind.of("C:\\a\\b\\doc.MD"))
        assertEquals(FileKind.IMAGE, FileKind.of("F:\\bot\\assets\\icon.PNG"))
        assertEquals(FileKind.CODE, FileKind.of("D:\\proj\\src\\Main.KT"))
    }

    @Test
    fun `嵌套路径与混合分隔符都只看最后一段`() {
        assertEquals(FileKind.MARKDOWN, FileKind.of("a/b/c/README.md"))
        assertEquals(FileKind.MARKDOWN, FileKind.of("a\\b/c\\README.md"))
        // 目录名里带点，不许把目录当成文件。
        assertEquals(FileKind.TEXT, FileKind.of("F:\\a.md\\notes"))
        assertEquals(FileKind.TEXT, FileKind.of("some.png/plainfile"))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertEquals(FileKind.MARKDOWN, FileKind.of("A.MD"))
        assertEquals(FileKind.MARKDOWN, FileKind.of("A.MarkDown"))
        assertEquals(FileKind.IMAGE, FileKind.of("A.PnG"))
        assertEquals(FileKind.CODE, FileKind.of("A.Kt"))
    }

    @Test
    fun `多重点号取最后一段作为扩展名`() {
        assertEquals(FileKind.IMAGE, FileKind.of("backup.tar.png"))
        // `tar.gz` 不是已知扩展名 → TEXT（不过度解读）。
        assertEquals(FileKind.TEXT, FileKind.of("backup.tar.gz"))
    }

    // ------------------------------------------------------------------ languageOf

    @Test
    fun `常见语言的扩展名映射`() {
        assertEquals("kotlin", FileKind.languageOf("Main.kt"))
        assertEquals("kotlin", FileKind.languageOf("build.gradle.kts"))
        assertEquals("python", FileKind.languageOf("script.py"))
        assertEquals("typescript", FileKind.languageOf("index.ts"))
        assertEquals("typescript", FileKind.languageOf("view.tsx"))
        assertEquals("powershell", FileKind.languageOf("deploy.ps1"))
        assertEquals("groovy", FileKind.languageOf("build.gradle"))
        assertEquals("rust", FileKind.languageOf("lib.rs"))
        assertEquals("go", FileKind.languageOf("main.go"))
        assertEquals("json", FileKind.languageOf("data.json"))
    }

    @Test
    fun `未知扩展名与无扩展名返回 null`() {
        // 返回 null 而不是 "text"：调用方据此决定不做高亮，而不是按 text 规则乱上色。
        assertNull(FileKind.languageOf("notes.txt"))
        assertNull(FileKind.languageOf("weird.zzz"))
        assertNull(FileKind.languageOf("Makefile"))
        assertNull(FileKind.languageOf(""))
    }

    @Test
    fun `languageOf 也按 basename 且大小写不敏感`() {
        assertEquals("kotlin", FileKind.languageOf("C:\\a\\b\\Main.KT"))
        assertEquals("powershell", FileKind.languageOf("F:\\bot\\scripts\\run.PS1"))
        assertEquals("python", FileKind.languageOf("pkg/tool.py"))
    }

    @Test
    fun `高亮的语言名与 FileKind 判定是两套独立规则`() {
        // 钉死这个组合：`yaml` 是 TEXT（走分页文本），但高亮语言是 yaml。
        assertEquals(FileKind.TEXT, FileKind.of("conf.yaml"))
        assertEquals("yaml", FileKind.languageOf("conf.yaml"))
        // `log` 是 TEXT 但没有高亮语言。
        assertEquals(FileKind.TEXT, FileKind.of("app.log"))
        assertNull(FileKind.languageOf("app.log"))
    }
}
