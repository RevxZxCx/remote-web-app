package com.revxzxcx.remotewebapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [TimeText] 的单测。
 *
 * 固定时区 `Asia/Shanghai`，不依赖运行机器的时区，否则 CI 与本地会得到不同结果。
 */
class TimeTextTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 13, 15, 30)

    @Test
    fun `0 或负数时间戳返回空串`() {
        assertEquals("", TimeText.relative(0L, now, zone))
        assertEquals("", TimeText.relative(-5L, now, zone))
    }

    @Test
    fun `一分钟以内显示刚刚`() {
        assertEquals("刚刚", TimeText.relative(now - 59_000L, now, zone))
    }

    @Test
    fun `服务端时钟超前也显示刚刚而不是负数`() {
        // 手机时钟比服务端慢时会出现未来时间戳。
        assertEquals("刚刚", TimeText.relative(now + 30_000L, now, zone))
    }

    @Test
    fun `一小时内显示分钟数`() {
        assertEquals("1 分钟前", TimeText.relative(now - 60_000L, now, zone))
        assertEquals("59 分钟前", TimeText.relative(now - 59 * 60_000L, now, zone))
    }

    @Test
    fun `跨小时但仍是当天显示时刻`() {
        assertEquals("09:05", TimeText.relative(at(2026, 10, 13, 9, 5), now, zone))
    }

    @Test
    fun `昨天带昨天前缀`() {
        assertEquals("昨天 23:10", TimeText.relative(at(2026, 10, 12, 23, 10), now, zone))
    }

    @Test
    fun `前天且同年显示月日`() {
        assertEquals("10月10日", TimeText.relative(at(2026, 10, 10, 8, 0), now, zone))
    }

    @Test
    fun `跨年显示完整日期`() {
        assertEquals("2025-12-31", TimeText.relative(at(2025, 12, 31, 22, 0), now, zone))
    }

    @Test
    fun `跨天即使不足一小时也按昨天显示而不是分钟`() {
        // 23:50 到次日 00:20：delta 是 30 分钟，但已经跨天。显示「昨天 23:50」比
        // 「30 分钟前」更有信息量——用户看到日期才知道这是昨晚那条。
        val justAfterMidnight = at(2026, 10, 13, 0, 20)
        val lateLastNight = at(2026, 10, 12, 23, 50)
        assertEquals("昨天 23:50", TimeText.relative(lateLastNight, justAfterMidnight, zone))
    }

    @Test
    fun `刚好满一分钟且跨天时按昨天显示`() {
        // 23:59:00 → 次日 00:00:00 恰好 60_000ms，不再算「刚刚」，且已跨天 → 昨天。
        val justAfterMidnight = at(2026, 10, 13, 0, 0)
        val lastMinute = at(2026, 10, 12, 23, 59)
        assertEquals("昨天 23:59", TimeText.relative(lastMinute, justAfterMidnight, zone))
    }
}
