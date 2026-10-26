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

    // ------------------------------------------------------------------
    // R049：完成 / 失败两条未读提醒
    // ------------------------------------------------------------------

    @Test
    fun patchUnreadFlagsMarksOnlyThatSession() {
        val before = listOf(summary("a"), summary("b"))

        val after = SessionListUpdates.patchUnreadFlags(
            items = before,
            sessionId = "b",
            completionUnread = true,
            failureUnread = false,
        )

        assertEquals(listOf(false, true), after.map { it.completionUnread })
    }

    @Test
    fun patchUnreadFlagsOnUnknownSessionReturnsSameList() {
        val before = listOf(summary("a"))

        assertSame(
            before,
            SessionListUpdates.patchUnreadFlags(before, "nope", true, false),
        )
    }

    @Test
    fun runningClearsBothReminders() {
        // 用户看到红点或绿点后让会话继续跑：running=true 说明这一轮重新开始了，
        // 两条提醒都该灭——新的结果会重新决定点亮什么。
        val before = listOf(
            summary("a").copy(completionUnread = true, failureUnread = true),
        )

        val after = SessionListUpdates.patchRunning(before, "a", running = true)

        assertEquals(false, after.single().completionUnread)
        assertEquals(false, after.single().failureUnread)
        assertEquals(true, after.single().running)
    }

    @Test
    fun stoppingLeavesTheRemindersAlone() {
        // running=false 只是「这一轮跑完了」，不代表用户看过提醒：是否熄灭由
        // CompletionLedger 的边沿判定说了算，patchRunning 不替它做主。
        val before = listOf(
            summary("a", running = true).copy(failureUnread = true),
        )

        val after = SessionListUpdates.patchRunning(before, "a", running = false)

        assertEquals(true, after.single().failureUnread)
        assertEquals(false, after.single().running)
    }

    @Test
    fun patchRunningStillShortCircuitsWhenNothingChanges() {
        // 短路条件必须同时看 running 与两条提醒：只看 running 会让
        // 「running 相同但有提醒要清」这条路径提前 return，点永远擦不掉。
        val clean = listOf(summary("a", running = true))
        assertSame(clean, SessionListUpdates.patchRunning(clean, "a", running = true))

        val marked = listOf(summary("a", running = true).copy(failureUnread = true))
        val after = SessionListUpdates.patchRunning(marked, "a", running = true)
        assertEquals(false, after.single().failureUnread)
    }

    @Test
    fun carryUnreadFlagsReappliesTheMarksAfterFullReload() {
        // `session/list` 不带这两个标记，每次全量重拉都会抹回默认值。
        // 不重新盖上的话，用户翻一次页点就没了，而那条会话依然是「跑完了没人看」。
        val reloaded = listOf(summary("a"), summary("b"), summary("c"))

        val after = SessionListUpdates.carryUnreadFlags(
            items = reloaded,
            completionIds = setOf("a"),
            failureIds = setOf("c"),
        )

        assertEquals(listOf(true, false, false), after.map { it.completionUnread })
        assertEquals(listOf(false, false, true), after.map { it.failureUnread })
    }

    /**
     * 全量重拉后失败原因必须一起盖回来（R049 二审 P2）。
     *
     * `session/list` 的新条目里 `failureReason` 恒为空串，只盖布尔的写法会让红点亮着
     * 但读屏退回通用文案——原因文本只活在 `CompletionLedger.failedReasons` 里。
     */
    @Test
    fun carryUnreadFlagsRestoresTheFailureReason() {
        val reloaded = listOf(summary("a"), summary("b"))

        val after = SessionListUpdates.carryUnreadFlags(
            items = reloaded,
            completionIds = emptySet(),
            failureIds = setOf("b"),
            failureReasons = mapOf("b" to "模型限流"),
        )

        assertEquals("", after[0].failureReason)
        assertEquals("模型限流", after[1].failureReason)
    }

    /** 已经带着同样原因时不产生新实例（幂等短路要把原因纳入比较）。 */
    @Test
    fun carryUnreadFlagsIsIdempotentForFailureReasons() {
        val already = listOf(
            summary("a").copy(failureUnread = true, failureReason = "模型限流"),
        )

        assertSame(
            already,
            SessionListUpdates.carryUnreadFlags(
                items = already,
                completionIds = emptySet(),
                failureIds = setOf("a"),
                failureReasons = mapOf("a" to "模型限流"),
            ),
        )
    }

    /** 熄灭红点时必须把原因一起清掉，否则下一条失败会读到上一次的文案。 */
    @Test
    fun patchUnreadFlagsClearsTheReasonWhenTheFailureIsCleared() {
        val before = listOf(summary("a").copy(failureUnread = true, failureReason = "模型限流"))

        val after = SessionListUpdates.patchUnreadFlags(
            items = before,
            sessionId = "a",
            completionUnread = false,
            failureUnread = false,
        )

        assertEquals("", after.single().failureReason)
    }

    /** 原因变化也必须算作「变了」，否则新原因写不进去。 */
    @Test
    fun patchUnreadFlagsUpdatesTheReasonWhenItChanges() {
        val before = listOf(summary("a").copy(failureUnread = true, failureReason = "旧原因"))

        val after = SessionListUpdates.patchUnreadFlags(
            items = before,
            sessionId = "a",
            completionUnread = false,
            failureUnread = true,
            failureReason = "新原因",
        )

        assertEquals("新原因", after.single().failureReason)
    }

    /**
     * 账本记住的运行态要能纠正陈旧快照（R049 三审 P2）。
     *
     * `session/list` 飞 2.6 秒，期间那条会话才开始跑：`status=true` 已把行 patch 成
     * 运行中，快照里仍是 `false`。不纠正的话小柱被打回静止，且那条事件已被消费，
     * 之后没有事件会再纠正它。
     */
    @Test
    fun patchRunningFromLedgerCorrectsStaleSnapshotValues() {
        val snapshot = listOf(summary("a"), summary("b"))

        val after = SessionListUpdates.patchRunningFromLedger(
            items = snapshot,
            updates = mapOf("a" to true),
        )

        assertEquals(listOf(true, false), after.map { it.running })
    }

    /** 账本与会话无关的 id 不动；状态一致时不产生新实例。 */
    @Test
    fun patchRunningFromLedgerLeavesUnrelatedRowsAlone() {
        val snapshot = listOf(summary("a"), summary("b").copy(running = true))

        val after = SessionListUpdates.patchRunningFromLedger(
            items = snapshot,
            updates = mapOf("b" to true, "zzz" to false),
        )

        assertSame(snapshot, after)
    }

    /** 空 updates 直接原样返回，避免无意义的重组。 */
    @Test
    fun patchRunningFromLedgerIsNoOpWithoutUpdates() {
        val snapshot = listOf(summary("a"))

        assertSame(snapshot, SessionListUpdates.patchRunningFromLedger(snapshot, emptyMap()))
    }

    @Test
    fun carryUnreadFlagsIsNoOpWithoutKnownReminders() {
        val reloaded = listOf(summary("a"))

        assertSame(reloaded, SessionListUpdates.carryUnreadFlags(reloaded, emptySet(), emptySet()))
    }

    @Test
    fun carryUnreadFlagsReturnsSameListWhenAlreadyMarked() {
        // 幂等：已经带标记的列表再盖一次不该产生新实例（避免无意义的重组）。
        val already = listOf(summary("a").copy(completionUnread = true))

        assertSame(already, SessionListUpdates.carryUnreadFlags(already, setOf("a"), emptySet()))
    }
}
