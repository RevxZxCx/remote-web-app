package com.revxzxcx.remotewebapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [MdParser] 的 JVM 单测。
 *
 * 这里守护两条契约（`MdParser.kt` 类注释里的两条设计承诺）：
 *
 * 1. **永不抛异常**。流式生成时文本永远处于「写了一半」的状态（未闭合围栏、
 *    只有一半的表格分隔行），任何输入都必须降级成段落而不是崩掉聊天页。
 * 2. **每个块都带原始字符区间**。区间是增量渲染的坐标系，所以对**每一篇**
 *    测试文档都断言 `0 <= startOffset <= endOffset <= text.length`
 *    （见 [assertOffsetsSane]）。
 *
 * 另外把行内语法到 [MdInline] 子类型的映射钉死，因为 `MdInlineParser` 是
 * `internal`，只有同模块的单测能直接覆盖它。
 */
class MdParserTest {

    // ------------------------------------------------------------ 标题 / 段落

    @Test
    fun `一二三级标题按 level 解析`() {
        val text = "# A\n## B\n### C\n"
        val blocks = MdParser.parse(text)

        assertEquals("三个标题应该解析成三个块", 3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals("A", (blocks[0] as MdBlock.Heading).inlines.plainText())
        assertEquals("B", (blocks[1] as MdBlock.Heading).inlines.plainText())
        assertEquals("C", (blocks[2] as MdBlock.Heading).inlines.plainText())
    }

    @Test
    fun `标题的边界情况`() {
        // 结尾的闭合 # 被去掉。
        assertEquals(1, (MdParser.parse("# A #\n")[0] as MdBlock.Heading).level)
        assertEquals("A", (MdParser.parse("# A #\n")[0] as MdBlock.Heading).inlines.plainText())
        assertEquals("B", (MdParser.parse("## B ##\n")[0] as MdBlock.Heading).inlines.plainText())
        // 6 级是上限。
        assertEquals(6, (MdParser.parse("###### F\n")[0] as MdBlock.Heading).level)
        // 前导空白不影响。
        assertEquals(1, (MdParser.parse("   # C\n")[0] as MdBlock.Heading).level)
        // 7 个 #、只有 #、# 后无空格 → 都不是标题，退化成段落。
        assertTrue("7 个 # 不是标题", MdParser.parse("####### G\n")[0] is MdBlock.Paragraph)
        assertTrue("裸 # 不是标题", MdParser.parse("#\n")[0] is MdBlock.Paragraph)
        assertTrue("# 后无空格不是标题", MdParser.parse("#NoSpace\n")[0] is MdBlock.Paragraph)
    }

    @Test
    fun `段落把连续行合并并对每行 trim`() {
        val text = "line one\nline two\n\nsecond para\n"
        val blocks = MdParser.parse(text)

        assertEquals(2, blocks.size)
        val first = blocks[0] as MdBlock.Paragraph
        // 两行以换行拼接，行内解析再把 \n 变成空格。
        assertEquals("line one line two", first.inlines.plainText())
        assertEquals("第二个段落", "second para", (blocks[1] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `段落内每行都会被 trim`() {
        val text = "  indented\nnext\n"
        val paragraph = MdParser.parse(text)[0] as MdBlock.Paragraph
        assertEquals("indented next", paragraph.inlines.plainText())
    }

    // ------------------------------------------------------------ 围栏代码块

    @Test
    fun `带语言标签的围栏代码块`() {
        val text = "before\n\n```kotlin\nval x = 1\n```\nafter\n"
        val blocks = MdParser.parse(text)

        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as MdBlock.Paragraph).inlines.plainText())
        val code = blocks[1] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertTrue("围栏已闭合，closed 应为 true", code.closed)
        assertEquals("after", (blocks[2] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `不带语言标签时 language 为 null`() {
        val code = MdParser.parse("```\nx\n```\n")[0] as MdBlock.CodeBlock
        assertNull("围栏 info 为空时 language 必须是 null 而不是空串", code.language)
        assertEquals("x", code.code)
        assertTrue(code.closed)
        // 空代码块也要能解析。
        val empty = MdParser.parse("```\n```\n")[0] as MdBlock.CodeBlock
        assertNull(empty.language)
        assertEquals("", empty.code)
        assertTrue(empty.closed)
    }

    @Test
    fun `未闭合围栏的 closed 为 false`() {
        val text = "text\n\n```kotlin\nval x = 1\n"
        val blocks = MdParser.parse(text)

        assertEquals(2, blocks.size)
        val code = blocks[1] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertFalse("没有闭合围栏时 closed 必须是 false", code.closed)
        assertEquals("未闭合时内容包含最后那个换行", "val x = 1\n", code.code)
    }

    @Test
    fun `闭合围栏必须不短于开启围栏`() {
        // 4 个反引号开启、3 个闭合 → 不算闭合。
        val short = MdParser.parse("````\nx\n```\n")[0] as MdBlock.CodeBlock
        assertFalse("更短的围栏不能闭合代码块", short.closed)
        assertEquals("x\n```\n", short.code)

        // 4 个开启 4 个闭合 → 闭合。
        val ok = MdParser.parse("````\nx\n````\n")[0] as MdBlock.CodeBlock
        assertTrue(ok.closed)
        assertEquals("x", ok.code)
    }

    @Test
    fun `波浪号围栏等价于反引号围栏`() {
        val code = MdParser.parse("~~~py\nx\n~~~\n")[0] as MdBlock.CodeBlock
        assertEquals("py", code.language)
        assertEquals("x", code.code)
        assertTrue(code.closed)
    }

    // ------------------------------------------------------------ 列表

    @Test
    fun `无序列表解析出条目`() {
        val text = "- a\n- b\n\n1. one\n2. two\n"
        val blocks = MdParser.parse(text)

        assertEquals(2, blocks.size)
        val bullets = blocks[0] as MdBlock.BulletList
        assertEquals(2, bullets.items.size)
        assertEquals("a", bullets.items[0].inlines.plainText())
        assertEquals("b", bullets.items[1].inlines.plainText())
        // 普通条目不是任务项。
        assertNull("非任务列表条目 checked 必须是 null", bullets.items[0].checked)
        assertEquals("有序列表默认从 1 开始", 1, (blocks[1] as MdBlock.OrderedList).start)
        assertEquals(2, (blocks[1] as MdBlock.OrderedList).items.size)
    }

    @Test
    fun `有序列表保留显式起始序号`() {
        val ordered = MdParser.parse("3. three\n4. four\n")[0] as MdBlock.OrderedList
        assertEquals("显式起始序号必须保留（渲染时要用）", 3, ordered.start)
        assertEquals(2, ordered.items.size)
        assertEquals("three", ordered.items[0].inlines.plainText())

        // 右括号也是合法标记。
        assertEquals(3, (MdParser.parse("3) three\n")[0] as MdBlock.OrderedList).start)
    }

    @Test
    fun `任务列表区分未完成已完成与非任务`() {
        val text = "- [ ] todo\n- [x] done\n- [X] DONE\n- plain\n"
        val list = MdParser.parse(text)[0] as MdBlock.BulletList

        assertEquals(4, list.items.size)
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
        assertEquals("大写 X 也算已完成", true, list.items[2].checked)
        assertNull("没有 [ ] 标记的条目 checked 为 null", list.items[3].checked)
        assertEquals("todo", list.items[0].inlines.plainText())
        assertEquals("done", list.items[1].inlines.plainText())
        assertEquals("DONE", list.items[2].inlines.plainText())
        assertEquals("plain", list.items[3].inlines.plainText())
    }

    @Test
    fun `列表条目的 children 目前始终为空`() {
        // 嵌套列表不会进 children：`- a\n  - b` 被当成两个同级条目。
        val list = MdParser.parse("- a\n  - b\n")[0] as MdBlock.BulletList
        assertEquals(2, list.items.size)
        assertTrue("解析器不会填充 children", list.items.all { it.children.isEmpty() })
    }

    // ------------------------------------------------------------ 引用 / 表格 / 分割线

    @Test
    fun `引用块内嵌标题段落与列表`() {
        val text = "> # H\n> text\n>\n> - a\n"
        val quote = MdParser.parse(text)[0] as MdBlock.Quote

        assertEquals("引用块里的内容递归解析成块序列", 3, quote.blocks.size)
        assertEquals(1, (quote.blocks[0] as MdBlock.Heading).level)
        assertEquals("H", (quote.blocks[0] as MdBlock.Heading).inlines.plainText())
        assertEquals("text", (quote.blocks[1] as MdBlock.Paragraph).inlines.plainText())
        assertEquals("a", ((quote.blocks[2] as MdBlock.BulletList).items[0]).inlines.plainText())
    }

    @Test
    fun `引用块可以嵌套引用`() {
        val quote = MdParser.parse("> outer\n> > inner\n")[0] as MdBlock.Quote
        val inner = quote.blocks.filterIsInstance<MdBlock.Quote>()
        assertEquals("第二行是嵌套引用", 1, inner.size)
        assertEquals("inner", (inner[0].blocks[0] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `表格解析表头与数据行`() {
        val text = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n"
        val table = MdParser.parse(text)[0] as MdBlock.Table

        assertEquals(listOf("a", "b"), table.headers.map { it.plainText() })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.plainText() })
        assertEquals(listOf("3", "4"), table.rows[1].map { it.plainText() })
    }

    @Test
    fun `表格对齐全由分隔行里的冒号承担而单元格内容不变`() {
        val table = MdParser.parse("| a | b |\n| :-: | --: |\n| 1 | 2 |\n")[0] as MdBlock.Table
        assertEquals(listOf("a", "b"), table.headers.map { it.plainText() })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.plainText() } })
    }

    @Test
    fun `表格单元格里的转义竖线被还原`() {
        val text = "| a \\| b | c |\n| - | - |\n| x | y |\n"
        val table = MdParser.parse(text)[0] as MdBlock.Table
        assertEquals("a | b", table.headers[0].plainText())
        assertEquals("c", table.headers[1].plainText())
    }

    @Test
    fun `只有表头没有数据行时 rows 为空`() {
        val table = MdParser.parse("| a | b |\n| --- | --- |\n")[0] as MdBlock.Table
        assertEquals(listOf("a", "b"), table.headers.map { it.plainText() })
        assertTrue("没有数据行时 rows 必须是空列表", table.rows.isEmpty())
    }

    @Test
    fun `分割线识别`() {
        val text = "---\n\ntext\n\n***\n"
        val blocks = MdParser.parse(text)

        assertEquals(3, blocks.size)
        assertTrue("第一行是分割线", blocks[0] is MdBlock.Rule)
        assertTrue("中间是段落", blocks[1] is MdBlock.Paragraph)
        assertTrue("*** 也是分割线", blocks[2] is MdBlock.Rule)
    }

    @Test
    fun `分割线优先于列表判断`() {
        // `- - -` 同时也是 `- ` 开头，但只算分割线。
        assertTrue(MdParser.parse("- - -\n")[0] is MdBlock.Rule)
        assertTrue(MdParser.parse("___\n")[0] is MdBlock.Rule)
        assertTrue(MdParser.parse("----\n")[0] is MdBlock.Rule)
        // 只有两个短横线不够。
        assertTrue(MdParser.parse("--\n")[0] is MdBlock.Paragraph)
    }

    // ------------------------------------------------------------ 区间不变量

    @Test
    fun `每个块的区间都在文本范围内且 endOffset 不小于 startOffset`() {
        val docs = listOf(
            "# A\n## B\n### C\n",
            "line one\nline two\n\nsecond para\n",
            "before\n\n```kotlin\nval x = 1\n```\nafter\n",
            "text\n\n```kotlin\nval x = 1\n",
            "```\n```\n",
            "- a\n- b\n\n1. one\n2. two\n",
            "3. three\n4. four\n",
            "- [ ] todo\n- [x] done\n- [X] DONE\n- plain\n",
            "> # H\n> text\n>\n> - a\n",
            "> outer\n> > inner\n",
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n",
            "| a | b |\n| --- | --- |\n",
            "---\n\ntext\n\n***\n",
            "a ` b\n",
            "x".repeat(3000),
            "Title\n=====\n",
            "para\n  continued\n\nnext\n",
            "",
            "   \n\n  \n",
        )
        for (doc in docs) {
            assertOffsetsSane(doc, MdParser.parse(doc), "文档 <${doc.take(24).replace("\n", "\\n")}>")
        }
    }

    @Test
    fun `区间恰好覆盖内容行且不含行尾换行`() {
        val text = "before\n\n```kotlin\nval x = 1\n```\nafter\n"
        val blocks = MdParser.parse(text)
        // 块区间是「行首到行尾（不含换行）」——增量冻结靠它做分割。
        assertEquals("段落区间不含行尾换行", "before", text.substring(blocks[0].startOffset, blocks[0].endOffset))
        assertEquals("after", text.substring(blocks[2].startOffset, blocks[2].endOffset))
        // 代码块区间覆盖整三行（含行内换行，不含最后一行的换行）。
        val code = blocks[1]
        assertEquals("```kotlin\nval x = 1\n```", text.substring(code.startOffset, code.endOffset))
    }

    // ------------------------------------------------------------ 健壮性

    @Test
    fun `病态输入不会抛异常`() {
        val pathological = listOf(
            "",
            "   \n\n  \n",
            "```",
            "```kotlin",
            "text\n\n```kotlin\nval x = 1\n",
            "````\nx\n```\n",
            "```a`b\nx\n```\n",
            "a ` b\n",
            "```\n",
            "|",
            "| a |\n",
            "| a | b |\n| --- | --- |\n",
            "a|b\n-|-\n",
            "> > > deep\n",
            "> \n> \n",
            "> quote\n>\n> more\n",
            "# A\r\n\r\ntext\r\n",
            "a\rb\rc",
            "\r\n\r\n",
            "x".repeat(20000),
            "- " + "y".repeat(5000),
            "| " + "z |".repeat(500),
            "~~~",
            "***",
            "___",
            "![a](",
            "[t](",
            "**unclosed",
            "~~unclosed",
            "1.",
            "99999999999. x\n",
            "\u0000\u0001\u0002",
        )
        for (input in pathological) {
            val shown = input.take(30).replace("\n", "\\n").replace("\r", "\\r")
            try {
                // 只要求「返回列表且区间合法」，不要求特定结构。
                assertOffsetsSane(input, MdParser.parse(input), "病态输入 <$shown>")
            } catch (e: Throwable) {
                fail("MdParser.parse 不允许抛异常，输入 <$shown> 抛了 ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    @Test
    fun `空串与纯空白解析成空列表`() {
        assertTrue("空串应该没有块", MdParser.parse("").isEmpty())
        assertTrue("纯空白应该没有块", MdParser.parse("   \n\n  \n").isEmpty())
        assertTrue("只有换行应该没有块", MdParser.parse("\n\n\n").isEmpty())
    }

    @Test
    fun `超长单行能解析成一个段落`() {
        val text = "x".repeat(3000)
        val blocks = MdParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(0, blocks[0].startOffset)
        assertEquals(3000, blocks[0].endOffset)
    }

    @Test
    fun `CRLF 会被归一化后再解析`() {
        val blocks = MdParser.parse("# A\r\n\r\ntext\r\n")
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals("当然要能识别 CRLF 里的标题", "A", (blocks[0] as MdBlock.Heading).inlines.plainText())
        assertEquals("text", (blocks[1] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `CRLF 文本的块区间是归一化后的坐标`() {
        // 疑似缺陷：MdParser.kt:10 与 MdModel.kt:11 都声明区间是「原始文本里的字符区间」，
        // 但 parse 在 MdParser.kt:26 先把 \r\n 归一化成 \n 再建行索引，区间因此落在
        // 归一化文本上。对 LF 文本两者重合，对 CRLF 文本就会整体左移。
        val text = "# A\r\n\r\ntext\r\n"
        val blocks = MdParser.parse(text)

        // 归一化后 "# A\n\ntext\n"：text 段在 5..8。
        assertEquals(5, blocks[1].startOffset)
        assertEquals(9, blocks[1].endOffset)

        // 用原始文本去切这两个下标会拿到完全不相干的内容（"\r\nte"），
        // 说明「区间可以映射回原始文本」这条契约在 CRLF 输入下不成立。
        assertEquals("\\r\\nte", text.substring(blocks[1].startOffset, blocks[1].endOffset).replace("\r", "\\r").replace("\n", "\\n"))
    }

    @Test
    fun `引用块内嵌块的区间不带引用前缀偏移`() {
        // 疑似缺陷：MdParser.kt:113 用 parseBlocks(innerText, 起始行偏移) 递归解析引用内容，
        // innerText 已经去掉了每行的 `> ` 前缀，但 base 只补了行偏移、没有补前缀长度，
        // 于是内嵌块的区间属于「去前缀后的坐标系」，与 Quote 自身的区间不是同一个空间。
        val text = "> # H\n> text\n> - a\n"
        val quote = MdParser.parse(text)[0] as MdBlock.Quote

        val heading = quote.blocks[0] as MdBlock.Heading
        // 内嵌标题报的是 0..3，但原文的 0..3 是 "> #"。
        assertEquals(0, heading.startOffset)
        assertEquals(3, heading.endOffset)
        assertEquals("> #", text.substring(heading.startOffset, heading.endOffset))
        // 而 Quote 自己的区间是外层坐标系（0..len-1），两者混在一起无法直接比较。
        assertEquals(0, quote.startOffset)
        assertEquals(text.length - 1, quote.endOffset)
    }

    // ------------------------------------------------------------ 行内

    @Test
    fun `行内语法映射到对应的 MdInline 子类型`() {
        assertTrue(MdParser.parse("**b**\n")[0].firstInline() is MdInline.Strong)
        assertTrue(MdParser.parse("__b__\n")[0].firstInline() is MdInline.Strong)
        assertTrue(MdParser.parse("*i*\n")[0].firstInline() is MdInline.Emphasis)
        assertTrue(MdParser.parse("_i_\n")[0].firstInline() is MdInline.Emphasis)
        assertTrue(MdParser.parse("~~s~~\n")[0].firstInline() is MdInline.Strikethrough)
        assertTrue(MdParser.parse("`c`\n")[0].firstInline() is MdInline.Code)
        assertTrue(MdParser.parse("[t](u)\n")[0].firstInline() is MdInline.Link)
        assertTrue(MdParser.parse("![a](s)\n")[0].firstInline() is MdInline.Image)
        assertTrue(MdParser.parse("plain\n")[0].firstInline() is MdInline.Text)
    }

    @Test
    fun `行内语法的具体内容`() {
        val strong = MdParser.parse("**b**\n")[0].firstInline() as MdInline.Strong
        assertEquals("b", strong.children.plainText())

        val code = MdParser.parse("`c`\n")[0].firstInline() as MdInline.Code
        assertEquals("c", code.text)

        val link = MdParser.parse("[t](u)\n")[0].firstInline() as MdInline.Link
        assertEquals("t", link.text.plainText())
        assertEquals("u", link.href)

        val image = MdParser.parse("![a](s)\n")[0].firstInline() as MdInline.Image
        assertEquals("a", image.alt)
        assertEquals("s", image.src)
    }

    @Test
    fun `plainText 拼接所有行内子类型`() {
        val inlines = listOf<MdInline>(
            MdInline.Text("a"),
            MdInline.Code("c"),
            MdInline.Strong(listOf(MdInline.Text("s"))),
            MdInline.Emphasis(listOf(MdInline.Text("e"))),
            MdInline.Strikethrough(listOf(MdInline.Text("t"))),
            MdInline.Link(listOf(MdInline.Text("l")), "u"),
            MdInline.Image("i", "src"),
            MdInline.LineBreak,
        )
        assertEquals("acsetli\n", inlines.plainText())
    }

    @Test
    fun `plainText 为空时返回空串`() {
        assertEquals("", emptyList<MdInline>().plainText())
    }

    @Test
    fun `未闭合的行内定界符退化成字面量`() {
        assertEquals("a ` b", MdParser.parse("a ` b\n")[0].firstInline().let { (it as MdInline.Text).text })
        assertEquals("**x", (MdParser.parse("**x\n")[0].firstInline() as MdInline.Text).text)
        // 蛇形命名不能被当成强调。
        assertEquals("snake_case_name", (MdParser.parse("snake_case_name\n")[0].firstInline() as MdInline.Text).text)
    }

    // ------------------------------------------------------------ 辅助

    private fun MdBlock.firstInline(): MdInline = when (this) {
        is MdBlock.Paragraph -> inlines.first()
        is MdBlock.Heading -> inlines.first()
        else -> throw AssertionError("块 ${this::class.simpleName} 没有行内内容")
    }

    /**
     * 区间不变量：`0 <= startOffset <= endOffset <= text.length`。
     *
     * 引用块的嵌套块属于另一套坐标系（见 `引用块内嵌块的区间不带引用前缀偏移`），
     * 但它们的数值仍然必须落在文本范围内，所以一并递归检查。
     */
    private fun assertOffsetsSane(text: String, blocks: List<MdBlock>, where: String) {
        for (block in blocks) {
            val kind = block::class.simpleName
            assertTrue("$where: $kind startOffset=${block.startOffset} 必须 >= 0", block.startOffset >= 0)
            assertTrue(
                "$where: $kind endOffset=${block.endOffset} 必须 >= startOffset=${block.startOffset}",
                block.endOffset >= block.startOffset,
            )
            assertTrue(
                "$where: $kind endOffset=${block.endOffset} 必须 <= 文本长度 ${text.length}",
                block.endOffset <= text.length,
            )
            if (block is MdBlock.Quote) {
                assertOffsetsSane(text, block.blocks, "$where/quote")
            }
        }
    }
}
