package com.revxzxcx.remotewebapp.core.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceFrameParser] + [WorkspaceReducer] 的单测。
 *
 * 帧形状取自 `@deepseek-ai/dsh-api-workspace-controller/lib/typert.remote-client.js`
 * 的 `WorkspaceFollowFrame`：baseline / upsert / remove / order / archived / pinned。
 */
class WorkspaceFrameParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser = WorkspaceFrameParser()

    private fun frame(text: String) = parser.parse(json.parseToJsonElement(text))

    private fun wsJson(id: String, path: String, title: String = "", sessions: String = "") =
        """{"workspaceId":"$id","path":"$path","title":"$title","sessionIds":[$sessions],
            "createdAt":1700000000000,"updatedAt":1700000001000}"""

    @Test
    fun parsesBaselineWithItemsAndGlobalSets() {
        val text = """
            {"type":"baseline","value":{
              "items":[${wsJson("w1", "/home/u/proj", "Proj", "\"s1\",\"s2\"")},
                       ${wsJson("w2", "/home/u/other")}],
              "archivedSessionIds":["s2"],
              "pinnedSessionIds":["s1"]}}
        """
        val baseline = frame(text) as WorkspaceFrame.Baseline
        assertEquals(2, baseline.snapshot.items.size)
        assertEquals("Proj", baseline.snapshot.items[0].title)
        assertEquals(listOf("s1", "s2"), baseline.snapshot.items[0].sessionIds)
        assertEquals(setOf("s2"), baseline.snapshot.archivedSessionIds)
        assertEquals(setOf("s1"), baseline.snapshot.pinnedSessionIds)
        assertEquals("w1", baseline.snapshot.workspaceOf("s1")?.workspaceId)
    }

    @Test
    fun parsesUpsertRemoveOrderArchivedPinned() {
        assertTrue(
            frame("""{"type":"upsert","workspace":${wsJson("w1", "/p")}}""")
                is WorkspaceFrame.Upsert,
        )
        assertEquals("w9", (frame("""{"type":"remove","workspaceId":"w9"}""") as WorkspaceFrame.Remove).workspaceId)
        assertEquals(
            listOf("w2", "w1"),
            (frame("""{"type":"order","workspaceIds":["w2","w1"]}""") as WorkspaceFrame.Order).workspaceIds,
        )
        assertEquals(
            listOf("s3"),
            (frame("""{"type":"archived","archivedSessionIds":["s3"]}""") as WorkspaceFrame.Archived).archivedSessionIds,
        )
        assertEquals(
            listOf("s4"),
            (frame("""{"type":"pinned","pinnedSessionIds":["s4"]}""") as WorkspaceFrame.Pinned).pinnedSessionIds,
        )
    }

    @Test
    fun unknownFrameTypeReturnsNull() {
        assertNull(frame("""{"type":"something-new","x":1}"""))
        assertNull(frame("""{"noType":true}"""))
    }

    @Test
    fun malformedFramesReturnNullInsteadOfThrowing() {
        assertNull(frame("""{"type":"baseline"}"""))
        assertNull(frame("""{"type":"upsert"}"""))
        assertNull(frame("""{"type":"upsert","workspace":{"path":"/no-id"}}"""))
        assertNull(frame("""[]"""))
    }

    @Test
    fun workspaceWithoutOptionalFieldsStillParses() {
        val ws = parser.workspaceOf(json.parseToJsonElement("""{"workspaceId":"w1"}""") as JsonObject)
        assertEquals("w1", ws?.workspaceId)
        assertEquals("", ws?.path)
        assertEquals(0L, ws?.updatedAt)
    }

    @Test
    fun displayNameFallsBackToLastPathSegment() {
        assertEquals("proj", Workspace("w1", path = "/home/u/proj").displayName)
        assertEquals("proj", Workspace("w1", path = "C:\\work\\proj").displayName)
        assertEquals("Proj", Workspace("w1", path = "/home/u/proj", title = "Proj").displayName)
        assertEquals("", Workspace("w1").displayName)
    }
}
