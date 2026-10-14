package com.revxzxcx.remotewebapp.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式（需求文档 M5「打磨：主题」）。
 *
 * 只有三档：跟随系统 / 浅色 / 深色。不做自定义配色——本期配色是写死的两套
 * （[com.revxzxcx.remotewebapp.ui.theme]），加自定义等于引入一整套取色器与
 * 对比度校验，收益不匹配。
 */
enum class ThemeMode {
    /** 跟随系统深浅色。 */
    SYSTEM,

    /** 强制浅色。 */
    LIGHT,

    /** 强制深色。 */
    DARK,
    ;

    /** 给设置页的选项标签。 */
    val label: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            LIGHT -> "浅色"
            DARK -> "深色"
        }
}

/**
 * 主题偏好的持久化。
 *
 * 用 SharedPreferences 而不是 DataStore：只有一字符串，不值得为它引一个依赖
 * （本期依赖策略见 `gradle/libs.versions.toml` 的注释）。
 *
 * 线程模型：[mode] 是 [StateFlow]，[setMode] 同步写盘再更新流。写盘发生在调用方线程，
 * 但 SharedPreferences.apply() 本身是异步落盘的，所以不会阻塞 UI；读取由
 * [com.revxzxcx.remotewebapp.MainActivity] 在 setContent 前同步做一次。
 */
class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(read())

    /** 当前主题模式。冷启动时读盘得到的初值，之后跟随 [setMode]。 */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** 立即切换主题模式并落盘。 */
    fun setMode(mode: ThemeMode) {
        // KTX 的 `edit {}`：等价于 `edit().put…().apply()`，但省掉一次
        // SharedPreferences.Editor 的显式分配。这里不需要 commit 的同步返回值。
        prefs.edit { putString(KEY_MODE, mode.name) }
        _mode.value = mode
    }

    private fun read(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        // 兜底：将来加档位时旧进程/别的进程写进来的未知值不能崩。
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    private companion object {
        const val FILE_NAME = "dsh-theme"
        const val KEY_MODE = "mode"
    }
}
