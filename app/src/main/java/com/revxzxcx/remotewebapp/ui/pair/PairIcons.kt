package com.revxzxcx.remotewebapp.ui.pair

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 配对页用到、而 `material-icons-core` 1.7.8（49 枚）里没有的字形。
 *
 * 沿用决策 D74 与 `ui/chat/ChatIcons.kt` 的既有做法：24×24 viewport、单一颜色
 * （由 `Icon(tint)` 覆盖）、不引入十几 MB 的 `material-icons-extended`。
 *
 * ⚠️ 这些字形用 **stroke 而不是 fill**：原型（`docs/R033-效果图.html` s2）里的图标全是
 * 线稿风格（`fill:none; stroke:currentColor; stroke-width:1.6`）。`material-icons-core`
 * 里那几枚可用的（`List` / `Refresh` / `Search`）是实心面，和线稿混在一起会让分段控件
 * 三格的字重看起来不一致——所以这里三个都自己画成线稿。`Icon(tint)` 的 ColorFilter
 * 对描边同样生效，不需要额外处理。
 */

/** 扫码：四角取景框 + 中线。 */
internal val ScanFrameIcon: ImageVector = ImageVector.Builder(
    name = "ScanFrame",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4f, 8f); verticalLineTo(5f); horizontalLineTo(7f)
        moveTo(20f, 8f); verticalLineTo(5f); horizontalLineTo(17f)
        moveTo(4f, 16f); verticalLineTo(19f); horizontalLineTo(7f)
        moveTo(20f, 16f); verticalLineTo(19f); horizontalLineTo(17f)
        moveTo(8f, 12f); horizontalLineTo(16f)
    }
}.build()

/** 链接：两个互扣的环（配对链接入口）。 */
internal val PairLinkIcon: ImageVector = ImageVector.Builder(
    name = "PairLink",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(10f, 13f)
        curveTo(11.9f, 14.9f, 15f, 14.9f, 16.9f, 13f)
        lineTo(19.9f, 10f)
        curveTo(21.8f, 8.1f, 21.8f, 5f, 19.9f, 3.1f)
        curveTo(18f, 1.2f, 14.9f, 1.2f, 13f, 3.1f)
        lineTo(12f, 4.1f)
        moveTo(14f, 11f)
        curveTo(12.1f, 9.1f, 9f, 9.1f, 7.1f, 11f)
        lineTo(4.1f, 14f)
        curveTo(2.2f, 15.9f, 2.2f, 19f, 4.1f, 20.9f)
        curveTo(6f, 22.8f, 9.1f, 22.8f, 11f, 20.9f)
        lineTo(12f, 19.9f)
    }
}.build()

/** 手工：三横线（末行更短）。 */
internal val ManualEntryIcon: ImageVector = ImageVector.Builder(
    name = "ManualEntry",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(4f, 7f); horizontalLineTo(20f)
        moveTo(4f, 12f); horizontalLineTo(20f)
        moveTo(4f, 17f); horizontalLineTo(14f)
    }
}.build()

/** 地球：地址输入框左侧的引导图标。 */
internal val GlobeIcon: ImageVector = ImageVector.Builder(
    name = "Globe",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(21f, 12f)
        curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
        curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
        curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
        curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
        close()
        moveTo(3f, 12f); horizontalLineTo(21f)
        moveTo(12f, 3f)
        curveTo(15.5f, 6.5f, 15.5f, 17.5f, 12f, 21f)
        curveTo(8.5f, 17.5f, 8.5f, 6.5f, 12f, 3f)
        close()
    }
}.build()
