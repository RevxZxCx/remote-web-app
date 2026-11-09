package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.session.SessionSummary

/**
 * 「这个 sessionId 是子代理吗」的旁路登记表（R059-B011 / B012）。
 *
 * ## 为什么必须单独记一张表
 * 判定子代理的唯一依据是会话摘要里的 `origin == "subagent"`
 * （见 `SessionSummary.isSubagent`）。但**摘要只在三个地方出现**：
 * `session/list` 的全量、`api-session/added` 帧、以及仓库缓存。
 * 而真正驱动通知与胶囊的是 `api-session/status` 与 `api-session/error`——
 * 这两种帧**只有 sessionId 和布尔值，没有 origin**。
 *
 * 于是「子代理跑完了」和「主会话跑完了」在事件层完全同形，通知层无从分辨。
 * 这不是漏判某个分支，而是**信息在传输途中就丢了**，只能靠旁路补记。
 *
 * ## 为什么这个 bug 会复发
 * 项目里已经出现过两次同款：第一次是会话列表把子代理当会话列出来
 * （`WorkspaceSessions.items` 的过滤），第二次是统计口径（`ContextTotals`）。
 * 两次都是「在某处加了个过滤，但别处读的是同一条数据流却没加」。
 * 所以本表刻意做成**一个可以被任意层查询的单一事实源**，而不是在每个
 * 消费点各写一遍 `origin == "subagent"`——那样只会漏掉第三处、第四处。
 *
 * ## 读多写少，用 @Volatile + 每次返回新容器
 * 理由与 [SessionNameTable] 完全一致：写在主线程（列表刷新、added 帧），
 * 读在 IO（通知收集协程、胶囊协调器）。短暂读到旧值只会让一条通知多发一次，
 * 不会破坏任何持久状态，不值得为它加锁。
 *
 * ## 为什么「不知道」要当「不是子代理」
 * 见 [isSubagent] 的 KDoc：宁可多提醒一次，也不要吞掉主会话的完成通知。
 */
class SubagentRegistry {

    /** 已确认为子代理的会话 id。 */
    @Volatile
    private var subagents: Set<String> = emptySet()

    /** 已确认为**非**子代理的会话 id。两集合互斥，见 [remember]。 */
    @Volatile
    private var primaries: Set<String> = emptySet()

    /**
     * 用一批会话摘要整体校准（首页全量刷新时调用）。
     *
     * ## 为什么不干脆整体覆盖
     * 全量刷新有两类调用方：应用了服务端完整列表的，与应用了增量事件的。
     * 前者权威，后者只是局部。**若让增量路径也整体覆盖，一次局部刷新会把
     * 其余会话的登记全抹掉**，下一次 status 帧就判不出来了。
     * 所以这里只做「补记」，删除只走 [forget]。
     *
     * 代价是一张只增不减的表。这可以接受：一条会话一个字符串，
     * 几百条也就几十 KB；而会话被销毁时会由 `api-session/removed` 走 [forget] 清掉。
     */
    fun remember(items: List<SessionSummary>) {
        if (items.isEmpty()) return

        // ## 先按 id 归并出「本批的最终结论」，再动全局（审查 P2）
        //
        // 早先是边遍历边往两个集合里写。同一批里若某个 id 先以 `subagent`、后以
        // 非子代理出现（服务端在同一帧里补发了两次摘要，或调用方把两批拼成了一批发过来），
        // 它会被同时写进 `nextSubagents` 与 `nextPrimaries`；紧接着两次减法各摘一次，
        // 最终**两个集合里都没有它**——等价于「不知道」，最坏后果是整个登记表
        // 对这条会话失效，子代理完成通知又开始多发。
        //
        // 归并规则与单条序列保持一致：后出现的覆盖先出现的。
        val latest = LinkedHashMap<String, Boolean>()
        for (item in items) {
            val id = item.sessionId
            if (id.isBlank()) continue
            latest[id] = item.isSubagent
        }
        if (latest.isEmpty()) return

        var nextSubagents = subagents
        var nextPrimaries = primaries
        var changed = false
        for ((id, isSub) in latest) {
            // 已经记过同一个结论就不动，避免每次都复制容器。
            if (isSub && id !in nextSubagents) {
                if (nextSubagents === subagents) nextSubagents = HashSet(subagents)
                nextSubagents = nextSubagents + id
                changed = true
            } else if (!isSub && id !in nextPrimaries) {
                if (nextPrimaries === primaries) nextPrimaries = HashSet(primaries)
                nextPrimaries = nextPrimaries + id
                changed = true
            }
        }
        if (!changed) return
        // 两个集合必须互斥，且**以最新一次结论为准**——注意是双向的。
        //
        // 服务端确实会改这个字段，两个方向都真实存在：
        // - 主 → 子：摘要初次到达时 `origin` 还没填，后一次刷新才带上 `subagent`；
        // - 子 → 主：子代理被提升成独立会话，或者 `origin` 在后续帧里被抹掉。
        //
        // 早先这里只做了 `primaries - overlap`，于是子 → 主 那一方向永远翻不过来：
        // 一旦某 id 被记成子代理，即使服务端后来把它改回普通会话，它也会一直留在
        // `subagents` 里直到被销毁——之后的完成与失败通知会被**静默吞掉**，
        // 而这正是本类 KDoc 说要避免的那种失效（用户根本不知道任务结束了）。
        //
        // 正确做法：以本批**新写入的那一侧**为准。下面按 `nextSubagents` 与
        // `nextPrimaries` 各自相对旧值的增量来判断，谁这次被写进来谁赢。
        val addedSubagents = nextSubagents - subagents
        val addedPrimaries = nextPrimaries - primaries
        if (addedSubagents.isNotEmpty()) {
            // 本批把它们记成子代理 → 从主会话表里摘掉
            nextPrimaries = nextPrimaries - addedSubagents
        }
        if (addedPrimaries.isNotEmpty()) {
            // 本批把它们记成主会话 → 从子代理表里摘掉（子 → 主 的翻转）
            nextSubagents = nextSubagents - addedPrimaries
        }
        subagents = nextSubagents
        primaries = nextPrimaries
    }

    /** 记一条（`api-session/added` 帧用；那条帧自带完整摘要）。 */
    fun remember(summary: SessionSummary) = remember(listOf(summary))

    /**
     * 这个会话是不是子代理。
     *
     * ## 三态里「不知道」偏向哪边
     * 表里没有这个 id 时返回 `false`（当成主会话）。理由：
     * - 漏判的后果是**多发一条通知**，用户看一眼就知道是子代理；
     * - 误判的后果是**主会话跑完了却一声不吭**，用户根本不知道任务已经结束。
     * 前者可恢复，后者是静默失效——本项目反复栽在静默失效上，两害相权取轻。
     */
    fun isSubagent(sessionId: String?): Boolean =
        if (sessionId.isNullOrBlank()) false else sessionId in subagents

    /** 会话被销毁时清掉。 */
    fun forget(sessionId: String) {
        if (sessionId !in subagents && sessionId !in primaries) return
        if (sessionId in subagents) subagents = subagents - sessionId
        if (sessionId in primaries) primaries = primaries - sessionId
    }

    /**
     * 清空整张表（换了一台主机时用，见 `NotifyCoordinator.attach`）。
     *
     * 必须清：不同主机上同一个 sessionId 完全可能是另一条会话，
     * 留着旧结论会让新连接上的会话被误判成子代理。
     */
    fun clear() {
        subagents = emptySet()
        primaries = emptySet()
    }
}
