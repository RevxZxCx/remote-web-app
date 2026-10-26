package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [statusOf] 的优先级（R049，用户 m32994 + m33325 纠正）。
 *
 * 与官方桌面端 `ui-workspace` 的 `sessionStatuses(node, t)` 同序（asar 偏移 49084078 起）：
 * `pendingInteraction`（warning）> `running`（ongoing）> `completed`（done）> `idle`。
 * 注意**待交互在 running 之前**——挂起等回答/审批时这一轮并没有结束，`running` 仍为真，
 * 所以顺序反过来会让「需要继续」这一档在提问场景永远显示不出来。
 *
 * 这里在 completed 之前插了 [SessionStatus.Failed]（用户要求「报错也该有颜色」），
 * 理由是它比「跑完了没看」承载更多信息，且失败后 `running` 必然已是 false，不会互相遮蔽。
 *
 * 只用纯 JVM：判据在 `core/session` 里，不碰 Compose，所以优先级可以直接穷举。
 * 注意这些用例全部**只用 [SessionSummary] 的字段**，不碰账本——账本自己的边沿判定
 * 归 [CompletionLedgerTest] 守，这里守的是「字段到位后画哪个」。
 */
class SessionActivityTest {

    private fun summary(
        id: String = "s",
        running: Boolean = false,
        completionUnread: Boolean = false,
        failureUnread: Boolean = false,
        awaitingAttention: Boolean = false,
        blank: Boolean = false,
    ) = SessionSummary(
        sessionId = id,
        running = running,
        completionUnread = completionUnread,
        failureUnread = failureUnread,
        awaitingAttention = awaitingAttention,
        blank = blank,
    )

    @Test
    fun runningIsOngoing() {
        assertEquals(SessionStatus.Ongoing, statusOf(summary(running = true)))
    }

    /**
     * 用户 m33325：「这个点不是常驻的，而是一个完成的标记」。
     *
     * 所以「什么都没标」的普通会话是 [SessionStatus.Idle]（不画点），**不是** Done。
     * 第一版把它判成 Done，结果列表上几乎每一行都挂着一枚常驻绿点。
     */
    @Test
    fun anOrdinarySessionStaysIdleInsteadOfContinuousDone() {
        assertEquals(SessionStatus.Idle, statusOf(summary()))
    }

    @Test
    fun completionUnreadIsDone() {
        assertEquals(SessionStatus.Done, statusOf(summary(completionUnread = true)))
    }

    @Test
    fun failureUnreadIsFailed() {
        assertEquals(SessionStatus.Failed, statusOf(summary(failureUnread = true)))
    }

    @Test
    fun awaitingAnswerIsAttention() {
        assertEquals(SessionStatus.Attention, statusOf(summary(awaitingAttention = true)))
    }

    @Test
    fun blankSessionIsIdle() {
        assertEquals(SessionStatus.Idle, statusOf(summary(blank = true)))
    }

    @Test
    fun runningWinsOverFailure() {
        // 用户看到红点后重发了一条：这一轮在跑，就不能再显示「上一轮失败」，
        // 否则看起来像新一轮也挂了。账本也会在 `running = true` 时把红点熄掉，
        // 这条守的是「万一两者同时为真」时的显示侧兜底。
        assertEquals(
            SessionStatus.Ongoing,
            statusOf(summary(running = true, failureUnread = true)),
        )
    }

    @Test
    fun runningWinsOverCompletionUnread() {
        assertEquals(
            SessionStatus.Ongoing,
            statusOf(summary(running = true, completionUnread = true)),
        )
    }

    /** 失败优先于「跑完了没看」：同一条会话既跑完又报错时，红点承载更多信息。 */
    @Test
    fun failureWinsOverCompletionUnread() {
        assertEquals(
            SessionStatus.Failed,
            statusOf(summary(failureUnread = true, completionUnread = true)),
        )
    }

    @Test
    fun attentionWinsOverCompletionUnread() {
        assertEquals(
            SessionStatus.Attention,
            statusOf(summary(awaitingAttention = true, completionUnread = true)),
        )
    }

    /**
     * 待交互**优先于**运行中。
     *
     * 这是 R049 审查的 P1：agent 挂起等你回答/等你审批时，它这一轮没有结束，`running`
     * 依然为 true。把 running 排在前面，「需要继续」这一档在提问/审批场景就永远显示不出来——
     * 而这正是用户 m32994 明确要求的三种状态之一。官方 `sessionStatuses` 里
     * `pendingInteraction` 也正是在 `node.running` 之前返回。
     */
    @Test
    fun attentionWinsOverRunning() {
        assertEquals(
            SessionStatus.Attention,
            statusOf(summary(running = true, awaitingAttention = true)),
        )
        // 三件同时为真时，仍然是待交互最优先。
        assertEquals(
            SessionStatus.Attention,
            statusOf(
                summary(
                    running = true,
                    awaitingAttention = true,
                    completionUnread = true,
                    failureUnread = true,
                ),
            ),
        )
    }
}
