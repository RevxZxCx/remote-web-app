package com.revxzxcx.remotewebapp.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceReducer] 的单测 —— 每条帧语义都要覆盖（M1 验收项之一）。
 */
class WorkspaceReducerTest {

    private fun ws(id: String, sessions: List<String> = emptyList()) =
        Workspace(workspaceId = id, path = "/p/$id", sessionIds = sessions)

    private val empty = WorkspaceSnapshot()

    @Test
    fun nullFrameKeepsCurrentSnapshot() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1")))
        assertEquals(current, WorkspaceReducer.reduce(current, null))
    }

    @Test
    fun baselineReplacesEverything() {
        val current = WorkspaceSnapshot(items = listOf(ws("old")), pinnedSessionIds = setOf("s1"))
        val next = WorkspaceReducer.reduce(
            current,
            WorkspaceFrame.Baseline(WorkspaceSnapshot(items = listOf(ws("w1")), archivedSessionIds = setOf("s9"))),
        )
        assertEquals(listOf("w1"), next.items.map { it.workspaceId })
        assertEquals(setOf("s9"), next.archivedSessionIds)
        // baseline 是权威快照，旧的 pinned 一并被替换。
        assertTrue(next.pinnedSessionIds.isEmpty())
    }

    @Test
    fun upsertAppendsNewWorkspace() {
        val next = WorkspaceReducer.reduce(empty, WorkspaceFrame.Upsert(ws("w1")))
        assertEquals(listOf("w1"), next.items.map { it.workspaceId })
    }

    @Test
    fun upsertReplacesInPlacePreservingOrder() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1"), ws("w2"), ws("w3")))
        val next = WorkspaceReducer.reduce(
            current,
            WorkspaceFrame.Upsert(Workspace(workspaceId = "w2", path = "/new", title = "T")),
        )
        assertEquals(listOf("w1", "w2", "w3"), next.items.map { it.workspaceId })
        assertEquals("T", next.items[1].title)
    }

    @Test
    fun removeDropsWorkspace() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1"), ws("w2")))
        val next = WorkspaceReducer.reduce(current, WorkspaceFrame.Remove("w1"))
        assertEquals(listOf("w2"), next.items.map { it.workspaceId })
    }

    @Test
    fun orderReordersAndKeepsUnlistedTail() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1"), ws("w2"), ws("w3")))
        val next = WorkspaceReducer.reduce(current, WorkspaceFrame.Order(listOf("w3", "w1")))
        // 服务端只发了部分顺序时不能丢条目。
        assertEquals(listOf("w3", "w1", "w2"), next.items.map { it.workspaceId })
    }

    @Test
    fun orderIgnoresUnknownIds() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1")))
        val next = WorkspaceReducer.reduce(current, WorkspaceFrame.Order(listOf("ghost", "w1")))
        assertEquals(listOf("w1"), next.items.map { it.workspaceId })
    }

    @Test
    fun archivedAndPinnedReplaceTheirGlobalSets() {
        val current = WorkspaceSnapshot(items = listOf(ws("w1")), archivedSessionIds = setOf("a"), pinnedSessionIds = setOf("p"))
        val archived = WorkspaceReducer.reduce(current, WorkspaceFrame.Archived(listOf("b", "c")))
        assertEquals(setOf("b", "c"), archived.archivedSessionIds)
        assertEquals(setOf("p"), archived.pinnedSessionIds)

        val pinned = WorkspaceReducer.reduce(archived, WorkspaceFrame.Pinned(listOf("x")))
        assertEquals(setOf("x"), pinned.pinnedSessionIds)
    }

    @Test
    fun foldOfRealisticSequenceProducesExpectedSnapshot() {
        val frames = listOf(
            WorkspaceFrame.Baseline(WorkspaceSnapshot(items = listOf(ws("w1"), ws("w2")))),
            WorkspaceFrame.Upsert(ws("w3")),
            WorkspaceFrame.Remove("w1"),
            WorkspaceFrame.Order(listOf("w3", "w2")),
            WorkspaceFrame.Archived(listOf("s7")),
            WorkspaceFrame.Pinned(listOf("s8")),
        )
        val final = frames.fold(WorkspaceSnapshot()) { acc, f -> WorkspaceReducer.reduce(acc, f) }
        assertEquals(listOf("w3", "w2"), final.items.map { it.workspaceId })
        assertEquals(setOf("s7"), final.archivedSessionIds)
        assertEquals(setOf("s8"), final.pinnedSessionIds)
    }

    @Test
    fun snapshotLookupFindsOwningWorkspace() {
        val snapshot = WorkspaceSnapshot(items = listOf(ws("w1", listOf("s1")), ws("w2", listOf("s2"))))
        assertEquals("w2", snapshot.workspaceOf("s2")?.workspaceId)
        assertEquals(null, snapshot.workspaceOf("s9"))
        assertTrue(WorkspaceSnapshot().isEmpty)
    }
}
