package com.revxzxcx.remotewebapp.core.notify

/**
 * 会话 id → 标题的小表（R056）。
 *
 * ## 为什么需要单独一张表
 * 发通知的那一刻，我们手上只有一个 `sessionId`（`api-session/status` 帧里只有 id 和
 * running 布尔）。而通知标题必须写人话——「任务结束 · 修登录接口」远比
 * 「任务结束 · session-3f9a…」有用。
 *
 * 标题的唯一权威来源是 `session/list` 的 `projections.values.title`，但那是
 * **1.78 MB 的全量响应**，绝不能为了取一个标题去拉它。所以这里记一份旁路：
 * 首页列表每次刷新时把标题灌进来（`AppRoot` 的一行 `LaunchedEffect`），
 * `api-session/added` 事件到达时也顺手更新（那条帧自带完整摘要）。
 *
 * ## 为什么不设上限
 * 一条会话一个字符串，几百条也就几十 KB。而**漏记**的代价是用户看到一串 id。
 * 会话被删除时由 [forget] 清掉，所以不会无限涨。
 *
 * 读写都在 IO 收集协程与主线程之间发生 → 用 `@Volatile` + 每次返回新 map 而不是
 * 加锁：读多写少，且所有读者只关心「能不能拿到一个非空标题」，短暂读到旧值无害。
 */
class SessionNameTable {

    @Volatile
    private var titles: Map<String, String> = emptyMap()

    /** 用一批摘要整体覆盖（首页列表刷新时调用）。空标题不覆盖已有标题。 */
    fun remember(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        val next = HashMap(titles)
        var changed = false
        for ((id, title) in pairs) {
            if (id.isBlank() || title.isBlank()) continue
            if (next[id] != title) {
                next[id] = title
                changed = true
            }
        }
        if (changed) titles = next
    }

    /** 记一条（`api-session/added` 用）。 */
    fun remember(sessionId: String, title: String) {
        if (sessionId.isBlank() || title.isBlank()) return
        if (titles[sessionId] == title) return
        titles = HashMap(titles).also { it[sessionId] = title }
    }

    /** 取标题；没有返回 null（**不是空串**，调用方要能区分「记着空标题」与「没记过」）。 */
    fun titleOf(sessionId: String): String? = titles[sessionId]?.takeIf { it.isNotBlank() }

    /** 会话被删除时清掉。 */
    fun forget(sessionId: String) {
        if (sessionId !in titles) return
        titles = HashMap(titles).also { it.remove(sessionId) }
    }

    /**
     * 清空整张表（换了一台主机时用，见 `NotifyCoordinator.attach`）。
     *
     * 会话 id 是每台主机各自的：不清的话，切到 B 之后发 B 上 `s-1` 的通知，
     * 标题会命中 A 留下的 `s-1` 标题——用户看到的是另一台机器上某个会话的名字，
     * 而他根本没在这台上开过它。
     */
    fun clear() {
        if (titles.isEmpty()) return
        titles = emptyMap()
    }

    /** 记着多少条（测试用）。 */
    val size: Int get() = titles.size
}
