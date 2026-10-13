package com.revxzxcx.remotewebapp.ui.markdown

import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MdCodeHighlight] 纯逻辑部分的 JVM 单测。
 *
 * 为什么重点测 [MdCodeHighlight.runs]：库给的 token 区间**可以互相覆盖**（`MdCodeHighlight.kt:26-29`），
 * 逐个往 `AnnotatedString` 上叠加会因为区间交叉而让「后加的语义被先加的盖住」。
 * 所以实现先把区间压成**逐字符着色表**（后写覆盖先写），再合并成连续同色的色块。
 * 这套「后写覆盖先写 + 合并相邻同色」的规则是本文件的断言对象——它一旦变了，
 * 代码块的配色就会整体错位。
 *
 * 这里只调用纯函数 [MdCodeHighlight.runs] 与 [MdCodeHighlight.languageOf]；
 * `tokenize()` / `theme()` 需要库在运行时的分词器，不在单测范围内。
 */
class MdCodeHighlightTest {

    private val red = 0x00FF0000
    private val blue = 0x000000FF

    /** 库给的是 `0xRRGGBB`，实现会或上不透明 alpha。 */
    private val opaqueRed = 0xFFFF0000.toInt()
    private val opaqueBlue = 0xFF0000FF.toInt()

    // ------------------------------------------------------------ 空输入

    @Test
    fun `codeLength 非正时返回空列表`() {
        assertTrue("codeLength = 0 应该没有任何色块", MdCodeHighlight.runs(0, emptyList()).isEmpty())
        assertTrue(
            "codeLength < 0 应该没有任何色块",
            MdCodeHighlight.runs(-5, listOf(ColorHighlight(PhraseLocation(0, 3), red))).isEmpty(),
        )
    }

    @Test
    fun `没有高亮时返回一整段基础色块`() {
        // 注意：这里**不是**空列表。实现（MdCodeHighlight.kt:118-129）会扫完整个
        // [0, codeLength) 生成连续的色块，没有高亮的位置就是 argb == null 的基础色块。
        // 对 annotate() 而言这是对的：它需要一个覆盖全文的区间来画基础色。
        val runs = MdCodeHighlight.runs(10, emptyList())

        assertEquals("应该是一整段覆盖全文的基础色块", 1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(10, runs[0].end)
        assertEquals("没有高亮的位置 argb 必须是 null（表示用基础色）", null, runs[0].argb)
        assertFalse(runs[0].bold)
    }

    // ------------------------------------------------------------ 单区间

    @Test
    fun `单个高亮产生三段色块且中间那段精确覆盖区间`() {
        val runs = MdCodeHighlight.runs(8, listOf(ColorHighlight(PhraseLocation(2, 5), red)))

        assertEquals("基础色 + 高亮 + 基础色", 3, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 2, null, false), runs[0])
        assertEquals(MdCodeHighlight.Run(2, 5, opaqueRed, false), runs[1])
        assertEquals(MdCodeHighlight.Run(5, 8, null, false), runs[2])
    }

    @Test
    fun `区间覆盖全文时只有一段`() {
        val runs = MdCodeHighlight.runs(4, listOf(ColorHighlight(PhraseLocation(0, 4), red)))
        assertEquals(1, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 4, opaqueRed, false), runs[0])
    }

    // ------------------------------------------------------------ 合并规则

    @Test
    fun `相邻同色高亮合并成一段`() {
        // 这正是压缩的意义：库会把一个 token 拆成多段相邻区间给过来。
        val runs = MdCodeHighlight.runs(
            6,
            listOf(
                ColorHighlight(PhraseLocation(0, 3), red),
                ColorHighlight(PhraseLocation(3, 6), red),
            ),
        )
        assertEquals("相邻同色必须合并成一段", 1, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 6, opaqueRed, false), runs[0])
    }

    @Test
    fun `相邻不同色保持为两段`() {
        val runs = MdCodeHighlight.runs(
            6,
            listOf(
                ColorHighlight(PhraseLocation(0, 3), red),
                ColorHighlight(PhraseLocation(3, 6), blue),
            ),
        )
        assertEquals("颜色不同不能合并", 2, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 3, opaqueRed, false), runs[0])
        assertEquals(MdCodeHighlight.Run(3, 6, opaqueBlue, false), runs[1])
    }

    @Test
    fun `同一区间同时着色与加粗时合成一段`() {
        // 合并条件是「颜色与 bold 都相同」（MdCodeHighlight.kt:121）；
        // 两条高亮落在同一区间时，逐字符表里颜色和 bold 都被打上，所以只剩一段。
        val runs = MdCodeHighlight.runs(
            3,
            listOf(
                ColorHighlight(PhraseLocation(0, 3), red),
                BoldHighlight(PhraseLocation(0, 3)),
            ),
        )
        assertEquals("整段既着色又加粗，仍是一段", 1, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 3, opaqueRed, true), runs[0])
    }

    @Test
    fun `加粗范围不重合时颜色段被切成三份`() {
        // 颜色覆盖全文、加粗只盖中间 → 粗细分界处必须断开（MdCodeHighlight.kt:121 的第二个条件）。
        val runs = MdCodeHighlight.runs(
            5,
            listOf(
                ColorHighlight(PhraseLocation(0, 5), red),
                BoldHighlight(PhraseLocation(1, 3)),
            ),
        )
        assertEquals(3, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 1, opaqueRed, false), runs[0])
        assertEquals(MdCodeHighlight.Run(1, 3, opaqueRed, true), runs[1])
        assertEquals(MdCodeHighlight.Run(3, 5, opaqueRed, false), runs[2])
    }

    // ------------------------------------------------------------ 覆盖规则（后写覆盖先写）

    @Test
    fun `区间重叠时后出现的条目按字符覆盖先出现的`() {
        // red 0..4 先写，blue 2..6 后写 → 2..6 全部是蓝色，0..2 保持红色。
        val runs = MdCodeHighlight.runs(
            6,
            listOf(
                ColorHighlight(PhraseLocation(0, 4), red),
                ColorHighlight(PhraseLocation(2, 6), blue),
            ),
        )
        assertEquals(2, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 2, opaqueRed, false), runs[0])
        assertEquals(MdCodeHighlight.Run(2, 6, opaqueBlue, false), runs[1])
    }

    @Test
    fun `调换顺序会得到不同的结果证明是后写覆盖先写`() {
        val runs = MdCodeHighlight.runs(
            6,
            listOf(
                ColorHighlight(PhraseLocation(2, 6), blue),
                ColorHighlight(PhraseLocation(0, 4), red),
            ),
        )
        assertEquals(2, runs.size)
        assertEquals("后写的红色盖住了 2..4", MdCodeHighlight.Run(0, 4, opaqueRed, false), runs[0])
        assertEquals(MdCodeHighlight.Run(4, 6, opaqueBlue, false), runs[1])
    }

    @Test
    fun `小区间被大区间包住时按字符切开三段`() {
        // red 0..5 整段打底，blue 1..3 精确覆盖中间三格。
        val runs = MdCodeHighlight.runs(
            5,
            listOf(
                ColorHighlight(PhraseLocation(0, 5), red),
                ColorHighlight(PhraseLocation(1, 3), blue),
            ),
        )
        assertEquals(3, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 1, opaqueRed, false), runs[0])
        assertEquals(MdCodeHighlight.Run(1, 3, opaqueBlue, false), runs[1])
        assertEquals(MdCodeHighlight.Run(3, 5, opaqueRed, false), runs[2])
    }

    // ------------------------------------------------------------ 间隙与加粗

    @Test
    fun `高亮之间没有覆盖到的位置是基础色块`() {
        val runs = MdCodeHighlight.runs(
            6,
            listOf(
                ColorHighlight(PhraseLocation(0, 2), red),
                ColorHighlight(PhraseLocation(4, 6), blue),
            ),
        )
        assertEquals(3, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 2, opaqueRed, false), runs[0])
        assertEquals("2..4 没有高亮，argb 必须是 null", MdCodeHighlight.Run(2, 4, null, false), runs[1])
        assertEquals(MdCodeHighlight.Run(4, 6, opaqueBlue, false), runs[2])
    }

    @Test
    fun `BoldHighlight 产生 bold 为 true 的色块`() {
        val runs = MdCodeHighlight.runs(6, listOf(BoldHighlight(PhraseLocation(1, 4))))

        assertEquals(3, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 1, null, false), runs[0])
        assertEquals(MdCodeHighlight.Run(1, 4, null, true), runs[1])
        assertEquals(MdCodeHighlight.Run(4, 6, null, false), runs[2])
    }

    // ------------------------------------------------------------ 越界与畸形区间

    @Test
    fun `越界区间被裁剪且不会抛异常`() {
        val runs = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(-5, 100), red)))

        assertEquals("越界不应崩，也不应产生范围外的色块", 1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(6, runs[0].end)
        assertEquals(opaqueRed, runs[0].argb)
        assertRunsWithinBounds(runs, 6)
    }

    @Test
    fun `完全在左边界外的区间被忽略`() {
        val runs = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(-5, -1), red)))
        assertEquals(1, runs.size)
        assertEquals("全部落在范围外时只剩基础色块", MdCodeHighlight.Run(0, 6, null, false), runs[0])
    }

    @Test
    fun `完全在右边界外的区间被忽略`() {
        val runs = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(10, 20), red)))
        assertEquals(1, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 6, null, false), runs[0])
    }

    @Test
    fun `起点不小于终点的区间被跳过`() {
        val reversed = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(4, 2), red)))
        assertEquals("end < start 应该被忽略", MdCodeHighlight.Run(0, 6, null, false), reversed.single())

        val zero = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(2, 2), red)))
        assertEquals("零长度区间应该被忽略", MdCodeHighlight.Run(0, 6, null, false), zero.single())
    }

    @Test
    fun `越界区间部分相交时只裁剪到有效范围`() {
        val runs = MdCodeHighlight.runs(6, listOf(ColorHighlight(PhraseLocation(4, 100), red)))
        assertEquals(2, runs.size)
        assertEquals(MdCodeHighlight.Run(0, 4, null, false), runs[0])
        assertEquals(MdCodeHighlight.Run(4, 6, opaqueRed, false), runs[1])
    }

    // ------------------------------------------------------------ 颜色编码

    @Test
    fun `库的 0xRRGGBB 会被补上不透明 alpha`() {
        val runs = MdCodeHighlight.runs(3, listOf(ColorHighlight(PhraseLocation(0, 3), 0x00123456)))
        assertEquals("必须补成 0xFF123456", 0xFF123456.toInt(), runs.single().argb)
    }

    @Test
    fun `黑色 0 的 rgb 不会被当成未着色哨兵`() {
        // 实现用 -1 当哨兵而不是 0，正是因为 0x00000000 是合法的「透明黑」。
        val runs = MdCodeHighlight.runs(3, listOf(ColorHighlight(PhraseLocation(0, 1), 0)))
        assertEquals(2, runs.size)
        assertEquals("rgb=0 应变成不透明黑", 0xFF000000.toInt(), runs[0].argb)
        assertEquals("其余位置仍是基础色", null, runs[1].argb)
    }

    @Test
    fun `已经带 alpha 的 rgb 保持原样`() {
        val runs = MdCodeHighlight.runs(3, listOf(ColorHighlight(PhraseLocation(0, 3), 0xFF00FF00.toInt())))
        assertEquals(0xFF00FF00.toInt(), runs.single().argb)
    }

    // ------------------------------------------------------------ 结构不变量

    @Test
    fun `色块永远连续升序且完整覆盖全文`() {
        val cases = listOf(
            10 to emptyList(),
            6 to listOf(ColorHighlight(PhraseLocation(0, 4), red), ColorHighlight(PhraseLocation(2, 6), blue)),
            9 to listOf(
                BoldHighlight(PhraseLocation(0, 9)),
                ColorHighlight(PhraseLocation(3, 6), red),
                ColorHighlight(PhraseLocation(5, 8), blue),
            ),
            1 to listOf(ColorHighlight(PhraseLocation(0, 1), red)),
        )
        for ((length, highlights) in cases) {
            val runs = MdCodeHighlight.runs(length, highlights)
            assertRunsWithinBounds(runs, length)
            assertEquals("第一段必须从 0 开始", 0, runs.first().start)
            assertEquals("最后一段必须到 codeLength 结束", length, runs.last().end)
            for (i in 1 until runs.size) {
                assertEquals("第 $i 段必须紧接上一段（不允许有洞或重叠）", runs[i - 1].end, runs[i].start)
                assertTrue("第 $i 段长度必须为正", runs[i].end > runs[i].start)
                // 相邻段必须真的不同，否则合并逻辑漏了。
                assertTrue(
                    "相邻两段不应同色同粗细（说明合并逻辑有漏）",
                    runs[i - 1].argb != runs[i].argb || runs[i - 1].bold != runs[i].bold,
                )
            }
        }
    }

    // ------------------------------------------------------------ languageOf

    @Test
    fun `语言别名映射到枚举`() {
        assertEquals(SyntaxLanguage.SHELL, MdCodeHighlight.languageOf("sh"))
        assertEquals(SyntaxLanguage.SHELL, MdCodeHighlight.languageOf("bash"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, MdCodeHighlight.languageOf("js"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, MdCodeHighlight.languageOf("ts"))
        assertEquals(SyntaxLanguage.KOTLIN, MdCodeHighlight.languageOf("kt"))
        assertEquals(SyntaxLanguage.KOTLIN, MdCodeHighlight.languageOf("kts"))
        assertEquals(SyntaxLanguage.PYTHON, MdCodeHighlight.languageOf("py"))
        assertEquals(SyntaxLanguage.RUST, MdCodeHighlight.languageOf("rs"))
        assertEquals(SyntaxLanguage.CSHARP, MdCodeHighlight.languageOf("c#"))
        assertEquals(SyntaxLanguage.CPP, MdCodeHighlight.languageOf("c++"))
        assertEquals(SyntaxLanguage.C, MdCodeHighlight.languageOf("h"))
        assertEquals(SyntaxLanguage.GO, MdCodeHighlight.languageOf("golang"))
        assertEquals(SyntaxLanguage.PHP, MdCodeHighlight.languageOf("php"))
        assertEquals(SyntaxLanguage.COFFEESCRIPT, MdCodeHighlight.languageOf("coffee"))
        assertEquals(SyntaxLanguage.RUBY, MdCodeHighlight.languageOf("ruby"))
    }

    @Test
    fun `语言标签会 trim 转小写并去掉开头的点`() {
        assertEquals(SyntaxLanguage.KOTLIN, MdCodeHighlight.languageOf("  KOTLIN  "))
        assertEquals(SyntaxLanguage.KOTLIN, MdCodeHighlight.languageOf(".kt"))
        assertEquals(SyntaxLanguage.PYTHON, MdCodeHighlight.languageOf(" .PY "))
    }

    @Test
    fun `无法识别的语言落到 DEFAULT 而不是抛异常`() {
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf(null))
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf(""))
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf("   "))
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf("."))
        // json/yaml 没有专门支持，DEFAULT 是通用分词器，仍比不高亮可读。
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf("json"))
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf("yaml"))
        assertEquals(SyntaxLanguage.DEFAULT, MdCodeHighlight.languageOf("某种未知语言"))
    }

    @Test
    fun `长度上限常量是两万`() {
        assertEquals(20_000, MdCodeHighlight.MAX_HIGHLIGHT_CHARS)
    }

    /**
     * 哨兵不能与任何合法颜色相撞（R005 P3 补遗）。
     *
     * 库给的是 `0xRRGGBB`，本实现统一 `or 0xFF000000`。若哨兵取 `-1`，纯白
     * `0xFFFFFF or OPAQUE` 恰好也等于 `-1`，会被误判成「这个字符没打过分」而
     * 退回基础色 —— 白色 token（暗色主题下很常见）静默消失。
     */
    @Test
    fun `纯白 token 不会被误判成未着色`() {
        val highlights = listOf(ColorHighlight(PhraseLocation(0, 3), 0xFFFFFF))
        val runs = MdCodeHighlight.runs(3, highlights)

        assertEquals("应与相邻未着色部分合并成一块", 1, runs.size)
        assertEquals("纯白是合法颜色，不能是 null", 0xFFFFFFFF.toInt(), runs[0].argb)
    }

    @Test
    fun `超过上限的代码块应当整段跳过高亮`() {
        val long = "x".repeat(MdCodeHighlight.MAX_HIGHLIGHT_CHARS + 1)
        assertTrue(
            "超长时 tokenize 必须直接返回空表",
            MdCodeHighlight.tokenize(long, SyntaxLanguage.KOTLIN, darkMode = true).isEmpty(),
        )
    }

    // ------------------------------------------------------------ 辅助

    private fun assertRunsWithinBounds(runs: List<MdCodeHighlight.Run>, codeLength: Int) {
        for (run in runs) {
            assertTrue("色块起点 ${run.start} 必须 >= 0", run.start >= 0)
            assertTrue("色块终点 ${run.end} 必须 <= codeLength=$codeLength", run.end <= codeLength)
            assertTrue("色块 ${run.start}..${run.end} 必须非空", run.end > run.start)
        }
    }
}
