package com.revxzxcx.remotewebapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C9AFF),
    onPrimary = Color(0xFF0A1420),
    background = Color(0xFF0F1B2A),
    surface = Color(0xFF152436),
    onBackground = Color(0xFFE6EDF5),
    onSurface = Color(0xFFE6EDF5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
)

/**
 * 应用主题。配色取自原型 `docs/原型.html` 的深色方案，默认跟随系统深浅色。
 */
@Composable
fun DshAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
