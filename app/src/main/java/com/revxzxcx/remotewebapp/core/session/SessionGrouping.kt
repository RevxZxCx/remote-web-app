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

        /**
         * 这个工作区里最强的「未读提醒」状态，用于工作区**折叠时**在标题右侧亮一个点。
         *
         * ## 为什么需要它（R052，用户 m37463 第 2 条）
         *
         * 用户原话：「当一个会话完成了或者状态有更新，会话的右边会显示圆点，但是当会话
         * 所在工作区折叠的时候，就不能正常看到了。我希望当会话所在工作区被折叠的时候，
         * 工作区的右边也会展示圆点，提醒用户查看。」折叠把子行整块收进
         * `AnimatedVisibility`，子行的点跟着看不见，提醒就失效了。
         *
         * ## 为什么取「最强的一个」而不是数个数
         *
         * 与子行共用同一套 [statusOf] 判据，只是把多行折叠成一个。数个数会引出
         * 「3 个未读里 1 个是失败」这种说不清的颜色，而用户需要知道的只是「这个壳里有东西
         * 要看」。优先级由 [unreadRank] 单点定义，与子行的视觉/读屏语义保持一致。
         *
         * ## 为什么排除归档行
         *
         * 归档是用户主动收起来的东西，它自己那行的点也只在展开归档视图时才看得到，
         * 不该让一个折叠的工作区长期为它亮着。
         *
         * ## 为什么「运行中」不参与聚合
         *
         * 工作区标题右侧已经有均衡器动画表示「这里在跑」（`hasRunning`）。把
         * [SessionStatus.Ongoing] 也算进聚合，会出现「标题右边一个动画 + 右边再一个点」
         * 的重复表达；更糟的是它会**盖住**真该提醒的状态——一个工作区里同时有「正在跑」
         * 和「上一轮失败没人看」时，用户需要看到的是那个红点。
         */
        val unreadStatus: SessionStatus
            get() = strongestUnread()?.let { statusOf(it.summary) } ?: SessionStatus.Idle

        /**
         * 聚合出来的那个状态若为 [SessionStatus.Failed]，它对应子行的失败原因；否则空串。
         *
         * 为什么需要它：折叠态的工作区圆点如果只传状态、不传原因，读屏只会念
         * 「上一轮失败，点进去看」，而展开后同一个状态会念出服务端给的具体原因——
         * 折叠反而让提示**信息更少**，用户还得展开才知道哪一条坏了。这里把原因一起
         * 带出去，折叠态与展开态的读屏信息就一致了。——R052 代码审查 P2-2。
         */
        val unreadFailureReason: String
            get() = strongestUnread()?.summary?.failureReason.orEmpty()

        /**
         * 该显示在工作区行上的那一条会话，没有则 null。
         *
         * 优先级由 [unreadRank] 单点定义，**必须与 [statusOf] 同序**：折叠态显示的点
         * 与展开后子行显示的点必须是同一个结论，否则会出现「折叠看是绿点、展开看是红点」
         * 的矛盾。这也是为什么没有把 `Failed` 提到 `Attention` 之前——`Attention` 在
         * [statusOf] 里本来就高于 `Failed`（agent 挂起等你回答时这一轮并没结束，
         * 那条判据由 R049 用户 m32994 的状态需求定下），聚合若自定一套顺序，
         * 两个视图就会各说各话。
         *
         * 同优先级时取第一命中：`rows` 已按 `updatedAt` 降序，即最活跃的那条。
         */
        private fun strongestUnread(): SessionRow? {
            var best: SessionRow? = null
            for (row in rows) {
                if (row.isArchived) continue
                val status = statusOf(row.summary)
                // 「运行中」不参与聚合：工作区标题右侧本来就有均衡器动画在表示
                // 这件事（`hasRunning`），再给圆点一次等于同一件事说两遍，而且
                // 动画是 16dp 的方框、圆点是 8dp 的圆，两者挨在一起会显得凌乱。
                if (status == SessionStatus.Ongoing) continue
                if (best == null || unreadRank(status) > unreadRank(statusOf(best.summary))) best = row
            }
            return best
        }
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
