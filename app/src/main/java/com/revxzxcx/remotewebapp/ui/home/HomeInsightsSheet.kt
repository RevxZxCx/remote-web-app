package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.ContextTotals
import com.revxzxcx.remotewebapp.core.session.DayActivity
import com.revxzxcx.remotewebapp.core.session.InsightsRange
import com.revxzxcx.remotewebapp.core.session.ModelUsage
import com.revxzxcx.remotewebapp.core.session.SessionRank
import com.revxzxcx.remotewebapp.core.session.TokenUsage
import com.revxzxcx.remotewebapp.core.session.formatCost
import com.revxzxcx.remotewebapp.core.session.formatTokens
import com.revxzxcx.remotewebapp.ui.chat.ChevronRightIcon
import com.revxzxcx.remotewebapp.ui.chat.ContextPalette
import com.revxzxcx.remotewebapp.ui.chat.DonutLegendRow
import com.revxzxcx.remotewebapp.ui.chat.DonutSlice
import com.revxzxcx.remotewebapp.ui.chat.SegmentDonut
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape
import java.util.Locale

/**
 * 首页右滑抽屉（T076 / 原型 R050）的两个页签。
 *
 * 只有两页，与聊天页的 [com.revxzxcx.remotewebapp.ui.chat.DetailTab] 不同：那边是
 * 「当前会话」与「会话里的文件」，这边是「所有会话的汇总」与「按会话拆开」。
 * 两者共用同一套抽屉手感（宽度、圆角、分段控件），但**职责不重叠**——
 * 首页看的是跨会话总量，聊天页看的是单会话七段构成。
 */
/**
 * 活跃度图用哪个指标画柱高。
 *
 * ## 为什么要有这个开关（R052，用户 m37499）
 *
 * 用户原话：「当前上下文洞察侧边栏显示的近14天活跃度是用活跃会话数展示的，而插件也可以
 * 用步数来展示，我希望你增加这个切换功能，并且将步数作为默认的展示逻辑」。
 *
 * ## 「步数」到底是什么
 *
 * 是**请求数**，与插件同一口径：dsh-context 的全局页源码是
 * `let u=e=>r==="sessions"?e.sessions:e.requests` —— 切到「步数」时它拿的是
 * `requests` 而不是 `sessionStats.steps`。App 侧对应
 * `contextActivity.days[day].requests`（见 `ContextInsights.dailyRequests`）。
 *
 * 两者不是同一件事的两种写法：实测最近 14 天合计 28,000 步 / 69 个会话日，
 * 一天可以只有 2 条会话却跑 2000 步。步数更能反映「这天有多忙」。
 */
internal enum class ActivityMetric(val label: String) {
    STEPS("步数"),
    SESSIONS("会话"),
    ;

    /** 某一天的取值，柱高按它算。 */
    fun valueOf(day: DayActivity): Int = when (this) {
        STEPS -> day.steps
        SESSIONS -> day.sessions
    }

    /** 读屏文案：一天的活动量。 */
    fun describe(day: DayActivity): String = when (this) {
        STEPS -> "${day.day} 跑了 ${day.steps} 步"
        SESSIONS -> "${day.day} 有 ${day.sessions} 个会话活动"
    }

    /** 底部一句话总结（峰值）。 */
    fun peakText(day: DayActivity): String = when (this) {
        STEPS -> "峰值 ${day.steps} 步 · ${day.day}"
        SESSIONS -> "峰值 ${day.sessions} 个 · ${day.day}"
    }

    /**
     * 整张图在这个口径上**一个数都没有**时，底部该说的话。
     *
     * 为什么不能统一写「最近 14 天没有活动」：步数的唯一来源是
     * `contextActivity.days[day].requests`，而它是 **dsh-context 插件**才下发的投影
     * （R040 取证：核心 asar 里 `contextActivity` 命中 0 次）；「会话数」来自
     * `session/list` 顶层的 `updatedAt`，核心自带。于是没装插件时会出现
     * 「一条会话都没少、步数却全是 0」的画面，此时说「没有活动」是**错的**。
     *
     * 需求 3 把步数设成默认口径之后，这个组合从「用户手动点才看得到」变成
     * 「打开抽屉就看得见」，所以必须说清楚差在哪、以及怎么绕过。——R052 审查 P3。
     */
    fun emptyText(activity: List<DayActivity>): String = when (this) {
        STEPS -> if (activity.any { it.sessions > 0 }) {
            "读不到步数 · 可切到「会话」"
        } else {
            "最近 14 天没有活动"
        }
        SESSIONS -> "最近 14 天没有活动"
    }

    companion object {
        /** UI 里按这个顺序渲染分段控件。 */
        val ordered: List<ActivityMetric> = listOf(STEPS, SESSIONS)

        /**
         * 打开抽屉时默认用哪个口径。
         *
         * 用户 m37499 定的「将步数作为默认的展示逻辑」，**这里是唯一来源**。
         * 早先默认值直接写在 `HomeScreen` 的 `rememberSaveable` 里，等于把同一条产品
         * 决定散落在两个文件；改成从 [ordered] 推导后，换口径只需要动这一处，
         * 单测也能从组件层直接守住（不依赖任何 Compose 宿主）。
         */
        val default: ActivityMetric get() = ordered.first()
    }
}

internal enum class InsightsTab(val label: String) {
    OVERVIEW("总览"),
    SESSIONS("会话"),
}

/**
 * 总上下文洞察抽屉：从首页右滑（或点顶栏 ◔ 按钮）拉出来的 320dp 浮起面板。
 *
 * ## 为什么是抽屉而不是新页面
 * 它是「扫一眼总量」的视图，不是要停留很久的工作区。做成新页面就要处理返回栈、
 * 顶栏标题、以及「返回后首页滚到哪」这三个问题，而这个视图的价值恰恰在于
 * **盖在首页上、随时可以划走**。与 R040 的会话详情抽屉同一套交互，用户不用学第二遍。
 *
 * ## 数据从哪来
 * 全部来自首页已经在拉的 `session/list` 的四个投影，**零新增 RPC**。`totals` 由
 * 调用方用 [com.revxzxcx.remotewebapp.core.session.ContextTotalsCalculator] 算好传进来，
 * 本组件是纯展示——这样切换统计范围只重算一个纯函数，不发请求、不等网络。
 *
 * @param totals 当前范围下的聚合结果。
 * @param range 统计范围（近 7 天 / 近 30 天 / 全部）。
 * @param onRangeChange 切范围。只重算本地聚合，调用方不需要重新请求。
 * @param tab 当前页签。
 * @param onTabChange 切页签。
 * @param loading 首次加载中（`totals` 为空时才有意义）。
 * @param onClose 关闭抽屉。
 * @param activityMetric 活跃度图当前的指标（步数 / 会话）。
 * @param onActivityMetricChange 切换指标；**只重画柱子**，不重新聚合、不发请求。
 * @param onOpenSession 点某个会话排行行 → 打开那个会话。
 */
@Composable
internal fun HomeInsightsSheet(
    totals: ContextTotals,
    range: InsightsRange,
    onRangeChange: (InsightsRange) -> Unit,
    tab: InsightsTab,
    onTabChange: (InsightsTab) -> Unit,
    activityMetric: ActivityMetric,
    onActivityMetricChange: (ActivityMetric) -> Unit,
    loading: Boolean,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight().width(SheetWidth),
        shape = SheetShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            SheetHeader(totals = totals, onClose = onClose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SegPicker(
                options = InsightsTab.entries.map { it.label },
                selectedIndex = InsightsTab.entries.indexOf(tab),
                onSelect = { onTabChange(InsightsTab.entries[it]) },
                group = "页签选项",
            )
            RangePicker(current = range, onSelect = onRangeChange)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    totals.isEmpty && loading -> CenteredHint("正在读取用量…", spinner = true)
                    totals.isEmpty -> CenteredHint("这个范围里还没有用量数据。")
                    tab == InsightsTab.OVERVIEW -> OverviewTab(
                        totals = totals,
                        activityMetric = activityMetric,
                        onActivityMetricChange = onActivityMetricChange,
                    )
                    else -> SessionsTab(totals = totals, onOpenSession = onOpenSession)
                }
            }
            SheetFooter()
        }
    }
}

/**
 * 抽屉宽度。
 *
 * 360dp 屏上占 320dp，留 40dp 遮罩——比会话详情侧栏（318dp）宽 2dp 是刻意的：
 * 那边塞的是「七段构成」，这边要塞「三张圆环 + 图例」，图例每行最长的模型名
 * （`deepseek-v4.1-flash`）在 318dp 里会被压成两行。仍然不铺满，理由同 R040：
 * 铺满会让人以为进了新页面，而遮罩本身是「点一下关掉」的落点。
 */
internal val SheetWidth = 320.dp

private val SheetShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)

@Composable
private fun SheetHeader(totals: ContextTotals, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "上下文洞察",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // 副标题把「这份汇总是多少个会话算出来的」摆在最显眼处：后面所有
                // 圆环与金额都建立在这个分母上，用户先看到它才不会把「32 个会话」误读成
                // 首页列表的长度（那是含子代理与空会话的 186 条）。
                text = if (totals.isEmpty) {
                    "暂时没有可统计的会话"
                } else {
                    "${totals.sessionCount} 个会话 · ${totals.turns} 轮 ${totals.steps} 步"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppIconButton(onClick = onClose, contentDescription = "关闭上下文洞察") {
            Icon(
                ChevronRightIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 等宽分段控件（2 格或 3 格）。
 *
 * 与聊天页 `TabPicker`、配对页 `.seg` 同一套语感：选中格用 surface 底 + 主色字。
 * 抽成通用的是因为这里要同时容纳「总览/会话」两格与「近 7 天/近 30 天/全部」三格，
 * 而它们除了格数完全一致。
 */
@Composable
private fun SegPicker(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * 这一组选择器的名字，会进每项的 `contentDescription`（「会话 选项」→「会话 指标选项」）。
     *
     * 抽屉里同时存在两组选择器：页签里有「会话」，活跃度指标里也有「会话」。
     * 不带组名时读屏听到的是两个一模一样的「会话 选项」，分不清点的是哪个；
     * 测试也没法唯一命中。
     */
    group: String = "选项",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (compact) 0.dp else 8.dp,
                bottom = if (compact) 0.dp else 8.dp,
            )
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 30.dp else 34.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = "$label $group" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 统计范围三档。放在页签下面是因为它同时作用于两个页签。 */
@Composable
private fun RangePicker(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
    SegPicker(
        options = InsightsRange.ordered.map { it.label },
        selectedIndex = InsightsRange.ordered.indexOf(current),
        onSelect = { onSelect(InsightsRange.ordered[it]) },
        compact = true,
        group = "范围选项",
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CenteredHint(text: String, spinner: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 总览 ──────────────────────────────────────────────────────────────────

@Composable
private fun OverviewTab(
    totals: ContextTotals,
    activityMetric: ActivityMetric,
    onActivityMetricChange: (ActivityMetric) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TokenDonutCard(totals)
        if (totals.hasCost) CostDonutCard(totals) else CostUnavailableCard()
        TimingDonutCard(totals)
        ActivityCard(
            activity = totals.activity,
            metric = activityMetric,
            onMetricChange = onActivityMetricChange,
        )
    }
}

/**
 * Token 用量构成环。
 *
 * ## 两种口径，取决于装没装插件
 *
 * - **有 `costUsage.byModel`**（装了 `dsh-cost-meter`）：按模型分，这是信息量最大的一版，
 *   用户能直接看出「谁在吃额度」。
 * - **没有**：退到 `tokenUsage` 的**四类口径**（缓存读 / 未缓存输入 / 输出 / 缓存写）。
 *
 * 第二条是必须的：`tokenUsage` 是**核心自带**的投影（实测 32/32 命中），而模型维度
 * 只存在于 `costUsage` 里。早先只按模型画，没装插件时 `models` 为空 → 圆环一根弧都不画、
 * 图例一片空白，只剩圆心那个数字，看着像加载失败。四类口径虽然粗，但它是真的、永远有。
 *
 * 分母一律用 [ContextTotals.tokens] 的 total：四类之和与模型之和在正常情况下相等，
 * 用同一把尺子可以保证圆心的大数字与图例百分比对得上。
 */
@Composable
private fun TokenDonutCard(totals: ContextTotals) {
    val total = totals.tokens.total
    val (slices, legend, trailing) = if (totals.hasModelBreakdown) {
        val groups = groupedSlices(
            models = totals.models,
            value = { it.tokens },
            total = total,
        )
        Triple(
            groups.mapIndexed { index, it ->
                DonutSlice(it.key, it.value, modelColor(index, it.key))
            },
            groups.mapIndexed { index, group ->
                LegendRow(
                    key = group.key,
                    color = modelColor(index, group.key),
                    label = group.label,
                    value = "${formatTokens(group.value)} · ${pctText(share(group.value, total))}",
                )
            },
            "按模型",
        )
    } else {
        val kinds = tokenSlices(totals.tokens)
        Triple(
            kinds.map { DonutSlice(it.key, it.value, it.color) },
            kinds.map {
                LegendRow(
                    key = it.key,
                    color = it.color,
                    label = it.label,
                    value = "${formatTokens(it.value)} · ${pctText(share(it.value, total))}",
                )
            },
            "按类型",
        )
    }

    DonutCard(
        title = "Token 用量构成",
        trailing = trailing,
        slices = slices,
        total = total,
        centerTop = formatTokens(total),
        centerSub = "累计 token",
        legend = legend,
        footer = tokenFooter(totals.tokens),
        footerHint = if (totals.hasModelBreakdown) {
            null
        } else {
            "装上上下文洞察插件后，这里会按模型拆分"
        },
    )
}

/**
 * 花费环（按模型金额）。
 *
 * 与 token 环用的是同一批模型，但**分母不同**：这里只用「有价格的模型」之和。
 * 未计价模型（价表里没有）金额是 0，把它们算进分母会让每一段的百分比都偏小，
 * 而用户真正想知道的是「我花的这些钱其中多少是 step-5-preview」。
 * 未计价的部分单独用一行文字说明——「未计价不是免费，是价表缺这些模型」。
 */
@Composable
private fun CostDonutCard(totals: ContextTotals) {
    val cost = totals.cost ?: return
    val priced = totals.models.filter { it.cost > 0.0 && it.tokens > 0 }
    val paidTotal = priced.sumOf { it.cost }
    val groups = groupedSlices(
        models = priced,
        value = { it.cost.toLong() },
        total = paidTotal.toLong(),
    )
    DonutCard(
        title = "预估费用",
        trailing = "按模型",
        slices = groups.mapIndexed { index, it ->
            DonutSlice(it.key, it.value, modelColor(index, it.key))
        },
        total = paidTotal.toLong(),
        centerTop = formatCost(cost).orEmpty(),
        centerSub = "预估费用",
        legend = groups.mapIndexed { index, group ->
            LegendRow(
                key = group.key,
                color = modelColor(index, group.key),
                label = group.label,
                value = "${formatCost(group.value.toDouble()).orEmpty()} · ${
                    pctText(share(group.value, paidTotal.toLong()))
                }",
            )
        },
        footer = if (totals.unpricedModels > 0) {
            "未计价 ${totals.unpricedModels} 个模型 · ${formatTokens(totals.unpricedTokens)} token"
        } else {
            "全部模型都有价格"
        },
        footerValue = formatCost(cost),
        footerHint = if (totals.unpricedModels > 0) "未计价不是免费，是价表缺这些模型" else null,
    )
}

/**
 * 费用卡在**没有金额**时的占位。
 *
 * 为什么不做「没有就不渲染」：那会让装了插件与没装的界面差一整张卡，而用户看到的只是
 * 「这次怎么少了一块」，没有任何线索。费用是这一页的核心诉求之一，缺了必须说出来。
 *
 * 什么时候会走到这里：没装 `dsh-cost-meter`（`costUsage` 投影整条缺失）。
 * 注意它**不影响** token 环——token 来自核心自带的 `tokenUsage`，永远有。
 */
@Composable
private fun CostUnavailableCard() {
    InsightCard(title = "预估费用", trailing = "缺插件") {
        Text(
            text = "读不到金额。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "费用由 dsh-cost-meter 插件按事件逐次累加，没装这个插件时服务端不下发 costUsage，" +
                "这里就没有数字可算。上面的 Token 构成不受影响。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
        )
    }
}

/**
 * 耗时分布环（六段）。
 *
 * ## 为什么没有单独的「未归类」段
 *
 * R050 原型里画过一段「未归类」（六段之和只到墙钟 79.2%）。实现时发现它和
 * 「其他开销」是同一笔时间的两个名字：`ContextInsightsParser` 里
 * `otherMs = wallMs - ttft - text - toolArg - tools`——缺口在**解析时**就已经被
 * 反推成 `otherMs` 了，聚合层再算一次「墙钟减六段」只会恒等于 0。
 *
 * 保留「其他开销」这一个名字就够了，图例已经标明它是 20% 那一块。
 */
@Composable
private fun TimingDonutCard(totals: ContextTotals) {
    val segments = totals.timing.segments
    if (segments.isEmpty()) return
    val wall = totals.timing.wallMs

    val slices = ArrayList<DonutSlice>(segments.size)
    segments.forEach { slices += DonutSlice(it.key, it.millis, ContextPalette.ofTiming(it.key)) }

    DonutCard(
        title = "耗时分布",
        trailing = "墙钟累计",
        slices = slices,
        total = wall,
        centerTop = wallText(wall),
        centerSub = "墙钟合计",
        legend = buildList {
            segments.forEach {
                add(
                    LegendRow(
                        key = it.key,
                        color = ContextPalette.ofTiming(it.key),
                        label = it.label,
                        value = "${durText(it.millis)} · ${pctText(share(it.millis, wall))}",
                    ),
                )
            }
        },
        footer = if (totals.hasDetailedTiming) "六段之和 = 墙钟" else "两段之和 = 墙钟",
        footerValue = durText(wall),
    )
}

/** 近 14 天活跃度柱状图。 */
@Composable
private fun ActivityCard(
    activity: List<DayActivity>,
    metric: ActivityMetric,
    onMetricChange: (ActivityMetric) -> Unit,
) {
    if (activity.isEmpty()) return
    val peak = activity.maxOf { metric.valueOf(it) }.coerceAtLeast(1)
    InsightCard(
        title = "近 14 天活跃度",
        trailing = null,
        // 指标切换放在卡头右侧：它只作用于这一张卡，放在抽屉顶部的范围选择器旁边
        // 会被误读成「影响全部三张圆环」。
        header = {
            SegPicker(
                options = ActivityMetric.ordered.map { it.label },
                selectedIndex = ActivityMetric.ordered.indexOf(metric),
                onSelect = { onMetricChange(ActivityMetric.ordered[it]) },
                group = "指标选项",
                compact = true,
                modifier = Modifier.width(104.dp),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            activity.forEach { day ->
                val value = metric.valueOf(day)
                val ratio = value.toFloat() / peak.toFloat()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = metric.describe(day) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // 0 也要留 2dp：空柱子消失会让横轴看起来缺了一天。
                            .height((2 + (50 * ratio)).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (value == 0) {
                                    ContextPalette.track.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f + 0.45f * ratio)
                                },
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "14 天前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val busiest = activity.maxByOrNull { metric.valueOf(it) }
            Text(
                text = if (busiest == null || metric.valueOf(busiest) == 0) {
                    metric.emptyText(activity)
                } else {
                    metric.peakText(busiest)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "今天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

// ── 会话 ──────────────────────────────────────────────────────────────────

/** 会话页的三种排序。切换只重排本地列表，不重新聚合。 */
private enum class SessionSort(val label: String) {
    TOKENS("按用量"),
    COST("按费用"),
    RECENT("按最近"),
}

@Composable
private fun SessionsTab(totals: ContextTotals, onOpenSession: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(SessionSort.TOKENS) }
    val sorted = remember(totals.ranking, sort) {
        when (sort) {
            SessionSort.TOKENS -> totals.ranking // 聚合器已按 token 降序
            SessionSort.COST -> totals.ranking.sortedWith(
                compareByDescending<SessionRank> { it.cost ?: 0.0 }.thenByDescending { it.tokens },
            )

            SessionSort.RECENT -> totals.ranking.sortedByDescending { it.updatedAt }
        }
    }
    val head = sorted.take(LEADERBOARD_SIZE)
    val tail = sorted.drop(LEADERBOARD_SIZE)
    val tailShare = tail.sumOf { it.tokens.toDouble() } /
        totals.tokens.total.toDouble().coerceAtLeast(1.0)

    Column(Modifier.fillMaxSize()) {
        SegPicker(
            options = SessionSort.entries.map { it.label },
            selectedIndex = SessionSort.entries.indexOf(sort),
            onSelect = { sort = SessionSort.entries[it] },
            compact = true,
        )
        if (sort == SessionSort.COST) {
            Text(
                text = "未计价会话的费用按 0 排在后面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // 排行空时**必须给一句说明**，否则页签切过来是一片空白：LazyColumn 里
        // 除了末尾那个 12dp 的 Spacer 什么都没有，用户会以为页签点坏了。
        // 这只发生在「范围里有会话但都没有用量投影」（没装插件且连核心
        // tokenUsage 也读不到）时，与总览页的空态文案保持一致。
        if (sorted.isEmpty()) {
            CenteredHint("这个范围里还没有用量数据。")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(head, key = { it.sessionId }) { rank ->
                SessionRankRow(
                    rank = sorted.indexOf(rank) + 1,
                    rankItem = rank,
                    onOpen = { onOpenSession(rank.sessionId) },
                )
            }
            if (tail.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .clip(bubbleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "其余 ${tail.size} 个会话 · 合计 ${pctText(tailShare.toFloat())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SessionRankRow(rank: Int, rankItem: SessionRank, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "第 $rank 名 ${rankItem.title}，打开会话" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp),
        )
        // 前两名加 2dp 主色竖条：与原型一致，给「谁在吃额度」一个不用读数字的锚点。
        Box(
            Modifier
                .width(2.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(
                    if (rank <= 2) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = rankItem.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(rankItem.cwd?.let { shortCwd(it) } ?: "未知工作区")
                    rankItem.model?.let { append(" · ").append(it) }
                    append(" · ").append(TimeText.relative(rankItem.updatedAt, nowOrZero()))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTokens(rankItem.tokens),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pctText(rankItem.share),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 共用小件 ──────────────────────────────────────────────────────────────

/** 排行榜默认展开多少行（原型 R050 定的是 8）。 */
private const val LEADERBOARD_SIZE = 8


/** 图例一行的数据（渲染时才决定颜色）。 */
private data class LegendRow(
    val key: String,
    val color: Color,
    val label: String,
    val value: String,
)

/** 圆环卡片的通用骨架：标题 + 圆环 + 图例 + 脚注。 */
@Composable
private fun DonutCard(
    title: String,
    trailing: String,
    slices: List<DonutSlice>,
    total: Long,
    centerTop: String,
    centerSub: String,
    legend: List<LegendRow>,
    footer: String,
    footerValue: String? = null,
    footerHint: String? = null,
) {
    var selected by remember(slices) { mutableStateOf<String?>(null) }
    InsightCard(title = title, trailing = trailing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentDonut(
                slices = slices,
                size = 96.dp,
                selected = selected,
                centerTop = if (selected == null) {
                    centerTop
                } else {
                    val slice = slices.firstOrNull { it.key == selected }
                    if (slice != null) pctText(share(slice.value, total)) else centerTop
                },
                centerSub = if (selected == null) centerSub else "占比",
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                legend.forEach { row ->
                    DonutLegendRow(
                        color = row.color,
                        label = row.label,
                        value = row.value,
                        selected = row.key == selected,
                        onClick = { selected = if (selected == row.key) null else row.key },
                        modifier = Modifier.height(19.dp).padding(horizontal = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (footerValue != null) {
                Text(
                    text = footerValue,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (footerHint != null) {
            Text(
                text = footerHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * 洞察卡片外壳。
 *
 * 与聊天页 `DetailCard` 形状一致（`bubbleShape` + surfaceVariant 35%），但**不复用**它：
 * 那个是 `private`，且它的 `trailing` 与这里语义不同（那边是「全量 xxx」，这边是
 * 「按模型 / 墙钟累计」这类口径说明）。为了少一个跨包改动而复用会逼着改它的可空性。
 */
@Composable
private fun InsightCard(
    title: String,
    trailing: String? = null,
    /**
     * 卡头右侧的交互控件（目前只有活跃度卡的「步数/会话」切换用）。
     *
     * 单独开一个槽而不是把 [trailing] 改成 `@Composable`：`trailing` 是**文字**
     * （「按模型」「墙钟累计」这类口径说明），语义是「这张卡的数字怎么算出来的」；
     * 切控件是**可点的操作**。混成一个参数会让读代码的人分不清哪张卡的右上角能点。
     */
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
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
                if (header != null) header()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SheetFooter() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Text(
        text = "数据取自首页已拉到的 session/list 投影 · 不发额外请求",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── 纯函数（可单测） ───────────────────────────────────────────────────────

/**
 * 把模型列表折叠成「前 4 个 + 其余」。
 *
 * 为什么必须折叠：实测 18 个模型里 14 个未计价、单个占比常低于 1%，全列出来图例会
 * 把卡片撑到屏幕外，而用户真正关心的是「谁在吃额度」。原型 R050 定的是前 4 + 其余。
 *
 * @param total 分母；用于算出「其余」的占比文案。
 */
internal fun groupedSlices(
    models: List<ModelUsage>,
    value: (ModelUsage) -> Long,
    total: Long,
    head: Int = 4,
): List<GroupedModel> {
    val positive = models.filter { value(it) > 0L }.sortedByDescending(value)
    if (positive.size <= head) {
        return positive.map { GroupedModel(it.name, value(it)) }
    }
    val front = positive.take(head).map { GroupedModel(it.name, value(it)) }
    val restValue = positive.drop(head).sumOf(value)
    return front + GroupedModel("其余 ${positive.size - head} 个", restValue)
}

/** [groupedSlices] 的一行。 */
internal data class GroupedModel(val label: String, val value: Long) {
    /** 圆环段位的稳定键——用标签而不是下标，选中状态才不会在切换范围后错位。 */
    val key: String get() = label
}

/** [tokenSlices] 的一段：四类 token 之一，带固定文案与配色。 */
internal data class TokenSlice(
    val key: String,
    val label: String,
    val value: Long,
    val color: Color,
)

/** [tokenSlices] 的段位键，也是 `ContextPalette.ofSegment` 认识的键。 */
internal const val TOKEN_CACHE_READ = "tool"
internal const val TOKEN_INPUT = "user"
internal const val TOKEN_OUTPUT = "assistant"
internal const val TOKEN_CACHE_WRITE = "inject"

/**
 * 把 [TokenUsage] 拆成四段，用于**没有模型维度时**的降级圆环。
 *
 * 顺序是刻意排的：缓存读在实测里占 96.6%，放第一段它就从 12 点方向开始，一眼看得出
 * 「这一圈几乎全是缓存读」。为 0 的段直接丢掉——画一个 0% 的段只会让图例多一行噪声。
 *
 * 口径与 token 环脚注（[tokenFooter]）完全一致，两处不能各写一套。
 */
internal fun tokenSlices(tokens: TokenUsage): List<TokenSlice> = buildList {
    if (tokens.cacheRead > 0L) {
        add(TokenSlice(TOKEN_CACHE_READ, "缓存读", tokens.cacheRead, ContextPalette.tool))
    }
    if (tokens.uncachedInput > 0L) {
        add(TokenSlice(TOKEN_INPUT, "未缓存输入", tokens.uncachedInput, ContextPalette.user))
    }
    if (tokens.output > 0L) {
        add(TokenSlice(TOKEN_OUTPUT, "输出", tokens.output, ContextPalette.assistant))
    }
    if (tokens.cacheWrite > 0L) {
        add(TokenSlice(TOKEN_CACHE_WRITE, "缓存写", tokens.cacheWrite, ContextPalette.inject))
    }
}

/**
 * 模型 → 颜色的稳定映射。
 *
 * 前 10 个走插件调色板（与聊天页七段同源），超出部分按黄金角旋转色相兜底：
 * 模型数量是服务端决定的，写死一个 12 色数组总会在第 13 个模型上重色。
 */
internal fun modelColor(index: Int, key: String): Color {
    if (key.startsWith("其余")) return ContextPalette.track
    val base = listOf(
        ContextPalette.assistant,
        ContextPalette.tools,
        ContextPalette.user,
        ContextPalette.inject,
        ContextPalette.tool,
        ContextPalette.skill,
        ContextPalette.system,
        ContextPalette.reasoning,
        ContextPalette.text,
        ContextPalette.other,
    )
    if (index < base.size) return base[index]
    val hue = (index * 137.508f) % 360f
    return Color.hsv(hue, 0.62f, 0.92f)
}

/** 占比文案：≥10% 取整，否则保留一位小数（0.04% 才不会显示成 0%）。 */
internal fun pctText(share: Float): String {
    val percent = share * 100f
    return if (percent >= 10f) {
        "${percent.toInt()}%"
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }
}

/** 比例；分母为 0 时返回 0（不产生 NaN 段）。 */
internal fun share(part: Long, total: Long): Float {
    if (total <= 0L) return 0f
    return (part.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** 耗时分段文案：小于 1 小时给「N 分钟」，否则「H 小时」（保留一位）。 */
internal fun durText(millis: Long): String {
    if (millis <= 0L) return "0"
    val minutes = millis / 60_000.0
    return if (minutes < 60) {
        String.format(Locale.US, "%.0f 分", minutes)
    } else {
        String.format(Locale.US, "%.1f 时", minutes / 60.0)
    }
}

/** 墙钟文案：≥24 小时给「N 天 H 时」，否则复用 [durText]。 */
internal fun wallText(millis: Long): String {
    if (millis <= 0L) return "0"
    val hours = millis / 3_600_000.0
    if (hours < 24) return durText(millis)
    val days = (hours / 24).toInt()
    val rest = (hours - days * 24).toInt()
    return if (rest == 0) "$days 天" else "$days 天 $rest 时"
}

/** token 环的脚注：四类拆开写，让「缓存读占大头」这个事实不用读图例也能看到。 */
internal fun tokenFooter(tokens: TokenUsage): String {
    val parts = buildList {
        if (tokens.cacheRead > 0L) add("缓存读 ${formatTokens(tokens.cacheRead)}")
        if (tokens.uncachedInput > 0L) add("未缓存输入 ${formatTokens(tokens.uncachedInput)}")
        if (tokens.output > 0L) add("输出 ${formatTokens(tokens.output)}")
        if (tokens.cacheWrite > 0L) add("缓存写 ${formatTokens(tokens.cacheWrite)}")
    }
    return if (parts.isEmpty()) "没有 token 记录" else parts.joinToString(" · ")
}

/** 工作区路径压缩成末段，避免 `F:\bot\remote-web-app` 把第二行挤满。 */
internal fun shortCwd(cwd: String): String {
    val trimmed = cwd.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return cwd
    val cut = trimmed.indexOfLast { it == '/' || it == '\\' }
    return if (cut >= 0 && cut < trimmed.length - 1) trimmed.substring(cut + 1) else trimmed
}

/** 排行行里的相对时间；`TimeText` 需要一个「现在」，这里取系统时钟（每行调用一次足够）。 */
private fun nowOrZero(): Long = System.currentTimeMillis()
