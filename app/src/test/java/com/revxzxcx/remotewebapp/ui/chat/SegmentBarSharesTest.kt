package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口构成条的段宽计算（R041 第 ① 条）。
 *
 * 用户 m24881 的原话是「上下文条没有分类显示构成的样式」——修法是把占用条从
 * **单色进度条**换成**分段堆叠条**。字段宽度的算式必须能被单测验算，所以抽成了
 * [segmentBarShares] 这个纯函数。
 *
 * 这里守的三条不变量，正好对应三种会出错的写法：
 *
 *  - 各段之和 == 已用比例（不是 1.0）。漏了乘 `fraction` 会让七段铺满整条，
 *    余量消失，用户看到「窗口已满」。
 *  - 已用比例被夹到 0..1。桌面端压缩前的瞬时会给出超窗口值，不夹会得到负的
 *    余量宽度，`Row` 里直接崩。
 *  - `total <= 0` 时全 0 而不是 NaN。新的会话还没有累计量，除法会得到 NaN，
 *    它会静默传播到 `fillMaxWidth`，那一段就消失。
 */
class SegmentBarSharesTest {

    @Test
    fun sharesSumToTheUsedFractionNotToOne() {
        // 三段占累计的四分之三，窗口用了 40% → 三段合计必须正好是 0.4。
        val shares = segmentBarShares(listOf(30L, 30L, 15L, 25L), total = 100L, fraction = 0.40f)
        assertEquals(0.12f, shares[0], 1e-6f)
        assertEquals(0.12f, shares[1], 1e-6f)
        assertEquals(0.06f, shares[2], 1e-6f)
        assertEquals(0.10f, shares[3], 1e-6f)
        assertEquals(0.40f, shares.sum(), 1e-5f)
    }

    @Test
    fun fullWindowFillsTheWholeBar() {
        val shares = segmentBarShares(listOf(70L, 20L, 10L), total = 100L, fraction = 1f)
        assertEquals(1f, shares.sum(), 1e-5f)
    }

    @Test
    fun oversizedFractionIsClampedSoTheRemainderNeverGoesNegative() {
        // 压缩前的瞬时超窗口值：不夹会得到合计 1.5，余量那一截变成负数。
        val shares = segmentBarShares(listOf(50L, 50L), total = 100L, fraction = 1.5f)
        assertEquals(1f, shares.sum(), 1e-5f)
    }

    @Test
    fun negativeFractionYieldsZeroWidthSegments() {
        val shares = segmentBarShares(listOf(50L, 50L), total = 100L, fraction = -0.3f)
        assertTrue(shares.all { it == 0f })
    }

    @Test
    fun zeroTotalYieldsNoNaN() {
        // 新会话还没有累计量：除法会得到 NaN，它会让整段静默消失。
        val shares = segmentBarShares(listOf(12L, 0L), total = 0L, fraction = 0.5f)
        assertEquals(2, shares.size)
        assertTrue(shares.none { it.isNaN() })
        assertTrue(shares.all { it == 0f })
    }

    @Test
    fun nonPositiveSegmentContributesNothing() {
        val shares = segmentBarShares(listOf(0L, -5L, 100L), total = 100L, fraction = 0.5f)
        assertEquals(0f, shares[0], 1e-6f)
        assertEquals(0f, shares[1], 1e-6f)
        assertEquals(0.5f, shares[2], 1e-6f)
    }

    @Test
    fun percentTextKeepsTinySegmentsVisible() {
        // 七段里最后几段只有 0.01%：取整会全部显示成 0%，用户以为那几段不存在。
        assertEquals("0.04%", formatPercent(0.0004f))
        assertEquals("0.37%", formatPercent(0.0037f))
        assertEquals("6.4%", formatPercent(0.064f))
        assertEquals("6.4%", formatPercent(0.0640f))
        assertEquals("38%", formatPercent(0.38f))
        assertEquals("100%", formatPercent(1f))
        assertEquals("0%", formatPercent(0f))
    }

    @Test
    fun percentBoundariesSwitchPrecision() {
        // 10% 与 1% 是两个精度分界。
        assertEquals("10%", formatPercent(0.10f))
        assertEquals("1%", formatPercent(0.01f))
        assertEquals("0.99%", formatPercent(0.0099f))
    }

    @Test
    fun percentNeverRendersTheSameNumberTwoWays() {
        // 9.99% 按一位小数渲染是 `10.0%`，而 10% 走整数档渲染是 `10%`——同一个数字
        // 两种写法，读起来像两个不同的值。所以渲染完要去掉多余的小数零。
        assertEquals("10%", formatPercent(0.0999f))
        assertEquals(formatPercent(0.10f), formatPercent(0.0999f))
        // 反方向同理：0.999% 一位小数是 `1.0%`，而 1% 走整数档是 `1%`。
        assertEquals("1%", formatPercent(0.00999f))
        assertEquals(formatPercent(0.01f), formatPercent(0.00999f))
    }
}
