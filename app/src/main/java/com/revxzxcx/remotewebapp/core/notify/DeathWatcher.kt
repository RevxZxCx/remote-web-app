package com.revxzxcx.remotewebapp.core.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 「上次进程是怎么没的」的探测器（R056 / Q3）。
 *
 * ## 它做的唯一一件事
 * App 冷启动时读一次系统的退出历史 → 判断该不该提示 → **立刻推进基线** → 把要说的那一条
 * 挂到 [pending] 上，等界面来取。取走即清（`consume()`），所以同一件事只会提示一次。
 *
 * ## 为什么在构造时就 probe，而不是等 UI 起来
 * 基线必须在**这一次启动**就写掉。如果等用户进了首页再判，中间这段时间里若进程又被杀
 * （一加上完全可能），那这次的历史记录会和上次重叠，下一次启动提示的仍是旧事。
 * 读一次 binder 调用的开销（20 条记录）远小于一次 Compose 首帧。
 *
 * ## 为什么不是 `object`
 * 单测要构造多个互不干扰的实例，而且它的状态（基线 / 后台时刻）存在 `SharedPreferences` 里，
 * 每个测试用独立的 `Application` 上下文即可。用 `object` 会让测试之间互相污染。
 *
 * @param settings 偏好（基线、上次进后台时刻都在这里）。
 * @param reader 读系统退出历史的手；单测可换成返回固定数据的假实现。
 * @param clock 当前时刻；单测可注入固定值。
 */class DeathWatcher(
    private val settings: NoticeSettings,
    private val reader: ExitSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _pending = MutableStateFlow<DeathNotice?>(null)

    /**
     * 待提示的那一条；`null` 表示没什么要说的。
     *
     * UI 用 `LaunchedEffect` 收集它即可——取走由 [consume] 负责，**不是**由收集器负责，
     * 因为 Compose 可能因为重组而重复收集，而「提示过一次」这件事必须在数据层定下来。
     */
    val pending: StateFlow<DeathNotice?> = _pending

    /**
     * 冷启动时调一次。
     *
     * 顺序不能变：**先算基线、再写、最后才发布**。反过来的话，若写盘抛异常
     * （`SharedPreferences` 在极端情况下会），下次启动会重弹同一条；
     * 而先写后发布，写失败时这一次就干脆不提示——少说一次远比重复说好。
     */
    fun probe() {
        val exits = reader.read()
        val baseline = settings.deathBaselineAt
        val notice = pickDeathNotice(
            exits = exits,
            lastSeenAt = baseline,
            backgroundedAt = settings.backgroundedAt.takeIf { it > 0L },
        )
        // 首次（baseline == 0）也要写：否则「第一次装完」永远建不出基线，
        // 之后每次启动都会因为 `lastSeenAt <= 0` 一路返回 null，提示永远不会出现。
        val next = baselineFor(exits)
        if (next > baseline) settings.deathBaselineAt = next
        _pending.value = notice
    }

    /**
     * 记下「刚刚进了后台」。
     *
     * 由 `DshApplication` 的 `onStop` 调用。写的是当前时刻，**不判断是否已经写过**：
     * 每次进后台都要刷新，否则「先进后台 → 回前台 → 再进后台 → 被杀」这条路上，
     * 死亡时刻会早于那个陈旧的进后台时刻，判据 `timestamp >= backgroundedAt` 仍然成立
     * （不误伤），但反过来「先进后台 → 被杀 → 重启 → 立刻进后台 → 出问题」这条路上，
     * 陈旧值会把新一次死亡错判成旧的。刷新一次的成本是一次 `apply()`。
     */
    fun onBackgrounded() {
        settings.backgroundedAt = clock()
    }

    /**
     * 用户看过了（点了按钮 / 关掉卡片）。
     *
     * 只清内存里的待提示项，**不回退基线**——基线记的是「这批退出记录已经处理过」，
     * 与用户是否点了按钮无关。回退它会让同一条死亡在下次启动时再弹一次。
     */
    fun consume() {
        _pending.value = null
    }
}
