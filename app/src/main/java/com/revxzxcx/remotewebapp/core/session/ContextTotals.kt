package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject

/**
 * 首页「总上下文洞察」的统计范围（用户 m34049 / R050 原型定的三档）。
 *
 * ## 为什么按会话的 `updatedAt` 过滤，而不是按事件时间
 * App 手上**没有**逐事件的 token 账单：`tokenUsage` 与 `costUsage` 都是**每个会话一个累计值**
 * （实测 `session/list` 的投影里没有任何按时间切分的用量）。要按天切分，唯一可用的时间戳
 * 就是会话自己的 `updatedAt`。
 *
 * 这个口径的代价写在界面上：一个跨月的长会话会整条落进「近 7 天」，哪怕它大部分 token 是
 * 上个月烧的。选择它是因为**数据只有这么多**，而自己按事件摊派 token 会造出假数字
 * （见 [ContextTotals] 的 KDoc「为什么不做按日摊派」）。
 */
enum class InsightsRange(val label: String, val days: Int) {
    /** 近 7 天（含今天）。 */
    WEEK("近 7 天", 7),

    /** 近 30 天（含今天）。 */
    MONTH("近 30 天", 30),

    /** 全部历史，不做时间过滤。 */
    ALL("全部", Int.MAX_VALUE),
    ;

    /**
     * 这条会话是否落在这个范围内。
     *
     * @param updatedAt 会话最近活动时间（epoch 毫秒）；0 或负数视为「没有时间戳」，
     *   **只有 [ALL] 收它**。理由：时间戳缺失时按任意天数过滤都等于凭空猜测，
     *   宁可让它出现在「全部」里，也不要在分档视图里悄悄吞掉一条会话。
     * @param now 当前时间（epoch 毫秒），由调用方传入以便测试可复现。
     */
    fun contains(updatedAt: Long, now: Long): Boolean {
        if (this == ALL) return true
        if (updatedAt <= 0L) return false
        // 边界：cutoff = now - days*86400000，updatedAt >= cutoff 才算在窗口内。
        // 用毫秒算术而不是 Calendar，避免夏令时/时区把「7 天」变成 6 天 23 小时。
        val windowMs = days.toLong() * MILLIS_PER_DAY
        val cutoff = now - windowMs
        return updatedAt >= cutoff
    }

    companion object {
        const val MILLIS_PER_DAY: Long = 86_400_000L

        /** UI 里按这个顺序渲染分段控件。 */
        val ordered: List<InsightsRange> = listOf(WEEK, MONTH, ALL)
    }
}

/**
 * 一条会话在首页洞察里的用量切片。
 *
 * 全部字段来自该会话自己的四个投影（`tokenUsage` / `costUsage` / `sessionStats` /
 * `contextTimeline`），**不做任何跨会话摊派**。构造时由 [ContextTotalsCalculator] 从
 * `SessionUsage.of(sessionId, values)` 得到。
 *
 * ## 为什么 `cost` 是 `Double?` 而不是 `Double`
 * 服务端没给 `costUsage` 时（未装 `dsh-cost-meter`）就**没有金额**，与「金额是 0」是两件事。
 * 前者界面整块不显示费用环，后者显示 `$0.00`。实测未装插件的会话两个字段会一起消失。
 *
 * ## 为什么同时保留 [timing] 而不是只留六段数字
 * [TurnTiming] 自己带 `detailAvailable`（六段 / 两段降级）与 `segments` 的固定顺序，
 * 聚合层直接累加它给出的六个字段，判定逻辑仍然只有一处（那条 KDoc 解释了
 * `ttft = rawTtft - reasoning` 为什么必须这么切）。
 */
data class SessionUsage(
    /** 会话 id。 */
    val sessionId: String,
    /** 四类 token（未缓存输入 / 输出 / 缓存读 / 缓存写）。 */
    val tokens: TokenUsage = TokenUsage.EMPTY,
    /** 会话累计花费（美元）；null 表示服务端没给。 */
    val cost: Double? = null,
    /** 费用明细（按模型 + 未计价判定）；null 表示服务端没给。 */
    val costDetail: CostDetail? = null,
    /** 耗时分布；null 表示既没有 `contextTimeline` 也没有 `sessionStats`。 */
    val timing: TurnTiming? = null,
    /** 轮数与步数（`sessionStats` 的 `turns` / `steps`）。 */
    val turns: Int = 0,
    val steps: Int = 0,
) {
    companion object {
        /**
         * 从一个会话的 `projections.values` 造一个切片。
         *
         * 完全读不到用量时返回 null：让调用方**跳过**这条会话，而不是塞一条全 0 的进去
         * ——全 0 会污染「共 N 个会话」这个分母，让用户以为有 N 个会话但只有几个有数据。
         */
        fun of(sessionId: String, values: JsonObject?): SessionUsage? {
            val insights = contextInsightsOf(values) ?: return null
            val hasTokens = insights.usage.total > 0
            val hasCost = insights.cost != null || insights.costDetail != null
            val hasTiming = insights.timing != null
            // 三项都没有：这条会话没有任何洞察数据，跳过。
            if (!hasTokens && !hasCost && !hasTiming) return null
            return SessionUsage(
                sessionId = sessionId,
                tokens = insights.usage,
                cost = insights.cost,
                costDetail = insights.costDetail,
                timing = insights.timing,
                turns = insights.turns,
                steps = insights.steps,
            )
        }
    }
}

/** Token 按模型拆分的一项（聚合后）。 */
data class ModelUsage(
    val name: String,
    val tokens: Long,
    val cost: Double,
) {
    /** 有 token 但金额为 0 —— 判据与 `CostDetail.unpricedModels` 完全一致。 */
    val unpriced: Boolean get() = cost == 0.0 && tokens > 0
}

/** 活跃度柱状图的一天。 */
data class DayActivity(
    /** `yyyy-MM-dd`（本地时区）。 */
    val day: String,
    /** 这一天有多少条会话被更新过。 */
    val sessions: Int,
)

/**
 * 首页「总上下文洞察」的一次聚合结果。
 *
 * ## 零新增 RPC
 * 全部数字来自 `session/list` 已经带回来的四个投影，实测 32 条主会话 32/32 命中。
 * 所以切档、切页、重新打开抽屉都不发任何请求（用户 m34453 拍板的「统计范围够」正是基于
 * 这个前提：范围只是在内存里换一个过滤条件）。
 *
 * ## 为什么不做「按日摊派」
 * 插件自己的全局页会按天显示 token，但它是**逐个会话读 `contextActivity`** 得来的
 * （一次会话一次请求，32 条就是 32 次往返）。App 只在 `session/list` 的一次响应里工作，
 * 手上没有按天的 token 拆分。若按会话 `updatedAt` 把整条用量摊到某一天，一个跨月会话
 * 会把它上亿 token 全算到最近这一天，柱子会变成假的。
 *
 * 所以活跃度柱子画的是**「这一天有多少条会话活动过」**（原型 R050 已经这么定并在图注里
 * 写明），而不是 token 量。这是本页唯一一个「换了指标」的地方，必须在界面上说明。
 */
data class ContextTotals(
    /** 参与聚合的会话数（已按范围过滤、已排除子代理与空会话、已排除读不到用量的）。 */
    val sessionCount: Int = 0,
    /** 四类 token 合计。 */
    val tokens: TokenUsage = TokenUsage.EMPTY,
    /** 花费合计（美元）；所有会话都没有金额时为 null。 */
    val cost: Double? = null,
    /** 按模型拆分，按 token 降序。 */
    val models: List<ModelUsage> = emptyList(),
    /** 未计价模型的 token 合计（`cost == 0 && tokens > 0`）。 */
    val unpricedTokens: Long = 0,
    /** 未计价的模型个数。 */
    val unpricedModels: Int = 0,
    /** 六段耗时之和（或降级态两项之和）。 */
    val timing: TurnTiming = TurnTiming(),
    /** 近 14 天活跃度，按日期升序，最后一项是今天。 */
    val activity: List<DayActivity> = emptyList(),
    /** 会话排行，按 token 降序。 */
    val ranking: List<SessionRank> = emptyList(),
    /** 轮数与步数合计。 */
    val turns: Int = 0,
    val steps: Int = 0,
) {
    val isEmpty: Boolean get() = sessionCount == 0

    /**
     * 有没有「按模型」的拆分（来自 `costUsage.byModel`，**插件** `dsh-cost-meter` 才有）。
     *
     * 这份数据的唯一来源是 `costUsage`：核心自带的 `tokenUsage` 只有四类口径，没有模型维度。
     * 所以它为 false 与「没装插件」是同一件事——不装插件时 token 环要退到四类口径去画，
     * 否则圆环会是一个空壳（图例空白、只剩圆心数字）。
     */
    val hasModelBreakdown: Boolean get() = models.isNotEmpty()

    /**
     * 有没有金额。缺 `costUsage` 时为 null——「算不出来」与「免费」是两件事，
     * 界面要把前者说出来，而不是把费用卡静默省略掉。
     */
    val hasCost: Boolean get() = cost != null

    /**
     * 耗时是插件给的六段细分，还是核心 `sessionStats` 的两段降级口径。
     *
     * UI 的脚注措辞由它决定：降级态写「六段之和 = 墙钟」是错误断言（那时只有两段）。
     */
    val hasDetailedTiming: Boolean get() = timing.detailAvailable

    /** 缓存命中率；总输入为 0 时 null（与 `TokenUsage.cacheHitRate` 同口径）。 */
    val cacheHitRate: Float? get() = tokens.cacheHitRate

    /**
     * 排行前 [count] 名占全部 token 的比例（0..1）。
     *
     * 原型里的「前 8 个占 86.9%」就是这个口径。分母用 [tokens] 的 `total`，与
     * `ranked` 各自 token 之和同一个集合，不会出现分母含未计价模型而分子不含的错位。
     */
    fun topShare(count: Int): Float {
        if (tokens.total <= 0L) return 0f
        val head = ranking.take(count).sumOf { it.tokens }
        return (head.toDouble() / tokens.total.toDouble()).toFloat()
    }

    companion object {
        val EMPTY = ContextTotals()
    }
}

/**
 * 会话排行的一行。
 *
 * [tokens] 是该会话的四类 token 合计；[share] 是它占 [ContextTotals.tokens] 的比例。
 * 单独的 `share` 字段而不是让 UI 每次现算：同一份排行会被「按用量 / 按费用 / 按最近」
 * 三种排序复用，占比必须跟着 token 走、**不跟着排序走**（按费用排第一的会话占比可能只有 5%）。
 */
data class SessionRank(
    val sessionId: String,
    val title: String,
    val cwd: String?,
    val model: String?,
    val updatedAt: Long,
    val tokens: Long,
    val cost: Double?,
    val share: Float,
    /** 是否子代理会话（排行榜不列它们，字段保留给 UI 的标记位）。 */
    val isSubagent: Boolean = false,
)

/**
 * 聚合器：把一批会话摘要算成 [ContextTotals]。
 *
 * 纯函数、无 Android 依赖、无挂起——所以整套逻辑可以在 JVM 单测里跑，也能安全地在
 * Compose 的 `remember` 里调用（输入不变就不重算）。
 */
object ContextTotalsCalculator {

    /** 活跃度柱子画多少天（原型 R050 定的是 14 天）。 */
    const val ACTIVITY_DAYS: Int = 14

    /**
     * @param sessions 待聚合的会话摘要（**调用方负责**先按 [SessionListResult.visible] 筛掉
     *   子代理与空会话；这里不再筛一次，以免同一规则两处实现走偏）。
     * @param range 统计范围。
     * @param now 当前时间（epoch 毫秒），由调用方传入以保证可测。
     * @param usages `session/list` 解析出来的逐会话用量，键是会话 id。
     *   缺失的会话会被跳过（它不是「0 用量」，而是「这条没数据」）。
     */
    fun compute(
        sessions: List<SessionSummary>,
        usages: Map<String, SessionUsage>,
        range: InsightsRange,
        now: Long,
    ): ContextTotals {
        val inRange = sessions.filter { range.contains(it.updatedAt, now) }
        if (inRange.isEmpty()) return ContextTotals()

        var uncachedInput = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var costSum = 0.0
        var anyCost = false
        var turns = 0
        var steps = 0

        // 六段耗时按会话累加。用可变累加器而不是逐条 TurnTiming 相加：
        // TurnTiming 是个 12 字段的大对象，逐条 `+` 会把 `segments` 的派生逻辑搅进来。
        var ttft = 0L
        var reasoning = 0L
        var text = 0L
        var toolArg = 0L
        var tools = 0L
        var other = 0L
        var wall = 0L
        var detailAvailable = false
        var llm = 0L
        var degradedTools = 0L

        val byModel = LinkedHashMap<String, Pair<Long, Double>>()
        val ranking = ArrayList<SessionRank>(inRange.size)
        var counted = 0

        for (session in inRange) {
            val usage = usages[session.sessionId] ?: continue
            counted++
            uncachedInput += usage.tokens.uncachedInput
            output += usage.tokens.output
            cacheRead += usage.tokens.cacheRead
            cacheWrite += usage.tokens.cacheWrite
            turns += usage.turns
            steps += usage.steps

            usage.cost?.let {
                costSum += it
                anyCost = true
            }

            usage.costDetail?.models?.forEach { model ->
                val prev = byModel[model.name]
                byModel[model.name] = if (prev == null) {
                    model.totalTokens to model.cost
                } else {
                    (prev.first + model.totalTokens) to (prev.second + model.cost)
                }
            }

            usage.timing?.let { timing ->
                if (timing.detailAvailable) {
                    detailAvailable = true
                    ttft += timing.ttftMs
                    reasoning += timing.reasoningMs
                    text += timing.textMs
                    toolArg += timing.toolArgMs
                    tools += timing.toolsMs
                    other += timing.otherMs
                    wall += timing.wallMs
                } else {
                    llm += timing.llmMs
                    degradedTools += timing.toolsMs
                }
            }

            ranking += SessionRank(
                sessionId = session.sessionId,
                title = session.displayName,
                cwd = session.cwd,
                model = session.lastModel,
                updatedAt = session.updatedAt,
                tokens = usage.tokens.total,
                cost = usage.cost,
                share = 0f,
                isSubagent = session.isSubagent,
            )
        }

        if (counted == 0) return ContextTotals()

        val tokens = TokenUsage(
            uncachedInput = uncachedInput,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
        )

        val models = byModel.entries
            .map { (name, pair) -> ModelUsage(name, pair.first, pair.second) }
            .sortedWith(compareByDescending<ModelUsage> { it.tokens }.thenBy { it.name })
        val unpriced = models.filter { it.unpriced }

        // 占地比例的分母是排行自身之和。
        //
        // 诚实说明：这个值与上面的 `tokens.total` **恒等**——缺 tokenUsage 的会话在循环里
        // 已被 `continue` 跳过，既没进 tokens 也没进 ranking，所以两者不可能不等。
        // 变异验证时把分母换成 `tokens.total` 不会让任何用例变红，这是等价变异，不是漏洞。
        // 仍然单独算一遍是为了让「前 N 名占多少」这个读数的分母在代码里写在它自己的
        // 位置上——将来若把「被跳过的会话」按 0 计入 tokens，这里才不会跟着错。
        val rankingTotal = ranking.sumOf { it.tokens }
        val ranked = ranking
            .sortedWith(compareByDescending<SessionRank> { it.tokens }.thenBy { it.sessionId })
            .map {
                it.copy(
                    share = if (rankingTotal > 0L) {
                        (it.tokens.toDouble() / rankingTotal.toDouble()).toFloat()
                    } else {
                        0f
                    },
                )
            }

        val timing = if (detailAvailable) {
            TurnTiming(
                wallMs = wall,
                ttftMs = ttft,
                reasoningMs = reasoning,
                textMs = text,
                toolArgMs = toolArg,
                toolsMs = tools,
                otherMs = other,
                detailAvailable = true,
                // 降级字段在六段口径下没有意义，留 0 以免 UI 误读。
                llmMs = 0,
                tools = emptyList(),
            )
        } else {
            TurnTiming(
                wallMs = llm + degradedTools,
                llmMs = llm,
                toolsMs = degradedTools,
                detailAvailable = false,
            )
        }

        return ContextTotals(
            sessionCount = counted,
            tokens = tokens,
            cost = if (anyCost) costSum else null,
            models = models,
            unpricedTokens = unpriced.sumOf { it.tokens },
            unpricedModels = unpriced.size,
            timing = timing,
            activity = activityOf(sessions, now),
            ranking = ranked,
            turns = turns,
            steps = steps,
        )
    }

    /**
     * 近 [ACTIVITY_DAYS] 天的活跃度：每天有多少条会话被更新过。
     *
     * 日期分桶走本地时区（用户在哪个时区就该看到哪一天），实现用
     * [java.util.Calendar] 而不是 `LocalDate`——工程 minSdk 21 且没有开
     * desugaring，`java.time` 在 Android 24 以下不可用。
     *
     * 边界：超出窗口的会话不计；窗口内没有会话的那一天照样出现（计数 0），
     * 否则柱子会因「没数据的那天被抽掉」而让横轴时间间距失真。
     */
    fun activityOf(sessions: List<SessionSummary>, now: Long): List<DayActivity> {
        val calendar = java.util.Calendar.getInstance()
        // 起点：今天往前推 ACTIVITY_DAYS-1 天的 00:00:00.000。
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis

        // 窗口内每一天的 00:00 都用 `Calendar` 逐日回退算出来，**不能**用
        // `todayStart - i * 86_400_000` 做毫秒累加：有夏令时的时区里一天是 23 或 25 小时，
        // 累加出来的时刻不再是本地零点，与 `counts` 的键（本地零点）对不上，
        // 那一天就被静默画成 0，日期标签也会错位。
        val dayStarts = LongArray(ACTIVITY_DAYS)
        calendar.timeInMillis = todayStart
        for (i in ACTIVITY_DAYS - 1 downTo 0) {
            dayStarts[i] = calendar.timeInMillis
            calendar.add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        val start = dayStarts[0]

        val counts = HashMap<Long, Int>()
        for (session in sessions) {
            if (session.updatedAt <= 0L) continue
            if (session.updatedAt < start || session.updatedAt > now) continue
            // 归一到当天 00:00，用「本地日」对齐。
            calendar.timeInMillis = session.updatedAt
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val dayStart = calendar.timeInMillis
            counts[dayStart] = (counts[dayStart] ?: 0) + 1
        }

        val out = ArrayList<DayActivity>(ACTIVITY_DAYS)
        for (i in 0 until ACTIVITY_DAYS) {
            val dayStart = dayStarts[i]
            calendar.timeInMillis = dayStart
            val label = String.format(
                java.util.Locale.US,
                "%04d-%02d-%02d",
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
            out += DayActivity(day = label, sessions = counts[dayStart] ?: 0)
        }
        return out
    }
}
