package com.revxzxcx.remotewebapp.core.session

/**
 * 会话列表的**增量**更新规则。
 *
 * ## 为什么需要
 * `session/list` 是一次性全量 unary（实测 125 条 / 1.78 MB / 2.6 s），而列表必须跟着
 * 桌面端实时变。拿全量去轮询不可行，所以增量只能来自 `$events` 转发事件里的
 * `api-session/…`（见 `RemoteEventsClient.sessionNotifications`）。
 *
 * ## 为什么单独抽成对象而不是写在 ViewModel 里
 * 这三个函数是纯函数、没有协程、没有 Android 依赖，可以直接在 JVM 单测里穷举
 * 「重复 added」「未知 id 的 status」「删不存在的 id」这些边角，而不必拉起整个
 * `HomeViewModel`（它要 `DshConnection` + 缓存，工程里没有假容器先例）。
 *
 * ## 一条硬约束：**保留列表原有的顺序语义**
 * [upsert] 对已存在的 id 只替换内容、**不改变位置**。原因是这个列表的顺序不参与 UI
 * 排序（`SessionGrouping` 会按 `updatedAt` 重新排），但它是缓存写穿的原始顺序——
 * 每次增量都挪位会让「增量更新」和「全量重读」产生不同的列表，从而在两者交替时
 * 出现肉眼可见的行跳变。
 */
internal object SessionListUpdates {

    /**
     * 插入或替换一条会话摘要。
     *
     * - 已存在：原位替换（保留位置）；
     * - 不存在：追加到末尾（排序交给 `SessionGrouping`，这里不做）。
     *
     * 与 `session/list` 的语义差：服务端返回的是 `updatedAt` 降序的全量，而这里是
     * 「增量拼上来」的顺序，所以**任何依赖顺序的地方都必须先排序**。
     */
    fun upsert(items: List<SessionSummary>, incoming: SessionSummary): List<SessionSummary> {
        val index = items.indexOfFirst { it.sessionId == incoming.sessionId }
        return if (index < 0) {
            items + incoming
        } else {
            items.toMutableList().also { it[index] = incoming }
        }
    }

    /**
     * 只改某条会话的运行态。
     *
     * `api-session/status` 只带 `(sessionId, running)`，没有完整摘要可替换，所以只能就地
     * 打补丁。找不到 id 时**原样返回**：那说明这条会话还没被 `session/list` 覆盖到
     * （冷启动时序），凭空造一条摘要会带出 `updatedAt = 0`、`title = ""` 的假行。
     */
    fun patchRunning(
        items: List<SessionSummary>,
        sessionId: String,
        running: Boolean,
    ): List<SessionSummary> {
        val index = items.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return items
        // R049：跑起来意味着用户已经让这个会话继续了，「跑完了没看」与「上一轮挂了」
        // 两条提醒都该灭——新的结果会重新决定点亮什么。
        val cleared = running && (items[index].completionUnread || items[index].failureUnread)
        if (items[index].running == running && !cleared) return items
        return items.toMutableList().also {
            it[index] = if (running) {
                it[index].copy(running = true, completionUnread = false, failureUnread = false)
            } else {
                it[index].copy(running = false)
            }
        }
    }

    /**
     * 移除一条会话。
     *
     * 只在 `api-session/removed` 时调用：那是 Host 真正销毁会话（关页签 + 持久化层回收）。
     * **不要**拿它实现「归档」——归档是 `workspace/follow` 的 id 集合变化，改的是分组
     * 而不该把摘要从列表里删掉（删了之后取消归档就再也找不回来了）。
     */
    fun remove(items: List<SessionSummary>, sessionId: String): List<SessionSummary> {
        if (items.none { it.sessionId == sessionId }) return items
        return items.filterNot { it.sessionId == sessionId }
    }

    /**
     * 只改某条会话的「跑完了，但用户还没看」提醒标记（R049）。
     *
     * 与 [patchRunning] 同构：事件只带一个 id，没有完整摘要可替换。找不到 id 时
     * 原样返回——冷启动时序下凭空造一条摘要会带出 `updatedAt = 0` 的假行。
     *
     * 判据的**唯一出处**是 `CompletionLedger`；这里只负责把它的结论落到列表条目上。
     */
    fun patchUnreadFlags(
        items: List<SessionSummary>,
        sessionId: String,
        completionUnread: Boolean,
        failureUnread: Boolean,
        failureReason: String = "",
    ): List<SessionSummary> {
        val index = items.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return items
        val current = items[index]
        // 熄灭时一并清掉原因：留着会让下一条红点显示上一次失败的文案。
        val reason = if (failureUnread) failureReason else ""
        if (current.completionUnread == completionUnread &&
            current.failureUnread == failureUnread &&
            current.failureReason == reason
        ) {
            return items
        }
        return items.toMutableList().also {
            it[index] = current.copy(
                completionUnread = completionUnread,
                failureUnread = failureUnread,
                failureReason = reason,
            )
        }
    }

    /**
     * 用账本记住的运行态纠正若干条会话的 `running`（R049 二审 P2 的另一半）。
     *
     * `session/list` 是 2.6 秒的一次性 RPC，返回时它内里的 `running` 可能已经过时：
     * 请求发出后那条会话才开始跑，`api-session/status` 已把行 patch 成运行中，而快照
     * 里仍是 `false`。直接整表覆盖会把小柱打回静止，且那条 `status=true` 已被消费，
     * 之后不会再有事件纠正它——直到这一轮结束时 `status=false` 才「看起来正常」。
     *
     * @param updates 会话 id → 账本记住的运行态
     */
    fun patchRunningFromLedger(
        items: List<SessionSummary>,
        updates: Map<String, Boolean>,
    ): List<SessionSummary> {
        if (updates.isEmpty()) return items
        val stale = items.any { item ->
            updates[item.sessionId]?.let { it != item.running } == true
        }
        if (!stale) return items
        return items.map { item ->
            val authoritative = updates[item.sessionId] ?: return@map item
            if (authoritative == item.running) item else item.copy(running = authoritative)
        }
    }

    /**
     * 把「本次运行期内的完成提醒」重新盖到一份新列表上（R049）。
     *
     * `session/list` 不带这个标记，所以每次全量重拉都会把
     * [SessionSummary.completionUnread] 抹成默认 false。若不重新盖上，用户翻个页、
     * 或某次 `api-session/activity` 触发的重拉，就会把刚亮的绿点擦掉——而那条会话
     * 依然是「跑完了没人看」。
     *
     * @param items 新拉回来的列表
     * @param completionIds 此刻亮着完成提醒的会话（`CompletionLedger.unreadIds()`）
     * @param failureIds 此刻亮着失败提醒的会话（`CompletionLedger.failureIds()`）
     */
    fun carryUnreadFlags(
        items: List<SessionSummary>,
        completionIds: Set<String>,
        failureIds: Set<String>,
        failureReasons: Map<String, String> = emptyMap(),
    ): List<SessionSummary> {
        if (completionIds.isEmpty() && failureIds.isEmpty()) return items
        val stale = items.any {
            (it.sessionId in completionIds && it.completionUnread.not()) ||
                (it.sessionId in failureIds && (it.failureUnread.not() || it.failureReason != failureReasons[it.sessionId].orEmpty()))
        }
        if (!stale) return items
        return items.map {
            val reason = if (it.sessionId in failureIds) failureReasons[it.sessionId].orEmpty() else ""
            it.copy(
                completionUnread = it.completionUnread || it.sessionId in completionIds,
                failureUnread = it.failureUnread || it.sessionId in failureIds,
                // 原因必须一起重盖：`session/list` 的新条目里这一项恒为空串，
                // 只盖布尔会把读屏文案打回通用版（审查 P2）。
                failureReason = reason,
            )
        }
    }
}
