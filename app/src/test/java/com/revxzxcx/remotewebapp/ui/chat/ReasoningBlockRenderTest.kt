package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `assertCountEquals` 在 `compose-ui-test` 里不存在（本项目已踩过两次），
 * 统一用语义树节点数自己数。
 */
private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.count(): Int =
    fetchSemanticsNodes().size

/**
 * [ReasoningBlock] 的渲染测试（Robolectric，决策 D22）。
 *
 * 为什么值得写：这是**用户 m22856 第一条反馈**的修复本体——「对话的气泡中总是掺杂一些
 * 思考过程的文字…这些我都不太想看到出现在对话中」。修复前 `ContentBlock.Reasoning`
 * 与 `LiveAttempt.reasoning` 都以等宽小字**平铺**在气泡里，模型每轮几千字的推理会把
 * 真正的回复挤出屏幕。
 *
 * 这里钉住三条契约：
 * ① 折叠态只出现一行「思考过程 · N 字」，**推理正文不在语义树里**；
 * ② 点一下才展开，展开后正文出现；
 * ③ 再点一下收起——否则用户没有回头路，只能重开会话。
 *
 * **断言只查文本在不在，不查 chevron 字符**：R016 把 `▸`/`▾` 换字符改成了同一个
 * `▸` 字形旋转 90°（两个字形宽度不同，切换时标题会被推着左右跳），所以展开态
 * **不再有**「▾」这个文本节点。展开/收起的判据改成「正文在不在语义树里」——
 * 那是用户真正关心的东西，也顺带对实现细节免疫。
 *
 * 配色/圆角由真机验收管。同 `QueuedRowRenderTest` 的教训，Robolectric 下 bounds
 * 退化，所以用 `assertExists()` 而不是 `assertIsDisplayed()`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReasoningBlockRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val reasoning = "先读一遍文件，再决定怎么改。这里是很长很长的推理过程，真实会话里会有几千字。"

    /** 标题行的文案：chevron 是独立的 Text 节点（且字形不随展开态变），所以只断标题。 */
    private fun title(length: Int) = "思考过程 · $length 字"

    @Test
    fun `默认折叠且不显示推理正文`() {
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock(reasoning)
            }
        }

        compose.onNodeWithText(title(reasoning.length)).assertExists()
        // 折叠态下正文必须**完全不在语义树里**——只靠「看不见」是不够的，
        //  TalkBack 与 find 都能摸到它。
        assertEquals(0, compose.onAllNodesWithText(reasoning).count())
    }

    @Test
    fun `点一下展开出推理正文`() {
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock(reasoning)
            }
        }

        compose.onNodeWithText(title(reasoning.length)).performClick()
        compose.runOnIdle { }

        compose.onNodeWithText(reasoning).assertExists()
    }

    @Test
    fun `再点一下收回正文`() {
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock(reasoning)
            }
        }

        compose.onNodeWithText(title(reasoning.length)).performClick()
        compose.runOnIdle { }
        compose.onNodeWithText(title(reasoning.length)).performClick()
        compose.runOnIdle { }

        assertEquals(0, compose.onAllNodesWithText(reasoning).count())
    }

    @Test
    fun `空白推理什么都不画`() {
        // 空文本不该留一个空壳卡片——assistant 消息可能带一个空的 reasoning 块。
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock("   ")
            }
        }
        assertEquals(0, compose.onAllNodesWithText("思考过程", substring = true).count())
    }

    @Test
    fun `流式增长时折叠状态保持`() {
        // live 预览的文本每个 token 都在变。展开状态必须不挂在文本上，
        // 否则用户展开后会被下一次增量折回去。
        val text = mutableStateOf("先读一遍文件。")
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock(text.value)
            }
        }
        compose.onNodeWithText(title("先读一遍文件。".length)).performClick()
        compose.runOnIdle { }
        compose.onNodeWithText("先读一遍文件。").assertExists()

        // 同一个前缀继续增长（流式的真实形状）：状态不应被重置。
        text.value = "先读一遍文件。然后决定怎么改。"
        compose.runOnIdle { }
        compose.onNodeWithText("先读一遍文件。然后决定怎么改。").assertExists()
    }

    @Test
    fun `超过一屏的长推理也能展开`() {
        val long = "推理".repeat(2000)
        compose.setContent {
            DshAssistantTheme {
                ReasoningBlock(long)
            }
        }
        compose.onNodeWithText(title(long.length)).performClick()
        compose.runOnIdle { }
        compose.onNodeWithText(long).assertExists()
    }

    @Test
    fun `主题切换不改变默认折叠`() {
        // 深色/浅色下行为必须一致：折叠是数据语义，不是配色派生的。
        compose.setContent {
            DshAssistantTheme(themeMode = ThemeMode.DARK) {
                ReasoningBlock(reasoning)
            }
        }
        assertEquals(0, compose.onAllNodesWithText(reasoning).count())
    }
}
