package com.revxzxcx.remotewebapp.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.revxzxcx.remotewebapp.MainActivity
import com.revxzxcx.remotewebapp.R
import com.revxzxcx.remotewebapp.core.notify.NoticeActionReceiver
import com.revxzxcx.remotewebapp.core.notify.NoticeChannels
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter

/**
 * 前台服务那条常驻通知（R057）。
 *
 * ## 为什么单独一个类，而不是塞进 [NotificationCenter]
 * 这条通知与 [NotificationCenter] 管的那些有一个根本区别：**它的生命周期由服务决定**，
 * 不是由事件决定。`NotificationCenter` 的所有方法都是「发一条 → 事件结束」，
 * 而这条要被服务反复原地刷新（时长每秒都在变），且**必须在 `startForeground()` 之前
 * 就构建好**（服务启动的 5 秒窗口内必须挂上通知，否则 `ANR`／`RemoteServiceException`）。
 * 两条约束加起来，它该有自己的位置。
 *
 * ## 折叠与展开
 * 收起态只显示 [ServiceText.title] 一行——用户扫一眼要能判断「后台还活着吗」。
 * 展开态（`BigTextStyle`）给出承诺、状态、时长与「退出」按钮。
 *
 * ## 「退出」按钮只撤本次
 * 它走 [NoticeActionReceiver] 的 `ACTION_EXIT_SERVICE` 分支，语义是「这次不要了」，
 * **不改开关**。用户的原话是「这个按钮只影响这一次的前台服务状态，后续启动还是会启动
 * 前台服务」，所以它对应的实现是 [ServiceExitMarker.markExited]，一个只活在内存里的标记。
 */
class ServiceNotice(private val context: Context) {

    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * 构建通知。
     *
     * 对 [ServicePhase.Stopped] 返回 `null`：服务停了这条就该撤掉，发一条「已停止」的
     * 常驻通知等于用户永远关不掉它——前台服务的通知不能划掉，停了还挂着就是 bug。
     */
    fun build(status: ServiceStatus): Notification? {
        if (status.phase == ServicePhase.Stopped) return null

        val title = ServiceText.title(status)
        val text = ServiceText.text(status)

        return NotificationCompat.Builder(context, NoticeChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_notice_service)
            .setColor(NotificationCenter.ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // 点通知主体 = 打开 App 停在设置页的「后台连接」段（用户 Q5 拍板：
            // 恢复通知落在设置页，常驻条同理——用户点它最可能就是想改这几个开关）。
            .setContentIntent(openSettingsIntent())
            .setSubText(ServiceText.SUB_TEXT)
            // 前台服务的通知默认就不可划掉；显式写一次是为了让意图留在代码里，
            // 而不是依赖「startForeground 会自动设 ongoing」这个隐含行为。
            .setOngoing(true)
            // 🔴 必须 true。时长每秒都在变，若允许每次提醒，通知栏会连响一整天。
            .setOnlyAlertOnce(true)
            // MIN 频道本来就不响，这里再压一层：`setSilent` 是给那些
            // 「频道被用户手动调高到 DEFAULT/HIGH」的情况兜底的 ——
            // 用户调高是希望**别的**通知响，不是希望这条每秒响一次。
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(exitAction())
            .build()
    }

    /** 撤掉常驻条（服务停止 / 用户点了退出）。 */
    fun cancel() {
        manager?.cancel(NOTICE_ID)
    }

    /**
     * 「退出」按钮。
     *
     * requestCode 固定：这条通知只有一个动作，不需要防覆盖。
     *
     * 走广播而不是 Activity：用户点「退出」的意思是「别再连了」，
     * 把他拉到前台来看一个已经停掉的服务页是反效果。
     */
    private fun exitAction(): NotificationCompat.Action {
        val intent = Intent(context, NoticeActionReceiver::class.java).apply {
            action = NotificationCenter.ACTION_EXIT_SERVICE
            setPackage(context.packageName)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            EXIT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, ServiceText.EXIT_LABEL, pending).build()
    }

    /**
     * 打开 App 并落到设置页。
     *
     * 复用 [NotificationCenter.ACTION_VIEW]，但**不带** `EXTRA_SESSION_ID`——
     * `MainActivity.dispatchIntent` 在没有会话 id 时什么都不投递，App 就正常打开；
     * 而「打开后停在设置页」这个意图靠 [NoticeIntents.offerSettings] 的独立信箱传，
     * 与会话信箱分开，因为两者可能同时存在（一个通知带 id、另一个不带）。
     */
    private fun openSettingsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = NotificationCenter.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationCenter.EXTRA_OPEN_SETTINGS, true)
        }
        return PendingIntent.getActivity(
            context,
            SETTINGS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /**
         * 常驻条的 id。
         *
         * 放在 [NotificationCenter] 那一段高位 id 之后：`LINK_ID = 151_072`、
         * `ACCOUNT_ID = 151_073`，这里取 151_074。不共用是因为「连不上桌面端」
         * 与「后台连接在跑」是两件事，随时可能同时成立。
         */
        const val NOTICE_ID: Int = 151_074

        /** 只有这一个动作，固定即可。 */
        private const val EXIT_REQUEST_CODE = 151_074

        /** 与「退出」不同 requestCode，否则两个 PendingIntent 会互相顶掉。 */
        private const val SETTINGS_REQUEST_CODE = 151_075
    }
}

/**
 * 确保 [NoticeChannels.SERVICE] 频道存在。
 *
 * 单独一个函数而不是塞进 `NotificationCenter.ensureChannels()`：服务可能在
 * **App 完全没启动过**的情况下被拉起（开机自启），那时 `bootstrap()` 从没跑过。
 * 服务启动的 5 秒窗口里不能依赖别处的初始化，必须自己保证频道在。
 *
 * 幂等：`createNotificationChannel` 对已存在的同 id 频道只会更新名称与描述，
 * 不会重置用户改过的重要性——这正是我们要的语义。
 */
fun ensureServiceChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val channel = NotificationChannel(
        NoticeChannels.SERVICE,
        NoticeChannels.name(NoticeChannels.SERVICE),
        NoticeChannels.importance(NoticeChannels.SERVICE),
    ).apply {
        description = NoticeChannels.description(NoticeChannels.SERVICE)
        setShowBadge(false)
        enableVibration(false)
        setSound(null, null)
    }
    manager.createNotificationChannel(channel)
}
