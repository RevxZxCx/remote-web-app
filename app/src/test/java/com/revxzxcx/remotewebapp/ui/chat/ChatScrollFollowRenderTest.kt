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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
 * [ChatScrollFollow] 的渲染层回归守卫。
 *
 * 盯的是 **R010m 在 MuMu 上用埋点实测到的 latch**：打开一个长会话时列表从索引 0 开始，
 * `canScrollForward` 恒真 → `atBottom` 立刻被置 false；而 `liveLength` 在没有流式预览时
 * 只发射一次 0，于是 `if (atBottom)` 永不成立 → 列表永远停在顶部、`atBottom` 永远 false、
 * 之后再也不跟随。现场埋点原文：
 *
 * ```
 * DSH-QUEUE: queued size=1 ids=[3] items=164 live=false atBottom=false canForward=true lastIndex=164
 * ```
 *
 * 排队回声其实一直在状态里，只是躺在视口下方；uiautomator 只报可见节点，所以被误判成
 * 「回声消失了」。同一形态也是用户 T7 反馈「生成时文本到底部不自动滚到最新行」的残余根因。
 *
 * 为什么必须用渲染测试而不是纯逻辑单测：`atBottom` 的取值来自 `LazyListState.canScrollForward`
 * 这个只有真实布局后才存在的量，纯 JVM 里连 `LazyListState` 都构造不出有效布局。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScrollFollowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var state: LazyListState? = null
    private var lastIndex = mutableIntStateOf(-1)
    private var liveLength = mutableIntStateOf(0)

    /**
     * 造一个 20 项、视口只放得下三四项的列表，并挂上 [ChatScrollFollow]。
     *
     * 视口必须明显小于内容总高，否则 `canScrollForward` 恒 false，latch 根本不会被触发。
     */
    private fun setList() {
        compose.setContent {
            val listState = rememberLazyListState()
            state = listState
            Box(modifier = Modifier.height(200.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(20) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .background(if (index == 19) Color(0xFF2850DC) else Color(0xFF888888)),
                        ) {
                            Text(
                                text = if (index == 19) "最后一行" else "第 $index 行",
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            ChatScrollFollow(
                listState = listState,
                lastIndex = { lastIndex.intValue },
                liveLength = { liveLength.intValue },
            )
        }
    }

    /** 等两轮空闲：一轮给 `canScrollForward` 的 snapshotFlow 发射，一轮给滚动落地。 */
    private fun settle() {
        compose.waitForIdle()
        compose.waitForIdle()
    }

    @Test
    fun `长会话打开时列表跳到最新一行`() {
        setList()
        settle()
        // 首帧内容还没到位：列表停在索引 0，但此时已经可以向下滚（这就是 latch 的温床）。
        assertEquals(0, state!!.firstVisibleItemIndex)
        assertTrue("内容应当溢出视口，否则这条用例没有意义", state!!.canScrollForward)

        lastIndex.intValue = 19
        settle()

        assertFalse(
            "首帧内容到位后必须主动滚到底，否则 atBottom 被 latch 成 false 后再也不跟随",
            state!!.canScrollForward,
        )
        assertTrue("应当已经跳到靠后的位置", state!!.firstVisibleItemIndex > 0)
        compose.onNodeWithText("最后一行").assertExists()
    }

    @Test
    fun `用户上翻历史后不再被拽回底部`() {
        setList()
        lastIndex.intValue = 19
        settle()
        assertFalse("先确认已经在底部", state!!.canScrollForward)

        // 用户往上翻。
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state!!.scrollToItem(0) } }
        settle()
        assertEquals(0, state!!.firstVisibleItemIndex)

        // 流式文本继续变长——此时不该把用户拽回去。
        liveLength.intValue = 500
        settle()
        assertEquals(
            "用户上翻历史时不能被自动跟随拽回底部",
            0,
            state!!.firstVisibleItemIndex,
        )
    }

    @Test
    fun `流式文本变长时若在底部就继续跟随`() {
        setList()
        lastIndex.intValue = 19
        settle()
        assertFalse("先确认已经在底部", state!!.canScrollForward)

        // 在底部时长高的一行会把底边推出视口，跟随必须把它拉回来。
        liveLength.intValue = 120
        settle()
        assertFalse("贴在底部时长高的一行必须继续被跟随", state!!.canScrollForward)
    }

    @Test
    fun `没有内容时不跳也不崩`() {
        setList()
        lastIndex.intValue = -1
        liveLength.intValue = 0
        settle()
        assertEquals("没有内容时不该跳", 0, state!!.firstVisibleItemIndex)
        assertTrue(true)
    }

    @Test
    fun `下标越界不会崩`() {
        setList()
        lastIndex.intValue = 999
        settle()
        assertTrue(true)
    }
}
