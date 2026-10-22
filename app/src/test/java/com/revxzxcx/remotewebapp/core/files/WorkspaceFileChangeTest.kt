package com.revxzxcx.remotewebapp.core.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `workspaceFiles/changes` 的帧解析（R042 第 ② 条）。
 *
 * 这些样本**照抄服务端真实帧**，形状取自桌面端 `app.asar` 里的类型声明：
 *
 * ```
 * export type WorkspaceFileWatchFrame =
 *   { kind: 'ready' } |
 *   { kind: 'change', change: WorkspaceFileChange };
 * export type WorkspaceFileChange =
 *   { absolutePath: string, version: string } |
 *   { absolutePath: string, absent: true };
 * ```
 *
 * 为什么这份解析值得单测：监听流只要断一次，目录就再也不会自动更新，而它断掉
 * 的表现是「列表停在旧内容」——不报错、不崩溃，靠肉眼很难发现。所以「哪些帧该
 * 返回 null（不打断流）」和「哪些帧必须解析成功」都得钉住。
 */
class WorkspaceFileChangeTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun readyFrameIsIgnored() {
        // `ready` 只表示握手完成，不带任何路径；把它当变更解析会得到一个空路径。
        assertNull(WorkspaceFileChange.parse(obj("""{"kind":"ready"}""")))
    }

    @Test
    fun changeFrameCarriesPathAndVersion() {
        val frame = obj(
            """{"kind":"change","change":{"absolutePath":"F:\\bot\\remote-web-app\\docs","version":"2296164758:1688849860631842:539"}}""",
        )
        val change = WorkspaceFileChange.parse(frame)
        assertEquals("F:\\bot\\remote-web-app\\docs", change?.absolutePath)
        assertEquals("2296164758:1688849860631842:539", change?.version)
        assertEquals(false, change?.absent)
    }

    @Test
    fun absentChangeHasNoVersion() {
        // 删除时服务端给 `{absent:true}` 而不是 version：这一帧同样意味着「目录变了」，
        // 必须照常返回，否则删掉文件后列表不会少那一行。
        val change = WorkspaceFileChange.parse(
            obj("""{"kind":"change","change":{"absolutePath":"F:\\tmp\\gone.txt","absent":true}}"""),
        )
        assertEquals("F:\\tmp\\gone.txt", change?.absolutePath)
        assertNull(change?.version)
        assertTrue(change!!.absent)
    }

    @Test
    fun versionThatLooksFalsyIsStillKept() {
        // 别用 `?: ` 之外的「空串即没有」判断：version 是不透明串，内容没有约定。
        val change = WorkspaceFileChange.parse(
            obj("""{"kind":"change","change":{"absolutePath":"a","version":""}}"""),
        )
        assertEquals("", change?.version)
    }

    @Test
    fun unknownKindIsIgnoredRatherThanCrashing() {
        // 服务端将来加新帧类型时，宁可少一次刷新，也不该让整个监听流因解析失败而断。
        assertNull(WorkspaceFileChange.parse(obj("""{"kind":"renamed"}""")))
        // 关键在于**即使新帧也带了 change 对象**，只要 kind 不是 change 就得丢掉：
        // 否则将来出现 `{kind:'move', change:{…}}` 时会被当成一次普通变更处理。
        assertNull(
            WorkspaceFileChange.parse(
                obj("""{"kind":"renamed","change":{"absolutePath":"F:\\x","version":"v"}}"""),
            ),
        )
    }

    @Test
    fun changeWithoutPathIsDropped() {
        assertNull(WorkspaceFileChange.parse(obj("""{"kind":"change","change":{"version":"v"}}""")))
        assertNull(WorkspaceFileChange.parse(obj("""{"kind":"change"}""")))
    }

    @Test
    fun nonObjectPayloadIsDropped() {
        // 防御性：mux 的 item 帧类型是 `JsonElement`，理论上可能是数组或字面量。
        assertNull(WorkspaceFileChange.parse(Json.parseToJsonElement("[1,2]")))
        assertNull(WorkspaceFileChange.parse(Json.parseToJsonElement("\"ready\"")))
    }
}
