package com.revxzxcx.remotewebapp.core.notify

import android.app.NotificationManager

/**
 * 三个通知频道（R056 的两个 + R057 的前台服务常驻条）。
 *
 * ## 为什么 R057 加了第三个
 * R056 时用户拍板 Q1「只做事件 → 通知，不引入前台服务」，因此没有常驻通知、
 * 也就不该提前建这个频道（空频道会出现在系统设置页里，用户点进去发现什么都没有）。
 * R057 用户改判「前台服务可以做，但要有开关」，常驻条随之有了存在理由，
 * 于是 [SERVICE] 现在真的会被用到。
 *
 * ## 频道一旦创建，重要性就不能改
 * 这是 Android 的硬规则（只有 `name` / `description` 可改）。所以每个频道的
 * 重要性必须一次定对——改错了只能换一个新 id，而换 id 会让用户此前对该频道的
 * 静音/重要标记全部失效。本轮定下的取值理由见各自 KDoc。
 */
object NoticeChannels {

    /**
     * 需要用户确认的请求（工具审批）。
     *
     * `IMPORTANCE_HIGH` + 响铃振动：这是 **waterfall** 请求，服务端把请求抛给第一个
     * 愿意回答的远程监听者然后**等**。用户看不见就会一直卡到请求自己的 signal 超时，
     * 表现是「桌面端问我要不要允许，手机一声不吭，过一会儿工具自己失败了」。
     * 所以它必须能打断用户，不能只是一个安静的小图标。
     */
    const val ASK: String = "ask"

    /**
     * 一轮跑完 / 跑挂了。
     *
     * `IMPORTANCE_DEFAULT`：要响一声，但不该像审批那样横幅打断。同一个会话反复跑时
     * 用固定 id 原地更新 + `setOnlyAlertOnce`，连跑五轮也只响第一次
     * （否则每轮都响，用户会直接把这个频道关掉——那是比不响更坏的结果）。
     */
    const val DONE: String = "done"

    /**
     * R057：保持后台连接时挂的那条常驻通知。
     *
     * `IMPORTANCE_MIN`：**不响、不震动、通知栏里默认折叠到最下面**。
     *
     * 这一条的职责不是「提醒」，而是「系统要求前台服务必须有一条通知，否则服务会被降级」。
     * 它必须存在，但不能打扰人——用户开着后台连接是要「任务跑完时能收到提醒」，
     * 不是要一条一直亮着的横幅。真正要提醒的事走 [ASK] / [DONE]，这里只回答
     * 「服务还在不在、连上没有」。
     *
     * ⚠️ MIN 意味着它会被折叠、用户可能根本看不见。这是有意的：状态明细在设置页
     * 「后台连接 · 服务状态」那一行里，通知栏只是一个不得不存在的最低限度载体。
     */
    const val SERVICE: String = "service"

    /**
     * 全部频道 id。
     *
     * 有这一份列表，[com.revxzxcx.remotewebapp.core.notify.NotificationCenter.ensureChannels]
     * 才是「遍历一遍」而不是「手写三次 createNotificationChannel」——以后加频道只改这里
     * 和上面三个 `when` 分支，漏改会编译报错（`when` 不是穷尽的话没有 else 也行，
     * 所以真正的保险是这里加一项就顺手想起另外三处）。
     */
    val ALL: List<String> = listOf(ASK, DONE, SERVICE)

    /** 频道名（系统设置页里显示的标题）。 */
    fun name(channelId: String): String = when (channelId) {
        ASK -> "需要确认"
        DONE -> "任务结束"
        SERVICE -> "后台连接"
        else -> channelId
    }

    /** 频道说明（系统设置页里标题下面那行）。 */
    fun description(channelId: String): String = when (channelId) {
        ASK -> "模型要使用工具时请你批准或拒绝。关掉会错过请求，任务会一直等着。"
        DONE -> "一轮任务跑完或跑挂时提醒你。"
        SERVICE -> "保持后台连接时挂在这里的状态条，不响铃。关掉会让后台连接无法维持。"
        else -> ""
    }

    /** 频道重要性；**只在首次创建时生效**，之后改这里不会影响已安装的设备。 */
    fun importance(channelId: String): Int = when (channelId) {
        ASK -> NotificationManager.IMPORTANCE_HIGH
        DONE -> NotificationManager.IMPORTANCE_DEFAULT
        SERVICE -> NotificationManager.IMPORTANCE_MIN
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    /**
     * 该类里哪些频道属于「App 自己的功能开关」，哪些是常驻状态条。
     *
     * 设置页的「提醒我」分区只列前者；[SERVICE] 的开关是「保持后台连接」那一项本身，
     * 在这里再给一个开关会造成两个开关管同一件事。
     */
    val USER_TOGGLES: List<String> = listOf(ASK, DONE)
}
