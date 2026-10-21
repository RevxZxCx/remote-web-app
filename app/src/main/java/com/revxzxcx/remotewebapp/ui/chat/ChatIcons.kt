package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本文件里几个 Material 图标集（`material-icons-core` 1.7.8）**没有**的字形。
 *
 * 决策 D74 已经就同一问题拍过一次板：只依赖 `material-icons-core`（49 枚图标），
 * 需要额外字形就按 Material 官方路径手绘，不引入十几 MB 的 `material-icons-extended`。
 * `CopyIconButton.kt` 的 `CopyIcon` 用的就是这个办法。
 *
 * 下面四个都遵循同一约定：24×24 viewport、`fill` 单一颜色（由 `Icon(tint)` 覆盖）、
 * 路径取自 Material Symbols 的官方字形（`target` / `pause` / `chevron_up` / `chevron_down`）。
 */

/**
 * 目标图标（Material `target`：三层同心圆环 + 中心点）。
 *
 * 为什么不用 `Icons.Default.Star` / `Flag` 之类现成的：目标在 DSH 里就是这个靶心字形
 * （`IconGoalOutlineRegular`），换一个会与桌面端不一致。
 */
internal val GoalIcon: ImageVector = ImageVector.Builder(
    name = "GoalTarget",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // 外环
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        reflectiveCurveTo(6.48f, 22f, 12f, 22f)
        reflectiveCurveTo(22f, 17.52f, 22f, 12f)
        reflectiveCurveTo(17.52f, 2f, 12f, 2f)
        close()
        moveTo(12f, 20f)
        curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
        reflectiveCurveTo(7.59f, 4f, 12f, 4f)
        reflectiveCurveTo(20f, 7.59f, 20f, 12f)
        reflectiveCurveTo(16.41f, 20f, 12f, 20f)
        close()
    }
    // 中环
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 8f)
        curveTo(9.79f, 8f, 8f, 9.79f, 8f, 12f)
        reflectiveCurveTo(9.79f, 16f, 12f, 16f)
        reflectiveCurveTo(16f, 14.21f, 16f, 12f)
        reflectiveCurveTo(14.21f, 8f, 12f, 8f)
        close()
        moveTo(12f, 14f)
        curveTo(10.9f, 14f, 10f, 13.1f, 10f, 12f)
        reflectiveCurveTo(10.9f, 10f, 12f, 10f)
        reflectiveCurveTo(14f, 10.9f, 14f, 12f)
        reflectiveCurveTo(13.1f, 14f, 12f, 14f)
        close()
    }
    // 中心点
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 11f)
        curveTo(11.45f, 11f, 11f, 11.45f, 11f, 12f)
        reflectiveCurveTo(11.45f, 13f, 12f, 13f)
        reflectiveCurveTo(13f, 12.55f, 13f, 12f)
        reflectiveCurveTo(12.55f, 11f, 12f, 11f)
        close()
    }
}.build()

/**
 * 暂停图标（Material `pause`：两个圆角竖条）。
 *
 * `Icons.Default.Pause` 不在 core 图标集里（已用 `jar tf` 核实过 49 枚清单）。
 */
internal val PauseIcon: ImageVector = ImageVector.Builder(
    name = "Pause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 5f)
        horizontalLineTo(10.5f)
        verticalLineTo(19f)
        horizontalLineTo(7f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(13.5f, 5f)
        horizontalLineTo(17f)
        verticalLineTo(19f)
        horizontalLineTo(13.5f)
        close()
    }
}.build()

/** 向上箭头（Material `chevron_up`），用于「收起」。 */
internal val ChevronUpIcon: ImageVector = ImageVector.Builder(
    name = "ChevronUp",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 8f)
        lineTo(18f, 14f)
        lineTo(16.6f, 15.4f)
        lineTo(12f, 10.8f)
        lineTo(7.4f, 15.4f)
        lineTo(6f, 14f)
        close()
    }
}.build()

/** 向下箭头（Material `chevron_down`），用于「展开」。 */
internal val ChevronDownIcon: ImageVector = ImageVector.Builder(
    name = "ChevronDown",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 16f)
        lineTo(6f, 10f)
        lineTo(7.4f, 8.6f)
        lineTo(12f, 13.2f)
        lineTo(16.6f, 8.6f)
        lineTo(18f, 10f)
        close()
    }
}.build()

/**
 * 插队图标（Material `bolt` / 斜向闪电）。
 *
 * 语义是「让这条插到当前轮里执行」，所以用闪电而不是箭头——箭头会被读成「上移一位」，
 * 而 `session/steer` 的语义是**打断当前轮、把它先做**。
 */
internal val SteerIcon: ImageVector = ImageVector.Builder(
    name = "SteerBolt",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(13f, 2f)
        lineTo(5f, 13.5f)
        horizontalLineTo(11f)
        lineTo(10f, 22f)
        lineTo(18f, 10.5f)
        horizontalLineTo(12f)
        close()
    }
}.build()


/**
 * 停止生成（Material `stop`：圆角方块）。
 *
 * 为什么是方块而不是 × 或 ■ 字符：方块在圆形按钮里是「停止」的通用字形，且它是实心块，
 * 40dp 按钮上任何字号下都清晰；用文字字符会随字体度量漂移（见 R037 的教训）。
 */
internal val StopIcon: ImageVector = ImageVector.Builder(
    name = "StopSquare",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(8f, 7f)
        horizontalLineTo(16f)
        curveTo(16.55f, 7f, 17f, 7.45f, 17f, 8f)
        verticalLineTo(16f)
        curveTo(17f, 16.55f, 16.55f, 17f, 16f, 17f)
        horizontalLineTo(8f)
        curveTo(7.45f, 17f, 7f, 16.55f, 7f, 16f)
        verticalLineTo(8f)
        curveTo(7f, 7.45f, 7.45f, 7f, 8f, 7f)
        close()
    }
}.build()

/**
 * 右向箭头（侧栏「关闭」）。
 *
 * 自绘而不是 `Icons.Filled.KeyboardArrowRight`：后者在 `material-icons-core` 里是
 * 一个带外框感的粗箭头，与顶栏其它细线图标不是同一套笔画粗细；这里用与
 * [ChevronDownIcon] 同一条折线旋转 90°得到，族内一致。
 */
internal val ChevronRightIcon: ImageVector = ImageVector.Builder(
    name = "ChevronRight",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(9.3f, 5.3f)
        lineTo(16f, 12f)
        lineTo(9.3f, 18.7f)
        lineTo(7.9f, 17.3f)
        lineTo(13.2f, 12f)
        lineTo(7.9f, 6.7f)
        close()
    }
}.build()

/** 文件夹（侧栏文件列表里的一级目录）。`material-icons-core` 的 49 枚里没有文件夹。 */
internal val FolderIcon: ImageVector = ImageVector.Builder(
    name = "Folder",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6.2f)
        curveTo(3f, 5.54f, 3.54f, 5f, 4.2f, 5f)
        horizontalLineTo(9f)
        lineTo(11f, 7f)
        horizontalLineTo(19.8f)
        curveTo(20.46f, 7f, 21f, 7.54f, 21f, 8.2f)
        verticalLineTo(17.8f)
        curveTo(21f, 18.46f, 20.46f, 19f, 19.8f, 19f)
        horizontalLineTo(4.2f)
        curveTo(3.54f, 19f, 3f, 18.46f, 3f, 17.8f)
        close()
    }
}.build()

/** 文件（侧栏文件列表里的普通条目）。 */
internal val FileIcon: ImageVector = ImageVector.Builder(
    name = "File",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 3f)
        horizontalLineTo(14.2f)
        lineTo(19f, 7.8f)
        verticalLineTo(21f)
        horizontalLineTo(6f)
        close()
        moveTo(13.4f, 4.9f)
        verticalLineTo(8.6f)
        horizontalLineTo(17.1f)
        close()
    }
}.build()
