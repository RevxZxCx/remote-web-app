package com.revxzxcx.remotewebapp.core.cache

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.SessionEventParser
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CacheMappers] 的单测。
 *
 * 为什么只测这一层：Room 的 DAO/数据库需要 Android 运行时，本工程不引入 Robolectric
 * （决策 D21）。映射全是纯函数，所以「缓存写进去什么、读出来什么」是可测的，只有
 * 「Room 会不会把行存对」交给真机——那是 Room 自己的责任。
 *
 * 这些用例钉的是**round-trip 不丢字段**：缓存最典型的缺陷不是崩溃，而是某个字段
 * 在往返中被悄悄丢掉（例如 `archive`/`pinned` 集合丢了，冷启动后归档会话就跑到
 * 「最近」里去了）。
 */
class CacheMappersTest {

    private val parser = SessionEventParser()

    private fun credential(
        baseUrl: String = "http://192.168.1.5:19387",
        deviceId: String = "dev-1234",
    ) = DeviceCredential(baseUrl = baseUrl, deviceId = deviceId, pairedAt = 1L)

    // ------------------------------------------------------------------ ownerId

    @Test
    fun `同一凭证的 ownerId 稳定`() {
        assertEquals(CacheMappers.ownerIdOf(credential()), CacheMappers.ownerIdOf(credential()))
    }

    @Test
    fun `换 deviceId 就换分区`() {
        assertNotEquals(
            CacheMappers.ownerIdOf(credential(deviceId = "dev-1234")),
            CacheMappers.ownerIdOf(credential(deviceId = "dev-5678")),
        )
    }

    @Test
    fun `换桌面端地址就换分区`() {
        assertNotEquals(
            CacheMappers.ownerIdOf(credential(baseUrl = "http://192.168.1.5:19387")),
            CacheMappers.ownerIdOf(credential(baseUrl = "https://xxx.dsh-market.com")),
        )
    }

    @Test
    fun `ownerId 不含凭证原文`() {
        // 缓存库是明文 SQLite（没有 Keystore 保护），把凭证写进去等于把「已配对设备 =
        // FULL-CONTROL 凭证」降级。指纹必须不可反推。
        val id = CacheMappers.ownerIdOf(credential(deviceId = "super-secret-device"))
        assertTrue("ownerId 不该包含 deviceId 原文：$id", !id.contains("super-secret-device"))
        assertTrue("ownerId 不该包含地址：$id", !id.contains("192.168"))
    }

    @Test
    fun `地址与 deviceId 的拼接无歧义`() {
        // 用 \u0000 分隔而不是直接字符串拼接：否则 ("a","bc") 与 ("ab","c") 会撞成同一个分区。
        assertNotEquals(
            CacheMappers.ownerIdOf(credential(baseUrl = "http://a", deviceId = "bc")),
            CacheMappers.ownerIdOf(credential(baseUrl = "http://ab", deviceId = "c")),
        )
    }

    // ------------------------------------------------------------------ workspaces

    @Test
    fun `工作区往返不丢字段`() {
        val workspace = Workspace(
            workspaceId = "ws-1",
            path = "F:\\bot",
            title = "bot",
            sessionIds = listOf("s-1", "s-2"),
            createdAt = 100L,
            updatedAt = 200L,
        )
        val model = CacheMappers.toModel(CacheMappers.toEntity("own", workspace, orderIndex = 3))

        assertEquals(workspace, model)
    }

    @Test
    fun `工作区顺序按下标保存`() {
        val snapshot = WorkspaceSnapshot(
            items = listOf(
                Workspace(workspaceId = "a"),
                Workspace(workspaceId = "b"),
                Workspace(workspaceId = "c"),
            ),
        )
        val rows = CacheMappers.toEntities("own", snapshot)

        assertEquals(listOf(0, 1, 2), rows.map { it.orderIndex })
        assertEquals(listOf("a", "b", "c"), rows.map { it.workspaceId })
    }

    @Test
    fun `工作区标题可以为空`() {
        val model = CacheMappers.toModel(CacheMappers.toEntity("own", Workspace(workspaceId = "w"), 0))
        assertEquals("", model.title)
    }

    @Test
    fun `sessionIds 的 JSON 坏掉时退化成空列表而不是崩`() {
        val entity = CacheMappers.toEntity("own", Workspace(workspaceId = "w"), 0)
            .copy(sessionIdsJson = "{不是数组}")
        assertTrue(CacheMappers.toModel(entity).sessionIds.isEmpty())
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun `会话摘要往返不丢字段`() {
        val summary = SessionSummary(
            sessionId = "s-1",
            updatedAt = 1_791_875_609_869L,
            agentAvailable = false,
            running = true,
            blank = false,
            parentSessionId = "s-parent",
            origin = "subagent",
            cwd = "F:\\bot",
            title = "app开发 main",
            lastPromptAt = 1_791_875_600_000L,
            lastModel = "deepseek-v4.1-flash",
        )
        val model = CacheMappers.toModel(CacheMappers.toEntity("own", summary))

        assertEquals(summary, model)
    }

    @Test
    fun `agentAvailable 为 false 是常态且被原样保留`() {
        // 实测 47 条里 40 条是 false，UI 绝不能把它当错误；缓存也不该把它归一到 true。
        val entity = CacheMappers.toEntity("own", SessionSummary(sessionId = "s", agentAvailable = false))
        assertTrue(!CacheMappers.toModel(entity).agentAvailable)
    }

    @Test
    fun `可空字段为 null 时往返仍是 null`() {
        val model = CacheMappers.toModel(CacheMappers.toEntity("own", SessionSummary(sessionId = "s")))

        assertNull(model.parentSessionId)
        assertNull(model.origin)
        assertNull(model.cwd)
        assertNull(model.lastPromptAt)
        assertNull(model.lastModel)
    }

    // ------------------------------------------------------------------ messages

    private fun userEnvelope(seq: Long, text: String, time: Long = 1_700_000_000_000L): JsonObject =
        buildJsonObject {
            put("type", "user/message")
            put("seq", seq)
            put("time", time)
            put(
                "data",
                buildJsonObject {
                    // `source.kind` 必须是 'user'：SessionEventParser 只渲染这个 kind
                    // （R012 的来源过滤），少了它 decodeEnvelope 会返回 null。
                    put("source", buildJsonObject { put("kind", "user") })
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                },
            )
        }

    @Test
    fun `消息行带上信封的 seq 与 time`() {
        val row = CacheMappers.toRow("own", "s-1", seq = 42L, envelope = userEnvelope(42L, "你好"))

        assertEquals("own", row.ownerId)
        assertEquals("s-1", row.sessionId)
        assertEquals(42L, row.seq)
        assertEquals(1_700_000_000_000L, row.time)
    }

    @Test
    fun `信封原文按 round-trip 还原成可渲染条目`() {
        val row = CacheMappers.toRow("own", "s-1", seq = 7L, envelope = userEnvelope(7L, "缓存里的消息"))

        val item = CacheMappers.decodeEnvelope(row.envelopeJson, parser)

        assertTrue("应当解析成 User 条目，实际 $item", item is ChatItem.User)
        assertEquals("缓存里的消息", (item as ChatItem.User).text)
        assertEquals(7L, item.seq)
    }

    @Test
    fun `存的是原文而不是解析结果`() {
        // 解析规则只应该有一份实现（SessionEventParser）：存解析结果就等于复制一份规则。
        val envelope = userEnvelope(7L, "原文")
        val row = CacheMappers.toRow("own", "s-1", seq = 7L, envelope = envelope)
        val decoded = Json.parseToJsonElement(row.envelopeJson) as JsonObject

        assertEquals(envelope["type"], decoded["type"])
        assertEquals(envelope["seq"], decoded["seq"])
        assertEquals(envelope["data"], decoded["data"])
    }

    @Test
    fun `ISO 字符串形态的 time 也能解析`() {
        // 同一个服务端上 workspace/follow 给 ISO 字符串、session/list 给数字（M1/M2 探针），
        // 直接 toLong() 会静默变 0。
        val envelope = buildJsonObject {
            put("type", "user/message")
            put("seq", 1)
            put("time", "2026-10-12T15:52:26.030Z")
            put("data", buildJsonObject { })
        }
        val row = CacheMappers.toRow("own", "s-1", seq = 1L, envelope = envelope)

        assertTrue("ISO 时间应当被解析成正数，实际 ${row.time}", row.time > 0L)
    }

    @Test
    fun `坏掉的信封返回 null 而不是抛异常`() {
        // 缓存是尽力而为的数据：一条坏行不该让整个会话打不开。
        assertNull(CacheMappers.decodeEnvelope("{不是 JSON", parser))
    }

    @Test
    fun `认不出的事件类型返回 null`() {
        val envelope = buildJsonObject {
            put("type", "some/future/event")
            put("seq", 1)
            put("time", 1)
            put("data", buildJsonObject { })
        }
        assertNull(CacheMappers.decodeEnvelope(envelope.toString(), parser))
    }

    @Test
    fun `seq 缺失的事件不能被缓存`() {
        // DshCache.appendEvents 用 seqOf 过滤，缺失 seq 的行会直接丢掉——这里钉住
        // 「信封里没有 seq」确实是可判定的。
        val envelope = buildJsonObject { put("type", "user/message"); put("time", 1) }
        assertNull(envelope["seq"])
    }

    // ------------------------------------------------------------------ meta 辅助

    @Test
    fun `集合编解码是 round-trip`() {
        val values = setOf("s-2", "s-1", "s-3")
        assertEquals(values, CacheMappers.decodeSet(CacheMappers.encodeSet(values)))
    }

    @Test
    fun `集合编码是稳定排序的`() {
        // 稳定排序让同一集合的写入字节相同，便于核对与去重。
        assertEquals(CacheMappers.encodeSet(setOf("b", "a")), CacheMappers.encodeSet(setOf("a", "b")))
    }

    @Test
    fun `空集合与 null 都解析成空集合`() {
        assertTrue(CacheMappers.decodeSet(CacheMappers.encodeSet(emptySet())).isEmpty())
        assertTrue(CacheMappers.decodeSet(null).isEmpty())
        assertTrue(CacheMappers.decodeSet("[]").isEmpty())
    }

    @Test
    fun `集合里有非字符串元素时只保留字符串`() {
        assertEquals(setOf("a"), CacheMappers.decodeSet("""["a", 1, null, {"x":1}]"""))
    }

    @Test
    fun `meta 行的键值被原样保留`() {
        val row = CacheMappers.meta("own", CacheKeys.ARCHIVED_SESSIONS, """["s-1"]""")

        assertEquals("own", row.ownerId)
        assertEquals(CacheKeys.ARCHIVED_SESSIONS, row.key)
    }

    @Test
    fun `空信封是空对象`() {
        assertTrue(CacheMappers.emptyEnvelope().isEmpty())
    }
}
