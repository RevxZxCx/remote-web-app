package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 手指落在首页的哪半边屏幕。
 *
 * ## 为什么按半屏分，而不是按左缘的一条窄带分
 *
 * 首页有**两块**折叠面板，各自贴在自己那一侧：左侧会话抽屉（`ModalNavigationDrawer`，
 * R001 起就在）、右侧总上下文洞察（T076）。T076 首版的规则是「左缘 [24dp] 内归抽屉、
 * 其余归洞察」，两个问题都是真机上用户直接抱怨的（R051 用户 m36530）：
 *
 * 1. **方向是反的**。洞察抽屉在右边，却要**向右滑**才拉得出来——手指往右推，面板也从
 *    右边出来，等于要用户先在心里反一次。改成右半屏左滑后，「把面板从它自己那一侧推
 *    出来」这个直觉成立，两个面板的手势是镜像对称的。
 * 2. **可滑面积太小**。24dp 在 360dp 宽的屏上约 9mm，用户得先瞄准再滑，形不成肌肉
 *    记忆。半屏把仲裁面积从 9mm 变成 180dp。
 *
 * ## 为什么左半屏整块让给抽屉，不留「死区」
 *
 * 左半屏右滑就是抽屉自己的手势，`ModalNavigationDrawer` 的 `anchoredDraggable` 天生
 * 做得比我们好（跟手、可中途撤销、松手吸附）。这里只做**否定**——判断这次不归我们管，
 * 于是整段不消费，事件照常到抽屉。真正需要拦的只有右半屏：抽屉的 `anchoredDraggable`
 * 覆盖整屏，不在右半屏消费掉，从右边滑一下也会把左抽屉拉出来。
 *
 * 判定只看**起点**。手指从右半屏划到左半屏不该中途改判归属，那正是「同一手势两种结果」
 * 的来源。
 */
internal enum class SwipeSide {
    /** 左半屏：右滑拉出左侧会话抽屉，交给 `ModalNavigationDrawer` 自己处理。 */
    LEFT,

    /** 右半屏：左滑拉出右侧总上下文洞察，由 [insightsSwipe] 消费。 */
    RIGHT,
}

/**
 * 起点 [startX] 落在哪半屏。
 *
 * @param startX 手势**起点**的 x 坐标（相对内容区左边缘，像素）。
 * @param widthPx 内容区宽度（像素）。
 *
 * 宽度未知（`<= 0`）时按 [SwipeSide.LEFT] 处理：那意味着「全部让给抽屉」，方向上是
 * 保守的——宁可相信抽屉自己的手势，也不要在量不出尺寸时抢事件。
 */
internal fun swipeSide(startX: Float, widthPx: Float): SwipeSide =
    if (widthPx > 0f && startX >= widthPx / 2f) SwipeSide.RIGHT else SwipeSide.LEFT

/**
 * 这次拖动归谁：横向手势（我们）还是纵向滚动（内容容器）？
 *
 * ## 为什么需要这一层判定（R052，用户 m37463 第 1 条）
 *
 * 用户原话：「上下文洞察侧边栏展开的时候，上下拖动也会触发右拉，导致实际操作的上拉，
 * 而实际没有进行滚动而是关闭侧边栏」。左侧会话抽屉没有这个问题，因为它交给
 * `ModalNavigationDrawer` 的 `anchoredDraggable`，那一套**先判主轴再决定接不接管**。
 *
 * 首版用的是 `horizontalDrag`，它只盯**水平轴**有没有越过 `touchSlop`：手指竖直滚动时
 * 只要有一点横向抖动（斜率 0.3 的斜划在 20dp 的竖直位移上就有 6dp 横移），水平阈值照样
 * 被越过 → 手势被我们接管、`consume()` 把每一帧都吃掉 → 内容容器收不到滚动，而
 * `revealAfterDrag` 被那点负位移拖着走，松手按 [settleInsightsCollapse] 判成收起。
 * 用户看到的就是「想滚动，结果面板关了」。
 *
 * ## 判据
 *
 * 先等**任意方向**的累计位移超过 `touchSlop`（[_UNDECIDED]），再比轴向绝对值：
 * 横向占优（[HORIZONTAL]）归我们，纵向占优（[VERTICAL]）整段让路。等阈值这一步不能省：
 * 手指刚落下时的几帧抖动方向是随机的，提前选边会把一次干净的竖直滚动判成横向。
 *
 * 平局（`abs(dx) == abs(dy)`）判纵向：滚动是内容区的常态操作，收起抽屉还有返回键、遮罩
 * 点击、顶栏按钮三条路，把误判代价压在让路这一侧。
 *
 * @param dx 从按下点到当前的累计横向位移（像素，右正左负）。
 * @param dy 从按下点到当前的累计纵向位移（像素，下正上负）。
 * @param slopPx 系统的 `touchSlop`（像素）。
 */
internal enum class DragAxis {
    /** 还没走够 `touchSlop`，不能选边。 */
    UNDECIDED,

    /** 横向占优：归 [insightsSwipe]。 */
    HORIZONTAL,

    /** 纵向占优（含平局）：让给滚动容器，整段手势不消费任何一帧。 */
    VERTICAL,
}

/** 轴向判定，见 [DragAxis]。纯函数，便于单测与变异验证。 */
internal fun dragAxis(dx: Float, dy: Float, slopPx: Float): DragAxis = when {
    // `hypot` 而不是 `max(abs(x), abs(y))`：斜向滑动时两者都小，用单轴判断会提前选边。
    hypot(dx, dy) <= slopPx -> DragAxis.UNDECIDED
    abs(dx) > abs(dy) -> DragAxis.HORIZONTAL
    else -> DragAxis.VERTICAL
}

/**
 * 等到能选边为止，返回是不是横向手势。
 *
 * 三件事会让我们**放弃**这次手势（返回 `false`）：
 * 1. 走到了 [DragAxis.VERTICAL] 的结论（用户要滚动）。
 * 2. 这一帧已经被子孙消费（`change.isConsumed`）——内容容器的 `scrollable` 在 Main pass
 *    里比我们**先**收到事件（派发由叶子向根），它已经接管就没必要再抢。
 * 3. 手指抬起来了（`!change.pressed`），说明是点击或极短的一划。
 *
 * 放弃时**不调用 `settleSink`**：那等同于「回到手势开始前的状态」，与
 * [settleAfterDrag] 里 `!completed` 分支的语义一致，直接 return 即可。
 */
private suspend fun AwaitPointerEventScope.awaitHorizontalIntent(pointerId: PointerId): Boolean {
    val slopPx = viewConfiguration.touchSlop
    var dx = 0f
    var dy = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.isConsumed) return false
        if (!change.pressed) return false
        val delta = change.positionChange()
        dx += delta.x
        dy += delta.y
        when (dragAxis(dx, dy, slopPx)) {
            DragAxis.UNDECIDED -> Unit
            DragAxis.HORIZONTAL -> return true
            DragAxis.VERTICAL -> return false
        }
    }
}

/**
 * 松手时抽屉是开还是合？
 *
 * ## 判据：过半即开，甩开也算开
 *
 * - **位移过半**（[reveal] ≥ [SETTLE_THRESHOLD]）→ 开。这是「跟手」的自然收尾：
 *   用户把它拖过了三分之一屏，多半就是想打开。
 * - **快速甩**（速度超过 [FLING_VELOCITY] 且确实在往打开方向拖，即 [reveal] > 0）
 *   → 开。手机上「轻轻一弹」比「拖到位」常用得多，只按位置判定会让这类手势回弹，
 *   手感直接变差。
 * - 其余 → 合。
 *
 * 反方向（拖回去）一律回弹到合：本次手势的意图就是放弃。
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
 * 收起方向上，松手时面板还该不该开着？
 *
 * [settleInsightsOpen] 的判据对方向不敏感（`reveal >= 0.35` 就开），直接拿去判收起
 * 会得到一个荒谬的结果：面板从 1.0 递减，要判成「合」必须把它拖到 0.35 以下，也就是
 * **拖过 65% 屏宽**；而打开只需要拖 35%。同一个手势反着做却要付出近两倍的距离，
 * A 方案的「反向滑 = 收起」在真机上几乎不可用（短距离快甩更是完全失效——收起时
 * 速度是负值，`velocity >= FLING_VELOCITY` 恒不成立）。
 *
 * 因此收起方向用**镜像**判据：
 * - 已经收回过半（`reveal <= 1 - SETTLE_THRESHOLD`）→ 合。与打开时的「拖过 35% 就开」
 *   完全对称。
 * - 没收回过半，但往右**快速甩**（`velocity <= -FLING_VELOCITY`）→ 合。轻弹一下
 *   就够，这是手机上最常用的收起方式。
 * - 一动没动（`reveal >= 1`）→ 保持开。
 * - 其余（拖了很少、又拖得很慢）→ 回弹为开，当作「拖了但反悔」。
 *
 * @param reveal 当前揭开比例，1 = 完全打开。
 * @param velocity 松手瞬间的速度，单位「每秒揭开比例」；收起时为负值。
 */
internal fun settleInsightsCollapse(reveal: Float, velocity: Float = 0f): Boolean {
    if (reveal >= 1f) return true
    if (reveal <= 1f - SETTLE_THRESHOLD) return false
    return velocity > -FLING_VELOCITY
}

/**
 * 拖动结束后该开还是合。
 *
 * 比 [settleInsightsOpen] / [settleInsightsCollapse] 多一层 [completed] 判定：手势循环
 * 里的 `drag` 在手势被抢占（比如从底部系统手势区起手、或上层出现对话框）时返回
 * `false`，那一刻的 `reveal` 与 `velocity` 都不是用户想要的收尾，按「放弃」回弹才安全。
 * 少了这层判定，被抢占的手势会把面板停在半开状态，用户既没打开也没关上。
 *
 * @param completed `drag` 是否完整走完（没有被别的手势抢占）。
 * @param reveal 松手瞬间的揭开比例。
 * @param velocity 松手瞬间的速度（每秒揭开比例）。
 * @param mode 决定用哪一套阈值：打开与收起的方向相反，判据必须镜像（见两条 KDoc）。
 */
internal fun settleAfterDrag(
    completed: Boolean,
    reveal: Float,
    velocity: Float,
    mode: SwipeMode = SwipeMode.REVEAL,
): Boolean {
    // 被打断时回到**这次手势开始前的状态**：REVEAL 手势是从「关着」起手的，所以回到
    // 关；COLLAPSE 手势是从「开着」起手的，所以回到开。写成恒 `false` 会让一次被系统
    // 抢走的收起手势把抽屉**直接关掉**——用户只是手指被别的东西截走，界面却跳变了。
    if (!completed) return mode == SwipeMode.COLLAPSE
    return when (mode) {
        SwipeMode.REVEAL -> settleInsightsOpen(reveal, velocity)
        SwipeMode.COLLAPSE -> settleInsightsCollapse(reveal, velocity)
    }
}

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
 * @param deltaPx 本次拖动增加的像素（朝**揭开方向**为正）。
 * @param screenWidthPx 内容区宽度。
 */
internal fun revealAfterDrag(current: Float, deltaPx: Float, screenWidthPx: Float): Float {
    if (screenWidthPx <= 0f) return current
    return (current + deltaPx / screenWidthPx).coerceIn(0f, 1f)
}

/**
 * 右半屏「左滑拉出总上下文洞察」的手势修饰符。
 *
 * ## 只认右半屏，且只认左滑
 *
 * - 起点在**左半屏** → 整段不消费，让 `ModalNavigationDrawer` 自己处理（右滑开抽屉）。
 * - 起点在**右半屏** → 由这里接管：
 *   - **向左**拖 → 按比例揭开洞察，手指和面板贴在一起走；
 *   - **向右**拖 → 什么都不做，但**仍然消费事件**。这一条不是为了自己做什么，而是
 *     为了拦住抽屉：`ModalNavigationDrawer` 的 `anchoredDraggable` 覆盖整屏，不消费
 *     的话用户从右半屏往右一划，左边的会话抽屉照样被拉出来，右半屏等于白分。
 *
 * 方向只在第一帧锁定。中途反悔（往左拖到一半改成往右）不会改判归属，只让 reveal
 * 跟着往回走——归属变来变去正是「同一手势两种结果」。
 *
 * ## 为什么能盖住左侧抽屉
 *
 * Compose 的指针事件在 `PointerEventPass.Main` 阶段**由叶子向根**派发。本修饰符挂在
 * Scaffold 的内容盒子上，而 `ModalNavigationDrawer` 的 `anchoredDraggable` 挂在它更
 * 外层的盒子上——子节点先看到事件，也就能先 `consume()`。
 *
 * 因此这个修饰符**必须挂在抽屉内部**。挂到抽屉外面（比如包住整个
 * `ModalNavigationDrawer`）派发顺序反过来，抽屉会先赢，洞察永远打不开（T076 真机
 * 实测复现过）。
 *
 * ## 为什么回调要用 rememberUpdatedState 包一层
 *
 * 拖动中每帧都在改 `reveal`，会触发重组；若把回调 lambda 直接当 `pointerInput` 的 key，
 * 每帧都会重启手势识别器，拖动当场断掉。用 `rememberUpdatedState` 取「最新那份」，
 * key 里不放任何会随帧变化的东西。
 *
 * @param reveal 读当前揭开比例。用 lambda 而不是值：值会让 key 每帧变化。
 * @param onReveal 拖动过程中回传新的比例。
 * @param onSettle 松手时回传「开/合」的最终判定。
 */
internal enum class SwipeMode {
    /** 只见效于「揭开」：抽屉没开时用。左半屏整段让给会话抽屉。 */
    REVEAL,

    /**
     * 只见效于「收起」：抽屉**已经开着**时挂在抽屉本身上用。
     *
     * 为什么不复用 [REVEAL]：洞察抽屉宽 320dp，在 360dp 宽的屏上占满 89%，遮罩只剩
     * 约 40dp 一条窄缝。抽屉打开后手指几乎只能落在抽屉上，而遮罩那份手势在抽屉**下面**，
     * 收不到事件（真机实测：从抽屉上起手右滑，面板纹丝不动）。在真机上「反向滑收起」
     * 必须能在抽屉本身上完成，否则 A 方案只有理论上成立。
     */
    COLLAPSE,
}

@Composable
internal fun Modifier.insightsSwipe(
    reveal: () -> Float,
    onReveal: (Float) -> Unit,
    onSettle: (Boolean) -> Unit,
    mode: SwipeMode = SwipeMode.REVEAL,
): Modifier {
    val revealNow by rememberUpdatedState(reveal)
    val revealSink by rememberUpdatedState(onReveal)
    val settleSink by rememberUpdatedState(onSettle)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            // **尺寸必须在每次手势里重新读**：`size` 是 `PointerInputScope` 的属性，
            // 挂在抽屉本体上时它等于抽屉宽（320dp）、挂在内容区时等于屏宽（360dp），
            // 分屏/折叠屏/旋屏后还会变。写成 `pointerInput` 启动时读一次的局部变量，
            // 窗口尺寸一变，半屏边界与「像素→比例」的分母就永久停在旧值上
            // （屏变宽后左半屏右侧一段会被误判成右半屏，往左滑会拉出洞察）。
            val screenWidthPx = size.width.toFloat()
            // **必须用 `awaitFirstDown` 拿真正的按下点**，不能用
            // `detectHorizontalDragGestures` 的 `onDragStart`：后者给的是「越过触摸阈值
            // 那一刻」的坐标，快速滑动时第一个被判定的事件已经离起点几十像素，起点会
            // 从半屏的一侧翻到另一侧。T076 用左缘窄带时就被这条坑过一次（13dp 的起点
            // 被读成 42dp，直接翻到带外）。
            val down = awaitFirstDown(requireUnconsumed = false)
            // REVEAL 只在右半屏起手时介入；COLLAPSE 挂在抽屉上，抽屉本身已经是
            // 右半屏那一块（外加一点点左边缘），按半屏判会把抽屉左侧一条判成「让给
            // 会话抽屉」而收不起来，所以 COLLAPSE 不分区。
            if (mode == SwipeMode.REVEAL &&
                swipeSide(down.position.x, screenWidthPx) == SwipeSide.LEFT
            ) {
                // 左半屏：整段手势让给 `ModalNavigationDrawer`，这里不消费任何一帧。
                return@awaitEachGesture
            }
            // **先判主轴再接管**（R052）：竖直滚动要整段让路，否则用户想上下滑看内容、
            // 结果面板被那点横向抖动收起来了。判据见 [dragAxis] / [awaitHorizontalIntent]。
            if (!awaitHorizontalIntent(down.id)) return@awaitEachGesture
            // 上一次采样时刻与「每秒揭开比例」的瞬时估计，用于松手时的甩动判定。
            var lastUptime = 0L
            var velocity = 0f
            // 这次触摸有没有真的拖动过。
            //
            // **纯点击绝不能走到 `settleSink`**：`drag` 在「一直没超过 touchSlop
            // 就抬手」时也会正常返回 `completed = true`，那一刻 `reveal` 还是原值。
            // 对 COLLAPSE（`reveal >= 1` 判开）来说这就等于「点一下 = 重新打开」——
            // 遮罩上那次点击刚触发 `settleInsights(false)`，紧接着这里又 `settleInsights(true)`，
            // `insightsJob?.cancel()` 把关闭动画掐掉再动画回全开，抽屉那条约 40dp 的
            // 窄缝彻底点不掉了（第二轮审查 P1 抓到，是我给遮罩补 mode 时引入的回归）。
            var moved = false
            val completed = drag(down.id) { change ->
                val delta = change.positionChange().x
                // 洞察从**右侧**推出，所以手指往左（delta < 0）才是揭开方向。
                // 整个手势里统一取负号，把「向左为正」换算成 revealAfterDrag 要求的
                // 「朝揭开方向为正」，判定函数因此不必知道自己画在哪一边。
                //
                // **两种模式取同一个符号**：`reveal` 描述的是「面板被拉出来多少」这个
                // 物理量，只由面板的几何位置决定（洞察从右侧出来，所以往左拖才是拉开），
                // 与「此刻正在开还是在关」无关。首版曾按 mode 翻转符号，结果收起时
                // reveal 被钳在 1.0 一动不动（测试当场抓出 `[1.0, 1.0, …]`）。
                val towardReveal = -delta
                if (lastUptime > 0L) {
                    val elapsed = change.uptimeMillis - lastUptime
                    if (elapsed > 0L && screenWidthPx > 0f) {
                        velocity = towardReveal / elapsed * 1000f / screenWidthPx
                    }
                }
                lastUptime = change.uptimeMillis
                if (towardReveal != 0f) {
                    moved = true
                    revealSink(revealAfterDrag(revealNow(), towardReveal, screenWidthPx))
                }
                // 吃掉这一帧，祖先的 anchoredDraggable 就不会同时开左侧抽屉。
                // 往右拖时这里**什么都不做却照样消费**——正是为了拦住抽屉。
                change.consume()
            }
            // `velocity` 同样是「reveal 每秒变化多少」，收得越快越负。快速右滑收起时
            // reveal 已经掉到接近 0、速度也是负的，`settleInsightsOpen` 自然判为关闭。
            if (moved) {
                settleSink(settleAfterDrag(completed, revealNow(), velocity, mode))
            }
        }
    }
}
