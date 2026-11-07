package com.revxzxcx.remotewebapp.core.notify

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import org.junit.Assert.assertEquals
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
 * 通知本体行为的测试（R056 六轮审查第 1/2 轮修复的守卫）。
 *
 * ## 为什么这些必须用 Robolectric
 * 前几轮审查修掉的两个缺陷都**只存在于系统交互里**，纯 JVM 测试断不出来：
 *
 * 1. **「锁屏显示详细内容」曾是假开关**：`NoticeSettings.lockScreenDetail` 有读写、
 *    有 UI 开关，但 `NotificationCenter` 从头到尾没读过它，通知一直是默认的
 *    `VISIBILITY_PRIVATE`。用户勾掉那个开关，锁屏上照样显示会话标题——
 *    而会话标题很可能是公司代码或私事。`.setVisibility(...)` 的取值只有从真正
 *    构造出来的 `Notification` 里读才看得到。
 * 2. **回答失败曾是静默的**：`postAskFailed` 要把「授权没能送出」写回**同一条**
 *    通知上，并且**保留原来的两个按钮**——连接恢复后再点一次是有效动作。
 *    这个「保留」靠的是 `postedAsks` 这本账，只有真的发过一次 `postAsk` 才有。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationCenterTest {

    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private lateinit var settings: NoticeSettings
    private lateinit var center: NotificationCenter

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        manager = app.getSystemService(NotificationManager::class.java)
        settings = NoticeSettings(app)
        center = NotificationCenter(app, settings)
        center.ensureChannels()
        shadowOf(manager).setNotificationsEnabled(true)
    }

    private fun doneNotice(sessionId: String): Notification? {
        val id = center.peekId(sessionId) ?: return null
        return shadowOf(manager).getNotification(id)
    }

    private fun askNotice(eventId: String): Notification? {
        val id = NotificationCenter.ASK_ID_BASE + (eventId.hashCode() and NotificationCenter.ASK_ID_MASK)
        return shadowOf(manager).getNotification(id)
    }

    @Test
    fun `默认锁屏显示详细内容 可见性是 PRIVATE`() {
        center.postDone("s1", "修登录接口", failed = false, reason = null)
        val n = doneNotice("s1")
        assertNotNull("任务结束通知应当发出来", n)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n!!.visibility)
    }

    @Test
    fun `关掉详细内容后 锁屏上是 SECRET 不露会话名`() {
        settings.lockScreenDetail = false
        center.postDone("s1", "修登录接口", failed = false, reason = null)
        val n = doneNotice("s1")
        assertNotNull(n)
        // 这一条就是那个假开关的守卫：实现里若把可见性写死 PRIVATE，这里会读到 PRIVATE。
        assertEquals(NotificationCompat.VISIBILITY_SECRET, n!!.visibility)
    }

    @Test
    fun `审批通知也遵守锁屏开关`() {
        settings.lockScreenDetail = false
        center.postAsk(probe("e-1"))
        val n = askNotice("e-1")
        assertNotNull(n)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, n!!.visibility)
    }

    @Test
    fun `回答失败时把原因写回同一条通知 而不是另起一条`() {
        center.postAsk(probe("e-1"))
        val before = askNotice("e-1")
        assertNotNull(before)
        center.postAskFailed("e-1", "连接已断开，进 App 重试。")
        val after = askNotice("e-1")
        assertNotNull("失败提示必须落在原来的 id 上", after)
        assertEquals(
            NotificationCenter.ASK_FAILED_TITLE,
            after!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(
            "正文要带上失败原因",
            after.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("连接已断开") == true,
        )
    }

    @Test
    fun `回答失败后仍然保留批准与拒绝 连接恢复后还能再点一次`() {
        center.postAsk(probe("e-1"))
        val before = askNotice("e-1")!!.actions?.size ?: 0
        center.postAskFailed("e-1", "连接已断开，进 App 重试。")
        val after = askNotice("e-1")!!
        assertEquals("原来有两个动作", 2, before)
        assertEquals("失败重绘不能把按钮弄丢", 2, after.actions?.size ?: 0)
    }

    @Test
    fun `没有发过这条审批时 失败提示也能显示 只是不带按钮`() {
        // 冷启动就直接点了通知里的旧按钮（postedAsks 是空的）：仍要让用户看到
        // 「为什么没反应」，但此时无处可取原请求，按钮只能省掉。
        center.postAskFailed("never-posted", "连接已断开，进 App 重试。")
        val n = askNotice("never-posted")
        assertNotNull(n)
        assertEquals(0, n!!.actions?.size ?: 0)
    }

    @Test
    fun `失败提示用 ONLY_ALERT_ONCE 不为了报错再响一次`() {
        center.postAsk(probe("e-1"))
        center.postAskFailed("e-1", "连接已断开，进 App 重试。")
        val n = askNotice("e-1")!!
        assertTrue(
            "用户刚就在看这条通知，报错不该再响",
            n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )
    }

    @Test
    fun `cancelAll 之后账本与通知一起清空 点旧通知撤不到东西`() {
        center.postDone("s1", "甲", failed = false, reason = null)
        center.postAsk(probe("e-1"))
        center.cancelAll()
        assertNull("id 池要清掉", center.peekId("s1"))
        assertNull("通知也要撤掉", shadowOf(manager).getNotification(NotificationCenter.ID_BASE))
    }

    @Test
    fun `cancelAll 之后重新发通知 不会与旧账撞 id`() {
        center.postDone("s1", "甲", failed = false, reason = null)
        center.cancelAll()
        center.postDone("s2", "乙", failed = false, reason = null)
        val n = doneNotice("s2")
        assertNotNull(n)
        assertTrue(
            "重新分配应当从池子起点开始",
            (center.peekId("s2") ?: 0) >= NotificationCenter.ID_BASE,
        )
    }

    private fun probe(eventId: String) = ApprovalRequest(
        eventId = eventId,
        agentId = "s1",
        toolName = "ops_bash",
        callId = null,
        reason = null,
        displayReason = mapOf("zh" to "写入全局 git 配置"),
    )
}
