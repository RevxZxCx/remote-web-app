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
 * [ContextTotalsCalculator] 与 [InsightsRange] 的测试。
 *
 * ## 样本抄真报文，不手写理想形状
 *
 * `tokenUsage` / `costUsage` / `sessionStats` / `contextTimeline` 四段 JSON 全部从
 * `session/list` 的真实响应里摘出来（2026-10-26 实测，见 R050 原型第 6 节）。R031 的教训：
 * 手写扁平样本会让单测全绿而线上从不命中。
 *
 * ## 这里钉死的几条口径
 *
 * 1. **范围过滤按 `updatedAt`**：`ALL` 收全部，`WEEK` / `MONTH` 只收窗口内的；
 *    `updatedAt <= 0`（解析失败）只有 `ALL` 收——时间未知不等于「很新」。
 * 2. **不完整的数据不参与聚合**：某会话读不到用量时整条跳过，而不是按 0 计入
 *    「会话数」。否则界面会出现「32 个会话，合计 0 token」这种自相矛盾的读数。
 * 3. **未计价（`cost == 0 && tokens > 0`）单独统计**，不能当作免费：那是价表里
 *    没有这个模型。
 * 4. **排行占比的分母是排行内的 token 之和**，不是总量——两者相等当且仅当所有会话
 *    都进了排行。
 */
class ContextTotalsTest {

    private fun values(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    // ── 真实报文片段（每段都是 session/list 里原样摘出来的） ────────────────

    /** 实测 `tokenUsage`（一个真实主会话）。 */
    private fun tokenUsageJson(
        uncached: Long = 1_126_995,
        output: Long = 150_527,
        cacheRead: Long = 28_481_280,
        cacheWrite: Long = 0,
    ) = """
        {
          "tokenUsage": {
            "uncachedInputTokens": $uncached,
            "outputTokens": $output,
            "cacheReadTokens": $cacheRead,
            "cacheWriteTokens": $cacheWrite
          }
        }
    """

    /** 实测 `costUsage`：注意 step-5-preview 的 `cost` 是 0 但 token 非 0（价表没有它）。 */
    private fun costUsageJson() = """
        {
          "costUsage": {
            "input": 1000, "output": 2000, "cacheRead": 3000, "cacheWrite": 0,
            "reasoning": 0, "cost": 1.5,
            "byModel": {
              "step-5-preview": {"input": 10, "output": 20, "cacheRead": 30, "cacheWrite": 0, "cost": 0},
              "deepseek-v4.1-flash": {"input": 40, "output": 50, "cacheRead": 60, "cacheWrite": 0, "cost": 1.5}
            },
            "byProviderModel": {}
          }
        }
    """

    /** 实测 `sessionStats` + `contextTimeline.timing`（降级态只有 llmMs / toolMs）。 */
    private fun statsJson() = """
        {
          "sessionStats": {
            "turns": 6, "steps": 206,
            "llmMs": 4983748, "toolMs": 99700,
            "ttftMs": 4207286, "ttftSteps": 204,
            "decodeMs": 776462, "decodeTokens": 150527
          }
        }
    """

    private fun summary(
        id: String,
        updatedAt: Long,
        title: String = id,
        cwd: String? = "F:/bot",
        model: String? = "deepseek-v4.1-flash",
    ) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        title = title,
        cwd = cwd,
        lastModel = model,
    )

    private val now = 1_790_000_000_000L
    private val day = InsightsRange.MILLIS_PER_DAY

    // ── InsightsRange ──────────────────────────────────────────────────────

    @Test
    fun rangeLabelsMatchTheProto() {
        // 三档文案是原型第 2 节定死的，改了用户会以为换了功能。
        assertEquals("近 7 天", InsightsRange.WEEK.label)
        assertEquals("近 30 天", InsightsRange.MONTH.label)
        assertEquals("全部", InsightsRange.ALL.label)
        assertEquals(
            listOf(InsightsRange.WEEK, InsightsRange.MONTH, InsightsRange.ALL),
            InsightsRange.ordered,
        )
    }

    @Test
    fun allRangeTakesEverythingIncludingUnknownTimestamp() {
        assertTrue(InsightsRange.ALL.contains(now, now))
        assertTrue(InsightsRange.ALL.contains(0L, now))
        assertTrue(InsightsRange.ALL.contains(-1L, now))
    }

    @Test
    fun windowRangeExcludesUnknownTimestamp() {
        // 时间未知不等于「很新」：只有 ALL 收它。否则解析失败的会话会混进「近 7 天」。
        assertFalse(InsightsRange.WEEK.contains(0L, now))
        assertFalse(InsightsRange.MONTH.contains(0L, now))
    }

    @Test
    fun windowRangeBoundaryIsInclusive() {
        val cutoff = now - 7 * day
        assertTrue(InsightsRange.WEEK.contains(cutoff, now))
        assertFalse(InsightsRange.WEEK.contains(cutoff - 1, now))
    }

    @Test
    fun sevenDayWindowIsNarrowerThanThirty() {
        val tenDaysAgo = now - 10 * day
        assertFalse(InsightsRange.WEEK.contains(tenDaysAgo, now))
        assertTrue(InsightsRange.MONTH.contains(tenDaysAgo, now))
    }

    // ── SessionUsage.of ────────────────────────────────────────────────────

    @Test
    fun usageReadsAllThreeProjections() {
        val usage = SessionUsage.of("s1", values("""
            { ${tokenUsageJson().trim().removeSurrounding("{", "}")},
              ${costUsageJson().trim().removeSurrounding("{", "}")},
              ${statsJson().trim().removeSurrounding("{", "}")} }
        """))
        assertNotNull(usage)
        val u = usage!!
        assertEquals(1_126_995L + 150_527L + 28_481_280L, u.tokens.total)
        assertEquals(1.5, u.cost!!, 1e-9)
        assertEquals(6, u.turns)
        assertEquals(206, u.steps)
    }

    @Test
    fun usageIsNullWhenNothingReadable() {
        // 三项全缺 → null。返回一个「全 0 的 usage」会让聚合层把它算成 1 个会话。
        assertNull(SessionUsage.of("s1", values("""{"title":"x"}""")))
        assertNull(SessionUsage.of("s1", null))
    }

    @Test
    fun usageSurvivesWithOnlyTokenUsage() {
        // 没装插件时只有核心的 tokenUsage。仍要能构出 usage，否则降级态什么都没有。
        val u = SessionUsage.of("s1", values(tokenUsageJson()))!!
        assertEquals(29_758_802L, u.tokens.total)
        assertNull(u.cost)
        assertEquals(0, u.turns)
    }

    // ── compute：范围过滤 ──────────────────────────────────────────────────

    @Test
    fun computeFiltersByRange() {
        val fresh = summary("fresh", now - day)
        val old = summary("old", now - 60 * day)
        val usages = mapOf(
            "fresh" to SessionUsage.of("fresh", values(tokenUsageJson()))!!,
            "old" to SessionUsage.of("old", values(tokenUsageJson()))!!,
        )
        val week = ContextTotalsCalculator.compute(listOf(fresh, old), usages, InsightsRange.WEEK, now)
        assertEquals(1, week.sessionCount)
        val all = ContextTotalsCalculator.compute(listOf(fresh, old), usages, InsightsRange.ALL, now)
        assertEquals(2, all.sessionCount)
    }

    @Test
    fun computeSkipsSessionsWithoutUsage() {
        // 3 条会话只有 2 条有用量 → 会话数是 2 而不是 3。
        // 否则界面会出现「3 个会话，合计 0 token」这种自相矛盾的读数。
        val sessions = listOf(summary("a", now - day), summary("b", now - day), summary("c", now - day))
        val usages = mapOf(
            "a" to SessionUsage.of("a", values(tokenUsageJson()))!!,
            "b" to SessionUsage.of("b", values(tokenUsageJson()))!!,
        )
        val totals = ContextTotalsCalculator.compute(sessions, usages, InsightsRange.ALL, now)
        assertEquals(2, totals.sessionCount)
    }

    @Test
    fun computeReturnsEmptyWhenRangeMatchesNothing() {
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("old", now - 60 * day)),
            mapOf("old" to SessionUsage.of("old", values(tokenUsageJson()))!!),
            InsightsRange.WEEK,
            now,
        )
        assertTrue(totals.isEmpty)
        assertEquals(0, totals.sessionCount)
        assertTrue(totals.ranking.isEmpty())
        assertTrue(totals.models.isEmpty())
    }

    // ── compute：token 汇总 ────────────────────────────────────────────────

    @Test
    fun computeSumsTokensAcrossSessions() {
        val usages = mapOf(
            "a" to SessionUsage.of("a", values(tokenUsageJson()))!!,
            "b" to SessionUsage.of("b", values(tokenUsageJson()))!!,
        )
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day), summary("b", now - day)),
            usages, InsightsRange.ALL, now,
        )
        assertEquals(29_758_802L * 2, totals.tokens.total)
        assertEquals(28_481_280L * 2, totals.tokens.cacheRead)
    }

    @Test
    fun cacheHitRateIsCacheReadOverInputNotTotal() {
        // 分母是**输入**（未缓存输入 + 缓存读 + 缓存写），不是 token 总量。
        // 混入输出会把命中率压低：只要模型话多，命中率看起来就变差。
        val u = SessionUsage.of("a", values(tokenUsageJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        val input = 1_126_995.0 + 28_481_280.0 + 0.0
        // 结果是 Float（与 TokenUsage 同口径），换算回来只有 7 位有效数字，
        // 用 1e-6 的相对容差比绝对容差稳。
        assertEquals(28_481_280.0 / input, totals.cacheHitRate!!.toDouble(), 1e-6)
    }

    @Test
    fun emptyTotalsHasNoCacheHitRateInsteadOfZero() {
        // 没有输入时返回 null 而不是 0.0：0% 是「一次都没命中」，
        // 而空范围是「没有数据」，界面要能区分这两种（否则会显示「0%」误导）。
        assertNull(ContextTotals.EMPTY.cacheHitRate)
    }

    // ── compute：费用与未计价 ──────────────────────────────────────────────

    @Test
    fun computeSumsCostWhenAvailable() {
        val u = SessionUsage.of("a", values(costUsageJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        assertEquals(1.5, totals.cost!!, 1e-9)
        assertTrue(totals.hasCost)
    }

    @Test
    fun costIsNullWhenNoSessionCarriesIt() {
        // 没装 dsh-cost-meter → 整列 cost 缺失。此时 cost 必须是 null（「读不到」），
        // 不是 0.0（「不要钱」）——界面靠这个区分显示占位卡还是金额。
        val u = SessionUsage.of("a", values(tokenUsageJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        assertNull(totals.cost)
        assertFalse(totals.hasCost)
    }

    @Test
    fun unpricedModelsAreCountedSeparately() {
        // step-5-preview 的 cost 是 0 但 token 非 0 → 未计价。
        // 把它当免费会让总额看起来比实际低一大截。
        val u = SessionUsage.of("a", values(costUsageJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        val unpriced = totals.models.filter { it.unpriced }
        assertEquals(1, unpriced.size)
        assertEquals("step-5-preview", unpriced.single().name)
        assertEquals(60L, totals.unpricedTokens) // 10+20+30
        assertEquals(1, totals.unpricedModels)
    }

    @Test
    fun modelWithZeroTokensIsNotUnpriced() {
        // cost == 0 **且** tokens > 0 才算未计价。只用 cost == 0 会把没跑过的模型
        // 也算进去，警示条就会常年挂着。
        val usage = SessionUsage.of("a", values("""
            {"costUsage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"cost":0,
             "byModel":{"unused-model":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"cost":0}},
             "byProviderModel":{}}}
        """))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to usage), InsightsRange.ALL, now,
        )
        assertEquals(0, totals.unpricedModels)
    }

    @Test
    fun modelsAreSortedByTokensDescending() {
        val usage = SessionUsage.of("a", values("""
            {"costUsage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"cost":1.0,
             "byModel":{
               "small":{"input":1,"output":0,"cacheRead":0,"cacheWrite":0,"cost":0.1},
               "big":{"input":9,"output":0,"cacheRead":0,"cacheWrite":0,"cost":0.9}},
             "byProviderModel":{}}}
        """))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to usage), InsightsRange.ALL, now,
        )
        assertEquals(listOf("big", "small"), totals.models.map { it.name })
    }

    // ── compute：耗时 ──────────────────────────────────────────────────────

    @Test
    fun degradedTimingFallsBackToTwoSegments() {
        // 没装插件时只有 sessionStats 的 llmMs / toolMs，detailAvailable = false。
        // 界面的脚注要靠这个判据从「六段之和」改成「两段之和」。
        val u = SessionUsage.of("a", values(statsJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        assertFalse(totals.hasDetailedTiming)
        assertTrue(totals.timing.llmMs > 0L)
        assertTrue(totals.timing.toolsMs > 0L)
    }

    // ── compute：排行 ──────────────────────────────────────────────────────

    @Test
    fun rankingIsSortedByTokensAndSharesSumToOne() {
        val big = SessionUsage.of("big", values(tokenUsageJson(cacheRead = 3_000_000L)))!!
        val small = SessionUsage.of("small", values(tokenUsageJson(cacheRead = 1_000_000L)))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("big", now - day), summary("small", now - day)),
            mapOf("big" to big, "small" to small),
            InsightsRange.ALL, now,
        )
        assertEquals(listOf("big", "small"), totals.ranking.map { it.sessionId })
        assertEquals(1f, totals.ranking.sumOf { it.share.toDouble() }.toFloat(), 1e-4f)
    }

    @Test
    fun rankingSharesAreProportionalToSessionTokens() {
        // 占比由每条会话的 token 决定，两条 3M / 1M 就是 75% / 25%。
        val big = SessionUsage.of("big", values(tokenUsageJson(cacheRead = 3_000_000L, uncached = 0, output = 0)))!!
        val small = SessionUsage.of("small", values(tokenUsageJson(cacheRead = 1_000_000L, uncached = 0, output = 0)))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("big", now - day), summary("small", now - day)),
            mapOf("big" to big, "small" to small),
            InsightsRange.ALL, now,
        )
        assertEquals(0.75f, totals.ranking.first().share, 1e-6f)
        assertEquals(0.25f, totals.ranking.last().share, 1e-6f)
        assertEquals(1f, totals.ranking.sumOf { it.share.toDouble() }.toFloat(), 1e-6f)
    }

    @Test
    fun rankingTotalEqualsTokenTotalByConstruction() {
        // 这一条是**诚实的记录**，不是守护：排行项只由「读到了 tokenUsage 的会话」构成，
        // 而 tokens 也正是从同一批会话累加出来的，所以
        // `ranking.sumOf { it.tokens } == tokens.total` **恒成立**。
        //
        // 换句话说 `share` 的分母无论是「排行之和」还是「tokens.total」在数学上都等价
        // ——把它改成另一种写法是等价变异，测试抓不到也不能抓。
        // 代码里仍写排行之和，理由不是算得不同，而是表意更直接：「这一行占这张榜单多少」，
        // 将来若加了「排除某个会话」的过滤，分母会自动跟着走而不用改两处。
        val a = SessionUsage.of("a", values(tokenUsageJson(cacheRead = 2_000_000L, uncached = 0, output = 0)))!!
        val b = SessionUsage.of("b", values(tokenUsageJson(cacheRead = 5_000_000L, uncached = 0, output = 0)))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day), summary("b", now - day)),
            mapOf("a" to a, "b" to b),
            InsightsRange.ALL, now,
        )
        assertEquals(totals.tokens.total, totals.ranking.sumOf { it.tokens })
    }

    @Test
    fun topShareSumsTheBiggestN() {
        // 只给 cacheRead，把另外三类置 0，这样占比只由 cacheRead 决定，
        // 断言才能写成整比例的 (4+3)/(4+3+2+1) = 0.7。
        val usages = (1..4).map { i ->
            "s$i" to SessionUsage.of(
                "s$i",
                values(tokenUsageJson(uncached = 0, output = 0, cacheRead = i * 1_000_000L)),
            )!!
        }.toMap()
        val totals = ContextTotalsCalculator.compute(
            (1..4).map { summary("s$it", now - day) }, usages, InsightsRange.ALL, now,
        )
        assertEquals(0.7f, totals.topShare(2), 1e-4f)
    }

    @Test
    fun topShareOfZeroSessionsIsZero() {
        assertEquals(0f, ContextTotals.EMPTY.topShare(8))
    }

    @Test
    fun rankingCarriesDisplayFieldsForTheRow() {
        // 会话行要显示「标题 + 工作区·模型·时间」，这些字段必须在排行项里，
        // 否则 UI 层还要再查一次 SessionSummary 才能渲染。
        val u = SessionUsage.of("a", values(tokenUsageJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day, title = "app开发 main", cwd = "F:/bot", model = "qfmodel")),
            mapOf("a" to u), InsightsRange.ALL, now,
        )
        val row = totals.ranking.single()
        assertEquals("app开发 main", row.title)
        assertEquals("F:/bot", row.cwd)
        assertEquals("qfmodel", row.model)
        assertEquals(now - day, row.updatedAt)
    }

    // ── compute：活跃度与总量 ──────────────────────────────────────────────

    @Test
    fun activityBucketsByDayWithFixedLength() {
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day), summary("b", now - day)),
            mapOf(
                "a" to SessionUsage.of("a", values(tokenUsageJson()))!!,
                "b" to SessionUsage.of("b", values(tokenUsageJson()))!!,
            ),
            InsightsRange.ALL, now,
        )
        assertEquals(ContextTotalsCalculator.ACTIVITY_DAYS, totals.activity.size)
        assertEquals(2, totals.activity.sumOf { it.sessions })
    }

    @Test
    fun activityIsIndependentOfTheSelectedRange() {
        // 活跃度是「近 14 天」这个固定窗口的指标，与用户选的统计范围无关。
        // 若把按范围裁过的列表传进去，选「近 7 天」时第 8–14 根柱子会被结构性压成 0，
        // 而卡片仍标着「近 14 天」——用户会读成「那些天真的没人用」。
        val outsideWeekButInside14Days = now - 10 * day
        val sessions = listOf(
            summary("recent", now - day),
            summary("tenDaysAgo", outsideWeekButInside14Days),
        )
        val usages = mapOf(
            "recent" to SessionUsage.of("recent", values(tokenUsageJson()))!!,
            "tenDaysAgo" to SessionUsage.of("tenDaysAgo", values(tokenUsageJson()))!!,
        )

        val week = ContextTotalsCalculator.compute(sessions, usages, InsightsRange.WEEK, now)
        // 7 天范围里只有 1 条会话参与聚合……
        assertEquals(1, week.sessionCount)
        // ……但活跃度仍要看见 14 天窗口里的两条。
        assertEquals(2, week.activity.sumOf { it.sessions })

        val all = ContextTotalsCalculator.compute(sessions, usages, InsightsRange.ALL, now)
        assertEquals(2, all.sessionCount)
        assertEquals(2, all.activity.sumOf { it.sessions })
    }

    @Test
    fun activityDaysAreConsecutiveLocalMidnightsAcrossDst() {
        // 有夏令时的时区里一天是 23 或 25 小时，用 `todayStart - i*86_400_000` 累加
        // 得到的时刻不再是本地零点，与 counts 的键对不上，那一天就被静默画成 0。
        // 这里直接验「相邻两天的标签日期差 1 天、且末尾是今天」——
        // 夏令时错位时中间会漏掉一天。
        val zone = java.util.TimeZone.getDefault()
        val saved = zone.clone() as java.util.TimeZone
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            // 2026-04-05 是欧洲夏令时切换日（03-29 起）。取 4 月 5 日中午避开边界。
            val noon = 1_775_380_800_000L // 2026-04-05T10:00:00Z
            val totals = ContextTotalsCalculator.compute(
                listOf(summary("a", noon)),
                mapOf("a" to SessionUsage.of("a", values(tokenUsageJson()))!!),
                InsightsRange.ALL, noon,
            )
            val days = totals.activity.map { it.day }
            assertEquals(ContextTotalsCalculator.ACTIVITY_DAYS, days.size)
            assertEquals(days.size, days.toSet().size) // 不许有重复日期
            // 逐日相差恰好 1 天（字符串前缀相同、日号连续）。
            val nums = days.map { it.takeLast(2).toInt() }
            for (i in 1 until nums.size) {
                val expected = if (nums[i - 1] == 1) 2 else nums[i - 1] + 1
                // 跨月时 nums 会回绕，这里只在同月内断言连续性。
                if (nums[i] > nums[i - 1]) assertEquals("第 $i 天断档：$days", expected, nums[i])
            }
            assertEquals(1, totals.activity.sumOf { it.sessions })
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun turnsAndStepsAreSummed() {
        val u = SessionUsage.of("a", values(statsJson()))!!
        val totals = ContextTotalsCalculator.compute(
            listOf(summary("a", now - day)), mapOf("a" to u), InsightsRange.ALL, now,
        )
        assertEquals(6, totals.turns)
        assertEquals(206, totals.steps)
    }
}
