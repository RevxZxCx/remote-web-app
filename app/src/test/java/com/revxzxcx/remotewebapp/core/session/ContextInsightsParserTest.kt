package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextInsightsParser] 的测试。
 *
 * ## 样本全部抄自真实报文
 * 下面每一个 JSON 都是从真实会话（`session-ad282b0d`）的 `session/projections` 响应里
 * 原样摘出来的，没有一个是手写的理想形状。R031 的教训：单测喂扁平样本而线上是双层包裹，
 * 导致「单测全绿、真机从不渲染」——样本形状必须抄真报文。
 *
 * ## 三条最容易写错、这里逐条钉死的语义
 * 1. `last.prompt`（本轮占用 150K）与 `current.total`（全量累计 3.7M）差 24 倍，
 *    混用会让 1M 窗口显示 373%；
 * 2. 三档精度（七段 / 三段 / 无）由**哪些键存在**决定，不是由数值大小决定；
 * 3. 耗时六段之和必须等于墙钟——`reasoningMs` 属于 `ttftMs` 的一部分，直接相加会多出来。
 */
class ContextInsightsParserTest {

    private fun values(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    // ── 真实报文片段 ──────────────────────────────────────────────────────

    /** 插件态：`contextTimeline` 在。数值是实测值。 */
    private fun detailedJson() = """
        {
          "contextTimeline": {
            "ok": true, "model": "deepseek-v4.1-flash", "provider": "buddy",
            "contextWindow": 1000000, "maxOutputTokens": 128000,
            "current": {"system":3921,"tools":16296,"user":2949,"inject":119824,
                        "skill":3508,"assistant":2202493,"tool":1520351,"total":3869342},
            "last": {"seq":25562,"total":3868934,"prompt":172744},
            "images": 180, "toolCalls": 4484, "humanInputs": 55, "answers": 550,
            "counts": {"turns":20,"steps":1500,"injects":375,"compactions":0,"prunes":0,"skills":0},
            "timing": {
              "wallMs": 93254721, "ttftMs": 50829106, "genMs": 12794698,
              "reasoningMs": 6605200, "textMs": 238161, "toolArgMs": 5951337,
              "speedTokens": 2953216, "speedMs": 12793479, "calls": 4746,
              "toolsMs": 29240072, "toolCalls": 4725,
              "tools": {"bash":{"calls":3938,"ms":13639529},"read":{"calls":136,"ms":4001}}
            }
          },
          "tokenUsage": {"uncachedInputTokens":9399316,"outputTokens":2946867,
                         "cacheReadTokens":674150656,"cacheWriteTokens":0},
          "costUsage": {"input":9399316,"output":2946867,"cacheRead":674150656,
                        "cacheWrite":0,"reasoning":1363559,"cost":4.6627997520000015,
                        "byModel":{"deepseek-v4.1-flash":{"input":8579976,"output":2826865,
                                   "cacheRead":559894784,"cacheWrite":0,"reasoning":1356625,
                                   "cost":4.6627997520000015},
                                   "dfmodel":{"input":819340,"output":120002,
                                   "cacheRead":114255872,"cacheWrite":0,"reasoning":6934,"cost":0}}},
          "contextPressure": {"pressureTokens":153988,"projectedTokens":154334,"contextWindow":1000000},
          "sessionStats": {"turns":51,"steps":4756,"llmMs":63623804,"toolMs":29240072,
                           "ttftMs":50830325,"ttftSteps":4746,"decodeMs":12793479,"decodeTokens":2953216}
        }
    """.trimIndent()

    /** 降级态：只有核心自带的三段，没有任何 `contextTimeline` / `costUsage`。 */
    private fun basicJson() = """
        {
          "contextBreakdown": {"systemTokens":3921,"toolsTokens":16296,"messageTokens":3836505},
          "tokenUsage": {"uncachedInputTokens":8984265,"outputTokens":2792233,
                         "cacheReadTokens":641429248,"cacheWriteTokens":0},
          "contextPressure": {"pressureTokens":156634,"projectedTokens":158706,"contextWindow":1000000},
          "sessionStats": {"turns":46,"steps":4510,"llmMs":58166495,"toolMs":29086442,
                           "ttftMs":46097908,"ttftSteps":4500,"decodeMs":12068587,"decodeTokens":2775822}
        }
    """.trimIndent()

    // ── 本轮占用 vs 全量累计 ──────────────────────────────────────────────

    @Test
    fun `本轮占用取 last prompt 而不是 current total`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(172_744L, insights.usedTokens)
        assertEquals(3_869_342L, insights.breakdown.total)
        assertTrue("两个数不能是同一个", insights.usedTokens != insights.breakdown.total)
    }

    @Test
    fun `进度条按本轮占用算，不是按累计，否则 1M 窗口会显示 387 倍`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(1_000_000L, insights.contextWindow)
        // 172,744 / 1,000,000 ≈ 17%
        assertEquals(17, insights.percent)
        assertTrue("一旦混用累计，百分比会超过 100", insights.fraction!! <= 1f)
    }

    @Test
    fun `last prompt 缺失时退回全量但标记 hasTurnUsage 为假`() {
        val json = detailedJson()
            .replace(""","prompt":172744""", "")
        val insights = ContextInsightsParser.parse(values(json))!!
        assertFalse(insights.hasTurnUsage)
        assertEquals(3_869_342L, insights.usedTokens)
    }

    @Test
    fun `剩余量不会因为超窗变成负数`() {
        val json = detailedJson()
            .replace(""""contextWindow": 1000000, "maxOutputTokens": 128000""", """"contextWindow": 100000, "maxOutputTokens": 128000""")
        val insights = ContextInsightsParser.parse(values(json))!!
        assertEquals(0L, insights.remaining)
        assertEquals(100, insights.percent)
    }

    // ── 三档精度 ──────────────────────────────────────────────────────────

    @Test
    fun `有 contextTimeline 时是七段细分`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(ContextDetailLevel.DETAILED, insights.detailLevel)
        assertEquals(7, insights.segments.size)
        assertEquals(listOf("system", "tools", "user", "inject", "skill", "assistant", "tool"),
            insights.segments.map { it.key })
    }

    @Test
    fun `没有 contextTimeline 只有 breakdown 时退到三段并标出消息是合并值`() {
        val insights = ContextInsightsParser.parse(values(basicJson()))!!
        assertEquals(ContextDetailLevel.BASIC, insights.detailLevel)
        assertEquals(3, insights.segments.size)
        assertEquals("消息", insights.segments.first().label)
        // 三段之和就是合计（服务端没有给 BASIC 的 total）
        assertEquals(3_856_722L, insights.breakdownTotal)
    }

    @Test
    fun `六个投影都没有时返回 null 而不是一堆零`() {
        assertNull(ContextInsightsParser.parse(values("""{"title":{"text":"x"}}""")))
        assertNull(ContextInsightsParser.parse(null))
    }

    @Test
    fun `连 breakdown 都没有时只剩占用与用量，构成图为空`() {
        val json = """
            {
              "tokenUsage": {"uncachedInputTokens":100,"outputTokens":50,
                             "cacheReadTokens":900,"cacheWriteTokens":0},
              "contextPressure": {"pressureTokens":156634,"contextWindow":1000000}
            }
        """.trimIndent()
        val insights = ContextInsightsParser.parse(values(json))!!
        assertEquals(ContextDetailLevel.MINIMAL, insights.detailLevel)
        assertTrue(insights.segments.isEmpty())
        // 没有分段时分子退到 contextPressure
        assertEquals(156_634L, insights.usedTokens)
        assertEquals(1_000_000L, insights.contextWindow)
    }

    // ── 耗时六段 ──────────────────────────────────────────────────────────

    @Test
    fun `耗时六段之和等于墙钟，reasoning 不从 ttft 里重复计`() {
        val timing = ContextInsightsParser.parse(values(detailedJson()))!!.timing!!
        assertTrue(timing.detailAvailable)
        assertEquals(93_254_721L, timing.wallMs)
        val sum = timing.segments.sumOf { it.millis }
        assertEquals("六段必须加回墙钟，否则圆环画不满", timing.wallMs, sum)
    }

    @Test
    fun `模型等待时长已扣掉思考，否则会和模型思考段重叠`() {
        val timing = ContextInsightsParser.parse(values(detailedJson()))!!.timing!!
        // ttftMs 50,829,106 - reasoningMs 6,605,200
        assertEquals(44_223_906L, timing.ttftMs)
        assertEquals(6_605_200L, timing.reasoningMs)
    }

    @Test
    fun `输出速度按服务端给的 speedTokens 除 speedMs`() {
        val timing = ContextInsightsParser.parse(values(detailedJson()))!!.timing!!
        // 2,953,216 / 12,793,479 s ≈ 230.8 tok/s
        assertEquals(230, timing.tokensPerSecond)
    }

    @Test
    fun `没有 timing 时退到 sessionStats 的两段，且不冒充六段`() {
        val timing = ContextInsightsParser.parse(values(basicJson()))!!.timing!!
        assertFalse(timing.detailAvailable)
        assertEquals(2, timing.segments.size)
        assertEquals(listOf("llm", "tools"), timing.segments.map { it.key })
        assertEquals(58_166_495L + 29_086_442L, timing.segmentsTotal)
    }

    @Test
    fun `工具耗时按降序排，最慢的在最前`() {
        val timing = ContextInsightsParser.parse(values(detailedJson()))!!.timing!!
        assertEquals("bash", timing.tools.first().name)
        assertEquals(13_639_529L, timing.tools.first().millis)
    }

    // ── 费用 ──────────────────────────────────────────────────────────────

    @Test
    fun `费用读服务端算好的值，不做 token 乘法`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(4.6627997520000015, insights.cost!!, 1e-9)
        assertEquals(4.6627997520000015, insights.costDetail!!.cost, 1e-9)
    }

    @Test
    fun `有 token 但金额为零的模型被标成未计价，而不是免费`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        val detail = insights.costDetail!!
        // dfmodel 消耗了 115M token 但 cost 是 0——价表里没有它
        assertEquals(listOf("dfmodel"), detail.unpricedModels)
        assertFalse(detail.fullyPriced)
        assertEquals("deepseek-v4.1-flash", detail.models.first().name)
    }

    @Test
    fun `零 token 的零元模型不算未计价`() {
        // 服务端可能给一个空桶（模型被选中过但还没产生用量）。它 cost=0、token 也是 0，
        // 报成「未计价」会让界面出现一个毫无意义的警告。
        val detail = ContextInsightsParser.parse(values(detailedJson()))!!.costDetail!!
        val zero = ModelCost(name = "dfmodel", cost = 0.0, totalTokens = 0)
        val empty = CostDetail(cost = 0.0, models = listOf(zero))
        assertTrue("零用量的模型不该出现在未计价列表里", empty.unpricedModels.isEmpty())
        assertTrue(empty.fullyPriced)
        // 反过来：有 token 但零元才算未计价
        val used = CostDetail(cost = 0.0, models = listOf(zero.copy(totalTokens = 1_000)))
        assertEquals(listOf("dfmodel"), used.unpricedModels)
        // 真实报文里 dfmodel 确实消耗了 token，所以要报
        assertEquals(listOf("dfmodel"), detail.unpricedModels)
    }

    @Test
    fun `推理 token 单独记录，与输出分开`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(1_363_559L, insights.costDetail!!.reasoningTokens)
        assertEquals(2_946_867L, insights.usage.output)
    }

    @Test
    fun `没有 costUsage 时费用是 null 而不是 0`() {
        val insights = ContextInsightsParser.parse(values(basicJson()))!!
        assertNull(insights.cost)
        assertNull(insights.costDetail)
    }

    // ── 用量与缓存命中率 ──────────────────────────────────────────────────

    @Test
    fun `缓存命中率的分母是全部输入，不是只除未缓存输入`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        val usage = insights.usage
        // 674,150,656 / (9,399,316 + 674,150,656 + 0) ≈ 98.6%
        val expected = 674_150_656.0 / (9_399_316.0 + 674_150_656.0)
        assertEquals(expected.toFloat(), usage.cacheHitRate!!, 1e-6f)
    }

    @Test
    fun `没有输入时缓存命中率是 null 而不是 0`() {
        assertNull(TokenUsage().cacheHitRate)
    }

    // ── 图例排序与配色键 ──────────────────────────────────────────────────

    @Test
    fun `图例按大小排但圆环顺序固定，颜色才不会串位`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        // 圆环：固定顺序（system 打头）
        assertEquals("system", insights.segments.first().key)
        // 图例：最大在前（助手输出 2.2M）
        assertEquals("assistant", insights.segmentsBySize.first().key)
    }

    @Test
    fun `零值的段不参与绘制`() {
        val json = detailedJson().replace(""""skill":3508""", """"skill":0""")
        val insights = ContextInsightsParser.parse(values(json))!!
        assertTrue(insights.segments.none { it.key == "skill" })
        assertEquals(6, insights.segments.size)
    }

    @Test
    fun `占比用服务端 total 当分母，各段加起来不会超过 100 percent`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        val sum = insights.segments.sumOf { insights.shareOf(it).toDouble() }
        assertTrue("各段占比之和不应超过 1（total 含未分类项）", sum <= 1.0001)
    }

    // ── 计数 ──────────────────────────────────────────────────────────────

    @Test
    fun `轮数优先取 timeline 的 counts，没有才用 sessionStats`() {
        assertEquals(20, ContextInsightsParser.parse(values(detailedJson()))!!.turns)
        assertEquals(46, ContextInsightsParser.parse(values(basicJson()))!!.turns)
    }

    @Test
    fun `工具调用与图片数直接来自 timeline`() {
        val insights = ContextInsightsParser.parse(values(detailedJson()))!!
        assertEquals(4_484, insights.toolCalls)
        assertEquals(180, insights.images)
        assertEquals(55, insights.humanInputs)
        assertEquals(550, insights.answers)
    }

    // ── 显示格式（纯函数） ────────────────────────────────────────────────

    @Test
    fun `token 数格式化`() {
        assertEquals("987", formatTokens(987))
        assertEquals("1.5K", formatTokens(1_500))
        assertEquals("151K", formatTokens(151_829))
        assertEquals("1.5M", formatTokens(1_500_000))
        assertEquals("12M", formatTokens(12_345_678))
    }

    @Test
    fun `时长格式化`() {
        assertEquals("—", formatDuration(0))
        assertEquals("1.2s", formatDuration(1_234))
        assertEquals("8m5s", formatDuration(485_000))
        assertEquals("24h21m", formatDuration(87_660_000))
        // 不足一小时但正好整分时秒数是 0，不补前导零
        assertEquals("16h6m", formatDuration(57_960_000))
    }

    @Test
    fun `金额格式化，null 时不返回字符串`() {
        assertEquals("$4.66", formatCost(4.6627997520000015))
        assertEquals("$0.00", formatCost(0.0))
        assertNull(formatCost(null))
    }

    // ── 解析健壮性 ────────────────────────────────────────────────────────

    @Test
    fun `字段类型不对时退回默认值而不是抛异常`() {
        val json = """
            {"contextTimeline": {"contextWindow": "not-a-number",
                                 "current": {"system": 3921},
                                 "last": {"prompt": 0}}}
        """.trimIndent()
        val insights = ContextInsightsParser.parse(values(json))
        assertNotNull(insights)
        assertEquals(0L, insights!!.contextWindow)
        assertNull(insights.fraction)
        // prompt 是 0，按「缺失」处理
        assertFalse(insights.hasTurnUsage)
    }
}
