package com.revxzxcx.remotewebapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 半屏分区与回收方向的纯函数测试（R051）。
 *
 * ## 规则来源
 *
 * 用户 m36530 定的分区：**左半屏右滑 = 会话抽屉，右半屏左滑 = 洞察抽屉**。
 * 用户 m36671 选了 A 方案：抽屉**开着时反向滑收起**，没开时不响应。
 *
 * ## 为什么与 T076 的判据完全不同
 *
 * T076 是按「起点是否落在左缘 24dp」分派，两个抽屉都响应右滑，只能靠一条约 9mm
 * 的窄带仲裁；洞察抽屉在右边却要向右滑才出来，手指方向与面板方向相反。R051 改成
 * 按半屏分区，且方向与面板所在侧一致——往右推拉出左边的，往左推拉出右边的。
 */
class HomeEdgeSwipeTest {

    // ---------- 半屏分区 ----------

    @Test
    fun leftHalfOfTheScreenBelongsToTheDrawer() {
        assertEquals(SwipeSide.LEFT, swipeSide(startX = 0f, widthPx = 1000f))
        assertEquals(SwipeSide.LEFT, swipeSide(startX = 499f, widthPx = 1000f))
    }

    @Test
    fun exactMiddleBelongsToTheRightHalf() {
        // 中线归右半屏：`>=` 判据。理由是右半屏的动作在「洞察」这一个方向上是唯一的，
        // 而左半屏还有列表本身的纵向滚动要照顾；把边界判给右半边不会让会话抽屉
        // 永久拿不到中线附近的手势（那 1px 在真机上无法瞄准）。
        assertEquals(SwipeSide.RIGHT, swipeSide(startX = 500f, widthPx = 1000f))
    }

    @Test
    fun rightHalfOfTheScreenBelongsToInsights() {
        assertEquals(SwipeSide.RIGHT, swipeSide(startX = 501f, widthPx = 1000f))
        assertEquals(SwipeSide.RIGHT, swipeSide(startX = 1000f, widthPx = 1000f))
    }

    @Test
    fun zeroWidthFallsBackToTheDrawerSide() {
        // 布局还没测量出宽度时（首帧 `size.width` 为 0）不能把整个屏幕判成右半屏，
        // 否则首帧的任何手势都会尝试开洞察。
        assertEquals(SwipeSide.LEFT, swipeSide(startX = 0f, widthPx = 0f))
        assertEquals(SwipeSide.LEFT, swipeSide(startX = 999f, widthPx = 0f))
    }

    // ---------- 松手判定（沿用 T076，语义未变） ----------

    @Test
    fun revealBelowThresholdCloses() {
        assertFalse(settleInsightsOpen(reveal = 0.34f))
    }

    @Test
    fun revealAtThresholdOpens() {
        assertTrue(settleInsightsOpen(reveal = 0.35f))
    }

    @Test
    fun flingOpensEvenWhenBarelyRevealed() {
        // 快甩一下（超过 2.2 屏宽/秒）即使只揭开一点点也算「要打开」：用户明显有这个意图，
        // 让他松手后又弹回去是违背直觉的。
        assertTrue(settleInsightsOpen(reveal = 0.1f, velocity = 2.5f))
    }

    @Test
    fun slowDragBelowThresholdCloses() {
        assertFalse(settleInsightsOpen(reveal = 0.1f, velocity = 0.5f))
    }

    @Test
    fun negativeRevealAlwaysCloses() {
        assertFalse(settleInsightsOpen(reveal = -0.2f, velocity = 9f))
    }

    @Test
    fun cancelledDragAlwaysCloses() {
        // 手势被系统抢走（来电、下拉通知栏）时 `horizontalDrag` 返回 false。
        // 这时不管揭开了多少都要回弹——否则用户会看到一个卡在半路的抽屉。
        assertFalse(settleAfterDrag(completed = false, reveal = 0.9f, velocity = 0f))
        assertTrue(settleAfterDrag(completed = true, reveal = 0.9f, velocity = 0f))
    }

    // ---------- 跟手位移 ----------

    @Test
    fun draggingTowardTheRightRevealsLess() {
        // 洞察从**右侧**推出，所以「往左拖」（delta 为负）才是揭开方向。
        // R051 的关键换算：调用方传进来的 delta 已经乘过 -1（见 `insightsSwipe` 里的
        // `towardReveal = -delta`），这里只验「正的位移让 reveal 变大」。
        assertTrue(revealAfterDrag(current = 0.2f, deltaPx = 100f, screenWidthPx = 1000f) > 0.2f)
    }

    @Test
    fun draggingBackLowersTheReveal() {
        // A 方案的收起路径：抽屉开着（reveal = 1）时反向拖，reveal 必须跟着降。
        assertTrue(revealAfterDrag(current = 1f, deltaPx = -300f, screenWidthPx = 1000f) < 1f)
    }

    @Test
    fun revealIsClampedToUnitRange() {
        assertEquals(1f, revealAfterDrag(current = 1f, deltaPx = 500f, screenWidthPx = 1000f), 1e-6f)
        assertEquals(0f, revealAfterDrag(current = 0f, deltaPx = -500f, screenWidthPx = 1000f), 1e-6f)
    }

    @Test
    fun zeroScreenWidthLeavesRevealUnchanged() {
        assertEquals(0.4f, revealAfterDrag(current = 0.4f, deltaPx = 100f, screenWidthPx = 0f), 1e-6f)
    }

    @Test
    fun revealIsMeasuredAgainstTheScreenNotTheSheet() {
        // 除以面板宽（320dp ≈ 屏宽的 0.89）会让手指还没走完面板宽度就满格，
        // 手感上像「一碰就弹到底」。屏宽是唯一的自然度量。
        assertEquals(
            0.5f,
            revealAfterDrag(current = 0f, deltaPx = 500f, screenWidthPx = 1000f),
            1e-6f,
        )
    }

    // ---------- 收起方向的松手判定（镜像阈值） ----------
    //
    // 这一组守的是审查 P1：收起曾复用「打开」的判据（`reveal >= 0.35` 就开），
    // 而收起时 reveal 从 1 递减 → 必须拖过 65% 屏宽才判合，短距离快甩更是完全
    // 失效（速度是负值，永远到不了 FLING_VELOCITY）。原来的两条 COLLAPSE 渲染
    // 测试分别拖到 97% 与拖满整屏，恰好绕开了这个区间。

    @Test
    fun collapseJustPastTheMirrorThresholdCloses() {
        // 收回过半（reveal <= 0.65）→ 合。与打开时的「拖过 35% 就开」镜像对称。
        assertFalse(settleInsightsCollapse(reveal = 0.64f))
    }

    @Test
    fun collapseAtExactlyTheMirrorThresholdCloses() {
        // 边界取闭区间：reveal <= 1 - SETTLE_THRESHOLD 即算收回过半。
        assertFalse(settleInsightsCollapse(reveal = 0.65f, velocity = 0f))
    }

    @Test
    fun collapseJustAboveTheMirrorThresholdStaysOpen() {
        assertTrue(settleInsightsCollapse(reveal = 0.66f, velocity = 0f))
    }

    @Test
    fun collapseFlingClosesEvenWhenBarelyMoved() {
        // 只拖了 10%，但甩得够快（往收起方向）→ 合。这是手机上最常用的收起方式。
        assertFalse(settleInsightsCollapse(reveal = 0.9f, velocity = -2.5f))
    }

    @Test
    fun collapseSlowShortDragSnapsBackOpen() {
        // 只拖了 10% 又拖得很慢 → 当作「拖了但反悔」，回弹为开。
        assertTrue(settleInsightsCollapse(reveal = 0.9f, velocity = -0.3f))
    }

    @Test
    fun collapseWithoutAnyMovementStaysOpen() {
        assertTrue(settleInsightsCollapse(reveal = 1f))
    }

    @Test
    fun collapseAfterDragHonoursTheMode() {
        // 同一个 reveal/velocity，两种模式的结论必须相反——这正是 P1 的核心。
        val reveal = 0.5f
        val velocity = -2.5f
        assertTrue("打开模式在 0.5 处判开", settleAfterDrag(true, reveal, velocity, SwipeMode.REVEAL))
        assertFalse("收起模式在同一位置必须判合", settleAfterDrag(true, reveal, velocity, SwipeMode.COLLAPSE))
    }

    @Test
    fun interruptedCollapseKeepsTheSheetOpen() {
        // 被别的手势抢占（系统返回手势、上层对话框）时，回到「这次手势开始前的状态」。
        // COLLAPSE 是从开着起手的 → 必须保持开；恒返回 false 会把抽屉直接关掉。
        assertTrue(settleAfterDrag(false, 0.1f, -9f, SwipeMode.COLLAPSE))
    }

    @Test
    fun interruptedRevealStaysClosed() {
        // REVEAL 是从关着起手的 → 回到关。
        assertFalse(settleAfterDrag(false, 0.9f, 9f, SwipeMode.REVEAL))
    }

    @Test
    fun revealModeIsStillTheDefault() {
        // 默认参数不能被 COLLAPSE 的行为污染。
        assertEquals(settleAfterDrag(true, 0.5f, 0f), settleAfterDrag(true, 0.5f, 0f, SwipeMode.REVEAL))
    }

    // ── R052 需求 1：轴向锁定 ────────────────────────────────────────────
    //
    // 用户 m37463 第 1 条：洞察抽屉展开时上下拖动会被判成右滑，抽屉被收起、内容滚不动。
    // 根因是从前直接用 horizontalDrag —— 它只按水平轴判断是否越过 touchSlop，
    // 手指上下滑时一点水平抖动越限就被接管。这组用例守的是「先判主轴」。

    @Test
    fun pureVerticalDragNeverClaimsTheGesture() {
        // 竖直远大于水平：让给滚动容器。
        assertEquals(DragAxis.VERTICAL, dragAxis(dx = 3f, dy = 40f, slopPx = 8f))
    }

    @Test
    fun pureHorizontalDragClaimsTheGesture() {
        assertEquals(DragAxis.HORIZONTAL, dragAxis(dx = 40f, dy = 3f, slopPx = 8f))
    }

    @Test
    fun tinyMovementStaysUndecided() {
        // 还没越过 touchSlop，方向是随机的，不能提前选边。
        assertEquals(DragAxis.UNDECIDED, dragAxis(dx = 2f, dy = 3f, slopPx = 8f))
    }

    @Test
    fun diagonalDragPicksTheDominantAxis() {
        // 用 hypot 判「是否越过 slop」、用 abs 比较判主轴：
        // 斜向滑动时不能只看单轴是否越限就选边。
        assertEquals(DragAxis.HORIZONTAL, dragAxis(dx = 30f, dy = 10f, slopPx = 8f))
        assertEquals(DragAxis.VERTICAL, dragAxis(dx = 10f, dy = 30f, slopPx = 8f))
    }

    @Test
    fun aTieBetweenAxesGoesToScrolling() {
        // 平局判纵向（让给滚动）：滚动是常态操作，收起还有返回键/遮罩/顶栏三条路。
        assertEquals(DragAxis.VERTICAL, dragAxis(dx = 20f, dy = 20f, slopPx = 8f))
    }

    @Test
    fun slopIsMeasuredOnTheDiagonalNotOnOneAxis() {
        // dx=7/dy=7 单轴都不越 8 的 slop，但 hypot≈9.9 越了 → 必须已经判定方向。
        // 若用 max(abs(dx),abs(dy)) 判，这里会停在 UNDECIDED。
        assertEquals(DragAxis.UNDECIDED, dragAxis(dx = 5f, dy = 5f, slopPx = 8f))
        assertEquals(DragAxis.VERTICAL, dragAxis(dx = 7f, dy = 7f, slopPx = 8f))
    }
}
