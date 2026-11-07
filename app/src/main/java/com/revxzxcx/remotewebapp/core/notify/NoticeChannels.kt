package com.revxzxcx.remotewebapp.core.notify

import android.app.NotificationManager

/**
 * 两个通知频道（R056）。
 *
 * ## 为什么只有两个，不是三个
 * 原型里还有一条「连接状态」常驻通知——那是配合**前台服务**用的，服务活着就必须挂一条
 * 不可划掉的常驻通知。用户拍板 Q1 只做第一步（事件 → 通知，不引入前台服务），
 * 于是常驻通知没有了存在理由，频道也就不该提前建出来：空频道会出现在系统设置页里，
 * 用户点进去发现什么都没有。
 *
 * ## 频道一旦创建，重要性就不能改
 * 这是 Android 的硬规则（只有 `name` / `description` 可改）。所以两个频道的
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
     * 全部频道 id。
     *
     * 有这一份列表，[com.revxzxcx.remotewebapp.core.notify.NotificationCenter.ensureChannels]
     * 才是「遍历一遍」而不是「手写两次 createNotificationChannel」——以后加频道只改这里
     * 和上面三个 `when` 分支，漏改会编译报错（`when` 不是穷尽的话没有 else 也行，
     * 所以真正的保险是这里加一项就顺手想起另外三处）。
     */
    val ALL: List<String> = listOf(ASK, DONE)

    /** 频道名（系统设置页里显示的标题）。 */
    fun name(channelId: String): String = when (channelId) {
        ASK -> "需要确认"
        DONE -> "任务结束"
        else -> channelId
    }

    /** 频道说明（系统设置页里标题下面那行）。 */
    fun description(channelId: String): String = when (channelId) {
        ASK -> "模型要使用工具时请你批准或拒绝。关掉会错过请求，任务会一直等着。"
        DONE -> "一轮任务跑完或跑挂时提醒你。"
        else -> ""
    }

    /** 频道重要性；**只在首次创建时生效**，之后改这里不会影响已安装的设备。 */
    fun importance(channelId: String): Int = when (channelId) {
        ASK -> NotificationManager.IMPORTANCE_HIGH
        DONE -> NotificationManager.IMPORTANCE_DEFAULT
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }
}
