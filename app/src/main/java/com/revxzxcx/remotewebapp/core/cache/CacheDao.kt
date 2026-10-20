package com.revxzxcx.remotewebapp.core.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 缓存的读写口（Room DAO）。
 *
 * 全部方法都是**同步**的：`RoomDatabase` 的 `allowMainThreadQueries` 保持默认（false），
 * 调用方必须自己在 IO 线程跑。本工程的约定是「DAO 只被 `DshCache` 调用，`DshCache` 的
 * 每个方法都 `withContext(Dispatchers.IO)`」——这样 UI 层永远不会意外触发主线程磁盘访问。
 */
@Dao
interface CacheDao {

    // ---------------------------------------------------------------- workspaces

    @Query("SELECT * FROM workspaces WHERE ownerId = :ownerId ORDER BY orderIndex ASC")
    suspend fun workspaces(ownerId: String): List<CachedWorkspace>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWorkspaces(rows: List<CachedWorkspace>)

    @Query("DELETE FROM workspaces WHERE ownerId = :ownerId")
    suspend fun deleteWorkspaces(ownerId: String)

    /**
     * 用一份完整快照**整体替换**工作区表。
     *
     * `workspace/follow` 的 baseline 是权威全量，逐条 upsert 会让服务端已删除的工作区永远
     * 留在缓存里（本期没有 `remove` 帧的本地应用逻辑，靠整体替换最省事也最正确）。
     */
    @Transaction
    suspend fun replaceWorkspaces(ownerId: String, rows: List<CachedWorkspace>) {
        deleteWorkspaces(ownerId)
        if (rows.isNotEmpty()) putWorkspaces(rows)
    }

    // ---------------------------------------------------------------- sessions

    @Query("SELECT * FROM sessions WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    suspend fun sessions(ownerId: String): List<CachedSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSessions(rows: List<CachedSession>)

    @Query("DELETE FROM sessions WHERE ownerId = :ownerId")
    suspend fun deleteSessions(ownerId: String)

    @Query("DELETE FROM sessions WHERE ownerId = :ownerId AND sessionId = :sessionId")
    suspend fun deleteSession(ownerId: String, sessionId: String)

    /**
     * `session/list` 是权威全量（实测无游标、无分页），所以整体替换。
     *
     * 同时清掉「会话已经不存在」的草稿：与 `messages` 一样，`drafts` 与 `sessions` 之间
     * 没有外键（主键组合不同），不在这里清就会永久留库。放在同一个 `@Transaction` 里是因为
     * 「会话列表刚被权威替换」正是唯一正确的清理时机——单独暴露只会多一条漏调的路径。
     */
    @Transaction
    suspend fun replaceSessions(ownerId: String, rows: List<CachedSession>) {
        deleteSessions(ownerId)
        if (rows.isNotEmpty()) putSessions(rows)
        deleteOrphanDrafts(ownerId)
    }

    // ---------------------------------------------------------------- messages

    /** 某会话最早缓存的 seq；没有缓存时为 `null`。用于「还有更早的历史吗」的判断。 */
    @Query("SELECT MIN(seq) FROM messages WHERE ownerId = :ownerId AND sessionId = :sessionId")
    suspend fun minSeq(ownerId: String, sessionId: String): Long?

    @Query("SELECT * FROM messages WHERE ownerId = :ownerId AND sessionId = :sessionId ORDER BY seq ASC")
    suspend fun messages(ownerId: String, sessionId: String): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessages(rows: List<CachedMessage>)

    /**
     * 裁掉某会话最旧的缓存，只保留最新的 [keep] 条。
     *
     * 为什么需要：会话日志是**只增**的，一个长跑会话可以有几万条事件；全量缓存会让启动时的
     * 读取与内存占用随时间线性增长。只留尾部 [keep] 条（默认见 `DshCache`），更早的历史在
     * 用户真的往上翻时走 `session/page` 从服务端取——那时一定有网（没网也翻不了）。
     */
    @Query(
        "DELETE FROM messages WHERE ownerId = :ownerId AND sessionId = :sessionId AND seq NOT IN " +
            "(SELECT seq FROM messages WHERE ownerId = :ownerId AND sessionId = :sessionId " +
            "ORDER BY seq DESC LIMIT :keep)",
    )
    suspend fun trimMessages(ownerId: String, sessionId: String, keep: Int)

    @Query("DELETE FROM messages WHERE ownerId = :ownerId AND sessionId = :sessionId")
    suspend fun deleteMessages(ownerId: String, sessionId: String)

    /**
     * 删掉某分区里已经没有对应会话的消息行。
     *
     * 为什么需要：`messages` 与 `sessions` 之间没有外键（两者的主键组合不同，Room 的级联
     * 要求整条主键链都在父表上声明），而 `session/list` 会整体替换 `sessions`——
     * 一个会话被删除后，它的消息行会永久留下。`trimMessages` 保证的是「单个会话有界」，
     * 挡不住「会话数 × 上限」的总量增长。
     *
     * 在 [replaceSessions] 之后调用（见 `DshCache.writeSessions`）而不是单独暴露：
     * 清理的时机就是「会话列表刚刚被权威替换」的那一刻，分开暴露只会多一条漏调的路径。
     */
    @Query(
        "DELETE FROM messages WHERE ownerId = :ownerId AND sessionId NOT IN " +
            "(SELECT sessionId FROM sessions WHERE ownerId = :ownerId)",
    )
    suspend fun deleteOrphanMessages(ownerId: String)

    // ---------------------------------------------------------------- drafts

    @Query("SELECT text FROM drafts WHERE ownerId = :ownerId AND sessionId = :sessionId")
    suspend fun draft(ownerId: String, sessionId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDraft(row: CachedDraft)

    @Query("DELETE FROM drafts WHERE ownerId = :ownerId AND sessionId = :sessionId")
    suspend fun deleteDraft(ownerId: String, sessionId: String)

    /** 同 [deleteOrphanMessages]：会话没了，草稿也该没。 */
    @Query(
        "DELETE FROM drafts WHERE ownerId = :ownerId AND sessionId NOT IN " +
            "(SELECT sessionId FROM sessions WHERE ownerId = :ownerId)",
    )
    suspend fun deleteOrphanDrafts(ownerId: String)

    @Query("DELETE FROM drafts WHERE ownerId != :ownerId")
    suspend fun deleteOtherDrafts(ownerId: String)

    // ---------------------------------------------------------------- meta

    @Query("SELECT value FROM meta WHERE ownerId = :ownerId AND key = :key")
    suspend fun meta(ownerId: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(row: CachedMeta)

    // ---------------------------------------------------------------- 调试页计数

    /**
     * 三张表的行数（当前分区）。
     *
     * 只给调试页用：`SELECT COUNT(*)` 是唯一能回答「缓存到底有没有东西」的廉价手段——
     * 缓存失效时它是 0，而不是靠 UI 上「列表空着」去猜（列表空也可能因为桌面端真没有工作区）。
     */
    @Query("SELECT COUNT(*) FROM workspaces WHERE ownerId = :ownerId")
    suspend fun countWorkspaces(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE ownerId = :ownerId")
    suspend fun countSessions(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE ownerId = :ownerId")
    suspend fun countMessages(ownerId: String): Int

    // ---------------------------------------------------------------- 换设备时清理

    @Query("DELETE FROM workspaces WHERE ownerId != :ownerId")
    suspend fun deleteOtherWorkspaces(ownerId: String)

    @Query("DELETE FROM sessions WHERE ownerId != :ownerId")
    suspend fun deleteOtherSessions(ownerId: String)

    @Query("DELETE FROM messages WHERE ownerId != :ownerId")
    suspend fun deleteOtherMessages(ownerId: String)

    @Query("DELETE FROM meta WHERE ownerId != :ownerId")
    suspend fun deleteOtherMeta(ownerId: String)

    /** 清掉所有非当前 owner 的行（配对到新桌面端时调用）。 */
    @Transaction
    suspend fun deleteOtherOwners(ownerId: String) {
        deleteOtherWorkspaces(ownerId)
        deleteOtherSessions(ownerId)
        deleteOtherMessages(ownerId)
        deleteOtherDrafts(ownerId)
        deleteOtherMeta(ownerId)
    }
}
