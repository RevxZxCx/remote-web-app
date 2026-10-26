package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompletionLedger] 的判据测试（R049，用户 m32994 + m33325 纠正）。
 *
 * 用户纠正的那句话是这个类存在的理由：「这个点不是常驻的，而是一个完成的标记，
 * 提醒用户查看对话的」。所以这些用例几乎全在守「什么时候**不**点亮」——
 * 常驻绿点的错误实现把这里每一条都违反了。
 *
 * 对照 DSH 桌面端 `@deepseek-ai/dsh-client-ui-session` 的 `UiSession.observeRunning`：
 * 边沿判定、当前会话不点亮、看过即清，三条一一对应下面的用例。
 */
class CompletionLedgerTest {

    private val ledger = CompletionLedger()

    // ---- 边沿判定：只有「上一刻在跑」才算跑完 ----

    @Test
    fun runningThenStopLightsTheReminder() {
        ledger.observe("s1", running = true)
        assertTrue(ledger.observe("s1", running = false))
        assertTrue(ledger.isUnread("s1"))
    }

    /**
     * 电平判据（「当前不在跑就点亮」）会把所有历史上跑完的会话全部点亮，
     * 这正是用户看到的满屏绿点。冷启动第一次见到一个不在跑的会话，不能算刚跑完。
     */
    @Test
    fun firstSightOfAnIdleSessionDoesNotLightTheReminder() {
        assertFalse(ledger.observe("s1", running = false))
        assertFalse(ledger.isUnread("s1"))
    }

    /** 同一个运行态重复到达（心跳重放、重复事件）时不能每次都点亮。 */
    @Test
    fun repeatedIdleObservationKeepsTheSameAnswer() {
        ledger.observe("s1", running = true)
        assertTrue(ledger.observe("s1", running = false))
        assertTrue("第二次仍是未读", ledger.observe("s1", running = false))
    }

    @Test
    fun observingRunningAgainClearsTheReminder() {
        ledger.observe("s1", running = true)
        ledger.observe("s1", running = false)
        assertFalse(ledger.observe("s1", running = true))
        assertFalse(ledger.isUnread("s1"))
    }

    // ---- 当前会话不点亮（官方 isMain 判据） ----

    @Test
    fun theSessionBeingViewedDoesNotLightTheReminder() {
        ledger.setCurrentSession("s1")
        ledger.observe("s1", running = true)
        assertFalse(ledger.observe("s1", running = false))
        assertFalse(ledger.isUnread("s1"))
    }

    @Test
    fun otherSessionsStillLightWhileViewingOne() {
        ledger.setCurrentSession("s1")
        ledger.observe("s2", running = true)
        assertTrue(ledger.observe("s2", running = false))
    }

    // ---- 看过即清 ----

    @Test
    fun enteringTheSessionClearsItsReminder() {
        ledger.observe("s1", running = true)
        ledger.observe("s1", running = false)
        ledger.setCurrentSession("s1")
        assertFalse(ledger.isUnread("s1"))
    }

    @Test
    fun leavingTheListKeepsOtherReminders() {
        ledger.observe("s1", running = true)
        ledger.observe("s1", running = false)
        ledger.observe("s2", running = true)
        ledger.observe("s2", running = false)
        ledger.setCurrentSession("s1")
        assertFalse(ledger.isUnread("s1"))
        assertTrue("看 s1 不该把 s2 的提醒也清掉", ledger.isUnread("s2"))
    }

    @Test
    fun setCurrentSessionClearsWithoutTouchingTheRunningBaseline() {
        ledger.observe("s1", running = true)   // 基线：正在跑
        ledger.observeFailure("s1")            // 先亮一枚提醒，待会儿要被清掉
        ledger.setCurrentSession("s1")
        assertFalse(ledger.isUnread("s1"))
        assertFalse(ledger.isFailureUnread("s1"))
        ledger.setCurrentSession(null)         // 离开会话，回到列表
        // 基线没被 `setCurrentSession` 删掉：这次「跑完了」仍是一次边沿。
        // 若它顺手删了基线，`previous == null` 会落到「首见不点亮」分支，下面这条就红。
        assertTrue("看过之后它跑完一轮，应当点亮", ledger.observe("s1", running = false))
    }

    // ---- 失败提醒 ----

    @Test
    fun failureLightsUntilSeen() {
        assertTrue(ledger.observeFailure("s1", reason = "模型限流"))
        assertTrue(ledger.isFailureUnread("s1"))
        ledger.setCurrentSession("s1")
        assertFalse(ledger.isFailureUnread("s1"))
        // 原因也要一起清：留着会让下一条红点显示上一次失败的原因。
        assertEquals("", ledger.failureReason("s1"))
    }

    @Test
    fun theSessionBeingViewedDoesNotLightTheFailure() {
        ledger.setCurrentSession("s1")
        assertFalse(ledger.observeFailure("s1"))
        assertFalse(ledger.isFailureUnread("s1"))
    }

    /** 失败与完成互斥：同一条会话既跑完又报错时只留红点。 */
    @Test
    fun failureSupersedesTheCompletionReminder() {
        ledger.observe("s1", running = true)
        ledger.observe("s1", running = false)
        ledger.observeFailure("s1")
        assertFalse("失败时不该同时留绿点", ledger.isUnread("s1"))
        assertTrue(ledger.isFailureUnread("s1"))
    }

    @Test
    fun observingRunningAgainClearsTheFailure() {
        ledger.observeFailure("s1")
        ledger.observe("s1", running = true)
        assertFalse(ledger.isFailureUnread("s1"))
    }

    // ---- 播种与清理 ----

    /**
     * 播种会把「基线变了」转成一次边沿（R049 审查 P2）。
     *
     * 场景：App 在后台或断连期间跑完了一轮——期间没有 `api-session/status` 事件，
     * 账本记着的基线还是 `true`，只有重新拉回来的列表说它已经停了。不在这里补一次
     * 边沿，那一轮的完成提醒就永久丢失。
     */
    @Test
    fun seedTurnsABaselineChangeIntoAnEdge() {
        ledger.observe("s1", running = true)
        assertFalse(ledger.isUnread("s1"))
        ledger.seed(listOf(summary("s1", running = false)))
        assertTrue(ledger.isUnread("s1"))
    }

    /**
     * 首见一条**已经停着**的会话不点亮。
     *
     * 这是类 KDoc 第 1 条的守卫：冷启动后账本是空的，全量列表里几乎每条都是
     * `running = false`。若把「首见 + 不在跑」当成边沿，重启后就是满屏绿点——
     * 正是用户 m33325 否掉的形态。
     */
    @Test
    fun seedDoesNotLightOnFirstSight() {
        ledger.seed(listOf(summary("s1", running = false)))
        assertFalse(ledger.isUnread("s1"))
    }

    /**
     * 快照在飞的时候收到过状态事件 → 快照里的 `running` 已经过期，不许拿它比对（二审 P2）。
     *
     * 场景：`session/list` 发出（耗时 2.6 秒），期间收到 `status = true` 把基线置为
     * `true`；返回的快照里那条会话仍是 `running = false`。若照旧比对，会把「刚开始跑」
     * 读成「跑完了」——既在正在跑的会话上点亮绿点，又把基线改回 false，
     * 等它真的跑完时 `previous == false` 不再产边沿，那一轮提醒永久丢失。
     */
    @Test
    fun seedSkipsBaselinesTouchedAfterTheRequestWasSent() {
        val epochAtRequest = ledger.currentEpoch()
        ledger.observe("s1", running = true)
        ledger.seed(listOf(summary("s1", running = false)), sinceEpoch = epochAtRequest)
        // 既没被当成「跑完了」点亮，基线也仍是事件给的 true。
        assertFalse(ledger.isUnread("s1"))
        assertTrue(ledger.observe("s1", running = false))
    }

    /** 同一份快照里，没被状态事件碰过的条目照旧比对——纪元守卫不能把整条路径关掉。 */
    @Test
    fun seedStillWorksForSessionsUntouchedSinceTheRequest() {
        ledger.observe("touched", running = true)
        ledger.observe("quiet", running = true)
        val epochAtRequest = ledger.currentEpoch()
        ledger.observe("touched", running = true)
        ledger.seed(
            listOf(summary("touched", running = false), summary("quiet", running = false)),
            sinceEpoch = epochAtRequest,
        )
        assertFalse(ledger.isUnread("touched"))
        assertTrue(ledger.isUnread("quiet"))
    }

    /** 基线没变时播种不点亮：否则每次列表刷新都会把静止的会话点亮一遍。 */
    @Test
    fun seedLeavesAnUnchangedBaselineAlone() {
        ledger.observe("s1", running = false)
        ledger.seed(listOf(summary("s1", running = false)))
        assertFalse(ledger.isUnread("s1"))
    }

    @Test
    fun seedFillsMissingBaselinesFromTheList() {
        ledger.seed(listOf(summary("s1", running = true)))
        assertTrue(ledger.observe("s1", running = false))
    }

    @Test
    fun seedDropsIdsThatLeftTheList() {
        ledger.observe("gone", running = true)
        ledger.observe("gone", running = false)
        ledger.observeFailure("gone")
        ledger.seed(listOf(summary("kept")))
        assertFalse(ledger.isUnread("gone"))
        assertFalse(ledger.isFailureUnread("gone"))
    }

    @Test
    fun forgetDropsEveryTrace() {
        // 先把基线建起来（true），否则 `forget` 删不删基线 `observe(false)` 都返回 false，
        // 断言恒真、证不了任何事。
        ledger.observe("s1", running = true)
        ledger.observeFailure("s1")
        ledger.forget("s1")
        assertFalse(ledger.isFailureUnread("s1"))
        // 基线也被清掉：从「曾在跑」变成「从没见过」，再次见到它不算边沿。
        // 若 `forget` 漏删基线，下面这条会产边沿并返回 true。
        assertFalse(ledger.observe("s1", running = false))
    }

    @Test
    fun seedKeepsSessionsDiscoveredAfterTheRequestWasSent() {
        // 时序：发出全量拉取（记下 epoch）→ 新会话被 status 事件写进账本 → 快照才回来，
        // 快照里自然没有这个新会话。无条件的 `retainAll(present)` 会把它的基线删掉，
        // 之后它跑完就成了「首见」，绿点永久丢失。（审查 P2）
        val epochAtRequest = ledger.currentEpoch()
        val newId = "created-while-loading"
        ledger.observe(newId, running = true)

        ledger.seed(listOf(summary("kept")), sinceEpoch = epochAtRequest)

        // 基线仍在（seed 之后、下一次事件之前读）。
        assertEquals("拉取期间新建的会话不能被快照成员表清掉", true, ledger.runningOf(newId))
        // 于是这一轮跑完是边沿，必须点亮。
        assertTrue("拉取期间新建的会话跑完应当点亮", ledger.observe(newId, running = false))
    }

    @Test
    fun seedStillDropsSessionsThatVanishedBeforeTheRequest() {
        // 反面：请求发出**之前**就有的陈旧条目，快照里没有就是真没了，照旧清掉。
        ledger.observe("stale", running = true)
        val epochAtRequest = ledger.currentEpoch()
        ledger.seed(listOf(summary("kept")), sinceEpoch = epochAtRequest)
        assertNull(ledger.runningOf("stale"))
    }

    @Test
    fun unreadIdsReportsBothKindsSeparately() {
        ledger.observe("done", running = true)
        ledger.observe("done", running = false)
        ledger.observeFailure("failed")
        assertEquals(setOf("done"), ledger.unreadIds())
        assertEquals(setOf("failed"), ledger.failureIds())
    }

    private fun summary(sessionId: String, running: Boolean = false) = SessionSummary(
        sessionId = sessionId,
        updatedAt = 0L,
        agentAvailable = true,
        running = running,
        blank = false,
    )
}
