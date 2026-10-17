package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 折叠分组内的思考过程用行式（用户 m45428 第 2 条）。
 *
 * 用户原话：「在折叠界面中的工具调用以及读写操作等，都应用了新的样式，但是，里面的
 * 思考过程的样式还是和之前一样，显得有些突兀。我希望，被折叠进去的思考过程，应该采用和
 * 其他工具一样的样式。需要注意，没有被折叠的思考过程保持原有的样式即可。」
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReasoningRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `行式思考显示 chip 与字数`() {
        compose.setContent {
            DshAssistantTheme {
                ReasoningRow(
                    text = "先读一遍文件，再决定怎么改。\n第二行不该出现在摘要里。",
                    showTopDivider = false,
                )
            }
        }
        // chip 与工具行一致：裸文本「思考过程」。
        compose.onNodeWithText("思考过程").assertExists()
        // 摘要取首行。
        compose.onNodeWithText("先读一遍文件，再决定怎么改。", substring = true).assertExists()
        // 字数 tag。
        compose.onNodeWithText("${"先读一遍文件，再决定怎么改。\n第二行不该出现在摘要里。".length} 字").assertExists()
        // 默认收起：正文第二行不在语义树里。
        compose.onNodeWithText("第二行不该出现在摘要里。").assertDoesNotExist()
    }

    @Test
    fun `点一下展开正文`() {
        compose.setContent {
            DshAssistantTheme {
                Column {
                    ReasoningRow(text = "第一行\n第二行正文", showTopDivider = false)
                }
            }
        }
        compose.onNodeWithText("第一行", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("第一行\n第二行正文").assertExists()
    }

    @Test
    fun `空文本不渲染`() {
        compose.setContent {
            DshAssistantTheme {
                ReasoningRow(text = "   ", showTopDivider = false)
            }
        }
        compose.onNodeWithText("思考过程").assertDoesNotExist()
    }

    @Test
    fun `预览取第一个非空行并归一空白`() {
        assertEquals("a b", reasoningPreview("\n\n  a   b  \n第二行"))
        assertEquals("", reasoningPreview("   \n\n  "))
        // 60 个字符 + 省略号 = 61。这条断言同时钉住「截断长度」与「省略号」两件事。
        assertEquals(61, reasoningPreview("x".repeat(200)).length)
    }

    @Test
    fun `超长首行被截断并加省略号`() {
        val long = "x".repeat(200)
        val preview = reasoningPreview(long)
        assertTrue("应以省略号结尾，实际「${preview.takeLast(5)}」", preview.endsWith("…"))
    }
}
