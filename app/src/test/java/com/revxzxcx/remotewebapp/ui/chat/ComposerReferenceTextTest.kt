package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用以 `@path` 写进正文的那几个纯函数（R047 ③，用户 m32024）。
 *
 * ## 为什么这一版要重写
 *
 * R043 的引用是「另存一份列表、发送时拼后缀」，所以当时的测试守的是
 * `composeOutgoingText`。那个设计在界面上表现为输入框**下面一排**胶囊：
 * 点掉胶囊不影响正文，正文里也看不到引用 —— 用户 m32024 第 3 条要求照 DSH
 * 把引用渲染在正文里，于是整个模型换成了「引用就是正文里的 token」。
 *
 * 现在守三件事：
 * - [insertReferenceToken]：插到末尾、去重、不重复加空格；
 * - [removeReferenceToken]：按词边界删、连前面的空格一起删、不误伤更长 token；
 * - [referenceTokens]：从正文反算回「选了哪几个引用」（选择器回显要用）。
 *
 * 这三条都是纯字符串函数，不碰 Android，所以能直接跑 JVM 单测。
 */
class ComposerReferenceTextTest {

    // ---- insertReferenceToken ----

    @Test
    fun emptyDraftBecomesBareToken() {
        assertEquals("@docs/a.md", insertReferenceToken("", "docs/a.md"))
    }

    @Test
    fun tokenIsAppendedAfterOneSpace() {
        assertEquals("看一下 @docs/a.md", insertReferenceToken("看一下", "docs/a.md"))
    }

    @Test
    fun trailingWhitespaceDoesNotDoubleTheSpace() {
        // 用户按了空格再点引用：不能出现「看  @a.kt」两个空格。
        assertEquals("看 @docs/a.md", insertReferenceToken("看  ", "docs/a.md"))
    }

    @Test
    fun samePathIsNotInsertedTwice() {
        // 同一个文件引用两次没有意义（服务端会解析出两段），去重按完整 token 判。
        assertEquals("@docs/a.md", insertReferenceToken("@docs/a.md", "docs/a.md"))
    }

    @Test
    fun blankPathLeavesDraftUntouched() {
        // 目录行的 path 理论上不会是空串，但真出现时不能往正文里塞一个光秃秃的 `@`。
        assertEquals("abc", insertReferenceToken("abc", ""))
    }

    @Test
    fun longerTokenWithSamePrefixIsNotTreatedAsDuplicate() {
        // `@docs/a.md.bak` 与 `@docs/a.md` 是两个不同文件：前者存在时仍要能插后者。
        assertEquals(
            "@docs/a.md.bak @docs/a.md",
            insertReferenceToken("@docs/a.md.bak", "docs/a.md"),
        )
    }

    // ---- removeReferenceToken ----

    @Test
    fun removingMiddleTokenTakesTheLeadingSpace() {
        // 删中间那个：连同前面那一个空格一起删，否则正文里会留下两个空格。
        assertEquals("看 @b.md", removeReferenceToken("看 @a.md @b.md", "a.md"))
    }

    @Test
    fun removingFirstTokenLeavesNoLeadingSpace() {
        assertEquals("@b.md", removeReferenceToken("@a.md @b.md", "a.md"))
    }

    @Test
    fun removingOnlyTokenYieldsEmpty() {
        assertEquals("", removeReferenceToken("@a.md", "a.md"))
    }

    @Test
    fun removalDoesNotTouchLongerToken() {
        // 关键边界：删 `@docs/a.md` 不能把 `@docs/a.md.bak` 削成 `.bak`。
        assertEquals(
            "@docs/a.md.bak",
            removeReferenceToken("@docs/a.md.bak", "docs/a.md"),
        )
    }

    @Test
    fun removalOfAbsentTokenIsIdentity() {
        assertEquals("看 @b.md", removeReferenceToken("看 @b.md", "a.md"))
    }

    // ---- referenceTokens ----

    @Test
    fun tokensAreScannedBackFromText() {
        // 选择器回显「已选」靠的就是这个反算：正文里有什么，胶囊就显示什么。
        assertEquals(
            listOf("docs/a.md", "docs/b.md"),
            referenceTokens("看 @docs/a.md 和 @docs/b.md").map { it.path },
        )
    }

    @Test
    fun duplicateTokensCollapseToOne() {
        assertEquals(1, referenceTokens("@a.md @a.md").size)
    }

    @Test
    fun emailIsNotAReference() {
        // `a@b.com` 里的 `@` 前面是字母，不构成引用 —— 否则正文里写个邮箱就冒出胶囊。
        assertTrue(referenceTokens("发到 a@b.com").isEmpty())
    }

    @Test
    fun plainTextHasNoTokens() {
        assertTrue(referenceTokens("没有引用的普通文字").isEmpty())
    }

    @Test
    fun tokenAtLineStartIsRecognised() {
        // `@` 在行首（或在换行之后）也要算引用。
        assertEquals(listOf("a.md"), referenceTokens("@a.md").map { it.path })
    }

    @Test
    fun directoryTokenKeepsTrailingSlashAndIsMarked() {
        val tokens = referenceTokens("看看 @docs/")
        assertEquals(listOf("docs/"), tokens.map { it.path })
        assertTrue(tokens.single().isDirectory)
        assertFalse(referenceTokens("@a.md").single().isDirectory)
    }

    // ---- inlineHighlightRanges（审查 R047 P2-4：这个函数此前零单测）----
    //
    // 它是「界面上染哪几段」的唯一判据，而用户 m32024 点名要审的边界（多行正文、
    // `@` 在行尾、全角标点相邻、`/` 不在行首）恰恰全在这一层。`referenceTokens`
    // 只是它的转调，测后者不等于测它。

    @Test
    fun `命令只在正文第一位才算`() {
        assertEquals(
            listOf(InlineSpan(0, 7, InlineSpanKind.Command)),
            inlineHighlightRanges("/export hi"),
        )
        // 正文中段的 `/` 是路径分隔符，不是命令。
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("看 docs/a.md"))
        // 光一个 `/` 不染色。
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("/"))
    }

    @Test
    fun `多行正文里第二行的斜杠不是命令`() {
        val spans = inlineHighlightRanges("hello" + "\n" + "/export")
        assertEquals(emptyList<InlineSpan>(), spans)
    }

    @Test
    fun `命令与引用可以在同一段正文里各占一段`() {
        val spans = inlineHighlightRanges("/export @docs/a.md")
        assertEquals(
            listOf(
                InlineSpan(0, 7, InlineSpanKind.Command),
                InlineSpan(8, 18, InlineSpanKind.Reference),
            ),
            spans,
        )
    }

    @Test
    fun `引用后面紧跟全角标点时只染到标点之前`() {
        // 审查 R047 P2-2 的原例：中文不会在标点后打空格。
        val spans = inlineHighlightRanges("看 @docs/a.md，这里有问题")
        assertEquals(listOf(InlineSpan(2, 12, InlineSpanKind.Reference)), spans)
    }

    @Test
    fun `引用在行尾也能染到`() {
        assertEquals(
            listOf(InlineSpan(0, 5, InlineSpanKind.Reference)),
            inlineHighlightRanges("@a.md"),
        )
        assertEquals(
            listOf(InlineSpan(3, 8, InlineSpanKind.Reference)),
            inlineHighlightRanges("看 " + "\n" + "@a.md"),
        )
    }

    @Test
    fun `邮箱不算引用`() {
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("a@b.com"))
        // 前面非单词字符则算。
        assertEquals(
            listOf(InlineSpan(2, 7, InlineSpanKind.Reference)),
            inlineHighlightRanges("( @a.md)"),
        )
    }

    @Test
    fun `光秃秃的 at 不染色`() {
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("@"))
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("看@ 这里"))
    }

    @Test
    fun `中文紧邻引用不会把中文吞进去`() {
        // `@` 前是中文（单词字符）时不算引用开头——避免「联系我@某人」被误染。
        assertEquals(emptyList<InlineSpan>(), inlineHighlightRanges("联系我@某人"))
    }
}
