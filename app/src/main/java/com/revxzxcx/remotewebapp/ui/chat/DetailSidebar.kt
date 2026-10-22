package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.files.WorkspaceFileListing
import com.revxzxcx.remotewebapp.core.session.ContextDetailLevel
import com.revxzxcx.remotewebapp.core.session.ContextInsights
import com.revxzxcx.remotewebapp.core.session.ContextSegment
import com.revxzxcx.remotewebapp.core.session.TimingSegment
import com.revxzxcx.remotewebapp.core.session.formatCost
import com.revxzxcx.remotewebapp.core.session.formatDuration
import com.revxzxcx.remotewebapp.core.session.formatTokens
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape
import com.revxzxcx.remotewebapp.ui.theme.chipShape

/**
 * 侧边栏的两个页签（用户 m22479：一个功能占一个视图）。
 *
 * public 而非 internal：这个类型出现在 [ChatScreen] 的参数里，而后者是 public 的
 * （`AppRoot` 直接调用）。R041 把页签状态提到 `AppRoot` 之后，它就成了跨 Composable
 * 的公开契约，Kotlin 不允许 public 函数暴露 internal 类型。
 */
enum class DetailTab(val label: String) {
    SESSION("会话详情"),
    FILES("文件"),
}

/**
 * 「对话详细信息」侧边栏。
 *
 * ## 为什么是右侧滑出的抽屉，不是折叠对话
 * R039 我误解过一次：把顶栏按钮做成「折叠整个对话」，用户 m21625 明确纠正——要折的是
 * **信息侧栏**，对话本身不动。两者的差别在于「折叠对话」会让消息区变成一块占位符，
 * 而侧栏只是在右侧盖上来，消息还在、滚动位置还在、输入框也还在。
 *
 * ## 为什么不用 WebView 直接嵌入插件面板
 * 插件面板是 Web 端 React 组件，不是独立 HTML 页；且它的 HTTP 路由要桌面端会话态
 * 认证，手机端直连一律 401（实测）。所以这里用原生 Compose 画，数据走
 * `session/projections`——这条路顺带解决了「插件没装怎么办」：核心自带的投影
 * 能给出降级版，而不是整块空白。
 *
 * @param insights 上下文遥测；null 表示还没读到（或桌面端没给这些投影）。
 * @param loading 是否正在读。
 * @param error 读失败的原因。
 * @param onClose 关闭侧栏。
 * @param onRetry 读失败后重试。
 */
@Composable
internal fun DetailSidebar(
    tab: DetailTab,
    onTabChange: (DetailTab) -> Unit,
    insights: ContextInsights?,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    files: WorkspaceFileListing?,
    filesPath: String,
    filesLoading: Boolean,
    filesError: String?,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (path: String, name: String) -> Unit,
    onFilesUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(SidebarWidth),
        shape = SidebarShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
    ) {
        // R041（用户 m24881）：侧栏现在从 `top: 0` 铺到 `bottom: 0`（原型 `.sheet` 就是
        // `top:0; right:0; bottom:0`），不再被顶栏挤出一个上留边。既然它盖住了整屏，
        // 系统栏也归它自己让——否则标题钻进状态栏、页脚压在导航条底下。
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            SidebarHeader(onClose = onClose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TabPicker(current = tab, onSelect = onTabChange)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    DetailTab.SESSION -> SessionDetailTab(
                        insights = insights,
                        loading = loading,
                        error = error,
                        onRetry = onRetry,
                    )

                    DetailTab.FILES -> FilesTab(
                        listing = files,
                        path = filesPath,
                        loading = filesLoading,
                        error = filesError,
                        onOpenDirectory = onOpenDirectory,
                        onOpenFile = onOpenFile,
                        onUp = onFilesUp,
                    )
                }
            }
            SidebarFooter(insights = insights)
        }
    }
}

/**
 * 侧栏宽度。
 *
 * 360dp 屏上占 318dp，留 42dp 遮罩：**不是铺满**。铺满整屏会让人以为进了新页面，
 * 而这里想要的是「盖上来一层、后面还看得见对话」，遮罩同时是点击关闭的落点
 * （与首页会话抽屉同一套手感）。
 */
private val SidebarWidth = 318.dp

/** 左侧两角圆、右侧贴边直角——从右边滑出，只有朝向屏幕内的那两个角需要收。 */
private val SidebarShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)

@Composable
private fun SidebarHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "对话详情",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        AppIconButton(onClick = onClose, contentDescription = "关闭侧栏") {
            Icon(ChevronRightIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 两个页签的分段控件。
 *
 * 与配对页 `.seg`、首页抽屉同一套语感：等宽两格、选中格用 surface 底 + 主色字。
 * 只有两格是因为侧栏在 318dp 里塞三格会让每个标签都折行（原型阶段试过三格）。
 */
@Composable
private fun TabPicker(current: DetailTab, onSelect: (DetailTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        DetailTab.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .clickable { onSelect(entry) }
                    .semantics { contentDescription = "${entry.label}页签" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ── 会话详情 ──────────────────────────────────────────────────────────────

@Composable
private fun SessionDetailTab(
    insights: ContextInsights?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    when {
        loading && insights == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }

        error != null && insights == null -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "读不到上下文信息\n$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        insights == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "桌面端没有提供上下文信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("occupancy") { OccupancyCard(insights) }
            item("breakdown") { BreakdownCard(insights) }
            if (insights.costDetail != null || insights.cost != null) {
                item("cost") { CostCard(insights) }
            }
            if (insights.timing != null) {
                item("timing") { TimingCard(insights) }
            }
            if (insights.detailLevel != ContextDetailLevel.DETAILED) {
                item("upgrade") { PluginHintCard(insights.detailLevel) }
            }
            item("usage") { UsageCard(insights) }
        }
    }
}

/**
 * 上下文占用卡：当前这一轮吃掉了窗口的多少。
 *
 * 分子必须是 `last.prompt`（本轮），不是 `current.total`（全会话累计）。两者实测差
 * 24 倍，用错会让 1M 窗口显示 373%（见 `ContextInsights` 的 KDoc）。
 */
@Composable
private fun OccupancyCard(insights: ContextInsights) {
    val fraction = insights.fraction ?: 0f
    val percent = insights.percent ?: 0
    DetailCard(title = "上下文占用") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatTokens(insights.usedTokens),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = " / ${formatTokens(insights.contextWindow)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (fraction >= 0.85f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.height(7.dp))

        // ── 窗口构成（用户 m24881 第 1 条）。
        //
        // 原来这里是一条单色进度条，用户看到后说「上下文条没有分类显示构成的样式」。
        // 原型 `docs/R040-效果图.html` 第 2 节里这条是**七段堆叠条 + 图例**：
        // 段宽 = 该段在累计构成里的占比 × 已用比例，所以七段加起来正好是占用率，
        // 剩下的一截留白就是余量（插件的 `.lc-stacked` 也是这个画法）。
        //
        // 为什么能这么算：`breakdown` 是全会话累计，`usedTokens` 是本轮占用，两者
        // 只按**比例**参与，不做同量纲加减——所以 24 倍的量纲差不会污染段宽。
        val segments = insights.segmentsBySize
        if (segments.isNotEmpty()) {
            SegmentStackedBar(segments = segments, fraction = fraction)
            Spacer(Modifier.height(6.dp))
            SegmentLegend(segments = segments, fraction = fraction)
            Spacer(Modifier.height(6.dp))
        } else {
            OccupancyBar(fraction = fraction)
            Spacer(Modifier.height(6.dp))
        }

        Row(Modifier.fillMaxWidth()) {
            Text(
                text = if (insights.hasTurnUsage) "本轮请求" else "累计（桌面端未给本轮数据）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "余量 ${formatTokens(insights.remaining ?: 0)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 占用进度条。85% 以上转错误色——那是「下一轮可能被压缩」的临界线。 */
@Composable
private fun OccupancyBar(fraction: Float) {
    val danger = fraction >= 0.85f
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.extraSmall)
                .background(
                    if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

/**
 * 窗口构成条的段宽（占整条的比例，0..1）。
 *
 * 每段 = `该段 token ÷ 累计总量 × 已用比例`。与 [insights.segmentsBySize] 同序。
 * 抽成纯函数是为了能在 JVM 单测里直接验算——UI 里算权重无法断言。
 *
 * 已用比例会被夹到 0..1：桌面端偶尔给出超窗口的瞬时值（压缩前的最后一帧），
 * 不夹会让剩下那一截变成负权重、整条塌掉。
 */
internal fun segmentBarShares(tokens: List<Long>, total: Long, fraction: Float): List<Float> {
    if (total <= 0L) return tokens.map { 0f }
    val used = fraction.coerceIn(0f, 1f)
    return tokens.map { token ->
        if (token <= 0L) 0f else (token.toFloat() / total.toFloat()) * used
    }
}

/**
 * 窗口构成的堆叠条：七段（降级时三段）紧贴，余量留白。
 *
 * 与「累计构成」卡里的 [StackedBar] 不是同一条：那条表达「全量内部构成」，
 * 这条表达「当前窗口被谁占了」。同一套配色、同一个画法，量纲不同。
 */
@Composable
private fun SegmentStackedBar(segments: List<ContextSegment>, fraction: Float) {
    val total = segments.sumOf { it.tokens }
    val shares = segmentBarShares(segments.map { it.tokens }, total, fraction)
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(ContextPalette.track.copy(alpha = 0.18f)),
    ) {
        segments.forEachIndexed { index, segment ->
            val share = shares[index]
            if (share <= 0f) return@forEachIndexed
            Box(
                Modifier
                    // 用 fillMaxWidth(fraction) 而不是 weight：段宽本身就是「占整条的
                    // 多少」，直接给比例才不会因为兄弟节点个数变化而重新归一化。
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .background(ContextPalette.ofSegment(segment.key)),
            )
        }
    }
}

/**
 * 窗口构成的图例：非零段全部列出（原型 `.legend7` 也是 flex-wrap 全列）。
 *
 * 不截断成「前 N 段 + 其余」：用户 m24881 投诉的正是「与原型不一样」，而原型
 * `docs/R040-效果图.html` 第 2 节里七枚彩色图例是**一个不少**地排两行。真实会话里
 * 小段只有 0.0x%，截断后就只剩「其余 3 段」这种读不出信息的占位。
 *
 * 0.1% 的段在 318dp 宽的条上不足 1px，点不中也看不清——图例是这一块的文字出口。
 */
@Composable
private fun SegmentLegend(segments: List<ContextSegment>, fraction: Float) {
    val total = segments.sumOf { it.tokens }
    if (total <= 0L) return
    val used = fraction.coerceIn(0f, 1f)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        segments.filter { it.tokens > 0L }.forEach { segment ->
            val share = segment.tokens.toFloat() / total.toFloat() * used
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ContextPalette.ofSegment(segment.key)),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${segment.label} ${formatPercent(share)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 百分比文案。
 *
 * 小段必须给一位小数：《助手 8.9% / 工具结果 6.2% / 注入 0.5%》里最后一段只有
 * 0.01%，取整后全变 0%，用户会以为那几段不存在（原型里也是这么标的）。
 */
internal fun formatPercent(share: Float): String {
    val percent = share * 100f
    if (percent <= 0f) return "0%"
    // 分档看的是**渲染之后**的值，不是原始值：9.99% 用一位小数渲染出来就是
    // `10.0%`，与 10% 这一档自相矛盾。所以先按档渲染，再按渲染结果重新判档。
    val text = when {
        percent >= 10f -> String.format(java.util.Locale.US, "%.0f", percent)
        percent >= 1f -> String.format(java.util.Locale.US, "%.1f", percent)
        else -> String.format(java.util.Locale.US, "%.2f", percent)
    }
    // 去掉多余的小数零：`10.0` → `10`、`6.40` → `6.4`、`1.00` → `1`。
    // 不这么做的话，「10%」这个数字会以 `10.0%` 的形式出现，读者会以为两者不同档。
    val trimmed = if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    return "$trimmed%"
}

/**
 * 累计构成卡：堆叠条 + 圆环 + 图例。
 *
 * 为什么同时给条和圆环：条擅长表达「占满窗口的比例」（与上面的占用条同一把尺），
 * 圆环擅长表达「内部构成」（比例高低一眼可比）。用户 m22134 明确要求后者是圆环、
 * 可点、有放大与文字提示。
 */
@Composable
private fun BreakdownCard(insights: ContextInsights) {
    val segments = insights.segmentsBySize
    var selected by remember(insights) { mutableStateOf<String?>(null) }
    DetailCard(
        title = "累计构成",
        trailing = when (insights.detailLevel) {
            ContextDetailLevel.DETAILED -> "全量 ${formatTokens(insights.breakdownTotal)}"
            ContextDetailLevel.BASIC -> "全量 ${formatTokens(insights.breakdownTotal)}"
            ContextDetailLevel.MINIMAL -> null
        },
    ) {
        if (segments.isEmpty()) {
            Text(
                text = "桌面端没有提供构成明细。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DetailCard
        }

        StackedBar(segments = segments, total = insights.breakdownTotal)
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentDonut(
                slices = segments.map {
                    DonutSlice(it.key, it.tokens, ContextPalette.ofSegment(it.key))
                },
                size = 104.dp,
                selected = selected,
                centerTop = if (selected == null) {
                    formatTokens(insights.breakdownTotal)
                } else {
                    val seg = segments.first { it.key == selected }
                    "${(insights.shareOf(seg) * 100).toInt()}%"
                },
                centerSub = if (selected == null) "累计" else "占累计",
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                segments.forEach { segment ->
                    val share = insights.shareOf(segment)
                    DonutLegendRow(
                        color = ContextPalette.ofSegment(segment.key),
                        label = segment.label,
                        value = "${formatTokens(segment.tokens)} · ${(share * 100).let {
                            if (it >= 10) it.toInt().toString() else String.format(java.util.Locale.US, "%.1f", it)
                        }}%",
                        selected = segment.key == selected,
                        onClick = {
                            selected = if (selected == segment.key) null else segment.key
                        },
                        modifier = Modifier.height(19.dp).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 堆叠上下文条。
 *
 * 照抄插件的 `.lc-stacked`：10dp 高、各段紧贴无间隙、剩余空间留白（不是虚线）。
 * 段间用极细的缝（0.5dp）分开，避免两段颜色接近时糊成一段。
 */
@Composable
private fun StackedBar(segments: List<ContextSegment>, total: Long) {
    if (total <= 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(ContextPalette.track.copy(alpha = 0.18f)),
    ) {
        segments.forEach { segment ->
            val weight = (segment.tokens.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            if (weight <= 0f) return@forEach
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .padding(end = 0.5.dp)
                    .background(ContextPalette.ofSegment(segment.key)),
            )
        }
        val used = segments.sumOf { it.tokens }.toFloat() / total.toFloat()
        if (used < 1f) {
            Box(Modifier.weight((1f - used).coerceAtLeast(0.0001f)).fillMaxHeight())
        }
    }
}

/** 费用卡：KPI + 四行细分 + 按模型。插件里费用也是这个形态（不是环图）。 */
@Composable
private fun CostCard(insights: ContextInsights) {
    val cost = formatCost(insights.cost)
    DetailCard(title = "费用", trailing = cost) {
        val detail = insights.costDetail
        val usage = insights.usage
        if (detail != null) {
            DetailRow("未缓存输入", formatTokens(usage.uncachedInput))
            DetailRow("缓存读取", formatTokens(usage.cacheRead))
            DetailRow("输出", formatTokens(usage.output))
            detail.models.filter { it.cost > 0 }.forEach { model ->
                DetailRow(model.name, formatCost(model.cost).orEmpty())
            }
            if (!detail.fullyPriced) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "价表里没有 ${detail.unpricedModels.joinToString("、")}，费用偏低。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            DetailRow("未缓存输入", formatTokens(usage.uncachedInput))
            DetailRow("缓存读取", formatTokens(usage.cacheRead))
            DetailRow("输出", formatTokens(usage.output))
        }
    }
}

/** 耗时卡：六段圆环（插件同款）或降级的两段。 */
@Composable
private fun TimingCard(insights: ContextInsights) {
    val timing = insights.timing ?: return
    val segments = timing.segments
    var selected by remember(timing) { mutableStateOf<String?>(null) }
    DetailCard(
        title = "耗时",
        trailing = timing.tokensPerSecond?.let { "$it tok/s" },
    ) {
        if (segments.isEmpty()) {
            Text(
                text = "桌面端没有提供耗时数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DetailCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentDonut(
                slices = segments.map {
                    DonutSlice(it.key, it.millis, ContextPalette.ofTiming(it.key))
                },
                size = 92.dp,
                selected = selected,
                centerTop = if (selected == null) {
                    formatDuration(if (timing.detailAvailable) timing.wallMs else timing.segmentsTotal)
                } else {
                    val seg = segments.first { it.key == selected }
                    val pct = if (timing.segmentsTotal > 0) {
                        (seg.millis.toDouble() / timing.segmentsTotal * 100).toInt()
                    } else {
                        0
                    }
                    "$pct%"
                },
                centerSub = if (selected == null) "墙钟" else "占活跃",
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                segments.forEach { segment ->
                    val share = if (timing.segmentsTotal > 0) {
                        segment.millis.toFloat() / timing.segmentsTotal
                    } else {
                        0f
                    }
                    DonutLegendRow(
                        color = ContextPalette.ofTiming(segment.key),
                        label = segment.label,
                        value = "${formatDuration(segment.millis)} · ${(share * 100).toInt()}%",
                        selected = segment.key == selected,
                        onClick = {
                            selected = if (selected == segment.key) null else segment.key
                        },
                        modifier = Modifier.height(19.dp).padding(horizontal = 4.dp),
                    )
                }
            }
        }
        if (!timing.detailAvailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "只有「模型 / 工具」两段：细分耗时来自上下文洞察插件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 降级态提示。
 *
 * 用 `tertiary`（琥珀）而不是普通说明文字：用户 m22479 要求「用比较醒目的文案提示
 * 用户安装插件」。但**不能做成可点的跳转**——插件管理路由 `/api/plugin-manager`
 * 在服务端属 `LOCAL_ONLY_PREFIXES`，手机端物理不可达（实测 403）。承诺一个点不进去
 * 的按钮比不给按钮更糟。
 */
@Composable
private fun PluginHintCard(level: ContextDetailLevel) {
    Surface(
        shape = chipShape,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (level == ContextDetailLevel.BASIC) {
                    "装上上下文洞察插件，这里会更详细"
                } else {
                    "装上上下文洞察插件，才能看到构成"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = if (level == ContextDetailLevel.BASIC) {
                    "现在只能分成「消息 / 工具定义 / 系统提示」三段，耗时只有模型与工具两项。" +
                        "装 dsh-context 后可拆成七段，并多出逐工具耗时与输出速度。"
                } else {
                    "桌面端没有提供任何构成投影。装 dsh-context 后可看到七段构成、费用与耗时分布。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "在桌面端「插件」页安装即可，装完不需要重新配对。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

/** 用量卡：四个计费口径 + 缓存命中率。 */
@Composable
private fun UsageCard(insights: ContextInsights) {
    val usage = insights.usage
    DetailCard(title = "Token 用量") {
        DetailRow("未缓存输入", formatTokens(usage.uncachedInput))
        DetailRow("缓存读取", formatTokens(usage.cacheRead))
        DetailRow("缓存写入", formatTokens(usage.cacheWrite))
        DetailRow("输出", formatTokens(usage.output))
        usage.cacheHitRate?.let { rate ->
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(4.dp))
            DetailRow("缓存命中率", "${(rate * 100).toInt()}%")
        }
        if (insights.turns > 0 || insights.toolCalls > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${insights.turns} 轮 · ${insights.toolCalls} 次工具 · " +
                    "${insights.humanInputs} 次输入 · ${insights.answers} 次回答",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 文件 ──────────────────────────────────────────────────────────────────

/**
 * 文件页签：以会话 cwd 为根的目录浏览。
 *
 * 只列一层，点目录再列下一层——服务端 `workspaceFiles/list` 就是按层返回的，
 * 一次性递归整棵树在 `F:\bot` 这种仓库上会返回 826 项（实测），既慢又没人看。
 *
 * 点文件**不在这里预览**：交给已有的 `FileViewerScreen`（它已经处理了 md 渲染、
 * 代码高亮、图片、分页，再写一遍就是第二套要维护的渲染器）。
 */
@Composable
private fun FilesTab(
    listing: WorkspaceFileListing?,
    path: String,
    loading: Boolean,
    error: String?,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (path: String, name: String) -> Unit,
    onUp: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (path.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(onClick = onUp)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "上一级",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = if (path.isEmpty()) "工作区根" else path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        when {
            loading && listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }

            error != null -> Box(
                Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "读不到这个目录\n$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            listing == null || listing.entries.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这个目录是空的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                // 目录在前、文件在后，各自按名称排序：这是文件管理器最通行的排法，
                // 服务端返回的顺序不保证。
                val directories = listing.entries.filter { it.isDirectory }.sortedBy { it.name }
                val files = listing.entries.filterNot { it.isDirectory }.sortedBy { it.name }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(directories, key = { "d:${it.name}" }) { entry ->
                        FileRow(
                            name = entry.name,
                            detail = "目录",
                            isDirectory = true,
                            onClick = { onOpenDirectory(joinPath(path, entry.name)) },
                        )
                    }
                    items(files, key = { "f:${it.name}" }) { entry ->
                        FileRow(
                            name = entry.name,
                            detail = entry.size?.let(::formatSize).orEmpty(),
                            isDirectory = false,
                            onClick = { onOpenFile(joinPath(path, entry.name), entry.name) },
                        )
                    }
                }
            }
        }
    }
}

/** 拼一个相对路径。根是空串时不能拼出 `/name`——服务端会当成绝对路径拒绝。 */
internal fun joinPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/**
 * 上一级目录（相对路径，根是空串）。
 *
 * 抽出来是因为 R041 之后它有两个调用方：`ChatViewModel.filesUp()` 真的去列上一级，
 * 而 `ChatScreen` 的 `onFilesUp` 还要把「向上走一层」这件事同步回 `AppRoot`（否则从
 * 文件页返回时会恢复到刚离开的那一层）。两处各写一遍 `substringBeforeLast('/', "")`
 * 迟早会走偏——比如某天给根加上 `"."` 的表示法，只改一处就错。
 *
 * 没有单独的「已在根就直接返回」分支：[String.substringBeforeLast] 的
 * `missingDelimiterValue` 已经是空串，`""` 进来自然还是 `""`。曾写过那个早退，
 * 变异测试证明删掉它一条用例都不会红——它是死代码，所以不留。
 */
internal fun parentPath(current: String): String = current.substringBeforeLast('/', "")

/** 文件大小：与工作区文件页保持一致的口径（B / KB / MB）。 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun FileRow(
    name: String,
    detail: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isDirectory) FolderIcon else FileIcon,
            contentDescription = null,
            tint = if (isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ── 共用小块 ──────────────────────────────────────────────────────────────

@Composable
private fun SidebarFooter(insights: ContextInsights?) {
    val model = insights?.model
    if (model == null) return
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${insights.provider ?: ""}/$model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            insights.maxOutputTokens?.let {
                Text(
                    text = "最大输出 ${formatTokens(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    trailing: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = bubbleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
