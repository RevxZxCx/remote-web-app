package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.capsule.CapsulePhase
import com.revxzxcx.remotewebapp.core.capsule.PendingKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 流体云此刻**正在替普通通知说话**的那一条会话（R062-B019）。
 *
 * 用户原话（m07102）：「开启流体云通知的时候，基础的普通通知有些功能已经被流体云
 * 替代了，普通通知却还在推送，这里属于重复通知了」。
 *
 * ## 为什么是「一个会话」而不是一张表
 * 系统只给胶囊**一个展示位**（`docs/dev/流体云接入调研.md` 3.7-A，真机实测多条
 * promoted 通知不会变成多个胶囊，只会轮播）。所以不管挂着多少条待处理、多少个会话
 * 在跑，同一时刻能被胶囊覆盖的**最多只有一个会话**。
 *
 * ## 只覆盖「任务结束」，不覆盖审批与提问（刻意的，不是漏做）
 * | 类别 | 是否压掉普通通知 | 理由 |
 * |---|---|---|
 * | 任务结束 / 出错 | **是** | 胶囊上已经有「已完成 / 出错」、对应状态图标和停住的计时器，信息完全重合 |
 * | 待批准 / 待回答 | **否** | 那是 `NoticeChannels.ASK` 频道，是**唯一会响铃震动**的通道。胶囊走 `IMPORTANCE_LOW`、静默无声（频道重要性创建后不可提升，见决策 D208）；而 waterfall 请求有超时，超时后任务直接失败。为少一张重复卡片而让请求在口袋里静默超时，代价不对等 |
 *
 * 如果将来要连审批一起压掉，正确做法是**先解决响铃通道**（例如给胶囊频道单独发一条
 * 高优先级的临时提醒），而不是把 [coversDone] 扩展成「什么都压」。
 *
 * @param sessionId 胶囊正在显示的会话；`null` 表示胶囊此刻没显示任何东西
 *   （被用户划掉、没打开「流体云显示实时活动」、或根本没有值得显示的会话）。
 * @param pending 胶囊当前呈的待处理类型。**只有 `None` 才可能覆盖「任务结束」**：
 *   正在显示审批时，卡片上没有任何「已完成」的痕迹。
 * @param phase 胶囊当前呈的相态。
 * @param posting 胶囊此刻**确实能显示**（`CapsuleCenter.canPost()` 全部通过）。
 *   这是覆盖成立的前提：用户没开「流体云显示实时活动」时胶囊根本不会出现，
 *   此时若还压掉普通通知，用户就彻底收不到提醒了。
 */
data class CapsuleCoverage(
    val sessionId: String? = null,
    val pending: PendingKind = PendingKind.None,
    val phase: CapsulePhase = CapsulePhase.Idle,
    val posting: Boolean = false,
) {

    /**
     * 这条会话的「任务结束 / 出错」通知，是不是已经由胶囊替它说了。
     *
     * 任何一项不满足都返回 `false`——**漏压一条重复通知，用户顶多被多提醒一次；
     * 错压一条，用户就完全收不到了**。所以判据取保守那一侧。
     */
    fun coversDone(sessionId: String): Boolean =
        posting &&
            this.sessionId == sessionId &&
            pending == PendingKind.None &&
            (phase == CapsulePhase.Done || phase == CapsulePhase.Failed)

    companion object {
        /** 胶囊什么都没显示，也不具备显示能力。 */
        val NONE: CapsuleCoverage = CapsuleCoverage()
    }
}

/**
 * 被胶囊顶掉、但**还没真正送达用户**的一条完成通知。
 *
 * ## 为什么需要「欠着」这一层
 * 胶囊只有一个展示位。会话 A 跑完 → 胶囊显示 A「已完成」→ 普通通知被压掉；
 * 紧接着会话 B 挂着审批抢走焦点（待处理在选举里压过已完成）→ 胶囊变成 B，
 * **A 从胶囊上消失了，而它那条完成通知从头到尾没发过**。用户于是两头落空。
 *
 * 所以压掉的时候要记账，等胶囊不再覆盖 A 时补发。不记的话，「去重」就变成了
 * 「丢通知」——那比重复推送严重得多。
 */
data class DeferredDone(
    val failed: Boolean,
    val reason: String?,
    /**
     * 记账那一刻的会话名。
     *
     * 为什么必须跟着账一起存：补发发生在 [CapsuleCoverageRegistry.state] 变化时，
     * 而 `NotifyCoordinator.attach` 是**先** `names.clear()` **后**订阅 state 的
     * （`StateFlow` 订阅即重放当前值，立刻触发一次补发）。那一刻 `names` 是空的，
     * 而重新播种要等首页刷新 → 同机重连保留的欠账会退化成「未命名会话 xxxxx」。
     * 跨主机的情况无所谓（欠账那时已被清掉），但同机重连很常见，不能降级成占位名。
     */
    val sessionName: String? = null,
)

/**
 * 胶囊 ↔ 通知层的**共享覆盖账**（R062-B019）。
 *
 * ## 为什么要一本共享的账
 * 两层各自独立订阅同一批事件（`NotifyCoordinator` 订 `approvals` / `questions` /
 * 会话事件流，`CapsuleCoordinator` 订同一批），谁也不知道对方刚发过什么。
 * 这不是某一处忘了加判断，而是**结构上缺一个交汇点**——所以修法是补这个交汇点，
 * 而不是在 `postDone` 里加一个 `if`。
 *
 * 沿用 [SubagentRegistry] 的做法：由 `AppContainer` 建**一份**实例，同时传给两层。
 * 两处各记一本的话，会出现「胶囊以为自己在显示、通知层以为没显示」这种自相矛盾，
 * 而那种矛盾在真机上表现为「有时多一条、有时少一条」——最难查的一类。
 *
 * ## 为什么是 `StateFlow` 而不是回调
 * 通知层需要看到**状态变化**，不只是当前值：变成「覆盖 A」时要撤掉已经躺在通知栏
 * 里的 A（先发后接管，顺序反的那一半）；不再覆盖 A 时要补发欠着的那条。
 * 回调只能给「此刻是什么」，给不了「刚刚变了什么」。
 */
class CapsuleCoverageRegistry {

    private val _state = MutableStateFlow(CapsuleCoverage.NONE)

    /** 供通知层 `collect` 的覆盖状态。订阅即拿到当前值。 */
    val state: StateFlow<CapsuleCoverage> = _state.asStateFlow()

    /** 保护 [deferredDone]，它由通知层写、通知层读后清，另加锁防跨协程。 */
    private val lock = Any()

    private val deferredDone = HashMap<String, DeferredDone>()

    /** 胶囊层发布它此刻覆盖了什么。 */
    fun publish(next: CapsuleCoverage) {
        _state.value = next
    }

    /**
     * 原子地「判定覆盖 + 记账」：覆盖成立才记账并返回 `true`。
     *
     * 为什么不能由调用方先 `state.value.coversDone(...)` 再调 [deferDone]：
     * 两步之间胶囊可能刚好 `publish(NONE)`（用户划掉胶囊即触发），
     * 那一刻 `flushDeferred` 会在 `deferredIds()` 还是空的时侯跑完，
     * 而随后落地的这条账**等不到下一次 emit**——`StateFlow` 对相同值会去重，
     * 覆盖状态已经是 `NONE` 了，不会再变。结果是这条完成通知永久丢失。
     *
     * 判定与记账必须在同一把锁里，中间不能有缝。
     */
    fun deferUnlessCovered(sessionId: String, notice: DeferredDone): Boolean =
        synchronized(lock) {
            if (!_state.value.coversDone(sessionId)) {
                false
            } else {
                deferredDone[sessionId] = notice
                true
            }
        }

    /** 通知层压掉一条完成通知时记账。 */
    fun deferDone(sessionId: String, notice: DeferredDone) {
        synchronized(lock) { deferredDone[sessionId] = notice }
    }

    /**
     * 取出并清掉一条欠账。
     *
     * @return `null` = 这条会话没有被欠过（绝大多数情况）。
     */
    fun takeDeferred(sessionId: String): DeferredDone? =
        synchronized(lock) { deferredDone.remove(sessionId) }

    /**
     * 丢掉一条欠账，不补发。
     *
     * 用于「用户已经亲眼看到了」：会话被删除、或用户打开它（`completionUnread`
     * 随之熄灭）。这两种情况下补发通知只是打扰。
     */
    fun dropDeferred(sessionId: String) {
        synchronized(lock) { deferredDone.remove(sessionId) }
    }

    /** 当前欠着几条（测试与诊断用）。 */
    @androidx.annotation.VisibleForTesting
    val deferredCount: Int get() = synchronized(lock) { deferredDone.size }

    /**
     * 当前欠着哪几条会话的快照。
     *
     * 返回**快照**而不是视图：调用方拿到后会逐条 `postDone`（可能跨挂起点），
     * 期间本账可能被事件协程改写。边遍历边改会 `ConcurrentModificationException`
     * ——这与 B008 里 `RemoteStreamMux.close` 的坑是同一个形状。
     */
    fun deferredIds(): List<String> = synchronized(lock) { deferredDone.keys.toList() }

    /**
     * 把「胶囊此刻在替谁说话」复位成什么都没覆盖。
     *
     * ## 为什么和 [clearDeferred] 分开（R062-B019 第一轮审查 P1-2 / P3-2）
     * 原先只有一个 `reset()`，同时做「复位状态」和「丢掉欠账」两件事。可是这两个
     * 动作的**适用边界完全不同**：
     *
     * - 复位状态：每次 `attach` 都要做。胶囊在进程重启/断连后已经没了，
     *   留着 `coversDone = true` 会让通知层替一个根本没显示的卡片压通知。
     * - 丢掉欠账：**只有退配对时才该做**。那些欠账是「胶囊替我说过了，所以普通
     *   通知还没发」，目标会话在同一台主机上仍然存在，重连之后必须补发。
     *
     * 合成一个方法之后，`CapsuleCoordinator.detach()` 里那句「欠账不清：重连后
     * 仍然要补」就成了假话 —— 一次网络抖动重连就会把欠账静默丢掉，用户永远
     * 收不到那几条完成通知，而这正是 B019 想消灭的症状。
     */
    fun resetCoverage() {
        _state.value = CapsuleCoverage.NONE
    }

    /**
     * 丢掉全部欠账。
     *
     * 只在**退配对**时调用（`NotifyCoordinator.attach(null)`）：欠账里的会话 id
     * 属于上一台机器，补发出去只会是一条点不开的僵尸通知。
     */
    fun clearDeferred() {
        synchronized(lock) { deferredDone.clear() }
    }
}
