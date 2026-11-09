package com.revxzxcx.remotewebapp.core.notify

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
 *
 * ## 为什么断言「宿主有没有被叫到」而不是「通知条还在不在」
 * R057 把 `bootstrapOnce()` 提到了 `DshApplication.onCreate`，它在 `appScope` 里异步跑
 * `installConnection(loadBook())`。Robolectric 下凭证簿一般是空的 → 走
 * `ConnectionPlan.Drop` → `notificationCenter.cancelAll()`。这条后台清场会和单测里
 * 「摆一条占位通知 → onReceive → 数一遍还剩几条」赛跑，于是计数断言偶发变红
 * （同一份代码，整类跑会红、单跑这一条却绿）。
 *
 * 换成 spy 宿主之后，每条用例只依赖接收器自己的分支判断，与进程里别的异步工作无关。
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
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(spyContext(spy), Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("无关广播不该触发审批回答", 0, spy.answers.size)
        assertEquals("无关广播不该触发退出服务", 0, spy.exits)
    }

    @Test
    fun `没有 eventId 时直接返回 不去回答`() {
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(
            spyContext(spy),
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_ALLOW, true)
            },
        )
        assertEquals("没有 eventId 就不该回答，用户还得能再点一次", 0, spy.answers.size)
    }

    @Test
    fun `没带 allow extra 时不能当成拒绝 拒绝是不可撤销的`() {
        // 关键分支：`getBooleanExtra(..., false)` 在「extra 没带」时与「显式给了 false」
        // 无法区分。一次构造错误的 Intent 会被当成「拒绝」，而工具已经失败、撤不回来。
        // 所以实现用 `hasExtra` 判，缺 extra 时直接返回——宿主**一次都不该被叫到**。
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(
            spyContext(spy),
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, "evt-1")
            },
        )
        assertEquals("缺 allow 就当拒绝会把任务误杀", 0, spy.answers.size)
    }

    @Test
    fun `eventId 是空白串时同样直接返回`() {
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(
            spyContext(spy),
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, "   ")
                putExtra(NotificationCenter.EXTRA_ALLOW, true)
            },
        )
        assertEquals("空白 eventId 不该回答", 0, spy.answers.size)
    }

    @Test
    fun `payload 齐了就把回答原样交给宿主`() {
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(
            spyContext(spy),
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, "evt-1")
                putExtra(NotificationCenter.EXTRA_ALLOW, false)
            },
        )
        assertEquals(1, spy.answers.size)
        assertEquals("拒绝必须原样传下去，不能被吞成批准", "evt-1" to false, spy.answers.first())
    }

    @Test
    fun `退出服务那条 action 走宿主 不会误走审批分支`() {
        val spy = SpyHost()
        NoticeActionReceiver().onReceive(
            spyContext(spy),
            Intent(app, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_EXIT_SERVICE
            },
        )
        assertEquals("退出只能叫一次", 1, spy.exits)
        assertEquals("退出不能触发审批回答", 0, spy.answers.size)
    }

    /** 记录「有没有被叫到」的宿主替身。 */
    private class SpyHost : NoticeActionHost {
        val answers = mutableListOf<Pair<String, Boolean>>()

        /** eventId / callId / questionId / label —— 四样都要，少一样就写不回原通知。 */
        data class Quad(
            val eventId: String,
            val callId: String,
            val questionId: String,
            val label: String,
        )

        val options = mutableListOf<Quad>()
        var exits = 0

        override fun answerFromNotification(eventId: String, allow: Boolean) {
            answers += eventId to allow
        }

        override fun answerQuestionOptionFromCapsule(
            eventId: String,
            callId: String,
            questionId: String,
            label: String,
        ) {
            options += Quad(eventId, callId, questionId, label)
        }

        override fun exitServiceFromNotification() {
            exits += 1
        }
    }

    /**
     * 把 `applicationContext` 换成 spy 的 Context。
     *
     * 接收器第一件事是 `context.applicationContext as? NoticeActionHost`，只要让
     * `applicationContext` 返回一个「自己就是宿主」的 Context，就能在这一跳上劫持它，
     * 不必改动真实的 Application。
     */
    private fun spyContext(host: NoticeActionHost): Context = object : ContextWrapper(app), NoticeActionHost {
        override fun getApplicationContext(): Context = this

        override fun answerFromNotification(eventId: String, allow: Boolean) {
            host.answerFromNotification(eventId, allow)
        }

        override fun answerQuestionOptionFromCapsule(
            eventId: String,
            callId: String,
            questionId: String,
            label: String,
        ) {
            host.answerQuestionOptionFromCapsule(eventId, callId, questionId, label)
        }

        override fun exitServiceFromNotification() {
            host.exitServiceFromNotification()
        }
    }
}
