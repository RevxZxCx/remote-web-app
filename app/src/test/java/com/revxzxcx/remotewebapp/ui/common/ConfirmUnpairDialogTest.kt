package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 共享「退出配对」确认框的行为测试（R018 P1-1）。
 *
 * 这个框 R018 之后由 `AppRoot` 层渲染一次、三屏共用。早前 HomeScreen 与
 * SettingsScreen 各渲染一份，而 **Home 错误横幅与两屏 ConnectionBanner 的
 * 「重新配对」按钮传的是同一个不带确认的 onUnpair**——五个入口、两处防护，
 * 误触一次就清掉本机凭证，而配对只能回桌面端重新发起。
 *
 * 这里钉住的是「框本身」的契约：确认才执行、取消不执行、文案把代价说清楚。
 * 「三屏的入口都接到这个框」由 AppRoot 的接线保证（见 AppRoot.kt 的 KDoc）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfirmUnpairDialogTest {

    @get:Rule
    val compose = createComposeRule()

    /** 模拟 AppRoot 的接线：visible 由调用方持有，框只负责问一次。 */
    @Composable
    private fun Harness(onConfirm: () -> Unit) {
        var visible by remember { mutableStateOf(true) }
        if (visible) {
            ConfirmUnpairDialog(onConfirm = onConfirm, onDismiss = { visible = false })
        }
    }

    @Test
    fun 点退出配对才真的执行() {
        var calls = 0
        compose.setContent { DshAssistantTheme { Harness(onConfirm = { calls++ }) } }

        compose.onNodeWithText("退出配对？").assertIsDisplayed()
        // 刚打开时什么都没发生。
        assertEquals(0, calls)

        // 对话框是独立窗口，节点在语义树的另一棵树上，直接按文本找即可。
        compose.onNodeWithText("退出配对").performClick()
        assertEquals(1, calls)
    }

    @Test
    fun 点取消不执行且框消失() {
        var calls = 0
        compose.setContent { DshAssistantTheme { Harness(onConfirm = { calls++ }) } }

        compose.onNodeWithText("取消").performClick()

        assertEquals(0, calls)
        compose.onNodeWithText("退出配对？").assertDoesNotExist()
    }

    @Test
    fun 文案必须说清代价() {
        // 确认框存在的意义就是这句「得回电脑」。少了它，用户点掉的只是
        // 一个标题，而不是一次真实的损失。
        compose.setContent { DshAssistantTheme { Harness(onConfirm = {}) } }

        val nodes = compose.onAllNodesWithText("桌面端", substring = true)
            .fetchSemanticsNodes()
        assertTrue("确认框必须提到要回桌面端重新配对", nodes.isNotEmpty())
    }
}
