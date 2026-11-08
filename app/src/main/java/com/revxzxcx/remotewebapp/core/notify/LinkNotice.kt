package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.protocol.CarrierState

/**
 * 连接状态类通知的纯逻辑（R056 缺口 3）。
 *
 * ## 为什么连接断了也要通知
 * 这条链路上有两个东西会一起失效，而用户看不见任何一个：
 *
 * 1. **事件流断了**——`sessionNotificationFeed` 是 `replay = 0` +
 *    `DROP_OLDEST` 的旁路（见 `RemoteEventsClient`），断线期间发出的事件**永久丢失**，
 *    重连后不补发。也就是说「任务跑完了」这条通知在断线期间根本不会产生。
 * 2. **`session/follow` 也断了**——即便断线期间任务真的跑完了，缺口 5 那条
 *    「查结局」的查询同样走 mux，一样查不到。
 *
 * 用户看到的是「App 安静得像一切正常」，而它其实是瞎的。**在一片安静中间插一条
 * 「我掉线了」，是整套通知里唯一能让用户区分「没事」和「不知道」的手段。**
 *
 * ## 为什么要有宽限时长
 * 移动网络下短暂抖动是常态：进电梯、切 Wi-Fi/蜂窝、锁屏后系统临时关 socket。
 * 这些都会让载波在 `Connected` 与 `Reconnecting` 之间来回跳，多数几秒就自愈。
 * **每次抖动都发一条「连接已断开」，用户一天会收到几十条，然后把这个频道关掉**
 * ——那恰好毁掉真正需要它的场景。所以断开要持续够久才算数（[DROP_GRACE_MILLIS]）。
 *
 * ## 为什么用墙钟时长而不是重连次数
 * 重试是 `2s → 4s → 8s …` 指数退避，次数越多确实断得越久，但**次数会归零**
 * （`Connected` 时 `consecutiveFailures = 0`）。于是「第几次失败」无法跨越一次
 * 短暂恢复去描述「这台机器现在是不是连不上」。墙钟单调，不会因为一次抖动被清零。
 *
 * ## 为什么恢复之后还要「补账」
 * 断线期间丢掉的不是只有「断开」这一件事，还有**断线期间本该发生的所有事件**。
 * 恢复网络不等于恢复信息：断线时正在跑的那个任务，可能在这 30 秒里已经跑完、
 * 跑挂、或者被截断了。所以 `Up` 之后要把「断线时正在跑的会话」逐个查一遍结局
 * （[LinkAction.Settle]），把漏掉的结果补发出去——否则用户永远不知道那一轮的结果。
 */
enum class LinkPhase {
    /** 通着（`Connected`）。 */
    Up,

    /** 正在连或正在等重试——**暂时**不通。 */
    Down,

    /** 配对凭证失效：再等也不会好，必须用户重新配对。 */
    Unpaired,

    /** 从未连接（`Idle`）：还没开始过，不该报错。 */
    Idle,
    ;

    /** 是否需要让用户知道「现在收不到东西」。 */
    val isProblem: Boolean get() = this == Down || this == Unpaired
}

/** 把载波状态压成 [LinkPhase]。 */
fun linkPhaseOf(state: CarrierState?): LinkPhase = when (state) {
    is CarrierState.Connected -> LinkPhase.Up
    is CarrierState.Reconnecting -> LinkPhase.Down
    is CarrierState.Connecting -> LinkPhase.Down
    is CarrierState.Unpaired -> LinkPhase.Unpaired
    is CarrierState.Idle, null -> LinkPhase.Idle
}

/**
 * 一次载波变化带来的动作。
 *
 * 把「什么都不做」显式列成一个分支，是因为 `when` 里一旦没有它，
 * 实现时很容易写成「其余情况一律撤通知」，而 `Idle` 恰好会落进「其余」。
 */
sealed interface LinkAction {
    /** 什么都不用做。 */
    data object Silent : LinkAction

    /** 发一条「连不上了」。[phase] 用来区分「等一会可能好」与「必须重新配对」。 */
    data class Warn(val phase: LinkPhase) : LinkAction

    /** 撤掉之前发的「连不上了」。 */
    data object Clear : LinkAction

    /**
     * 断线后恢复了：把断线期间正在跑的会话逐个补查结局。
     *
     * 与 [Clear] 可以同时发生——先说「连上了」，再补发漏掉的结果。
     * 拆成两个动作是因为它们不总是一起：只断了 30 秒（没到宽限，没发过警告）
     * 就恢复的，只补账、不发任何「恢复了」的通知。
     */
    data object Settle : LinkAction
}

/**
 * 连接通知的判定器。
 *
 * **有状态、非线程安全**：只在协调器的单个 `carrier.collect` 协程里用。
 * 所有时间都由调用方传入（`nowMillis`），不读系统时钟，测试可以随便造时间。
 */
class LinkNoticeGate(
    /**
     * 断开多久之后才提醒。
     *
     * 90 秒的取值理由：退避是 `2s → 4s → 8s → 16s → 32s`，加上每次握手本身
     * 可能耗几秒，90 秒大致等于「已经重试 5 次以上」。在这个时长内恢复的抖动
     * 一条通知都不发；超过它基本可以确定不是抖动。
     */
    private val dropGraceMillis: Long = DROP_GRACE_MILLIS,
) {

    /** 进入 [LinkPhase.Down] 的墙钟时刻；不在 Down 时为 null。 */
    private var downSince: Long? = null

    /**
     * 已经就哪一类问题发过提醒；null = 没发过。
     *
     * 记**哪一类**而不只是「发过没有」，是因为「等一会儿可能会好」与「永远不会好」
     * 是两条不同的消息，而后者可能紧接着前者出现（重试几轮之后服务端回 403 →
     * 载波从 `Reconnecting` 变成 `Unpaired`）。只看布尔的话那次升级会被静默掉，
     * 用户就一直在等一个**永远不会发生**的重连——那条挂着的通知还在说「App 会自己重连」。
     */
    private var warnedPhase: LinkPhase? = null

    /** 自上次恢复以来是否进过 Down（含没到宽限的）。补账看这个，不看 [warnedPhase]。 */
    private var wasDown = false

    /** 当前是否挂着一个「连不上了」的提醒。 */
    val isWarning: Boolean get() = warnedPhase != null

    /**
     * 喂一个新状态，得到要做的事。
     *
     * 返回值可能同时需要「撤提醒」与「补账」，所以恢复时若两种情况都成立，
     * 用 [LinkAction.Clear] 表达前半段——协调器在 `Clear` 之后总会跑一次补账
     * （补账的触发条件是「刚变成 Up」，不是这个返回值）。
     *
     * @param nowMillis 当前墙钟（由调用方给，便于测试）。
     */
    fun onCarrier(phase: LinkPhase, nowMillis: Long): LinkAction {
        if (!phase.isProblem) {
            val hadWarn = warnedPhase != null
            downSince = null
            warnedPhase = null
            return if (hadWarn) LinkAction.Clear else LinkAction.Silent
        }

        wasDown = true

        // 配对失效没有「再等等」的余地：立刻提醒，不等宽限。
        if (phase == LinkPhase.Unpaired) {
            downSince = null
            // 只在「已经就配对被撤销提醒过」时静默。若之前提醒的是「会自己重连」，
            // 这里必须再提醒一次——否则用户守着一条承诺不兑现的通知，一直等下去。
            if (warnedPhase == LinkPhase.Unpaired) return LinkAction.Silent
            warnedPhase = LinkPhase.Unpaired
            return LinkAction.Warn(LinkPhase.Unpaired)
        }

        // 已经提醒过：不重复（否则每次退避失败都弹一条）。
        if (warnedPhase != null) return LinkAction.Silent

        val since = downSince ?: nowMillis.also { downSince = it }
        if (nowMillis - since < dropGraceMillis) return LinkAction.Silent

        warnedPhase = LinkPhase.Down
        return LinkAction.Warn(LinkPhase.Down)
    }

    /**
     * 刚变成 [LinkPhase.Up] 时调：要不要补账。
     *
     * 判据是 [wasDown]（断过，哪怕没到宽限）**而不是** [warned]：断 30 秒没提醒
     * 就恢复了，那 30 秒的事件一样丢了，一样要补——只是不该为此打扰用户。
     *
     * 调用后 [wasDown] 清零，避免同一次恢复被补两遍（`Connected` 的
     * `sinceMillis` 变化也会让状态流再发一次）。
     */
    fun takeSettle(): Boolean {
        if (!wasDown) return false
        wasDown = false
        return true
    }

    /** 清空全部状态（换主机时用）。 */
    fun reset() {
        downSince = null
        warnedPhase = null
        wasDown = false
    }

    /** 供测试与诊断读的「从什么时候开始断的」。 */
    fun downSinceMillis(): Long? = downSince

    companion object {
        /** 断开多久之后才提醒（见构造参数 KDoc 的取值理由）。 */
        const val DROP_GRACE_MILLIS: Long = 90_000L
    }
}
