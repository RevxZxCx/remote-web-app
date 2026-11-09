package com.revxzxcx.remotewebapp.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 后台连接（前台服务）的三个开关（R057）。
 *
 * ## 为什么按主机分开记
 * 与 R055 的模式偏好同构：用户可能只希望某台常连的桌面端保持后台连接，
 * 而另一台偶尔才用的机器不值得一直挂一条常驻通知。用 hostId 做 key 后缀
 * （`keep_alive_<hostId>`），换主机就换一套开关。
 *
 * 没有 hostId（还没配对 / 已退配对）时用 [NO_HOST]：此时服务本来也不会启动，
 * 但要保证 `getter` 不抛异常——设置页在配对页也可能被打开。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 * 与 `core/prefs/ThemePreferences.kt` / `core/notify/NoticeSettings.kt` 同一个理由：
 * 只有三个布尔值和一个字符串，为它引一个依赖不划算。
 *
 * ## 三个开关的默认值（以及为什么不同）
 * - [foregroundEnabled] **默认关**：会多一条常驻通知，属于「用户没要求就不做」的事。
 * - [autoRecover] **默认开**：纯收益（不响不弹，只把服务拉回来），
 *   「多一条常驻通知」这个代价由上一项承担，不需要在第二项上再问一次。
 * - [bootStart] **默认关**：开机自启是额外的后台行为，默认开会让用户在
 *   完全没意识到的情况下多出一个开机常驻项。
 */
class ServicePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 「保持后台连接」总开关。关着时服务不该被任何路径启动。 */
    fun foregroundEnabled(hostKey: String): Boolean =
        prefs.getBoolean(key(KEY_FOREGROUND, hostKey), false)

    fun setForegroundEnabled(hostKey: String, enabled: Boolean) {
        prefs.edit { putBoolean(key(KEY_FOREGROUND, hostKey), enabled) }
    }

    /**
     * 「服务停了自动恢复」。
     *
     * 默认 `true`：它是纯收益（静默拉起，不响不弹），而代价（多一条常驻通知）
     * 已经由总开关承担了。关掉它的唯一理由是「不想让它在背后自己起来」，
     * 此时改发一条通知让用户自己决定（见 [planRecovery]）。
     */
    fun autoRecover(hostKey: String): Boolean =
        prefs.getBoolean(key(KEY_AUTO_RECOVER, hostKey), true)

    fun setAutoRecover(hostKey: String, enabled: Boolean) {
        prefs.edit { putBoolean(key(KEY_AUTO_RECOVER, hostKey), enabled) }
    }

    /** 「开机后自动连接」。默认关。 */
    fun bootStart(hostKey: String): Boolean =
        prefs.getBoolean(key(KEY_BOOT_START, hostKey), false)

    fun setBootStart(hostKey: String, enabled: Boolean) {
        prefs.edit { putBoolean(key(KEY_BOOT_START, hostKey), enabled) }
    }

    /**
     * 首次打开「保持后台连接」时那个确认框是否已经看过。
     *
     * 与开关本身分开记：用户可能关掉开关再看一次说明，也可能看过了还继续关着。
     * 只有「没看过」才需要弹。
     */
    fun confirmSeen(hostKey: String): Boolean =
        prefs.getBoolean(key(KEY_CONFIRM_SEEN, hostKey), false)

    fun setConfirmSeen(hostKey: String, seen: Boolean) {
        prefs.edit { putBoolean(key(KEY_CONFIRM_SEEN, hostKey), seen) }
    }

    /**
     * 上一次启动失败的原因（`""` = 没有失败）。
     *
     * ## 为什么要记下来
     * `ForegroundServiceStartNotAllowedException` / `SecurityException` 只在启动那一刻
     * 抛一次，而那时用户很可能不在 App 里（开机自启、自动恢复）。不落盘的话，
     * 设置页那个开关会一直亮着，用户以为功能开着、其实是关的——
     * **这种「显示与实际不符」正是 R056-B1 那条缺陷的同一个病**。
     *
     * 所以启动失败时：把开关关掉 + 把原因写在这里，设置页读它显示一行说明。
     * 下次启动成功时清空。
     */
    fun lastFailure(hostKey: String): String =
        prefs.getString(key(KEY_LAST_FAILURE, hostKey), "").orEmpty()

    fun setLastFailure(hostKey: String, reason: String) {
        prefs.edit { putString(key(KEY_LAST_FAILURE, hostKey), reason) }
    }

    /**
     * 上一次失败**是否该挡住自动重试**（R057 审查 P2-2）。
     *
     * ## 为什么不能只看 `lastFailure` 非空
     * 失败分两种，对重试的含义完全相反：
     *
     * - **用户改不了**（后台启动被拒、类型没声明、ROM 拒绝）：重试一次还是失败，
     *   而且每来一帧载波就重试一次等于无声循环，只会让用户看到电量和日志在涨。→ 挡住。
     * - **用户能修**（通知未授权）：用户去系统里点一下授权就好了。挡住的话，
     *   他授权完回来，`ensureRunning()` 每次都在这里提前返回，服务永远起不来，
     *   只能靠「关掉开关再打开」这种没人会想到的动作才恢复。→ 不挡。
     *
     * 判据就是失败发生那一刻 `noticeUsable()` 的值：通知不可用引起的失败都是可修的。
     * 与 `lastFailure` 分开存而不是从文案里猜——猜文案会在改一次措辞之后静默失效。
     */
    fun lastFailureBlocksRetry(hostKey: String): Boolean =
        prefs.getBoolean(key(KEY_LAST_FAILURE_FATAL, hostKey), false)

    fun setLastFailureBlocksRetry(hostKey: String, blocks: Boolean) {
        prefs.edit { putBoolean(key(KEY_LAST_FAILURE_FATAL, hostKey), blocks) }
    }

    /**
     * 上一次确定过的 hostKey（R057 审查 P1-1）。
     *
     * ## 为什么必须落盘一份
     * 三个开关都是按 hostKey 分开记的，而 hostKey 的唯一来源是
     * `AppContainer.linkStore.current().activeHost?.hostId`——它要等
     * `bootstrapOnce()` 在 IO 线程上读完凭证簿、建好连接、`publish()` 之后才有值。
     *
     * 开机自启这条路径上这个窗口是必然存在的：`Application.onCreate` 只是
     * `appScope.launch { bootstrap() }`（异步），而 `BootReceiver.onReceive` 紧接着
     * 就在主线程上问「要不要起服务」。那一刻 hostKey 还是 `""`，
     * `key()` 退化成 `keep_alive_none`，三个 getter 全返回默认值
     * （`foregroundEnabled=false`）→ `shouldStartOnBoot` 恒为 false，
     * **开机自启与 START_STICKY 恢复整条链路都是死的**。
     *
     * 所以这里落一份「上次见到的主机」。冷启动窗口里读它，`publish()` 之后
     * 由 `AppContainer` 写回真值；凭证簿真的空了就清掉，避免用一台已经删掉的
     * 主机名去启动一个没有连接的服务。
     */
    fun lastHostKey(): String = prefs.getString(KEY_LAST_HOST, "").orEmpty()

    fun setLastHostKey(hostKey: String) {
        if (hostKey.isBlank()) return
        if (prefs.getString(KEY_LAST_HOST, "") == hostKey) return
        prefs.edit { putString(KEY_LAST_HOST, hostKey) }
    }

    /** 凭证簿已经加载完、且确实没有可用主机时调用。 */
    fun clearLastHostKey() {
        if (!prefs.contains(KEY_LAST_HOST)) return
        prefs.edit { remove(KEY_LAST_HOST) }
    }

    private fun key(base: String, hostKey: String): String =
        "${base}_${hostKey.ifBlank { NO_HOST }}"

    companion object {
        /** 没有主机时用的 key 后缀。 */
        const val NO_HOST: String = "none"

        private const val FILE = "dsh-link-service"
        private const val KEY_FOREGROUND = "keep_alive"
        private const val KEY_AUTO_RECOVER = "auto_recover"
        private const val KEY_BOOT_START = "boot_start"
        private const val KEY_CONFIRM_SEEN = "confirm_seen"
        private const val KEY_LAST_FAILURE = "last_failure"

        /** 上一次失败是否属于「重试也没用」那一类（见 [lastFailureBlocksRetry]）。 */
        private const val KEY_LAST_FAILURE_FATAL = "last_failure_fatal"

        /** 上一次见过的主机（见 [lastHostKey]）：冷启动窗口里唯一的 hostKey 来源。 */
        private const val KEY_LAST_HOST = "last_host"
    }
}
