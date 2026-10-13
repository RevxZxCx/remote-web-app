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

/** [CachedMeta.key] 的取值。 */
object CacheKeys {
    const val ARCHIVED_SESSIONS: String = "archivedSessionIds"
    const val PINNED_SESSIONS: String = "pinnedSessionIds"
    const val SESSIONS_CACHED_AT: String = "sessionsCachedAt"
    const val WORKSPACES_CACHED_AT: String = "workspacesCachedAt"
}
