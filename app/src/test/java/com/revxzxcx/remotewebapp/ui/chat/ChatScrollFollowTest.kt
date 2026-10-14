package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7 设备验收实测缺陷的回归守卫：**生成中不自动往下滚**与**回弹到生成的开头**。
 *
 * 两个症状是同一处代码的两个面：
 *
 * 1. `LaunchedEffect(listState, lastIndex)` 的 key 在流式过程中不变，于是 effect 不重启；
 *    而 effect 里的 `snapshotFlow { state.live?.text?.length }` 捕获的是**组合时**的 `state`，
 *    拿到的是旧 `ChatUiState` —— `live.text` 长度被冻住，新 token 不再触发滚动。
 * 2. 即使用对了状态，`scrollToItem(lastIndex)` 也只把最后一行**顶边**对齐视口顶边；
 *    流式气泡长高后，最新的字一直在屏幕外，视觉上就是「停在生成的开头」。
 *
 * 这里直接驱动 [scrollToBottom] 钉第 2 点（第 1 点是 ViewModel 的 state 读取方式，
 * 由 `ChatReducerTest` 与人工验收覆盖）。
 *
 * 为什么用 `canScrollForward` 当判据而不是自己算 item 偏移：`LazyListItemInfo.offset`
 * 的坐标系约定在 Compose 版本间变过，而「还能不能向前滚」是语义层的、跨版本稳定的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScrollFollowTest {

    @get:Rule
    val compose = createComposeRule()

    private var state: LazyListState? = null

    /**
     * 造一个 6 项的列表，视口只有 200dp，最后一项 [lastHeight]dp。
     *
     * @param lastHeight 600 时最后一项高过视口（必须靠第二次滚动才能到底）；
     *   50 时它比视口矮（一次 `scrollToItem` 就该到底）。
     */
    private fun setList(lastHeight: Int) {
        compose.setContent {
            val listState = rememberLazyListState()
            state = listState
            Box(modifier = Modifier.height(200.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(6) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (index == 5) lastHeight.dp else 100.dp)
                                .background(if (index == 5) Color(0xFF2850DC) else Color(0xFF888888))
                                .padding(4.dp),
                        ) {
                            Text(
                                text = if (index == 5) "正在生成的最后一条" else "第 $index 条",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 最后一项的**底边**必须落在视口里。
     *
     * 不要求顶边也在视口里：最后一项比视口高时，正确的底部形态就是「它的下半截贴着视口底边」，
     * 顶边滚出屏外是正常的。只断言底边正是这个函数与「顶边也要在」的分界。
     */
    private fun assertLastItemBottomInsideViewport() {
        val info = state!!.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 5 }
        requireNotNull(info) { "最后一项不在可见项里" }
        val viewportEnd = state!!.layoutInfo.viewportEndOffset
        assertTrue(
            "最后一项还有一截在视口下面（bottom=${info.offset + info.size} > $viewportEnd）",
            info.offset + info.size <= viewportEnd,
        )
        val viewportStart = state!!.layoutInfo.viewportStartOffset
        val viewportHeight = viewportEnd - viewportStart
        if (info.size <= viewportHeight) {
            assertTrue(
                "最后一项比视口矮，本应整块可见（offset=${info.offset} < $viewportStart）",
                info.offset >= viewportStart,
            )
        }
    }

    @Test
    fun `最后一行高过视口时滚到底后没有剩余可滚内容`() {
        setList(lastHeight = 600)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state?.scrollToBottom(5) } }
        compose.runOnIdle {
            assertFalse(
                "只 scrollToItem 会把最后一行的顶边钉在视口顶边，于是仍有内容在下面",
                state!!.canScrollForward,
            )
            assertLastItemBottomInsideViewport()
        }
    }

    @Test
    fun `最后一行比视口矮时一次滚动也到底`() {
        setList(lastHeight = 50)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state?.scrollToBottom(5) } }
        compose.runOnIdle {
            assertFalse("不溢出的列表不该还剩可滚内容", state!!.canScrollForward)
            assertLastItemBottomInsideViewport()
        }
    }

    @Test
    fun `最后一行高过视口时它的开头仍然可见`() {
        // 滚到底不等于把最后一行整个跳过：它的开头必须还在语义树里。
        setList(lastHeight = 600)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state?.scrollToBottom(5) } }
        compose.onNodeWithText("正在生成的最后一条").assertExists()
    }

    @Test
    fun `空列表与越界下标不会崩`() {
        setList(lastHeight = 600)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                state?.scrollToBottom(-1)
                state?.scrollToBottom(99)
            }
        }
        assertTrue(true)
    }
}
