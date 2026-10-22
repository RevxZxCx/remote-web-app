package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.FileReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「引用」拼进正文的那一步（R043 第 2 条）。
 *
 * ## 为什么单独测这一行
 *
 * 审查 P1 抓到的真问题：`references` 在界面上渲染成 chip、点发送后却什么都没带上，
 * 因为 `send()` 读的是草稿，而拼后缀的 `composedText()` **一个调用点都没有**。
 * 功能看起来做好了、单测全绿、设备上 chip 也确实出现，只有消息本身是错的。
 *
 * 所以这里守两件事：① 拼接规则本身（有/无引用、空正文、多引用）；② 发送入口用的是
 * 这个函数（由 `ChatViewModelTest` 之外的 `send()` 接线保证——见 KDoc 说明）。
 */
class ComposerReferenceTextTest {

    private fun ref(path: String) = FileReference(path = path, isDirectory = false)

    @Test
    fun noReferencesLeavesDraftByteForByte() {
        // 没选引用时绝不能改动正文（包括尾随空格），否则用户看到的和发出去的不一致。
        assertEquals("hello  ", composeOutgoingText("hello  ", emptyList()))
    }

    @Test
    fun singleReferenceIsAppendedAfterDraft() {
        assertEquals(
            "看一下这个 @docs/R043-效果图.html",
            composeOutgoingText("看一下这个", listOf(ref("docs/R043-效果图.html"))),
        )
    }

    @Test
    fun multipleReferencesKeepSelectionOrder() {
        // 顺序即用户在选器里的点选顺序，不能排序——改了顺序等于改了语义。
        assertEquals(
            "@b.kt @a.kt",
            composeOutgoingText("", listOf(ref("b.kt"), ref("a.kt"))),
        )
    }

    @Test
    fun emptyDraftWithReferencesProducesOnlyTheSuffix() {
        // 只选引用、一个字都不打，也要能发出去（这条以前会因 text.isEmpty() 被拦）。
        assertEquals("@a.kt", composeOutgoingText("   ", listOf(ref("a.kt"))))
    }

    @Test
    fun trailingWhitespaceInDraftDoesNotDoubleTheSpace() {
        // 用户按了空格再选引用：不能出现「…  @a.kt」两个空格。
        assertEquals("看 @a.kt", composeOutgoingText("看  ", listOf(ref("a.kt"))))
    }

    @Test
    fun directoryReferenceLooksTheSameAsFileReference() {
        // 路径里带不带结尾斜杠都由服务端给的原样带出，这里只验不被改写。
        assertEquals(
            "@docs/",
            composeOutgoingText("", listOf(FileReference(path = "docs/", isDirectory = true))),
        )
    }
}
