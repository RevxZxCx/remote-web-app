package com.revxzxcx.remotewebapp.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarrierState] / [MuxStats] 的单测。
 *
 * 这些文案是需求文档 FR-4.4 写死的四种全局状态（已连接 / 重连中 / 已断开 / 配对失效），
 * 所以把它放在协议层的纯函数里、用测试钉住 —— 否则 UI 一重构文案就漂了。
 *
 * 请求「已连接」这一条尤其重要：M3 的真机缺陷是 UI 在**空列表**上谎报「已连接」，
 * 根因是把「还没读到」当成了「确实没有」。状态条必须是**推导**出来的，不是乐观写死的。
 */
class CarrierStateTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `未尝试连接时是未连接`() {
        assertEquals("未连接", CarrierState.Idle.describe(now))
    }

    @Test
    fun `握手中时是连接中`() {
        assertEquals("连接中…", CarrierState.Connecting.describe(now))
    }

    @Test
    fun `已建立时是已连接`() {
        assertEquals("已连接", CarrierState.Connected(sinceMillis = now - 1_000).describe(now))
    }

    @Test
    fun `重连中带失败次数与剩余秒数`() {
        val state = CarrierState.Reconnecting(
            attempt = 3L,
            retryAtMillis = now + 8_000L,
            reason = "transport：网络请求失败",
        )
        assertEquals("重连中…（第 3 次失败，8s 后重试）", state.describe(now))
    }

    @Test
    fun `重试时刻已过时不出现负数秒`() {
        val state = CarrierState.Reconnecting(attempt = 1L, retryAtMillis = now - 5_000L, reason = "x")
        assertEquals("重连中…（第 1 次失败，0s 后重试）", state.describe(now))
    }

    @Test
    fun `剩余秒数向下取整`() {
        val state = CarrierState.Reconnecting(attempt = 2L, retryAtMillis = now + 1_999L, reason = "x")
        assertTrue(state.describe(now).contains("1s 后重试"))
    }

    @Test
    fun `配对失效是终态而不是重连`() {
        // 单独一个状态是为了让状态条能给出「重新配对」入口，而不是一直说「重连中…」。
        val text = CarrierState.Unpaired("设备未配对或已被桌面端撤销（HTTP 403）").describe(now)
        assertTrue(text.startsWith("配对已失效："))
        assertTrue(text.contains("403"))
    }

    @Test
    fun `只有已连接算健康`() {
        assertTrue(CarrierState.Connected(0L).isHealthy)
        assertTrue(!CarrierState.Idle.isHealthy)
        assertTrue(!CarrierState.Connecting.isHealthy)
        assertTrue(!CarrierState.Reconnecting(1L, 0L, "x").isHealthy)
        assertTrue(!CarrierState.Unpaired("x").isHealthy)
    }

    @Test
    fun `默认统计是全零且未连接`() {
        val stats = MuxStats()

        assertEquals(0L, stats.opens)
        assertEquals(0L, stats.failures)
        assertEquals(0L, stats.framesIn)
        assertEquals(0L, stats.framesOut)
        assertEquals(0L, stats.invalidFrames)
        assertEquals(0L, stats.orphanFrames)
        assertEquals(0L, stats.consecutiveFailures)
        assertEquals(CarrierState.Idle, stats.carrier)
        assertEquals(null, stats.lastFailure)
    }

    @Test
    fun `故障记录保留时刻与错误码`() {
        val error = MuxError(atMillis = now, summary = "握手超时", code = "transport")

        assertEquals(now, error.atMillis)
        assertEquals("transport", error.code)
    }
}
