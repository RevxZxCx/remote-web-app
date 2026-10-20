package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [SessionListUpdates] 的纯函数测试。
 *
 * 这几条守的是 R039 用户报的两个现象背后的增量规则：
 * 1. 桌面端新建会话 → `api-session/added` → 列表里要出现（[SessionListUpdates.upsert]）；
 * 2. 会话跑完 → `api-session/status(false)` → 「进行中」小柱要停
 *    （[SessionListUpdates.patchRunning]）。
 *
 * 之所以能纯 JVM 跑：这三个函数不碰协程、不碰 Android，也不读全局状态。列表页真正的
 * 接线（`HomeViewModel.observeSessionNotifications`）依赖 `DshConnection`，工程里没有
 * 假容器先例，所以把可测的部分全部推到这里。
 */
class SessionListUpdatesTest {

    private fun summary(
        id: String,
        updatedAt: Long = 1_000L,
        running: Boolean = false,
        title: String = "",
        blank: Boolean = false,
    ) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        running = running,
        title = title,
        blank = blank,
    )

    // ------------------------------------------------------------------
    // upsert
    // ------------------------------------------------------------------

    @Test
    fun upsertAppendsUnknownSession() {
        val before = listOf(summary("a"), summary("b"))

        val after = SessionListUpdates.upsert(before, summary("c"))

        assertEquals(listOf("a", "b", "c"), after.map { it.sessionId })
    }

    @Test
    fun upsertReplacesInPlaceWithoutReordering() {
        // 位置必须保留：这个列表的顺序是缓存写穿的原始顺序，挪位会让「增量更新」与
        // 「全量重读」产生不同排列，两者交替时出现行跳变。
        val before = listOf(summary("a"), summary("b", title = "旧标题"), summary("c"))

        val after = SessionListUpdates.upsert(before, summary("b", title = "新标题"))

        assertEquals(listOf("a", "b", "c"), after.map { it.sessionId })
        assertEquals("新标题", after[1].title)
    }

    @Test
    fun upsertDoesNotMutateInputList() {
        val before = listOf(summary("a"))

        SessionListUpdates.upsert(before, summary("b"))

        assertEquals(listOf("a"), before.map { it.sessionId })
    }

    @Test
    fun upsertStartingFromEmptyProducesSingleItem() {
        assertEquals(1, SessionListUpdates.upsert(emptyList(), summary("solo")).size)
    }

    // ------------------------------------------------------------------
    // patchRunning —— R039「跑完了小柱还在转」的直接修复点
    // ------------------------------------------------------------------

    @Test
    fun patchRunningFlipsRunningFlag() {
        val before = listOf(summary("a", running = true), summary("b"))

        val after = SessionListUpdates.patchRunning(before, "a", running = false)

        assertEquals(listOf(false, false), after.map { it.running })
    }

    @Test
    fun patchRunningLeavesOtherEntriesIdentical() {
        val b = summary("b", title = "别动我")
        val before = listOf(summary("a", running = true), b)

        val after = SessionListUpdates.patchRunning(before, "a", running = false)

        // 引用相等：没被改的条目必须原样传下去，否则 LazyColumn 会因为
        // data class 相等但实例不同而白重组一遍。
        assertSame(b, after[1])
    }

    @Test
    fun patchRunningOnUnknownSessionReturnsSameList() {
        // 冷启动时序：`status` 可能先于 `session/list` 到达。此时凭空造一条摘要会带出
        // `updatedAt = 0` / `title = ""` 的假行，所以必须原样返回。
        val before = listOf(summary("a"))

        val after = SessionListUpdates.patchRunning(before, "幽灵会话", running = true)

        assertSame(before, after)
    }

    @Test
    fun patchRunningIsNoOpWhenFlagAlreadyMatches() {
        val before = listOf(summary("a", running = false))

        val after = SessionListUpdates.patchRunning(before, "a", running = false)

        assertSame(before, after)
    }

    // ------------------------------------------------------------------
    // remove
    // ------------------------------------------------------------------

    @Test
    fun removeDropsOnlyThatSession() {
        val before = listOf(summary("a"), summary("b"), summary("c"))

        val after = SessionListUpdates.remove(before, "b")

        assertEquals(listOf("a", "c"), after.map { it.sessionId })
    }

    @Test
    fun removeUnknownSessionReturnsSameList() {
        val before = listOf(summary("a"))

        assertSame(before, SessionListUpdates.remove(before, "nope"))
    }
}
