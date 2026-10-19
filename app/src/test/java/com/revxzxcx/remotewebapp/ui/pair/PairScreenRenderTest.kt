package com.revxzxcx.remotewebapp.ui.pair

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
 * 3. **探测结果分色**：成功与失败必须是两种可区分的形态（改造前都写进同一个错误条），
 *    并且结论要回显它对应的是哪个地址（赶不上`clearProbe()` 的在途请求会造成错配）。
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
            PairLinkParser.parse(
                "https://192.168.1.5:19387/pair-accept?pair=abcdefghijklmnopqrstuvwxyz012345",
            ),
        )

        assertTrue(preview.ok)
        // 成功态压成一行：host 与 token 必须同时在一行里，用户扫一眼就能核对
        // 「贴的是不是那台机器、是不是那个 token」。
        assertEquals("已识别", preview.headline)
        // token 只露首尾 4 位：整段显示等于把凭证摊在屏幕上。
        assertEquals("host 192.168.1.5:19387 · token abcd…2345", preview.detail)
    }

    @Test
    fun `设备链接解析出主机与打码后的设备 id`() {
        val preview = previewOf(
            PairLinkParser.parse("http://10.0.0.9:8080/pair-app?device=717de5233c6e5c3b853a9c05cdf70f25"),
        )

        assertTrue(preview.ok)
        assertEquals("已识别", preview.headline)
        assertEquals("host 10.0.0.9:8080 · 设备 717de5…0f25", preview.detail)
    }

    @Test
    fun `裸 token 没有上次地址时不可配对`() {
        // `PairingCoordinator` 对裸 token 的处理是「拿 fallback 补全地址，没有就抛」。
        // 所以全新安装 / 刚 unpair 之后，裸 token 的 ok 必须是 false——恒 true 会让
        // 「开始配对」亮着，点下去必然失败。
        val preview = previewOf(PairLinkParser.parse("abcdefghijklmnopqrstuvwxyz012345"))

        assertFalse(preview.ok)
        assertEquals("还缺服务器地址", preview.headline)
    }

    @Test
    fun `裸 token 有上次地址时可配对并说明用哪个地址`() {
        val preview = previewOf(
            PairLinkParser.parse("abcdefghijklmnopqrstuvwxyz012345"),
            fallbackBaseUrl = "http://192.168.1.5:19387",
        )

        assertTrue(preview.ok)
        assertEquals("已识别为裸 token", preview.headline)
        assertEquals("host 192.168.1.5:19387 · token abcd…2345", preview.detail)
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
    fun `hostOf 只留 scheme 之后的主机与端口，剥掉尾斜杠与路径`() {
        assertEquals("192.168.1.5:19387", hostOf("http://192.168.1.5:19387"))
        assertEquals("192.168.1.5:19387", hostOf("http://192.168.1.5:19387/"))
        // 用例名原本就写着「带路径」，但断言里一条带路径的样本都没有——于是
        // `substringAfter("://").trimEnd('/')` 不剥路径这个缺陷一直没被发现，
        // 与 `PairingApi.hostOf`（`substringBefore('/')`）语义分叉。下面两条才是正题。
        assertEquals("192.168.1.5:19387", hostOf("http://192.168.1.5:19387/a/b"))
        assertEquals("h:1", hostOf("https://h:1/x?y=1"))
    }

    @Test
    fun `过短的 token 整段打码`() {
        // 短到「首 4 + 尾 4」只剩一两个字符被遮，那样打码毫无意义。阈值取 16：
        // 真实 token 是 32 位，9~16 位的短 token 一律整段打码。
        assertEquals("…", maskToken("12345678"))
        assertEquals("…", maskToken("1234567890123456"))
        assertEquals("1234…4567", maskToken("12345678901234567"))
    }

    // ---------------------------------------------------------------- 分段互斥

    @Test
    fun `分段控件显示三个入口且只选中一个`() {
        // `assertIsSelected` 能成立是因为 `PairEntryPicker` 用的是
        // `Modifier.selectable(role = Role.Tab)` 而不是 `Surface(onClick)`——后者连
        // `Role.Button` 都不带，更不会有 `Selected` 语义（反编译 `SurfaceKt$Surface$2`
        // 确认 role 实参是 null）。改动前这条用例只断言「三个文本可见」，选中态零覆盖。
        var selected by mutableStateOf(PairEntry.Scan)
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

        compose.onNodeWithText("扫码").assertIsSelected()
        compose.onNodeWithText("链接").assertIsNotSelected()

        compose.onNodeWithText("链接").performClick()
        assertEquals(listOf(PairEntry.Link), clicks)
        compose.onNodeWithText("链接").assertIsSelected()
        compose.onNodeWithText("扫码").assertIsNotSelected()
    }

    @Test
    fun `三条入口一次只能选中一条`() {
        // 互斥是分段控件的全部意义：连点三个入口，最终只应停在最后一个上。
        var selected by mutableStateOf(PairEntry.Scan)
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
        var text by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                LinkCard(
                    busy = false,
                    onPair = { clicks += it },
                    text = text,
                    onTextChange = { text = it },
                )
            }
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
        var text by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                LinkCard(
                    busy = false,
                    onPair = { clicks += it },
                    text = text,
                    onTextChange = { text = it },
                )
            }
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
            MaterialTheme {
                LinkCard(
                    busy = true,
                    onPair = { clicks += it },
                    text = "https://192.168.1.5:19387/pair-accept?pair=abcdefghijkl",
                )
            }
        }
        compose.onNodeWithText("开始配对").assertIsNotEnabled()
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun `裸 token 没有上次地址时入口里按钮也点不动`() {
        // 与上面的纯函数用例呼应：这是用户真正会看到的后果——按钮必须是灰的。
        val clicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LinkCard(
                    busy = false,
                    onPair = { clicks += it },
                    fallbackBaseUrl = null,
                    text = "abcdefghijklmnopqrstuvwxyz012345",
                )
            }
        }
        compose.onNodeWithText("还缺服务器地址").assertExists()
        compose.onNodeWithText("开始配对").assertIsNotEnabled()
        assertEquals(emptyList<String>(), clicks)
    }

    // ---------------------------------------------------------------- 扫码权限

    @Test
    fun `没有相机权限时给授权入口而不是一个黑框`() {
        // Robolectric 默认不授予 CAMERA。改造前（R031）这里只有一句「需要相机权限才能
        // 扫码。」+ 按钮，但**二次拒绝**之后那个按钮是死的（系统不再弹窗，`launch` 立刻
        // 回调 false，界面毫无变化）。现在 `deniedOnce` 会把按钮换成「打开系统设置」。
        // 这里守的是首次进入的那一支：必须能点到授权，且不显示取景框。
        compose.setContent {
            MaterialTheme {
                ScanCard(enabled = true, scanEpoch = 0, onRescan = {}, onScanned = {})
            }
        }

        compose.onNodeWithText("授予相机权限").assertExists()
        compose.onNodeWithText("需要相机权限才能扫码。也可以换用「链接」或「手工」入口。").assertExists()
        compose.onAllNodesWithText("把桌面端的二维码放进取景框内。").assertCountEquals(0)
        compose.onAllNodesWithText("重新扫描").assertCountEquals(0)
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
    fun `探测卡回显被探测的地址，避免结论贴错输入框`() {
        // 场景：地址 A 探测很慢，用户改成 B 又点了一次；A 的结论后到。`clearProbe()`
        // 只在「用户改地址」那一刻清卡，挡不住在途请求回来写状态。卡片自带地址回显后，
        // 这种错配在界面上是可自证的，而不是一张绿灯贴着一个没探过的地址。
        compose.setContent {
            MaterialTheme {
                ProbeResultCard(
                    PairProbe(
                        ok = true,
                        baseUrl = "http://10.0.2.2:19387",
                        headline = "地址可达",
                        lines = listOf("桌面端当前无配对设备"),
                    ),
                )
            }
        }

        compose.onNodeWithText("地址可达").assertIsDisplayed()
        compose.onNodeWithText("10.0.2.2:19387").assertIsDisplayed()
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
    fun `解析结论卡的文案不会串`() {
        // 本用例守的是「文案不串」。此前它叫「两张不同形态的卡」，却只断言文本存在，
        // 而 Robolectric 里拿不到 Surface 的实际颜色——颜色差异只能靠设备验收，
        // 于是这条用例名给自己记了一个假覆盖。
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
        compose.onNodeWithText("token 1234…5678").assertIsDisplayed()
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
    fun `服务器卡排在设备 id 卡上方`() {
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
        // 原来是「探测结果卡没出现」的两条 assertCountEquals(0)：在 probe = null 时
        // 恒真，与用例名声称的「纵向序列」毫无关系。改成几何断言，顺序反了会红。
        val serverTop = compose.onNodeWithText("填 1/2").fetchSemanticsNode().boundsInRoot.top
        val deviceTop = compose.onNodeWithText("填 2/2").fetchSemanticsNode().boundsInRoot.top
        assertTrue("服务器卡（$serverTop）应在设备 id 卡（$deviceTop）上方", serverTop < deviceTop)
        // 没探测过就没有探测结果卡。
        compose.onAllNodesWithText("地址可达").assertCountEquals(0)
    }
}
