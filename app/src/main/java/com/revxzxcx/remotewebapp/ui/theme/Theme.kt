package com.revxzxcx.remotewebapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C9AFF),
    onPrimary = Color(0xFF0A1420),
    background = Color(0xFF0F1B2A),
    surface = Color(0xFF152436),
    onBackground = Color(0xFFE6EDF5),
    onSurface = Color(0xFFE6EDF5),
    // surfaceVariant 被 AttachmentImage 用作图片占位底色。不覆写会落回 M3 基线（偏紫灰），
    // 与整套蓝色 palette 色相不统一（R007d P3-8）。
    surfaceVariant = Color(0xFF1E3050),
    onSurfaceVariant = Color(0xFFA8C0DC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EBF6),
    onSurfaceVariant = Color(0xFF44586F),
)

/**
 * 应用主题。配色取自原型 `docs/原型.html` 的深色方案，默认跟随系统深浅色。
 *
 * [themeMode] 来自 [com.revxzxcx.remotewebapp.core.prefs.ThemePreferences]（M5-9）。
 * 三档语义在 [ThemeMode] 上；这里只负责把三档折叠成一个布尔值。
 *
 * ## 系统栏图标也要跟着 [themeMode] 走（R007d P2-3）
 *
 * `enableEdgeToEdge()` 对两根栏用的是 `SystemBarStyle.auto(透明, 透明)`，图标深浅跟随
 * **系统**夜间模式。于是「系统深色 + ThemeMode.LIGHT」时界面是浅色、状态栏图标仍是浅色
 * → 几乎不可见；「系统浅色 + DARK」反向同理。用户只要手动选过主题且与系统不一致就中招。
 *
 * 所以这里必须把 `darkTheme` 回写到窗口：深色主题 → 浅色图标（`isAppearanceLight*Bars = false`）。
 */
@Composable
fun DshAssistantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 放在这里而不是 MainActivity：主题是三档的唯一收口点，而系统栏外观必须与它同步。
    // 用 LocalView 而不是 activity.window，因为 Compose 里拿 View 不需要 Context 转型。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
