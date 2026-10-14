package com.revxzxcx.remotewebapp

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.revxzxcx.remotewebapp.ui.AppRoot
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme

/**
 * 唯一 Activity。
 *
 * 屏幕切换用 [AppRoot] 内部的 Compose 状态机（配对 → 工作区 → …），不引入 navigation-compose：
 * 本期只有 4~6 个屏、且没有深链接需求，`when` + `rememberSaveable` 足够，还少一个依赖。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // `enableEdgeToEdge()` 让窗口不再为输入法收缩（Android 15 的 EDGE_TO_EDGE_ENFORCED
        // 连 `adjustResize` 都一并失效），而 Compose 的 `Modifier.imePadding()` 在
        // MuMu + 搜狗输入法上实测拿不到 inset（见 ImeInsets 的 KDoc）。
        // 所以在 DecorView 上挂框架层监听，把原生 ime inset 桥进 Compose。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            ImeInsets.update(ime)
            insets
        }

        val container = (application as DshApplication).container
        setContent {
            // 主题偏好必须在 setContent 之外同步读一次初值（冷启动第一帧就生效），
            // 之后交给 StateFlow 驱动重组。M5-9。
            val themeMode by container.themePreferences.mode.collectAsState()
            DshAssistantTheme(themeMode = themeMode) {
                AppRoot(container = container)
            }
        }
    }
}
