package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
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
 * 助手消息左侧导轨的回归守卫（用户 m44550 第 1 条 / m45428 第 7 条）。
 *
 * ## 为什么这个测试只验「内容没被截断」而不验导轨像素
 * 导轨现在由 `Modifier.drawBehind` 绘制——它**不是语义节点**，`onNodeWithTag` 查不到，
 * Robolectric 也拿不到绘制结果（没有真实画布）。硬要验像素只能上真机截图扫描，
 * 那是 `测试记录.md` T055 的职责，不是单测的。
 *
 * 而**真正会毁掉用户的缺陷是「内容被截断」**，这一条可以在这里验：
 * 历史上有两版实现都有问题——
 *  - 只写 `fillMaxHeight()` 时导轨高度为 0（无界约束），内容不截断但导轨看不见；
 *  - 加 `height(IntrinsicSize.Min)` 后导轨能显示了，**但行高被固定成「子项固有高度」**，
 *    而 Markdown 正文/代码块的固有高度只是估算，实际排版更高时超出部分被裁掉
 *    （用户 m45428 第 7 条：「比较长的有导轨的文字输出，有被截断的情况…后面的都看不到了」）。
 *
 * 定稿的 `drawBehind` 不参与测量，所以内容一定完整。这条测试钉住的就是「完整」。
 *
 * ⚠️ **容器必须是 LazyColumn**：真机的聊天列表就是它，高度约束无界。摆在 `setContent`
 * 根上（有界）会让历史上的错误实现也通过——见 D80。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleRailRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** 长内容必须完整渲染，不能因为导轨的实现方式被裁掉尾部。 */
    @Test
    fun `LazyColumn 里的长消息内容不被截断`() {
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
                                // 🔴 关键：必须包含一个**横向滚动**的子项（代码块/表格就是这样）。
                                // 横向滚动的固有高度是 0，`height(IntrinsicSize.Min)` 拿它
                                // 算出来的行高就远小于实际排版高度，后面的内容被整体裁掉——
                                // 这正是真机上「长文本被截断」的机制（已实测：纯文本样本
                                // 在 Robolectric 下固有高度是精确的，复现不出来）。
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        text = "一行很宽的代码，宽到必须在气泡里横向滚动才能看全。".repeat(6),
                                        maxLines = 1,
                                    )
                                }
                                // 🔴 这一段必须是一条**会换行**的长文本。
                                //
                                // `height(IntrinsicSize.Min)` 只在「子项固有高度 ≠ 实际排版高度」
                                // 时才会截断内容；固定高度的小 `Text` 两者相等，拿它做样本
                                // 连变异验证都抓不住（已实测：改回 `IntrinsicSize.Min` 仍绿）。
                                // 会换行的 `Text` 的 `minIntrinsicHeight` 按**未约束宽度**估算，
                                // 多行就低估，这才是真机上 Markdown 正文被裁的机制。
                                Text(
                                    text = "这是最后一行，必须可见。" + "用来迫使文本换行的填充内容。".repeat(12),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 🔴 必须用 `assertIsDisplayed()` 而不是 `assertExists()`（R029 审查 P3-1）。
        //
        // `assertExists()` 只查**语义树里有没有这个节点**——被 `clip` 裁掉的 `Text`
        // 节点依然在树里，于是把实现改回 `height(IntrinsicSize.Min)` 这三条断言照样通过，
        // 测试完全空转（已实测：改回旧实现，`assertExists` 版本仍绿）。
        // `assertIsDisplayed()` 会检查节点的实际绘制区域与根视图的交集，被裁掉的会失败。
        compose.onNodeWithText("第 0 行正文，用来把气泡撑高。").assertIsDisplayed()
        compose.onNodeWithText("第 11 行正文，用来把气泡撑高。").assertIsDisplayed()
        // 🔴 真正的判据：**气泡容器的高度必须容得下最后一行**。
        //
        // `assertExists` / `assertIsDisplayed` 在 Robolectric 下都抓不到这个缺陷
        // （已逐一实测：`assertExists` 查语义树，被 clip 的节点仍在；`assertIsDisplayed`
        // 查与根视图的交集，被**父级** clip 的子树仍算「在屏内」；`boundsInRoot` 报的是
        // 布局位置，clip 不改布局）。所以只能直接比几何：
        // 末行底边必须 ≤ 气泡底边。
        //
        // ⚠️ 诚实记录一条局限：这条几何断言在 Robolectric 下**抓不到**「把 `drawBehind`
        // 换回 `height(IntrinsicSize.Min)`」这个具体变异——实测三次（纯文本样本 / 会换行
        // 长样本 / 含横向滚动子项的样本）气泡高度都不变，说明 Robolectric 对这几个样本
        // 算出的固有高度恰好等于实际排版高度。真机截断由 T055 的截图与代码审查守护，
        // 这条测试守的是**另一类**真实回归：有人给容器加 `heightIn(max=)` 或把内容
        // 移出 `Row` 之外，那会让末行跑到气泡底下之外。
        val lastBottom = compose
            .onNodeWithText("这是最后一行，必须可见", substring = true)
            .fetchSemanticsNode().boundsInRoot.bottom
        val bubbleBottom = compose
            .onNodeWithTag(ChatRailTestTag)
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "末行底边 $lastBottom 必须落在气泡底边 $bubbleBottom 之内（超出即内容被裁）",
            lastBottom <= bubbleBottom,
        )
        compose.onNodeWithText("这是最后一行，必须可见", substring = true).assertIsDisplayed()
    }

    /** 短消息也能正常渲染（导轨画不画都不该影响内容）。 */
    @Test
    fun `短消息内容完整`() {
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
        compose.onNodeWithText("短").assertIsDisplayed()
    }

    /** `railColor = null`（用户气泡那条路径）不画导轨，但内容照旧。 */
    @Test
    fun `不传颜色时内容仍然完整`() {
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
        compose.onNodeWithText("用户消息").assertIsDisplayed()
    }
}
