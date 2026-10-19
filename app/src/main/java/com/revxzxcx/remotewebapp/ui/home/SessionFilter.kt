package com.revxzxcx.remotewebapp.ui.home

import com.revxzxcx.remotewebapp.core.session.SessionListResult
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot

/**
 * 抽屉搜索命中的一条会话。
 *
 * 与 [com.revxzxcx.remotewebapp.core.session.SessionGrouping.SessionRow] 分开定义：
 * 那个是「按工作区分组」的产物，带着 bucket；搜索结果是跨工作区的平铺列表，需要
 * 自带归属工作区（用来显示副标题）与归档标记（用来在结果里区分）。
 */
internal data class SearchHit(
    val summary: SessionSummary,
    val workspace: Workspace?,
    val archived: Boolean,
    val pinned: Boolean,
)

/** 抽屉搜索一次最多列多少条。见 [searchSessions] 的 KDoc。 */
internal const val SEARCH_RESULT_LIMIT = 50

/**
 * 在本地已有的会话摘要里按关键词过滤（R036）。
 *
 * **为什么不调 `session/search`**：实测这个部署的会话全文索引是关着的——
 * 桌面端 `cordis.patch.yml` 里 session-query 的 `openAt` 是 `never`，调用会直接返回
 * `gateway/internal: session search failed: SessionQueryError: session search is disabled:
 * this deployment configures the session-query index with openAt "never"`。asar 里那段
 * 注释也写明了：`openAt: never` 时 `ctx.sessionQuery` 仍然挂着，但**每次搜索都失败**，
 * SQLite 永远不打开；桌面端 Web 侧边栏的搜索也只是匹配标题与工作区名。
 *
 * 所以这里做与桌面端**同一件事**：匹配标题、工作区名、模型名。好处是零延迟、
 * 离线（缓存数据）可用、不引入一个已知必然失败的 RPC。代价是它搜不到消息正文——
 * 这一点在界面上要说清楚（见 `DrawerHead` 的搜索框占位文案）。
 *
 * 匹配规则：
 * - 大小写不敏感（`ignoreCase = true`）；中文没有大小写，这条只为英文会话名服务；
 * - 参与匹配的字段：会话显示名（`displayName`，标题为空时是「未命名会话 + id 尾号」）、
 *   归属工作区的显示名、`lastModel`；
 * - 会话列表**可能含重复条目**（`session/list` 的解析不去重），这里按 `sessionId` 去重，
 *   否则同一个会话会在结果里出现两次、`LazyColumn` 还会撞 key；
 * - 命中太多时截断到 [SEARCH_RESULT_LIMIT] 条：抽屉里滚动几十屏对定位没有帮助，
 *   继续输入更窄的词才是用户该做的事。
 *
 * 排序：按 `updatedAt` 降序（最近活动优先），不按匹配位置——用户找的通常是「我刚用过
 * 的那个」，而不是「标题里关键词出现得最早的那个」。
 */
internal fun searchSessions(
    snapshot: WorkspaceSnapshot,
    sessions: SessionListResult,
    query: String,
): List<SearchHit> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptyList()
    val archivedIds = snapshot.archivedSessionIds
    val pinnedIds = snapshot.pinnedSessionIds
    return sessions.items
        .asSequence()
        .filter { it.sessionId.isNotBlank() }
        // 必须排除子代理会话：`session/list` 实测 125 条里有 73 条是
        // `origin == "subagent"`（本机跑过大量审查/探针子会话），而抽屉的三段
        // （置顶 / 最近 / 按工作区）都以 `topLevel` 或工作区 `sessionIds` 为骨架、
        // 天然不含它们。不过滤的话搜索会给出「列表里根本没有的行」——用户点进去
        // 打开的是一个子代理会话，且他从列表里无论如何也找不到它，只会认为是 bug。
        // 与 `SessionGrouping.recentSessions` 的 `topLevel` 口径保持一致。
        .filterNot { it.isSubagent }
        .distinctBy { it.sessionId }
        .map { summary ->
            SearchHit(
                summary = summary,
                workspace = snapshot.workspaceOf(summary.sessionId),
                archived = summary.sessionId in archivedIds,
                pinned = summary.sessionId in pinnedIds,
            )
        }
        .filter { hit -> matches(hit, keyword) }
        .sortedByDescending { it.summary.updatedAt }
        .take(SEARCH_RESULT_LIMIT)
        .toList()
}

private fun matches(hit: SearchHit, keyword: String): Boolean {
    fun String.hit() = contains(keyword, ignoreCase = true)
    if (hit.summary.displayName.hit()) return true
    if (hit.summary.lastModel?.hit() == true) return true
    if (hit.workspace?.displayName?.hit() == true) return true
    return false
}
