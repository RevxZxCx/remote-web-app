package com.revxzxcx.remotewebapp.core.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「用户点了通知，要打开哪条会话」的进程级信箱（R056）。
 *
 * ## 为什么需要这么一个对象
 *
 * 通知的点击目标由 `PendingIntent` 送进 [com.revxzxcx.remotewebapp.MainActivity]，
 * 但真正决定「打开哪个会话」的是 `AppRoot` 里那个 Compose 状态机。中间隔着三层：
 * Activity 的 `onCreate`/`onNewIntent` → 组合 → `Paired` 分支里的 `openSession`。
 *
 * 不能直接把参数塞给 `AppRoot`：AppRoot 的参数只从 `container` 来，加一个「启动参数」
 * 会让它在进程重建后与真实意图脱节（重建时 Activity 的 intent 还是旧的那个）。
 * 所以用一个只在进程内存在的信箱：Activity 往里投，AppRoot 取走即清。
 *
 * ## 为什么是 `MutableStateFlow` 而不是普通字段
 *
 * 冷启动那一刻 `AppRoot` 还没进 `Paired` 分支（要先读凭证簿、装连接），投信与取信
 * 之间隔着好几个挂起点。用普通字段就得在 AppRoot 里轮询或额外加一次 `LaunchedEffect`；
 * 用 StateFlow 则是「谁先谁后都对」——投信时没人收，等有人开始 collect 会立刻拿到当前值。
 */
object NoticeIntents {

    private val _pendingSessionId = MutableStateFlow<String?>(null)

    /**
     * 待打开的会话 id；null 表示没有。
     *
     * 只投递**会话 id**而不是「打开会话」事件：同一条通知连点两次时，
     * 第二次的值与第一次相同、StateFlow 不发射，正好避免了「已经在看这条会话、
     * 又被自己弹一次」。
     */
    val pendingSessionId: StateFlow<String?> = _pendingSessionId.asStateFlow()

    /** 由 Activity 在 `onCreate` / `onNewIntent` 里调用；空白 id 一律忽略。 */
    fun offer(sessionId: String?) {
        val trimmed = sessionId?.trim()
        if (trimmed.isNullOrEmpty()) return
        _pendingSessionId.value = trimmed
    }

    private val _pendingOpenSettings = MutableStateFlow(false)

    /**
     * 待打开「通知与后台保活」页；false 表示没有。
     *
     * 与会话信箱**分开两个流**：两者语义不同（一个是「去看那条会话」，一个是
     * 「去改后台连接开关」），合成一个 `String?` 就会让「空串」这种值承担两种含义，
     * `AppRoot` 里的判断也会变成「字符串非空就开会话」——而它根本不是会话 id。
     *
     * 用 `Boolean` 而不是 `Unit?` 的原因同 [pendingSessionId]：同一条通知连点两次时
     * 值与上次相同、StateFlow 不发射，正好避免重复跳转。
     */
    val pendingOpenSettings: StateFlow<Boolean> = _pendingOpenSettings.asStateFlow()

    /** 由 Activity 在 `onCreate` / `onNewIntent` 里调用。 */
    fun offerSettings() {
        _pendingOpenSettings.value = true
    }

    /**
     * 取走「打开设置页」的意图。
     *
     * 消费即清，理由同 [consume]：这是一次性交接，留着会让下次重组又把用户弹进设置页。
     */
    fun consumeSettings(): Boolean {
        val current = _pendingOpenSettings.value
        _pendingOpenSettings.value = false
        return current
    }

    /**
     * 取走并清空。
     *
     * 消费即清的理由与 R055 的 `pendingNewSessionPrompt` 相同：这是一次性交接，
     * 留着的话下一次重组/进程重建会再把用户弹进那条会话——而他早就自己返回首页了。
     */
    fun consume(): String? {
        val current = _pendingSessionId.value
        _pendingSessionId.value = null
        return current
    }

    /** 断开会话（退配对）时清空：这些 id 属于上一台主机，换台之后没有意义。 */
    fun clear() {
        _pendingSessionId.value = null
        _pendingOpenSettings.value = false
    }
}
