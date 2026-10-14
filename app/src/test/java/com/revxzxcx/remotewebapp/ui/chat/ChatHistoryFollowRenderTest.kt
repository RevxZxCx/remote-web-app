package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatHistoryFollow] 的渲染层回归守卫。
 *
 * 盯的是 **R012 P2-C**：`snapshotFlow { firstVisibleItemIndex }` 必发初始值，而
 * [ChatScrollFollow] 把列表定位到底部后，短会话（消息全在一屏内）的 `firstVisibleItemIndex`
 * 恒为 0 —— 用户什么都没做就会触发 `loadMore()`，顶部凭空转圈并静默 prepend 旧消息。
 * 与用户 m22856 反馈的「如果需要往上翻查看历史的对话，也应做自动加载更早的历史对话」
 * 正好相反：那个「如果」是前提，不是装饰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatHistoryFollowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var state: LazyListState? = null
    private var count by mutableIntStateOf(3)
    private val hasMore = mutableStateOf(true)
    private var loadMoreCalls = 0

    /**
     * 造一个列表并挂上 [ChatHistoryFollow]。
     *
     * @param items 列表项数
     * @param viewportDp 视口高度（dp）；项高固定 50dp
     */
    private fun setList(items: Int, viewportDp: Int = 200) {
        count = items
        compose.setContent {
            val listState = rememberLazyListState()
            state = listState
            Box(modifier = Modifier.height(viewportDp.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(Color.White),
                ) {
                    items(count) { index ->
                        Text(
                            text = "消息 $index",
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        )
                    }
                }
            }
            ChatHistoryFollow(
                listState = listState,
                hasMore = { hasMore.value },
                onLoadMore = { loadMoreCalls++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `短会话不会一进去就自动翻历史`() {
        // 3 项 × 50dp = 150dp，视口 200dp —— 一屏放得下，firstVisibleItemIndex 恒 0。
        setList(items = 3, viewportDp = 200)
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `长会话停在底部时也不会自动翻历史`() {
        // 20 项 × 50dp = 1000dp，视口 200dp —— 溢出了，但用户还在底部，不该翻。
        setList(items = 20, viewportDp = 200)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                state?.scrollToItem(19)
            }
        }
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `长会话滚到顶部才自动翻历史`() {
        setList(items = 20, viewportDp = 200)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                state?.scrollToItem(19)
            }
        }
        compose.waitForIdle()
        assertEquals("前置条件：滚到底部时不应触发", 0, loadMoreCalls)

        // 用户主动往上翻到顶。
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                state?.scrollToItem(0)
            }
        }
        compose.waitForIdle()
        assertTrue("滚到顶应触发一次 loadMore，实际 $loadMoreCalls", loadMoreCalls >= 1)
    }

    @Test
    fun `没有更早历史时滚到顶也不翻`() {
        setList(items = 20, viewportDp = 200)
        hasMore.value = false
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                state?.scrollToItem(0)
            }
        }
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `从短会话长成长会话后仍然不会自动翻`() {
        // 先短：ChatScrollFollow 的首帧跳底对短列表等于停在 0。
        setList(items = 3, viewportDp = 200)
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)

        // 新消息到来变成 20 项（溢出），但用户没有滚动 —— 仍然不该翻。
        count = 20
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `单屏内容可见`() {
        setList(items = 3, viewportDp = 200)
        compose.onNodeWithText("消息 0").assertExists()
        compose.onNodeWithText("消息 2").assertExists()
        assertFalse(count == 0)
    }
}
