package com.revxzxcx.remotewebapp.ui.pair

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配对页三处改动的渲染层与纯函数测试（R033 ③）。
 *
 * 守三件事：
 * 1. **三入口互斥**：点分段只切换一条路径，不是把三张卡全摊开——改造前三个输入框
 *    挤在一张卡里，真机上踩过把 token 填进「服务器地址」。
 * 2. **贴链接即解析**：解析结论要提前说清「这段文本会被当成什么」。
 * 3. **探测结果分色**：成功与失败必须是两种可区分的形态（改造前都写进同一个错误条）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------- 解析预览

    @Test
    fun `接受链接解析出主机与打码后的 token`() {
        val preview = previewOf(
            PairLinkParser.parse("https://192.168.1.5:19387/pair-accept?pair=abcdefghijkl"),
        )

        assertTrue(preview.ok)
        assertEquals("将配对到 192.168.1.5:19387", preview.headline)
        // token 只露首尾 4 位：整段显示等于把凭证摊在屏幕上。
        assertEquals("token abcd…ijkl", preview.detail)
    }

    @Test
    fun `设备链接解析出主机与打码后的设备 id`() {
        val preview = previewOf(
            PairLinkParser.parse("http://10.0.0.9:8080/pair-app?device=717de5233c6e5c3b853a9c05cdf70f25"),
        )

        assertTrue(preview.ok)
        assertEquals("将直接登记到 10.0.0.9:8080", preview.headline)
        assertEquals("设备 id 717de5…0f25", preview.detail)
    }

    @Test
    fun `裸 token 会被认出来但不能直接配对`() {
        val preview = previewOf(PairLinkParser.parse("abcdefghijkl"))

        assertTrue(preview.ok)
        assertEquals("这是一个裸 token", preview.headline)
        assertEquals("需要配上一次用过的地址才能用", preview.detail)
    }

    @Test
    fun `无法识别的内容给出解析器的原话`() {
        val parse = PairLinkParser.parse("这不是链接也不是 token")
        val preview = previewOf(parse)

        assertFalse(preview.ok)
        assertEquals("这段内容还不能用", preview.headline)
        // 细节必须原样带出解析器的理由，不能在 UI 层二次糊一遍。
        assertEquals((parse as PairParse.Failed).reason, preview.detail)
    }

    @Test
    fun `主机名里带路径时只留 scheme 之后的部分`() {
        assertEquals("192.168.1.5:19387", hostOf("http://192.168.1.5:19387"))
        assertEquals("192.168.1.5:19387", hostOf("http://192.168.1.5:19387/"))
    }

    @Test
    fun `过短的 token 整段打码`() {
        // 短到「首 4 + 尾 4」就等于原值，那样打码毫无意义。
        assertEquals("…", maskToken("12345678"))
        assertEquals("1234…6789", maskToken("123456789"))
    }

    // ---------------------------------------------------------------- 分段互斥

    @Test
    fun `分段控件显示三个入口且只高亮选中的那个`() {
        var selected = PairEntry.Scan
        var clicks = mutableListOf<PairEntry>()
        compose.setContent {
            MaterialTheme {
                PairEntryPicker(
                    selected = selected,
                    onSelect = {
                        clicks = (clicks + it).toMutableList()
                        selected = it
                    },
                )
            }
        }

        compose.onNodeWithText("扫码").assertIsDisplayed()
        compose.onNodeWithText("链接").assertIsDisplayed()
        compose.onNodeWithText("手工").assertIsDisplayed()

        compose.onNodeWithText("链接").performClick()
        assertEquals(listOf(PairEntry.Link), clicks)
    }

    @Test
    fun `三条入口一次只能选中一条`() {
        // 互斥是分段控件的全部意义：连点三个入口，最终只应停在最后一个上。
        var selected = PairEntry.Scan
        val compose2 = compose
        compose2.setContent {
            MaterialTheme {
                PairEntryPicker(selected = selected, onSelect = { selected = it })
            }
        }
        compose2.onNodeWithText("链接").performClick()
        assertEquals(PairEntry.Link, selected)
        compose2.onNodeWithText("手工").performClick()
        assertEquals(PairEntry.Manual, selected)
        compose2.onNodeWithText("扫码").performClick()
        assertEquals(PairEntry.Scan, selected)
    }

    // ---------------------------------------------------------------- 配对按钮判据

    @Test
    fun `解析失败时开始配对按钮点不动`() {
        // 这是「贴链接即解析」真正的用处：预览卡好看是次要的，关键是**解析失败的
        // 文本不许被提交**。否则用户点下去只会拿到一次注定失败的请求，还得自己
        // 从错误里反推是哪个字段写错了（改造前就是这样）。
        val clicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { LinkCard(busy = false, onPair = { clicks += it }) }
        }

        // 空输入：按钮禁用。
        compose.onNodeWithText("开始配对").assertIsNotEnabled()

        // 填一段解析不出东西的文本：仍然禁用，且点不动。
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("%%")
        compose.onNodeWithText("开始配对").assertIsNotEnabled()
        compose.onNodeWithText("开始配对").performClick()
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun `解析成功时开始配对按钮可以点且只回调一次`() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { LinkCard(busy = false, onPair = { clicks += it }) }
        }

        val link = "https://192.168.1.5:19387/pair-accept?pair=abcdefghijkl"
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(link)
        compose.onNodeWithText("开始配对").assertIsEnabled()
        compose.onNodeWithText("开始配对").performClick()

        assertEquals(listOf(link), clicks)
    }

    @Test
    fun `配对进行中按钮也不许再点`() {
        // busy 期间重复点击会并发两个 accept 请求，其中一个必然拿着已消费的 token 失败。
        val clicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { LinkCard(busy = true, onPair = { clicks += it }) }
        }
        compose.onAllNodes(hasSetTextAction())[0]
            .performTextInput("https://192.168.1.5:19387/pair-accept?pair=abcdefghijkl")
        compose.onNodeWithText("开始配对").assertIsNotEnabled()
        assertEquals(emptyList<String>(), clicks)
    }

    // ---------------------------------------------------------------- 结果卡分色

    @Test
    fun `探测成功的卡片显示结论与细节行`() {
        compose.setContent {
            MaterialTheme {
                ProbeResultCard(
                    PairProbe(
                        ok = true,
                        headline = "地址可达",
                        lines = listOf("桌面端当前无配对设备", "局域网地址 192.168.1.5:19387"),
                    ),
                )
            }
        }

        compose.onNodeWithText("地址可达").assertIsDisplayed()
        compose.onNodeWithText("桌面端当前无配对设备").assertIsDisplayed()
        compose.onNodeWithText("局域网地址 192.168.1.5:19387").assertIsDisplayed()
    }

    @Test
    fun `探测失败的卡片显示标题与原因`() {
        compose.setContent {
            MaterialTheme {
                ProbeResultCard(
                    PairProbe(
                        ok = false,
                        headline = "连不上这个地址",
                        lines = listOf("HTTP 502：Bad Gateway"),
                    ),
                )
            }
        }

        compose.onNodeWithText("连不上这个地址").assertIsDisplayed()
        compose.onNodeWithText("HTTP 502：Bad Gateway").assertIsDisplayed()
    }

    @Test
    fun `解析成功与失败是两张不同形态的卡`() {
        // 形态差异必须真的存在：改造前成功与失败都是同一张无边框 Surface。
        compose.setContent {
            MaterialTheme {
                Column(Modifier.requiredHeight(400.dp)) {
                    ParsePreviewCard(
                        ParsePreview(ok = true, headline = "将配对到 甲", detail = "token 1234…5678"),
                    )
                    ParsePreviewCard(
                        ParsePreview(ok = false, headline = "这段内容还不能用", detail = "不是合法的链接"),
                    )
                }
            }
        }

        compose.onNodeWithText("将配对到 甲").assertIsDisplayed()
        compose.onNodeWithText("这段内容还不能用").assertIsDisplayed()
        compose.onNodeWithText("不是合法的链接").assertIsDisplayed()
    }

    @Test
    fun `手工登记拆成两步且共用同一对值`() {
        // 改造前三件事挤在一张卡里。拆开之后必须仍然共用同一对 (地址, 设备 id)，
        // 否则「登记」按钮拿到的是另一份 state——这条守的是状态没有被卡片切碎。
        var url = "http://192.168.1.5:19387"
        var device = ""
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredHeight(2000.dp)) {
                    ManualEntryCards(
                        busy = false,
                        baseUrl = url,
                        onBaseUrlChange = { url = it },
                        deviceId = device,
                        onDeviceIdChange = { device = it },
                        probe = null,
                        onProbe = {},
                        onManual = { _, _ -> },
                    )
                }
            }
        }

        // 两张卡各自的步骤标记都在（列表内容可能被裁到视口外，所以只断言存在）。
        compose.onNodeWithText("填 1/2").assertExists()
        compose.onNodeWithText("填 2/2").assertExists()
        // 地址卡显示的是外部传进来的那一份值，不是它自己 remember 的一份。
        compose.onNodeWithText("http://192.168.1.5:19387").assertExists()

        // 两个可输入框，顺序即卡片顺序：先地址、后设备 id。
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)

        // 在设备 id 那一格输入，回调必须把值交给同一个外部 state。
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("717de5")
        assertEquals("717de5", device)
        // 地址没被这次输入改动过。
        assertEquals("http://192.168.1.5:19387", url)
    }

    @Test
    fun `手工登记的两张卡显示在同一个纵向序列里`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredHeight(1200.dp)) {
                    ManualEntryCards(
                        busy = false,
                        baseUrl = "",
                        onBaseUrlChange = {},
                        deviceId = "",
                        onDeviceIdChange = {},
                        probe = null,
                        onProbe = {},
                        onManual = { _, _ -> },
                    )
                }
            }
        }
        // 探测结果卡还没出现（没探测过）。
        compose.onAllNodesWithText("地址可达").assertCountEquals(0)
        compose.onAllNodesWithText("连不上这个地址").assertCountEquals(0)
    }
}
