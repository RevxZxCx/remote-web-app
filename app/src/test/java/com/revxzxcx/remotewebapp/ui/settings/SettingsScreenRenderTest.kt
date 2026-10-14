package com.revxzxcx.remotewebapp.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode
import com.revxzxcx.remotewebapp.core.prefs.ThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页的渲染测试（M5-9）。
 *
 * 用 Robolectric 而不是纯 JVM：这个页面的价值全在「点了之后主题偏好真的被写下去」，
 * 而那条路径必须穿过 `SharedPreferences`——纯 JVM 测试只能验证 Compose 树，
 * 验证不了落盘。这与 M5-5 引入渲染测试的理由相同。
 *
 * `ThemePreferences` 直接拿真实 Context 构造（Robolectric 有完整的 SharedPreferences
 * 实现），所以 [setMode] 之后读回来的是同一份数据，等价于真机行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun prefs(): ThemePreferences =
        ThemePreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun 三档主题都画出来且默认选中跟随系统() {
        val prefs = prefs()
        compose.setContent { SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = {}) }

        compose.onNodeWithText("跟随系统").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("浅色").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("深色").assertIsDisplayed().assertIsNotSelected()
        assertEquals(ThemeMode.SYSTEM, prefs.mode.value)
    }

    @Test
    fun 点深色之后偏好落盘且选中态跟着走() {
        val prefs = prefs()
        compose.setContent { SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = {}) }

        compose.onNodeWithText("深色").performClick()

        // 落盘 + StateFlow 双通道都要变（页面读的是 StateFlow，别处读盘）。
        assertEquals(ThemeMode.DARK, prefs.mode.value)
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("跟随系统").assertIsNotSelected()
    }

    @Test
    fun 解除配对按钮先问一次再触发回调() {
        val prefs = prefs()
        var unpairCalls = 0
        compose.setContent {
            SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = { unpairCalls++ })
        }

        // 解除配对在页面最底部：Robolectric 的虚拟屏幕只有几屏高，直接 performClick
        // 落在视口外的节点上什么都不会发生（M5-5 踩过同一个坑），必须先滚过去。
        compose.onNodeWithText("解除配对").performScrollTo().performClick()

        // R007 P2-5：这个动作必须二次确认。早先设置页直接执行，与工作区页顶栏的
        // 「退出」保护强度不一致——M5-1 的确认框被整条绕过。
        compose.onNodeWithText("退出配对？").assertExists()
        assertEquals("还没确认，不该解除配对", 0, unpairCalls)

        // 对话框是独立窗口，节点在语义树的另一棵树上，直接按文本找即可。
        compose.onNodeWithText("退出配对").performClick()
        assertEquals(1, unpairCalls)
    }

    @Test
    fun 解除配对对话框可以取消() {
        val prefs = prefs()
        var unpairCalls = 0
        compose.setContent {
            SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = { unpairCalls++ })
        }

        compose.onNodeWithText("解除配对").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()

        assertEquals(0, unpairCalls)
        compose.onNodeWithText("退出配对？").assertDoesNotExist()
    }

    @Test
    fun 能力清单是只读的不做成开关() {
        val prefs = prefs()
        compose.setContent { SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = {}) }

        // 原型的这些开关在 App 里没有「关掉」的实现路径，做成开关只会把用户
        // 送进一个从未测试过的组合。这里断言它们只以说明文字出现。
        // 用 assertExists 而不是 assertIsDisplayed：它们在视口外（同上）。
        compose.onNodeWithText("能力").assertExists()
        compose.onNodeWithText("Markdown 渲染").assertExists()
        compose.onNodeWithText("模型切换").assertExists()
    }
}
