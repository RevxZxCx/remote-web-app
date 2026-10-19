package com.revxzxcx.remotewebapp.ui.home

import com.revxzxcx.remotewebapp.core.session.SessionListResult
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉搜索的纯逻辑测试（R036）。
 *
 * 不套 Robolectric：`searchSessions` 只吃两个数据类、只吐列表，没有任何 Android 依赖。
 * 放在纯 JVM 里跑的意义是——搜索的正确性（**去重**、匹配哪些字段、截断、排序）与
 * 「输入框长什么样」是两件事，不该为了验前者去组合一整棵界面。
 *
 * 输入框那一侧（能输入、能清空、有焦点态）由 [SessionDrawerRenderTest] 的渲染用例守。
 */
class SessionFilterTest {

    private fun session(
        sessionId: String,
        title: String,
        updatedAt: Long = 1_000L,
        lastModel: String? = null,
    ) = SessionSummary(
        sessionId = sessionId,
        title = title,
        updatedAt = updatedAt,
        agentAvailable = true,
        lastModel = lastModel,
    )

    private fun snapshot(
        vararg ids: String,
        archived: Set<String> = emptySet(),
        pinned: Set<String> = emptySet(),
    ) = WorkspaceSnapshot(
        items = listOf(
            Workspace(
                workspaceId = "ws-a",
                path = "F:\\a",
                title = "bot",
                sessionIds = ids.toList(),
            ),
        ),
        archivedSessionIds = archived,
        pinnedSessionIds = pinned,
    )

    @Test
    fun `空关键词返回空列表而不是全部`() {
        // 「没输入任何东西」不能等于「显示全部」：抽屉的常规视图已经按「最近 / 置顶 /
        // 按工作区」分好组了，搜索框空着时再列一遍全部会是同一批会话的第三种排法。
        val sessions = SessionListResult(listOf(session("s1", "R033 抽屉")))
        assertEquals(emptyList<SearchHit>(), searchSessions(snapshot("s1"), sessions, ""))
        assertEquals(emptyList<SearchHit>(), searchSessions(snapshot("s1"), sessions, "   "))
    }

    @Test
    fun `按标题匹配且大小写不敏感`() {
        val sessions = SessionListResult(
            listOf(
                session("s1", "R033 抽屉美化"),
                session("s2", "配对页重排"),
            ),
        )
        val snap = snapshot("s1", "s2")

        assertEquals(listOf("s1"), searchSessions(snap, sessions, "r033").map { it.summary.sessionId })
        // 中文没有大小写，这条只为英文会话名服务。
        assertEquals(listOf("s1"), searchSessions(snap, sessions, "R033").map { it.summary.sessionId })
    }

    @Test
    fun `按归属工作区名匹配`() {
        // 用户经常记得「那是 bot 里的会话」而不记得标题。
        val sessions = SessionListResult(listOf(session("s1", "完全无关的标题")))
        val hits = searchSessions(snapshot("s1"), sessions, "bot")

        assertEquals(1, hits.size)
        assertEquals("bot", hits.single().workspace?.displayName)
    }

    @Test
    fun `按模型名匹配`() {
        val sessions = SessionListResult(
            listOf(
                session("s1", "甲", lastModel = "deepseek-v4.1-flash"),
                session("s2", "乙", lastModel = "qwen3-coder"),
            ),
        )
        val hits = searchSessions(snapshot("s1", "s2"), sessions, "qwen")
        assertEquals(listOf("s2"), hits.map { it.summary.sessionId })
    }

    @Test
    fun `会话列表里的重复条目只出现一次`() {
        // `session/list` 的解析只做 mapNotNull，不去重（这是真实存在的数据形态，
        // R035 的归档聚合视图就因此崩过）。搜索结果同样按 sessionId 去重，
        // 否则同一会话会在结果里出现两次、`LazyColumn` 还会撞 key。
        val dup = session("s1", "重复会话", updatedAt = 1_000L)
        val sessions = SessionListResult(listOf(dup, dup.copy(updatedAt = 2_000L)))

        val hits = searchSessions(snapshot("s1"), sessions, "重复")
        assertEquals(1, hits.size)
        assertEquals("s1", hits.single().summary.sessionId)
    }

    @Test
    fun `结果按最近活动排序`() {
        val sessions = SessionListResult(
            listOf(
                session("old", "R033 旧", updatedAt = 1_000L),
                session("new", "R033 新", updatedAt = 9_000L),
                session("mid", "R033 中", updatedAt = 5_000L),
            ),
        )
        val hits = searchSessions(snapshot("old", "new", "mid"), sessions, "R033")
        assertEquals(listOf("new", "mid", "old"), hits.map { it.summary.sessionId })
    }

    @Test
    fun `命中过多时截断到上限`() {
        val many = (1..(SEARCH_RESULT_LIMIT + 20)).map {
            session("s$it", "会话 $it", updatedAt = it.toLong())
        }
        val snap = WorkspaceSnapshot(
            items = listOf(
                Workspace(
                    workspaceId = "ws-a",
                    path = "F:\\a",
                    title = "bot",
                    sessionIds = many.map { it.sessionId },
                ),
            ),
        )
        val hits = searchSessions(snap, SessionListResult(many), "会话")
        assertEquals(SEARCH_RESULT_LIMIT, hits.size)
        // 截断发生在排序之后：留下来的必须是**最新**的那一批，而不是列表里最靠前的。
        assertEquals("s${SEARCH_RESULT_LIMIT + 20}", hits.first().summary.sessionId)
    }

    @Test
    fun `归档与置顶状态跟着命中项一起带出来`() {
        // 搜索结果**不排除**归档会话：用户搜的是「我记得有这么个会话」，它被归档了
        // 恰恰是更要紧的信息。但结果里必须能看出它已归档，否则点开再返回就找不到了。
        val sessions = SessionListResult(listOf(session("s1", "归档了的 R033")))
        val hits = searchSessions(
            snapshot("s1", archived = setOf("s1"), pinned = emptySet()),
            sessions,
            "R033",
        )
        assertEquals(1, hits.size)
        assertTrue(hits.single().archived)
        assertEquals(false, hits.single().pinned)
    }

    @Test
    fun `子代理会话不出现在搜索结果里`() {
        // `session/list` 实测 125 条里有 73 条是 `origin == "subagent"`（本机跑过大量
        // 审查/探针子会话）。抽屉三段都以 `topLevel` 或工作区 `sessionIds` 为骨架、
        // 天然不含它们；搜索若不过滤，就会给出「列表里根本没有的行」——点进去打开一个
        // 子代理会话，而用户从列表里无论如何找不到它。
        val sessions = SessionListResult(
            listOf(
                session("s1", "R033 抽屉美化"),
                session("s2", "R033 子代理探针").copy(origin = "subagent", parentSessionId = "s1"),
            ),
        )
        val hits = searchSessions(snapshot("s1", "s2"), sessions, "R033")

        assertEquals(listOf("s1"), hits.map { it.summary.sessionId })
    }

    @Test
    fun `没有归属工作区的会话也能被搜到`() {
        // `workspace/follow` 快照与会话列表是两条独立通道，会话可能先到、工作区归属
        // 还没到。此时不能让这条会话从搜索里消失——它只是没有副标题。
        val sessions = SessionListResult(listOf(session("orphan", "孤儿 R033")))
        val hits = searchSessions(WorkspaceSnapshot(), sessions, "R033")

        assertEquals(1, hits.size)
        assertEquals(null, hits.single().workspace)
    }
}
