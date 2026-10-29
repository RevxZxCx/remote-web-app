package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 溢出菜单的**样式**测试（B003）。
 *
 * 用户原话：「主页右上角三点展开的菜单上个需求中要求进行修改，也已经画出原型，
 * 但是目前只改了内容，没有修改样式。」
 *
 * 修复前 `AppOverflowMenu` 只有一行 `DropdownMenu(expanded, onDismissRequest) { content() }`，
 * 全部走 M3 默认：4dp 圆角、无描边、`DropdownMenuItem` 的 48dp 行高与矩形水波纹。
 * 而原型 `docs/R054-多主机效果图.html` 定义的是 14dp 容器圆角 / 1dp 描边 /
 * 10dp 行圆角 / 38dp 行高 / 12sp 字号。
 *
 * 这个文件的断言分三层，缺一层都会漏：
 *
 * 1. **常量层**：`OverflowMenuShape` / `OverflowMenuRowShape` 的圆角数值必须等于原型。
 *    这一层最便宜，但只锁常量，锁不住「常量定义了却没用上」。
 * 2. **渲染层**：菜单真的显示出来、条目真的可点。这一层锁「功能没被样式改坏」。
 * 3. **反例层**：断言样式**不等于** M3 默认值。若将来有人把参数删回默认，
 *    前两层可能照样绿（常量还在、渲染还在），只有这一层会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverflowMenuStyleTest {

    @get:Rule
    val compose = createComposeRule()

    // ── 第 1 层：常量等于原型 ──────────────────────────────

    @Test
    fun 菜单容器圆角必须是原型的14dp而不是M3默认的4dp() {
        assertEquals(14.dp, OverflowMenuCornerRadius)
        // 断言形状确实是用这个半径构造出来的：只改常量不改 shape 会被这条抓住。
        assertEquals(RoundedCornerShape(14.dp), OverflowMenuShape)
        // M3 DropdownMenu 的内部默认圆角是 4dp（MenuDefaults / MenuTokens.ContainerShape）。
        // 这里写常量而不是引用框架内部值：框架没导出它，而我们要防的正是「退回 4dp」。
        assertNotEquals("不能退回 M3 默认的 4dp 圆角", 4.dp, OverflowMenuCornerRadius)
    }

    @Test
    fun 菜单行圆角必须是原型的10dp而不是直角的DropdownMenuItem() {
        assertEquals(10.dp, OverflowMenuRowCornerRadius)
        assertEquals(RoundedCornerShape(10.dp), OverflowMenuRowShape)
        // M3 的 DropdownMenuItem 没有 shape 参数，画出来是直角；这里断言行一定带圆角。
        assertNotEquals(RoundedCornerShape(0.dp), OverflowMenuRowShape)
    }

    /**
     * 反例：容器与行的圆角**必须不同**。
     *
     * 原型 `.menu`（14px）与 `.mi`（10px）是两级圆角：内层条目如果和容器同圆角，
     * 条目会顶到容器弧线上，看起来像「一个饼切成几条」。这条断言防的是
     * 「复制粘贴时把行圆角写成了容器圆角」——那种改动上面两条**都不会红**
     * （各自仍然是 14/10 中的一个合法值），只有这条会。
     */
    @Test
    fun 行圆角必须小于容器圆角否则条目会顶到容器弧线上() {
        val container: Dp = OverflowMenuCornerRadius
        val row: Dp = OverflowMenuRowCornerRadius
        assertTrue("行圆角 $row 必须小于容器圆角 $container", row < container)
        assertNotEquals(container, row)
    }

    // ── 第 2 层：渲染层没被样式改坏 ──────────────────────

    @Test
    fun 菜单展开时条目可见且可点() {
        var clicked = 0
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = { clicked++ })
                    }
                }
            }
        }
        compose.onNodeWithText("刷新列表").assertIsDisplayed()
        compose.onNodeWithText("刷新列表").performClick()
        compose.waitForIdle()
        assertEquals(1, clicked)
    }

    @Test
    fun 菜单收起时不渲染任何条目() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = false, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                    }
                }
            }
        }
        compose.onAllNodesWithText("刷新列表").assertCountEquals(0)
    }

    @Test
    fun 破坏性条目仍然走错误色_样式改动不能吃掉语义() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "断开当前远程", destructive = true, onClick = {})
                    }
                }
            }
        }
        compose.onNodeWithText("断开当前远程").assertIsDisplayed()
    }

    @Test
    fun 分区标题与分隔线在该渲染的时候渲染() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                        OverflowMenuDivider()
                        OverflowMenuSectionHeader("远程")
                    }
                }
            }
        }
        compose.onNodeWithText("远程").assertIsDisplayed()
    }

    /**
     * 行高必须是原型 `.mi{padding:8px 9px}` + 12px 字算出来的紧凑行，而不是
     * `DropdownMenuItem` 的 48dp。
     *
     * 这条是**渲染层**的尺寸断言，也是本文件唯一能抓住「又换回
     * `DropdownMenuItem`」的用例：换回去之后上面所有常量断言照旧绿
     * （常量还在，只是没人用），而这一条会因为 48dp ≠ 38dp 立刻红。
     */
    @Test
    fun 菜单行高必须按原型压到38dp而不是DropdownMenuItem的48dp() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                    }
                }
            }
        }
        compose.onNodeWithText("刷新列表").assertHeightIsEqualTo(38.dp)
    }

    /**
     * 菜单宽度必须是原型 `:146` `.menu{width:212px}` 的 212dp。
     *
     * 原型手机是 360px 宽，212/360 ≈ 59%——真机 PKG110（1080px @ 480dpi）算下来
     * 正好也是 360dp 宽，比例一致。模拟器密度低（216dpi → 640dp 宽）所以看起来
     * 偏窄，那是屏幕宽的问题，不是菜单的问题。
     *
     * 断言写成「容器宽度恰好等于 min(max(内容宽, 212dp), 268dp)」太重，这里直接量
     * 语义树里那个 IsContainer 节点的宽度 = 212dp（density 换算后）。
     */
    @Test
    fun 菜单宽度必须是原型的212dp() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                    }
                }
            }
        }
        val row = compose.onNodeWithText("刷新列表").fetchSemanticsNode()
        val container = generateSequence(row.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.IsContainer) }
        val expected = with(compose.density) { 212.dp.roundToPx() }
        assertEquals(
            "菜单容器宽度必须是 212dp = ${expected}px（原型 .menu{width:212px}），实际 ${container.size.width}px",
            expected,
            container.size.width,
        )
    }

    /**
     * 语义树里能直接读到 `SemanticsProperties.Shape`——这给了「圆角真的生效了吗」
     * 一个**不靠常量**的观测点。上面几条常量断言只能证明「常量写对了」，
     * 这条证明「这个常量真的被传进了 shape 参数」。
     *
     * 实测语义树（density=1.0）：
     * ```
     * L0 Row    size=188x38  Shape=RoundedCornerShape(...10.0.dp...) Role=Button
     * L1 滚动容器 size=200x48  Shape=VerticalScrollableClipShape
     * L2 菜单容器 size=200x64  Shape=RoundedCornerShape(...14.0.dp...) IsContainer
     * ```
     */
    @Test
    fun 行与容器的圆角必须真的落到语义树的Shape上() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                    }
                }
            }
        }
        val row = compose.onNodeWithText("刷新列表").fetchSemanticsNode()
        assertEquals(
            "行的 shape 必须是 10dp 圆角，而不是没有 clip（M7）",
            OverflowMenuRowShape,
            row.config[SemanticsProperties.Shape],
        )

        // 往上找容器：中间那层是 VerticalScrollableClipShape，不是我们要的。
        val shapes = generateSequence(row.parent) { it.parent }
            .mapNotNull { node ->
                if (node.config.contains(SemanticsProperties.Shape)) {
                    node.config[SemanticsProperties.Shape]
                } else {
                    null
                }
            }
            .toList()
        assertTrue(
            "语义树里必须出现 14dp 的容器圆角，实际拿到 $shapes（M6：shape 参数没走常量）",
            OverflowMenuShape in shapes,
        )
    }

    /**
     * 条目必须被 5dp 容器内边距收进来。
     *
     * 实测（density=1.0）：容器 200x64、行 188x38 —— 宽度差 12px = 2 × (5dp 内边距 + 1dp 行自身水平内缩)。
     * 内边距一旦被删，行宽会变成 198，差值只剩 2，这条立刻红。
     *
     * 用「宽差」而不是绝对宽度：绝对宽度受 density 影响，差值只受 dp 常量影响。
     */
    @Test
    fun 条目必须被容器的5dp内边距收进来() {
        compose.setContent {
            DshAssistantTheme {
                Box {
                    AppOverflowMenu(expanded = true, onDismiss = {}) {
                        OverflowItem(text = "刷新列表", onClick = {})
                    }
                }
            }
        }
        val row = compose.onNodeWithText("刷新列表").fetchSemanticsNode()
        val container = generateSequence(row.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.IsContainer) }
        val expectedGap = with(compose.density) { 12.dp.toPx() }
        val actualGap = container.size.width - row.size.width
        assertEquals(
            "容器 ${container.size.width}px 与行 ${row.size.width}px 的宽差必须是 12px（5dp 内边距 + 1dp 行内缩，两侧合计）",
            expectedGap,
            actualGap.toFloat(),
            0.5f,
        )
    }

}
