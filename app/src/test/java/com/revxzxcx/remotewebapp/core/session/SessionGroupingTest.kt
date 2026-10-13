package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionGrouping] 的单测。
 *
 * 边界全部来自 M2 探针实测的真实数据（`测试记录.md` T009）：
 * 5 个工作区 / 27 个 id / 47 条会话（含 20 条子代理）/ 14 条归档 / 0 条置顶。
 */
class SessionGroupingTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        running: Boolean = false,
        origin: String? = null,
        title: String = "",
    ) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        running = running,
        origin = origin,
        title = title,
    )

    private fun workspace(id: String, vararg sessionIds: String) =
        Workspace(workspaceId = id, path = "F:\\$id", title = id, sessionIds = sessionIds.toList())

    @Test
    fun `按 workspace 的 sessionIds 分组并解析`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2"), workspace("vm", "s3")),
        )
        val sessions = SessionListResult(listOf(session("s1"), session("s2"), session("s3")))

        val groups = SessionGrouping.group(snapshot, sessions)

        assertEquals(2, groups.size)
        assertEquals(listOf("s1", "s2"), groups[0].rows.map { it.sessionId })
        assertEquals(listOf("s3"), groups[1].rows.map { it.sessionId })
        assertTrue(groups.all { it.missingIds.isEmpty() })
    }

    @Test
    fun `子代理会话不出现在任何分组里`() {
        // 实测 20 条 origin=subagent 不在任何 workspace.sessionIds 里。
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "s1")))
        val sessions = SessionListResult(
            listOf(session("s1"), session("sub", origin = "subagent")),
        )

        val groups = SessionGrouping.group(snapshot, sessions)

        assertEquals(listOf("s1"), groups.single().rows.map { it.sessionId })
    }

    @Test
    fun `分组内按 updatedAt 降序而不是沿用服务端顺序`() {
        // 实测 workspace.sessionIds 顺序不是按时间排的（bot 工作区是 07:15, 04:28, 16:57, 07:13）。
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "a", "b", "c")))
        val sessions = SessionListResult(
            listOf(session("a", updatedAt = 715), session("b", updatedAt = 428), session("c", updatedAt = 1657)),
        )

        val groups = SessionGrouping.group(snapshot, sessions)

        assertEquals(listOf("c", "a", "b"), groups.single().rows.map { it.sessionId })
    }

    @Test
    fun `解析不到的 sessionId 进入 missingIds 而不是产生空行`() {
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "s1", "gone")))
        val sessions = SessionListResult(listOf(session("s1")))

        val group = SessionGrouping.group(snapshot, sessions).single()

        assertEquals(listOf("s1"), group.rows.map { it.sessionId })
        assertEquals(listOf("gone"), group.missingIds)
    }

    @Test
    fun `归档集合是跨工作区的全局集合`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2"), workspace("vm", "s3")),
            archivedSessionIds = setOf("s1", "s3"),
        )
        val sessions = SessionListResult(listOf(session("s1"), session("s2"), session("s3")))

        val groups = SessionGrouping.group(snapshot, sessions)

        assertEquals(
            listOf(SessionGrouping.Bucket.ARCHIVED, SessionGrouping.Bucket.RECENT),
            groups[0].rows.map { it.bucket },
        )
        assertEquals(SessionGrouping.Bucket.ARCHIVED, groups[1].rows.single().bucket)
        assertEquals(1, groups[0].archivedCount)
        assertEquals(1, groups[0].activeCount)
    }

    @Test
    fun `置顶优先于归档`() {
        val snapshot = WorkspaceSnapshot(
            archivedSessionIds = setOf("s1"),
            pinnedSessionIds = setOf("s1"),
        )
        assertEquals(SessionGrouping.Bucket.PINNED, SessionGrouping.bucketOf("s1", snapshot))
    }

    @Test
    fun `既未置顶也未归档时为 RECENT`() {
        assertEquals(SessionGrouping.Bucket.RECENT, SessionGrouping.bucketOf("s1", WorkspaceSnapshot()))
    }

    @Test
    fun `hasRunning 反映组内是否有运行中的会话`() {
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "a", "b")))
        val sessions = SessionListResult(listOf(session("a"), session("b", running = true)))

        assertTrue(SessionGrouping.group(snapshot, sessions).single().hasRunning)

        val idle = SessionListResult(listOf(session("a"), session("b")))
        assertTrue(!SessionGrouping.group(snapshot, idle).single().hasRunning)
    }

    @Test
    fun `工作区顺序与快照一致`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("third"), workspace("first"), workspace("second")),
        )
        val groups = SessionGrouping.group(snapshot, SessionListResult())
        assertEquals(listOf("third", "first", "second"), groups.map { it.workspace.workspaceId })
    }

    @Test
    fun `recentSessions 只含顶层会话且默认排除归档`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2")),
            archivedSessionIds = setOf("s2"),
        )
        val sessions = SessionListResult(
            listOf(
                session("s1", updatedAt = 100),
                session("s2", updatedAt = 200),
                session("sub", updatedAt = 300, origin = "subagent"),
                session("orphan", updatedAt = 400),
            ),
        )

        val recent = SessionGrouping.recentSessions(snapshot, sessions)

        assertEquals(listOf("s1"), recent.map { it.sessionId })
    }

    @Test
    fun `recentSessions 可包含归档并严格按时间降序`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2")),
            archivedSessionIds = setOf("s2"),
        )
        val sessions = SessionListResult(
            listOf(session("s1", updatedAt = 100), session("s2", updatedAt = 200)),
        )

        val recent = SessionGrouping.recentSessions(snapshot, sessions, includeArchived = true)

        assertEquals(listOf("s2", "s1"), recent.map { it.sessionId })
    }

    @Test
    fun `pinnedSessions 返回会话与所属工作区`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1"), workspace("vm", "s2")),
            pinnedSessionIds = setOf("s2", "s1"),
        )
        val sessions = SessionListResult(
            listOf(session("s1", updatedAt = 100), session("s2", updatedAt = 200)),
        )

        val pinned = SessionGrouping.pinnedSessions(snapshot, sessions)

        assertEquals(listOf("s2", "s1"), pinned.map { it.first.sessionId })
        assertEquals("vm", pinned[0].second?.workspaceId)
        assertEquals("bot", pinned[1].second?.workspaceId)
    }

    @Test
    fun `pinnedSessions 对已消失的会话返回 null 归属而不是崩溃`() {
        val snapshot = WorkspaceSnapshot(pinnedSessionIds = setOf("ghost"))
        val pinned = SessionGrouping.pinnedSessions(snapshot, SessionListResult())
        assertTrue(pinned.isEmpty())
    }

    @Test
    fun `pinnedRows 只取组内被置顶的会话`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2")),
            pinnedSessionIds = setOf("s2"),
        )
        val sessions = SessionListResult(
            listOf(session("s1", updatedAt = 100), session("s2", updatedAt = 50)),
        )

        val group = SessionGrouping.group(snapshot, sessions).single()

        assertEquals(listOf("s2"), group.pinnedRows.map { it.sessionId })
    }

    @Test
    fun `recentSessions 排除置顶会话避免抽屉里重复显示`() {
        // 置顶会话在抽屉里有独立的「置顶」段，若同时出现在「最近」里用户会看到两次（R003）。
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "s1", "s2")),
            pinnedSessionIds = setOf("s2"),
        )
        val sessions = SessionListResult(
            listOf(session("s1", updatedAt = 100), session("s2", updatedAt = 200)),
        )

        val recent = SessionGrouping.recentSessions(snapshot, sessions)

        assertEquals(listOf("s1"), recent.map { it.sessionId })
    }

    @Test
    fun `sessionIds 里重复的 id 只产生一行`() {
        // 重复的行会让 LazyColumn 的 key 冲突直接抛异常，所以在分组层去重（R003）。
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "s1", "s1", "s1")))
        val sessions = SessionListResult(listOf(session("s1")))

        val group = SessionGrouping.group(snapshot, sessions).single()

        assertEquals(listOf("s1"), group.rows.map { it.sessionId })
        assertEquals(1, group.activeCount)
    }

    @Test
    fun `recentSessions 对 sessionList 里的重复条目只保留一条`() {
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("bot", "s1")))
        val sessions = SessionListResult(
            listOf(session("s1", updatedAt = 100), session("s1", updatedAt = 200)),
        )

        val recent = SessionGrouping.recentSessions(snapshot, sessions)

        assertEquals(listOf("s1"), recent.map { it.sessionId })
    }

    @Test
    fun `空快照产生空分组`() {
        assertTrue(SessionGrouping.group(WorkspaceSnapshot(), SessionListResult()).isEmpty())
        assertTrue(SessionGrouping.recentSessions(WorkspaceSnapshot(), SessionListResult()).isEmpty())
        assertTrue(SessionGrouping.pinnedSessions(WorkspaceSnapshot(), SessionListResult()).isEmpty())
    }

    @Test
    fun `工作区无会话时不崩且计数为 0`() {
        val snapshot = WorkspaceSnapshot(items = listOf(workspace("empty")))
        val group = SessionGrouping.group(snapshot, SessionListResult()).single()
        assertTrue(group.rows.isEmpty())
        assertEquals(0, group.activeCount)
        assertEquals(0, group.archivedCount)
        assertTrue(group.pinnedRows.isEmpty())
    }

    @Test
    fun `同一会话出现在两个工作区时两边都显示`() {
        // 服务端理论上不该这样，但客户端不该因此丢数据或崩掉。
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("a", "s1"), workspace("b", "s1")),
        )
        val sessions = SessionListResult(listOf(session("s1")))

        val groups = SessionGrouping.group(snapshot, sessions)

        assertEquals(listOf("s1"), groups[0].rows.map { it.sessionId })
        assertEquals(listOf("s1"), groups[1].rows.map { it.sessionId })
    }

    @Test
    fun `SessionRow 暴露归档与置顶状态`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(workspace("bot", "a", "b")),
            archivedSessionIds = setOf("a"),
            pinnedSessionIds = setOf("b"),
        )
        val sessions = SessionListResult(listOf(session("a"), session("b")))

        val rows = SessionGrouping.group(snapshot, sessions).single().rows.associateBy { it.sessionId }

        assertTrue(rows.getValue("a").isArchived)
        assertTrue(!rows.getValue("a").isPinned)
        assertTrue(rows.getValue("b").isPinned)
        assertTrue(!rows.getValue("b").isArchived)
    }

    @Test
    fun `displayName 对空标题给出可区分的兜底`() {
        val a = session("session-aaaaaaaaaaaa").displayName
        val b = session("session-bbbbbbbbbbbb").displayName
        assertTrue(a != b)
        assertTrue(a.contains("未命名会话"))
    }
}
