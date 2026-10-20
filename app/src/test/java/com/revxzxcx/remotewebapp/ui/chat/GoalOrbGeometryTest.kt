package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标横幅 / 顶栏目标圆的呼吸环几何（用户 m17042 第 4 条）。
 *
 * ## 为什么这些断言需要一个纯函数
 *
 * 「大圆圈在呼吸」这件事在 Robolectric 下**无法断言**：`rememberInfiniteTransition` 在
 * 测试时钟里不会自己推进，而且 `Canvas` 画出来的像素也不可读。能守住的只有一件事——
 * 给定进度算出的半径与 alpha 是否落在正确的轨道上。所以 `goalOrbRing` 被抽成纯函数，
 * 绘制代码与测试共用它，改绘制逻辑很难绕开这些断言。
 *
 * ## 这些断言各自防的是什么
 *
 * 三条性质都是「写错了界面会坏，但不会崩」的那一类：环被 `Canvas` 边界硬切出直边、
 * 环从圆外凭空冒出来、环在半途就淡没了看着像掉帧。这类缺陷没法靠「跑一遍没报错」发现，
 * 只能靠把数字钉死。
 */
class GoalOrbGeometryTest {

    private val disc = 13f
    private val max = 17f

    @Test
    fun `进度 0 时环从实心圆边缘出发`() {
        val ring = goalOrbRing(0f, disc, max)

        assertEquals("环的起点必须正好是实心圆边缘，否则圆外会凭空冒出一条线", disc, ring.radius, 0.001f)
        assertEquals("起点是全不透明", 0.55f, ring.alpha, 0.001f)
    }

    @Test
    fun `进度 1 时环抵达格子边缘且完全透明`() {
        val ring = goalOrbRing(1f, disc, max)

        assertEquals("环必须正好停在格子边缘，超出会被 Canvas 裁成直边", max, ring.radius, 0.001f)
        assertEquals("终点必须完全透明", 0f, ring.alpha, 0.001f)
    }

    @Test
    fun `中途的环严格落在起点与终点之间`() {
        val ring = goalOrbRing(0.5f, disc, max)

        assertTrue("半径要单调向外", ring.radius > disc && ring.radius < max)
        assertEquals("半径线性推进", (disc + max) / 2f, ring.radius, 0.001f)
        assertEquals("alpha 线性衰减到一半", 0.275f, ring.alpha, 0.001f)
    }

    @Test
    fun `alpha 随进度单调递减且从不为负`() {
        var previous = Float.MAX_VALUE
        var progress = 0f
        while (progress <= 1f) {
            val alpha = goalOrbRing(progress, disc, max).alpha
            assertTrue("alpha 不能随进度回升（在 $progress 处）", alpha <= previous)
            assertTrue("alpha 不能为负（在 $progress 处）", alpha >= 0f)
            previous = alpha
            progress += 0.05f
        }
    }

    @Test
    fun `越界的进度被夹回 0 到 1`() {
        // 动画的 progress 理论上不会越界，但 `coerceIn` 让「越界也不会画出格子外的环」
        // 成为这个函数的性质，而不是调用方的责任。
        assertEquals(goalOrbRing(0f, disc, max), goalOrbRing(-3f, disc, max))
        assertEquals(goalOrbRing(1f, disc, max), goalOrbRing(7f, disc, max))
    }

    @Test
    fun `实心圆按 26 比 34 的格子比例取半径`() {
        // 这条对应绘制代码里的 `discRadius = maxRadius * (GOAL_ORB_DISC_DP / GOAL_ORB_BOX_DP)`。
        // 两个常量任一被改动（比如把格子从 34 调成 40 却忘了调整比例），实心圆就会相对
        // 格子变小或变大——用户看到的是「加动效之后大圆圈缩水了」。
        val boxHalf = 34f / 2f
        val expected = boxHalf * (26f / 34f)
        assertEquals("26dp 圆在 34dp 格子里应占 13", 13f, expected, 0.001f)
        assertEquals("圆心不变，格子与圆同心", 0f, expected - disc, 0.001f)
    }
}
