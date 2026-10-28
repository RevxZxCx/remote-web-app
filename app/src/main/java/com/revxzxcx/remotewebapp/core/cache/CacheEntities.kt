package com.revxzxcx.remotewebapp.core.cache

import androidx.room.Entity
import androidx.room.Index

/**
 * 离线缓存的表结构（决策 D04：Room，冷启动先出缓存再对齐）。
 *
 * ## 为什么每张表的主键都带 `ownerId`
 *
 * `ownerId` 是**设备凭证的指纹**（见 [ownerIdOf]）。同一部手机可能先后配对过不同的桌面端，
 * 也可能对同一个桌面端重新配对拿到新的 `deviceId`。如果不带 `ownerId`，换一台桌面端后
 * 冷启动会先把**上一台机器的工作区/消息**画出来，然后被真实数据覆盖——用户会看到短暂但
 * 明确的错误内容。带上 `ownerId` 后，旧数据在查询层面就不可见，不需要依赖「配对时清表」
 * 这种容易漏掉一条路径的写法。
 *
 * 代价是表会随配对次数增长，因此 [DshCache] 在启动时会删掉非当前 owner 的行（见
 * `CacheDaos.deleteOtherOwners`）。
 *
 * ## 为什么消息存的是**事件信封原文**而不是解析好的 [com.revxzxcx.remotewebapp.core.session.ChatItem]
 *
 * 会话日志的解析规则（`tool/call` 与 `assistant/message` 里的 tool-call 块要去重、`turn/end`
 * 要变成提示行、`workspace/changes` 要变成占位卡片……）只应该有一份实现，就是
 * `SessionEventParser`。缓存如果存解析结果，就等于把这份规则复制一遍；将来服务端加字段、
 * 改语义时，两份实现必然漂移。存原文则读回来还要走同一个解析器，规则永远只有一份。
 *
 * 原文用 [CachedMessage.envelopeJson] 保存，形状与线上事件信封一致
 * （`{type,seq,time,data}`），因此 `SessionEventParser.parseEvent` 可以直接吃。
 */
@Entity(
    tableName = "workspaces",
    primaryKeys = ["ownerId", "workspaceId"],
    indices = [Index("ownerId")],
)
data class CachedWorkspace(
    val ownerId: String,
    val workspaceId: String,
    val path: String,
    val title: String,
    /** `sessionIds` 用 JSON 数组存：它只被整体替换，从不按元素查询。 */
    val sessionIdsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 服务端下发的显示顺序；`WorkspaceSnapshot.items` 本身有序，缓存必须保住它。 */
    val orderIndex: Int,
)

/**
 * 一条会话摘要（`session/list` 的一行 + 工作区分组所需的归档/置顶标记）。
 *
 * `archived` / `pinned` 属于**跨工作区的全局集合**（`workspace/follow` 的 `archived` /
 * `pinned` 帧），不是会话自己的属性，所以它们由 [CachedMeta] 单独保存，这里不重复存。
 */
@Entity(
    tableName = "sessions",
    primaryKeys = ["ownerId", "sessionId"],
    indices = [Index("ownerId"), Index("ownerId", "updatedAt")],
)
data class CachedSession(
    val ownerId: String,
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val agentAvailable: Boolean,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val cwd: String?,
    val lastPromptAt: Long?,
    val lastModel: String?,
)

/** 一条已缓存的消息/事件（原文信封）。 */
@Entity(
    tableName = "messages",
    primaryKeys = ["ownerId", "sessionId", "seq"],
    indices = [Index("ownerId", "sessionId")],
)
data class CachedMessage(
    val ownerId: String,
    val sessionId: String,
    val seq: Long,
    val time: Long,
    val envelopeJson: String,
)

/**
 * 键值表：跨工作区的全局集合与其它零碎状态。
 *
 * 目前存 `archivedSessionIds` / `pinnedSessionIds`（JSON 数组）与 `sessionListCachedAt`
 * （`session/list` 最近一次成功的时间，用于调试页与「缓存有多旧」的判断）。
 */
@Entity(tableName = "meta", primaryKeys = ["ownerId", "key"])
data class CachedMeta(
    val ownerId: String,
    val key: String,
    val value: String,
)

/**
 * 未发出的输入框草稿（用户 m17042 第 2 条：退出会话 / 关掉 App 之后不该丢）。
 *
 * ## 为什么单独一张表而不是塞进 [CachedMeta]
 *
 * `meta` 的键是自由字符串，塞 `draft:<sessionId>` 也能跑，但那样**没有任何东西能约束它的
 * 生命周期**：会话被删掉之后那条草稿会永远留在库里（`deleteOrphanMessages` 只清
 * `messages`）。单独一张表之后，「会话没了 → 草稿跟着没」可以在
 * `CacheDao.replaceSessions` 的同一个事务里用一句 SQL 表达，和其他孤儿数据的清理
 * 走同一条路径。
 *
 * ## 为什么存整段原文而不是增量
 *
 * 输入框是**整体替换**语义（Compose 的 `onValueChange` 给的是新全文），存增量既没有
 * 收益也没有可靠的落点——光标位置、选区、IME 组合态都不该被持久化（恢复一段旧选区会
 * 让用户下一次点进来时莫名其妙选中半句话）。
 */
@Entity(
    tableName = "drafts",
    primaryKeys = ["ownerId", "sessionId"],
    indices = [Index("ownerId")],
)
data class CachedDraft(
    val ownerId: String,
    val sessionId: String,
    /** 草稿全文。空白草稿不入库（见 `DshCache.writeDraft`）。 */
    val text: String,
    /** 最后写入时间，调试页用来判断「这条草稿有多旧」。 */
    val updatedAt: Long,
)

/** [CachedMeta.key] 的取值。 */
object CacheKeys {
    const val ARCHIVED_SESSIONS: String = "archivedSessionIds"
    const val PINNED_SESSIONS: String = "pinnedSessionIds"
    const val SESSIONS_CACHED_AT: String = "sessionsCachedAt"
    const val WORKSPACES_CACHED_AT: String = "workspacesCachedAt"

    /**
     * 该分区最近一次被激活的时间（毫秒）。
     *
     * M57 新增：多主机之后每个分区各占一份缓存，必须能回答「哪片最久没用」才能做 LRU
     * 淘汰。[SESSIONS_CACHED_AT] 不能代替它——那只在**成功拉到** `session/list` 时刷新，
     * 用户切过去看一眼就走（列表命中缓存、没发请求）时它不会动，于是最常看的那几台
     * 会被当成「很久没用」而先被淘汰，恰好把缓存淘汰成了最坏结果。
     */
    const val LAST_USED_AT: String = "lastUsedAt"
}
