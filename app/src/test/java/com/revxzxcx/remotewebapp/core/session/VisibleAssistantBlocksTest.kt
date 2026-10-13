package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [visibleAssistantBlocks] 的单测。
 *
 * 这条规则原先只写在 `ChatScreen` 的 Composable 里，于是**没有任何测试覆盖**——
 * 而它决定「每次工具调用会不会渲染两遍」。M2 的教训正是「数据层断言对了、UI 层照样
 * 可能重复渲染」：把规则提到顶层纯函数，才可能在 195 项纯逻辑单测里钉住它。
 */
class VisibleAssistantBlocksTest {

    private val text = ContentBlock.Text("正文")
    private val reasoning = ContentBlock.Reasoning("想想")
    private val toolCall = ContentBlock.ToolCall("c1", "read", """{"path":"a.md"}""")

    @Test
    fun `滤掉工具调用块`() {
        val visible = visibleAssistantBlocks(listOf(text, toolCall), interrupted = false)
        assertEquals(listOf<ContentBlock>(text), visible)
    }

    @Test
    fun `保留文本与思考块`() {
        val visible = visibleAssistantBlocks(listOf(reasoning, text), interrupted = false)
        assertEquals(listOf<ContentBlock>(reasoning, text), visible)
    }

    @Test
    fun `只含工具调用时结果为空 —— 该助手消息不该占一行`() {
        assertTrue(visibleAssistantBlocks(listOf(toolCall), interrupted = false).isEmpty())
    }

    @Test
    fun `被中止的消息保留工具调用块`() {
        // 中止时可能没有对应的 tool/call 事件，滤掉就真的丢内容了。
        val visible = visibleAssistantBlocks(listOf(toolCall), interrupted = true)
        assertEquals(listOf<ContentBlock>(toolCall), visible)
    }

    @Test
    fun `被中止的消息连文本一起保留`() {
        val visible = visibleAssistantBlocks(listOf(text, toolCall), interrupted = true)
        assertEquals(listOf<ContentBlock>(text, toolCall), visible)
    }

    @Test
    fun `空块列表结果为空`() {
        assertTrue(visibleAssistantBlocks(emptyList(), interrupted = false).isEmpty())
        assertTrue(visibleAssistantBlocks(emptyList(), interrupted = true).isEmpty())
    }
}
