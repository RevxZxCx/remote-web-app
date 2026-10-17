package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 助手消息左侧导轨必须**铺满整条消息**（R028b，用户 m44550 第一张图）。
 *
 * 用户原话：「助手消息的导轨没有铺满会话，可能是渲染bug」。
 *
 * ## 这条测试为什么必须放进 LazyColumn
 * 第一版把它直接摆在 `setContent` 的根上——那里高度是**有界**的，导轨`fillMaxHeight()`
 * 能正常工作，测试全绿；**真机上导轨却完全消失**。区别就在容器：
 *
 * - 渲染测试的根视图高度有界 → `fillMaxHeight()` 拿到一个具体的 maxHeight；
 * - 真实的聊天列表是 `LazyColumn` 的 item，高度约束是 `Infinity`
 *   → `fillMaxHeight()` 的语义退化成「取 maxHeight，否则取 minHeight」= **0**。
 *
 * 所以这条测试的容器**必须**是 LazyColumn，否则它守护的是一个真机上不存在的布局。
 * 这是本项目「测试全绿 ≠ 没有缺陷」的又一次实例（同 D46）。
 *
 * 修复：外层 Row 加 `height(IntrinsicSize.Min)`，把无界高度改写成「子项固有高度」，
 * `fillMaxHeight()` 才有东西可填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleRailRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** 在 LazyColumn（无界高度）里摆一条长消息，导轨必须跟着长。 */
    @Test
    fun `LazyColumn 里的长消息导轨铺满`() {
        compose.setContent {
            DshAssistantTheme {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(listOf(Unit)) {
                        Bubble(
                            alignEnd = false,
                            background = null,
                            time = 0L,
                            now = 0L,
                            wide = true,
                            railColor = MaterialTheme.colorScheme.outline,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                repeat(12) { i ->
                                    Text(
                                        text = "第 $i 行正文，用来把气泡撑高。",
                                        modifier = Modifier.fillMaxWidth().height(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val rail = compose.onNodeWithTag(ChatRailTestTag).getBoundsInRoot()
        val railHeight = rail.bottom.value - rail.top.value
        // 12 行 × 24dp ≈ 288dp。导轨允许比正文短一点（上下各 3dp padding），
        // 但只要它还在 0～14dp 那个量级，「铺满」就没做到。
        assertTrue(
            "导轨高度只有 ${railHeight}dp，没有铺满整条消息（正文约 288dp）" +
                "——多半是 LazyColumn 的无界高度把 fillMaxHeight() 压成了 0",
            railHeight > 200f,
        )
    }

    /** 短消息下导轨仍然存在（不能被 intrinsic 测量优化掉）。 */
    @Test
    fun `短消息也有导轨`() {
        compose.setContent {
            DshAssistantTheme {
                Column {
                    Bubble(
                        alignEnd = false,
                        background = null,
                        time = 0L,
                        now = 0L,
                        railColor = MaterialTheme.colorScheme.outline,
                    ) {
                        Text("短")
                    }
                }
            }
        }
        compose.onNodeWithTag(ChatRailTestTag).assertExists()
        compose.onNodeWithText("短").assertExists()
    }

    /** `railColor = null` 时不画导轨（用户气泡那条路径）。 */
    @Test
    fun `不传颜色就不画导轨`() {
        compose.setContent {
            DshAssistantTheme {
                Bubble(
                    alignEnd = true,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    time = 0L,
                    now = 0L,
                ) {
                    Text("用户消息")
                }
            }
        }
        compose.onNodeWithTag(ChatRailTestTag).assertDoesNotExist()
    }
}
