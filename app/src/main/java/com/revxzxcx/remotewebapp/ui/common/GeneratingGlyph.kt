package com.revxzxcx.remotewebapp.ui.common

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「正在生成」的统一动态图标（R017）。
 *
 * ## 为什么要有这一个组件
 *
 * 之前在三个地方各写了一个手绘的呼吸圆点（工作区徽标、会话行状态点、
 * ConnectionBanner 的重连态），三处时长不一、颜色不一、含义却完全相同。
 * R017 的原则之一是「一个屏幕只有一个溢出菜单」，「正在跑」同样只由
 * 这一个组件表达——用户在任何屏幕上看到它，语义都是「有东西正在产出」。
 *
 * ## 三种形态的语义分工
 *
 * | 形态 | 用在 | 为什么是它 |
 * |---|---|---|
 * | [GlyphSize.SM] 三柱均衡器 | 列表行 / 折叠卡 / 横幅 / 模型条 | 经典「正在产出」符号，绘制只有 3 个 drawRoundRect |
 * | [GlyphSize.MD] 弧线扫旋 | LiveRow 尾部、发送键 busy | 替代原来的 CircularProgressIndicator，一个 arc + 一个 rotate |
 * | [GlyphSize.LG] 终端光标 | 空会话等首 token | 极客符号学里光标 = 「还会继续吐字」，比转圈更贴 LLM 流式 |
 *
 * ## 性能与无障碍铁律（这几条不是风格偏好，是硬约束）
 *
 * 1. 每屏可见无限动画 ≤ 2 个；列表里只有 `if (running)` 的行会实例化 Glyph，
 *    LazyColumn 滚出视口就销毁，不会累积。
 * 2. 所有动画量走 `graphicsLayer` 或 Canvas 内部重绘，**不触发重新测量**；
 *    禁止用 `Modifier.scale()` / `Modifier.alpha()` 改布局。
 * * 3. 循环周期 ≥ 530ms——更快只带来视觉焦虑和额外帧消耗。
 * 4. [rememberAnimationsEnabled]：读系统动画开关，关闭时退化成静态三柱
 *    （`scaleY = 0.6`）。**状态不能消失**——动画可以关，「正在生成」必须还在。
 * 5. 每个 Glyph 带 `contentDescription`；所有 `rememberInfiniteTransition` 具名，
 *    便于测试与工具定位。
 * 6. 不引 Lottie：一个 Canvas + 三个 animateFloat 的事，不值得 300KB 依赖与 JSON 解析。
 */
@Composable
fun GeneratingGlyph(
    modifier: Modifier = Modifier,
    size: GlyphSize = GlyphSize.SM,
    tint: Color = MaterialTheme.colorScheme.primary,
    description: String = "正在生成",
) {
    val enabled = rememberAnimationsEnabled()
    Box(
        modifier = if (description.isBlank()) {
            modifier
        } else {
            modifier.semantics { contentDescription = description }
        },
        contentAlignment = Alignment.Center,
    ) {
        when (size) {
            // 动画被关掉时 SM 退化为静态三柱；MD/LG 本来就是「扫一圈 / 闪一次」的
            // 循环，关掉后改成静态弧线与静态光标，语义不变。
            GlyphSize.SM -> if (enabled) BarsGlyph(tint) else StaticBars(tint)
            GlyphSize.MD -> if (enabled) ArcGlyph(tint) else StaticArc(tint)
            GlyphSize.LG -> if (enabled) CursorGlyph(tint) else StaticCursor(tint)
        }
    }
}

/** 三种尺寸的图标。[dp] 是整体边长。 */
enum class GlyphSize(val dp: Dp) {
    /** 16dp：列表行、折叠卡、横幅、模型条。 */
    SM(16.dp),

    /** 20dp：LiveRow 尾部、发送键 busy。 */
    MD(20.dp),

    /** 28dp：空会话等首 token 的全屏态。 */
    LG(28.dp),
}

/**
 * SM：三柱均衡器。
 *
 * 柱间用 `delayMillis = index * 150` 错峰，三根柱子此起彼伏——这是「正在产出」的
 * 通用符号，且比旋转动画更安静（不需要 360° 的连续运动）。
 */
@Composable
private fun BarsGlyph(tint: Color) {
    val transition = rememberInfiniteTransition(label = "generatingGlyphBars")
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(GlyphSize.SM.dp),
    ) {
        repeat(3) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "generatingGlyphBar$index",
            )
            Canvas(
                Modifier
                    .width(3.dp)
                    .height(GlyphSize.SM.dp),
            ) {
                // 在 Canvas 内部按 scale 算高度：重绘不重组，比 graphicsLayer
                // 更省（少一个 renderNode 的 layer），且三根柱子共用一个 Canvas。
                val h = size.height * scale
                val w = size.width
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0f, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
            if (index < 2) Spacer(Modifier.width(3.dp))
        }
    }
}

/** 动画关闭时的三柱：统一 0.6 高度，不闪但还在。 */
@Composable
private fun StaticBars(tint: Color) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(GlyphSize.SM.dp),
    ) {
        repeat(3) { index ->
            Canvas(
                Modifier
                    .width(3.dp)
                    .height(GlyphSize.SM.dp),
            ) {
                val h = size.height * 0.6f
                val w = size.width
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0f, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
            if (index < 2) Spacer(Modifier.width(3.dp))
        }
    }
}

/**
 * MD：弧线扫旋。
 *
 * 270° 弧 + `rotationZ` 0→360，线性 + Restart。替代原来的
 * `CircularProgressIndicator`——后者是 Material 的「不确定进度」语义，
 * 而流式生成更像是「一直在转但不承诺完成度」，弧线扫旋更贴。
 */
@Composable
private fun ArcGlyph(tint: Color) {
    val transition = rememberInfiniteTransition(label = "generatingGlyphArc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "generatingGlyphArcAngle",
    )
    Canvas(
        Modifier
            .size(GlyphSize.MD.dp)
            .graphicsLayer { rotationZ = angle },
    ) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun StaticArc(tint: Color) {
    Canvas(Modifier.size(GlyphSize.MD.dp)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * LG：终端光标。
 *
 * 2dp×16dp 竖条 alpha 0↔1，周期 530ms（所有动效里最快的一个，再快就闪）。
 * 极客符号学里光标 = 「还会继续吐字」——比转圈更贴合 LLM 流式。
 */
@Composable
private fun CursorGlyph(tint: Color) {
    val transition = rememberInfiniteTransition(label = "generatingGlyphCursor")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "generatingGlyphCursorAlpha",
    )
    Canvas(
        Modifier
            .width(2.dp)
            .height(GlyphSize.LG.dp - 12.dp),
    ) {
        drawRoundRect(
            color = tint.copy(alpha = alpha),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}

@Composable
private fun StaticCursor(tint: Color) {
    Canvas(
        Modifier
            .width(2.dp)
            .height(GlyphSize.LG.dp - 12.dp),
    ) {
        drawRoundRect(
            color = tint.copy(alpha = 0.6f),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}

/**
 * 「正在生成」的文字 + LG 光标组合，用于空会话等首 token 的全屏态。
 *
 * 放在这里而不是调用方：文案与光标是一个语义单元，分开写会出现
 * 「有的地方带文案、有的地方不带」。
 */
@Composable
fun GeneratingStatus(
    modifier: Modifier = Modifier,
    text: String = "正在生成…",
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier.semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GeneratingGlyph(size = GlyphSize.LG, tint = tint, description = "")
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * 系统动画开关（R017 铁律 4）。
 *
 * 读 `ANIMATOR_DURATION_SCALE`：0 = 关闭动画。关闭时所有无限动画退化成静态形态，
 * 但**状态本身不消失**——「正在生成」这个信息永远要能看见。
 */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}
