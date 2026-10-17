package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 折叠分组展开后的自动滚动（用户 m45428 第 3 条）。
 *
 * 用户原话：「对于折叠操作的部分，展开后应该要显示最后一行的操作，现在显示的是前面几行的
 * 内容。并且，展开后，如果用户停在展开后的最后一个操作，有新的操作被折叠进来了，也要能
 * 优雅的滚动，目前的情况是不会自动滚动。需要注意的是，如果用户上划了想查看之前的操作，
 * 也不要给用户拉下来，这是交互性的基本要求。」
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupAutoScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private var state: LazyListState? = null
    private var count by mutableIntStateOf(5)
    private var expanded by mutableIntStateOf(0)

    /** 造一个限高的内层列表（模拟分组展开区），并挂上 [GroupAutoScroll]。 */
    private fun setList(viewportDp: Int = 150) {
        compose.setContent {
            val listState = rememberLazyListState()
            state = listState
            Box(modifier = Modifier.height(viewportDp.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(count) { index ->
                        Text(
                            text = "操作 $index",
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        )
                    }
                }
            }
            GroupAutoScroll(
                listState = listState,
                expanded = expanded == 1,
                memberCount = count,
            )
        }
        compose.waitForIdle()
    }

    /** 展开的一瞬间必须跳到最后一项（用户看到的是「刚刚发生了什么」）。 */
    @Test
    fun `展开时跳到最后一个操作`() {
        // 20 项 × 50dp = 1000dp，视口 150dp —— 严重溢出。
        count = 20
        expanded = 0
        setList()

        // 未展开时不动。
        assertEquals(0, state?.firstVisibleItemIndex)

        // 展开。
        expanded = 1
        compose.waitForIdle()

        val last = state?.layoutInfo?.visibleItemsInfo?.lastOrNull()?.index ?: -1
        assertTrue(
            "展开后应停在末尾附近，实际最后可见项 = $last（共 20 项）",
            last >= 19 - 2,
        )
    }

    /** 用户贴在末尾时，新成员进来要跟随。 */
    @Test
    fun `贴底时新操作进来会跟随`() {
        count = 20
        expanded = 1
        setList()
        compose.waitForIdle()

        // 再追加 3 项。
        count = 23
        compose.waitForIdle()

        val last = state?.layoutInfo?.visibleItemsInfo?.lastOrNull()?.index ?: -1
        assertTrue("追加后应跟到新的末尾，实际 $last（共 23 项）", last >= 23 - 2)
    }

    /** 用户上划查看历史后，新成员进来**不许**把他拽回底部。 */
    @Test
    fun `用户上划后新操作进来不抢滚动`() {
        count = 20
        expanded = 1
        setList()
        compose.waitForIdle()

        // 用户主动往上翻。
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking { state?.scrollBy(-300f) }
        }
        compose.waitForIdle()
        val afterScrollUp = state?.firstVisibleItemIndex ?: 0
        assertTrue("前置条件：用户应已离开底部，实际 index=$afterScrollUp", afterScrollUp < 15)

        // 新成员到达。
        count = 23
        compose.waitForIdle()

        val now = state?.firstVisibleItemIndex ?: 0
        assertTrue(
            "用户上划后不该被拽回底部，实际 index 从 $afterScrollUp 变成 $now",
            now <= afterScrollUp + 2,
        )
    }
}
