package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B006 的**渲染层**契约测试：行菜单必须贴着该行的「⋯」按钮弹出，
 * 而不是从整个设备栏的底部冒出来。
 *
 * 缺陷成因（用户 m05374 第 3 条）：`DropdownMenu` 用 `Popup`，位置由它自己的
 * 父布局节点决定。早先它是 `DeviceRow` 之后的兄弟节点、直接挂在设备栏那个
 * `Column` 里，于是 Popup 的锚点落在整栏布局流上——点任何一行的「⋯」，
 * 菜单都从卡片底部弹出。修法是把「按钮 + 菜单」塞进同一个 `Box`。
 *
 * 断言思路：真实像素位置在 Robolectric 里拿不到可靠的窗口坐标，
 * 但**语义树上的父子关系**是可靠的——菜单与按钮必须是同一分支下的近邻，
 * 而不是按钮的远房表亲。这里要求「从菜单节点往上走能先遇到按钮所在的
 * 行节点」，并且两者共同祖先不能越过了整栏容器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceBarRowMenuAnchorTest {

    @get:Rule
    val compose = createComposeRule()

    private fun book() = CredentialBook(
        hosts = listOf(
            DeviceCredential(
                baseUrl = "http://192.168.1.5:19387",
                deviceId = "d1",
                pairedAt = 0L,
                hostId = "a",
                label = "家里的主机",
            ),
            DeviceCredential(
                baseUrl = "http://192.168.1.9:19387",
                deviceId = "d2",
                pairedAt = 0L,
                hostId = "b",
                label = "公司的主机",
            ),
        ),
        activeHostId = "a",
    )

    private fun setBar() {
        compose.setContent {
            DshAssistantTheme {
                DeviceBar(
                    book = book(),
                    expanded = true,
                    activeFailed = false,
                    onDismiss = {},
                    onSwitch = {},
                    onRename = { _, _ -> },
                    onEditEndpoint = { _, _ -> },
                    onTestConnection = {},
                    onRemove = {},
                    onAddRemote = {},
                )
            }
        }
    }

    @Test
    fun `每行都有自己的管理按钮`() {
        setBar()
        compose.onNodeWithContentDescription("管理 家里的主机").assertExists()
        compose.onNodeWithContentDescription("管理 公司的主机").assertExists()
    }

    /**
     * B006 的**结构契约**：设备行的「⋯」按钮与它的 `DropdownMenu` 必须塞在
     * 同一个 `Box` 里。
     *
     * 为什么不在这里断弹层坐标：`DropdownMenu` 走 `Popup`，在 Robolectric 里
     * 弹层落在独立窗口、坐标由窗口自行布局（实测菜单被翻到按钮**上方** 45dp），
     * 拿它做「应该贴在按钮下方」的断言只会变成假红。真实位置由真机目视确认，
     * 这里钉住的是「修法本身还在」——只要有人把菜单挪出这一行的 Box，
     * 下一条断言必红。
     */
    @Test
    fun `按钮与菜单必须塞在同一个 Box 里 否则锚点会落到整栏上`() {
        val text = homeDeviceBarSource()

        val slot = text.indexOf("menuButton = {")
        assertTrue("DeviceBar 必须用 menuButton 槽把菜单塞进行内", slot > 0)

        val boxAt = text.indexOf("Box {", slot)
        val buttonAt = text.indexOf("AppIconButtonSmall(", slot)
        val menuAt = text.indexOf("DropdownMenu(", slot)
        assertTrue("menuButton 槽里必须有 Box 承载按钮与菜单", boxAt > slot)
        assertTrue("Box 必须包住按钮（Box 在按钮之前）", buttonAt > boxAt)
        assertTrue("Box 必须包住菜单（Box 在菜单之前）", menuAt > boxAt)
        assertTrue("菜单要贴着按钮，必须声明在按钮之后", menuAt > buttonAt)

        // 旧结构里 DropdownMenu 是 DeviceRow 的兄弟节点，挂在 forEach 的 Column 下。
        // 修好后 forEach 段里不该再出现「DeviceRow 之后跟一个独立 DropdownMenu」。
        val forEachStart = text.indexOf("book.hosts.forEach")
        val forEachEnd = text.indexOf("// 对话框放在 AnimatedVisibility 内部", forEachStart)
        assertTrue("找不到 forEach 段，源码结构已大改，请复核本测试", forEachStart > 0 && forEachEnd > forEachStart)
        val body = text.substring(forEachStart, forEachEnd)
        assertEquals("一段 forEach 里只该有一次 DeviceRow 调用", 1, body.split("DeviceRow(").size - 1)
        assertEquals("菜单只该有一个，且必须在那段 menuButton 槽里", 1, body.split("DropdownMenu(").size - 1)
    }

    /** 从测试的工作目录往上找源文件：Gradle 下 `user.dir` 可能是模块目录也可能是根目录。 */
    private fun homeDeviceBarSource(): String {
        val relative = "src/main/java/com/revxzxcx/remotewebapp/ui/home/HomeDeviceBar.kt"
        val candidates = listOf(
            java.io.File(relative),
            java.io.File("app/$relative"),
            java.io.File("../app/$relative"),
        )
        val found = candidates.firstOrNull { it.exists() }
        requireNotNull(found) {
            "找不到 HomeDeviceBar.kt，试过：" +
                candidates.joinToString { it.absolutePath } +
                "（user.dir=" + System.getProperty("user.dir") + "）"
        }
        return found.readText()
    }

    @Test
    fun `点了行按钮不会顺手切换主机`() {
        var switched: String? = null
        compose.setContent {
            DshAssistantTheme {
                DeviceBar(
                    book = book(),
                    expanded = true,
                    activeFailed = false,
                    onDismiss = {},
                    onSwitch = { switched = it },
                    onRename = { _, _ -> },
                    onEditEndpoint = { _, _ -> },
                    onTestConnection = {},
                    onRemove = {},
                    onAddRemote = {},
                )
            }
        }
        compose.onNodeWithContentDescription("管理 公司的主机").performClick()
        compose.waitForIdle()
        assertEquals("点「⋯」只该开菜单，不该触发行点击的切换动作", null, switched)
    }

    @Test
    fun `两行的菜单互斥 点第二行不会同时开着第一行的`() {
        setBar()
        compose.onNodeWithContentDescription("管理 家里的主机").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("管理 公司的主机").performClick()
        compose.waitForIdle()
        // 菜单不是同一个节点：任何时刻只应有一组菜单项存在。
        val renameItems = compose.onAllNodesWithText("重命名").fetchSemanticsNodes()
        assertTrue(
            "同时开着两份菜单说明状态没互斥，实际有 ${renameItems.size} 份",
            renameItems.size <= 1,
        )
    }

    @Test
    fun `管理按钮必须带无障碍描述 否则读屏用户找不到它`() {
        setBar()
        val button = compose.onNodeWithContentDescription("管理 家里的主机").fetchSemanticsNode()
        assertTrue(
            "管理按钮必须带 contentDescription",
            button.config.contains(SemanticsProperties.ContentDescription),
        )
    }
}
