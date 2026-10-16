package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.ui.theme.AppShapes
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning

/**
 * 顶栏连接指示器（R028 ①）。
 *
 * ## 它取代了什么
 *
 * 原来首页顶栏下面永远压着一条 32dp 高的整宽状态条（[ConnectionBanner]），
 * 不管连接健不健康都占着一整行。收进顶栏之后：
 * - 列表多出一整行；
 * - 「连接」本来就是全局事实，天然属于全局顶栏；
 * - 信息密度分三级——**不打扰**（圆点）→ **想知道状态**（胶囊）→ **要排查**（面板）。
 *   原来是一上来就把全部细节摊在脸上。
 *
 * ## 三态与切换
 *
 * ```
 * 圆点 ──点击──▶ 胶囊 ──点击──▶ 面板
 *   ▲                             │
 *   └──────── 点面板外任意处 ──────┘
 * ```
 *
 * 圆点态与胶囊态是**同一个组件**的两个尺寸（胶囊只是「圆点 + 状态文字」）。
 * 点圆点先进胶囊、再点进面板——两级展开与效果图一致，且胶囊本身就是「我想知道
 * 状态但不想看细节」的那一档，跳过它就等于把这一档删了。
 *
 * ## 颜色语义沿用现有设定
 *
 * 绿＝已连接（[LocalStatusRunning]）、琥珀＝连接中/重连中（`tertiary`）、
 * 红＝配对失效（`error`）。这套语义从 M1 的状态条一脉相承，不因重构而漂移。
 *
 * ## 为什么面板必须走 `Popup`
 *
 * 指示器住在 `TopAppBar.actions` 里——那里的高度被顶栏约束死（64dp）。面板高约 300dp，
 * 直接画在 `Box` 里只会被裁掉或被顶栏撑高。`Popup` 是独立窗口，天然越出父布局，
 * 遮罩也只有它能画。
 *
 * 位置不用 `DropdownMenu`：它的宽度随内容自适应、位置由锚点算法决定，而效果图
 * 要求面板**固定 308dp 宽、从指示器正下方展开**。这里用自定义
 * [PopupPositionProvider] 把窗口钉在原点，再用 `onGloballyPositioned` 量到的
 * 指示器下沿算出面板的上边距——坐标是真实窗口坐标，转屏/分屏都不会错位。
 */
@Composable
fun ConnectionIndicator(
    carrier: CarrierState,
    details: ConnectionDetails,
    // `modifier` 必须是第一个可选参数（lint `ModifierParameter`）：Compose 的惯例是调用方
    // 一眼就能看见能改布局的那个口子，掺在几个回调后面会被忽略。
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onRepairRequest: (() -> Unit)? = null,
    onOpenDebug: (() -> Unit)? = null,
) {
    // 两级展开都要能扛住旋屏，且**两边策略必须一致**（R028 审查 P2-5）：
    // 早先 `panelOpen` 用 rememberSaveable、`pillExpanded` 用 remember，于是「胶囊态开着面板」
    // 旋屏后会变成「圆点态开着面板」——锚点从 32dp 胶囊变回 40dp 圆点，面板下移 4dp，
    // 还会出现与状态图矛盾的画面。两个都用 rememberSaveable。
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var pillExpanded by rememberSaveable { mutableStateOf(false) }
    // 收起面板时把胶囊也退回圆点：状态图（见 KDoc）画的回退边是「面板 → 圆点」，
    // 而 `pillExpanded` 早先只有置 true 一处赋值（R028 审查 P2-4），于是点过胶囊之后
    // 顶栏**永久**挂着约 100dp 的胶囊，360dp 屏上标题只剩约 200dp。
    // 三级信息密度本来就是「临时想知道状态」用一下就走，不是常驻档位。
    val collapsePanel: () -> Unit = { panelOpen = false; pillExpanded = false }
    // 指示器下沿在**窗口坐标**里的 y（px）。面板要靠它定位。
    // 用 `mutableFloatStateOf`（lint `AutoboxingStateCreation`）：`MutableState<Float>` 每次读写
    // 都会把 float 装箱，而这个值在滚动/重连倒计时里会被反复读。
    var anchorBottomPx by remember { mutableFloatStateOf(0f) }

    val needsClock = carrier is CarrierState.Reconnecting || carrier is CarrierState.Connected
    val nowMillis = if (needsClock) rememberSecondClock() else 0L
    val tone = indicatorToneOf(carrier)
    val density = LocalDensity.current

    Box(modifier.onGloballyPositioned { anchorBottomPx = it.boundsInWindow().bottom }) {
        if (pillExpanded) {
            ConnectionPill(
                carrier = carrier,
                nowMillis = nowMillis,
                tone = tone,
                onClick = { panelOpen = true },
            )
        } else {
            ConnectionDot(
                carrier = carrier,
                nowMillis = nowMillis,
                tone = tone,
                animated = carrier !is CarrierState.Unpaired,
                onClick = { pillExpanded = true },
            )
        }
    }

    if (panelOpen) {
        Popup(
            // 钉在窗口原点：面板自己按窗口坐标算位置，不依赖锚点算法。
            popupPositionProvider = WindowOriginPositionProvider,
            // 关掉平台默认宽度上限，否则遮罩铺不满一屏（`usePlatformDefaultWidth` 默认 **true**，
            // 只写 `focusable = true` 时遮罩被限到平台对话框宽度——**露出的那条不吃触摸**，
            // 点在「面板外」的那条上会穿透到下面的工作区/会话行，面板还开着却发生了导航。
            // R028 审查 P2-1：注释说要做的事和代码做的事必须一致。
            properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false),
            onDismissRequest = collapsePanel,
        ) {
            ConnectionPanel(
                carrier = carrier,
                details = details,
                nowMillis = nowMillis,
                tone = tone,
                topDp = with(density) { anchorBottomPx.toDp() },
                onRetry = onRetry,
                onRepairRequest = onRepairRequest,
                onOpenDebug = onOpenDebug,
                onDismiss = collapsePanel,
            )
        }
    }
}

/**
 * 把 `Popup` 钉在窗口原点。
 *
 * 不这么写的话 `Popup` 会相对**父 composable** 定位，而父在顶栏里、还被顶栏的
 * 布局约束影响——面板就会跑到顶栏内部而不是盖在内容上。
 */
private val WindowOriginPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * 面板要展示的连接细节（R028 ①）。
 *
 * 全部字段都是**只读的展示值**：本对象不参与任何控制流，也不做打码以外的加工。
 * deviceId 已打码（见 [maskDeviceId]）——「`deviceId` 是完全控制权凭证」这条约束
 * 对任何要上屏的地方都成立，而截图外传是常态。
 */
data class ConnectionDetails(
    val baseUrl: String = "",
    val maskedDeviceId: String = "",
    /** `$events` 通道是否已收到 `ready` 帧。 */
    val channelReady: Boolean = false,
    val framesIn: Long = 0L,
    val framesOut: Long = 0L,
    val lastFailure: String? = null,
)

/** 指示器配色的三档。与 [ConnectionBanner] 的 `toneOf` 同源，但前景／底色分工不同。 */
internal data class IndicatorTone(
    /** 圆点与胶囊里那颗点的颜色。 */
    val dot: Color,
    /** 光晕／呼吸圈的基色（通常就是 [dot]）。 */
    val glow: Color,
    /** 胶囊与面板里的正文色。 */
    val foreground: Color,
)

@Composable
internal fun indicatorToneOf(carrier: CarrierState): IndicatorTone {
    val scheme = MaterialTheme.colorScheme
    val running = LocalStatusRunning.current
    return when (carrier) {
        is CarrierState.Connected -> IndicatorTone(running, running, scheme.onSurface)
        is CarrierState.Unpaired -> IndicatorTone(scheme.error, scheme.error, scheme.error)
        CarrierState.Idle, CarrierState.Connecting, is CarrierState.Reconnecting ->
            IndicatorTone(scheme.tertiary, scheme.tertiary, scheme.onSurface)
    }
}

/** 面板相对指示器下沿的间距、距屏底的保留区、以及正文的最小高度（R028 审查 P2-6）。 */
private val PANEL_TOP_GAP = 6.dp
private val PANEL_BOTTOM_GAP = 12.dp
private val PANEL_MIN_HEIGHT = 160.dp

/** 状态 A：40dp 触控区 + 8dp 圆点 + 光晕呼吸（R028 ①）。 */
@Composable
private fun ConnectionDot(
    carrier: CarrierState,
    nowMillis: Long,
    tone: IndicatorTone,
    animated: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // 琥珀（重连）的呼吸比绿（已连接）快：1600ms vs 2600ms。等待中的事更需要「它在动」。
    val periodMillis = if (animated && tone.dot != LocalStatusRunning.current) 1_600 else 2_600
    Box(
        modifier = Modifier
            .size(40.dp)
            .semantics {
                // 读屏用户在圆点档也必须能听到当前状态（R028 审查 P3-9）：
                // 早先恒为「连接状态」，不点开胶囊就不知道到底连上没有。
                contentDescription = "连接状态：${pillText(carrier, nowMillis)}"
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConnectionDotArt(tone = tone, animated = animated, periodMillis = periodMillis)
    }
}

/**
 * 圆点本体（光晕 + 呼吸圈 + 芯）。
 *
 * 三个元素共用**一个** `rememberInfiniteTransition`：R017 铁律要求每屏可见无限动画 ≤2，
 * 而顶栏可能同时出现圆点与列表里的 `GeneratingGlyph`——拆成两个 transition 就会
 * 让一个控件吃掉配额。
 *
 * 动画量只走 Canvas 内部重绘（`animateFloat` 读值 → 重绘），**不触发重新测量**（铁律 2）。
 */
@Composable
private fun ConnectionDotArt(
    tone: IndicatorTone,
    animated: Boolean,
    periodMillis: Int,
) {
    val enabled = rememberAnimationsEnabled() && animated
    if (!enabled) {
        // 动画被关掉时退化成静态光晕：没有呼吸，但**颜色与状态不消失**（R017 铁律 4）。
        StaticDot(tone)
        return
    }
    val transition = rememberInfiniteTransition(label = "connectionDot")
    val haloScale by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            // 0.72 → 1.15 → 0.72：用 Reverse 表达，比手写关键帧少一层。
            animation = tween(durationMillis = periodMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connectionHaloScale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connectionHaloAlpha",
    )
    val ringRadius by transition.animateFloat(
        initialValue = 4f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connectionRingRadius",
    )
    // 呼吸圈的透明度随半径扩大衰减：CSS 里 `box-shadow: 0 0 0 11px rgba(…,0)` 的扩散，
    // 在 Compose 里就是「半径变大 + 透明度归零」的描边圆。
    val ringAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connectionRingAlpha",
    )

    Canvas(Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val haloRadius = size.minDimension / 2f * haloScale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tone.glow.copy(alpha = 0.34f * haloAlpha),
                    tone.glow.copy(alpha = 0.14f * haloAlpha),
                    Color.Transparent,
                ),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )
        drawCircle(
            color = tone.glow.copy(alpha = ringAlpha),
            radius = ringRadius * 1.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCircle(color = tone.dot, radius = 4.dp.toPx(), center = center)
    }
}

/** 动画关闭时的圆点：光晕固定、没有呼吸圈，但状态仍在。 */
@Composable
private fun StaticDot(tone: IndicatorTone) {
    Canvas(Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tone.glow.copy(alpha = 0.22f), Color.Transparent),
                center = center,
                radius = size.minDimension / 2f,
            ),
            radius = size.minDimension / 2f,
            center = center,
        )
        drawCircle(color = tone.dot, radius = 4.dp.toPx(), center = center)
    }
}

/** 状态 B：胶囊＝圆点 + 状态文字（R028 ①）。 */
@Composable
private fun ConnectionPill(
    carrier: CarrierState,
    nowMillis: Long,
    tone: IndicatorTone,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier
            .height(32.dp)
            .semantics { contentDescription = "连接状态：${pillText(carrier, nowMillis)}" }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            DotWithGlow(tone)
            Text(
                text = pillText(carrier, nowMillis),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = tone.foreground,
                maxLines = 1,
            )
        }
    }
}

/** 胶囊与面板头部共用的小圆点（带同色辉光）。 */
@Composable
private fun DotWithGlow(tone: IndicatorTone) {
    Canvas(Modifier.size(10.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = tone.dot.copy(alpha = 0.28f), radius = size.minDimension / 2f, center = center)
        drawCircle(color = tone.dot, radius = 4.dp.toPx(), center = center)
    }
}

/**
 * 胶囊文案：连接中/重连中带倒计时，其余只说状态。
 *
 * 重连态必须带秒数——没有倒计时的话「重连中…」与死掉没区别，用户不知道该等还是该点。
 */
internal fun pillText(carrier: CarrierState, nowMillis: Long): String = when (carrier) {
    CarrierState.Idle -> "未连接"
    CarrierState.Connecting -> "连接中…"
    is CarrierState.Connected -> "已连接"
    is CarrierState.Reconnecting -> {
        val seconds = ((carrier.retryAtMillis - nowMillis) / 1000L).coerceAtLeast(0L)
        "重连中… ${seconds}s"
    }

    is CarrierState.Unpaired -> "配对失效"
}

/**
 * 状态 C：向下展开的连接详情面板（R028 ①）。
 *
 * 六行内容就是诊断页的核心几项，日常排查不用再进诊断页。底部的「重试」「诊断」
 * 与配对失效时的「重新配对」都**不是**新入口：它们分别调 [onRetry]、[onOpenDebug]、
 * [onRepairRequest]，其中 `onRepairRequest` 到 AppRoot 后仍会走统一的那一次二次确认
 * （R018 P1-1），不能在这里另开一条破坏性路径。
 *
 * @param topDp 面板上边距＝指示器下沿（窗口坐标），由调用方把 px 转成 dp 传进来。
 */
@Composable
private fun ConnectionPanel(
    carrier: CarrierState,
    details: ConnectionDetails,
    nowMillis: Long,
    tone: IndicatorTone,
    topDp: androidx.compose.ui.unit.Dp,
    onRetry: (() -> Unit)?,
    onRepairRequest: (() -> Unit)?,
    onOpenDebug: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    // 面板交互源与遮罩**必须分开**（R028 审查 P3-4）：两个 clickable 共享一个
    // MutableInteractionSource，将来任一方加上涟漪/按压态都会互相触发。
    val panelInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        // 面板必须有高度上限且正文可滚（R028 审查 P2-6）：横屏可用高约 360dp、
        // 系统字号放大时内容会超出窗口，底部「重试 / 诊断 / **重新配对**」被裁掉——
        // 而「重新配对」是脱离 Unpaired 的**唯一**入口，裁掉它等于把用户锁死。
        // 与 `Approvals.kt` 的 `ComposerShell` 是同一套做法（运行时算上限、正文滚动、
        // 底部动作区永远在滚动区外）。
        val scroll = rememberScrollState()
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            val reserved = topDp + PANEL_TOP_GAP + PANEL_BOTTOM_GAP
            val panelMax = (maxHeight - reserved).coerceAtLeast(PANEL_MIN_HEIGHT)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .padding(top = topDp + PANEL_TOP_GAP, end = 8.dp)
                    .width(308.dp)
                    .heightIn(max = panelMax)
                    // 面板自己也要吃掉点击，否则点面板内部会穿透到遮罩、把面板关掉。
                    .clickable(interactionSource = panelInteraction, indication = null) {},
            ) {
                Column(Modifier.fillMaxWidth()) {
                    PanelHeader(carrier, nowMillis, tone)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scroll),
                    ) {
                        PanelRow("地址", details.baseUrl)
                        PanelRow("设备", details.maskedDeviceId)
                        PanelRow("通道", if (details.channelReady) "已就绪" else "未就绪")
                        PanelRow("收到帧", formatCount(details.framesIn))
                        PanelRow("发出帧", formatCount(details.framesOut))
                        PanelRow("最近故障", details.lastFailure ?: "无", warn = details.lastFailure != null)
                    }
                    // 动作区在滚动区**外面**：矮窗口下被裁的只能是正文，不能是「重新配对」。
                    PanelFooter(carrier, onRetry, onRepairRequest, onOpenDebug)
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(carrier: CarrierState, nowMillis: Long, tone: IndicatorTone) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        DotWithGlow(tone)
        Text(
            text = pillText(carrier, nowMillis),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // 「已连接」时右侧补一个已连上多久；其他状态没有「时长」这个概念。
        val since = (carrier as? CarrierState.Connected)?.let { formatSince(it.sinceMillis, nowMillis) }
        if (since != null) {
            Text(
                text = since,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PanelRow(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (warn) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PanelFooter(
    carrier: CarrierState,
    onRetry: (() -> Unit)?,
    onRepairRequest: (() -> Unit)?,
    onOpenDebug: (() -> Unit)?,
) {
    // 判定**只看 carrier**（R028 审查 P3-11）：早先写成 `Unpaired && onRepairRequest != null`，
    // 于是调用方只传 `onRetry` 时终态 Unpaired 会退化成显示「重试」——而下面 591 行的注释
    // 明说重试对它不会有结果。判定与「回调传没传」无关，传没传只决定按钮渲染不渲染。
    val repair = carrier is CarrierState.Unpaired
    val retry = !repair && onRetry != null
    if ((!repair || onRepairRequest == null) && !retry && onOpenDebug == null) return
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 配对失效是终态：重试不会有结果，只给「重新配对」（走 AppRoot 的二次确认）。
        if (repair && onRepairRequest != null) {
            PanelButton("重新配对", danger = true, onClick = onRepairRequest)
        }
        if (retry) PanelButton("重试", onClick = onRetry!!)
        if (onOpenDebug != null) PanelButton("诊断", onClick = onOpenDebug)
    }
}

/**
 * 面板底部的等宽按钮。
 *
 * **必须是 `RowScope` 扩展**：`Modifier.weight(1f)` 只在 `RowScope` / `ColumnScope` 里可用，
 * 普通 `@Composable` 函数体不在任何 scope 内（编译器报 `Unresolved reference 'weight'`）。
 */
@Composable
private fun RowScope.PanelButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val container = if (danger) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant
    val content = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val stroke = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = container,
        shape = AppShapes.small,
        border = BorderStroke(1.dp, stroke),
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = content,
            )
        }
    }
}

/** 千位分隔（`1 284`），四位以上才分隔——三位数分隔反而更难读。 */
internal fun formatCount(value: Long): String {
    val text = value.toString()
    if (text.length <= 3) return text
    return text.reversed().chunked(3).joinToString(" ").reversed()
}

/**
 * 「已连接多久」（`3 分 12 秒`）。
 *
 * 只在面板头部用，粒度到秒即可：显示时长是为了让用户判断「这条连接是不是刚掉过」，
 * 精确到毫秒没有意义。
 */
internal fun formatSince(sinceMillis: Long, nowMillis: Long): String {
    val total = ((nowMillis - sinceMillis) / 1000L).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        minutes > 0 -> "$minutes 分 $seconds 秒"
        else -> "$seconds 秒"
    }
}
