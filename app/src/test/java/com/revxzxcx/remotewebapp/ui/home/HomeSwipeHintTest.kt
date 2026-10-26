package com.revxzxcx.remotewebapp.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Rule

/**
 * 首页两条「可以滑」提示的回归测试（R051）。
 *
 * ## 为什么提示逻辑必须落盘、也必须被测
 *
 * 半屏分区规则本身看不见，提示是用户学会手势的唯一途径。它有两条硬性语义：
 * **左右各自独立**（只学会一侧不该让另一侧也消失）、**看过即永久消失**（每次冷启动
 * 重新教育一遍是纯噪声）。这两条都靠 `SwipeHintPreferences` 落地，一旦退化成内存标记，
 * 冷启动就会重新出现——单测里「换一个新实例仍然读得到」正是守这个的。
 *
 * ## 渲染部分能测到什么、测不到什么（诚实记录）
 *
 * 提示胶囊对读屏 `clearAndSetSemantics` 隐形，这是刻意的设计（读屏用户用的是顶栏的
 * ☰ 与 ◔，两条纯视觉引导对他们既读不出也做不了）。代价是**语义树里根本找不到这两段
 * 文字**，所以「胶囊确实画出来了」无法用 `onNodeWithText` 证明——Robolectric 也没有
 * 真实字形度量（工程内已多次记录该局限）。设备截图是那部分的证据。
 *
 * 因此下面两条渲染断言的方向是**反的**，但依然有守护价值：断言「文字不在语义树里」，
 * 一旦有人删掉 `clearAndSetSemantics`，文字立刻可被找到、用例变红。变异验证已确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeSwipeHintTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun prefs(): SwipeHintPreferences =
        SwipeHintPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun freshInstallShowsBothHints() {
        val prefs = prefs()
        // 全新的安装：两侧都没看过，两条提示都该出现。
        assertFalse(prefs.seen(SwipeSide.LEFT))
        assertFalse(prefs.seen(SwipeSide.RIGHT))
    }

    @Test
    fun markingOneSideLeavesTheOtherAlone() {
        val prefs = prefs()
        prefs.markSeen(SwipeSide.LEFT)
        // 「只学会了一侧」不能把另一侧也判定为看过，否则用户永远学不到第二个手势。
        assertTrue(prefs.seen(SwipeSide.LEFT))
        assertFalse(prefs.seen(SwipeSide.RIGHT))
    }

    @Test
    fun markSeenIsIdempotent() {
        val prefs = prefs()
        prefs.markSeen(SwipeSide.RIGHT)
        prefs.markSeen(SwipeSide.RIGHT)
        assertTrue(prefs.seen(SwipeSide.RIGHT))
    }

    @Test
    fun seenSurvivesANewInstance() {
        prefs().markSeen(SwipeSide.LEFT)
        // 换一个新实例读——这条守的是「真的落盘了」。若实现退化成内存字段，
        // 这里会读到 false，冷启动就会重新弹一遍提示。
        assertTrue(prefs().seen(SwipeSide.LEFT))
    }

    @Test
    fun bothSidesPersistIndependently() {
        val prefs = prefs()
        prefs.markSeen(SwipeSide.LEFT)
        prefs.markSeen(SwipeSide.RIGHT)
        val reread = prefs()
        assertTrue(reread.seen(SwipeSide.LEFT))
        assertTrue(reread.seen(SwipeSide.RIGHT))
    }

    @Test
    fun hintsAreInvisibleToTheScreenReader() {
        compose.setContent {
            SwipeHints(showLeft = true, showRight = true, modifier = Modifier.testTag("hints"))
        }
        compose.waitForIdle()
        // 容器在（证明这一层确实被组合出来了）。
        compose.onNodeWithTag("hints").assertExists()
        // 两段文字都不在语义树里：删掉 clearAndSetSemantics 会让这两条立刻变成 1。
        compose.onAllNodesWithText("右滑看会话").assertCountEquals(0)
        compose.onAllNodesWithText("左滑看洞察").assertCountEquals(0)
        // 箭头也一并隐形，否则读屏会单独念一个「→」。
        compose.onAllNodesWithText("→").assertCountEquals(0)
        compose.onAllNodesWithText("←").assertCountEquals(0)
    }

    @Test
    fun hiddenHintsAlsoStayOutOfTheSemanticsTree() {
        compose.setContent {
            SwipeHints(showLeft = false, showRight = false, modifier = Modifier.testTag("hints"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("hints").assertExists()
        compose.onAllNodesWithText("右滑看会话").assertCountEquals(0)
        compose.onAllNodesWithText("左滑看洞察").assertCountEquals(0)
    }
}
