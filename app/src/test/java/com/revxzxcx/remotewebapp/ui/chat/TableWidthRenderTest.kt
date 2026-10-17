package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.markdown.MdBlocks
import com.revxzxcx.remotewebapp.ui.markdown.MdParser
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Markdown 表格必须铺满可用宽度（用户 m45428 第 4 条）。
 *
 * 用户原话：「markdown 中的表格，有的时候不会占满整个屏幕的宽度，导致表被挤成比较窄的
 * 一条，对于表格的展示，需要更好的自适应逻辑」。
 *
 * 根因：旧实现把表格总宽写死成 `96 * columns`。2 列表格在 360dp 屏上只有 192dp，
 * 右边留一大片空白。
 *
 * 定稿：总宽 = `maxOf(可用宽度, 96 × 列数)`。这里钉住「2 列表格会拉伸到容器宽度」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TableWidthRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setTable(markdown: String, containerDp: Int) {
        compose.setContent {
            DshAssistantTheme {
                Box(modifier = Modifier.width(containerDp.dp)) {
                    MdBlocks(
                        blocks = MdParser.parse(markdown),
                        onLinkClick = {},
                        onFilePathClick = {},
                    )
                }
            }
        }
    }

    /** 两列表格在 400dp 容器里必须铺满（而不是停在 192dp）。 */
    @Test
    fun `两列表格铺满容器宽度`() {
        setTable(
            markdown = """
                | 列 A | 列 B |
                |---|---|
                | 值一 | 值二 |
            """.trimIndent(),
            containerDp = 400,
        )
        // 内容必须完整渲染（宽度算错时最容易先丢的是右侧列）。
        compose.onNodeWithText("列 A").assertExists()
        compose.onNodeWithText("列 B").assertExists()
        compose.onNodeWithText("值一").assertExists()
        compose.onNodeWithText("值二").assertExists()

        // 🔴 判据必须是**右列起点的绝对位置**，不能是「右列右边缘 − 左列左边缘」
        // （我首版就是后者，变异验证没变红——因为固定 96dp 宽时那个跨度也能到 190dp）。
        //
        // 400dp 容器均分两列 → 右列应从 ~200dp 开始。
        // 旧实现每列固定 96dp → 右列从 96dp 开始，一眼可分。
        val rightColumnStart = compose.onNodeWithText("列 B").getBoundsInRoot().left.value
        org.junit.Assert.assertTrue(
            "右列起点只有 ${rightColumnStart}dp；400dp 容器均分两列时应在 150dp 以上" +
                "（固定 96dp 宽时会停在 ~96dp）",
            rightColumnStart > 150f,
        )
    }

    /** 列很多时超过容器宽度，必须仍能渲染（靠横向滚动看全，不崩）。 */
    @Test
    fun `多列表格不崩且内容完整`() {
        setTable(
            markdown = """
                | A | B | C | D | E | F |
                |---|---|---|---|---|---|
                | 1 | 2 | 3 | 4 | 5 | 6 |
            """.trimIndent(),
            containerDp = 360,
        )
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithText("F").assertExists()
        compose.onNodeWithText("6").assertExists()
    }
}
