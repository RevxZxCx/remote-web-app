package com.revxzxcx.remotewebapp.core.cache

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.SessionEventParser
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 缓存各表的行数（调试页展示「缓存到底有没有东西」）。 */
data class CacheRows(
    val workspaces: Int,
    val sessions: Int,
    val messages: Int,
)

/** 一份从缓存读回来的会话历史。 */
data class CachedChat(
    /** 按 seq 升序、已解析成可渲染条目的历史。 */
    val items: List<ChatItem> = emptyList(),
    /** 缓存里的最大 seq（`null` 表示空）。用作翻页游标的兜底。 */
    val maxSeq: Long? = null,
    /** 缓存里的最小 seq（`null` 表示空）。加载更早历史时用它做 `beforeSeq`。 */
    val minSeq: Long? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 离线缓存的统一入口（需求 FR-4.3）。
 *
 * 这一层是 UI 与 Room 之间的**唯一**通道，职责有三：
 * 1. 把领域模型 ↔ 实体之间的映射委托给 [CacheMappers]（纯函数，可单测）；
 * 2. 保证所有磁盘访问都在 `Dispatchers.IO`；
 * 3. 保证缓存的写入**永远不抛异常打断调用方**——见 [guard]。
 *
 * 第 3 条是刻意的：缓存是**性能优化**，不是正确性依赖。磁盘满、数据库损坏、Room 抛
 * `SQLiteException` 时，正确行为是「当作没缓存继续跑」，而不是让用户看到工作区页崩掉。
 * 唯一需要用户知道的是缓存不可用（调试页有标记），不是每次操作都弹一次错误。
 */
class DshCache private constructor(
    private val dao: CacheDao,
    private val parser: SessionEventParser = SessionEventParser(),
) {

    /**
     * 缓存写失败时的回调（调试页/日志用）；默认不做任何事。
     *
     * `@Volatile`：它由 `AppContainer` 在装配期写、由任意 IO 线程（见 [guard]）读，
     * 没有可见性保证时调试页可能永远收不到失败通知。
     */
    @Volatile
    var onFailure: ((String, Throwable) -> Unit)? = null

    // ------------------------------------------------------------------ workspaces

    /** 读回工作区快照（含跨工作区的归档/置顶集合）。 */
    suspend fun readWorkspaces(ownerId: String): WorkspaceSnapshot? = guard("readWorkspaces") {
        val rows = dao.workspaces(ownerId)
        // 与 [readSessions] 同一契约：**零工作区是合法的**（桌面端一个新工作区都没建），
        // 所以不能拿 `rows.isEmpty()` 当「没有缓存」——否则那份合法的空快照每次冷启动都被
        // 判定成 cache miss，缓存提示条会永远挂着。用写入时间戳区分。
        val cachedAt = dao.meta(ownerId, CacheKeys.WORKSPACES_CACHED_AT) ?: return@guard null
        if (cachedAt.isBlank()) return@guard null
        WorkspaceSnapshot(
            items = rows.map { CacheMappers.toModel(it) },
            archivedSessionIds = CacheMappers.decodeSet(dao.meta(ownerId, CacheKeys.ARCHIVED_SESSIONS)),
            pinnedSessionIds = CacheMappers.decodeSet(dao.meta(ownerId, CacheKeys.PINNED_SESSIONS)),
        )
    }

    /**
     * 用一份完整快照覆盖缓存的工作区行（`workspace/follow` 的 baseline / 每次 upsert 后调用）。
     *
     * 归档与置顶**不在这里写**：它们来自 `workspace/follow` 的独立 `archived` / `pinned` 帧
     * （见 [WorkspaceFrame.Archived] / [WorkspaceFrame.Pinned]），而工作区行来自 `baseline` /
     * `upsert`。两种帧到达的时机不同，一次 `writeWorkspaces` 同时写三份状态时，只要调用方
     * 手里那份 [WorkspaceSnapshot] 还没收到归档帧，就会把磁盘上正确的归档集合清空。
     * 拆成两个入口后，每种状态只由自己的帧写入。
     */
    suspend fun writeWorkspaces(ownerId: String, snapshot: WorkspaceSnapshot) = guard("writeWorkspaces") {
        writeWorkspaceRows(ownerId, snapshot.items)
    }

    /** 只写工作区行（不含归档/置顶集合）。 */
    suspend fun writeWorkspaceRows(ownerId: String, items: List<Workspace>) = guard("writeWorkspaceRows") {
        dao.replaceWorkspaces(ownerId, CacheMappers.toEntities(ownerId, items))
        dao.putMeta(CacheMappers.meta(ownerId, CacheKeys.WORKSPACES_CACHED_AT, System.currentTimeMillis().toString()))
    }

    /**
     * 只写跨工作区的归档 / 置顶集合（`workspace/follow` 的独立帧）。
     *
     * 两个集合各自独立：归档帧到达时置顶集合可能还没到（反之亦然），所以用 `null` 表示
     * 「这一帧没带这个集合」，不覆盖磁盘上的旧值。
     */
    suspend fun writeMembership(
        ownerId: String,
        archivedSessionIds: Set<String>? = null,
        pinnedSessionIds: Set<String>? = null,
    ) = guard("writeMembership") {
        if (archivedSessionIds != null) {
            dao.putMeta(
                CacheMappers.meta(ownerId, CacheKeys.ARCHIVED_SESSIONS, CacheMappers.encodeSet(archivedSessionIds)),
            )
        }
        if (pinnedSessionIds != null) {
            dao.putMeta(
                CacheMappers.meta(ownerId, CacheKeys.PINNED_SESSIONS, CacheMappers.encodeSet(pinnedSessionIds)),
            )
        }
    }

    // ------------------------------------------------------------------ sessions

    /** 读回会话列表；没有缓存时返回 `null`（区别于「缓存里就是空的」）。 */
    suspend fun readSessions(ownerId: String): List<SessionSummary>? = guard("readSessions") {
        val rows = dao.sessions(ownerId)
        // 会话列表为空数组是**合法**的（桌面端一个新工作区都没建），所以不能用
        // `rows.isEmpty()` 当「没有缓存」——用写入时间戳区分。
        val cachedAt = dao.meta(ownerId, CacheKeys.SESSIONS_CACHED_AT) ?: return@guard null
        if (cachedAt.isBlank()) return@guard null
        rows.map { CacheMappers.toModel(it) }
    }

    /** `session/list` 成功后整体覆盖（它是权威全量，实测无游标）。 */
    suspend fun writeSessions(ownerId: String, sessions: List<SessionSummary>) = guard("writeSessions") {
        dao.replaceSessions(ownerId, sessions.map { CacheMappers.toEntity(ownerId, it) })
        // 会话被删除时连带清掉它的消息：表间没有外键（Room 的外键要在整套实体上声明级联，
        // 而 `messages` 的 ownerId/sessionId 与 `sessions` 不是同一主键组合），没有这一步
        // 孤儿消息会永久留库，`trimMessages` 的「消息数有界」也就不成立了。
        dao.deleteOrphanMessages(ownerId)
        dao.putMeta(CacheMappers.meta(ownerId, CacheKeys.SESSIONS_CACHED_AT, System.currentTimeMillis().toString()))
    }

    /**
     * 单条会话摘要的就地覆盖（`api-session/added` / `api-session/status` 的落盘）。
     *
     * ## 为什么必须与 [writeSessions] 分开（审查 P1）
     *
     * [writeSessions] 是**权威全量**语义：先 `deleteSessions` 再整表插入，随后
     * `deleteOrphanMessages` / `deleteOrphanDrafts` 把所有「会话表里已经没有」的消息与
     * 草稿一起删掉。拿它写增量是灾难——冷启动时 `session/list`（2.6s）还没回来，
     * 桌面端恰好新建了一个会话，此刻内存里只有 1 条，整表替换后**其余全部会话的
     * 缓存消息与全部草稿会被当成孤儿清掉**，而且不可恢复。
     *
     * 这里只做逐行 upsert，不删任何东西、也不碰 `SESSIONS_CACHED_AT`
     * （那个时间戳是 [readSessions] 判断「有没有一份完整快照」的依据，
     * 增量不能把半份列表伪装成完整快照）。
     */
    suspend fun upsertSession(ownerId: String, session: SessionSummary) = guard("upsertSession") {
        dao.putSessions(listOf(CacheMappers.toEntity(ownerId, session)))
    }

    /** 会话被销毁（`api-session/removed`）时从缓存里摘掉；连带清它的消息与草稿。 */
    suspend fun deleteSession(ownerId: String, sessionId: String) = guard("deleteSession") {
        dao.deleteSession(ownerId, sessionId)
        dao.deleteOrphanMessages(ownerId)
        dao.deleteOrphanDrafts(ownerId)
    }

    // ------------------------------------------------------------------ drafts

    /**
     * 读回某会话上次没发出去的草稿（用户 m17042 第 2 条）。
     *
     * `null` 与空串在这里**合并成 `null`**：调用方只关心「要不要往输入框里放东西」，
     * 而空白草稿本来就不入库（见 [writeDraft]）。区分「没有草稿」与「草稿是空串」
     * 对 UI 没有任何用，只会多一条判空路径。
     */
    suspend fun readDraft(ownerId: String, sessionId: String): String? = guard("readDraft") {
        dao.draft(ownerId, sessionId)?.takeIf { it.isNotBlank() }
    }

    /**
     * 保存输入框草稿。
     *
     * 三条规则，每条都对应一个具体的坏结果：
     * 1. **空白草稿删除行**而不是存空串——否则用户清空输入框后，库里留着一行
     *    `text = ""`，`readDraft` 还得再判一次空，且草稿表会随「打过字又删掉」的会话增长；
     * 2. **超长截断到 [DRAFT_MAX_CHARS]**——草稿表没有「保留最近 N 条」的裁剪机制（它
     *    每个会话只有一行，本来不会增长），但单行长度不受限。粘贴一整篇文档进输入框
     *    再退出，会把这个上限之外的内容永久写进明文 SQLite。截断比「整条丢弃」好：
     *    用户回来还能看到开头，知道他曾经想写什么；
     * 3. **写失败不报错**——继承 [guard] 的约定，缓存是优化不是正确性依赖。
     */
    suspend fun writeDraft(ownerId: String, sessionId: String, text: String) = guard("writeDraft") {
        if (sessionId.isBlank()) return@guard Unit
        val trimmed = text.take(DRAFT_MAX_CHARS)
        if (trimmed.isBlank()) {
            dao.deleteDraft(ownerId, sessionId)
        } else {
            dao.putDraft(
                CachedDraft(
                    ownerId = ownerId,
                    sessionId = sessionId,
                    text = trimmed,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 删掉某会话的草稿（消息真的发出去了 / 用户主动清空）。
     *
     * 与 [writeDraft] 传空串等价，单独开一个入口是因为调用点的意图不同：
     * 「发送成功要清掉」不该读起来像「写一个空草稿」。
     */
    suspend fun clearDraft(ownerId: String, sessionId: String) = guard("clearDraft") {
        dao.deleteDraft(ownerId, sessionId)
    }

    // ------------------------------------------------------------------ messages

    /** 读回某会话的缓存历史。 */
    suspend fun readChat(ownerId: String, sessionId: String): CachedChat = guard("readChat") {
        val rows = dao.messages(ownerId, sessionId)
        if (rows.isEmpty()) return@guard CachedChat()
        val items = rows
            .mapNotNull { CacheMappers.decodeEnvelope(it.envelopeJson, parser) }
            // 坏行被上面滤掉后必须重新去重排序：items 是 LazyColumn 的 key 来源，
            // 重复 key 会直接抛 IllegalArgumentException（R003 P1-1 同类问题）。
            .distinctBy { it.seq }
            .sortedBy { it.seq }
        // 游标取自**解析成功的条目**而不是磁盘上的 rows：解析不出来的行（版本升级后语义变了、
        // 信封被截断）如果参与 min/max，会给出一个客户端根本渲染不出的边界 seq——把它当
        // `beforeSeq` 请求下一页时，服务端会从那条前面开始返回，而那些事件又解析不出来，
        // 表现为「翻页翻不动」。
        CachedChat(
            items = items,
            maxSeq = items.maxOfOrNull { it.seq },
            minSeq = items.minOfOrNull { it.seq },
        )
    }.let { it ?: CachedChat() }

    /**
     * 追加（或覆盖）若干事件信封。
     *
     * 收 [envelopes] 而不是领域模型：原文才是唯一无损的形态（见 [CachedMessage]）。
     *
     * [prepended] 区分**追新**与**向上翻页补历史**：只有追新才从尾部裁剪。向上翻页插进来的
     * 是**更早**的行，如果在同一次调用里按「保留最新 600 条」裁剪，刚写进来的旧行会立刻被
     * 自己裁掉——用户翻上去还是空的，而且 `minSeq` 不变会让同一页被反复请求。
     */
    suspend fun appendEvents(
        ownerId: String,
        sessionId: String,
        envelopes: List<JsonObject>,
        prepended: Boolean = false,
    ) = guard("appendEvents") {
        if (envelopes.isEmpty()) return@guard Unit
        val rows = envelopes.mapNotNull { envelope ->
            val seq = seqOf(envelope) ?: return@mapNotNull null
            // 单条坏行不该拖垮整批：`toRow` 里的时间解析对畸形信封是会抛的
            // （`JsonNull` 也是 `JsonPrimitive`，其 `content` 是字符串 "null"）。
            runCatching { CacheMappers.toRow(ownerId, sessionId, seq, envelope) }.getOrNull()
        }
        if (rows.isEmpty()) return@guard Unit
        dao.putMessages(rows)
        if (!prepended) dao.trimMessages(ownerId, sessionId, MESSAGE_CACHE_LIMIT)
    }

    /** 去掉某会话的缓存（会话被删除时用；本期没有删除入口，留作完整性）。 */
    suspend fun clearChat(ownerId: String, sessionId: String) = guard("clearChat") {
        dao.deleteMessages(ownerId, sessionId)
    }

    // ------------------------------------------------------------------ 维护

    /**
     * 当前分区的行数（调试页用）。
     *
     * 返回 `null` 表示读库失败——与「行数为 0」是**不同**的事实：0 是「缓存是空的」，
     * `null` 是「缓存读不出来」。调试页必须能区分这两者，否则会把坏掉的缓存显示成「正常，只是空」。
     */
    suspend fun countRows(ownerId: String): CacheRows? = guard("countRows") {
        CacheRows(
            workspaces = dao.countWorkspaces(ownerId),
            sessions = dao.countSessions(ownerId),
            messages = dao.countMessages(ownerId),
        )
    }

    /**
     * 首次拿到凭证时调用：清掉别的设备留下的行。
     *
     * 不这样做的话，表会随配对次数无界增长（虽然那些行永远查不到）。
     */
    suspend fun pruneOtherOwners(ownerId: String) = guard("pruneOtherOwners") {
        dao.deleteOtherOwners(ownerId)
    }

    /**
     * 缓存不可用时的兜底：把 Room 抛出的异常折成「没有缓存」。
     *
     * 同时承担第二个职责：**把所有磁盘访问放到 `Dispatchers.IO`**。DAO 的
     * `allowMainThreadQueries` 是关着的，所以这既是避免 ANR 的要求，也是避免
     * `IllegalStateException: Cannot access database on the main thread` 的必要条件。
     * 把 `withContext` 放在这里而不是每个方法里，是为了让「忘记切线程」这件事在结构上不可能发生。
     *
     * 故障分支里两处细节都是刻意的（R006）：
     * - `ensureActive()`：`Dispatcher` 被取消与 Room 抛异常**同时**发生时，上面那个
     *   `CancellationException` 分支接不到（抛出来的是 `SQLiteException` 之类），此时
     *   直接返回 null 会把取消状态吞掉——调用方的 `finally` 会延后到下一个挂起点，可能
     *   已经在被销毁的界面上发状态。`ensureActive()` 在取消时抛 `CancellationException`，
     *   让取消语义完整。
     * - `runCatching { onFailure?.invoke(...) }` 且**放在 `withContext(IO)` 之外**：
     *   回调是调试/日志用途，它自己抛异常绝不能穿透 [guard] 破坏「缓存故障不崩 UI」这条
     *   唯一保证。放在 IO 之外是因为回调整理 UI 状态时应当回到调用方的线程语义（旧实现
     *   把它留在 IO 线程上，与调用方对线程的假设不符）。
     */
    private suspend fun <T> guard(operation: String, block: suspend () -> T): T? =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (cause: kotlinx.coroutines.CancellationException) {
            // 取消必须原样上抛：吞掉会让 ViewModel 的 finally 逻辑错乱（R003 P1-2 教训）。
            throw cause
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            runCatching { onFailure?.invoke(operation, cause) }
            null
        }

    companion object {

        /**
         * 每个会话保留的最新事件条数。
         *
         * 为什么是 600：一次 `session/follow` 的窗口约 50 条消息，一条消息平均带 2–3 条事件
         * （同组的 `tool/call`、`step/end` 等），600 条大约等于 8–10 屏滚动历史——覆盖
         * 「冷启动看一眼上文」的真实需求，又让单会话缓存稳定在几百 KB 量级。
         * 更早的历史在用户真的往上翻时走 `session/page`（那时一定有网）。
         */
        const val MESSAGE_CACHE_LIMIT: Int = 600

        /**
         * 单条草稿的字符上限。
         *
         * 4000 是「一屏输入框放不下、但一次认真写下的任务描述一定装得下」的量级：
         * 输入框最大 160dp 高（约 6 行），4000 字远超它能显示的内容，够用户回来接着写；
         * 而粘贴整篇几十页文档再退出，不会被永久写进明文 SQLite。
         */
        const val DRAFT_MAX_CHARS: Int = 4_000

        /** 用已经打开的数据库建一个缓存门面。 */
        fun create(dao: CacheDao): DshCache = DshCache(dao)

        /** 从凭证算出分区键；UI 层不该关心细节，所以这里再暴露一次。 */
        fun ownerIdOf(credential: DeviceCredential): String = CacheMappers.ownerIdOf(credential)
    }
}

/**
 * 事件信封的 `seq`；缺失或非法时返回 null（这条不缓存）。
 *
 * 同时接受数字与数字字符串：`time` 字段在实测中既有 ISO-8601 字符串也有纯数字两种形态
 * （见 `DshTime`），`seq` 同样由服务端序列化决定。只认一种的话，服务端换一种写法就会让
 * **整条缓存静默变空**——而缓存变空是看不出来的（只是慢），不像崩溃那样容易被发现。
 * 非数字内容仍然拒绝：它确实不是可用的 seq。
 */
private fun seqOf(envelope: JsonObject): Long? =
    (envelope["seq"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content
        ?.toLongOrNull()
