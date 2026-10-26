package com.revxzxcx.remotewebapp.ui.home

import kotlin.math.roundToLong

/**
 * 顶栏洞察按钮上那颗 5dp 圆点的判据（R050 定稿）。
 *
 * ## 语义：不是「有数据」，是「有你没看过的变化」
 *
 * 原型原文：「距离上次打开洞察后，若新增了会话或费用有变化就亮；打开抽屉即清除」。
 * 也就是说它是一个**提醒**，而不是一个**状态**——一直亮着等于没有提醒（R049 同一个教训：
 * 用户 m33325 明确说「这个点不是常驻的，而是一个完成的标记，提醒用户查看对话的」）。
 *
 * ## 为什么需要基线
 *
 * 没有基线时「有变化」无从判起。这里用两个数当基线：[seenSessions] / [seenCostCents]，
 * 每次打开抽屉时刷新成当时的值。`-1` 表示**还没建立基线**（进程刚起来、或用户从没打开过）。
 *
 * 没基线时返回 false、由调用方去播种，而不是直接点亮：冷启动的第一帧不是「变化」，
 * 一进 App 就挂个圆点只会让人以为是脏数据。
 *
 * ## 为什么只比「变大」
 *
 * 会话被归档、被删、切到更小的统计范围都会让计数**变小**。那些不是「新数据」，
 * 若写成 `!=` 就会在切范围时无端点亮。费用同理（金额不会变小，但缺插件时是 null）。
 *
 * @param sessions 当前统计到的会话数。
 * @param costCents 当前费用（分）；`-1` 表示**读不到**（没装 `dsh-cost-meter`）。
 * @param seenSessions 上次打开抽屉时的会话数；`-1` 表示还没建立基线。
 * @param seenCostCents 上次打开抽屉时的费用（分）；`-1` 表示当时读不到。
 * @param sameRange 当前统计范围是否与基线取自同一档。
 */
internal fun shouldLightInsightsDot(
    sessions: Int,
    costCents: Long,
    seenSessions: Int,
    seenCostCents: Long,
    sameRange: Boolean = true,
): Boolean {
    // 换了统计窗口：会话数与费用都会跟着变，但那是同一批历史数据换了个口径，
    // 不是「来了新东西」。基线是按旧口径取的，拿新口径去比毫无意义，
    // 所以这一维直接不参与判定（否则切到「全部」就会点亮一颗假圆点）。
    if (!sameRange) return false
    // 没基线：不是「变化」，交给调用方播种。
    if (seenSessions < 0) return false
    if (sessions > seenSessions) return true
    // 费用基线缺失（当时没装插件、或现在还是没装）时这一维不参与判定——
    // 把「读不到」当成 0 会让装上插件的那一刻凭空点亮。
    if (seenCostCents < 0L || costCents < 0L) return false
    return costCents > seenCostCents
}

/**
 * 费用换算成「分」，用于比较而不是显示。
 *
 * 圆点只需要判断「有没有变多」，直接比 `Double` 会被浮点累加的尾差（1e-13 级别）
 * 误判成变化，导致每刷新一次列表圆点就闪一下。取整到分足够灵敏：一次模型调用最少也值
 * 0.01 美元的量级，远大于这个粒度。
 *
 * @return 分；`cost` 为 null（没有 `costUsage` 投影）时返回 `-1`。
 */
internal fun costCentsOf(cost: Double?): Long {
    if (cost == null) return -1L
    if (cost.isNaN() || cost.isInfinite()) return -1L
    return (cost * 100.0).roundToLong()
}
