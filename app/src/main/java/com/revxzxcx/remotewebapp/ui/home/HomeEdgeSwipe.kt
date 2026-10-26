package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/**
 * 首页「向右滑」的手势归属：会话抽屉，还是总上下文洞察抽屉？
 *
 * ## 为什么需要这个判断
 *
 * 首页此时有**两个**右滑目标：
 *
 * 1. 左侧会话抽屉（`ModalNavigationDrawer`，R001 起就在）；
 * 2. 右侧总上下文洞察抽屉（T076，本阶段新增）。
 *
 * 两者都响应「向右滑」，而 Material3 的 `ModalNavigationDrawer` 在
 * `gesturesEnabled = true` 时把**整屏**横滑都吃掉（它给内容挂了 `anchoredDraggable`）。
 * 谁都不让就会两败：抽屉抢走每一次右滑，洞察永远打不开。
 *
 * ## 规则：左缘归抽屉，其余归洞察
 *
 * 与 Android 的系统级手势约定一致——**边缘**是导航（返回、抽屉），**内容区**是页面
 * 自己的动作。落到这里就是：
 *
 * - 起点在屏幕左缘 [EdgeWidth] 以内 → 交给 `ModalNavigationDrawer`（用户想开会话列表）；
 * - 起点在 [EdgeWidth] 之外 → 本次手势由洞察消费，抽屉收不到。
 *
 * 判定只看**起点**，不看当前位置：手指从内容区划到边缘不该中途变成「开抽屉」，
 * 那正是「同一手势两种结果」的来源。起点一旦判定，整个手势归属就不再变。
 *
 * ## 为什么是 24dp
 *
 * 系统 `ViewConfiguration.getEdgeSlop()` 常见实现是 20dp 上下，Material 的边缘抽屉
 * 判定也用同一量级。取 24dp 而不是更宽：这条带子每加宽 1dp，都是从「右滑看洞察」
 * 那边抢来的可滑面积；而会话抽屉还有不依赖手势的入口（顶栏 ☰），少一点边缘容错
 * 不致命。
 *
 * @param startX 手势**起点**的 x 坐标（相对首页内容区左边缘，像素）。
 * @param edgeWidthPx [EdgeWidth] 换算成像素后的值，由调用方用 `LocalDensity` 算好。
 */
internal fun swipeOpensDrawer(startX: Float, edgeWidthPx: Float): Boolean =
    startX < edgeWidthPx

/**
 * 左缘手势带宽度。
 *
 * 单独提出来是为了让 [insightsSwipe] 的调用方与单测用**同一个**常量；改成各写各的
 * 数字之后，「边缘归抽屉」这条规则就会出现两个真值来源。
 */
internal val EdgeWidth = 24.dp

/**
 * 松手时抽屉是开还是合？
 *
 * ## 判据：过半即开，甩开也算开
 *
 * - **位移过半**（[reveal] ≥ [SETTLE_THRESHOLD]）→ 开。这是「跟手」的自然收尾：
 *   用户把它拖过了三分之一屏，多半就是想打开。
 * - **快速右甩**（速度超过 [FLING_VELOCITY] 且确实在往右拖，即 [reveal] > 0）
 *   → 开。手机上「轻轻一弹」比「拖到位」常用得多，只按位置判定会让这类手势回弹，
 *   手感直接变差。
 * - 其余 → 合。
 *
 * 反方向（左甩、拖回左侧）一律回弹到合：本次手势的意图就是放弃。
 *
 * @param reveal 当前揭开比例，0 = 完全收起，1 = 完全打开。
 * @param velocity 松手瞬间的速度，单位「每秒揭开比例」，正值表示朝打开方向。
 */
internal fun settleInsightsOpen(reveal: Float, velocity: Float = 0f): Boolean {
    if (reveal <= 0f) return false
    if (reveal >= SETTLE_THRESHOLD) return true
    return velocity >= FLING_VELOCITY
}

/**
 * 拖动结束后该开还是合。
 *
 * 比直接调 [settleInsightsOpen] 多一层 [completed] 判定：手势循环里的 `drag` 在手势被
 * 抢占（比如从底部系统手势区起手、或上层出现对话框）时返回 `false`，那一刻的 `reveal`
 * 与 `velocity` 都不是用户想要的收尾，按「放弃」回弹才安全。少了这层判定，被抢占的
 * 手势会把面板停在半开状态，用户既没打开也没关上。
 *
 * @param completed `drag` 是否完整走完（没有被别的手势抢占）。
 * @param reveal 松手瞬间的揭开比例。
 * @param velocity 松手瞬间的速度（每秒揭开比例）。
 */
internal fun settleAfterDrag(completed: Boolean, reveal: Float, velocity: Float): Boolean =
    completed && settleInsightsOpen(reveal, velocity)

/** 松手时判定为「要打开」的位移阈值。0.35 比一半略低：拖过三分之一就该给结果。 */
internal const val SETTLE_THRESHOLD = 0.35f

/** 甩动判定阈值（每秒揭开比例）。按 300ms 划开一屏估算约 3.3/s，取 2.2 略保守。 */
internal const val FLING_VELOCITY = 2.2f

/**
 * 把一次拖动的位移换算成新的揭开比例。
 *
 * 除以屏幕宽度而不是抽屉宽度：用户是按**手指走过的距离**建立预期的，「拖过半个屏幕」
 * 就该看到一半。若除以抽屉宽度（320dp 对 360dp 屏），拖到 320dp 时比例会算出 1.125，
 * 手指还没走完面板就满了，后半程怎么拖都没反应。
 *
 * @param current 当前比例。
 * @param deltaPx 本次拖动增加的像素（向右为正）。
 * @param screenWidthPx 内容区宽度。
 */
internal fun revealAfterDrag(current: Float, deltaPx: Float, screenWidthPx: Float): Float {
    if (screenWidthPx <= 0f) return current
    return (current + deltaPx / screenWidthPx).coerceIn(0f, 1f)
}

/**
 * 「向右滑打开洞察抽屉」的手势修饰符。
 *
 * ## 为什么能盖住左侧抽屉
 *
 * Compose 的指针事件在 `PointerEventPass.Main` 阶段**由叶子向根**派发。本修饰符挂在
 * Scaffold 的内容盒子上，而 `ModalNavigationDrawer` 的 `anchoredDraggable` 挂在它更
 * 外层的盒子上——子节点先看到事件，也就能先 `consume()`。抽屉那一侧看到事件已被消费，
 * 就会放弃这次拖动。
 *
 * 因此这个修饰符**必须挂在抽屉内部**。挂到抽屉外面（比如包住整个
 * `ModalNavigationDrawer`）派发顺序反过来，抽屉会先赢，洞察永远打不开。
 *
 * ## 为什么回调要用 rememberUpdatedState 包一层
 *
 * 拖动中每帧都在改 `reveal`，会触发重组；若把点击 lambda 直接当 `pointerInput` 的 key，
 * 每帧都会重启手势识别器，拖动当场断掉。用 `rememberUpdatedState` 取「最新那份」，
 * key 里只留真正的结构参数 `edgeWidthPx`。
 *
 * @param edgeWidthPx 左缘手势带宽度（像素）。
 * @param reveal 读当前揭开比例。用 lambda 而不是值：值会让上面那个 key 每帧变化。
 * @param onReveal 拖动过程中回传新的比例。
 * @param onSettle 松手时回传「开/合」的最终判定。
 */
@Composable
internal fun Modifier.insightsSwipe(
    edgeWidthPx: Float,
    reveal: () -> Float,
    onReveal: (Float) -> Unit,
    onSettle: (Boolean) -> Unit,
): Modifier {
    val revealNow by rememberUpdatedState(reveal)
    val revealSink by rememberUpdatedState(onReveal)
    val settleSink by rememberUpdatedState(onSettle)

    return this.pointerInput(edgeWidthPx) {
        val screenWidthPx = size.width.toFloat()
        awaitEachGesture {
            // **必须用 `awaitFirstDown` 拿真正的按下点**，不能用
            // `detectHorizontalDragGestures` 的 `onDragStart`：后者给的是「越过触摸阈值
            // 那一刻」的坐标，快速甩动时第一个被判定的事件已经离起点几十像素，左缘那一带
            // 会被整段判到内容区去。真机上从 x=40（约 13dp，在 24dp 带内）快速右滑时
            // 开的是洞察抽屉而不是会话抽屉，就是这么来的。
            val down = awaitFirstDown(requireUnconsumed = false)
            if (swipeOpensDrawer(down.position.x, edgeWidthPx)) {
                // 左缘：整段手势让给 `ModalNavigationDrawer`，这里不消费任何一帧。
                return@awaitEachGesture
            }
            // 上一次采样时刻与「每秒揭开比例」的瞬时估计，用于松手时的甩动判定。
            var lastUptime = 0L
            var velocity = 0f
            val completed = horizontalDrag(down.id) { change ->
                val delta = change.positionChange().x
                if (lastUptime > 0L) {
                    val elapsed = change.uptimeMillis - lastUptime
                    if (elapsed > 0L && screenWidthPx > 0f) {
                        velocity = delta / elapsed * 1000f / screenWidthPx
                    }
                }
                lastUptime = change.uptimeMillis
                revealSink(revealAfterDrag(revealNow(), delta, screenWidthPx))
                // 吃掉这一帧，祖先的 anchoredDraggable 就不会同时开左侧抽屉。
                change.consume()
            }
            settleSink(settleAfterDrag(completed, revealNow(), velocity))
        }
    }
}
