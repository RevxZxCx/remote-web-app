package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.revxzxcx.remotewebapp.ui.theme.Motion

/**
 * 列表行 / 卡片的按压反馈（R017）。
 *
 * ## 之前的状态
 *
 * `HomeScreen` 的 `WorkspaceRow` 与 `SessionRowItem` 各写了一份 12 行的
 * `interactionSource + LaunchedEffect + animateFloatAsState(0.98f)`；
 * 抽屉行与 `ChatScreen` 的若干行则**什么都没有**。结果是同一屏里有的行按下去内缩、
 * 有的行只换个 ripple——「质感」的差异用户说不出来但感觉得到。
 *
 * ## 两个 hook 的分工
 *
 * - [rememberPressScale]：整行轻微内缩（0.98）。比单纯换背景更像「按到了」，
 *   且不改变行高（`graphicsLayer` 不触发重新测量）。
 * - [rememberPressTint]：洗色浮出。给**没有背景**的行用（会话行、抽屉行
 *   是「无底 + 2dp 导轨」方案，按压时除了 ripple 还需要一个可感知的面）。
 *
 * 两者都走 [Motion.FAST]：按压是「原地状态变化」，按 Motion.kt 的分级归 FAST。
 *
 * ## 唯一入口
 *
 * `AppButtons.kt` 里曾经有过第二份 `rememberPressScale` / `rememberPressTint`
 * （内部自建 `MutableInteractionSource`，不外传）。两份同名同包、全默认参数，
 * 调用方的无参写法对两个候选都适用 → Kotlin 重载决议歧义，整模块编译不过
 * （R018 P1-1）。已删除；本文件是唯一实现。
 *
 * **为什么调用方必须外传 interactionSource**：`Modifier.clickable` 默认自建一个
 * source。若按压 hook 内部另建一个，两个 source 毫无关联 → scale 恒 1f、
 * tint 恒 Transparent，按压反馈全 App 不生效（R018 P2-2）。所以调用方要把同一个
 * source 同时交给 `clickable(interactionSource = …, indication = ripple())`
 * 与这里的 hook。`AppButtons.AppIconButton` 已内置这条链路，直接用它最省事。
 */
@Composable
internal fun rememberPressed(interactionSource: MutableInteractionSource): Boolean {
    var pressed by remember(interactionSource) { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            // 只有 Press 置 true：正常点击序列是 Press→Release，若 Release 也置
            // true，常规点按不产生 Cancel，pressed 一旦为 true 再无人置 false →
            // 缩放永久停在 0.98、洗色永久浮着（R018 P1-2）。
            pressed = interaction is PressInteraction.Press
        }
    }
    return pressed
}

/** 行按压轻微内缩；配合 [rememberPressed] 与外部传入的 [interactionSource]。 */
@Composable
fun rememberPressScale(
    target: Float = 0.98f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Float {
    val pressed = rememberPressed(interactionSource)
    return animateFloatAsState(
        targetValue = if (pressed) target else 1f,
        animationSpec = tween(Motion.FAST),
        label = "pressScale",
    ).value
}

/**
 * 按压洗色：返回一个「当前该用的颜色」，调用方 `Modifier.background(tint)`。
 *
 * 用 `animateColorAsState` 而不是 alpha 动画：alpha 动画在半透明底色上会与
 * 下层颜色反复混合，低端机上能看到色带跳变；直接补间到一个实色不会。
 */
@Composable
fun rememberPressTint(
    pressedColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
    releasedColor: Color = Color.Transparent,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Color {
    val pressed = rememberPressed(interactionSource)
    return animateColorAsState(
        targetValue = if (pressed) pressedColor else releasedColor,
        animationSpec = tween(Motion.FAST),
        label = "pressTint",
    ).value
}
