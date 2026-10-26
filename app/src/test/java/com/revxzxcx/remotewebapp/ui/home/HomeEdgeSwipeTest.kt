package com.revxzxcx.remotewebapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [swipeOpensDrawer] / [settleInsightsOpen] / [revealAfterDrag] 的测试。
 *
 * 这三个纯函数承载的是「首页右滑到底开哪个面板」的全部分歧逻辑，抽出来就是为了能在
 * 不启动 Compose 的情况下把它们钉死——手势本身在 Robolectric 里测不出真实手指行为。
 *
 * ## 用户拍板的规则（m35111）
 * 「左缘 24dp 内右滑 = 会话抽屉；其余区域右滑 = 洞察」
 */
class HomeEdgeSwipeTest {

    private val edge = 24f

    // ── swipeOpensDrawer ───────────────────────────────────────────────────

    @Test
    fun edgeStartOpensDrawer() {
        assertTrue(swipeOpensDrawer(0f, edge))
        assertTrue(swipeOpensDrawer(23.9f, edge))
    }

    @Test
    fun boundaryBelongsToInsights() {
        // 恰好等于边界算「内容区」：宁可让洞察多一个像素的可滑面积，
        // 因为抽屉还有顶栏 ☰ 入口而洞察只有手势和 ◔ 按钮。
        assertFalse(swipeOpensDrawer(24f, edge))
    }

    @Test
    fun anywhereElseOpensInsights() {
        assertFalse(swipeOpensDrawer(25f, edge))
        assertFalse(swipeOpensDrawer(500f, edge))
    }

    @Test
    fun zeroEdgeWidthNeverOpensDrawer() {
        // 兜底：edgeWidthPx 传 0（density 异常）时不能让所有手势都走抽屉。
        assertFalse(swipeOpensDrawer(0f, 0f))
    }

    // ── settleInsightsOpen ─────────────────────────────────────────────────

    @Test
    fun revealBelowThresholdCloses() {
        assertFalse(settleInsightsOpen(0.1f))
        assertFalse(settleInsightsOpen(0.34f))
    }

    @Test
    fun revealAtThresholdOpens() {
        assertEquals(0.35f, SETTLE_THRESHOLD, 1e-6f)
        assertTrue(settleInsightsOpen(SETTLE_THRESHOLD))
        assertTrue(settleInsightsOpen(0.9f))
    }

    @Test
    fun flingOpensEvenWhenBarelyRevealed() {
        // 快速一甩（位移小但速度快）应该开，否则用户会觉得「甩了没反应」。
        assertTrue(settleInsightsOpen(0.1f, velocity = 3f))
    }

    @Test
    fun slowDragBelowThresholdCloses() {
        assertFalse(settleInsightsOpen(0.2f, velocity = 0.5f))
    }

    @Test
    fun negativeRevealAlwaysCloses() {
        // 拖到负值（往左越过起点）必须收，不能被 fling 判据翻盘。
        assertFalse(settleInsightsOpen(-0.1f, velocity = 99f))
        assertFalse(settleInsightsOpen(0f, velocity = 99f))
    }

    // ── revealAfterDrag ────────────────────────────────────────────────────

    @Test
    fun dragRightIncreasesReveal() {
        // 屏宽 1000px，向右拖 250px → 揭开 0.25
        assertEquals(0.25f, revealAfterDrag(0f, 250f, 1000f), 1e-6f)
    }

    @Test
    fun dragLeftDecreasesReveal() {
        assertEquals(0.35f, revealAfterDrag(0.6f, -250f, 1000f), 1e-6f)
    }

    @Test
    fun revealIsClampedToUnitRange() {
        // 拖过头不能出现负值或 >1：负值会让遮罩 alpha 变负、>1 会让面板飞出屏幕。
        assertEquals(1f, revealAfterDrag(0.9f, 500f, 1000f), 1e-6f)
        assertEquals(0f, revealAfterDrag(0.1f, -500f, 1000f), 1e-6f)
    }

    @Test
    fun zeroScreenWidthLeavesRevealUnchanged() {
        // 屏宽未知时保持原值，避免除零得到 Infinity/NaN 让面板彻底消失。
        assertEquals(0.5f, revealAfterDrag(0.5f, 100f, 0f), 1e-6f)
        assertEquals(0.5f, revealAfterDrag(0.5f, 100f, -10f), 1e-6f)
    }

    @Test
    fun revealUsesScreenWidthNotSheetWidth() {
        // 除以抽屉宽度（320dp）会让手指还没走完面板就满格——刻意用屏宽。
        // 屏宽 1000 拖 320 只揭开 0.32，用抽屉宽 320 会得到 1.0。
        assertEquals(0.32f, revealAfterDrag(0f, 320f, 1000f), 1e-6f)
    }
}
