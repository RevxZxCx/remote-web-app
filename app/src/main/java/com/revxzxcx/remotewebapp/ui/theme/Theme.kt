package com.revxzxcx.remotewebapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5AA2FF),
    onPrimary = Color(0xFF08131F),
    primaryContainer = Color(0xFF1B3E63),
    onPrimaryContainer = Color(0xFFD3E5FF),
    secondary = Color(0xFF7FA8D4),
    onSecondary = Color(0xFF0C1A28),
    secondaryContainer = Color(0xFF1D3348),
    onSecondaryContainer = Color(0xFFC9DDF2),
    background = Color(0xFF0B1420),
    onBackground = Color(0xFFE4ECF6),
    surface = Color(0xFF111C2B),
    onSurface = Color(0xFFE4ECF6),
    surfaceVariant = Color(0xFF1A2942),
    onSurfaceVariant = Color(0xFFA6BDD8),
    // 描边色：卡片去掉重背景后靠这一层分隔（R016）。
    outline = Color(0xFF31485F),
    outlineVariant = Color(0xFF22334A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0F0C),
    errorContainer = Color(0xFF48211F),
    onErrorContainer = Color(0xFFFFDAD6),
    // R017：见 LightColors 的同名注释。琥珀 = 重连等待。
    tertiary = Color(0xFFF2C14E),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFFE7B0),
)

/**
 * 「正在生成」的语义色（R017）。
 *
 * 之前三处手写 `Color(0xFF4ADE80)`（HomeScreen 的工作区徽标、会话行状态点、
 * 抽屉行状态点），浅色主题下这个绿压在 #FFFFFF 上偏灰、不够「活着」。
 * 收成一个 [CompositionLocal]，深浅两套各取各的值，全 App 只有一种「运行绿」。
 *
 * 用 CompositionLocal 而不是 `MaterialTheme.colorScheme`：`ColorScheme` 是
 * material3 的固定结构，加自定义字段得包一层子类，反而更难维护。
 */
internal val LocalStatusRunning = staticCompositionLocalOf { Color(0xFF4ADE80) }

/**
 * 「上一轮跑挂了」的语义色（R049）。
 *
 * 不复用 `colorScheme.error`：那是给**文字与边框**用的错误色，深色主题下是
 * `#FFB4AB`（偏粉的浅色），画一个 8dp 的状态点会显得发灰、不够「报警」。
 * 这里要的是**深色主题下也亮得起来**的红，所以深浅两套各给一个值：
 * 深色下用 `#FF6B6B`（在 `#111C2B` 上约 4.9:1），浅色下用 `#D93025`
 * （在 `#FFFFFF` 上约 4.7:1，且比纯红更沉稳）。
 *
 * 与 [LocalStatusRunning] 同样用 CompositionLocal 而不是 `ColorScheme`，理由见上。
 */
internal val LocalStatusError = staticCompositionLocalOf { Color(0xFFFF6B6B) }

/**
 * 「需要你看一眼」的语义色（R049）：有提问等回答、或有排队消息没发出去。
 *
 * 取琥珀而不是黄：`#F2C14E` 在白底上只有 1.9:1 左右，做状态点太淡，
 * 所以深色用 `#F5B942`、浅色用 `#B26A00`（后者在 `#FFFFFF` 上约 4.6:1）。
 */
internal val LocalStatusAttention = staticCompositionLocalOf { Color(0xFFF5B942) }

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF10305C),
    secondary = Color(0xFF4E6B87),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8F6),
    onSecondaryContainer = Color(0xFF1B3247),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF182430),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF182430),
    surfaceVariant = Color(0xFFE9EFF7),
    onSurfaceVariant = Color(0xFF45586D),
    outline = Color(0xFFB9C8D9),
    outlineVariant = Color(0xFFDCE5EF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    // R017：tertiary 两个主题都没设置过，落到 Material 默认紫（深色 #EFB8FF /
    // 浅色 #7D5260），与整套蓝灰 palette 相撞。现在语义明确：
    // tertiary = 「等待 / 进行中」的琥珀色，ConnectionBanner 的重连态用它。
    tertiary = Color(0xFFF2C14E),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFFE7B0),
)

/**
 * 应用主题。默认跟随系统深浅色，可在设置里手动覆盖（M5-9）。
 *
 * [themeMode] 来自 [com.revxzxcx.remotewebapp.core.prefs.ThemePreferences]。
 * 三档语义在 [ThemeMode] 上；这里只负责把三档折叠成一个布尔值。
 *
 * ## 系统栏图标也要跟着 [themeMode] 走（R007d P2-3）
 *
 * `enableEdgeToEdge()` 对两根栏用的是 `SystemBarStyle.auto(透明, 透明)`，图标深浅跟随
 * **系统**夜间模式。于是「系统深色 + ThemeMode.LIGHT」时界面是浅色、状态栏图标仍是浅色
 * → 几乎不可见；「系统浅色 + DARK」反向同理。用户只要手动选过主题且与系统不一致就中招。
 *
 * 所以这里必须把 `darkTheme` 回写到窗口：深色主题 → 浅色图标（`isAppearanceLight*Bars = false`）。
 *
 * ## R016：排版 / 圆角 / 动效也在这里统一挂上
 *
 * [AppTypography] 与 [AppShapes] 是全局唯一的一份。之前散落在各处的字号行高
 * 靠的是 Material3 默认值，而那套是按拉丁语调的，中文多行消息会被压成一片。
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
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalStatusRunning provides if (darkTheme) Color(0xFF4ADE80) else Color(0xFF15803D),
            LocalStatusError provides if (darkTheme) Color(0xFFFF6B6B) else Color(0xFFD93025),
            LocalStatusAttention provides if (darkTheme) Color(0xFFF5B942) else Color(0xFFB26A00),
        ) {
            content()
        }
    }
}
