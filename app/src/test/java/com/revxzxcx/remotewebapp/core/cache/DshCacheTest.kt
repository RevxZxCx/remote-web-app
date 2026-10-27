package com.revxzxcx.remotewebapp.core.cache

import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DshCache] 的单测。
 *
 * 用**手写假 DAO** 而不是 Room：`CacheDao` 是接口，Room 只在编译期生成实现，所以界面契约
 * 可以直接在 JVM 上替身（决策 D21：不引入 Robolectric）。这样能测的是本层真正负责的三件事：
 * 1. 领域模型 ↔ 实体的映射有没有丢字段（委托给 [CacheMappers]）；
 * 2. 「没有缓存」与「缓存里是空的」能不能区分；
 * 3. 磁盘故障时是不是**当作没缓存继续跑**，而不是把异常抛给 UI（本层最重要的契约）。
 */
class DshCacheTest {

    /**
     * 内存假 DAO。
     *
     * [failWith] 非 null 时所有操作抛该异常，用来验证 [DshCache.guard] 的兜底。
     */
    private class FakeDao : CacheDao {
        val workspaces = mutableListOf<CachedWorkspace>()
        val sessions = mutableListOf<CachedSession>()
        val messages = mutableListOf<CachedMessage>()
        val meta = mutableMapOf<String, String>()

        var failWith: Exception? = null

        private fun check() {
            failWith?.let { throw it }
        }

        override suspend fun workspaces(ownerId: String): List<CachedWorkspace> {
            check(); return workspaces.filter { it.ownerId == ownerId }.sortedBy { it.orderIndex }
        }

        override suspend fun putWorkspaces(rows: List<CachedWorkspace>) {
            check(); workspaces += rows
        }

        override suspend fun deleteWorkspaces(ownerId: String) {
            check(); workspaces.removeAll { it.ownerId == ownerId }
        }

        override suspend fun sessions(ownerId: String): List<CachedSession> {
            check(); return sessions.filter { it.ownerId == ownerId }.sortedByDescending { it.updatedAt }
        }

        override suspend fun putSessions(rows: List<CachedSession>) {
            check()
            // 必须与真实 DAO 的 `@Insert(onConflict = REPLACE)` 同语义：同主键覆盖。
            // 早先只写 `sessions += rows`，会让「upsert 覆盖旧行」这类用例变成
            // 「假 DAO 允许重复」，测试自己骗自己（本轮变异验证抓到的）。
            rows.forEach { row ->
                sessions.removeAll { it.ownerId == row.ownerId && it.sessionId == row.sessionId }
                sessions += row
            }
        }

        override suspend fun deleteSessions(ownerId: String) {
            check(); sessions.removeAll { it.ownerId == ownerId }
        }

        override suspend fun deleteSession(ownerId: String, sessionId: String) {
            check(); sessions.removeAll { it.ownerId == ownerId && it.sessionId == sessionId }
        }

        override suspend fun minSeq(ownerId: String, sessionId: String): Long? {
            check(); return messages.filter { it.ownerId == ownerId && it.sessionId == sessionId }.minOfOrNull { it.seq }
        }

        override suspend fun messages(ownerId: String, sessionId: String): List<CachedMessage> {
            check()
            return messages.filter { it.ownerId == ownerId && it.sessionId == sessionId }.sortedBy { it.seq }
        }

        override suspend fun putMessages(rows: List<CachedMessage>) {
            check()
            rows.forEach { row ->
                messages.removeAll { it.ownerId == row.ownerId && it.sessionId == row.sessionId && it.seq == row.seq }
                messages += row
            }
        }

        override suspend fun trimMessages(ownerId: String, sessionId: String, keep: Int) {
            check()
            val forSession = messages.filter { it.ownerId == ownerId && it.sessionId == sessionId }
                .sortedByDescending { it.seq }
            val doomed = forSession.drop(keep).map { it.seq }.toSet()
            messages.removeAll { it.ownerId == ownerId && it.sessionId == sessionId && it.seq in doomed }
        }

        override suspend fun deleteMessages(ownerId: String, sessionId: String) {
            check(); messages.removeAll { it.ownerId == ownerId && it.sessionId == sessionId }
        }

        /**
         * 对应 SQL 的 `DELETE FROM messages WHERE NOT EXISTS (SELECT 1 FROM sessions …)`：
         * 只保留在 `sessions` 表里还有对应行的消息。
         */
        override suspend fun deleteOrphanMessages(ownerId: String) {
            check()
            val alive = sessions.map { it.sessionId }.toSet()
            messages.removeAll { it.ownerId == ownerId && it.sessionId !in alive }
        }

        // ------------------------------------------------------------ drafts

        val drafts = mutableMapOf<String, CachedDraft>()

        override suspend fun draft(ownerId: String, sessionId: String): String? {
            check(); return drafts["$ownerId/$sessionId"]?.text
        }

        override suspend fun putDraft(row: CachedDraft) {
            check(); drafts["${row.ownerId}/${row.sessionId}"] = row
        }

        override suspend fun deleteDraft(ownerId: String, sessionId: String) {
            check(); drafts.remove("$ownerId/$sessionId")
        }

        override suspend fun deleteOrphanDrafts(ownerId: String) {
            check()
            val alive = sessions.filter { it.ownerId == ownerId }.map { it.sessionId }.toSet()
            drafts.keys.removeAll { key ->
                key.startsWith("$ownerId/") && key.removePrefix("$ownerId/") !in alive
            }
        }

        override suspend fun deleteOtherDrafts(ownerId: String) {
            check(); drafts.keys.removeAll { !it.startsWith("$ownerId/") }
        }

        override suspend fun meta(ownerId: String, key: String): String? {
            check(); return meta["$ownerId/$key"]
        }

        override suspend fun putMeta(row: CachedMeta) {
            check(); meta["${row.ownerId}/${row.key}"] = row.value
        }

        override suspend fun countWorkspaces(ownerId: String): Int {
            check(); return workspaces.count { it.ownerId == ownerId }
        }

        override suspend fun countSessions(ownerId: String): Int {
            check(); return sessions.count { it.ownerId == ownerId }
        }

        override suspend fun countMessages(ownerId: String): Int {
            check(); return messages.count { it.ownerId == ownerId }
        }

        // R054：删除**指定**分区（别的分区留着）。与上面的 deleteOther* 方向相反。
        override suspend fun deleteOwnerWorkspaces(ownerId: String) {
            check(); workspaces.removeAll { it.ownerId == ownerId }
        }

        override suspend fun deleteOwnerSessions(ownerId: String) {
            check(); sessions.removeAll { it.ownerId == ownerId }
        }

        override suspend fun deleteOwnerMessages(ownerId: String) {
            check(); messages.removeAll { it.ownerId == ownerId }
        }

        override suspend fun deleteOwnerDrafts(ownerId: String) {
            check(); drafts.keys.removeAll { it.startsWith("$ownerId/") }
        }

        override suspend fun deleteOwnerMeta(ownerId: String) {
            check(); meta.keys.removeAll { it.startsWith("$ownerId/") }
        }

        override suspend fun deleteOtherWorkspaces(ownerId: String) {
            check(); workspaces.removeAll { it.ownerId != ownerId }
        }

        override suspend fun deleteOtherSessions(ownerId: String) {
            check(); sessions.removeAll { it.ownerId != ownerId }
        }

        override suspend fun deleteOtherMessages(ownerId: String) {
            check(); messages.removeAll { it.ownerId != ownerId }
        }

        override suspend fun deleteOtherMeta(ownerId: String) {
            check(); meta.keys.removeAll { !it.startsWith("$ownerId/") }
        }
    }

    private fun envelope(seq: Long, text: String): JsonObject =
        buildJsonObject {
            put("type", "user/message")
            put("seq", seq)
            put("time", 1_700_000_000_000L + seq)
            put(
                "data",
                buildJsonObject {
                    // `source.kind` 必须是 'user'：SessionEventParser 只渲染这个 kind
                    // （R012 的来源过滤），少了它冷启动读缓存时会少一行。
                    put("source", buildJsonObject { put("kind", "user") })
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                },
            )
        }

    // ------------------------------------------------------------------ workspaces

    @Test
    fun `没有缓存时读工作区返回 null`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        assertNull(cache.readWorkspaces("own"))
    }

    @Test
    fun `工作区写进去能读回来`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        val snapshot = WorkspaceSnapshot(
            items = listOf(Workspace(workspaceId = "a", title = "A"), Workspace(workspaceId = "b", title = "B")),
            archivedSessionIds = setOf("s-arch"),
            pinnedSessionIds = setOf("s-pin"),
        )

        cache.writeWorkspaces("own", snapshot)
        // 归档/置顶来自 `workspace/follow` 的**独立帧**，走自己的入口（R006 P2-5）：
        // 合在一次 writeWorkspaces 里时，只要调用方手里那份快照还没收到归档帧，就会把
        // 磁盘上正确的归档集合清空。
        cache.writeMembership(
            "own",
            archivedSessionIds = snapshot.archivedSessionIds,
            pinnedSessionIds = snapshot.pinnedSessionIds,
        )
        val back = cache.readWorkspaces("own")

        assertNotNull(back)
        assertEquals(listOf("a", "b"), back!!.items.map { it.workspaceId })
        assertEquals(setOf("s-arch"), back.archivedSessionIds)
        assertEquals(setOf("s-pin"), back.pinnedSessionIds)
    }

    @Test
    fun `只写工作区行不会清掉已存的归档集合`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.writeMembership("own", archivedSessionIds = setOf("s-arch"))

        // 只带工作区行的写入（baseline/upsert 帧）不该动归档集合。
        cache.writeWorkspaces("own", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "a"))))

        assertEquals(setOf("s-arch"), cache.readWorkspaces("own")!!.archivedSessionIds)
    }

    @Test
    fun `归档帧不带置顶集合时不覆盖已存的置顶`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        // 先落一行工作区，`readWorkspaces` 靠 `WORKSPACES_CACHED_AT` 区分「空快照」与
        // 「没缓存」，只写集合不写行的话读回来是 null。
        cache.writeWorkspaces("own", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "a"))))
        cache.writeMembership("own", pinnedSessionIds = setOf("s-pin"))

        // 归档帧到达时置顶集合可能还没到：null 表示「这一帧没带」，不是「清空」。
        cache.writeMembership("own", archivedSessionIds = setOf("s-arch"))

        val back = cache.readWorkspaces("own")!!
        assertEquals(setOf("s-arch"), back.archivedSessionIds)
        assertEquals(setOf("s-pin"), back.pinnedSessionIds)
    }

    @Test
    fun `工作区快照整体替换而不是累积`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.writeWorkspaces("own", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "old"))))
        cache.writeWorkspaces("own", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "new"))))

        val back = cache.readWorkspaces("own")

        // 服务端删掉的工作区不能永远留在缓存里。
        assertEquals(listOf("new"), back!!.items.map { it.workspaceId })
    }

    @Test
    fun `别的主机的行不可见`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.writeWorkspaces("other", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "x"))))

        assertNull(cache.readWorkspaces("own"))
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun `没有缓存时读会话返回 null`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        assertNull(cache.readSessions("own"))
    }

    @Test
    fun `空会话列表与没有缓存是两件事`() = runBlocking {
        // 桌面端一个新工作区都没建时 session/list 是空数组，这是**合法**的成功结果。
        // 缓存必须把它记成「缓存里有 0 条」，而不是「没有缓存」。
        val cache = DshCache.create(FakeDao())
        cache.writeSessions("own", emptyList())

        assertNotNull(cache.readSessions("own"))
        assertTrue(cache.readSessions("own")!!.isEmpty())
    }

    @Test
    fun `会话写进去能读回来`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        val sessions = listOf(
            SessionSummary(sessionId = "s-1", title = "一", updatedAt = 200L),
            SessionSummary(sessionId = "s-2", title = "二", updatedAt = 100L),
        )

        cache.writeSessions("own", sessions)
        val back = cache.readSessions("own")

        assertEquals(listOf("s-1", "s-2"), back!!.map { it.sessionId })
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun `空会话的历史是空而不是 null`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        assertTrue(cache.readChat("own", "s-1").isEmpty)
    }

    @Test
    fun `消息写进去按 seq 升序读回来`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.appendEvents("own", "s-1", listOf(envelope(5, "五"), envelope(1, "一"), envelope(3, "三")))

        val chat = cache.readChat("own", "s-1")

        assertEquals(listOf(1L, 3L, 5L), chat.items.map { it.seq })
        assertEquals(1L, chat.minSeq)
        assertEquals(5L, chat.maxSeq)
    }

    @Test
    fun `消息内容被还原成可渲染条目`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.appendEvents("own", "s-1", listOf(envelope(2, "缓存里的正文")))

        val item = cache.readChat("own", "s-1").items.single()

        assertTrue(item is ChatItem.User)
        assertEquals("缓存里的正文", (item as ChatItem.User).text)
    }

    @Test
    fun `同 seq 重复写入只留一条`() = runBlocking {
        // 断线重连会重放重叠窗口，重复插入是常态而不是异常。
        val cache = DshCache.create(FakeDao())
        cache.appendEvents("own", "s-1", listOf(envelope(1, "旧")))
        cache.appendEvents("own", "s-1", listOf(envelope(1, "新")))

        val items = cache.readChat("own", "s-1").items

        assertEquals(1, items.size)
        assertEquals("新", (items.single() as ChatItem.User).text)
    }

    @Test
    fun `缺 seq 的信封被丢掉且不影响其它行`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        val bad = buildJsonObject { put("type", "user/message"); put("time", 1) }

        cache.appendEvents("own", "s-1", listOf(bad, envelope(9, "好行")))

        assertEquals(listOf(9L), cache.readChat("own", "s-1").items.map { it.seq })
    }

    @Test
    fun `坏行的信封不影响同一会话的其它消息`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        val dao = FakeDao()
        val cache2 = DshCache.create(dao)
        dao.messages += CachedMessage("own", "s-1", 1L, 1L, "{坏 JSON")
        cache2.appendEvents("own", "s-1", listOf(envelope(2, "好行")))

        val chat = cache2.readChat("own", "s-1")

        // 坏行被滤掉，且 minSeq/maxSeq **只反映解析成功的条目**（R006 P3-2）：若把解析不出来
        // 的行也算进去，游标会指向一个客户端根本渲染不出的 seq——拿它当 `beforeSeq` 请求上一
        // 页时，服务端从那一条前面开始返回，而那些事件又解析不出来，表现为「翻页翻不动」。
        assertEquals(listOf(2L), chat.items.map { it.seq })
        assertEquals(2L, chat.minSeq)
        assertEquals(2L, chat.maxSeq)
    }

    @Test
    fun `超过上限的旧消息被裁掉`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        val limit = DshCache.MESSAGE_CACHE_LIMIT
        val envelopes = (1..(limit + 10L)).map { envelope(it, "第 $it 条") }

        cache.appendEvents("own", "s-1", envelopes)

        val chat = cache.readChat("own", "s-1")
        assertEquals(limit, chat.items.size)
        // 保留的是尾部：最新一条一定在，最旧一条一定不在。
        assertEquals(limit + 10L, chat.maxSeq)
        assertEquals(11L, chat.minSeq)
    }

    @Test
    fun `向上翻页补进来的更早消息不会被同一次调用裁掉`() = runBlocking {
        // 只有**追新**才从尾部裁剪。向上翻页插进来的是**更早**的行，如果在同一次调用里按
        // 「保留最新 600 条」裁剪，刚写进去的旧行会立刻被自己裁掉——用户翻上去还是空的，
        // 而 `minSeq` 不变会让同一页被反复请求（R006 P2-7）。
        val cache = DshCache.create(FakeDao())
        val limit = DshCache.MESSAGE_CACHE_LIMIT

        // 先把窗口填满到上限。
        cache.appendEvents("own", "s-1", (1..(limit + 10L)).map { envelope(it, "第 $it 条") })
        // 再补一页更早的（seq 远小于当前 minSeq）。
        cache.appendEvents("own", "s-1", (1..10L).map { envelope(it, "补第 $it 条") }, prepended = true)

        val chat = cache.readChat("own", "s-1")
        // 补进来的 10 条一条都不能少。
        assertEquals(1L, chat.minSeq)
        assertTrue(chat.items.map { it.seq }.containsAll((1L..10L).toList()))
        // 上限在**下一次追新**时才重新生效——prepend 这一次故意不裁剪，否则刚补进来的
        // 旧行会被同一次调用自己裁掉。
        cache.appendEvents("own", "s-1", listOf(envelope(limit + 20L, "更新的")))
        val afterTrim = cache.readChat("own", "s-1")
        assertTrue(
            "追新后总数应回到上限内，实际 ${afterTrim.items.size}",
            afterTrim.items.size <= limit,
        )
    }

    @Test
    fun `清除会话历史只影响该会话`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.appendEvents("own", "s-1", listOf(envelope(1, "一")))
        cache.appendEvents("own", "s-2", listOf(envelope(1, "二")))

        cache.clearChat("own", "s-1")

        assertTrue(cache.readChat("own", "s-1").isEmpty)
        assertEquals(1, cache.readChat("own", "s-2").items.size)
    }

    @Test
    fun `空信封列表不做任何事`() = runBlocking {
        val dao = FakeDao()
        val cache = DshCache.create(dao)

        cache.appendEvents("own", "s-1", emptyList())

        assertTrue(dao.messages.isEmpty())
    }

    // ------------------------------------------------------------------ 维护

    @Test
    fun `行数统计按分区`() = runBlocking {
        val cache = DshCache.create(FakeDao())
        cache.writeWorkspaces("own", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "a"))))
        cache.writeSessions("own", listOf(SessionSummary(sessionId = "s-1")))
        cache.appendEvents("own", "s-1", listOf(envelope(1, "一"), envelope(2, "二")))
        cache.writeWorkspaces("other", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "b"))))

        val rows = cache.countRows("own")

        assertEquals(CacheRows(workspaces = 1, sessions = 1, messages = 2), rows)
    }

    @Test
    fun `清掉指定分区且不动别的主机`() = runBlocking {
        // R054 语义变更：旧 `pruneOtherOwners` 是「除了这台其他都删」，只在
        // 「一台手机连一台桌面端」时成立；多主机后它会让切到 B 顺手清掉 A 的缓存，
        // 切回来要重拉 session 列表（单次实测 1.78 MB）。
        // 现在按 ownerId 精确认领：只清用户真正移除的那一台。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeWorkspaces("gone", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "x"))))
        cache.writeSessions("gone", listOf(SessionSummary(sessionId = "s-gone")))
        cache.appendEvents("gone", "s-gone", listOf(envelope(1, "旧")))
        cache.writeWorkspaces("kept", WorkspaceSnapshot(items = listOf(Workspace(workspaceId = "y"))))
        cache.writeSessions("kept", listOf(SessionSummary(sessionId = "s-kept")))
        cache.appendEvents("kept", "s-kept", listOf(envelope(1, "留")))

        cache.pruneOwner("gone")

        assertEquals("被移除那台的工作区要清", 0, dao.workspaces.count { it.ownerId == "gone" })
        assertEquals("被移除那台的会话要清", 0, dao.sessions.count { it.ownerId == "gone" })
        assertEquals("被移除那台的消息要清", 0, dao.messages.count { it.ownerId == "gone" })
        // 关键断言：另一台主机的缓存一行都不能少。
        assertEquals(1, dao.workspaces.count { it.ownerId == "kept" })
        assertEquals(1, dao.sessions.count { it.ownerId == "kept" })
        assertEquals(1, dao.messages.count { it.ownerId == "kept" })
    }

    // ------------------------------------------------------------------ 故障兜底

    @Test
    fun `读库失败时返回没有缓存而不是抛异常`() = runBlocking {
        val dao = FakeDao().apply { failWith = RuntimeException("数据库损坏") }
        val cache = DshCache.create(dao)
        var reported: String? = null

        cache.onFailure = { operation, _ -> reported = operation }

        // 缓存是性能优化而不是正确性依赖：磁盘坏掉时正确行为是「当作没缓存继续跑」。
        assertNull(cache.readWorkspaces("own"))
        assertNull(cache.readSessions("own"))
        assertTrue(cache.readChat("own", "s-1").isEmpty)
        assertNull(cache.countRows("own"))
        assertEquals("countRows", reported)
    }

    @Test
    fun `写库失败时不抛异常`() = runBlocking {
        val dao = FakeDao().apply { failWith = RuntimeException("磁盘已满") }
        val cache = DshCache.create(dao)

        // 缓存写失败的代价只是「下次冷启动没有缓存」，绝不能把异常抛给 UI 线程。
        cache.writeWorkspaces("own", WorkspaceSnapshot())
        cache.writeSessions("own", listOf(SessionSummary(sessionId = "s")))
        cache.appendEvents("own", "s", listOf(envelope(1, "一")))
        cache.clearChat("own", "s")
        cache.pruneOwner("own")

        // 走到这里就说明四次写都没抛；再确认库确实是空的（没有半写状态被读成有效缓存）。
        assertTrue(dao.workspaces.isEmpty())
        assertNull(cache.readWorkspaces("own"))
    }

    @Test
    fun `行数读不出来是 null 而不是 0`() = runBlocking {
        // 调试页必须能区分「缓存是空的」与「缓存读不出来」，否则会把坏掉的缓存显示成正常。
        val dao = FakeDao().apply { failWith = RuntimeException("打不开") }
        assertNull(DshCache.create(dao).countRows("own"))
    }

    @Test
    fun `取消不会被兜底吞掉`() = runBlocking {
        // R003 P1-2 的教训：吞掉 CancellationException 会让调用方的 finally/loading 标志错乱。
        // 这里让 DAO 直接抛取消异常，验证 guard 必须原样上抛、且**不**报告为缓存故障。
        val dao = FakeDao().apply {
            failWith = kotlinx.coroutines.CancellationException("被取消")
        }
        val cache = DshCache.create(dao)
        var reported = false
        cache.onFailure = { _, _ -> reported = true }

        val thrown = try {
            cache.readWorkspaces("own")
            null
        } catch (cause: kotlinx.coroutines.CancellationException) {
            cause
        }

        assertNotNull("CancellationException 必须上抛而不是折成 null", thrown)
        assertTrue("取消不该被报告成缓存故障", !reported)
    }

    // ------------------------------------------------------------------ drafts

    @Test
    fun `草稿写进去能原样读回来`() = runBlocking {
        val cache = DshCache.create(FakeDao())

        cache.writeDraft("own", "s-1", "帮我改一下首页的标题栏")

        assertEquals("帮我改一下首页的标题栏", cache.readDraft("own", "s-1"))
    }

    @Test
    fun `没有草稿时读到 null`() = runBlocking {
        assertNull(DshCache.create(FakeDao()).readDraft("own", "s-1"))
    }

    @Test
    fun `两张会话的草稿互不干扰`() = runBlocking {
        // 用户 m17042 第 2 条的原话是「退出会话草稿就丢」——按会话分开存是这个功能的
        // 全部意义：如果共用一行，切一个有内容的会话就会把另一个的草稿顶掉。
        val cache = DshCache.create(FakeDao())

        cache.writeDraft("own", "s-1", "第一段")
        cache.writeDraft("own", "s-2", "第二段")

        assertEquals("第一段", cache.readDraft("own", "s-1"))
        assertEquals("第二段", cache.readDraft("own", "s-2"))
    }

    @Test
    fun `不同设备分区的草稿互相看不见`() = runBlocking {
        val dao = FakeDao()
        val cache = DshCache.create(dao)

        cache.writeDraft("own-a", "s-1", "旧设备的草稿")

        assertNull(cache.readDraft("own-b", "s-1"))
    }

    @Test
    fun `清空输入框会把草稿行删掉而不是留一个空串`() = runBlocking {
        // 留空串的话，`readDraft` 还得再判一次空白，且草稿表会随「打过字又删掉」的会话增长。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeDraft("own", "s-1", "写了一半")

        cache.writeDraft("own", "s-1", "   ")

        assertNull(cache.readDraft("own", "s-1"))
        assertTrue("空白草稿不该在库里留行", dao.drafts.isEmpty())
    }

    @Test
    fun `超长草稿被截断到上限`() = runBlocking {
        // 粘贴一整篇文档再退出，不该把任意长度的明文永久写进 SQLite。
        val cache = DshCache.create(FakeDao())
        val huge = "字".repeat(DshCache.DRAFT_MAX_CHARS + 500)

        cache.writeDraft("own", "s-1", huge)

        val back = cache.readDraft("own", "s-1")
        assertEquals(DshCache.DRAFT_MAX_CHARS, back?.length)
    }

    @Test
    fun `恰好等于上限的草稿不被截断`() = runBlocking {
        // 边界：截断发生在 `take(limit)`，等于上限时必须是完整保留。
        val cache = DshCache.create(FakeDao())
        val exact = "a".repeat(DshCache.DRAFT_MAX_CHARS)

        cache.writeDraft("own", "s-1", exact)

        assertEquals(DshCache.DRAFT_MAX_CHARS, cache.readDraft("own", "s-1")?.length)
    }

    @Test
    fun `会话列表替换后孤儿草稿被清掉`() = runBlocking {
        // 会话被删掉后草稿不能永久留库——与 `messages` 的孤儿清理同一条路径。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeSessions("own", listOf(SessionSummary(sessionId = "s-1", updatedAt = 1L)))
        cache.writeDraft("own", "s-1", "会话还在时的草稿")
        cache.writeDraft("own", "s-2", "会话已经不在了")

        // `s-2` 从未出现在会话表里；一次权威替换之后它的草稿必须消失。
        cache.writeSessions("own", listOf(SessionSummary(sessionId = "s-1", updatedAt = 2L)))

        assertNull("孤儿草稿必须被清掉", cache.readDraft("own", "s-2"))
        assertEquals("活着的会话草稿必须保留", "会话还在时的草稿", cache.readDraft("own", "s-1"))
    }

    @Test
    fun `移除主机时只清它自己的草稿`() = runBlocking {
        // 同上级用例：草稿也要按 ownerId 精确认领，别的主机的草稿一行不能少。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeDraft("own-gone", "s-1", "要移除那台的草稿")
        cache.writeDraft("own-kept", "s-1", "留着那台的草稿")

        cache.pruneOwner("own-gone")

        assertTrue("被移除那台的草稿要删", dao.drafts.keys.none { it.startsWith("own-gone/") })
        assertEquals("别的主机的草稿必须留着", "留着那台的草稿", cache.readDraft("own-kept", "s-1"))
    }

    @Test
    fun `草稿读不出来时当作没有草稿`() = runBlocking {
        // 磁盘故障不该让聊天页崩：草稿是优化，不是正确性依赖。
        val dao = FakeDao().apply { failWith = RuntimeException("磁盘满了") }
        assertNull(DshCache.create(dao).readDraft("own", "s-1"))
    }

    @Test
    fun `写草稿失败不抛异常`() = runBlocking {
        val dao = FakeDao().apply { failWith = RuntimeException("磁盘满了") }
        // 只要求不抛：`guard` 的契约就是缓存故障静默。
        // 末尾显式 `assertNull` 而不是裸调用：`guard` 的返回类型是 `Unit?`，
        // 让 lambda 以它结尾会让 `runBlocking` 推断出 `Unit?`，JUnit4 直接判
        // 「should be void」并把整个类标成 initializationError（一条都跑不起来）。
        assertNull(DshCache.create(dao).writeDraft("own", "s-1", "内容"))
    }

    @Test
    fun `空 sessionId 不写草稿`() = runBlocking {
        // 理论入口只会有真实 sessionId，但空串会把所有「无主草稿」写进同一行，
        // 之后任何一个拿不到 sessionId 的路径都会读到别人的内容。
        val dao = FakeDao()
        DshCache.create(dao).writeDraft("own", "", "内容")

        assertTrue(dao.drafts.isEmpty())
    }

    // ------------------------------------------------ 增量写入（审查 P1 的回归）

    @Test
    fun `upsertSession 只写这一条，不删其它会话的草稿与缓存`() = runBlocking {
        // 这是审查 P1-2 的直接回归：早先增量走的是 `writeSessions`（整表替换 + 清孤儿），
        // 冷启动早期会把「还没拉回来的会话」的消息与草稿当成孤儿删光。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeSessions("own", listOf(session("a"), session("b")))
        cache.writeDraft("own", "a", "a 的草稿")
        cache.writeDraft("own", "b", "b 的草稿")

        cache.upsertSession("own", session("c"))

        assertEquals(setOf("a", "b", "c"), dao.sessions.map { it.sessionId }.toSet())
        assertEquals("a 的草稿", cache.readDraft("own", "a"))
        assertEquals("b 的草稿", cache.readDraft("own", "b"))
    }

    @Test
    fun `upsertSession 会覆盖同 id 的旧行`() = runBlocking {
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeSessions("own", listOf(session("a", title = "旧标题")))
        cache.upsertSession("own", session("a", title = "新标题"))

        assertEquals(1, dao.sessions.count { it.sessionId == "a" })
        assertEquals("新标题", dao.sessions.first { it.sessionId == "a" }.title)
    }

    @Test
    fun `upsertSession 不写会话列表的时间戳`() = runBlocking {
        // `SESSIONS_CACHED_AT` 是 `readSessions` 判断「有没有一份完整快照」的依据。
        // 增量把它写掉，半份列表就会被当成完整快照喂给首页。
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.upsertSession("own", session("a"))

        assertNull(cache.readSessions("own"))
    }

    @Test
    fun `deleteSession 只摘这一条并清掉它的草稿`() = runBlocking {
        val dao = FakeDao()
        val cache = DshCache.create(dao)
        cache.writeSessions("own", listOf(session("a"), session("b")))
        cache.writeDraft("own", "a", "a 的草稿")
        cache.writeDraft("own", "b", "b 的草稿")

        cache.deleteSession("own", "a")

        assertEquals(listOf("b"), dao.sessions.map { it.sessionId })
        assertNull(cache.readDraft("own", "a"))
        assertEquals("b 的草稿", cache.readDraft("own", "b"))
    }

    private fun session(id: String, title: String = "会话 $id") = SessionSummary(
        sessionId = id,
        title = title,
        updatedAt = 1_000L,
        agentAvailable = true,
        running = false,
        blank = false,
        parentSessionId = null,
        origin = null,
        cwd = null,
        lastPromptAt = null,
        lastModel = null,
    )
}
