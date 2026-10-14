package com.revxzxcx.remotewebapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MdStream] 的 JVM 单测。
 *
 * 这个类是聊天页的性能关键路径：助手回复逐字到达，每来一个字都会走一次 [MdStream.update]。
 * 它把稳定前缀冻进缓存、只重解析尾部，所以**正确性完全依赖一条不变量**（`MdStream.kt:18`）：
 *
 * > `update(text)` 的结果永远等于 `MdParser.parse(text)`。
 *
 * 增量只是优化，不允许改变输出。本文件的核心就是用「文档的每一个前缀」去撞这条不变量：
 * 把文档切成块、逐块拼接成越来越长的前缀，每一步都拿 [MdStream.update] 与
 * [MdParser.parse] 逐块比对。凡是分割点选错（例如把跨空行续写的列表拆开），这里就会红。
 *
 * 其余用例覆盖缓存路径、整段重写、未闭合围栏与 [MdStream.reset]。
 */
class MdStreamTest {

    /**
     * 覆盖了各个块类型与各种「跨行/跨空行」结构的文档。
     * 每一项都会同时跑「逐字符」与「逐行」两种前缀序列。
     */
    private val documents: List<Pair<String, String>> = listOf(
        "标题" to "# A\n## B\n### C\n\ntext\n",
        "围栏代码块" to "before\n\n```kotlin\nval x = 1\nfun f() = x\n```\nafter\n",
        "未闭合围栏" to "before\n\n```kotlin\nval x = 1\nfun f() = x\n",
        "无语言代码块" to "```\nx\ny\n```\n",
        "表格" to "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n\ntail\n",
        "嵌套列表与引用" to "> # H\n> text\n>\n> - a\n\n- x\n  - y\n- z\n\n1. one\n2. two\n",
        "跨空行续写的列表" to "- first\n\n  still first\n\n- second\n",
        "分割线" to "---\n\ntext\n\n***\n",
        "段落续行" to "para\n  continued\n\nnext\n",
        "任务列表" to "- [ ] todo\n- [x] done\n- plain\n",
        "CRLF 文档" to "# A\r\n\r\ntext\r\n\r\n- item\r\n",
        // M5-10 实测缺陷：带语言标签的围栏内部有空行。早先 safeSplit 的 fenceAt 只认裸围栏，
        // ```kotlin 被判成普通行 → 代码块内的空行被当成安全边界 → 冻结线切进代码块中间。
        "围栏内含空行" to "before\n\n```kotlin\nval x = 1\n\nfun f() = x\n```\nafter\n",
        "围栏内含空行且前面无空行" to "```kotlin\nval x = 1\n\nfun f() = x\n```\n",
        "波浪线围栏含语言与空行" to "~~~kotlin\nval x = 1\n\nfun f() = x\n~~~\n",
        "围栏内含更短的同类围栏行" to "```kotlin\nval x = 1\n``\n\nfun f() = x\n```\n",
        "围栏内含不同字符的围栏行" to "```kotlin\nval x = 1\n~~~\n\nfun f() = x\n```\n",
    )

    // ------------------------------------------------------------ 核心不变量

    @Test
    fun `逐字符前缀的 update 结果等于 MdParser_parse`() {
        for ((name, doc) in documents) {
            val stream = MdStream()
            for (end in 1..doc.length) {
                val prefix = doc.substring(0, end)
                assertSameBlocks(
                    expected = MdParser.parse(prefix),
                    actual = stream.update(prefix),
                    where = "文档「$name」逐字符前缀 len=$end",
                )
            }
        }
    }

    @Test
    fun `逐行前缀的 update 结果等于 MdParser_parse`() {
        for ((name, doc) in documents) {
            val stream = MdStream()
            val lines = doc.split("\n")
            val builder = StringBuilder()
            for ((index, line) in lines.withIndex()) {
                builder.append(line)
                if (index != lines.lastIndex) builder.append("\n")
                val prefix = builder.toString()
                assertSameBlocks(
                    expected = MdParser.parse(prefix),
                    actual = stream.update(prefix),
                    where = "文档「$name」逐行前缀 lines=${index + 1}",
                )
            }
        }
    }

    @Test
    fun `分块前缀的 update 结果等于 MdParser_parse`() {
        // 3 个字符一块，制造出落在围栏中间、表格中间、行内定界符中间的分割点。
        for ((name, doc) in documents) {
            val stream = MdStream()
            var end = 0
            while (end < doc.length) {
                end = minOf(end + 3, doc.length)
                val prefix = doc.substring(0, end)
                assertSameBlocks(
                    expected = MdParser.parse(prefix),
                    actual = stream.update(prefix),
                    where = "文档「$name」3 字符分块前缀 len=$end",
                )
            }
        }
    }

    @Test
    fun `前缀序列里每个块的区间都在当前文本范围内`() {
        // 增量坐标系一旦错位，跳转与高亮就会指向错误位置，这里一并守住。
        for ((name, doc) in documents) {
            val stream = MdStream()
            for (end in 1..doc.length) {
                val prefix = doc.substring(0, end)
                for (block in stream.update(prefix)) {
                    assertTrue(
                        "文档「$name」len=$end：${block::class.simpleName} startOffset=${block.startOffset} 必须 >= 0",
                        block.startOffset >= 0,
                    )
                    assertTrue(
                        "文档「$name」len=$end：${block::class.simpleName} endOffset=${block.endOffset} 必须 >= startOffset",
                        block.endOffset >= block.startOffset,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 缓存路径

    /**
     * 回归：**开围栏那一行绝不能被当成安全边界**。
     *
     * 这是 M5-3 实测抓到的真缺陷。增量续扫会把最后一个「可能还没写完的行」重扫一遍；
     * 修 bug 前没有回滚状态，于是同一个 `"```"` 先被当开围栏、重扫时又被当**闭**围栏，
     * 它后面的换行就被登记成安全分割点。于是 `"```\n"` 被冻结成一个空代码块，
     * 紧接着到来的 `x` 落到了冻结区之外、被解析成段落——而全文解析里 `x` 明明是代码块内容。
     *
     * 症状就是「流式输出时代码块的第一行掉出来变成一个独立段落」。
     * 文档「无语言代码块」在 [documents] 里已覆盖这条路径，这里再钉一个最小用例，
     * 以免以后精简文档列表时把它顺手删掉。
     */
    @Test
    fun `开围栏后的第一行不会被冻结出去`() {
        val stream = MdStream()
        // 逐字符走到 "```\nx"，每一步都与全文解析一致。
        val doc = "```\nx\n```\n"
        for (end in 1..doc.length) {
            val prefix = doc.substring(0, end)
            assertSameBlocks(
                expected = MdParser.parse(prefix),
                actual = stream.update(prefix),
                where = "开围栏文档 len=$end",
            )
        }
        // 关键节点单独断言：len=5 时必须仍是 1 个未闭合代码块（缺陷时是 2 块）。
        val at5 = stream.update("```\nx")
        assertEquals("len=5 时块数", 1, at5.size)
        val block = at5[0]
        assertTrue("必须是 CodeBlock，实际 ${block::class.simpleName}", block is MdBlock.CodeBlock)
        assertEquals("len=5 时代码块内容", "x", (block as MdBlock.CodeBlock).code)
        assertFalse("尚未闭合", block.closed)
    }

    /**
     * 回归：**带语言标签的开栏行必须被认成围栏，代码块内部的空行不能当安全边界**。
     *
     * 这是 M5-10 设备验收（T6）抓到的真缺陷，比 M5-3 那条更隐蔽：早先 `safeSplit` 自己写了
     * 一份 `fenceAt`，只认**裸**围栏（```` ``` ```` 后面必须只有空白），于是 ```` ```kotlin ````
     * 被判成普通行，状态机全程认为「不在围栏内」——代码块内部一出现空行就命中「围栏外空行」
     * 分支，把 `split` 推到**代码块中间**。冻结区因此含一个永不闭合的 CodeBlock 且永不重解析：
     * 后续的代码行进块外变成普通段落，头部标签永远显示「生成中…」。
     *
     * 修法：围栏判定只保留 [MdParser.fenceInfo] 一份——开栏认 info，闭栏要求同字符、
     * 不短于开栏、且 info 为空白。
     */
    @Test
    fun `带语言标签的围栏内部空行不会把冻结线切进代码块`() {
        val doc = "before\n\n```kotlin\nval x = 1\n\nfun f() = x\n```\nafter\n"
        val stream = MdStream()
        for (end in 1..doc.length) {
            val prefix = doc.substring(0, end)
            assertSameBlocks(
                expected = MdParser.parse(prefix),
                actual = stream.update(prefix),
                where = "围栏内含空行 len=$end",
            )
        }
        // 关键节点：走到代码块内部那个空行之后，绝不能多出一个段落。
        val insideBlank = "before\n\n```kotlin\nval x = 1\n\n"
        val blocks = stream.update(insideBlank)
        assertEquals("空行之后仍应是 2 块", 2, blocks.size)
        val code = blocks[1]
        assertTrue("第二块必须是 CodeBlock，实际 ${code::class.simpleName}", code is MdBlock.CodeBlock)
        assertEquals("代码必须还在代码块里", "val x = 1\n\n", (code as MdBlock.CodeBlock).code)
    }

    @Test
    fun `围栏闭合后生成的代码不会掉到块外`() {
        // T6 截图的形状：代码块闭合后又来一段普通文本，代码必须整块留在 CodeBlock 里。
        val stream = MdStream()
        val doc = "```kotlin\nfun f() = 1\n```\nafter\n"
        stream.update(doc)
        val blocks = stream.update(doc)
        assertEquals(2, blocks.size)
        val code = blocks[0]
        assertTrue(code is MdBlock.CodeBlock)
        assertTrue("闭合后不能再显示生成中", (code as MdBlock.CodeBlock).closed)
        assertEquals("fun f() = 1", code.code)
    }

    @Test
    fun `文本不变时返回同一个结果实例`() {
        val stream = MdStream()
        val first = stream.update("# A\n\ntext\n")
        val second = stream.update("# A\n\ntext\n")
        assertSame("文本没变必须直接复用缓存，不能再解析一遍", first, second)
    }

    @Test
    fun `CRLF 与 LF 视为同一内容并复用缓存`() {
        // update 先把 \r\n 归一化，所以两种写法归一化后相同，应命中缓存。
        val stream = MdStream()
        val first = stream.update("# A\n\ntext\n")
        val second = stream.update("# A\r\n\r\ntext\r\n")
        assertSame("归一化后内容相同，应命中缓存", first, second)
    }

    @Test
    fun `文本变化后返回新的结果`() {
        val stream = MdStream()
        val first = stream.update("# A\n")
        val second = stream.update("# A\n\ntext\n")
        assertNotSame("文本变了不能复用旧结果", first, second)
    }

    // ------------------------------------------------------------ 文本被整段改写

    @Test
    fun `文本不再以上次内容开头时按全文重解析`() {
        val stream = MdStream()
        stream.update("# A\n\ntext\n")
        // 重新生成：前缀完全不同。
        val replaced = "## B\n\nother\n"
        assertSameBlocks(MdParser.parse(replaced), stream.update(replaced), "整段改写")
    }

    @Test
    fun `文本被截断后仍等于全文解析结果`() {
        val stream = MdStream()
        stream.update("# A\n\ntext\n\n- a\n- b\n")
        // 删除尾部（用户编辑或重新生成回退）。
        val truncated = "# A\n\ntext\n"
        assertSameBlocks(MdParser.parse(truncated), stream.update(truncated), "截断")
    }

    @Test
    fun `反复整段改写不会污染冻结区`() {
        val stream = MdStream()
        val variants = listOf("alpha\n\nbeta\n", "gamma\n", "delta\n\nepsilon\n", "zeta\n")
        for (variant in variants) {
            assertSameBlocks(MdParser.parse(variant), stream.update(variant), "改写 <${variant.trim()}>")
        }
    }

    @Test
    fun `reset 之后从零开始且结果仍然正确`() {
        val stream = MdStream()
        stream.update("# A\n\ntext\n")
        stream.reset()
        val after = stream.update("# A\n\ntext\n")
        assertSameBlocks(MdParser.parse("# A\n\ntext\n"), after, "reset 后")
    }

    // ------------------------------------------------------------ 未闭合围栏

    @Test
    fun `流式中未闭合围栏是 closed=false 且后续补上闭合围栏后变为 true`() {
        val stream = MdStream()
        val open = "text\n\n```kotlin\nval x = 1\n"
        val openBlocks = stream.update(open)
        val openCode = openBlocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertFalse("围栏还没闭合时 closed 必须是 false", openCode.closed)
        assertEquals("kotlin", openCode.language)
        assertTrue("未闭合期间代码块必须包住已经收到的内容", openCode.code.contains("val x = 1"))

        // 逐个字符补上闭合围栏。
        val closed = "text\n\n```kotlin\nval x = 1\n```\n"
        for (end in open.length..closed.length) {
            val prefix = closed.substring(0, end)
            assertSameBlocks(MdParser.parse(prefix), stream.update(prefix), "闭合围栏过程 len=$end")
        }
        val closedCode = stream.update(closed).filterIsInstance<MdBlock.CodeBlock>().single()
        assertTrue("围栏闭合后 closed 必须变成 true", closedCode.closed)
        assertEquals("val x = 1", closedCode.code)
    }

    @Test
    fun `未闭合围栏后续行被算进代码块而不是新块`() {
        val stream = MdStream()
        stream.update("```\nline one\n")
        val blocks = stream.update("```\nline one\nline two\n")
        assertEquals("追加的行必须落进代码块，不能变成新段落", 1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertFalse(code.closed)
        assertEquals("line one\nline two\n", code.code)
    }

    @Test
    fun `围栏闭合后其后的新块被正常识别`() {
        val stream = MdStream()
        stream.update("```\ncode\n")
        val blocks = stream.update("```\ncode\n```\n\ntail\n")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.CodeBlock)
        assertTrue("闭合围栏之后应稳定成代码块", (blocks[0] as MdBlock.CodeBlock).closed)
        assertEquals("tail", (blocks[1] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `波浪号围栏在流式中同样被识别`() {
        val stream = MdStream()
        val blocks = stream.update("~~~py\nx = 1\n")
        val code = blocks.single() as MdBlock.CodeBlock
        assertFalse(code.closed)
        assertSameBlocks(MdParser.parse("~~~py\nx = 1\n"), blocks, "波浪号未闭合")
    }

    // ------------------------------------------------------------ 长文档

    @Test
    fun `长文档逐行流式与全文解析一致`() {
        val doc = buildString {
            append("# 长文档\n\n")
            for (i in 1..40) {
                append("段落 $i\n\n")
                if (i % 5 == 0) {
                    append("```kotlin\nval v$i = $i\n```\n\n")
                }
                if (i % 7 == 0) {
                    append("| h1 | h2 |\n| --- | --- |\n| $i | ${i + 1} |\n\n")
                }
                if (i % 3 == 0) {
                    append("- item $i\n- item ${i + 1}\n\n")
                }
            }
        }
        val stream = MdStream()
        val lines = doc.split("\n")
        val builder = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            builder.append(line)
            if (index != lines.lastIndex) builder.append("\n")
            val prefix = builder.toString()
            assertSameBlocks(MdParser.parse(prefix), stream.update(prefix), "长文档 len=${prefix.length}")
        }
        assertTrue("长文档应该切出足够多的块才有意义", MdParser.parse(doc).size > 20)
    }

    // ------------------------------------------------------------ 辅助

    /** 逐块比较，失败时能直接看出是哪一块、哪个字段不一致。 */
    private fun assertSameBlocks(expected: List<MdBlock>, actual: List<MdBlock>, where: String) {
        assertEquals("$where：块数量不一致", expected.size, actual.size)
        for (i in expected.indices) {
            assertSameBlock(expected[i], actual[i], "$where：第 $i 块")
        }
    }

    private fun assertSameBlock(expected: MdBlock, actual: MdBlock, where: String) {
        assertEquals("$where 类型不一致", expected::class, actual::class)
        assertEquals("$where startOffset 不一致", expected.startOffset, actual.startOffset)
        assertEquals("$where endOffset 不一致", expected.endOffset, actual.endOffset)
        when (expected) {
            is MdBlock.Paragraph -> assertSameInlines(
                expected.inlines,
                (actual as MdBlock.Paragraph).inlines,
                where,
            )
            is MdBlock.Heading -> {
                val a = actual as MdBlock.Heading
                assertEquals("$where level 不一致", expected.level, a.level)
                assertSameInlines(expected.inlines, a.inlines, where)
            }
            is MdBlock.CodeBlock -> {
                val a = actual as MdBlock.CodeBlock
                assertEquals("$where language 不一致", expected.language, a.language)
                assertEquals("$where code 不一致", expected.code, a.code)
                assertEquals("$where closed 不一致", expected.closed, a.closed)
            }
            is MdBlock.BulletList -> assertSameItems(
                expected.items,
                (actual as MdBlock.BulletList).items,
                where,
            )
            is MdBlock.OrderedList -> {
                val a = actual as MdBlock.OrderedList
                assertEquals("$where start 不一致", expected.start, a.start)
                assertSameItems(expected.items, a.items, where)
            }
            is MdBlock.Quote -> {
                val a = actual as MdBlock.Quote
                val e = expected.blocks
                assertEquals("$where 引用内块数量不一致", e.size, a.blocks.size)
                for (i in e.indices) assertSameBlock(e[i], a.blocks[i], "$where/quote 第 $i 块")
            }
            is MdBlock.Table -> {
                val a = actual as MdBlock.Table
                assertEquals("$where 表头列数不一致", expected.headers.size, a.headers.size)
                for (i in expected.headers.indices) {
                    assertSameInlines(expected.headers[i], a.headers[i], "$where 表头第 $i 列")
                }
                assertEquals("$where 数据行数不一致", expected.rows.size, a.rows.size)
                for (r in expected.rows.indices) {
                    assertEquals("$where 第 $r 行列数不一致", expected.rows[r].size, a.rows[r].size)
                    for (c in expected.rows[r].indices) {
                        assertSameInlines(expected.rows[r][c], a.rows[r][c], "$where 第 $r 行第 $c 列")
                    }
                }
            }
            is MdBlock.Rule -> Unit
        }
    }

    private fun assertSameItems(expected: List<MdListItem>, actual: List<MdListItem>, where: String) {
        assertEquals("$where 条目数不一致", expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals("$where 第 $i 条 checked 不一致", e.checked, a.checked)
            assertSameInlines(e.inlines, a.inlines, "$where 第 $i 条")
            assertEquals("$where 第 $i 条 children 数不一致", e.children.size, a.children.size)
            for (c in e.children.indices) {
                assertSameBlock(e.children[c], a.children[c], "$where 第 $i 条 children 第 $c 项")
            }
        }
    }

    private fun assertSameInlines(expected: List<MdInline>, actual: List<MdInline>, where: String) {
        assertEquals("$where 行内元素数量不一致", expected.size, actual.size)
        for (i in expected.indices) {
            // MdInline 全是 data class / data object，equals 已按结构比较。
            assertEquals("$where 第 $i 个行内元素不一致", expected[i], actual[i])
        }
    }
}
