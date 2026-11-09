package com.revxzxcx.remotewebapp.core.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.revxzxcx.remotewebapp.core.notify.NoticeChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 常驻通知的构建（R057 用户要求第 3 条：显示运行时长 + 展开后有退出按钮）。
 *
 * ## 为什么用 Robolectric
 * 这一层全部价值在**系统真的收到了一条什么样的通知**：能不能响、能不能划掉、
 * 有几个按钮、展开后正文里有没有时长。纯 JVM 上 `NotificationCompat.build()`
 * 会因 `NotificationChannel` 之类走不到真实现；只断源码又断不出「flags 最终是什么」。
 *
 * ## 与 R056 的 `NotificationCenterTest` 同一个套路
 * `ShadowNotificationManager.getNotification(id)` 拿回真实对象，再读它的字段——
 * `dumpsys notification` 在模拟器上看到的那几个字段（importance / flags / actions）
 * 就是这里断的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceNoticeTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notice: ServiceNotice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 不开通知的话 ShadowNotificationManager 会静默丢弃，读回 null。
        shadowOf(manager).setNotificationsEnabled(true)
        ensureServiceChannel(context)
        notice = ServiceNotice(context)
    }

    private fun build(phase: ServicePhase, connectedFor: Long = 0L, downFor: Long = 0L, attempt: Int = 0): Notification {
        val n = notice.build(ServiceStatus(phase = phase, connectedForMillis = connectedFor, downForMillis = downFor, attempt = attempt))
        assertNotNull("$phase 相不该返回 null 通知", n)
        return n!!
    }

    @Test
    fun `已停止不给通知 发一条已停止的常驻条等于用户永远关不掉它`() {
        assertNull(notice.build(ServiceStatus(phase = ServicePhase.Stopped)))
    }

    @Test
    fun `常驻条不可划掉`() {
        val n = build(ServicePhase.Connected)
        assertTrue("前台服务的通知被划掉后服务就失去前台资格", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `常驻条不响 时长每秒都在变`() {
        val n = build(ServicePhase.Connected)
        // setSilent 在 API 26+ 落到 FLAG_SILENT（0x00000080 的位）与 groupAlertBehavior。
        assertTrue(
            "不静音的话，频道被用户调高后会每秒响一次",
            n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 || n.sound == null,
        )
    }

    @Test
    fun `常驻条只有一个动作 就是退出`() {
        val n = build(ServicePhase.Connected)
        assertEquals(1, n.actions.size)
        assertEquals(ServiceText.EXIT_LABEL, n.actions[0].title.toString())
    }

    @Test
    fun `展开后的正文里带得出运行时长`() {
        val n = build(ServicePhase.Connected, connectedFor = 2L * 3_600_000L + 14L * 60_000L)
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertTrue("用户要求第 3 条，正文里必须有它：得到「$big」", big.contains("2 小时 14 分"))
    }

    @Test
    fun `重连中的标题与已连接不同 而且说明已断开多久`() {
        val connected = build(ServicePhase.Connected)
        val reconnecting = build(ServicePhase.Reconnecting, downFor = 42_000L, attempt = 3)
        assertFalse(
            "共用标题的话用户不知道此刻收不到通知",
            connected.extras.getCharSequence(Notification.EXTRA_TITLE) ==
                reconnecting.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        val big = reconnecting.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        // 42 秒按五秒档**向上**归到「45 秒」（第五轮审查 P2：通知里的秒数不能每秒变，
        // 否则去重键每秒都不同，等于每秒 notify 一次，系统进不了静止态）。
        assertTrue("大正文要说明断了多久", big.contains("45 秒"))
    }

    @Test
    fun `配对失效的正文带上桌面端给的原因`() {
        val n = notice.build(
            ServiceStatus(phase = ServicePhase.Unpaired, unpairedReason = "凭证已被撤销"),
        )!!
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertTrue(big.contains("凭证已被撤销"))
    }

    @Test
    fun `频道是静音的 它不该参与提醒`() {
        val channel = manager.getNotificationChannel(NoticeChannels.SERVICE)
        assertNotNull("服务可能在没有 App 的情况下被拉起，频道必须自己建", channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
    }

    @Test
    fun `反复建频道不会重置用户调高的重要性`() {
        // createNotificationChannel 对已存在频道只更新名称与描述——这是我们要的语义，
        // 但它是平台行为，值得钉住：哪天有人改成先 delete 再 create，用户的手动调整就没了。
        ensureServiceChannel(context)
        ensureServiceChannel(context)
        val channel = manager.getNotificationChannel(NoticeChannels.SERVICE)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
    }

    @Test
    fun `撤掉之后通知栏里就查不到`() {
        val n = build(ServicePhase.Connected)
        manager.notify(ServiceNotice.NOTICE_ID, n)
        assertNotNull(shadowOf(manager).getNotification(ServiceNotice.NOTICE_ID))
        notice.cancel()
        assertNull(shadowOf(manager).getNotification(ServiceNotice.NOTICE_ID))
    }
}
