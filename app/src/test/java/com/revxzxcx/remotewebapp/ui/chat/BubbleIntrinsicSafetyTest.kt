package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.revxzxcx.remotewebapp.ui.markdown.MdBlocks
import com.revxzxcx.remotewebapp.ui.markdown.MdParser
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Bubble` 的 `height(IntrinsicSize.Min)` 与 Markdown 里可横向滚动的内容必须能共存。
 *
 * 背景（R028b）：为了修「助手消息导轨没铺满」，`Bubble` 的外层 `Row` 加了
 * `height(IntrinsicSize.Min)`。Compose 的**固有测量**不是所有布局都支持的——
 * `LazyRow` / `LazyColumn` 这类惰性容器在固有测量下会直接抛
 * `IllegalStateException: Intrinsic measurements are not supported`。
 *
 * `MdView` 里有两处 `horizontalScroll`（表格、代码块），实测它们是支持的
 * （`horizontalScroll` 走 `ScrollNode` 而非惰性布局，固有测量能穿透）。但这是一条
 * **隐式依赖**：将来有人在 Markdown 渲染里加一个 `LazyRow`（比如横向图片轮播），
 * 整条消息就会崩在测量阶段，而错误栈会指向 `Bubble` 而不是新增的 `LazyRow`。
 *
 * 所以这里钉住：**表格与代码块放在 LazyColumn 里的 Bubble 中必须能正常渲染**。
 * 它同时也是「导轨修复没有引入崩溃」的守护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleIntrinsicSafetyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setBubbleWith(markdown: String, tail: String) {
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
                                MdBlocks(
                                    blocks = MdParser.parse(markdown),
                                    onLinkClick = {},
                                    onFilePathClick = {},
                                )
                                Text(tail)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 表格走 `horizontalScroll`——固有测量必须能穿透。 */
    @Test
    fun `表格在带导轨的气泡里不会崩在固有测量`() {
        setBubbleWith(
            markdown = """
                | 列 A | 列 B |
                |---|---|
                | 1 | 2 |
            """.trimIndent(),
            tail = "表格之后的文字",
        )
        compose.onNodeWithText("表格之后的文字").assertExists()
    }

    /** 代码块同样走 `horizontalScroll`。 */
    @Test
    fun `代码块在带导轨的气泡里不会崩在固有测量`() {
        setBubbleWith(
            markdown = "```kotlin\nval x = 1\n```",
            tail = "代码块之后的文字",
        )
        compose.onNodeWithText("代码块之后的文字").assertExists()
    }
}
