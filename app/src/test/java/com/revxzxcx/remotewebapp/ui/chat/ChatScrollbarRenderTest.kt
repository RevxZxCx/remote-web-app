package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatScrollbar] 与 [ScrollToBottomFab] 的渲染层回归守卫。
 *
 * 需求来自用户 m24150：聊天页没有「我输入过哪些对话点」的位置参照，想找回某一条自己发的话
 * 只能一路翻。这条滚动条的全部风险都在渲染层——高度模型靠量到的可见项、命中半径靠手势
 * 坐标、滑块位置靠 `layoutInfo`——纯逻辑单测一条也抓不到，本工程已经为此付过九次代价。
 *
 * 断言走 [ScrollbarModel]（`onModelChange` 回调）而不是去读 Canvas：Canvas 画的虚线、
 * 滑块、标记在语义树里什么都不是，只能靠这个可观测模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScrollbarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var state: LazyListState? = null
    private var count by mutableIntStateOf(20)
    private var userEvery = 5

    private var scrollToIndex = -1
    private var lastModel: ScrollbarModel? = null
    private var fabClicks = 0
    private var fabVisible = false

    /**
     * 造一个聊天列表并挂上 [ChatScrollbar]。
     *
     * @param items 总行数
     * @param viewportDp 视口高度（dp）；行高固定 50dp
     * @param userEvery 每隔几行算一条「我发送的消息」（0 表示一条都没有）
     */
    private fun setChatList(items: Int, viewportDp: Int = 200, userEvery: Int = 5) {
        count = items
        this.userEvery = userEvery
        lastModel = null
        scrollToIndex = -1
        compose.setContent {
            val listState = rememberLazyListState()
            state = listState
            Box(modifier = Modifier.height(viewportDp.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(Color.White),
                ) {
                    // key 必须与下面 itemKeys() 的字符串逐字一致：高度模型按 key 关联
                    // 「行序位置」与「量到的像素高」，两边对不上时模型恒为空、滚动条根本不画。
                    // 真实 ChatScreen 用的是 `key = { it.seq }`，同理。
                    items(count, key = { index -> "row-$index" }) { index ->
                        Text(
                            text = "消息 $index",
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        )
                    }
                }
                ChatScrollbar(
                    listState = listState,
                    itemKeys = { (0 until count).map { "row-$it" } },
                    userItemIndices = {
                        if (userEvery <= 0) {
                            emptyList()
                        } else {
                            (0 until count).filter { it % userEvery == 0 }
                        }
                    },
                    onScrollToItem = { index -> scrollToIndex = index },
                    onModelChange = { lastModel = it },
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(18.dp)
                        .padding(vertical = 6.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private val scrollbar
        get() = compose.onNodeWithContentDescription("聊天位置滚动条", substring = true)

    @Test
    fun `列表不溢出时滚动条不出现`() {
        // 3 行 × 50dp = 150dp，视口 200dp —— 一屏放得下，滑块就是整条轨道，纯噪声。
        setChatList(items = 3, viewportDp = 200)
        compose.waitForIdle()
        scrollbar.assertDoesNotExist()
    }

    @Test
    fun `列表溢出时滚动条出现`() {
        setChatList(items = 20, viewportDp = 200)
        compose.waitForIdle()
        scrollbar.assertExists()
    }

    @Test
    fun `标记数量等于我发送的消息条数`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 5)
        compose.waitForIdle()
        // 下标 0、5、10、15 四条。
        assertNotNull("应当拿到滚动条模型", lastModel)
        assertEquals(4, lastModel!!.markerFractions.size)
    }

    @Test
    fun `一条我发送的消息都没有时标记为空`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 0)
        compose.waitForIdle()
        assertNotNull(lastModel)
        assertTrue("没有用户消息时不应有标记", lastModel!!.markerFractions.isEmpty())
        // 没有标记也仍然是滚动条：滑块本身有价值。
        scrollbar.assertExists()
    }

    @Test
    fun `停在底部时滑块贴在轨道底部`() {
        setChatList(items = 20, viewportDp = 200)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking { state?.scrollToItem(19) }
        }
        compose.waitForIdle()
        assertNotNull(lastModel)
        // 判据必须是**滑块的底边**而不是顶边：贴底时列表会钳到最大滚动（末行整块进视口），
        // 此时滑块顶边在 1 - 视口/总高 处而不是 1。20 行 × 50dp = 1000dp、视口 200dp →
        // 顶边 0.8、底边 1.0。断言顶边接近 1 是把「钳制」误当成「没滚到底」。
        val model = lastModel!!
        assertTrue(
            "贴底时滑块底边应到轨道末端，实际 ${model.thumbTopFraction + model.thumbHeightFraction}",
            model.thumbTopFraction + model.thumbHeightFraction > 0.99f,
        )
        assertTrue(
            "滑块应比整条轨道矮，实际高度占比 ${model.thumbHeightFraction}",
            model.thumbHeightFraction < 1f,
        )
    }

    @Test
    fun `停在顶部时滑块贴在轨道顶端`() {
        setChatList(items = 20, viewportDp = 200)
        compose.waitForIdle()
        val model = lastModel!!
        assertTrue(
            "停顶时滑块顶边应在 0 附近，实际 ${model.thumbTopFraction}",
            model.thumbTopFraction < 0.01f,
        )
    }

    @Test
    fun `滚到中段时滑块离开底部`() {
        setChatList(items = 40, viewportDp = 200)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking { state?.scrollToItem(20) }
        }
        compose.waitForIdle()
        assertNotNull(lastModel)
        assertTrue(
            "中段时滑块应在 0.3..0.7 之间，实际 ${lastModel!!.thumbTopFraction}",
            lastModel!!.thumbTopFraction in 0.3f..0.7f,
        )
    }

    @Test
    fun `标记按顺序排列且都在轨道范围内`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 5)
        compose.waitForIdle()
        val markers = lastModel!!.markerFractions
        assertEquals(4, markers.size)
        markers.forEach { assertTrue("标记位置 $it 越界", it in 0f..1f) }
        for (i in 1 until markers.size) {
            assertTrue("标记应随行号递增", markers[i] > markers[i - 1])
        }
    }

    @Test
    fun `点第一个标记跳到对应的那条消息`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 5)
        compose.waitForIdle()
        val node = scrollbar.fetchSemanticsNode()
        val top = node.positionInRoot.y
        val height = node.size.height.toFloat()
        val centerX = node.size.width / 2f
        // 第一个标记在下标 0 上。行高 50dp、视口 200dp，量到的只有可见的 4 行，
        // 其余 16 行按平均值 50dp 补 → 标记 0 的中心 ≈ 25dp / 1000dp ≈ 2.5%。
        val fraction = lastModel!!.markerFractions.first()
        val y = top + fraction * height
        scrollbar.performTouchInput { click(Offset(centerX, y - top)) }
        compose.waitForIdle()
        assertEquals("点第一个标记应跳到第 0 行", 0, scrollToIndex)
    }

    @Test
    fun `点靠后的标记跳到靠后的行`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 5)
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking { state?.scrollToItem(19) }
        }
        compose.waitForIdle()
        // 滚到底后再量，量到的行高仍然全是 50dp，模型不变。
        val node = scrollbar.fetchSemanticsNode()
        val top = node.positionInRoot.y
        val height = node.size.height.toFloat()
        val fraction = lastModel!!.markerFractions[3]
        val centerX = node.size.width / 2f
        scrollbar.performTouchInput { click(Offset(centerX, fraction * height)) }
        compose.waitForIdle()
        assertEquals("点最后一个标记应跳到第 15 行", 15, scrollToIndex)
    }

    @Test
    fun `点轨道中部也会跳到大致的行`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 0)
        compose.waitForIdle()
        val node = scrollbar.fetchSemanticsNode()
        val height = node.size.height.toFloat()
        val centerX = node.size.width / 2f
        // 轨道中点 → 模型总高的一半 → 约第 10 行。允许 ±3 行的近似（行高平均值补的代价）。
        scrollbar.performTouchInput { click(Offset(centerX, height / 2f)) }
        compose.waitForIdle()
        assertTrue("点轨道中部应跳到中段，实际跳到 $scrollToIndex", scrollToIndex in 7..13)
    }

    @Test
    fun `点没有用户消息的区域不会跳到标记上`() {
        setChatList(items = 20, viewportDp = 200, userEvery = 0)
        compose.waitForIdle()
        val node2 = scrollbar.fetchSemanticsNode()
        val height = node2.size.height.toFloat()
        val centerX = node2.size.width / 2f
        scrollbar.performTouchInput { click(Offset(centerX, height / 2f)) }
        compose.waitForIdle()
        // 没有标记时只能按比例二分，落点必然在中段而不是某个标记行。
        assertTrue(scrollToIndex in 7..13)
    }
}

/**
 * [ScrollToBottomFab] 的渲染层回归守卫。
 *
 * 与滚动条是用户 m24150 同一次反馈里的两件事，所以放同一个文件。这里单独测它自己：
 * 显隐由调用方（`ChatScreen` 的 `showScrollToBottom`）决定，本组件只负责
 * 「出现/消失的动画别把点击吃掉」以及「点一下真的会回调」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScrollToBottomFabRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `不可见时不占语义树`() {
        compose.setContent { ScrollToBottomFab(visible = false, onClick = { }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("滚动到底部").assertDoesNotExist()
    }

    @Test
    fun `可见时可以点且会回调`() {
        var clicks = 0
        compose.setContent { ScrollToBottomFab(visible = true, onClick = { clicks++ }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("滚动到底部").assertExists()
        compose.onNodeWithContentDescription("滚动到底部").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun `从可见切到不可见会消失`() {
        var visible by mutableStateOf(true)
        compose.setContent { ScrollToBottomFab(visible = visible, onClick = { }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("滚动到底部").assertExists()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("滚动到底部").assertDoesNotExist()
    }
}
