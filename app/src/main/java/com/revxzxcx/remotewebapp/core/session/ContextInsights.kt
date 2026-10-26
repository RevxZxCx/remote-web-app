package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一个会话的上下文遥测，喂给「对话详情」侧边栏。
 *
 * ## 为什么不用 `dsh-context` 插件
 * 插件提供了更全的观测面，但它有三条硬伤，这里逐条记下来免得后人再试一遍：
 *  1. 它的面板是 Web 端 React 组件，本 App 不内嵌桌面端前端源码（工程既有决策），
 *     而插件的 HTTP 接口要桌面端的会话态认证——拿设备凭证直连一律 401。
 *  2. 它的 `/detail` 一次返回 **840 KB** 的逐请求明细，手机上不该拉。
 *  3. 我们要的这几个数字，`session/projections` 里**已经算好了**：
 *     `contextTimeline` 2.1 KB、`tokenUsage` 103 B、`costUsage` 652 B、`contextPressure` 74 B。
 *
 * ## 两个「总量」必须分开，否则数字自相矛盾
 * 这是本文件最重要的语义约定：
 *  - [usedTokens]（来自 `last.prompt`）= **当前**这一轮请求的上下文占用，它是「窗口还剩多少」的分子；
 *  - [breakdown].total（来自 `current.total`）= **整个会话至今累计**吃掉的 token，它随轮次单调上涨且
 *    与窗口无关。
 *
 * 实测同一个会话：`usedTokens` = 150,325，`breakdown.total` = 3,734,172 —— 差 24 倍。
 * 早期版本把 `current.total` 当成占用画进度条，于是 1M 窗口永远显示 373%，看着像要爆。
 * 界面必须分别标注「本轮」与「全量」，不能只给一个大数字。
 *
 * ## 精度是有等级的三档，不是「有 / 没有」
 * 见 [ContextDetailLevel]。这一条决定了界面能做多细，也决定了要不要提示用户装插件。
 */
data class ContextInsights(
    /** 模型上下文窗口（`contextWindow`）。 */
    val contextWindow: Long,
    /** **本轮**请求实际占用的 token（`last.prompt`）。 */
    val usedTokens: Long,
    /**
     * [usedTokens] 是不是真的「本轮」数字。
     *
     * false 表示 `last.prompt` 缺失、[usedTokens] 退回了全量累计——此时界面必须把标签
     * 换成「累计」而不是「本轮」，否则就是在用错的分子画进度条。
     *
     * 为什么不在解析后靠 `usedTokens < breakdown.total` 反推：新会话两者可能**恰好相等**
     * （第一轮还没积累），反推会把它误判成「退化」。这个标志只能是解析时的第一手信息。
     */
    val hasTurnUsage: Boolean = false,
    /** 精度档位，决定构成图是七段、三段还是一段都没有。 */
    val detailLevel: ContextDetailLevel = ContextDetailLevel.MINIMAL,
    val model: String? = null,
    val provider: String? = null,
    val maxOutputTokens: Long? = null,
    /** **全量**构成，随会话单调上涨。有效段数由 [detailLevel] 决定。 */
    val breakdown: ContextBreakdown = ContextBreakdown.EMPTY,
    val images: Int = 0,
    val toolCalls: Int = 0,
    val humanInputs: Int = 0,
    val answers: Int = 0,
    val turns: Int = 0,
    val steps: Int = 0,
    val compactions: Int = 0,
    val prunes: Int = 0,
    /** 会话累计 token 用量（`tokenUsage` 投影）。 */
    val usage: TokenUsage = TokenUsage.EMPTY,
    /** 累计花费（美元）；服务端没给就是 null，**不要**显示成 0。 */
    val cost: Double? = null,
    /** 费用明细（推理 token + 按模型拆分）；缺 `costUsage` 时整个为 null。 */
    val costDetail: CostDetail? = null,
    /** 压缩预警（`contextPressure`），null 表示服务端还没算。 */
    val pressure: Long? = null,
    /**
     * 耗时分布（`contextTimeline.timing` 或 `sessionStats` 兜底）。
     *
     * 只有 [ContextDetailLevel.DETAILED] 才有六段；[ContextDetailLevel.BASIC] 时退化成
     * 「LLM / 工具」两段（`sessionStats` 能给出这两个数）。null 表示一个耗时数字都没有。
     */
    val timing: TurnTiming? = null,
) {

    /** 窗口占用比例，0..1。窗口缺失或为 0 时返回 null（不显示进度条）。 */
    val fraction: Float?
        get() = if (contextWindow > 0) (usedTokens.toDouble() / contextWindow).toFloat().coerceIn(0f, 1f) else null

    /** 百分比整数，没有窗口时返回 null。 */
    val percent: Int?
        get() = fraction?.let { (it * 100).toInt() }

    /** 已用窗口的剩余量。 */
    val remaining: Long?
        get() = if (contextWindow > 0) (contextWindow - usedTokens).coerceAtLeast(0) else null

    /**
     * 圆环 / 堆叠条要画的段，顺序**固定**（与插件一致：系统 → 工具定义 → 用户 → 注入 →
     * skill → 助手输出 → 工具结果）。
     *
     * 为什么固定而不是按大小排：颜色必须与段位绑定，否则同一个会话两次打开抽屉，同一种
     * 颜色可能指代不同的东西。图例那边才按大小排（见 [segmentsBySize]）。
     */
    val segments: List<ContextSegment>
        get() = when (detailLevel) {
            ContextDetailLevel.DETAILED -> listOf(
                ContextSegment(KEY_SYSTEM, "系统提示", breakdown.system),
                ContextSegment(KEY_TOOLS, "工具定义", breakdown.tools),
                ContextSegment(KEY_USER, "用户输入", breakdown.user),
                ContextSegment(KEY_INJECT, "注入", breakdown.inject),
                ContextSegment(KEY_SKILL, "skill", breakdown.skill),
                ContextSegment(KEY_ASSISTANT, "助手输出", breakdown.assistant),
                ContextSegment(KEY_TOOL, "工具结果", breakdown.tool),
            )

            // 降级态只有三段，且服务端给的 `messageTokens` 是「助手 + 工具结果 + 注入 +
            // skill + 用户」的合并值（实测 3 632 980 ≈ 各段之和），所以这里只能叫「消息」。
            ContextDetailLevel.BASIC -> listOf(
                ContextSegment(KEY_MESSAGE, "消息", breakdown.message),
                ContextSegment(KEY_TOOLS, "工具定义", breakdown.tools),
                ContextSegment(KEY_SYSTEM, "系统提示", breakdown.system),
            )

            ContextDetailLevel.MINIMAL -> emptyList()
        }.filter { it.tokens > 0 }

    /** 图例用的段，按 token 从大到小。 */
    val segmentsBySize: List<ContextSegment>
        get() = segments.sortedByDescending { it.tokens }

    /** 构成合计。DETAILED 用服务端的 `total`（它可能比各段之和略大，含未分类项）；BASIC 只能相加。 */
    val breakdownTotal: Long
        get() = when (detailLevel) {
            ContextDetailLevel.DETAILED -> breakdown.total
            ContextDetailLevel.BASIC -> segments.sumOf { it.tokens }
            ContextDetailLevel.MINIMAL -> 0
        }

    /** [segment] 占 [breakdownTotal] 的比例，0..1；合计为 0 时返回 0。 */
    fun shareOf(segment: ContextSegment): Float =
        if (breakdownTotal > 0) (segment.tokens.toDouble() / breakdownTotal).toFloat() else 0f

    companion object {
        /** 段位键。与 `contextTimeline.current` 的字段名一致，UI 用它查配色。 */
        const val KEY_SYSTEM = "system"
        const val KEY_TOOLS = "tools"
        const val KEY_USER = "user"
        const val KEY_INJECT = "inject"
        const val KEY_SKILL = "skill"
        const val KEY_ASSISTANT = "assistant"
        const val KEY_TOOL = "tool"

        /** 降级态专用的合并段键（服务端没有这个字段名，是本 App 的标签）。 */
        const val KEY_MESSAGE = "message"

        val EMPTY = ContextInsights(contextWindow = 0, usedTokens = 0)
    }
}

/**
 * 构成图的精度档位。
 *
 * 三档对应三套数据来源，**不要**把它简化成「有 / 没有」：
 *  - [DETAILED]：`contextTimeline` 在（`dsh-context` 插件注册）。七段，能分清助手输出与
 *    工具结果，还能给六段耗时圆环。
 *  - [BASIC]：只有核心自带的 `contextBreakdown`。三段（消息 / 工具定义 / 系统提示），
 *    耗时只能给「LLM / 工具」两项。
 *  - [MINIMAL]：连 `contextBreakdown` 都没有。只剩当前占用进度条与用量四行。
 *
 * 实测依据：`contextTimeline` 在核心 asar 里命中 **0 次**，只来自插件；`contextBreakdown`
 * 命中 57 次，是核心自带。
 */
enum class ContextDetailLevel {
    DETAILED,
    BASIC,
    MINIMAL,
}

/**
 * `contextTimeline.current` 的八段构成，外加降级态用的 [message]。
 *
 * 名称沿用服务端的键（`system` / `tools` / `user` / `inject` / `skill` / `assistant` / `tool`），
 * 中文标签放在 [ContextInsights.segments]，让 UI 与测试共用同一套说法。
 */
data class ContextBreakdown(
    val system: Long = 0,
    val tools: Long = 0,
    val user: Long = 0,
    val inject: Long = 0,
    val skill: Long = 0,
    val assistant: Long = 0,
    val tool: Long = 0,
    val total: Long = 0,
    /** 降级态专用：`contextBreakdown.messageTokens`（助手 + 工具结果 + 注入 + skill + 用户）。 */
    val message: Long = 0,
) {
    companion object {
        val EMPTY = ContextBreakdown()
    }
}

/**
 * 构成图的一段。
 *
 * [key] 是与配色表绑定的稳定标识，[label] 是给人看的中文，[tokens] 是绝对量。
 * 比例不放在这里——它要除以合计，而合计的口径依 [ContextInsights.detailLevel] 而变，
 * 放在段里会出现「两段的百分比加起来不是 100%」。
 */
data class ContextSegment(val key: String, val label: String, val tokens: Long)

/** `tokenUsage` 投影。 */
data class TokenUsage(
    val uncachedInput: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    /** 计费合计（未缓存输入 + 输出 + 缓存读写）。 */
    val total: Long
        get() = uncachedInput + output + cacheRead + cacheWrite

    /**
     * 缓存命中率，0..1；总输入为 0 时 null。
     *
     * 分母是**全部输入**（未缓存 + 缓存读 + 缓存写），因为缓存命中率的含义是「输入里有
     * 多少走的是缓存」。
     */
    val cacheHitRate: Float?
        get() {
            val input = uncachedInput + cacheRead + cacheWrite
            return if (input > 0) (cacheRead.toDouble() / input).toFloat() else null
        }

    companion object {
        val EMPTY = TokenUsage()
    }
}

/**
 * 费用明细（`costUsage` 投影）。
 *
 * ## 金额是服务端算好的，本 App 只读不算
 * `costUsage.cost` 是 `dsh-cost-meter` 按「事件发生时刻的价格档位」逐次累加出来的（源码注释：
 * 峰谷时代前用 legacyBase、之后用峰谷两档）。用今天的单价去乘历史 token 会算出错的数字，
 * 所以这里**绝不**自己做乘法。
 *
 * ## 未计价不等于免费
 * 服务端为每个模型都给一个 `cost`，但价表里没有的模型会是 **0**。所以看到 0 不能断言
 * 「这个模型免费」——[unpricedModels] 就是为此存在的：它列出「有 token 但 cost 为 0」的
 * 模型，界面据此显示「N 个模型未计价」而不是假装它们免费。
 */
data class CostDetail(
    /** 推理 token（`costUsage.reasoning`）。它与输出 token 分开计费口径，所以单列。 */
    val reasoningTokens: Long = 0,
    /** 会话累计花费（美元）。 */
    val cost: Double = 0.0,
    /** 按模型拆分，金额从大到小。 */
    val models: List<ModelCost> = emptyList(),
) {
    /**
     * 有 token 但金额为 0 的模型名。
     *
     * 判据是「消耗了 token 而 cost 恰好为 0」，不是「cost == 0」——真·免费模型也会命中，
     * 但那与「价表缺这个模型」在用户眼里是同一件事（费用不准），提示一句没有坏处。
     */
    val unpricedModels: List<String>
        get() = models.filter { it.cost == 0.0 && it.totalTokens > 0 }.map { it.name }

    /** 是否所有模型都已计价。 */
    val fullyPriced: Boolean get() = unpricedModels.isEmpty()
}

/** [CostDetail.models] 的一项。 */
data class ModelCost(
    val name: String,
    val cost: Double,
    val totalTokens: Long,
)

/**
 * 耗时分布。
 *
 * ## 六段是怎么算出来的（**不是**照抄服务端字段）
 * `contextTimeline.timing` 给的是 `wallMs` / `ttftMs` / `reasoningMs` / `textMs` /
 * `toolArgMs` / `toolsMs`。它们**互相重叠**，直接相加会超过墙钟：
 *
 * 实测同一会话 92,254,721(墙钟) vs 五段相加 93,543,876 —— 多了 1,289,155。
 * 原因是 `reasoningMs` 属于 `ttftMs` 的一部分（推理发生在首 token 之前）。
 *
 * 所以这里按「首 token（扣掉思考）+ 思考 + 回复 + 工具参数 + 工具执行 + 其他」切分：
 * `ttftMs - reasoningMs` 才是「纯等待模型出第一个 token」的时间，六段之和回到墙钟。
 * 界面画圆环时必须用 [segments]，不能直接用服务端那六个原始字段。
 *
 * ## 降级态
 * 没有 `contextTimeline` 时，`sessionStats` 只给 `llmMs` 与 `toolMs`，这时
 * [detailAvailable] 为 false，[segments] 只有两段。
 */
data class TurnTiming(
    /** 墙钟总时长：从第一轮开始到最后一次活动。 */
    val wallMs: Long = 0,
    /** 纯等待首 token 的时间（已扣除思考）。 */
    val ttftMs: Long = 0,
    /** 模型思考（reasoning）时长，是 [ttftMs] 的一部分被单列出来。 */
    val reasoningMs: Long = 0,
    /** 模型输出正文的时长。 */
    val textMs: Long = 0,
    /** 生成工具调用参数（tool call arguments）的时长。 */
    val toolArgMs: Long = 0,
    /** 工具执行时长。 */
    val toolsMs: Long = 0,
    /** 其他开销：墙钟减去上面各项，兜底为 0。 */
    val otherMs: Long = 0,
    /** 输出速度（tok/s）；服务端没给速度分母时 null。 */
    val tokensPerSecond: Int? = null,
    /** 是否拿到插件给的六段细分。false 时只有 [llmMs] / [toolsMs] 两项。 */
    val detailAvailable: Boolean = false,
    /** 降级态专用：模型侧总耗时（`sessionStats.llmMs`）。 */
    val llmMs: Long = 0,
    /** 按耗时降序的工具明细。 */
    val tools: List<ToolTiming> = emptyList(),
) {

    /** 圆环要画的段，顺序固定；`otherMs` 排最后，因为它不是一个真实动作。 */
    val segments: List<TimingSegment>
        get() = if (detailAvailable) {
            listOf(
                TimingSegment(KEY_TTFT, "模型等待", ttftMs),
                TimingSegment(KEY_REASONING, "模型思考", reasoningMs),
                TimingSegment(KEY_TEXT, "模型回复", textMs),
                TimingSegment(KEY_TOOL_ARG, "工具参数", toolArgMs),
                TimingSegment(KEY_TOOLS, "工具执行", toolsMs),
                TimingSegment(KEY_OTHER, "其他开销", otherMs),
            ).filter { it.millis > 0 }
        } else {
            listOf(
                TimingSegment(KEY_LLM, "模型", llmMs),
                TimingSegment(KEY_TOOLS, "工具", toolsMs),
            ).filter { it.millis > 0 }
        }

    /** 圆环分割用的合计。六段口径下它应等于 [wallMs]；降级态是两项之和。 */
    val segmentsTotal: Long
        get() = segments.sumOf { it.millis }

    companion object {
        const val KEY_TTFT = "ttft"
        const val KEY_REASONING = "reasoning"
        const val KEY_TEXT = "text"
        const val KEY_TOOL_ARG = "toolarg"
        const val KEY_TOOLS = "tools"
        const val KEY_OTHER = "other"
        const val KEY_LLM = "llm"
    }
}

/** 耗时圆环的一段。 */
data class TimingSegment(val key: String, val label: String, val millis: Long)

/** 单个工具的调用次数与累计耗时。 */
data class ToolTiming(val name: String, val calls: Int, val millis: Long)

/**
 * 解析 `contextTimeline` / `contextBreakdown` / `tokenUsage` / `costUsage` / `contextPressure` /
 * `sessionStats` 六个投影。
 *
 * 与全工程一致：**任何字段缺失都退回默认值，绝不抛异常**——服务端随时会加字段，也会在
 * 会话刚开始时给出半截数据。所以这里没有一个是「必需字段」：
 *  - 完全没有 `contextTimeline` → 退到 `contextBreakdown`（三段），再没有就只剩占用与用量；
 *  - 六个投影**一个都没有** → 返回 null（UI 显示「读不到」，而不是显示一堆 0）。
 *
 * @param values `session/projections` 的 `values` 对象（或 `session/follow` 快照里的同名字段）。
 */
internal object ContextInsightsParser {

    /** 投影键名。放常量是为了 UI 与 `session/control` 帧分发共用同一串。 */
    const val KEY_TIMELINE = "contextTimeline"
    const val KEY_BREAKDOWN = "contextBreakdown"
    const val KEY_TOKEN_USAGE = "tokenUsage"
    const val KEY_COST = "costUsage"
    const val KEY_PRESSURE = "contextPressure"
    const val KEY_SESSION_STATS = "sessionStats"

    fun parse(values: JsonObject?): ContextInsights? {
        if (values == null) return null

        val timeline = values[KEY_TIMELINE] as? JsonObject
        val rawBreakdown = values[KEY_BREAKDOWN] as? JsonObject
        val usageObj = values[KEY_TOKEN_USAGE] as? JsonObject
        val costObj = values[KEY_COST] as? JsonObject
        val pressureObj = values[KEY_PRESSURE] as? JsonObject
        val statsObj = values[KEY_SESSION_STATS] as? JsonObject

        // 六个键一个都没有：这不是「会话刚建」，是「桌面端不认识这套投影」。
        // 返回 null 让 UI 说「读不到」，而不是渲染一排 0 冒充正常。
        if (timeline == null && rawBreakdown == null && usageObj == null &&
            costObj == null && pressureObj == null && statsObj == null
        ) {
            return null
        }

        val level = when {
            timeline != null -> ContextDetailLevel.DETAILED
            rawBreakdown != null -> ContextDetailLevel.BASIC
            else -> ContextDetailLevel.MINIMAL
        }

        val current = timeline?.get("current") as? JsonObject
        val counts = timeline?.get("counts") as? JsonObject
        // `last.prompt` 是「当前这一轮请求的上下文占用」，与 `current.total`（全量累计）
        // 语义完全不同，**不要**互相兜底到看起来一样的数字上。见类注释。
        val last = timeline?.get("last") as? JsonObject

        val breakdown = when (level) {
            ContextDetailLevel.DETAILED -> ContextBreakdown(
                system = current.longOrZero("system"),
                tools = current.longOrZero("tools"),
                user = current.longOrZero("user"),
                inject = current.longOrZero("inject"),
                skill = current.longOrZero("skill"),
                assistant = current.longOrZero("assistant"),
                tool = current.longOrZero("tool"),
                total = current.longOrZero("total"),
            )

            ContextDetailLevel.BASIC -> ContextBreakdown(
                system = rawBreakdown.longOrZero("systemTokens"),
                tools = rawBreakdown.longOrZero("toolsTokens"),
                message = rawBreakdown.longOrZero("messageTokens"),
            )

            ContextDetailLevel.MINIMAL -> ContextBreakdown.EMPTY
        }

        val lastPrompt = last?.long("prompt")?.takeIf { it > 0 }
        // 退化时的分子：按档位取「全量」的同一个口径。BASIC 没有 `current.total`，
        // 用三段之和；MINIMAL 连分段都没有，用 `contextPressure.pressureTokens`。
        val fallbackUsed = when (level) {
            ContextDetailLevel.DETAILED -> breakdown.total
            ContextDetailLevel.BASIC -> breakdown.system + breakdown.tools + breakdown.message
            ContextDetailLevel.MINIMAL -> pressureObj.longOrZero("pressureTokens")
        }

        return ContextInsights(
            contextWindow = (timeline?.long("contextWindow")?.takeIf { it > 0 })
                ?: pressureObj.longOrZero("contextWindow"),
            usedTokens = lastPrompt ?: fallbackUsed,
            hasTurnUsage = lastPrompt != null,
            detailLevel = level,
            model = timeline?.string("model"),
            provider = timeline?.string("provider"),
            maxOutputTokens = timeline?.longOrNull("maxOutputTokens"),
            breakdown = breakdown,
            images = timeline?.int("images") ?: 0,
            toolCalls = timeline?.int("toolCalls") ?: 0,
            humanInputs = timeline?.int("humanInputs") ?: 0,
            answers = timeline?.int("answers") ?: 0,
            turns = counts.intOrZero("turns").takeIf { it > 0 } ?: statsObj.intOrZero("turns"),
            steps = counts.intOrZero("steps").takeIf { it > 0 } ?: statsObj.intOrZero("steps"),
            compactions = counts.intOrZero("compactions"),
            prunes = counts.intOrZero("prunes"),
            usage = TokenUsage(
                uncachedInput = usageObj.longOrZero("uncachedInputTokens"),
                output = usageObj.longOrZero("outputTokens"),
                cacheRead = usageObj.longOrZero("cacheReadTokens"),
                cacheWrite = usageObj.longOrZero("cacheWriteTokens"),
            ),
            // `cost` 是浮点；`costUsage` 缺失时留 null 而不是 0——「没算出来」与「免费」是两件事。
            cost = (costObj?.get("cost") as? JsonPrimitive)?.content?.toDoubleOrNull(),
            costDetail = parseCost(costObj),
            pressure = pressureObj?.long("pressureTokens")?.takeIf { it > 0 },
            timing = parseTiming(timeline?.get("timing") as? JsonObject, statsObj),
        )
    }

    /**
     * 费用明细。
     *
     * 优先读 `byProviderModel`（键是 `provider:model`，同名模型在不同渠道可以不同价），
     * **它读不到条目时**再退回 `byModel`。两者都按金额降序。
     *
     * 判据是「有没有条目」而不是「键在不在」：实测 `dsh-cost-meter` 有时会下发
     * `"byProviderModel": {}` 这样的空对象（32 条样本里 2 条如此），此时只判 null
     * 会选中这个空对象、把同一响应里非空的 `byModel` 整个丢掉 —— 模型的 token 构成
     * 与未计价警示都会凭空消失，界面上是「有费用、却没有任何模型」。
     */
    private fun parseCost(costObj: JsonObject?): CostDetail? {
        if (costObj == null) return null
        val cost = (costObj["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null

        val byProvider = costObj["byProviderModel"] as? JsonObject
        val source = byProvider?.takeIf { it.isNotEmpty() } ?: (costObj["byModel"] as? JsonObject)
        val models = source?.entries?.mapNotNull { (name, element) ->
            val bucket = element as? JsonObject ?: return@mapNotNull null
            ModelCost(
                name = name,
                cost = (bucket["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
                totalTokens = bucket.longOrZero("input") + bucket.longOrZero("output") +
                    bucket.longOrZero("cacheRead") + bucket.longOrZero("cacheWrite"),
            )
        }?.sortedByDescending { it.cost }.orEmpty()

        return CostDetail(
            reasoningTokens = costObj.longOrZero("reasoning"),
            cost = cost,
            models = models,
        )
    }

    /** 耗时分布：插件给六段，没有就退到 `sessionStats` 的两项。 */
    private fun parseTiming(rawTiming: JsonObject?, statsObj: JsonObject?): TurnTiming? {
        if (rawTiming != null) {
            val wallMs = rawTiming.longOrZero("wallMs")
            val rawTtft = rawTiming.longOrZero("ttftMs")
            val reasoning = rawTiming.longOrZero("reasoningMs")
            val text = rawTiming.longOrZero("textMs")
            val toolArg = rawTiming.longOrZero("toolArgMs")
            val tools = rawTiming.longOrZero("toolsMs")
            // 六段互不重叠的唯一切法：把 reasoning 从 ttft 里扣出来。
            val ttft = (rawTtft - reasoning).coerceAtLeast(0)
            // `otherMs` 用墙钟反推而不是把五个字段相加：相加会重复计入 reasoning（见类注释），
            // 反推则无论服务端怎么调整口径，六段之和都自动等于墙钟。
            val other = (wallMs - rawTtft - text - toolArg - tools).coerceAtLeast(0)

            val speedTokens = rawTiming.longOrZero("speedTokens")
            val speedMs = rawTiming.longOrZero("speedMs")
            val tps = if (speedMs > 0 && speedTokens > 0) {
                (speedTokens * 1000.0 / speedMs).toInt()
            } else {
                null
            }

            val toolMap = rawTiming["tools"] as? JsonObject
            val toolTimings = toolMap?.entries?.mapNotNull { (name, element) ->
                val detail = element as? JsonObject ?: return@mapNotNull null
                ToolTiming(
                    name = name,
                    calls = detail.intOrZero("calls"),
                    millis = detail.longOrZero("ms"),
                )
            }?.sortedByDescending { it.millis }.orEmpty()

            if (wallMs <= 0) return null
            return TurnTiming(
                wallMs = wallMs,
                ttftMs = ttft,
                reasoningMs = reasoning,
                textMs = text,
                toolArgMs = toolArg,
                toolsMs = tools,
                otherMs = other,
                tokensPerSecond = tps,
                detailAvailable = true,
                llmMs = statsObj.longOrZero("llmMs"),
                tools = toolTimings,
            )
        }

        if (statsObj != null) {
            val llm = statsObj.longOrZero("llmMs")
            val tools = statsObj.longOrZero("toolMs")
            if (llm > 0 || tools > 0) {
                // 降级态没有墙钟（`sessionStats` 不给），用「模型 + 工具」之和当分母。
                // 它小于真实墙钟（不含人思考、不含等待），所以界面在降级态**不显示**
                // 「活跃时长」这个说法，只显示这两段各自多长。
                return TurnTiming(
                    wallMs = llm + tools,
                    llmMs = llm,
                    toolsMs = tools,
                    detailAvailable = false,
                )
            }
        }
        return null
    }

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.int(key: String): Int = long(key).toInt()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** null 安全：`current` / `counts` / 各个可选投影都可能整块缺失（会话刚开始）。 */
    private fun JsonObject?.longOrZero(key: String): Long = this?.long(key) ?: 0L

    private fun JsonObject?.intOrZero(key: String): Int = this?.longOrZero(key)?.toInt() ?: 0
}

/**
 * 把 `session/control` 的一条投影增量合并进已有的 `values`。
 *
 * ## 为什么需要累加器
 * `session/control` 的增量帧是**单个键**（`{type:'projection', key, value}`），而
 * [ContextInsightsParser.parse] 要一次看到六个键才能定档位。所以侧边栏必须自己维护
 * 「已知的最新值集合」，每来一帧合并一次再整块解析。
 *
 * ## 三个必须的规则
 *  1. **只认这六个键**，其余（`contextHeaders` 70 KB、`turnOutline` 11 KB 等）直接返回
 *     原对象，不进累加器——否则一次会话跑下来，这个 JsonObject 会涨到几 MB 常驻内存。
 *  2. `value` 为 null / `JsonNull` 时**移除**该键而不是写入空值：投影被卸载时是这么通知的，
 *     留着会让解析器拿一个 `null` 当「有这个键」而定错档位。
 *  3. 返回**新对象**而不是原地改：`_insights` 要按引用变化触发重组，原地改会让
 *     `StateFlow` 判定「值没变」而吞掉更新。
 */
internal fun mergeContextValue(current: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement?): JsonObject {
    if (key !in CONTEXT_PROJECTION_KEYS) return current
    val next = current.toMutableMap()
    if (value == null || value is kotlinx.serialization.json.JsonNull) {
        next.remove(key)
    } else {
        next[key] = value
    }
    return JsonObject(next)
}

/**
 * 用一次**全量读**的返回值重建累加器。
 *
 * 全量读是权威快照：服务端**不再给出**某个键（插件在运行期被卸载、某个投影被卸载）
 * 时不会补一个 null，只会让这个键缺席。所以这里必须从空开始重建，不能并进旧值——
 * 否则档位永远停在 DETAILED，侧栏一直显示早已不存在的数据（R040 审查 P1）。
 *
 * 增量帧走的才是 [mergeContextValue]（那里 null 表示「这个键没了」）。
 */
internal fun rebuildContextValues(values: JsonObject): JsonObject {
    val next = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    CONTEXT_PROJECTION_KEYS.forEach { key ->
        values[key]?.let { next[key] = it }
    }
    return JsonObject(next)
}

/**
 * 全量读与增量帧撞车时的收尾：把刷新期间攒下的增量帧盖到刚读回来的快照上。
 *
 * `session/control` 的帧在刷新在飞时会先攒到 `pending`（见 `ChatViewModel`），
 * 读回来之后再补这一步，否则那几帧被静默丢掉，侧栏要等到**下一次**变更才追上
 * （R040 审查 P2）。
 */
internal fun replayPendingContext(snapshot: JsonObject, pending: JsonObject): JsonObject {
    if (pending.isEmpty()) return snapshot
    var merged = snapshot
    pending.forEach { (key, value) -> merged = mergeContextValue(merged, key, value) }
    return merged
}

/** 侧边栏关心的六个投影键。顺序无所谓，但必须与 [ContextInsightsParser] 读的那批完全一致。 */
internal val CONTEXT_PROJECTION_KEYS: Set<String> = setOf(
    ContextInsightsParser.KEY_TIMELINE,
    ContextInsightsParser.KEY_BREAKDOWN,
    ContextInsightsParser.KEY_TOKEN_USAGE,
    ContextInsightsParser.KEY_COST,
    ContextInsightsParser.KEY_PRESSURE,
    ContextInsightsParser.KEY_SESSION_STATS,
)

/** 便于 UI 直接取：投影里有没有上下文数据。 */
fun contextInsightsOf(values: JsonObject?): ContextInsights? = ContextInsightsParser.parse(values)

/** 上下文 token 数的显示形态：`1.5M` / `151.8K` / `987`。 */
internal fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (m >= 10) "${m.toInt()}M" else String.format(java.util.Locale.US, "%.1fM", m)
    }

    tokens >= 10_000 -> "${tokens / 1000}K"
    tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1000.0)
    else -> tokens.toString()
}

/**
 * 时长的显示形态。
 *
 * 口径与插件一致：超过 1 小时给 `24h21m`，超过 1 分钟给 `16h6m` / `8m5s`，否则 `1.2s`。
 * 一律不带前导零——`8h05m` 那种写法在窄侧边栏里多占一个字宽。
 */
internal fun formatDuration(millis: Long): String {
    if (millis <= 0) return "—"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m${seconds}s"
        else -> String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
    }
}

/** 美元金额，保留两位；null 时返回 null（调用方据此整块不渲染）。 */
internal fun formatCost(cost: Double?): String? =
    cost?.let { String.format(java.util.Locale.US, "$%.2f", it) }
