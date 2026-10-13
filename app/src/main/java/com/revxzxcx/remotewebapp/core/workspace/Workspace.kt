package com.revxzxcx.remotewebapp.core.workspace

import kotlinx.serialization.Serializable

/**
 * 一个工作区。
 *
 * 依据 `@deepseek-ai/dsh-api-workspace-controller/lib/typert.remote-client.js`：
 * `workspace/create` 返回 `{workspace:{workspaceId,path,title,sessionIds,createdAt,updatedAt}, created}`，
 * `workspace/follow` 的 `baseline.items` 是同一个 `WorkspaceValue`。
 *
 * 全部字段都有默认值：Typert 是严格 schema，但服务端在版本演进中可能省略可选字段，客户端
 * 不该因为少一个字段就整条流崩掉（需求文档 §7.3 第 6 条）。
 *
 * **时间字段是 ISO-8601 字符串，不是数字**（M1 真机探针实测，见 `测试记录.md` T004）：
 * 线上 `workspace/follow` 的 baseline 返回 `"createdAt":"2026-10-12T15:52:26.030Z"`，
 * 而生成类型声明里写的是 `number`。这里统一折算成 epoch 毫秒，解析失败记 0。
 */
@Serializable
data class Workspace(
    val workspaceId: String,
    val path: String = "",
    val title: String = "",
    val sessionIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 列表里优先显示 title，没有就退回路径最后一段。 */
    val displayName: String
        get() = title.ifBlank {
            path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
        }
}

/**
 * 工作区的完整快照。
 *
 * `archivedSessionIds` / `pinnedSessionIds` 是**跨工作区**的全局集合（服务端把它们放在
 * `workspace/follow` 的 baseline 与 archived/pinned 帧里，而不是每个 workspace 内）。
 */
data class WorkspaceSnapshot(
    val items: List<Workspace> = emptyList(),
    val archivedSessionIds: Set<String> = emptySet(),
    val pinnedSessionIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun workspaceOf(sessionId: String): Workspace? =
        items.firstOrNull { sessionId in it.sessionIds }
}

/**
 * `workspace/follow` 的帧。
 *
 * 服务端帧的 `type` 是判别键：`baseline` / `upsert` / `remove` / `order` / `archived` / `pinned`
 * （`typert.remote-client.js` 的 `WorkspaceFollowFrame`）。未知 `type` 会被
 * [WorkspaceReducer.reduce] 忽略而不是抛异常——这样服务端新增帧类型时 App 不会断流。
 */
sealed interface WorkspaceFrame {

    data class Baseline(val snapshot: WorkspaceSnapshot) : WorkspaceFrame

    data class Upsert(val workspace: Workspace) : WorkspaceFrame

    data class Remove(val workspaceId: String) : WorkspaceFrame

    data class Order(val workspaceIds: List<String>) : WorkspaceFrame

    data class Archived(val archivedSessionIds: List<String>) : WorkspaceFrame

    data class Pinned(val pinnedSessionIds: List<String>) : WorkspaceFrame
}

/**
 * 把帧序列折叠成快照。
 *
 * 纯函数、无副作用，便于单测覆盖每一条帧语义（M1 的验收之一）。
 */
object WorkspaceReducer {

    /** 处理一帧；[frame] 为 `null` 表示「未知帧」，原样返回 [current]。 */
    fun reduce(current: WorkspaceSnapshot, frame: WorkspaceFrame?): WorkspaceSnapshot =
        when (frame) {
            null -> current

            is WorkspaceFrame.Baseline -> frame.snapshot

            is WorkspaceFrame.Upsert -> {
                val index = current.items.indexOfFirst { it.workspaceId == frame.workspace.workspaceId }
                val items = if (index >= 0) {
                    current.items.toMutableList().also { it[index] = frame.workspace }
                } else {
                    current.items + frame.workspace
                }
                current.copy(items = items)
            }

            is WorkspaceFrame.Remove ->
                current.copy(items = current.items.filterNot { it.workspaceId == frame.workspaceId })

            is WorkspaceFrame.Order -> {
                // 按给定顺序重排；不在列表里的工作区保持原有相对顺序追加在后面，
                // 避免服务端只发部分顺序时丢条目。
                val byId = current.items.associateBy { it.workspaceId }
                val ordered = frame.workspaceIds.mapNotNull { byId[it] }
                val orderedIds = ordered.map { it.workspaceId }.toSet()
                current.copy(items = ordered + current.items.filterNot { it.workspaceId in orderedIds })
            }

            is WorkspaceFrame.Archived ->
                current.copy(archivedSessionIds = frame.archivedSessionIds.toSet())

            is WorkspaceFrame.Pinned ->
                current.copy(pinnedSessionIds = frame.pinnedSessionIds.toSet())
        }
}
