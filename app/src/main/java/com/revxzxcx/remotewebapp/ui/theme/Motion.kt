package com.revxzxcx.remotewebapp.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 动效常量与规格（R016 界面美化）。
 *
 * ## 为什么集中在这里
 *
 * 各处随手写 `tween(200)` 的结果是：列表淡入用 150ms、卡片展开用 300ms、
 * 页面切换用 200ms，三个速度挨在一起，用户感知到的不是「流畅」而是「抖」。
 * 时长分级之后，同一个层级的动作永远同一个速度。
 *
 * 分级依据（Material 3 motion 的「短/中/长」四级取前三）：
 * - [FAST]：原地状态变化（chevron 旋转、透明度、颜色）。
 * - [MEDIUM]：元素进出屏幕（新消息淡入、排队气泡增删）。
 * - [SLOW]：容器形变（分组展开、页面切换）。
 *
 * 缓动统一用 [Emphasized]：起步快、收尾慢，符合「物体被推出去然后停住」的
 * 直觉。线性 `tween` 只用于无限循环的呼吸效果，交互一律不用。
 */
internal object Motion {
    const val FAST = 140
    const val MEDIUM = 240
    const val SLOW = 360

    /** 交互缓动：先快后慢。 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 进场专用：比 [Emphasized] 更柔，用于从屏幕边缘滑入。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** 透明度规格，用于 `AnimatedVisibility` 的 fadeIn / fadeOut。 */
    fun <T> fade(durationMillis: Int = MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Emphasized)

    /** 位移规格，用于 `AnimatedVisibility` 的 slideIn / slideOut。 */
    fun offset(durationMillis: Int = MEDIUM): FiniteAnimationSpec<IntOffset> =
        tween(durationMillis = durationMillis, easing = Decelerate)
}
