package com.revxzxcx.remotewebapp.core.notify

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.session.AskQuestion
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

    // ── 缺口1：失败通知曾被 success 侧的 ONLY_ALERT_ONCE 静默 ────────────────

    @Test
    fun `失败通知不能带 ONLY_ALERT_ONCE 否则跑挂了一声不吭`() {
        // 场景：同一会话先正常跑完（响过一次），再跑一轮并且**失败**。两次用同一个 id
        // （id 按 sessionId 分配），而 ONLY_ALERT_ONCE 的语义是「这条通知已经响过就不再响」
        // → 通知栏文字被更新成「任务出错」，却不响铃、不弹横幅。
        // 用户看到的正是本需求要修的那件事：任务出问题了，却一声不吭。
        center.postDone("s1", "修登录接口", failed = false, reason = null)
        val ok = doneNotice("s1")!!
        assertTrue(
            "成功侧应当只响一次（否则同会话连跑五轮会响五次）",
            ok.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )

        center.postDone("s1", "修登录接口", failed = true, reason = "模型返回 500")
        val failed = doneNotice("s1")
        assertNotNull("失败必须覆盖同一条通知（同 sessionId 同 id），不能另起一条", failed)
        assertTrue(
            "失败侧不能带 ONLY_ALERT_ONCE：用户必须被真的提醒到",
            failed!!.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0,
        )
        assertTrue(
            "失败正文要带上原因",
            failed.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("模型返回 500") == true,
        )
    }

    @Test
    fun `失败通知仍然是自动撤销的 点开就消失`() {
        center.postDone("s1", "甲", failed = true, reason = "超时")
        val n = doneNotice("s1")!!
        assertTrue(
            "去掉 ONLY_ALERT_ONCE 不能顺手把 AUTO_CANCEL 也去掉",
            n.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    // ── 缺口2：提问（user-questions/request）曾在通知层完全缺席 ──────────────

    @Test
    fun `提问要发通知 标题用 header`() {
        center.postQuestion("q-1", "s1", ask("用哪个方案？", header = "选择方案"), 1)
        val n = questionNotice("q-1")
        assertNotNull("提问必须发通知——没人答就是 NO_PROVIDER，任务直接失败", n)
        assertEquals("选择方案", n!!.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `没有 header 时标题回落到问题首行`() {
        center.postQuestion("q-1", "s1", ask("用哪个方案？"), 1)
        assertEquals("用哪个方案？", questionNotice("q-1")!!.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `多道题时标题要说明还有几个 否则用户以为答一道就完`() {
        center.postQuestion("q-1", "s1", ask("问题一"), 3)
        val n = questionNotice("q-1")!!
        assertTrue(
            "标题要带「等 3 个问题」",
            n.extras.getString(Notification.EXTRA_TITLE)?.contains("等 3 个问题") == true,
        )
    }

    @Test
    fun `提问通知不自动撤销 划掉不等于问题不存在`() {
        center.postQuestion("q-1", "s1", ask("问题一"), 1)
        val n = questionNotice("q-1")!!
        assertTrue(
            "任务还在等回答，划掉通知不该让系统以为已经处理完",
            n.flags and Notification.FLAG_AUTO_CANCEL == 0,
        )
    }

    @Test
    fun `提问通知没有动作按钮 多选题不能硬塞一个同意`() {
        center.postQuestion("q-1", "s1", ask("问题一"), 1)
        assertEquals("提问答案放不进通知栏，只能点进会话", 0, questionNotice("q-1")!!.actions?.size ?: 0)
    }

    @Test
    fun `提问也遵守锁屏开关`() {
        settings.lockScreenDetail = false
        center.postQuestion("q-1", "s1", ask("问题一"), 1)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, questionNotice("q-1")!!.visibility)
    }

    @Test
    fun `提问与审批的 id 段必须错开 否则互相顶掉`() {
        // 两者都是 waterfall，同时挂着的可能性很高。用同一个 eventId 各发一条，
        // 若 id 段重叠，后发的那条会把先发的顶掉——而顶掉的那条背后是一个正在等回答的任务。
        center.postAsk(probe("same-event"))
        val askId = NotificationCenter.ASK_ID_BASE +
            ("same-event".hashCode() and NotificationCenter.ASK_ID_MASK)
        assertNotNull("审批那条要先发出来", shadowOf(manager).getNotification(askId))

        center.postQuestion("same-event", "s1", ask("问题一"), 1)
        val questionId = questionNoticeId("same-event")
        assertNotNull("提问那条也要发出来", shadowOf(manager).getNotification(questionId))
        assertTrue("两段的 id 不能相等（ask=$askId question=$questionId）", askId != questionId)
        assertNotNull("发提问不能把审批那条顶掉", shadowOf(manager).getNotification(askId))
    }

    @Test
    fun `撤掉提问通知之后就查不到那条`() {
        center.postQuestion("q-1", "s1", ask("问题一"), 1)
        assertNotNull(questionNotice("q-1"))
        center.cancelQuestionByEventId("q-1")
        assertNull(questionNotice("q-1"))
    }

    @Test
    fun `撤掉不存在的提问不出错`() {
        center.cancelQuestionByEventId("nobody")
    }

    private fun questionNoticeId(eventId: String): Int =
        NotificationCenter.QUESTION_ID_BASE + (eventId.hashCode() and NotificationCenter.ASK_ID_MASK)

    private fun questionNotice(eventId: String): Notification? =
        shadowOf(manager).getNotification(questionNoticeId(eventId))

    private fun ask(question: String, header: String? = null) = AskQuestion(
        id = "q1",
        question = question,
        header = header,
    )

    private fun probe(eventId: String) = ApprovalRequest(
        eventId = eventId,
        agentId = "s1",
        toolName = "ops_bash",
        callId = null,
        reason = null,
        displayReason = mapOf("zh" to "写入全局 git 配置"),
    )
}
