package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.common.rememberAnimationsEnabled
import com.revxzxcx.remotewebapp.ui.theme.Motion

/**
 * 对话详情侧栏里所有「构成类」图表的调色板与几何。
 *
 * ## 配色为什么是这些十六进制
 * 不是随手选的，是**照抄 `dsh-context` 插件**（`lib/client.js` 里圆环函数用的七段色板）。
 * 用户 m22479 明确要求「渲染插件格式的上下文条」，所以同一个指标在 App 里和在桌面端
 * 插件面板里必须是同一种颜色，否则两边对照着看会互相矛盾。
 *
 * 插件的色板写在 CSS 变量里（`var(--color-indigo-500)` 之类，Tailwind 的 oklch 值），
 * 这里换算是 oklch → OKLab → 线性 sRGB → gamma 的完整结果，不是近似取值。
 *
 * ## 为什么颜色跟着**段位键**而不是跟着大小
 * 同一个会话两次打开侧栏，颜色必须指代同一个东西。所以段位顺序固定，图例才按大小排
 * （见 `ContextInsights.segments` 的 KDoc）。
 */
internal object ContextPalette {

    /** 系统提示（插件 `indigo-500`）。 */
    val system = Color(0xFF615FFF)

    /** 工具定义（插件 `amber-500`）。 */
    val tools = Color(0xFFFE9A00)

    /** 用户输入（插件 `green-500`）。 */
    val user = Color(0xFF00C950)

    /** 注入（插件 `purple-500`）。 */
    val inject = Color(0xFFAD46FF)

    /** skill（插件 `orange-500`）。 */
    val skill = Color(0xFFFF6900)

    /** 助手输出（插件 `blue-500`）。 */
    val assistant = Color(0xFF2B7FFF)

    /** 工具结果（插件 `teal-500`）。 */
    val tool = Color(0xFF00BBA7)

    /**
     * 降级态的「消息」段。
     *
     * 服务端把助手输出、工具结果、注入、skill、用户输入合并成 `messageTokens` 一个数，
     * 拆不开。用助手输出的蓝——它在这五者里占绝对多数（实测 2.1M / 3.6M），
     * 用蓝色比新造一个颜色更容易让人对上「这大概就是那些东西」。
     */
    val message = assistant

    /** 耗时：模型等待（`blue-500`）。 */
    val ttft = Color(0xFF2B7FFF)

    /** 耗时：模型思考（`violet-500`）。 */
    val reasoning = Color(0xFF8E51FF)

    /** 耗时：模型回复（`pink-500`）。 */
    val text = Color(0xFFF6339A)

    /** 耗时：工具参数（`amber-500`）。 */
    val toolArg = Color(0xFFFE9A00)

    /** 耗时：工具执行（`teal-500`）。 */
    val toolExec = Color(0xFF00BBA7)

    /** 耗时：其他开销（`slate-400`）。 */
    val other = Color(0xFF90A1B9)

    /** 降级态耗时里的「模型」段（`blue-500`）。 */
    val llm = ttft

    /** 空态轨道（插件 `neutral-500`，18% 不透明）。 */
    val track = Color(0xFF737373)

    /** 按段位键取色；未知键回落到轨道色（不会因为服务端加段就崩）。 */
    fun ofSegment(key: String): Color = when (key) {
        "system" -> system
        "tools" -> tools
        "user" -> user
        "inject" -> inject
        "skill" -> skill
        "assistant" -> assistant
        "tool" -> tool
        "message" -> message
        else -> track
    }

    /** 按耗时段位键取色。 */
    fun ofTiming(key: String): Color = when (key) {
        "ttft" -> ttft
        "reasoning" -> reasoning
        "text" -> text
        "toolarg" -> toolArg
        "tools" -> toolExec
        "other" -> other
        "llm" -> llm
        else -> track
    }
}

/**
 * 一段弧的起点与长度，单位是「整圈 = 100」的百分比。
 *
 * 抽成纯函数是为了能单测：`Canvas` 画出来的像素在 Robolectric 下读不到，弧长算错
 * （少一段、叠一段、首尾对不上）只能靠这个函数守。
 *
 * @param share 这一段占整圈的比例，0..1。
 * @param consumed 在这段之前已经画掉的百分比（从 12 点方向顺时针累加）。
 * @param gap 相邻段之间留的缝，单位同 [share] 的百分比。段数 > 1 时才有意义。
 */
internal data class DonutArc(val startPercent: Float, val sweepPercent: Float)

/**
 * 算出第 [index] 段要画的弧。
 *
 * 算法照抄插件：每段先按占比算出长度，再在**两端各削掉一半缝**，起点相应后移半个缝。
 * 缝隙上限是 `min(0.5, len/4)`（插件原式 `Math.min(0.5/2, len/4)` 里的 `0.5` 是缝宽），
 * 目的是让极小的段（比如占比 0.1% 的系统提示）不至于因为削缝变成负长度而消失。
 *
 * 削缝之后 [DonutArc.sweepPercent] 可能为 0（份额太小、缝吃光了）——调用方应跳过它，
 * 否则 `drawArc` 会画出一条发丝线冒充「这一段很大」。
 */
internal fun donutArc(
    shares: List<Float>,
    index: Int,
    gaps: Boolean = true,
): DonutArc {
    if (index !in shares.indices) return DonutArc(0f, 0f)
    var consumed = 0f
    for (i in 0 until index) consumed += shares[i] * 100f
    val len = shares[index] * 100f
    if (!gaps || shares.size <= 1) return DonutArc(consumed, len)
    val gap = minOf(0.5f / 2f, len / 4f)
    return DonutArc(consumed + gap, (len - gap * 2f).coerceAtLeast(0f))
}

/**
 * 构成圆环。
 *
 * ## 为什么自己画而不用现成库
 * 工程不引 Compose 图表库（多一个依赖就多一份体积与一个升级变量），而这里要的只有
 * 「一堆按百分比切开的圆弧」，`Canvas.drawArc` 十几行就够。
 *
 * ## 为什么起点在 12 点方向
 * 插件也是从 12 点开始顺时针画。用户已经习惯那个读法：最大的段（助手输出）从正上方
 * 开始向右铺开，一眼能估出「大概一半多」。
 *
 * @param slices 要画的段，顺序即绘制顺序。
 * @param selected 当前高亮段的键；非 null 时其余段变暗、该段加粗。
 * @param size 直径。
 * @param centerTop 圆心上方的大字（总 token / 墙钟）。
 * @param centerSub 圆心下方的小字。
 */
@Composable
internal fun SegmentDonut(
    slices: List<DonutSlice>,
    size: Dp,
    centerTop: String,
    centerSub: String,
    modifier: Modifier = Modifier,
    selected: String? = null,
    track: Color = ContextPalette.track.copy(alpha = 0.18f),
) {
    val total = slices.sumOf { it.value }
    val shares = remember(slices) {
        if (total <= 0) emptyList() else slices.map { it.value.toFloat() / total.toFloat() }
    }

    // 选中时其余段淡出、选中的那段加粗。用 `animateFloatAsState` 让切换不突兀，
    // 但**动画时长取 Motion.FAST**：侧栏是快速查看的地方，慢动画会拖住操作。
    val animations = rememberAnimationsEnabled()
    val dim by animateFloatAsState(
        targetValue = if (selected == null) 1f else 0.28f,
        animationSpec = tween(if (animations) Motion.FAST else 0),
        label = "donutDim",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokeWidth = this.size.minDimension * (4f / 42f)
            val radius = this.size.minDimension * (15.9155f / 42f)
            val diameter = radius * 2f
            val topLeft = Offset(
                x = (this.size.width - diameter) / 2f,
                y = (this.size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)

            // 空态轨道：占比合计为 0（比如刚建的会话）时也要有个环，否则那块地方是空的，
            // 侧栏看起来像加载失败。
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )

            shares.forEachIndexed { index, _ ->
                val slice = slices[index]
                val arc = donutArc(shares, index)
                if (arc.sweepPercent <= 0f) return@forEachIndexed
                val isSelected = slice.key == selected
                val width = if (isSelected) strokeWidth * 1.6f else strokeWidth
                // 12 点方向 = -90°；`startPercent` 直接换算成角度。
                val startAngle = -90f + arc.startPercent * 3.6f
                drawArc(
                    color = slice.color.copy(alpha = if (selected == null || isSelected) 1f else dim),
                    startAngle = startAngle,
                    sweepAngle = arc.sweepPercent * 3.6f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = width, cap = StrokeCap.Butt),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = centerTop,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = centerSub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 圆环的一段：键（用于配色与选中）、数值、颜色。 */
internal data class DonutSlice(val key: String, val value: Long, val color: Color)

/**
 * 圆环右侧的一行图例。
 *
 * **图例是可点的，而且这是主要入口**：圆环上最细的段只有 0.1%（1M 窗口下约 1000 token），
 * 换算成弧长不到半度、屏幕上不到 1px，手指根本点不中。图例行高 20dp 起，才是手机上真正
 * 能操作的目标（这条来自 R040 原型评审）。
 */
@Composable
internal fun DonutLegendRow(
    color: Color,
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(backgroundColor)
            .semantics { contentDescription = "$label $value" }
            // 图例行必须可点：圆环上 0.1% 的段在屏幕上不到 1px，手指点不中，
            // 图例才是手机上真正的操作入口。
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        // 名字让位给数字：两者都用 weight(1f) 时各占一半，`stepfun:step-5-preview`
        // 这种长模型名会被截成 `stepfun:step-`（真机实测），右边那串
        // 「$131.00 · 90.4%」也因为只有一半宽而丢掉占比、只剩一个「·」。
        // 数字是不可再压缩的信息（截断后毫无意义），名字截断还能认出是谁，
        // 所以名字让出剩余空间并省略号收尾，数字取自然宽度全量显示。
        //
        // 名字必须 `fill = true`（默认）：写成 `weight(1f, fill = false)` 时，
        // 短名字只占自身宽度，行尾剩下的空间无人占用，数字就紧贴名字之后——
        // 于是同一列里「缓存读」的数字偏左、「stepfun:step-5-preview」的数字偏右，
        // 三行金额不再成列（审查 P3）。填满之后所有数字都停在右边同一条线上。
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
