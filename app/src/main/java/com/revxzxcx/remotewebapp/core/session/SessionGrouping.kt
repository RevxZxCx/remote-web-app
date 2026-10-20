package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot

/**
 * 把一个工作区 + 全部会话摘要，整理成侧栏要显示的「会话分组」。
 *
 * 为什么要单独一层而不是直接在 Composable 里算：
 *
 * 1. **两个数据源要合并**：`workspace/follow` 给 `sessionIds`（顶层会话、顺序由服务端定），
 *    `session/list` 给全部会话摘要（含子代理）。实测两者**不是包含关系**——
 *    `session/list` 有 47 条，其中 20 条 `origin == 'subagent'` 且**不在任何**
 *    `workspace.sessionIds` 里（T009）。所以不能只遍历 `session/list` 按 cwd 猜分组，
 *    必须以 `workspace.sessionIds` 为准。
 * 2. **纯函数好测**：排序、归档/置顶过滤、缺失摘要兜底这些边界都要有单测，
 *    放在 Composable 里就没法测。
 */
object SessionGrouping {

    /**
     * 会话在侧栏里的归类。
     *
     * 服务端把 `archivedSessionIds` / `pinnedSessionIds` 作为**跨工作区的全局集合**下发
     * （`workspace/follow` 的 baseline 与 archived/pinned 帧），所以归档/置顶是会话的属性，
     * 不是工作区的属性——同一工作区里可以既有归档又有置顶。
     */
    enum class Bucket {
        /** 置顶（`pinnedSessionIds`）。 */
        PINNED,

        /** 普通。 */
        RECENT,

        /** 已归档（`archivedSessionIds`）。 */
        ARCHIVED,
    }

    /**
     * 一条会话 + 它在当前快照下的归类。
     *
     * 为什么把 [bucket] 算好放进来而不是让 UI 自己查集合：UI 每帧都要判断，而
     * `archivedSessionIds` / `pinnedSessionIds` 是 `Set`，在 `LazyColumn` 的 item 里
     * 反复查集合既啰嗦又容易漏（例如某个分支忘了排除归档）。在这里算一次，UI 只读字段。
     */
    data class SessionRow(
        val summary: SessionSummary,
        val bucket: Bucket,
    ) {
        val sessionId: String get() = summary.sessionId
        val isArchived: Boolean get() = bucket == Bucket.ARCHIVED
        val isPinned: Boolean get() = bucket == Bucket.PINNED
    }

    /**
     * 一个工作区及其会话。
     *
     * @param rows 按 `sessionIds` 顺序解析出的会话；**解析不到的 id 会被丢掉**——
     *   实测不会发生（5 个工作区 27 个 id 全部解析成功），但服务端可能在会话被删后
     *   仍把 id 留在 `sessionIds` 里，丢一个总比显示一行空白好。
     * @param missingIds 解析不到的 id，保留给调试页用（M4）。
     */
    data class WorkspaceGroup(
        val workspace: Workspace,
        val rows: List<SessionRow>,
        val missingIds: List<String> = emptyList(),
    ) {
        /** 非归档会话数——侧栏标题上显示这个比总数更有意义。 */
        val activeCount: Int get() = rows.count { it.isArchived.not() }

        /** 归档会话数，用于「已归档 N」的折叠项。 */
        val archivedCount: Int get() = rows.count { it.isArchived }

        /** 有会话正在跑，用于在标题上显示活动指示。 */
        val hasRunning: Boolean get() = rows.any { it.summary.running }

        /** 置顶会话（跨工作区集合的下发顺序不保证，这里按活动时间降序）。 */
        val pinnedRows: List<SessionRow>
            get() = rows.filter { it.isPinned }.sortedByDescending { it.summary.updatedAt }
    }

    /**
     * 把 [snapshot] 的每个工作区与 [sessions] 合并。
     *
     * 输出顺序与 `snapshot.items` 一致（服务端用 `workspace/order` 帧维护它）。
     * 每个工作区内的会话按 `updatedAt` 降序——实测 `workspace.sessionIds` 的顺序
     * **不是**按时间排的（T009：`bot` 工作区是 07:15, 04:28, 16:57, 07:13），
     * 所以必须自己排，不能依赖服务端顺序。
     */
    fun group(
        snapshot: WorkspaceSnapshot,
        sessions: SessionListResult,
    ): List<WorkspaceGroup> {
        val byId = sessions.items.associateBy { it.sessionId }
        return snapshot.items.map { workspace ->
            val rows = mutableListOf<SessionRow>()
            val missing = mutableListOf<String>()
            // 服务端理论上不该在同一个 sessionIds 里重复同一个 id，但真机数据不可控；
            // 重复的行会让 LazyColumn 的 key 冲突直接抛异常，所以在源头去重。
            for (id in workspace.sessionIds.distinct()) {
                val summary = byId[id]
                when {
                    summary == null -> missing += id
                    // 空会话（建了但一句话没发过）直接不列：它没有任何内容可看，桌面端侧栏
                    // 也不显示。**不能记进 `missing`**——那不是「读不到」，而是「不该显示」，
                    // 记进去会让工作区标题挂一个红色的「读不到 N」。
                    summary.blank -> Unit
                    else -> rows += SessionRow(summary, bucketOf(id, snapshot))
                }
            }
            WorkspaceGroup(
                workspace = workspace,
                rows = rows.sortedByDescending { it.summary.updatedAt },
                missingIds = missing,
            )
        }
    }

    /**
     * 判断一条会话属于哪个归类。
     *
     * 置顶优先于归档：同时被置顶和归档时按置顶处理（服务端允许这种组合，且用户显式置顶
     * 的意图比归档更强）。
     */
    fun bucketOf(sessionId: String, snapshot: WorkspaceSnapshot): Bucket = when {
        sessionId in snapshot.pinnedSessionIds -> Bucket.PINNED
        sessionId in snapshot.archivedSessionIds -> Bucket.ARCHIVED
        else -> Bucket.RECENT
    }

    /**
     * 全部工作区里所有顶层会话，按最近活动降序——抽屉里「最近会话」用的就是它。
     *
     * 只取 `snapshot.items` 里出现过的会话（即顶层会话），**不含子代理**：子代理会话不在
     * 任何 `sessionIds` 里，用户也没法从工作区进入它们。
     *
     * **排除置顶**：置顶会话在抽屉里有独立的一段，若同时出现在「最近」里，用户会看到
     * 同一条会话两次（R003）。
     *
     * **排除空会话**（`blank`）：建了但一句话没发过的会话没有内容可看，桌面端侧栏也不显示
     * （R038，见 [SessionListResult.visible]）。它虽然会出现在 `workspace.sessionIds` 里，
     * 但把它列成一行「未命名会话」只会让用户以为是坏数据。
     */
    fun recentSessions(
        snapshot: WorkspaceSnapshot,
        sessions: SessionListResult,
        includeArchived: Boolean = false,
    ): List<SessionSummary> {
        val topLevelIds = snapshot.items.flatMap { it.sessionIds }.toSet()
        return sessions.items
            .filter { it.sessionId in topLevelIds }
            .filterNot { it.blank }
            .filter { it.sessionId !in snapshot.pinnedSessionIds }
            .filter { includeArchived || it.sessionId !in snapshot.archivedSessionIds }
            .distinctBy { it.sessionId }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * 全局置顶会话，跨工作区。
     *
     * 置顶集合是全局的，所以一条置顶会话可能属于任何工作区；UI 上需要显示它来自哪里，
     * 因此返回「会话 + 所属工作区」的配对（找不到归属时 workspace 为 null——
     * 会话被移出工作区但仍在置顶集合里的边界情况）。
     *
     * 空会话照旧排除（R038）：置顶一条空会话在语义上没意义，桌面端也不会让用户看到它。
     */
    fun pinnedSessions(
        snapshot: WorkspaceSnapshot,
        sessions: SessionListResult,
    ): List<Pair<SessionSummary, Workspace?>> {
        val byId = sessions.items.associateBy { it.sessionId }
        return snapshot.pinnedSessionIds
            .mapNotNull { id -> byId[id]?.takeIf { it.blank.not() }?.let { it to snapshot.workspaceOf(id) } }
            .sortedByDescending { it.first.updatedAt }
    }
}
