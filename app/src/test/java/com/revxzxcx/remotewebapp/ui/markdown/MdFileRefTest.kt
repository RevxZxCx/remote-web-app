package com.revxzxcx.remotewebapp.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MdFileRef] 的 JVM 单测。
 *
 * 这个函数决定「一段行内代码要不要变成可点的文件入口」（需求 FR-5.9，P2）。
 * `MdFileRef.kt:5-8` 明确写了它的取向：**故意保守**。误判的代价（点开一个不存在的
 * 路径 → 文件查看页报「文件不存在」）比漏判大得多，所以断言的重点全在**否定**一侧：
 * 普通标识符、裸文件名、目录名、带空格的表达式、超长串都必须被拒。
 *
 * 本文件同时记录了一处与设计意图冲突的行为（Windows 盘符路径），见
 * `Windows 盘符路径全部被拒`。
 */
class MdFileRefTest {

    private fun p(text: String) = MdFileRef.looksLikePath(text)

    // ------------------------------------------------------------ 正例

    @Test
    fun `带目录的相对路径被识别`() {
        assertTrue("app/src/main/java/Foo.kt", p("app/src/main/java/Foo.kt"))
        assertTrue("app/build.gradle.kts", p("app/build.gradle.kts"))
        assertTrue("src/a.b/c.ts", p("src/a.b/c.ts"))
        assertTrue("./a/b.kt", p("./a/b.kt"))
        assertTrue("../a/b.kt", p("../a/b.kt"))
        assertTrue("a/b/c.d", p("a/b/c.d"))
        assertTrue("src\\main\\Foo.kt", p("src\\main\\Foo.kt"))
    }

    @Test
    fun `路径里的非 ASCII 文件名不影响识别`() {
        // 扩展名正则只约束最后一个点之后的部分，文件名里的中文不参与匹配。
        assertTrue("docs/需求文档.md", p("docs/需求文档.md"))
        assertTrue("docs\\需求文档.md", p("docs\\需求文档.md"))
        assertTrue("b/中文目录/文件.kt", p("b/中文目录/文件.kt"))
    }

    @Test
    fun `扩展名长度上限是 12 位且可以是纯数字`() {
        assertTrue("a/b.c", p("a/b.c"))
        assertTrue("正好 12 位扩展名", p("a/b.cdefghijklmn"))
        assertTrue("数字扩展名", p("a/b.123"))
        assertTrue("双扩展名只看最后一段", p("a/b.gradle.kts"))
    }

    @Test
    fun `超长但仍在长度窗口内的路径被识别`() {
        val path400 = "a/" + "y".repeat(395) + ".kt"
        assertTrue("长度刚好 400 应该在窗口内（len=${path400.length}）", p(path400))
    }

    @Test
    fun `首尾空白会被 trim 后再判断`() {
        assertTrue(p("  app/src/Foo.kt  "))
        assertTrue(p("\tapp/src/Foo.kt\n"))
    }

    // ------------------------------------------------------------ 反例：标识符与裸文件名

    @Test
    fun `普通标识符不是路径`() {
        assertFalse("requestId", p("requestId"))
        assertFalse("isActive", p("isActive"))
        assertFalse("snake_case_name", p("snake_case_name"))
        assertFalse("Foo", p("Foo"))
        assertFalse("a.b", p("a.b"))
    }

    @Test
    fun `裸文件名刻意不算路径`() {
        // MdFileRef.kt:13-15：工作区里可能有多个同名文件，猜错比漏判更糟。
        assertFalse("ChatScreen.kt", p("ChatScreen.kt"))
        assertFalse("build.gradle.kts", p("build.gradle.kts"))
        assertFalse("需求文档.md", p("需求文档.md"))
    }

    // ------------------------------------------------------------ 反例：字符否决

    @Test
    fun `含空格或代码字符的片段被否决`() {
        assertFalse("a b/c.kt", p("a b/c.kt"))
        assertFalse("a + b/c.kt", p("a + b/c.kt"))
        assertFalse("a\tb/c.kt", p("a\tb/c.kt"))
        assertFalse("带引号", p("\"a/b.kt\""))
        assertFalse("带括号的函数调用", p("f(a/b.kt)"))
        assertFalse("带方括号", p("a/b[0].kt"))
        assertFalse("带管道", p("a/b.kt|grep"))
        assertFalse("带星号", p("a/*.kt"))
        assertFalse("带美元符号", p("\$a/b.kt"))
        assertFalse("带等号", p("x=a/b.kt"))
        assertFalse("带惊叹号", p("a/b.kt!"))
        assertFalse("带逗号", p("a/b.kt, c/d.kt"))
    }

    // ------------------------------------------------------------ 反例：形态不对

    @Test
    fun `空串与纯空白不是路径`() {
        assertFalse("空串", p(""))
        assertFalse("纯空格", p("   "))
        assertFalse("只有分隔符", p("/"))
        assertFalse("两个字符太短", p("a/"))
    }

    @Test
    fun `没有扩展名的目录形态不是路径`() {
        assertFalse("ui/chat", p("ui/chat"))
        assertFalse("app/src/main/java", p("app/src/main/java"))
        assertFalse("a/b/c", p("a/b/c"))
    }

    @Test
    fun `以分隔符结尾不是路径`() {
        assertFalse("app/", p("app/"))
        assertFalse("app\\", p("app\\"))
        assertFalse("app/src/", p("app/src/"))
    }

    @Test
    fun `点号后为空或扩展名过长不是路径`() {
        assertFalse("a/b.", p("a/b."))
        assertFalse("13 位扩展名超过上限", p("a/b.cdefghijklmno"))
        assertFalse("扩展名含短横线", p("a/b.c-d"))
        assertFalse("扩展名含下划线", p("a/b.c_d"))
    }

    @Test
    fun `超过长度上限的字符串不是路径`() {
        val path401 = "a/" + "y".repeat(396) + ".kt"
        assertFalse("长度 401 必须被拒（len=${path401.length}）", p(path401))
        assertFalse("超长单行（1000 字符）", p("a/" + "y".repeat(995) + ".kt"))
        assertFalse("纯重复字符", p("y".repeat(500)))
    }

    @Test
    fun `单个斜杠开头的短路径需要至少两个斜杠`() {
        // MdFileRef.kt:33：`/a.kt` 这种太像除法表达式，要求至少两个 '/'。
        assertFalse("单个斜杠的根路径被拒", p("/a.kt"))
        assertTrue("两个斜杠的绝对路径通过", p("/a/b.kt"))
    }

    @Test
    fun `连续分隔符不会被特别处理`() {
        // 记录实际行为：`a//b.kt` 只做形态检查，不规范化路径，所以算命中。
        assertTrue("a//b.kt", p("a//b.kt"))
    }

    // ------------------------------------------------------------ 与设计意图冲突

    @Test
    fun `URL 因为冒号规则被拒`() {
        // 设计意图如此：NOT_PATH_CHARS 含 ':'（MdFileRef.kt:20），
        // 所以 `https://x.com/a` 在「字符否决」这一步就被拦下，不会变成可点入口。
        assertFalse("https://example.com/a", p("https://example.com/a"))
        assertFalse("https://x.com/a", p("https://x.com/a"))
        assertFalse("http://a.com", p("http://a.com"))
        assertFalse("ftp://a.com/b.txt", p("ftp://a.com/b.txt"))
    }

    @Test
    fun `Windows 盘符路径被识别`() {
        // R005 审查前这里断言「全部被拒」，是**记录缺陷**而非记录意图：旧实现的
        // NOT_PATH_CHARS 含 ':'，而字符否决在 DRIVE 判断之前执行，于是 DRIVE 分支
        // 永远不可达（死代码），Windows 绝对路径一律识别不出来。修复后冒号只在
        // 盘符位置合法：先剥掉 `C:\` / `F:/` 前缀，再校验剩余部分不含冒号。
        assertTrue("F:\\bot\\remote-web-app\\settings.gradle.kts", p("F:\\bot\\remote-web-app\\settings.gradle.kts"))
        assertTrue("F:/bot/remote-web-app/settings.gradle.kts", p("F:/bot/remote-web-app/settings.gradle.kts"))
        assertTrue("盘符后直接跟文件名（盘符自带分隔符）", p("C:/x.kt"))
        assertTrue("盘符 + 反斜杠相对段", p("C:\\Users\\me\\Foo.kt"))

        // 盘符之外的冒号仍然否决，否则 `https://x.com/a` 会变成可点文件入口。
        assertFalse("第二个冒号仍在否决位", p("C:/a:b/c.kt"))
        assertFalse("没有盘符的冒号", p("a:b/c.kt"))

        // 对照：同样的路径去掉盘符也能通过。
        assertTrue("bot/remote-web-app/settings.gradle.kts", p("bot/remote-web-app/settings.gradle.kts"))
    }

    @Test
    fun `盘符后只有文件名且没有扩展名不是路径`() {
        // 盘符允许「没有目录分隔符」，但仍要求最后一段像文件名（有扩展名）。
        assertFalse("C:\\x", p("C:\\x"))
        assertFalse("C:\\", p("C:\\"))
        assertFalse("F:/bot", p("F:/bot"))
    }
}
