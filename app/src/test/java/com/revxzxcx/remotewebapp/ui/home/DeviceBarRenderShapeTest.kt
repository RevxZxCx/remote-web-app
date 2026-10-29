package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B001 的**语义树层**契约测试：形状常量必须真的接到渲染出来的容器上。
 *
 * 为什么必须有这一层（B001 变异验证 M6 的教训）：
 * [DeviceBarShapeTest] 只断常量，把 `Surface(shape = DeviceBarShape)` 换回
 * `RoundedCornerShape(0.dp)` 时它**照样全绿**——常量对，但没用上。
 * 这与 B003 里 M6/M7 存活是同一个毛病，解法也一样：去看 Compose 自己
 * 写进语义树的 `SemanticsProperties.Shape`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceBarRenderShapeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun book() = CredentialBook(
        hosts = listOf(
            DeviceCredential(
                baseUrl = "http://192.168.1.5:19387",
                deviceId = "d1",
                pairedAt = 0L,
                hostId = "a",
            ),
        ),
        activeHostId = "a",
    )

    private fun setBar() {
        compose.setContent {
            DshAssistantTheme {
                Box {
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
                        modifier = Modifier,
                    )
                }
            }
        }
    }

    @Test
    fun 渲染出来的容器形状必须是只圆下两角() {
        setBar()
        // 设备栏里一定有「我的远程」这个标题，从它往上找带 Shape 的祖先容器。
        val title = compose.onNodeWithText("我的远程").fetchSemanticsNode()
        val shaped = generateSequence(title.parent) { it.parent }
            .firstOrNull { it.config.contains(SemanticsProperties.Shape) }
        val shape = requireNotNull(shaped) {
            "设备栏容器没有把 shape 写进语义树——说明 Surface 的 shape 参数没接上"
        }.config[SemanticsProperties.Shape]

        assertEquals(
            "设备栏容器的形状必须是 RoundedCornerShape(bottomStart=16dp, bottomEnd=16dp)，" +
                "实际是 $shape",
            RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            shape,
        )
    }

    @Test
    fun 渲染出来的容器上两角不能是圆的() {
        // 上两角跟着圆起来会露出顶栏底下的背景（B001 方案 B 的关键理由）。
        setBar()
        val title = compose.onNodeWithText("我的远程").fetchSemanticsNode()
        val shaped = generateSequence(title.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.Shape) }
        val shape = shaped.config[SemanticsProperties.Shape] as RoundedCornerShape

        assertEquals("左上角必须是 0", CornerSize(0.dp), shape.topStart)
        assertEquals("右上角必须是 0", CornerSize(0.dp), shape.topEnd)
    }

    @Test
    fun 收起时不渲染设备栏内容() {
        // 顺带锁住 AnimatedVisibility(visible = expanded) 这个前提：
        // 如果设备栏常驻，上面两条「形状对不对」的断言就失去意义。
        compose.setContent {
            DshAssistantTheme {
                Box {
                    DeviceBar(
                        book = book(),
                        expanded = false,
                        activeFailed = false,
                        onDismiss = {},
                        onSwitch = {},
                        onRename = { _, _ -> },
                        onEditEndpoint = { _, _ -> },
                        onTestConnection = {},
                        onRemove = {},
                        onAddRemote = {},
                        modifier = Modifier,
                    )
                }
            }
        }
        compose.onNodeWithText("我的远程").assertDoesNotExist()
    }
}
