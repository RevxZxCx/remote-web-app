package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话列表里的时间文案。
 *
 * 为什么自己写而不是用 `DateUtils.getRelativeTimeSpanString`：那个 API 的输出受系统
 * Locale 影响，中文设备上会给出「3 分钟前」但英文设备给「3 min ago」，而本项目其余文案
 * 全是中文，混排会很难看。这里统一用固定中文文案。
 *
 * 分段：<1 分钟「刚刚」、<1 小时「N 分钟前」、当天「HH:mm」、昨天「昨天 HH:mm」、
 * 今年「M月d日」、更早「yyyy-MM-dd」。
 */
internal object TimeText {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDay = DateTimeFormatter.ofPattern("M月d日")
    private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun relative(epochMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return ""
        val deltaMs = nowMillis - epochMillis
        // 服务端时钟比手机快时会算出负数；当作「刚刚」而不是显示「-3 分钟前」。
        if (deltaMs < 60_000L) return "刚刚"

        val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minutes = deltaMs / 60_000L
        if (minutes < 60L && then.toLocalDate() == now.toLocalDate()) {
            return "$minutes 分钟前"
        }

        val days = now.toLocalDate().toEpochDay() - then.toLocalDate().toEpochDay()
        return when {
            days == 0L -> then.format(hhmm)
            days == 1L -> "昨天 ${then.format(hhmm)}"
            then.year == now.year -> then.format(monthDay)
            else -> then.format(fullDate)
        }
    }
}

/**
 * 每 30 秒跳一次的「当前时间」，用来驱动相对时间的重组。
 *
 * 直接在 Composable 里读 `System.currentTimeMillis()` 只在重组时求值：页面静止半小时后
 * 「5 分钟前」不会变成「35 分钟前」（R003 P3-1）。这里用 `produceState` 定时推进。
 *
 * 为什么放在本文件（而不是 `HomeScreen.kt`）：聊天页也要用它显示消息时间，
 * 放在时间工具旁边更合理；`internal` 可见性让同模块直接调用。
 */
@Composable
internal fun rememberNow(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    return now
}
