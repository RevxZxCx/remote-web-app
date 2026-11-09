package com.revxzxcx.remotewebapp.core.service

/**
 * 时长格式化：把「已连接多久」写成人话。
 *
 * ## 为什么单独一个函数而不是各写一遍
 * 同一段时长会出现在三个地方：常驻通知展开后的正文、设置页的「服务状态」行、
 * 以及「已断开 42 秒」这句。三处口径必须一致——否则用户会看到通知说「2 小时 14 分」、
 * 设置页说「2 小时」，而这两处说的其实是同一件事。
 *
 * ## 为什么最多只显示两级
 * 「2 小时 14 分 33 秒」在通知栏里会换行，而且秒级精度对「这服务活了多久」
 * 这个问题没有意义——它不是倒计时。只在不到一分钟时才用秒。
 */
fun formatDuration(millis: Long): String {
    // 时钟回拨（NTP 校正、用户改时间）会让差值变成负数。写「-3 秒」不如写「0 秒」。
    val seconds = (millis.coerceAtLeast(0L)) / 1000L
    if (seconds < 60L) return "$seconds 秒"

    val totalMinutes = seconds / 60L
    if (totalMinutes < 60L) return "$totalMinutes 分钟"

    val totalHours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    if (totalHours < 24L) {
        // 整点时不写「0 分」：读起来像没算完。
        return if (minutes == 0L) "$totalHours 小时" else "$totalHours 小时 $minutes 分"
    }

    val days = totalHours / 24L
    val hours = totalHours % 24L
    return if (hours == 0L) "$days 天" else "$days 天 $hours 小时"
}

/**
 * 常驻通知专用时长：**六十秒内按五秒一档取整**。
 *
 * ## 为什么不直接用 [formatDuration]
 * 通知的开销不在画一次，而在**每画一次都会让整个通知栏动一下**：系统要重新布局、
 * 无障碍服务要重新解析。真机上表现为 `uiautomator dump` 恒报
 * `ERROR: could not get idle state`——连截图工具都等不到静止态。
 *
 * `formatDuration` 在六十秒内是秒级的，而 [com.revxzxcx.remotewebapp.core.service.ServiceStatusTracker]
 * 每秒 `tick()` 一次推进时长。于是「刚连上」和「刚断开」这两段的头一分钟里，
 * 通知每秒重发一次，正是要消除的那个现象（第五轮审查 P2）。
 *
 * 五秒一档是刻意的取舍：一分钟内用户看不出「12 秒」和「15 秒」的差别，
 * 而通知重发次数降到原来的五分之一；跨过一分钟之后 [formatDuration] 本来就是分钟级，
 * 两段口径自然接上，不需要额外分支。
 *
 * ## 与设置页的分工
 * 设置页那一行是用户主动看的，秒级跳动不会打扰任何人，所以它继续用 [formatDuration]。
 * 只有会去动通知栏的那几处才用本函数。
 */
fun formatNoticeDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L)) / 1000L
    if (seconds >= 60L) return formatDuration(millis)
    // 向上取整到 5 秒档：0~4 秒都写「0 秒」不合适（会闪一下「0 秒」），
    // 所以至少写「5 秒」，让「刚开始」有个像样的起点。
    val bucket = ((seconds + 4L) / 5L) * 5L
    return "${bucket.coerceAtLeast(5L)} 秒"
}

/**
 * 常驻通知的四态文案（R057 原型第 3 节）。
 *
 * ## 「连接中」与「已连接」必须分开写
 * 曾经这两相共用一句「正在连接桌面端」，理由是「对用户含义相同——后台有个东西在跑」。
 * 这是错的：通知默认就是**收起态**，用户不展开只看得到标题。于是连上几个小时、
 * 正文里明明写着「已连接 2 小时」，标题却一直说「正在连接」——用户会以为它坏了，
 * 或者以为自己被卡住了。第四轮审查 P1 抓的就是这条，直接违反「状态如实写」。
 *
 * ## 「重连中」必须换标题
 * 它意味着**此刻收不到任何通知**。这是用户必须知道的事实，藏进正文等于没说。
 *
 * ## 「已停止」没有文案
 * 服务停了常驻通知就撤掉，不留残影（用户 Q4 的口径：退出就是真的退干净）。
 * 所以这里对 [ServicePhase.Stopped] 返回空标题，调用方据此撤通知而不是发一条空通知。
 */
object ServiceText {

    /** 收起态标题。`Stopped` 返回空串——调用方看到空串就撤通知。 */
    fun title(status: ServiceStatus): String = when (status.phase) {
        ServicePhase.Connecting -> "正在连接桌面端"
        // 标题里就带上时长：收起态也能一眼看出「连着、而且连了多久」，
        // 不必展开。`formatDuration` 的最粗一级是「天」，再长也不会把标题撑爆。
        //
        // 🔴 这里用 `formatNoticeDuration` 而不是 `formatDuration`（第五轮审查 P2）：
        // 标题是通知的一部分，时长每秒变一次就等于每秒重发一遍通知，
        // 通知栏永远进不了静止态（真机表现：`uiautomator dump` 报 could not get idle state）。
        ServicePhase.Connected -> "已连接桌面端 · ${formatNoticeDuration(status.connectedForMillis)}"
        ServicePhase.Reconnecting -> "正在重连桌面端…（第 ${status.attempt.coerceAtLeast(1)} 次）"
        // 与「重连中」一样必须换标题：它同样意味着**此刻收不到任何通知**，
        // 而且比重连更糟——重连会自己好，这个不会。藏进正文等于让用户一直等。
        ServicePhase.Unpaired -> "重新配对才能继续"
        ServicePhase.Stopped -> ""
    }

    /**
     * 展开态正文。
     *
     * 第一行是**这个功能对用户的意义**，不是技术说明：他要的是「任务跑完会提醒我」，
     * 不是「前台服务在运行」。第二行才是状态。
     */
    fun text(status: ServiceStatus): String = when (status.phase) {
        ServicePhase.Connecting ->
            "$PROMISE\n正在建立连接。"
        ServicePhase.Connected ->
            "$PROMISE\n已连接 ${formatNoticeDuration(status.connectedForMillis)}。"
        ServicePhase.Reconnecting ->
            "如果一直连不上，检查桌面端是否开着。\n已断开 ${formatNoticeDuration(status.downForMillis)}。"
        ServicePhase.Unpaired -> {
            val reason = status.unpairedReason.trim()
            val tail = if (reason.isEmpty()) "" else "\n桌面端的说明：$reason"
            "这台手机已经不被桌面端认了，重试没有用。\n需要回到 App 里重新配对一次。$tail"
        }
        ServicePhase.Stopped -> ""
    }

    /** 「退出」按钮上的字（用户要的就是这个词，不是「停止服务」）。 */
    const val EXIT_LABEL: String = "退出"

    /** 通知栏里的应用名下方那行小字（`setSubText`），说明这一条为什么划不掉。 */
    const val SUB_TEXT: String = "后台连接"

    /**
     * 设置页「服务状态」那一行。
     *
     * 与通知里的正文同一份口径，但去掉「任务跑完会提醒你」那句承诺——
     * 设置页上方已经写过了，这一段只负责回答「现在什么状态」。
     */
    fun statusLine(status: ServiceStatus): String = when (status.phase) {
        ServicePhase.Connecting -> "启动中 · 正在连接桌面端"
        ServicePhase.Connected -> "运行中 · 已连上桌面端 · ${formatDuration(status.connectedForMillis)}"
        ServicePhase.Reconnecting -> "运行中 · 正在重连（第 ${status.attempt.coerceAtLeast(1)} 次）· 已断开 ${formatDuration(status.downForMillis)}"
        ServicePhase.Unpaired -> "运行中 · 需要重新配对"
        ServicePhase.Stopped -> "未运行"
    }

    /** 自动恢复关着、服务被停时发的那条通知的标题。 */
    fun stoppedTitle(): String = "后台连接断了"

    /** 同上那条通知的正文。说清「点了会发生什么」，而不是「请重试」。 */
    fun stoppedText(): String = "任务跑完时可能提醒不到你。点这里去打开。"

    private const val PROMISE = "任务跑完、要你确认时会在这里提醒。"
}
