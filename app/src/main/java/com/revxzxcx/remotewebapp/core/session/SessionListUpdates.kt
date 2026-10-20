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
        if (items[index].running == running) return items
        return items.toMutableList().also { it[index] = it[index].copy(running = running) }
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
}
