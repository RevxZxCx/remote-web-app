package com.revxzxcx.remotewebapp.core.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 通知相关的偏好与「问过没」（R056）。
 *
 * ## 为什么要自己记「问过没」
 * `shouldShowRequestPermissionRationale` 有三段状态：**从未请求过** = false、
 * **永久拒绝** = false、已授予 = false（权限在的时候它答的是「要不要解释」）。
 * 也就是说它**分不出「还没问」和「问过被拒且不再问」**。
 *
 * 没有这个标记，App 每次冷启动都会弹一次权限请求——而 Android 在「永久拒绝」之后
 * 会**直接返回拒绝、不弹任何界面**，用户看到的是「点按钮什么都没发生」。
 *
 * 所以：第一次主动请求前写 `requested = true`，之后一律走「去系统设置页」的引导，
 * 不再调 `requestPermissions`。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 * 与 `core/prefs/ThemePreferences.kt` 同一个理由：只有几个布尔值，为它引一个依赖
 * 不划算。构造时同步读一次（几个 boolean 的 XML 解析，比建 Room 库便宜得多）。
 */
class NoticeSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 曾经主动请求过通知权限。**一旦置 true 永不回退**（权限授予后也不需要再问）。 */
    var permissionRequested: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_PERMISSION_REQUESTED, value) }
        }

    /**
     * 用户把「任务结束」通知关掉了（App 内的开关，不是系统频道）。
     *
     * 与系统频道的区别：系统频道关了以后我们**发不出去**（`notify()` 静默失败），
     * 而 App 内开关关掉是我们**主动不发**——后者还能用来区分「用户嫌烦」与
     * 「用户不知情地被系统静音了」，设置页要分别提示。
     */
    var doneEnabled: Boolean
        get() = prefs.getBoolean(KEY_DONE_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_DONE_ENABLED, value) }
        }

    /**
     * 用户把「需要确认」通知关掉了。
     *
     * 默认 `true`。这一项关掉后审批请求不再发通知，**任务会一直卡在等待**——
     * 所以设置页要把它写成一句显式警告，而不是一个普通开关。
     */
    var askEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASK_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_ASK_ENABLED, value) }
        }

    /**
     * 锁屏上是否显示通知的详细内容（任务标题、失败原因、工具名）。
     *
     * 默认 `true`——这一类通知的价值就在「看一眼就知道哪条跑完了」，
     * 只显示「有新消息」等于没有。但任务内容可能是公司代码或私事，
     * 所以必须给一个关掉的出口；关掉后锁屏只显示「远程 Web 有新提醒」。
     *
     * 实现上落到 `NotificationCompat.VISIBILITY_SECRET`：系统会在锁屏上
     * 把整条通知的内容区涂黑，只留 App 名与时间，见 `NotificationCenter`。
     */
    var lockScreenDetail: Boolean
        get() = prefs.getBoolean(KEY_LOCK_SCREEN_DETAIL, true)
        set(value) {
            prefs.edit { putBoolean(KEY_LOCK_SCREEN_DETAIL, value) }
        }

    /**
     * App 内开关 + 系统频道状态合成「这条频道此刻是否真的可用」。
     *
     * `channelEnabled` 由调用方用 `NotificationManagerCompat.areNotificationsEnabled(channelId)`
     * 查（API 26+ 可用）。**不能只看 `areNotificationsEnabled()`**：它反映总开关，
     * 用户单独关掉一个频道时它仍是 true，我们照发、系统静默丢弃。
     */
    fun effectiveEnabled(channelId: String, channelEnabled: Boolean): Boolean {
        if (!channelEnabled) return false
        return when (channelId) {
            NoticeChannels.ASK -> askEnabled
            NoticeChannels.DONE -> doneEnabled
            else -> true
        }
    }

    /** 用户在设置页切换 App 内开关。 */
    fun setChannelEnabled(channelId: String, enabled: Boolean) {
        when (channelId) {
            NoticeChannels.ASK -> askEnabled = enabled
            NoticeChannels.DONE -> doneEnabled = enabled
            else -> Unit
        }
    }

    /** 读 App 内开关的原始值（不管系统频道）。 */
    fun appEnabled(channelId: String): Boolean = when (channelId) {
        NoticeChannels.ASK -> askEnabled
        NoticeChannels.DONE -> doneEnabled
        else -> true
    }

    private companion object {
        const val FILE = "dsh-notice"
        const val KEY_PERMISSION_REQUESTED = "permission_requested"
        const val KEY_DONE_ENABLED = "done_enabled"
        const val KEY_ASK_ENABLED = "ask_enabled"
        const val KEY_LOCK_SCREEN_DETAIL = "lock_screen_detail"
    }
}
