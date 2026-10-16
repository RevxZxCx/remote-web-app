package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R028 ③（用户 m42277）：答题面板的高度必须**随可用高度与题目数量自适应**。
 *
 * 这一条是从一个真实痛点来的，用户原话：「当前的提问回答界面虽然占据了输入面板，但是
 * 展开的高度太小了，如果选项比较多（超过两个），基本上只能通过滚轮看下面的选项了」。
 *
 * 旧实现把正文高度**写死**成 `160.dp`，而 160 这个数的来历与用户毫无关系——它是为了让
 * 卡片塞进 Robolectric 只有 386px 高的测试窗口（见 `InteractiveWindowGeometryTest` 的
 * 缘由）。**一个测试窗口的尺寸决定了真机上用户能看见几个选项**，这是典型的「用邻近量
 * 代替真正要守护的量」（D66 同一形状）。
 *
 * 现在上限由 `interactiveBodyMaxHeight(availableHeight, hasPinned)` 在组合期从
 * `BoxWithConstraints` 的 `maxHeight` 算出。这个函数是纯函数，所以能被 JVM 单测钉住——
 * 下面四条契约任何一条被改坏，真机上都会退化成「滚轮才能看选项」或者「按钮被挤出屏幕」。
 */
class InteractiveBodyMaxHeightTest {

    /**
     * 契约 1：**短内容不被强行拉高**。
     *
     * 返回值是 `heightIn(max = …)` 的**上限**，所以这里返回的是「最多能给多少」。
     * 只要它不小于内容自然高度，短问题就会按内容自然展开、下方不留空白。
     * 这条测试真正要防的是「上限被算得比正文最小高度还小」——那会让所有问题都挤成一条。
     */
    @Test
    fun `上限永远不会低于正文最小高度`() {
        // 一个连标题行都放不下的极端可用高度（例如键盘弹出 + 小屏）。
        val tiny = interactiveBodyMaxHeight(availableHeight = 40.dp, hasPinned = true)
        assertEquals(INTERACTIVE_BODY_MIN_HEIGHT, tiny)
    }

    /**
     * 契约 2：**常规手机竖屏下，4~6 个选项要能全部看见**。
     *
     * 用户 m42277 的痛点是「超过两个就只剩滚轮」。MuMu 竖屏 1080×2560 / density 420
     * 约合 411×975dp，可用高度（扣掉状态栏与输入框保留区）约 700dp。这条断言要求
     * 上限至少能装下 5 个选项（每项约 52dp）+ 题号 + 题目 + 详情 ≈ 340dp。
     * 旧实现写死 160dp 时这条必然失败——那正是要修的缺陷。
     */
    @Test
    fun `常规竖屏下能放下五个选项`() {
        val max = interactiveBodyMaxHeight(availableHeight = 700.dp, hasPinned = true)
        assertTrue(
            "上限 $max 装不下五个选项 —— 用户又要靠滚轮看选项了（旧实现写死 160dp）",
            max.value >= 340f,
        )
    }

    /**
     * 契约 3：**上限有天花板，内容再多也不许把消息列表挤没**。
     *
     * 面板住在 Scaffold 的 `bottomBar` 里：如果它比窗口还高，动作区（允许/拒绝、提交/跳过）
     * 会被推到窗口下方——而 `performClick` 往窗口外注入触摸**不抛异常也不触发回调**，
     * 这条静默失败在 R023 已经让「允许」按钮整轮点不到。所以宁可滚动，也不能无限长高。
     */
    @Test
    fun `无限高的可用空间也不会超过上限`() {
        val huge = interactiveBodyMaxHeight(availableHeight = 4_000.dp, hasPinned = false)
        assertEquals(INTERACTIVE_BODY_MAX_HEIGHT, huge)
        assertTrue(INTERACTIVE_BODY_MAX_HEIGHT <= 380.dp)
    }

    /**
     * 契约 4：**有钉住区（提问卡的「其他答案」输入框）时上限更小**。
     *
     * 提问卡的钉住区比审批卡多一个 `OutlinedTextField`（约 56dp）。同一个可用高度下，
     * 留给正文的额度必须相应减少，否则「正文 + 钉住区 + 动作区」的总和会超窗口，
     * 又是动作区被挤出去。审批卡没有钉住区，所以它拿到的额度更大。
     */
    @Test
    fun `同一可用高度下提问卡比审批卡少一截`() {
        // 可用高度必须挑在两档都**没有撞到上下限**的区间里：700dp 时两者都会被
        // `coerceIn` 顶到 380dp 上限，差值恒为 0——那样这条用例就在测天花板而不是测配额。
        val approval = interactiveBodyMaxHeight(availableHeight = 440.dp, hasPinned = false)
        val question = interactiveBodyMaxHeight(availableHeight = 440.dp, hasPinned = true)
        assertTrue(
            "提问卡上限 $question 不小于审批卡上限 $approval，钉住区会把动作区挤出窗口",
            question.value < approval.value,
        )
        assertEquals(56f, approval.value - question.value, 0.01f)
    }

    /**
     * 契约 5：**边界处的单调性**。
     *
     * 可用高度增加时上限不许倒退（`coerceIn` 的两个边界都正确夹住）。这条防的是
     * 「把 `coerceIn` 的参数顺序写反」这类改动——那种错误在极端值是看得出来的，
     * 在中间值是静默的。
     */
    @Test
    fun `上限随可用高度单调不减`() {
        val heights = listOf(80.dp, 120.dp, 200.dp, 320.dp, 500.dp, 900.dp)
        var previous = Dp.Unspecified
        for (h in heights) {
            val current = interactiveBodyMaxHeight(h, hasPinned = false)
            if (previous != Dp.Unspecified) {
                assertTrue("可用高度 $h 时上限 $current 比上一档 $previous 还小", current >= previous)
            }
            assertTrue("上限 $current 越过了下限", current >= INTERACTIVE_BODY_MIN_HEIGHT)
            assertTrue("上限 $current 越过了上限", current <= INTERACTIVE_BODY_MAX_HEIGHT)
            previous = current
        }
    }
}
