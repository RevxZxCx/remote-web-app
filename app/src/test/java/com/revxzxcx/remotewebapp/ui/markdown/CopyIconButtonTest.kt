package com.revxzxcx.remotewebapp.ui.markdown

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复制按钮（用户 m45428 第 6 条）。
 *
 * 用户原话：「对话界面应该要可以复制，并且，一些代码块也应该要有独立的复制按钮，
 * 你添加按钮的时候注意与整体布局的协调，添加的按钮应该要用复制的图标展示而不是文字按钮」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CopyIconButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clipboardText(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    @Test
    fun `点一下把文本写进剪贴板`() {
        compose.setContent {
            DshAssistantTheme {
                CopyIconButton(text = "val x = 1", contentDescription = "复制代码")
            }
        }
        compose.onNodeWithContentDescription("复制代码").performClick()
        compose.waitForIdle()
        assertEquals("val x = 1", clipboardText())
    }

    /** 空文本不写剪贴板（Android 13+ 会闪一个空白浮层，看起来像出错）。 */
    @Test
    fun `空文本不写剪贴板`() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("t", "sentinel"))
        copyToClipboard(context, "")
        assertEquals("sentinel", clipboardText())
    }

    /** `contentDescription` 必须可用于定位（M5 验收与渲染测试都靠它）。 */
    @Test
    fun `按钮带可访问名`() {
        compose.setContent {
            DshAssistantTheme {
                CopyIconButton(text = "x", contentDescription = "复制消息")
            }
        }
        compose.onNodeWithContentDescription("复制消息").assertExists()
    }
}
