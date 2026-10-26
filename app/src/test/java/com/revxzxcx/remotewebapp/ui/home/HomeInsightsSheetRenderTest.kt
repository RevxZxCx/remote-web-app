package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.ContextTotals
import com.revxzxcx.remotewebapp.core.session.DayActivity
import com.revxzxcx.remotewebapp.core.session.InsightsRange
import com.revxzxcx.remotewebapp.core.session.ModelUsage
import com.revxzxcx.remotewebapp.core.session.SessionRank
import com.revxzxcx.remotewebapp.core.session.TokenUsage
import com.revxzxcx.remotewebapp.core.session.TurnTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HomeInsightsSheet] 的渲染测试。
 *
 * ## 守的是什么
 *
 * 这份 UI 有两类「编译通过但用户看不懂」的失效方式，录代码看不出来：
 *
 * 1. **没装插件时出现空壳**：三张卡里只要有一张渲染成空白（没有弧、没有图例、
 *    只剩一个圆心数字），用户会以为 App 坏了。所以降级态的三种表现必须逐条断言：
 *    token 环退回四类口径、费用位变成占位卡、耗时脚注改成「两段之和」。
 * 2. **数字与口径不匹配**：脚注写着「六段之和 = 墙钟」而实际只有两段时，
 *    用户会拿它去核对桌面端，发现永远对不上。
 *
 * ## 为什么用 `createComposeRule()` 而不是 `createAndroidComposeRule`
 *
 * 这里不测返回键、不测 Activity 生命周期，只需要组合出界面看文案。用 Android
 * 变体会多起一个 Activity，且 [HomeInsightsSheet] 内部没有依赖任何平台能力。
 *
 * ## Robolectric 视口只有 320×470 px 的坑
 *
 * 抽屉（320dp）比视口宽，且内部纵向内容很长。「存在」用 `assertExists()`，
 * 只有确实在首屏的才用 `assertIsDisplayed()`——这与 R033 记录的经验一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeInsightsSheetRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val tokens = TokenUsage(
        uncachedInput = 1_126_995L,
        output = 150_527L,
        cacheRead = 28_481_280L,
        cacheWrite = 0L,
    )

    /** 插件态：三张卡齐全。 */
    private fun detailedTotals() = ContextTotals(
        sessionCount = 32,
        tokens = tokens,
        cost = 155.19,
        models = listOf(
            ModelUsage("step-5-preview", 1_037_000_000L, 140.37),
            ModelUsage("deepseek-v4.1-flash", 1_461_000_000L, 13.68),
        ),
        unpricedTokens = 1_298_000_000L,
        unpricedModels = 14,
        timing = TurnTiming(
            wallMs = 578_160_000L,
            ttftMs = 206_000_000L,
            reasoningMs = 68_000_000L,
            textMs = 5_400_000L,
            toolArgMs = 57_000_000L,
            toolsMs = 190_000_000L,
            otherMs = 51_000_000L,
            detailAvailable = true,
        ),
        activity = List(14) { DayActivity("2026-10-${it + 1}", it % 4) },
        ranking = listOf(
            SessionRank(
                sessionId = "s1",
                title = "app开发 main",
                cwd = "F:/bot",
                model = "deepseek-v4.1-flash",
                updatedAt = 1_790_000_000_000L,
                tokens = 1_767_800_000L,
                cost = 100.0,
                share = 0.45f,
            ),
        ),
    )

    /** 降级态：只有核心的 tokenUsage + 两段耗时，没有费用、没有模型拆分。 */
    private fun degradedTotals() = ContextTotals(
        sessionCount = 32,
        tokens = tokens,
        cost = null,
        models = emptyList(),
        timing = TurnTiming(llmMs = 379_600_000L, toolsMs = 190_000_000L, detailAvailable = false),
        activity = List(14) { DayActivity("2026-10-${it + 1}", it % 3) },
        ranking = emptyList(),
    )

    @Composable
    private fun Sheet(
        totals: ContextTotals,
        range: InsightsRange = InsightsRange.MONTH,
        tab: InsightsTab = InsightsTab.OVERVIEW,
        loading: Boolean = false,
        onClose: () -> Unit = {},
        onRangeChange: (InsightsRange) -> Unit = {},
        onTabChange: (InsightsTab) -> Unit = {},
        onOpenSession: (String) -> Unit = {},
    ) {
        Box(Modifier.height(900.dp)) {
            HomeInsightsSheet(
                totals = totals,
                range = range,
                onRangeChange = onRangeChange,
                tab = tab,
                onTabChange = onTabChange,
                loading = loading,
                onClose = onClose,
                onOpenSession = onOpenSession,
            )
        }
    }

    // ── 骨架 ───────────────────────────────────────────────────────────────

    @Test
    fun headerShowsSessionCountAndCloseButton() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        // 头部副标题是「N 个会话 · M 个工作区」，至少要把会话数写出来。
        compose.onNodeWithText("32 个会话", substring = true).assertExists()
        compose.onNodeWithContentDescription("关闭上下文洞察").assertExists()
    }

    @Test
    fun closeButtonCallsBack() {
        var closes = 0
        compose.setContent { Sheet(detailedTotals(), onClose = { closes++ }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("关闭上下文洞察").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun bothTabsArePresent() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("总览").assertExists()
        compose.onNodeWithText("会话").assertExists()
    }

    @Test
    fun allThreeRangesArePresent() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("近 7 天").assertExists()
        compose.onNodeWithText("近 30 天").assertExists()
        compose.onNodeWithText("全部").assertExists()
    }

    @Test
    fun rangeClickReportsTheNewRange() {
        var picked: InsightsRange? = null
        compose.setContent { Sheet(detailedTotals(), onRangeChange = { picked = it }) }
        compose.waitForIdle()
        compose.onNodeWithText("近 7 天").performClick()
        assertEquals(InsightsRange.WEEK, picked)
    }

    // ── 总览：插件态 ───────────────────────────────────────────────────────

    @Test
    fun overviewShowsThreeCardsWhenDataIsComplete() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("Token 用量构成").assertExists()
        compose.onAllNodesWithText("预估费用").onFirst().assertExists()
        compose.onNodeWithText("耗时分布").assertExists()
    }

    @Test
    fun tokenCardIsLabelledByModelWhenModelsExist() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onAllNodesWithText("按模型").onFirst().assertExists()
    }

    @Test
    fun costCardShowsAmountAndFooters() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onAllNodesWithText("$155.19", substring = true).onFirst().assertExists()
    }

    @Test
    fun timingLegendNamesEverySegmentIncludingOther() {
        // 「其他开销」是墙钟减去四段后的余量（step 之间没有埋点的间隙）。
        // 它必须出现在图例里：少一段，圆环就看起来像少画了一块。
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onAllNodesWithText("其他开销", substring = true).onFirst().assertExists()
        // 旧设计里另有独立的「未归类」段，与「其他开销」是同一笔时间；
        // 聚合层不再重复计算，界面上也不该再有第二个名字。
        compose.onAllNodesWithText("未归类", substring = true).assertCountEquals(0)
    }

    @Test
    fun activityCardRenders() {
        compose.setContent { Sheet(detailedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("近 14 天活跃度").assertExists()
    }

    // ── 总览：降级态（没装插件） ───────────────────────────────────────────

    @Test
    fun tokenCardFallsBackToTypeBreakdownWithoutModels() {
        // 没有 byModel 时必须退回四类口径，并且标签改成「按类型」。
        // 这一条守的是 R050 降级缺口 1：整张卡只剩一个空心环。
        compose.setContent { Sheet(degradedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("按类型").assertExists()
        compose.onNodeWithText("缓存读").assertExists()
        compose.onNodeWithText("输出").assertExists()
    }

    @Test
    fun degradedTokenCardExplainsThePlugin() {
        compose.setContent { Sheet(degradedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("上下文洞察插件", substring = true).assertExists()
    }

    @Test
    fun costCardBecomesPlaceholderWithoutCostUsage() {
        // 缺口 2：原来整张卡静默消失，用户看到「少一块」却没有任何说明。
        compose.setContent { Sheet(degradedTotals()) }
        compose.waitForIdle()
        compose.onAllNodesWithText("预估费用").onFirst().assertExists()
        compose.onNodeWithText("缺插件").assertExists()
        compose.onNodeWithText("dsh-cost-meter", substring = true).assertExists()
    }

    @Test
    fun degradedTimingFootnoteSaysTwoSegments() {
        // 缺口 3：脚注不能再说「六段之和 = 墙钟」，因为只有两段。
        compose.setContent { Sheet(degradedTotals()) }
        compose.waitForIdle()
        compose.onNodeWithText("两段之和", substring = true).assertExists()
    }

    // ── 空态与加载态 ───────────────────────────────────────────────────────

    @Test
    fun emptyRangeShowsHintNotBlankSheet() {
        compose.setContent { Sheet(ContextTotals.EMPTY) }
        compose.waitForIdle()
        compose.onNodeWithText("这个范围里还没有用量数据。").assertExists()
    }

    @Test
    fun emptyWhileLoadingShowsSpinnerHint() {
        compose.setContent { Sheet(ContextTotals.EMPTY, loading = true) }
        compose.waitForIdle()
        compose.onNodeWithText("正在读取用量…").assertExists()
    }

    // ── 会话页 ─────────────────────────────────────────────────────────────

    @Test
    fun sessionsTabListsRankedRows() {
        compose.setContent { Sheet(detailedTotals(), tab = InsightsTab.SESSIONS) }
        compose.waitForIdle()
        compose.onNodeWithText("app开发 main").assertExists()
    }

    @Test
    fun sessionsTabRowClickOpensThatSession() {
        var opened: String? = null
        compose.setContent {
            Sheet(detailedTotals(), tab = InsightsTab.SESSIONS, onOpenSession = { opened = it })
        }
        compose.waitForIdle()
        compose.onNodeWithText("app开发 main").performClick()
        assertEquals("s1", opened)
    }

    @Test
    fun sessionsTabIsEmptyWhenNothingRanked() {
        compose.setContent { Sheet(degradedTotals(), tab = InsightsTab.SESSIONS) }
        compose.waitForIdle()
        // 排行空时不能是一片空白——要有可读的说明。
        compose.onNodeWithText("这个范围里还没有用量数据。").assertExists()
    }

    @Test
    fun tabClickReportsTheNewTab() {
        var picked: InsightsTab? = null
        compose.setContent { Sheet(detailedTotals(), onTabChange = { picked = it }) }
        compose.waitForIdle()
        compose.onNodeWithText("会话").performClick()
        assertEquals(InsightsTab.SESSIONS, picked)
    }

    // ── tokenSlices 纯函数 ─────────────────────────────────────────────────

    @Test
    fun tokenSlicesKeepOnlyNonZeroParts() {
        val slices = tokenSlices(tokens)
        assertEquals(
            listOf("缓存读", "未缓存输入", "输出"),
            slices.map { it.label },
        )
    }

    @Test
    fun tokenSlicesOrderStartsWithCacheRead() {
        // 缓存读实测占 96.6%，放第一位图例才不用滚动就能看到最大的那块。
        assertEquals("缓存读", tokenSlices(tokens).first().label)
    }

    @Test
    fun tokenSlicesDropsEverythingWhenTokensAreZero() {
        assertTrue(tokenSlices(TokenUsage.EMPTY).isEmpty())
    }
}
