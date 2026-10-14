package com.revxzxcx.remotewebapp.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp

/**
 * [MdBlocks] 的**真实渲染**测试（决策 D22）。
 *
 * 为什么需要它：M5-4 给单次组合加了块数/列表/表格上限，但这个上限是**渲染行为**，
 * 纯逻辑层单测一根毛都碰不到——只有真的把节点组合出来、再在语义树上数一遍，
 * 才能证明「超长文档不会一次画完」以及「点一下能把剩下的全画出来」。
 *
 * 同时它也是 Robolectric + Compose 的**冒烟测试**：跑不通说明 D22 的基础设施坏了，
 * 后面所有渲染测试都不可信。
 *
 * ⚠️ 断言统一用 `assertExists()` / `assertDoesNotExist()`，**不用 `assertIsDisplayed()`**：
 * Robolectric 的虚拟屏幕只有几屏高，第 199 个段落节点确实已经组合出来了、只是落在视口外，
 * `assertIsDisplayed()` 会误报成「没画」。这里要证明的是「有没有被组合」，不是「在不在屏上」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MdViewRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * 把 [MdBlocks] 放进一个**可竖向滚动的限高容器**里再组合。
     *
     * 直接 `setContent { MdBlocks(…) }` 的话，根节点不滚动、虚拟屏幕又只有几屏高，
     * 于是 `performClick()` 落在视口外的提示条上什么都不会发生（点不到）。
     * 套一层 `verticalScroll` 之后 `performScrollTo()` 才能把节点带进视口。
     */
    private fun setBlocks(blocks: List<MdBlock>) {
        compose.setContent {
            Box(
                modifier = Modifier
                    .height(240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MdBlocks(blocks = blocks, onLinkClick = {}, onFilePathClick = {})
            }
        }
    }

    /** 造 [count] 个互不相同的段落块。 */
    private fun paragraphs(count: Int): List<MdBlock> =
        (0 until count).map { index ->
            MdBlock.Paragraph(
                inlines = listOf(MdInline.Text("p$index")),
                startOffset = 0,
                endOffset = 0,
            )
        }

    @Test
    fun `块数超过上限时只画前一部分并给出展开入口`() {
        setBlocks(paragraphs(MAX_RENDERED_BLOCKS + 50))

        // 第 0 块与最后一个可见块都在语义树上。
        compose.onNodeWithText("p0").assertExists()
        compose.onNodeWithText("p${MAX_RENDERED_BLOCKS - 1}").assertExists()
        // 超出上限的块一次都不该被组合出来。
        compose.onNodeWithText("p$MAX_RENDERED_BLOCKS").assertDoesNotExist()
        compose.onNodeWithText("p${MAX_RENDERED_BLOCKS + 49}").assertDoesNotExist()
        // 提示条写明还剩多少。
        compose.onNodeWithText("展开其余 50 个内容块").assertExists()
    }

    @Test
    fun `点展开之后把剩下的块全部画出来`() {
        setBlocks(paragraphs(MAX_RENDERED_BLOCKS + 3))

        compose.onNodeWithText("展开其余 3 个内容块").performScrollTo().performClick()

        compose.onNodeWithText("p$MAX_RENDERED_BLOCKS").assertExists()
        compose.onNodeWithText("p${MAX_RENDERED_BLOCKS + 2}").assertExists()
        // 全部展开后提示条消失。
        compose.onNodeWithText("展开其余 3 个内容块").assertDoesNotExist()
    }

    @Test
    fun `不超过上限时不出现展开入口`() {
        setBlocks(paragraphs(3))

        compose.onNodeWithText("p0").assertExists()
        compose.onNodeWithText("p2").assertExists()
        // 整棵语义树里都不存在以「展开其余」开头的文本。
        compose.onNodeWithText("展开其余", substring = true).assertDoesNotExist()
    }

    @Test
    fun `列表条目超过上限时收手并给出展开入口`() {
        val items = (0 until MAX_LIST_ITEMS + 7).map { index ->
            MdListItem(inlines = listOf(MdInline.Text("item$index")))
        }
        setBlocks(listOf(MdBlock.BulletList(items = items, startOffset = 0, endOffset = 0)))

        compose.onNodeWithText("item0").assertExists()
        compose.onNodeWithText("item${MAX_LIST_ITEMS - 1}").assertExists()
        compose.onNodeWithText("item$MAX_LIST_ITEMS").assertDoesNotExist()
        compose.onNodeWithText("展开其余 7 个列表项").assertExists()
    }

    @Test
    fun `表格数据行超过上限时收手并给出展开入口`() {
        val headers = listOf(listOf(MdInline.Text("h1")), listOf(MdInline.Text("h2")))
        val rows = (0 until MAX_TABLE_ROWS + 4).map { index ->
            listOf(
                listOf(MdInline.Text("c${index}a")),
                listOf(MdInline.Text("c${index}b")),
            )
        }
        setBlocks(listOf(MdBlock.Table(headers = headers, rows = rows, startOffset = 0, endOffset = 0)))

        compose.onNodeWithText("h1").assertExists()
        compose.onNodeWithText("c${MAX_TABLE_ROWS - 1}a").assertExists()
        compose.onNodeWithText("c${MAX_TABLE_ROWS}a").assertDoesNotExist()
        compose.onNodeWithText("展开其余 4 个数据行").assertExists()
    }

    @Test
    fun `流式生成的未闭合围栏照常显示生成中`() {
        setBlocks(
            listOf(
                MdBlock.CodeBlock(
                    language = "kotlin",
                    code = "val x = 1",
                    closed = false,
                    startOffset = 0,
                    endOffset = 0,
                ),
            ),
        )

        compose.onNodeWithText("kotlin").assertExists()
        compose.onNodeWithText("生成中…").assertExists()
        compose.onNodeWithText("val x = 1").assertExists()
    }
}
