package com.revxzxcx.remotewebapp.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上次是被系统杀掉的」提示卡的接线（R056 / Q3）。
 *
 * ## 为什么用源码结构断言而不是渲染断言
 * 这张卡的可见性来自 `DeathWatcher.pending`，而那个值取决于**系统给的退出历史**——
 * 单测里 `ApplicationExitInfo` 是空桩，永远读不到数据，所以「渲染出来长什么样」这一层
 * 在 JVM 上测不到（真机验过）。但**接线有没有接**完全测得到，而接线的漏法恰恰是最坏的一种：
 * 卡片的组件写好了、测试全绿，可首页根本没调用它，或者点「去开保活」什么也不做。
 * 这种「代码在、功能不在」的缺陷，只有结构断言能守。
 *
 * 真机验收已确认正面路径（卡片出现、按钮跳二级页）；这里守的是不回归。
 */
class DeathNoticeWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        )
        for (candidate in candidates) {
            if (candidate.isFile) return candidate.readText()
        }
        error("找不到 $relative，试过：${candidates.joinToString { it.absolutePath }}")
    }

    private fun homeScreen() = source(
        "src/main/java/com/revxzxcx/remotewebapp/ui/home/HomeScreen.kt",
    )

    private fun appRoot() = source(
        "src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt",
    )

    @Test
    fun `首页必须真的渲染这张卡 组件写好却不调用等于没做`() {
        val text = homeScreen()
        assertTrue(
            "HomeScreen 必须调用 DeathNoticeCard，否则提示永远不会出现",
            text.contains("DeathNoticeCard("),
        )
        // 必须在条件里渲染：无条件渲染就等于永远显示一张卡。
        assertTrue(
            "DeathNoticeCard 必须包在 deathNotice 的非空判断里",
            text.contains("deathNotice?.let") || text.contains("deathNotice != null"),
        )
    }

    @Test
    fun `卡片必须排在连接错误条之前 上次的解释比现在的状态更先要看`() {
        val text = homeScreen()
        val cardAt = text.indexOf("DeathNoticeCard(")
        val errorAt = text.indexOf("state.error?.let { error ->")
        assertTrue("两处都应该存在", cardAt >= 0 && errorAt >= 0)
        assertTrue(
            "提示卡应在连接错误横幅之前渲染",
            cardAt < errorAt,
        )
    }

    @Test
    fun `AppRoot 必须把两条回调都接上 点去开保活要真的跳二级页`() {
        val text = appRoot()
        assertTrue(
            "必须传 deathNotice",
            text.contains("deathNotice = deathNotice"),
        )
        // 「去开保活」的落点：直接进通知二级页（保活四项、权限、两类开关都在那页）。
        val action = text.substringAfter("onDeathNoticeAction = {")
            .substringBefore("},")
        assertTrue(
            "点「去开保活」必须跳到通知二级页，实际是：$action",
            action.contains("openNoticeSettings = true"),
        )
        assertTrue(
            "点过之后要 consume，否则卡片不会消失",
            action.contains("consume()"),
        )
    }

    @Test
    fun `先不管也要 consume 否则卡片赖着不走`() {
        val text = appRoot()
        val dismiss = text.substringAfter("onDeathNoticeDismiss = {").substringBefore("},")
        assertTrue(
            "「先不管」必须 consume，实际是：$dismiss",
            dismiss.contains("consume()"),
        )
    }

    @Test
    fun `提示状态必须从容器取 不能用界面本地状态凭空造一条`() {
        val text = appRoot()
        assertTrue(
            "deathNotice 必须来自 container.deathWatcher.pending",
            text.contains("container.deathWatcher.pending.collectAsState()"),
        )
    }

    @Test
    fun `进后台必须写时刻 否则永远判不出是被杀还是崩溃`() {
        val text = source("src/main/java/com/revxzxcx/remotewebapp/DshApplication.kt")
        val onStop = text.substringAfter("override fun onStop(owner: LifecycleOwner) {")
            .substringBefore("},")
        assertTrue(
            "onStop 里必须记下进后台时刻，实际是：$onStop",
            onStop.contains("watcher.onBackgrounded()"),
        )
        // 记在 onPause 会把「弹权限框」也当成进后台，于是一次崩溃会被误判成系统杀的。
        assertTrue(
            "不能挂到 onPause 上",
            !text.contains("override fun onPause"),
        )
    }
}
