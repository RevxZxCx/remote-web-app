package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceGroup.unreadStatus] 与 [unreadRank] 的单测（R052，用户 m37463 第 2 条）。
 *
 * 这一组用例守三件事：
 * 1. **优先级**按 [unreadRank] 取最强，而不是「先遇到谁就是谁」；
 * 2. **「运行中」不参与聚合** —— 工作区标题右侧本来就有均衡器动画，再给圆点是重复表达，
 *    而且它会盖住真该提醒的红点；
 * 3. **归档行不参与聚合** —— 归档是用户主动收起的，不该让折叠的工作区长期为它亮着。
 */
class WorkspaceUnreadStatusTest {

    private fun session(
        id: String,
        running: Boolean = false,
        awaitingAttention: Boolean = false,
        completionUnread: Boolean = false,
        failureUnread: Boolean = false,
        blank: Boolean = false,
        updatedAt: Long = 0L,
    ) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        running = running,
        awaitingAttention = awaitingAttention,
        completionUnread = completionUnread,
        failureUnread = failureUnread,
        blank = blank,
    )

    private fun group(
        vararg ids: String,
        archivedIds: Set<String> = emptySet(),
        pinnedIds: Set<String> = emptySet(),
        summaries: Map<String, SessionSummary>,
    ): SessionGrouping.WorkspaceGroup {
        val workspace = Workspace(
            workspaceId = "bot",
            path = "F:\\bot",
            title = "bot",
            sessionIds = ids.toList(),
        )
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace),
            archivedSessionIds = archivedIds,
            pinnedSessionIds = pinnedIds,
        )
        val sessions = SessionListResult(items = summaries.values.toList())
        return SessionGrouping.group(snapshot = snapshot, sessions = sessions).single()
    }

    @Test
    fun `running 不参与聚合，标题右边的动画已经表示这件事`() {
        val g = group("s1", summaries = mapOf("s1" to session("s1", running = true)))
        assertEquals(SessionStatus.Idle, g.unreadStatus)
        // 对照组：动画走的是另一个属性，两条判据互不干扰。
        assertTrue(g.hasRunning)
    }

    @Test
    fun `运行中不会盖住失败的提醒`() {
        val g = group(
            "s1", "s2",
            summaries = mapOf(
                "s1" to session("s1", running = true),
                "s2" to session("s2", failureUnread = true),
            ),
        )
        assertEquals(SessionStatus.Failed, g.unreadStatus)
    }

    @Test
    fun `取最强的一个：失败优先于完成`() {
        val g = group(
            "s1", "s2",
            summaries = mapOf(
                "s1" to session("s1", completionUnread = true),
                "s2" to session("s2", failureUnread = true),
            ),
        )
        assertEquals(SessionStatus.Failed, g.unreadStatus)
    }

    @Test
    fun `取最强的一个：强状态排在前面也不会被后面的弱状态盖掉`() {
        // rows 按 updatedAt 降序排列，所以这里让「强」的更新得更近、排在**最前**。
        // 「取最后一个」的写法会拿到后面那个 Done，本用例立刻变红；
        // 「取最强」的写法不受顺序影响。
        val g = group(
            "strong", "weak",
            summaries = mapOf(
                "strong" to session("strong", failureUnread = true, updatedAt = 2_000L),
                "weak" to session("weak", completionUnread = true, updatedAt = 1_000L),
            ),
        )
        assertEquals(SessionStatus.Failed, g.unreadStatus)
    }

    @Test
    fun `取最强的一个：待交互优先于失败`() {
        val g = group(
            "s1", "s2",
            summaries = mapOf(
                "s1" to session("s1", failureUnread = true),
                "s2" to session("s2", awaitingAttention = true),
            ),
        )
        assertEquals(SessionStatus.Attention, g.unreadStatus)
    }

    @Test
    fun `只有一个完成未读时是 Done`() {
        val g = group("s1", summaries = mapOf("s1" to session("s1", completionUnread = true)))
        assertEquals(SessionStatus.Done, g.unreadStatus)
    }

    @Test
    fun `全是 Idle 时聚合也是 Idle（空工作区不画点）`() {
        val g = group(
            "s1", "s2",
            summaries = mapOf("s1" to session("s1"), "s2" to session("s2")),
        )
        assertEquals(SessionStatus.Idle, g.unreadStatus)
    }

    @Test
    fun `归档行的未读不参与聚合`() {
        val g = group(
            "s1", "s2",
            archivedIds = setOf("s2"),
            summaries = mapOf(
                "s1" to session("s1"),
                // 归档行本身带一个失败提醒；它不该让折叠的工作区一直亮红点。
                "s2" to session("s2", failureUnread = true),
            ),
        )
        assertEquals(SessionStatus.Idle, g.unreadStatus)
    }

    @Test
    fun `置顶行的未读照样参与聚合`() {
        val g = group(
            "s1",
            pinnedIds = setOf("s1"),
            summaries = mapOf("s1" to session("s1", failureUnread = true)),
        )
        assertEquals(SessionStatus.Failed, g.unreadStatus)
    }

    @Test
    fun `空会话不参与聚合（它整行都不显示）`() {
        val g = group(
            "s1", "s2",
            summaries = mapOf(
                "s1" to session("s1"),
                "s2" to session("s2", failureUnread = true, blank = true),
            ),
        )
        assertEquals(SessionStatus.Idle, g.unreadStatus)
    }

    @Test
    fun `unreadRank 与 statusOf 的优先级顺序一致`() {
        // 这两处必须同时改，否则会出现「子行是红的、工作区是绿的」这种自相矛盾。
        // 这里用两个同时成立的状态验证顺序：failureUnread 高于 completionUnread。
        val both = SessionSummary(
            sessionId = "s",
            completionUnread = true,
            failureUnread = true,
        )
        assertEquals(SessionStatus.Failed, statusOf(both))
        assertTrue(unreadRank(SessionStatus.Failed) > unreadRank(SessionStatus.Done))
        assertTrue(unreadRank(SessionStatus.Attention) > unreadRank(SessionStatus.Ongoing))
        assertTrue(unreadRank(SessionStatus.Ongoing) > unreadRank(SessionStatus.Failed))
        assertTrue(unreadRank(SessionStatus.Done) > unreadRank(SessionStatus.Idle))
        assertEquals(0, unreadRank(SessionStatus.Idle))
    }

    @Test
    fun unreadFailureReasonCarriesTheStrongestRowsReason() {
        // 守 R052 审查 P2-2：折叠态圆点若只传状态、不传原因，读屏只会念
        // 「上一轮失败，点进去看」，而展开后同一个点会念出服务端给的具体原因——
        // 折叠反而让提示信息更少。
        val g = group(
            "failed", "done",
            summaries = mapOf(
                "failed" to session("failed", failureUnread = true, updatedAt = 2_000L)
                    .copy(failureReason = "限流：429 too many requests"),
                "done" to session("done", completionUnread = true, updatedAt = 1_000L),
            ),
        )
        assertEquals(SessionStatus.Failed, g.unreadStatus)
        assertEquals("限流：429 too many requests", g.unreadFailureReason)
    }

    @Test
    fun unreadFailureReasonIsEmptyWhenTheStrongestRowDidNotFail() {
        // 归档行即使有原因也不能被带出去——它根本不参与聚合。
        val g = group(
            "done", "archivedFailed",
            archivedIds = setOf("archivedFailed"),
            summaries = mapOf(
                "done" to session("done", completionUnread = true, updatedAt = 2_000L),
                "archivedFailed" to session("archivedFailed", failureUnread = true, updatedAt = 3_000L)
                    .copy(failureReason = "崩了"),
            ),
        )
        assertEquals(SessionStatus.Done, g.unreadStatus)
        assertEquals("", g.unreadFailureReason)
    }

}
