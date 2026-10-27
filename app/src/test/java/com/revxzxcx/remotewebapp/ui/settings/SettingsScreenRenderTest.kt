package com.revxzxcx.remotewebapp.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
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
    fun 移除全部远程把动作交给AppRoot层的确认框() {
        val prefs = prefs()
        var unpairCalls = 0
        compose.setContent {
            SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = { unpairCalls++ })
        }

        // 危险操作在页面最底部：Robolectric 的虚拟屏幕只有几屏高，直接 performClick
        // 落在视口外的节点上什么都不会发生（M5-5 踩过同一个坑），必须先滚过去。
        compose.onNodeWithText("移除全部远程").performScrollTo().performClick()

        // R018 P1-1：确认框已上移到 AppRoot 层，本屏**不再自己弹**。这里断言的是
        // 「本屏点下去就调 onUnpair」——真实 App 里 onUnpair = { pendingUnpair = true }，
        // 对话框随后由 AppRoot 渲染。早前本屏自己弹框、而错误横幅与 ConnectionBanner
        // 的「重新配对」传的是不带确认的同一个 onUnpair，五个入口只有两处有防护。
        //
        // 反过来说：如果哪天有人把确认框搬回本屏，这个测试会立刻失败——
        // 那时 onUnpair 会被本屏的 showUnpairConfirm 拦住，unpairCalls 仍是 0。
        assertEquals(1, unpairCalls)
        // 本屏不该再出现自己的对话框。
        compose.onNodeWithText("退出配对？").assertDoesNotExist()
    }

    @Test
    fun 断开当前远程与移除全部远程是两个不同的出口() {
        // R054 的核心纠正：多主机语境下旧的单一「解除配对」会被读成「清空所有远程」，
        // 而它实际只影响当前这一台。两个动作必须各走各的回调，否则用户以为只删一台
        // 却回到配对页（或者以为清空了却还留着一台）。
        val prefs = prefs()
        val book = CredentialBook(
            hosts = listOf(
                DeviceCredential(baseUrl = "http://192.168.1.5:19387", deviceId = "d1", pairedAt = 0L, hostId = "a"),
                DeviceCredential(baseUrl = "http://10.0.0.2:19387", deviceId = "d2", pairedAt = 0L, hostId = "b"),
            ),
            activeHostId = "a",
        )
        val removed = mutableListOf<String>()
        var unpairCalls = 0
        compose.setContent {
            SettingsScreen(
                themePreferences = prefs,
                onBack = {},
                onUnpair = { unpairCalls++ },
                book = book,
                onRemoveActiveHost = { removed += "active" },
            )
        }

        compose.onNodeWithText("断开当前远程").performScrollTo().performClick()
        assertEquals("只应触发移除当前主机", listOf("active"), removed)
        assertEquals("不该触发移除全部", 0, unpairCalls)

        compose.onNodeWithText("移除全部远程").performScrollTo().performClick()
        assertEquals("现在才轮到移除全部", 1, unpairCalls)
        assertEquals("移除当前主机只应被调一次", listOf("active"), removed)
    }

    @Test
    fun 单台主机时文案说明会回到配对页() {
        // 只剩一台时「断开当前远程」的后果与「移除全部」等价，文案必须提前说清，
        // 否则用户点下去会以为自己还留着一台。
        val prefs = prefs()
        val single = CredentialBook(
            hosts = listOf(
                DeviceCredential(baseUrl = "http://192.168.1.5:19387", deviceId = "d1", pairedAt = 0L, hostId = "a"),
            ),
            activeHostId = "a",
        )
        compose.setContent {
            SettingsScreen(
                themePreferences = prefs,
                onBack = {},
                onUnpair = {},
                book = single,
                onRemoveActiveHost = {},
            )
        }

        compose.onNodeWithText("这是最后一台，会回到配对页").assertExists()
    }

    @Test
    fun 多台主机时文案说明只移除当前那台() {
        val prefs = prefs()
        val book = CredentialBook(
            hosts = listOf(
                DeviceCredential(
                    baseUrl = "http://192.168.1.5:19387",
                    deviceId = "d1",
                    pairedAt = 0L,
                    label = "家里的主机",
                    hostId = "a",
                ),
                DeviceCredential(baseUrl = "http://10.0.0.2:19387", deviceId = "d2", pairedAt = 0L, hostId = "b"),
            ),
            activeHostId = "a",
        )
        compose.setContent {
            SettingsScreen(
                themePreferences = prefs,
                onBack = {},
                onUnpair = {},
                book = book,
                onRemoveActiveHost = {},
            )
        }

        compose.onNodeWithText("只移除「家里的主机」，其余 1 台保留").assertExists()
    }

    @Test
    fun 没接onRemoveActiveHost时断开当前远程不可点() {
        // 防御：调用方忘了传这个回调时，不能让一个「点了没反应」的破坏性按钮看起来可用。
        val prefs = prefs()
        val book = CredentialBook(
            hosts = listOf(
                DeviceCredential(baseUrl = "http://192.168.1.5:19387", deviceId = "d1", pairedAt = 0L, hostId = "a"),
            ),
            activeHostId = "a",
        )
        var unpairCalls = 0
        compose.setContent {
            SettingsScreen(themePreferences = prefs, onBack = {}, onUnpair = { unpairCalls++ }, book = book)
        }

        compose.onNodeWithText("断开当前远程").performScrollTo().performClick()
        assertEquals("没有出口就不该有任何动作", 0, unpairCalls)
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
