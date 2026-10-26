package com.revxzxcx.remotewebapp.core.session

/**
 * 会话**完成提醒**的进程内账本（R049，用户 m32994 + m33325 纠正）。
 *
 * ## 这个点到底是什么
 *
 * 用户第一次说的是「对话完成时图标应该从加载切成绿点」，我按字面实现成「只要不在跑就
 * 是绿点」——结果列表上几乎每一行都挂着一枚常驻绿点。用户随后纠正：
 * 「这个点不是常驻的，而是一个完成的标记，提醒用户查看对话的」。
 *
 * 所以它的语义是**一次性提醒**：一个会话从「在跑」变成「不在跑」，且当时用户没在看它，
 * 才点亮；用户进这个会话看过了，就熄灭；它再次跑起来，也熄灭。
 *
 * ## 为什么照官方这套边沿判定
 *
 * DSH 桌面端 `@deepseek-ai/dsh-client-ui-session` 的 `UiSession` 里就是这份逻辑
 * （asar 内 `observeRunning` / `reconcileStatus`）：
 *
 * ```js
 * observeRunning(sessionId, running) {
 *   const previous = this.running.get(sessionId);
 *   this.running.set(sessionId, running);
 *   if (running) this.completionUnread.delete(sessionId);
 *   else if ((previous === true || previous === void 0 && beforeBaseline) && !this.isMain(sessionId))
 *     this.completionUnread.add(sessionId);
 * }
 * reconcileStatus() { … for (const id of present) if (this.isMain(id)) this.completionUnread.delete(id); }
 * ```
 *
 * 三个要点，缺一个都会退化成现在的错误形态：
 *
 * 1. **必须是边沿**（`previous === true`），不能是电平（「当前不在跑」）。电平判据会把
 *    所有历史上跑完的会话全部点亮，也就是用户看到的满屏绿点。
 * 2. **正在看的那个会话不点亮**（`!isMain`）。用户盯着屏幕看它跑完，不该再冒一个「你来看」。
 * 3. **看过就清**（`reconcileStatus` 里的 `isMain` 清理；本工程由 [setCurrentSession] 承担，
 *    进入会话即熄灭）。否则提醒会
 *    永远留在那里，变成装饰。
 *
 * ## 为什么是进程内而不是落盘
 *
 * 官方 README 明说「"New task completed" unread marking is local viewing state —
 * completion-time > last-seen never reaches the host」。完成时间与上次查看时间都不在
 * 服务端数据里（`session/list` 顶层只有 8 个字段，没有 outcome 也没有 seen），
 * 所以这份账本天然只在本次进程内成立。冷启动后不补点亮——重启后凭空点亮一批
 * 「上次关机前跑完的」会话，用户无从判断哪些是新的。
 *
 * ## 线程
 *
 * 只在主线程读写（`HomeViewModel` 的 collect 与 UI 回调都在主线程），所以不加锁。
 * 这不是疏忽：加锁会让「读-改-写」在持有锁的区间里跨协程挂起，反而更难保证一致。
 */
internal class CompletionLedger {

    /** 上一次观测到的运行态。用于区分「边沿」与「电平」，见类 KDoc 第 1 条。 */
    private val running = mutableMapOf<String, Boolean>()

    /** 当前亮着的完成提醒集合。 */
    private val unread = mutableSetOf<String>()

    /**
     * 当前亮着的失败提醒集合。
     *
     * 与 [unread] 同构、同生命周期：也是「一次性提醒」，不是「这条会话历史上挂过」。
     * 用户进来看过就灭，它重新跑起来也灭（新的失败会再点亮）。
     *
     * 为什么不能只靠 [running] 判断失败：`api-session/error` 是**跑挂了**的时刻才发的事件，
     * 而跑挂了之后 `running` 就是 false，之后再也不会有事件说明「这条会话上次失败了」。
     * 所以失败必须自己记一份，且同样只在本次运行期内有效。
     */
    private val failed = mutableSetOf<String>()

    /**
     * 失败原因（`api-session/error` 的第二项）。与 [failed] 同生命周期，见 [observeFailure]。
     *
     * 为什么不干脆把它挂在列表条目上就算：条目的重建路径有三条（全量重拉、摘要重发、
     * 缓存铺底），每一条都会造新对象，只有账本能在这些重建之间守住原因。
     */
    private val failedReasons = mutableMapOf<String, String>()

    /**
     * 状态事件的单调纪元：每收到一次 `api-session/status` 就 +1。
     *
     * 存在的唯一目的是挡住 [seed] 的一个真实竞态（R049 二审 P2）：`session/list` 是
     * 2.6 秒的一次性 RPC，请求发出后那条会话可能**才开始跑**（收到 `status = true`，
     * 基线被 [observe] 置为 true），而返回的快照里它仍是 `running = false`。此时
     * [seed] 会把它当成一次「跑完了」的边沿——既在正在跑的会话上点亮绿点，又把基线
     * 改回 false；等它真的跑完时 `previous == false`，那一轮的完成提醒被永久吞掉。
     *
     * 判据：`seed` 只在「这份快照发出之后没有关于该会话的状态事件」时才做基线比对。
     */
    private var statusEpoch: Long = 0

    /** 每条会话最后一次被 [observe] 观测到时的 [statusEpoch]。 */
    private val lastStatusEpoch = mutableMapOf<String, Long>()

    /** [statusEpoch] 的当前值，供调用方在发起全量拉取前记下。 */
    fun currentEpoch(): Long = statusEpoch

    /** 当前正在被查看的会话 id；null 表示停在列表页。 */
    var currentSessionId: String? = null
        private set

    /**
     * 用户进入了某个会话（或离开所有会话）。
     *
     * 进入时立刻把该会话的提醒熄灭——这就是「看过即清」，用户已经在读了，
     * 再挂一个「来看」没有意义。离开（传 null）只改当前会话，不动提醒。
     */
    fun setCurrentSession(sessionId: String?) {
        currentSessionId = sessionId
        if (sessionId != null) {
            unread -= sessionId
            failed -= sessionId
            failedReasons -= sessionId
        }
    }

    /**
     * 观测到某条会话的运行态变化，返回它此刻**是否**应当亮着完成提醒。
     *
     * 调用方只需要把返回值写回列表条目。判据顺序与类 KDoc 一致：
     * 跑起来 → 灭；不跑了且上一刻确实在跑且不在当前会话 → 亮；其余保持原状。
     */
    fun observe(sessionId: String, running: Boolean): Boolean {
        statusEpoch += 1
        lastStatusEpoch[sessionId] = statusEpoch
        val previous = this.running.put(sessionId, running)
        return when {
            running -> {
                // 又跑起来了：上一轮的完成提醒与失败提醒都该灭——新的结果会重新决定点亮什么。
                unread -= sessionId
                failed -= sessionId
                failedReasons -= sessionId
                false
            }
            // 边沿：只有「上一刻在跑」才算跑完。previous == null（从没见过）说明这是
            // 冷启动第一次见到它，不能当作刚跑完——否则重启后满屏绿点。
            //
            // 官方这里还多一个 `previous === void 0 && beforeBaseline` 分支（列表快照仍是
            // `pending` 时把首次观测也当边沿）。本实现**有意不实现**：App 侧 `session/list`
            // 是 2.6 秒的一次性 RPC，没有官方那种「列表快照挂起、事件先于快照到达」的
            // 显式 phase，唯一能做的是拿 `sessions.items.isEmpty()` 冒充——而冷启动时
            // 列表为空是真常态，那等于把重启后所有会话都点亮，正是用户否掉的形态。
            // 首帧真正需要的场景（后台/断连期间跑完）由 [seed] 的基线比对覆盖。
            previous == true && sessionId != currentSessionId -> {
                unread += sessionId
                true
            }
            else -> sessionId in unread
        }
    }

    /**
     * 记一条失败提醒（`api-session/error`），返回它此刻是否应当亮着。
     *
     * 用户正在看的那条不点亮：他就在会话里，失败原因已经由会话内的失败卡片呈现了
     * （`core/session/TurnOutcome` + `ui/chat/TurnResultCard`），列表上再挂一枚红点
     * 属于重复提醒。
     */
    fun observeFailure(sessionId: String, reason: String = ""): Boolean {
        if (sessionId == currentSessionId) return false
        failed += sessionId
        // 原因必须跟着账本走，不能只留在列表条目里：`session/list` 重拉、`api-session/added`
        // 重发摘要、冷启动铺缓存这三条重建路径都会把条目换成新对象，原因会被抹成空串，
        // 而 `failed` 还在——红点亮着但读屏退回通用文案（审查 P2）。
        if (reason.isNotBlank()) {
            failedReasons[sessionId] = reason
        } else {
            failedReasons -= sessionId
        }
        // 失败优先于「跑完了没看」：同一条会话既跑完又报错时，红点承载更多信息。
        unread -= sessionId
        return true
    }

    /** 这条会话失败原因文本；没有就返回空串（读屏退回通用文案）。 */
    fun failureReason(sessionId: String): String = failedReasons[sessionId].orEmpty()

    /** 当前所有亮着失败提醒的会话及其原因。 */
    fun failureReasons(): Map<String, String> = failedReasons.toMap()

    /** 这条会话的失败提醒是否还亮着。 */
    fun isFailureUnread(sessionId: String): Boolean = sessionId in failed

    /** 当前所有亮着失败提醒的会话 id。 */
    fun failureIds(): Set<String> = failed.toSet()

    /**
     * 用一份全量列表给账本**播种**运行态，并把「基线变化」转成一次边沿。
     *
     * 分两种情形：
     *
     * - **没见过的会话**：[MutableMap.putIfAbsent] 语义，只补基线、不点亮。第一次见到
     *   一条已经停着的会话，不能当作「刚跑完」——否则冷启动后满屏绿点，正是用户否掉的形态。
     * - **见过且基线变了**（账本记着 `true`，列表说 `false`）：走一次 [observe]，与官方
     *   `reconcileStatus()` 的 `previous !== row.running` 对齐。这条路径覆盖「App 在后台
     *   或断连期间跑完一轮」——那时没有 `api-session/status` 事件，只有列表能告诉我们还活着。
     *   漏掉它，那一轮的完成提醒会永久丢失。
     *
     * 同时把「当前不在列表里」的会话从三份账里清掉：会话被销毁后残留的 id 会让账本
     * 无限增长，也会让一条同名新会话凭空继承旧提醒。
     *
     * @param sinceEpoch 调用方**发起**这次全量拉取前记下的 [currentEpoch]。快照在飞的时候
     *   收到的状态事件比快照新，那些会话跳过基线比对（见 [statusEpoch] 与二审 P2）。
     *   传 `null` 表示不启用这道守卫（只在测试与「刚拿到一份新鲜快照」的场合合适）。
     *
     * @return 被纪元守卫跳过、基线未采纳快照值的会话 id。调用方必须用 [runningOf]
     *   把这些行在列表里的 `running` 纠正回账本真相——只保住账本基线而不改列表，
     *   陈旧快照仍会把「刚开始跑」的行打回静止，且之后没有事件会再纠正它。
     *
     * 点亮结果本身不用接收：都进了 [unreadIds]，紧接着的
     * `SessionListUpdates.carryUnreadFlags` 会把它整体盖回列表。
     */
    fun seed(items: List<SessionSummary>, sinceEpoch: Long? = null): Set<String> {
        val present = items.mapTo(mutableSetOf()) { it.sessionId }
        // 被纪元守卫跳过、因而基线未采纳快照值的会话。调用方必须用 [runningOf]
        // 把它们在列表里的 `running` 覆盖回账本真相，否则那份陈旧快照会把
        // 刚刚开始跑的行打回静止（二审 P2 的另一半）。
        val skipped = mutableSetOf<String>()
        for (item in items) {
            // 这份快照发出之后又收到过该会话的状态事件 —— 快照里的 `running` 已经过期，
            // 拿它比对会把「刚开始跑」误判成「跑完了」，还会把基线改回 false 从而吞掉
            // 真正的完成边沿（R049 二审 P2）。直接跳过，事件才是权威。
            //
            // `sinceEpoch` 为 null 表示调用方不追踪纪元（不做这道守卫）。不能把它默认成
            // `0`——那样任何被 `observe` 碰过的会话（纪元从 1 起）都会被跳过，守卫会
            // 变成「基线比对永不触发」的另一种 bug。
            if (sinceEpoch != null && (lastStatusEpoch[item.sessionId] ?: 0) > sinceEpoch) {
                skipped += item.sessionId
                continue
            }
            // 必须先接住 putIfAbsent 的返回值：条目已存在时它**不会**覆盖旧值，
            // 之后再去读 `running[id]` 拿到的必然是旧值，`!=` 恒为 false，比对永远不触发。
            val previous = running.putIfAbsent(item.sessionId, item.running)
            if (previous != null && previous != item.running) {
                observe(item.sessionId, item.running)
            }
        }
        // 清理也必须守同一道纪元守卫：`present` 来自一份**可能已经过期**的快照，
        // 「不在快照里」不等于「会话没了」。请求发出后由 `api-session/added` 或
        // `api-session/status` 写进账本的新会话（行本身可能已由 upsert 追加进列表）
        // 恰恰不在 `present` 里，无条件 retainAll 会把它的基线、刚点亮的提醒一起删掉：
        // 之后它跑完时 `previous == null`，按边沿判据不产边沿 —— 那一轮的绿点永久丢失。
        // 真正的「会话被销毁」由 `api-session/removed` → [forget] 负责，不靠快照成员表推断。
        fun touchedAfterRequest(id: String): Boolean =
            sinceEpoch != null && (lastStatusEpoch[id] ?: 0) > sinceEpoch
        running.keys.retainAll { it in present || touchedAfterRequest(it) }
        unread.retainAll { it in present || touchedAfterRequest(it) }
        failed.retainAll { it in present || touchedAfterRequest(it) }
        failedReasons.keys.retainAll { it in present || touchedAfterRequest(it) }
        lastStatusEpoch.keys.retainAll { it in present || touchedAfterRequest(it) }
        return skipped
    }

    /**
     * 账本记住的运行态；从没被观测过时返回 null。
     *
     * 供调用方在全量快照返回后纠正列表里的 `running`——快照可能比状态事件旧，
     * 而账本里的基线只由事件写入，是这两者中的权威（见 [statusEpoch]）。
     */
    fun runningOf(sessionId: String): Boolean? = running[sessionId]

    /** 某条会话此刻是否亮着完成提醒。 */
    fun isUnread(sessionId: String): Boolean = sessionId in unread


    /** 会话被销毁：连运行态账一起忘掉。 */
    fun forget(sessionId: String) {
        lastStatusEpoch -= sessionId
        running -= sessionId
        unread -= sessionId
        failed -= sessionId
        failedReasons -= sessionId
    }

    /** 当前所有亮着提醒的会话 id。供全量重拉后重新盖上（`session/list` 不带这个标记）。 */
    fun unreadIds(): Set<String> = unread.toSet()
}
