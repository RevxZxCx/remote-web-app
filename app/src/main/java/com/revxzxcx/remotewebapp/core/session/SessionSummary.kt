package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.DshTime

/**
 * `session/list` 返回的一条会话摘要。
 *
 * 依据（`@deepseek-ai/dsh-api-session-controller/lib/typert.host.js` 的 `SessionSummary` 声明
 * + M2 真机探针实测）：
 * ```
 * { agentAvailable: boolean, sessionId, updatedAt: number, running: boolean, blank: boolean,
 *   parentSessionId?: SessionId, origin?: 'subagent', cwd?: string, projections?: SessionProjectionHints }
 * ```
 *
 * 三个**实测得到、与直觉不同**的点（M2 探针，见 `测试记录.md` T009）：
 *
 * 1. `updatedAt` 在 `session/list` 里是**数字**（epoch 毫秒），而 `workspace/follow` 的
 *    `createdAt`/`updatedAt` 是 **ISO-8601 字符串**。同一个服务端、两套形态，所以这里统一走
 *    [DshTime.epochMillis] 而不是直接读数字。
 * 2. `agentAvailable == false` **不是错误**：它只表示「当前没有活跃 Agent 附着在这个会话上」
 *    （`lib/types/list.js:92` `this.ctx.agents.get(session.id)?.session === session`，
 *    `:131` 冷会话恒为 false）。实测 47 条里 40 条是 false——绝大多数历史会话都这样，
 *    所以**绝不能**把它当成「不可用」在 UI 上报错。
 * 3. `session/list` **包含子代理会话**（`origin == 'subagent'`，实测 20/47），而
 *    `workspace.sessionIds` 只列顶层会话。要按工作区分组时必须用后者筛，不能直接把
 *    `session/list` 的结果全塞进去。
 */
data class SessionSummary(
    val sessionId: String,
    /** epoch 毫秒；服务端给的是数字，但解析走 [DshTime] 以兼容字符串形态。 */
    val updatedAt: Long = 0L,
    /** 是否有活跃 Agent 附着。false 是常态，不是错误。 */
    val agentAvailable: Boolean = false,
    /** 是否正在跑（`lib/types/list.js:93` `status === 'running'`）。 */
    val running: Boolean = false,
    /** 是否还没有任何对话内容（新建未用）。 */
    val blank: Boolean = false,
    /** 子代理会话才有：父会话 id。 */
    val parentSessionId: String? = null,
    /** `'subagent'` 表示这是一条子代理会话。 */
    val origin: String? = null,
    /** 会话的工作目录；服务端对无 cwd 的持久化会话会直接跳过（`list.js:116-117`）。 */
    val cwd: String? = null,
    /** `projections.values.title`；实测 47/47 都有该字段。 */
    val title: String = "",
    /** `projections.values.sessionListMetadata.lastPromptAt`，可为 null。 */
    val lastPromptAt: Long? = null,
    /** `projections.values.modelSelection.lastUsed?.model`，仅用于副标题展示。 */
    val lastModel: String? = null,
) {
    /** 是不是子代理会话。子代理不参与工作区列表（`workspace.sessionIds` 里没有它们）。 */
    val isSubagent: Boolean get() = origin == SUBAGENT_ORIGIN

    /**
     * 列表里显示的名字。
     *
     * 没有标题时给一个**稳定**的兜底，而不是空串——否则同一屏会出现多行空白无法区分。
     * 用会话 id 的后 8 位（`session-xxxxxxxx-…` 的前缀部分对每条都一样，没有区分度）。
     */
    val displayName: String
        get() = title.ifBlank { "未命名会话 ${sessionId.takeLast(8)}" }

    companion object {
        const val SUBAGENT_ORIGIN: String = "subagent"
    }
}

/**
 * 会话列表的一次完整结果。
 *
 * `session/list` 实测**不返回分页游标**：带 `cursor` 传进去仍然返回全部 47 条，
 * `limit` 也被忽略（见 T009）。所以这里只保留 `items`，不做分页建模——等将来服务端真的
 * 支持游标再加，避免现在写一个永远不会被触发的分支。
 */
data class SessionListResult(
    val items: List<SessionSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 顶层会话（排除子代理），按最近活动降序——服务端已排好，这里再兜一次底。 */
    val topLevel: List<SessionSummary>
        get() = items.filterNot { it.isSubagent }.sortedByDescending { it.updatedAt }
}
