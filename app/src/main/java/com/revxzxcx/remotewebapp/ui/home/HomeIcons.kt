package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 首页用到、而 `material-icons-core` 1.7.8（49 枚）里**没有**的两个字形。
 *
 * 沿用决策 D74 与 `ui/chat/ChatIcons.kt` 的既有做法：24×24 viewport、`fill` 单一颜色
 * （由 `Icon(tint)` 覆盖）、路径取自 Material Symbols 官方字形，不引入十几 MB 的
 * `material-icons-extended`。
 */

/**
 * 归档箱（Material `archive`：盖子 + 提手 + 箱体）。
 *
 * 菜单项用图标而不是只靠文字，是因为 R033 之前的长按菜单只有两行纯文字，
 * 用户扫视时要逐字读完才知道两个选项分别是什么。
 */
internal val ArchiveIcon: ImageVector = ImageVector.Builder(
    name = "ArchiveBox",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(20.54f, 5.23f)
        lineTo(19.15f, 3.55f)
        curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
        horizontalLineTo(6f)
        curveTo(5.53f, 3f, 5.12f, 3.21f, 4.84f, 3.55f)
        lineTo(3.46f, 5.23f)
        curveTo(3.17f, 5.57f, 3f, 6.02f, 3f, 6.5f)
        verticalLineTo(19f)
        curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
        horizontalLineTo(19f)
        curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
        verticalLineTo(6.5f)
        curveTo(21f, 6.02f, 20.83f, 5.57f, 20.54f, 5.23f)
        close()
        moveTo(12f, 17.5f)
        lineTo(6.5f, 12f)
        horizontalLineTo(10f)
        verticalLineTo(10f)
        horizontalLineTo(14f)
        verticalLineTo(12f)
        horizontalLineTo(17.5f)
        lineTo(12f, 17.5f)
        close()
        moveTo(5.12f, 5f)
        lineTo(5.93f, 4f)
        horizontalLineTo(17.93f)
        lineTo(18.87f, 5f)
        horizontalLineTo(5.12f)
        close()
    }
}.build()

/**
 * 图钉（Material `push_pin`），用于「置顶 / 取消置顶」。
 *
 * 用图钉而不是星标：置顶的语义是「钉在列表最上面」，星标会被读成收藏。
 */
internal val PinIcon: ImageVector = ImageVector.Builder(
    name = "PushPin",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 9f)
        verticalLineTo(4f)
        horizontalLineTo(17f)
        curveTo(17.55f, 4f, 18f, 3.55f, 18f, 3f)
        reflectiveCurveTo(17.55f, 2f, 17f, 2f)
        horizontalLineTo(7f)
        curveTo(6.45f, 2f, 6f, 2.45f, 6f, 3f)
        reflectiveCurveTo(6.45f, 4f, 7f, 4f)
        horizontalLineTo(8f)
        verticalLineTo(9f)
        curveTo(8f, 10.66f, 6.66f, 12f, 5f, 12f)
        verticalLineTo(14f)
        horizontalLineTo(10.97f)
        verticalLineTo(21f)
        lineTo(11.97f, 22f)
        lineTo(12.97f, 21f)
        verticalLineTo(14f)
        horizontalLineTo(19f)
        verticalLineTo(12f)
        curveTo(17.34f, 12f, 16f, 10.66f, 16f, 9f)
        close()
    }
}.build()
