package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「滑动对话收起输入框」的判据守卫（用户 m45428 第 5 条 / R029 审查 P2-2）。
 *
 * ## 守的是「用哪个信号」，不是「有没有收起」
 *
 * 首版实现用 `snapshotFlow { listState.isScrollInProgress }`。它**看起来**完全正确
 * （「手指在拖/惯性滑行时为真」），但那个量不只由手指驱动：
 * [ChatScrollFollow] 在贴底时会**随每个流式 token** 调 `scrollToBottom(...)`，
 * 而它内部是 `scrollToItem` + `scroll(MutatePriority.Default)`——两者都会把
 * `isScrollInProgress` 置真。于是「模型正在输出 + 列表贴底」这个生成中的默认状态里，
 * 收缩信号随 token 不断自增，`Composer` 反复 `clearFocus()`：用户想在生成中先打一句话，
 * 键盘会被下一个 token 顶掉。
 *
 * 定稿改用 `listState.interactionSource` 的 `DragInteraction.Start`——**只由指针输入产生**。
 * 这两条测试把那个差别变成可执行的断言：程序化滚动**不发** `DragInteraction`，
 * 手指拖动**必须发**。若有人把判据改回 `isScrollInProgress`，第二条仍绿（它对两种都为真），
 * 但第一条会明确告诉他「程序化滚动也不是零信号」——真正要防的是「两种都算」。
 *
 * ⚠️ 不要用 `isScrollInProgress` 做断言：它对两种滚动都为真，正是缺陷本身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatCollapseSignalTest {

    @get:Rule
    val compose = createComposeRule()

    /** 与 `ChatScreen` 里那条 `LaunchedEffect` 完全同源的计数逻辑。 */
    private fun setList(counter: AtomicInteger, onReady: (LazyListState) -> Unit = {}) {
        compose.setContent {
            DshAssistantTheme {
                val state = rememberLazyListState()
                LaunchedEffect(state) {
                    state.interactionSource.interactions
                        .collect { i -> if (i is DragInteraction.Start) counter.incrementAndGet() }
                }
                LazyColumn(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .testTag("list"),
                ) {
                    items((0 until 40).toList()) { i ->
                        Text("第 $i 行", modifier = Modifier.fillMaxWidth().height(50.dp))
                    }
                }
                LaunchedEffect(state) { onReady(state) }
            }
        }
        compose.waitForIdle()
    }

    /** 程序化滚动（流式跟随走的就是这条路）**不该**被当成「用户在滑动」。 */
    @Test
    fun `程序化滚动不产生 DragInteraction`() {
        val dragStarts = AtomicInteger(0)
        lateinit var listState: LazyListState
        setList(dragStarts) { listState = it }

        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main).launch {
                listState.scrollToItem(20)
                listState.scrollBy(120f)
            }
        }
        compose.waitForIdle()

        assertTrue("列表应该真的滚动了（否则测试无意义）", listState.firstVisibleItemIndex > 0)
        assertEquals(
            "程序化滚动不该产生 DragInteraction，实际发了 ${dragStarts.get()} 次",
            0,
            dragStarts.get(),
        )
    }

    /** 手指拖动**必须**产生 `DragInteraction.Start`——否则收起功能彻底失效。 */
    @Test
    fun `手指拖动产生 DragInteraction`() {
        val dragStarts = AtomicInteger(0)
        setList(dragStarts)

        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue(
            "手指拖动必须产生 DragInteraction.Start，实际 ${dragStarts.get()} 次",
            dragStarts.get() > 0,
        )
    }
}
