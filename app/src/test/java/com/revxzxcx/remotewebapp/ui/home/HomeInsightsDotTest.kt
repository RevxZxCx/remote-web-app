package com.revxzxcx.remotewebapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldLightInsightsDot] / [costCentsOf] 的测试。
 *
 * ## 圆点的语义（原型 R050 第 1 节 + R049 用户纠正 m33325）
 *
 * 圆点是**提醒**，不是状态：它只在「上次打开洞察之后有新东西」时亮一次，打开即灭。
 * 所以判据必须是「与基线比较后变大」，而不是「当前是否有数据」。
 */
class HomeInsightsDotTest {

    // ── costCentsOf ────────────────────────────────────────────────────────

    @Test
    fun nullCostMeansUnknown() {
        // 读不到费用（没装 dsh-cost-meter）返回 -1 而不是 0：
        // 0 会让判定以为「费用从有变没」，从而误点亮。
        assertTrue(costCentsOf(null) < 0L)
    }

    @Test
    fun centsAreRoundedNotTruncated() {
        // 必须 roundToLong 而不是 toLong。8.7 * 100 在 double 里是 869.9999999999999，
        // 截断会得到 869 —— 金额没变却少了一分，圆点就会在每次刷新时闪一下。
        // 0.29 同理（28.999999999999996）。
        assertEquals(870L, costCentsOf(8.7))
        assertEquals(29L, costCentsOf(0.29))
        assertEquals(15519L, costCentsOf(155.19))
        assertEquals(30L, costCentsOf(0.1 + 0.2))
    }

    @Test
    fun zeroCostIsZeroCents() {
        assertTrue(costCentsOf(0.0) == 0L)
    }

    @Test
    fun nonFiniteCostIsTreatedAsUnknown() {
        assertTrue(costCentsOf(Double.NaN) < 0L)
        assertTrue(costCentsOf(Double.POSITIVE_INFINITY) < 0L)
    }

    // ── shouldLightInsightsDot ─────────────────────────────────────────────

    @Test
    fun noBaselineMeansNoDot() {
        // 冷启动后基线未建立（-1）：此时不该亮，否则每次开 App 都是亮点。
        assertFalse(shouldLightInsightsDot(sessions = 32, costCents = 15519, seenSessions = -1, seenCostCents = -1))
    }

    @Test
    fun switchingRangeNeverLightsTheDot() {
        // 切到更大的范围会让 sessionCount / cost 都变大，但那是同一批历史数据
        // 换了个统计窗口，不是「来了新东西」。范围不同时这一维不参与判定。
        assertFalse(shouldLightInsightsDot(30, 200L, 20, 100L, sameRange = false))
        // 同一档范围里同样的数字变化才该点亮（对照组，防止整条判据被短路掉）。
        assertTrue(shouldLightInsightsDot(30, 200L, 20, 100L, sameRange = true))
    }

    @Test
    fun switchingRangeAlsoSuppressesCostOnlyChange() {
        // 会话数没变、只有费用变大（范围变大后又会多出一些计价模型）。
        assertFalse(shouldLightInsightsDot(20, 250L, 20, 100L, sameRange = false))
        assertTrue(shouldLightInsightsDot(20, 250L, 20, 100L, sameRange = true))
    }

    @Test
    fun moreSessionsLightsTheDot() {
        assertTrue(shouldLightInsightsDot(sessions = 33, costCents = 15519, seenSessions = 32, seenCostCents = 15519))
    }

    @Test
    fun moreCostLightsTheDot() {
        assertTrue(shouldLightInsightsDot(sessions = 32, costCents = 16000, seenSessions = 32, seenCostCents = 15519))
    }

    @Test
    fun unchangedDataDoesNotLight() {
        assertFalse(shouldLightInsightsDot(sessions = 32, costCents = 15519, seenSessions = 32, seenCostCents = 15519))
    }

    @Test
    fun fewerSessionsDoesNotLight() {
        // 归档会话或切到更小的范围会让计数变小。这是「减少」不是「有新数据」，
        // 用 != 判定会让圆点在每次切范围时误亮。
        assertFalse(shouldLightInsightsDot(sessions = 31, costCents = 15519, seenSessions = 32, seenCostCents = 15519))
    }

    @Test
    fun lowerCostDoesNotLight() {
        assertFalse(shouldLightInsightsDot(sessions = 32, costCents = 15000, seenSessions = 32, seenCostCents = 15519))
    }

    @Test
    fun unknownCostOnEitherSideDoesNotParticipate() {
        // 任一侧读不到费用时，费用维度不参与判定——否则「插件刚装上」会让圆点误亮。
        assertFalse(shouldLightInsightsDot(sessions = 32, costCents = -1, seenSessions = 32, seenCostCents = 15519))
        assertFalse(shouldLightInsightsDot(sessions = 32, costCents = 15519, seenSessions = 32, seenCostCents = -1))
    }

    @Test
    fun unknownCostStillLightsOnNewSessions() {
        // 费用读不到不影响会话数维度的判定。
        assertTrue(shouldLightInsightsDot(sessions = 33, costCents = -1, seenSessions = 32, seenCostCents = -1))
    }
}
