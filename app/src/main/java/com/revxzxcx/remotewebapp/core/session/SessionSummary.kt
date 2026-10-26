package com.revxzxcx.remotewebapp.core.session

import android.os.Parcelable
import com.revxzxcx.remotewebapp.core.protocol.DshTime
import kotlinx.parcelize.Parcelize

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
 *
 * 实现 [Parcelable] 的唯一原因是 `AppRoot` 的导航状态要 `rememberSaveable`：旋转屏幕或
 * 进程被回收后重建时，正在看的聊天页应当原样恢复，而不是退回工作区页（M5-2）。
 * 这里没有别的 Android 依赖——`SessionListParser` / `SessionGrouping` 仍是纯 Kotlin。
 */
@Parcelize
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
    /**
     * `projections.values.userQuestions.active` 非空，或 `inbox` 的 `next-turn` / `next-step`
     * 非空 —— 即「有东西在等人」，R049 用来画琥珀状态点。
     *
     * 两个都收在同一个布尔里而不是分开两个：列表行只有一个位置放状态点，
     * 而「等回答」与「有排队」对用户的含义相同（这个会话需要我看一眼）。
     */
    val awaitingAttention: Boolean = false,
    /**
     * 「这个会话刚跑完，用户还没来看」的提醒标记（R049，用户 m32994 + m33325 纠正）。
     *
     * **不是 `session/list` 的字段**，也**不是「跑完了」的同义词**：官方 README 记着
     * 「"New task completed" unread marking is local viewing state — completion-time >
     * last-seen never reaches the host」，服务端两边都不给。
     *
     * 由 `HomeViewModel` 经 `CompletionLedger` 从事件流边沿写入：跑起来 → 灭；
     * 跑完（上一刻在跑）且用户没在看它 → 亮；用户进会话看过 → 灭。进程重启即失效。
     * 判据详见 [CompletionLedger] 的类 KDoc。
     */
    val completionUnread: Boolean = false,

    /**
     * 「这个会话上一轮跑挂了，用户还没来看」的提醒标记（R049）。
     *
     * 与 [completionUnread] 同构、同来源、同生命周期：也**不是** `session/list` 的字段，
     * 也**不是「历史上失败过」**，而是一次性提醒。由 `CompletionLedger` 从
     * `api-session/error` 写入：失败 → 亮；用户进会话看过 / 它重新跑起来 → 灭。
     */
    val failureUnread: Boolean = false,

    /**
     * 失败提醒的原因文本（`api-session/error` 的第二项，服务端拼好的 user-safe 描述）。
     *
     * 只在 [failureUnread] 为真时有意义，且只给读屏与将来的悬停提示用：列表行不展示长文本。
     * 空串表示这次失败没带原因——红点照样该亮，文案只是附加信息。
     */
    val failureReason: String = "",
) : Parcelable {
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
    /**
     * 逐会话的用量切片（T076），键是会话 id。
     *
     * 与 [items] 是**同一份响应的两个视角**：`session/list` 的每条本来就带
     * `tokenUsage` / `costUsage` / `sessionStats` / `contextTimeline` 四个投影
     * （实测 32 条主会话 32/32 命中），所以首页洞察不需要任何新增 RPC。
     *
     * ## 为什么在这里而不是塞进 [SessionSummary]
     * `SessionSummary` 是 `@Parcelable`，会被放进 `rememberSaveable` 的导航状态与缓存表
     * （`CachedSession`）。用量是**几百字节一个会话**的大块数据，跟着列表项走会让每次
     * 会话状态更新（`running` 翻转、完成点亮起）都连带搬运一遍；而它只在打开洞察抽屉时
     * 才被读。分开放还有第二个好处：`CachedSession` 不含它，冷启动时这份映射是空的，
     * 界面显示的就不是「上次的旧账」而是等网络帧——洞察本来就是实时视图。
     *
     * 缺失某一项表示「这条会话没有洞察数据」，**不是「用量为 0」**。
     */
    val usages: Map<String, SessionUsage> = emptyMap(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 列表界面可以显示的那些会话（R038）。
     *
     * 排除两类：
     *
     * 1. **子代理会话**（[SessionSummary.isSubagent]）——它们不在任何
     *    `workspace.sessionIds` 里，用户从列表点进去也无处可回（T009：47 条里 20 条）。
     * 2. **空会话**（[SessionSummary.blank]）——`session/list` 会返回「建了但一句话没发过」
     *    的会话：`sessionStats.turns == 0`、`turnOutline` 为空、`lastPromptAt == null`、
     *    `title == null`。R038 实测本机 125 条里有 14 条是这种。
     *
     *    它们**不是**用户的对话：桌面端点「新建会话」会立刻建一个空会话，关掉那个页签后
     *    它从侧栏消失，但记录仍在磁盘上（`sessionListMetadata.blank = true` 就是为这件事
     *    留的持久化标记，schema 描述原文是「Whether the folded prefix contains no turn」）。
     *    桌面端侧栏与 Web UI 都不列它们——客户端 bundle 里 `session.blank` 只出现在
     *    `headerBlank` / `hero` / `conversationPhase` 这类「当前这个会话是空的」渲染分支，
     *    没有一处把它放进列表。
     *
     *    App 之前没用这个字段，于是把这些会话全渲染成「未命名会话 xxxxxxxx」。用户的观感
     *    正是「一堆我在桌面端根本找不到、点进去也什么都没有的对话」。
     *
     * 这里**不改变 [items]**：按 id 查单条会话（打开聊天页、`session/follow` 的摘要）仍要
     * 能从全量里找到。过滤只发生在「列表该显示什么」这一层。
     */
    val visible: List<SessionSummary>
        get() = items.filterNot { it.isSubagent || it.blank }

    /** 列表可显示的会话，按最近活动降序——服务端已排好，这里再兜一次底。 */
    val topLevel: List<SessionSummary>
        get() = visible.sortedByDescending { it.updatedAt }

    /**
     * 可显示且**有洞察数据**的会话 + 它们的用量，供 [ContextTotalsCalculator] 使用。
     *
     * 与 [visible] 分开是因为两者语义不同：[visible] 是「列表里该显示的」，
     * 这里是「能进洞察统计的」。差集是刚建好还没产生用量的会话——它们在列表里该有一行，
     * 但塞进总额度分母就会让「共 N 个会话」与「有数据的 N' 个」对不上。
     */
    val visibleWithUsage: List<SessionSummary>
        get() = visible.filter { usages.containsKey(it.sessionId) }
}
