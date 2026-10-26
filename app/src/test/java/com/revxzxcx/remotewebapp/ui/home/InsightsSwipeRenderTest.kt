package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [insightsSwipe] 的渲染层回归守卫。
 *
 * ## 为什么不能只测纯函数
 *
 * 纯函数 `swipeOpensDrawer` 早就有四条用例，它们全绿，而真机上的手势必照样分派错：
 * 决定归属的那个 `startX` 从哪来，纯函数管不着。第一版实现用
 * `detectHorizontalDragGestures` 的 `onDragStart`，拿到的不是按下点而是「越过触摸阈值
 * 那一刻」的坐标——快速右滑时已经跑出几十像素，左缘那一带整段被判到内容区去，
 * 于是**左缘右滑打开的是洞察抽屉**（真机实测复现）。改用 `awaitFirstDown` 之后才对。
 *
 * 本测试守的就是这条：从边缘带**内侧**起手，哪怕拖到屏幕另一端，也必须整段留给
 * 会话抽屉——一次 `onReveal` 都不该收到，`onSettle` 也不该被调用。
 *
 * ## 为什么起点用 `edgeWidthPx - 1f` 而不是写死像素
 *
 * Robolectric 的 density 不是 1，`EdgeWidth`（24dp）换算成像素随配置变。用
 * `edgeWidthPx - 1f` 表达「刚刚在带内」。这个 1px 差正是分辨两种实现的关键：
 * 旧实现会在越过 touch slop 处才判起点，只要 slop > 1px 就会越过边界，
 * 归属翻到内容区，本测试立刻变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InsightsSwipeRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** 手势回传过的揭开比例，按先后顺序保留，用来断言「一次都没动过」。 */
    private val reveals = mutableListOf<Float>()

    /** `onSettle` 的入参；`null` 表示从未被调用（边缘手势就是这条路径）。 */
    private var settled: Boolean? = null

    /**
     * 组合里实际换算出的边缘带宽度（像素）。
     *
     * `createComposeRule` 的 `setContent` 全局只能调一次，所以这里用字段把值带出来，
     * 而不是再调一次 `setContent` 去读 density。
     */
    private var edgeWidthPx = 0f

    /** 铺一个透明的全屏宿主，挂上 [insightsSwipe]。 */
    private fun setHost() {
        compose.setContent {
            val edge = with(LocalDensity.current) { EdgeWidth.toPx() }
            edgeWidthPx = edge
            var reveal by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .testTag(TAG)
                    .fillMaxSize()
                    .insightsSwipe(
                        edgeWidthPx = edge,
                        reveal = { reveal },
                        onReveal = {
                            reveal = it
                            reveals += it
                        },
                        onSettle = { settled = it },
                    ),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun swipeStartingInsideTheEdgeBandNeverTouchesInsights() {
        setHost()
        assertTrue("边缘带宽度没被量到，用例无效", edgeWidthPx > 0f)
        // 起点打在边缘带内侧 1px 处，然后一路拖到屏幕右端：
        // 位移足够大（远超 35% 阈值），若归属判错，洞察一定会开。
        compose.onNodeWithTag(TAG).performTouchInput {
            swipeRight(startX = edgeWidthPx - 1f, endX = width - 10f, durationMillis = 200)
        }
        compose.waitForIdle()

        assertTrue(
            "边缘起手的手势不该改洞察的揭开比例，实际收到 $reveals",
            reveals.isEmpty(),
        )
        assertNull("边缘起手的手势不该被洞察结算", settled)
    }

    @Test
    fun swipeStartingOutsideTheEdgeBandOpensInsights() {
        setHost()
        assertTrue("边缘带宽度没被量到，用例无效", edgeWidthPx > 0f)
        // 起点在带外 40px，拖到右端：位移约等于视口宽度的四分之三，超过 35% 阈值。
        compose.onNodeWithTag(TAG).performTouchInput {
            swipeRight(startX = edgeWidthPx + 40f, endX = width - 10f, durationMillis = 200)
        }
        compose.waitForIdle()

        assertTrue("内容区起手应当把揭开比例推上去，实际收到 $reveals", reveals.isNotEmpty())
        assertTrue("拖过阈值松手应当判为打开", settled == true)
    }

    @Test
    fun settledValueReflectsTheFinalRevealNotTheStart() {
        setHost()
        assertTrue("边缘带宽度没被量到，用例无效", edgeWidthPx > 0f)
        // 只拖很短一段（约 8% 视口宽），松手应当回弹闭合而不是打开。
        compose.onNodeWithTag(TAG).performTouchInput {
            swipeRight(
                startX = edgeWidthPx + 40f,
                endX = edgeWidthPx + 40f + width * 0.08f,
                durationMillis = 600,
            )
        }
        compose.waitForIdle()

        assertEquals("位移不足阈值时松手必须判为闭合", false, settled)
    }

    private companion object {
        const val TAG = "insights-swipe-host"
    }
}
