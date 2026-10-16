package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R028 ①（用户 m41448）：首页那条**整宽连接横幅**收进顶栏，变成一个 40dp 指示器。
 *
 * 用户原话：「将首页的已连接横幅变成胶囊型按钮收束进顶部栏，用户没有点击的时候收成小圆点，
 * 圆点有光效，具有高级感，保留颜色显示设定。当用户点击小圆点，圆点展开成胶囊型按钮，
 * 里面显示圆点和文字…用户再次点击胶囊，胶囊会向下展示一个窗口，展示连接的远程情况」。
 *
 * 三级信息密度是这次改动的全部内容，所以三条契约各有测试：
 * ① **收起态不许有文字**——横幅能省下一整行，正因为不打扰时一个字都不显示；
 * ② 点一次到胶囊，胶囊里必须有状态文字；
 * ③ 点第二次到详情面板，面板里六行数据与底部动作都要在。
 *
 * 另有一条来自 R005/R006 的老教训：`Unpaired` 是**终态**，「重试」对它毫无意义，
 * 面板必须换成「重新配对」（仍走 AppRoot 那一次二次确认，不在面板里另开路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionIndicatorRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val details = ConnectionDetails(
        baseUrl = "http://127.0.0.1:19387",
        maskedDeviceId = "da738b…5c09",
        channelReady = true,
        framesIn = 1284L,
        framesOut = 96L,
        lastFailure = null,
    )

    private fun setIndicator(
        carrier: CarrierState = CarrierState.Connected(sinceMillis = 1_000L),
        details: ConnectionDetails = this.details,
        onRetry: (() -> Unit)? = {},
        onRepairRequest: (() -> Unit)? = {},
        onOpenDebug: (() -> Unit)? = {},
    ) {
        compose.setContent {
            ConnectionIndicator(
                carrier = carrier,
                details = details,
                onRetry = onRetry,
                onRepairRequest = onRepairRequest,
                onOpenDebug = onOpenDebug,
            )
        }
    }

    // ---- 状态 A：收起态 ----

    @Test
    fun `收起态只是一个圆点没有任何文字`() {
        setIndicator()
        // `substring = true`：圆点的 contentDescription 带状态（读屏用），
        // 但**屏幕上**一个字都不显示——横幅整行的高度就是这样省下来的。
        compose.onNodeWithContentDescription("连接状态", substring = true).assertExists()
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithText("连接中…").assertDoesNotExist()
        compose.onNodeWithText("未连接").assertDoesNotExist()
    }

    /**
     * 收起态的可访问名必须**含状态**（R028 审查 P3-9）。
     *
     * 缺陷原形：contentDescription 恒为「连接状态」，读屏用户不点开胶囊就永远不知道到底连上没有。
     */
    @Test
    fun `收起态的可访问名带上了当前状态`() {
        setIndicator()
        compose.onNodeWithContentDescription("连接状态：已连接", substring = true).assertExists()
    }

    /** 收起态的圆点仍然要能点（40dp 触控区），否则用户没法展开看状态。 */
    @Test
    fun `点圆点展开成胶囊`() {
        setIndicator()
        compose.onNodeWithContentDescription("连接状态", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已连接").assertExists()
    }

    // ---- 状态 B：胶囊 ----

    @Test
    fun `胶囊里是当前状态文字`() {
        setIndicator(carrier = CarrierState.Reconnecting(attempt = 3, retryAtMillis = 5_000L, reason = "断流"))
        compose.onNodeWithContentDescription("连接状态", substring = true).performClick()
        compose.waitForIdle()
        // 重连态必须带秒数：只有「重连中…」的话用户不知道等还是点（与 pillText 的 KDoc 一致）。
        //
        // 这里只断言前缀，不断言具体秒数：`retryAtMillis` 是**服务端算出来的绝对时刻**，
        // 而指示器读的是真实系统时钟，两者在这个纯渲染测试里对不上（真实场景里对得上）。
        // 硬写 "重连中… 5s" 会变成一条永远失败的断言，且失败原因与契约无关。
        compose.onNodeWithText("重连中…", substring = true).assertExists()
    }

    // ---- 状态 C：详情面板 ----

    /**
     * 点两次（圆点 → 胶囊 → 面板）。
     *
     * **每次点击后都要重新查询节点**，而且必须用 `substring = true`：
     * ① 第一次点击把圆点换成了胶囊，那个语义节点已经不在树上，继续用旧的
     * `SemanticsNodeInteraction` 注入触摸会报 `The node is no longer in the tree`；
     * ② 圆点的 contentDescription 是 `连接状态`，胶囊的是 `连接状态：已连接`
     * （`ConnectionIndicator.kt:359`）——精确匹配在第二下必然找不到节点。
     */
    private fun openPanel() {
        compose.onNodeWithContentDescription("连接状态", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("连接状态", substring = true).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `再点一次胶囊展开详情面板且六行齐全`() {
        setIndicator()
        openPanel()

        compose.onNodeWithText("地址").assertExists()
        compose.onNodeWithText("http://127.0.0.1:19387").assertExists()
        compose.onNodeWithText("设备").assertExists()
        compose.onNodeWithText("da738b…5c09").assertExists()
        compose.onNodeWithText("通道").assertExists()
        compose.onNodeWithText("已就绪").assertExists()
        compose.onNodeWithText("收到帧").assertExists()
        compose.onNodeWithText("1 284").assertExists()
        compose.onNodeWithText("发出帧").assertExists()
        compose.onNodeWithText("最近故障").assertExists()
        compose.onNodeWithText("无").assertExists()
    }

    @Test
    fun `面板底部给出重试与诊断`() {
        setIndicator()
        openPanel()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("诊断").assertIsDisplayed()
    }

    /**
     * `Unpaired` 是终态：面板里不能出现「重试」（重试一个失效凭证只会再失败一次），
     * 必须换成「重新配对」。
     */
    @Test
    fun `配对失效时面板换成重新配对且不给重试`() {
        setIndicator(carrier = CarrierState.Unpaired("凭证已失效"))
        openPanel()
        compose.onNodeWithText("重新配对").assertExists()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test
    fun `点重新配对走的是统一入口而不是自己开一条路径`() {
        var repairs = 0
        setIndicator(carrier = CarrierState.Unpaired("凭证已失效"), onRepairRequest = { repairs++ })
        openPanel()
        compose.onNodeWithText("重新配对").performClick()
        compose.waitForIdle()
        assertEquals(1, repairs)
    }

    @Test
    fun `最近故障非空时原样显示`() {
        setIndicator(details = details.copy(lastFailure = "EOFException"))
        openPanel()
        compose.onNodeWithText("EOFException").assertExists()
        compose.onNodeWithText("无").assertDoesNotExist()
    }

    /**
     * 面板头部的「已连接多久」不许恒为「0 秒」（R028 审查 P2-2）。
     *
     * 缺陷原形：`nowMillis` 只在 `Reconnecting` 时取真秒表，其余状态是 `0L`，而
     * `PanelHeader` 恰好在 `Connected` 时调 `formatSince(sinceMillis, 0L)` →
     * `(0 - since).coerceAtLeast(0) = 0` → **永远「0 秒」**，面板里唯一的时长信息完全失效。
     *
     * 这是**有分辨力**的断言：把 `needsClock` 改回只认 `Reconnecting`，这条立刻变红。
     * 注意不能用 `formatSince` 的纯函数测试替代——那条传 `0L` 也能通过（契约是调用方必须给真钟）。
     */
    @Test
    fun `面板头部显示真实连接时长而不是恒为零秒`() {
        setIndicator()
        openPanel()
        compose.onNodeWithText("0 秒").assertDoesNotExist()
        // 真实时长走「N 小时 M 分 / M 分 S 秒 / S 秒」三种形态之一，这里只要求非零。
        compose.onNodeWithText("分", substring = true).assertExists()
    }

    // ---- 纯字符串函数 ----
    @Test
    fun `四位以上才做千位分隔`() {
        assertEquals("96", formatCount(96L))
        assertEquals("999", formatCount(999L))
        assertEquals("1 000", formatCount(1_000L))
        assertEquals("1 284", formatCount(1_284L))
        assertEquals("12 345", formatCount(12_345L))
    }

    /**
     * `formatSince` 必须与 `formatWallClock` 一样走 `java.time`。
     *
     * 本工程曾用 `(millis / 1000) % 86400` 算时刻，在东八区整整早 8 小时（R006 P2-2）。
     * 这里算的是时长不是时刻，但同样只许用减法——**不许掺入时区**。
     */
    @Test
    fun `连接时长按小时分秒递进`() {
        assertEquals("12 秒", formatSince(0L, 12_000L))
        assertEquals("3 分 12 秒", formatSince(0L, 192_000L))
        assertEquals("1 小时 2 分", formatSince(0L, 3_720_000L))
        // 时钟回拨（now < since）不许产出负数。
        assertEquals("0 秒", formatSince(9_000L, 1_000L))
    }

    /** 配色三档：绿＝已连接、琥珀＝连接中/重连中、红＝配对失效（用户「保留颜色显示设定」）。 */
    @Test
    fun `三档配色与状态一一对应`() {
        // `indicatorToneOf` 是 `@Composable`（要读 MaterialTheme 与 LocalStatusRunning），
        // 只能在组合里求值。三条一起抓：`setContent` 每个 rule 只能调一次。
        val tones = mutableMapOf<String, IndicatorTone>()
        compose.setContent {
            tones["connected"] = indicatorToneOf(CarrierState.Connected(1L))
            tones["reconnecting"] = indicatorToneOf(CarrierState.Reconnecting(1, 2L, "x"))
            tones["unpaired"] = indicatorToneOf(CarrierState.Unpaired("x"))
        }
        compose.waitForIdle()
        val dots = listOf("connected", "reconnecting", "unpaired").map { requireNotNull(tones[it]).dot }
        // 三个状态必须给出互不相同的圆点色——同色就等于「保留颜色显示设定」没做到。
        assertEquals(
            "三档配色撞色了：$dots（已连接必须是运行绿、重连是琥珀、失效是 error）",
            3,
            dots.toSet().size,
        )
    }
}
