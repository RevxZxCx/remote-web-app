package com.revxzxcx.remotewebapp.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.revxzxcx.remotewebapp.core.session.AgentPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 新会话默认模式的记忆（R055 Q3）。
 *
 * 用户拍板「记住上次选择，但**按主机分开记**」（原型 `docs/R055-新增会话效果图.html`
 * 第 8 节 Q3）。按主机分开的理由是真实的：一台机器常用极简模式做对比测试、另一台
 * 常用创造模式写插件，共用一个值会让每次切主机后都得手动改回来。
 *
 * key 用 [hostKey]，它由调用方从 `ActiveLink` 的 `deviceId` 派生——**不用 baseUrl**：
 * 同一台桌面端换隧道地址（`127.0.0.1:19387` ↔ `zcsb.ltd`）在凭证簿里是同一行，
 * 但 baseUrl 变了，用 baseUrl 当 key 会让「换了个入口」变成「换了个偏好」。
 *
 * 用 SharedPreferences 而不是 DataStore：理由与 [ThemePreferences] 相同，只有几个短字符串，
 * 不值得为它引依赖。
 *
 * 线程模型：[presetFor] 同步读（内存缓存），[remember] 同步写盘（`apply()` 异步落盘）。
 * 整个类在 UI 线程调用即可，不做 suspend。
 */
class PresetPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * 内存镜像。
     *
     * 不直接每次读 `prefs.getString`：`SharedPreferences` 的读会拿锁，而这个值在新建会话页
     * 每次重组时都可能被读（胶囊文案）。镜像用 [StateFlow] 是为了让已经开始订阅的界面
     * 在别的入口改了偏好后也能同步——虽然当前只有一个入口，但保持与 [ThemePreferences]
     * 同一套形态，省得以后加第二个入口时又改一遍。
     */
    private val state = MutableStateFlow<Map<String, AgentPreset>>(readAll())

    /** 观察整张偏好表（测试与将来的设置页用）。 */
    val presets: StateFlow<Map<String, AgentPreset>> = state.asStateFlow()

    /** 取某台主机上次选的模式；没有记录就用 [AgentPreset.DEFAULT]。 */
    fun presetFor(hostKey: String): AgentPreset =
        state.value[hostKey] ?: AgentPreset.DEFAULT

    /** 记住这台主机这次选的模式。 */
    fun remember(hostKey: String, preset: AgentPreset) {
        if (hostKey.isBlank()) return
        prefs.edit { putString(KEY_PREFIX + hostKey, preset.wire) }
        state.value = state.value + (hostKey to preset)
    }

    private fun readAll(): Map<String, AgentPreset> {
        val out = LinkedHashMap<String, AgentPreset>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val wire = value as? String ?: continue
            // 未知 wire 值不写进表：它意味着 App 降级过或被别的版本写过，
            // 读的时候退回默认档即可（`AgentPreset.fromWire` 已保证），
            // 但留在表里会让「这台有没有偏好」的判断变得含糊。
            val preset = AgentPreset.entries.firstOrNull { it.wire == wire } ?: continue
            out[key.removePrefix(KEY_PREFIX)] = preset
        }
        return out
    }

    private companion object {
        const val FILE_NAME = "dsh-agent-preset"

        /**
         * 每条偏好一个 key（`preset_<hostKey>`），而不是把所有主机塞进一个 JSON 字符串。
         *
         * 理由：主机数量会随用户添加而增长，整串重写需要先读出旧的再合并，多一条
         * 「读-改-写」的竞态路径。一 key 一值让 SharedPreferences 自己管合并。
         */
        const val KEY_PREFIX = "preset_"
    }
}
