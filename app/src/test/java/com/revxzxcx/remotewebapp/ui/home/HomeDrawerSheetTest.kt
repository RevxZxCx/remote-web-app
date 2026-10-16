package com.revxzxcx.remotewebapp.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话抽屉的系统返回键回归测试（R028 ②）。
 *
 * ## 守的是什么
 *
 * 用户报告：**首页点左上角「三条横线」打开最近会话抽屉后，按系统返回会直接退出应用。**
 *
 * 根因（经 `javap` 反编译 material3 1.4.0 字节码 + 设备实测确认）：material3 只在
 * **接收 `DrawerState` 的** `ModalDrawerSheet` 重载里注册预测性返回回调；不带参数的
 * 旧重载给内部 `drawerPredictiveBackState` 传 `aconst_null`，随后在 `DrawerSheet-cm3T3N0`
 * 里被 `ifnull` 短路——注册路径根本没走。于是抽屉打开时平台侧**零回调注册**，
 * 返回落到系统默认行为 = `finish()` Activity（进程存活，用户看到「退出应用」）。
 *
 * 整条链上「返回键是否可用」**只由一个实参决定**：`drawerState` 传没传。而这个错误
 * 用读代码发现不了——`drawerState` 就在同一个函数里、到处都在用；设备上复现也只是
 * 「按返回退出应用」，与「回首页」一样像正常导航。所以这里直接断言**可观察的后果**：
 * 抽屉打开时按返回，Activity 不能被 finish。
 *
 * ## 为什么用 `createAndroidComposeRule<ComponentActivity>`
 *
 * 返回事件是**平台侧**行为，`createComposeRule()` 没有 `OnBackPressedDispatcher`
 * 可打。这里要的就是真实分派路径，不能用手写回调做替身——那样测的又是替身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeDrawerSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 抽屉打开时按系统返回：关闭抽屉，**不能** finish Activity。 */
    @Test
    fun backPressWithOpenDrawerClosesItInsteadOfFinishingTheActivity() {
        var drawer: DrawerState? = null
        compose.setContent {
            val state = rememberDrawerState(DrawerValue.Open)
            drawer = state
            HomeDrawerSheet(drawerState = state) { Text("抽屉内容") }
        }
        compose.waitForIdle()

        val state = requireNotNull(drawer) { "抽屉状态没有被组合出来" }
        assertEquals("前置条件：抽屉必须是打开的", true, state.isOpen)

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse(
            "抽屉打开时按返回把 Activity finish 掉了 —— 这正是用户报告的「直接退出应用」",
            compose.activity.isFinishing,
        )
    }

    /**
     * 反向守护：抽屉**关闭**时不该吞掉返回。
     *
     * 只测「打开时能关」会留下另一种破坏空间——把 `BackHandler(enabled = …)` 写成
     * 恒真（`enabled = true`）也能让上一条通过，但那会让首页永远退不出去。
     * 这里断言抽屉关闭时返回仍然正常 finish Activity。
     */
    @Test
    fun backPressWithClosedDrawerStillFinishesTheActivity() {
        compose.setContent {
            val state = rememberDrawerState(DrawerValue.Closed)
            HomeDrawerSheet(drawerState = state) { Text("抽屉内容") }
        }
        compose.waitForIdle()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(
            "抽屉关闭时返回必须仍然能退出（否则首页永远退不出去）",
            true,
            compose.activity.isFinishing,
        )
    }

    /**
     * 抽屉内容确实渲染在侧板里。
     *
     * 守住上一条可能的「假修复」：把 `HomeDrawerSheet` 改成什么都不渲染、或让
     * `ModalDrawerSheet` 的 content 丢失，也能让两条返回测试通过而界面是空的。
     */
    @Test
    fun sheetRendersItsContent() {
        compose.setContent {
            val state = rememberDrawerState(DrawerValue.Open)
            HomeDrawerSheet(drawerState = state) { Text("抽屉内容") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("抽屉内容").assertExists()
    }
}
