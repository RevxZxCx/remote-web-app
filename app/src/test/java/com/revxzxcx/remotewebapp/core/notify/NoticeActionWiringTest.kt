package com.revxzxcx.remotewebapp.core.notify

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「点通知里的按钮，真的会有人接」这条链路的回归测试（R056）。
 *
 * ## 为什么必须有这个文件
 * 真机验收时发现：审批通知的「批准 / 拒绝」按钮点了之后，**通知条消失了，但什么都没发生**
 * ——任务还在原地等授权，界面上那张审批卡也还在。根因是 [NoticeActionReceiver] 第一件事就做
 * `context.applicationContext as? NoticeActionHost`，而 `DshApplication` 当时只继承了
 * `Application`，没实现这个接口。`as?` 拿到 null，代码走进兜底分支：撤掉通知条，然后返回。
 *
 * **而全部 1960 条单测都是绿的**，因为：
 * - `AppContainer.answerFromNotification` 有实现、也从没被删；
 * - 没有任何一条测试经过 `Application` 这一跳。
 *
 * 所以这里测的不是「接收器逻辑对不对」（那部分本来就是对的），而是
 * **「装配对不对」**——这类缺陷只有跑真机才暴露，代价是一整轮验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoticeActionWiringTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `DshApplication 必须实现 NoticeActionHost 否则通知按钮点了没反应`() {
        // 这条断言就是本文件存在的全部理由。把 DshApplication 的 `, NoticeActionHost`
        // 去掉，这里立刻变红——而那正是真机上「按钮没反应」的原因。
        assertTrue(
            "applicationContext 必须是 NoticeActionHost，否则 NoticeActionReceiver 会走兜底分支只撤通知",
            app is NoticeActionHost,
        )
    }

    @Test
    fun `接收器对别的 action 一律不理 不能把系统广播当成审批回答`() {
        val receiver = NoticeActionReceiver()
        val before = askCount()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("无关广播不该动通知", before, askCount())
    }

    @Test
    fun `没有 eventId 时直接返回 不去碰通知`() {
        val receiver = NoticeActionReceiver()
        postProbe()
        val before = askCount()
        receiver.onReceive(
            app,
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_ALLOW, true)
            },
        )
        assertEquals("没有 eventId 就不该撤通知，用户还得能再点一次", before, askCount())
    }

    @Test
    fun `没带 allow extra 时不能当成拒绝 拒绝是不可撤销的`() {
        // 关键分支：`getBooleanExtra(..., false)` 在「extra 没带」时与「显式给了 false」
        // 无法区分。一次构造错误的 Intent 会被当成「拒绝」，而工具已经失败、撤不回来。
        // 所以实现用 `hasExtra` 判，缺 extra 时直接返回。
        val receiver = NoticeActionReceiver()
        postProbe()
        val before = askCount()
        receiver.onReceive(
            app,
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, "evt-1")
            },
        )
        assertEquals("缺 allow 就当拒绝会把任务误杀", before, askCount())
    }

    @Test
    fun `eventId 是空白串时同样直接返回`() {
        val receiver = NoticeActionReceiver()
        postProbe()
        val before = askCount()
        receiver.onReceive(
            app,
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, "   ")
                putExtra(NotificationCenter.EXTRA_ALLOW, true)
            },
        )
        assertEquals(before, askCount())
    }

    private fun manager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    /** 当前进程发出的通知条数（用来判「有没有被撤掉」）。 */
    private fun askCount(): Int = shadowOf(manager()).size()

    /** 摆一条占位通知，好让「撤掉了没有」这件事可观测。 */
    private fun postProbe() {
        val nm = manager()
        if (shadowOf(nm).size() > 0) return
        nm.notify(
            99,
            android.app.Notification.Builder(app, NoticeChannels.ASK)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("probe")
                .build(),
        )
    }
}
