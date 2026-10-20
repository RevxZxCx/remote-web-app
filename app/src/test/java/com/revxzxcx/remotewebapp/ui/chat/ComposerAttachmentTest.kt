package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Composer] 附件入口的渲染测试（Robolectric，决策 D22）。
 *
 * 为什么值得写：附件是 M5 新功能，而它最脆的地方全在 UI 与参数的接缝上——
 * ① 只有附件没有文字时「发送」还能不能点（`draft.isNotBlank() || pending.isNotEmpty()`）；
 * ② 「＋」点开是不是真的给出两个选择器（R017 后「＋」换成 Add 图标 + contentDescription）；
 * ③ 待发送 chips 的「×」删的是不是点的那一个。
 * 这三件事纯逻辑层测不到，因为它们编码在 Composable 的入参组合上。
 *
 * R019（用户 m27990）起重写输入区，发送键从「胶囊文字钮」改成**圆形 ↑ 图标钮**，
 * 展开态多出配置行。断言因此从 `onNodeWithText("发送")` 换成
 * `onNodeWithContentDescription("发送按钮")`——**这更接近真实用户路径**（读屏用户
 * 听到的就是这个描述），也把「按钮还在、文案被换掉」这类回归钉死了。
 *
 * 与 `MdViewRenderTest` 同样的教训：Robolectric 虚拟屏幕只有几屏高，断言用
 * `assertIsDisplayed`（这里每个节点都点得到，没有滚动需求）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerAttachmentTest {

    @get:Rule
    val compose = createComposeRule()

    private val image = PromptAttachment(
        kind = PromptAttachment.Kind.IMAGE,
        name = "shot.png",
        mediaType = "image/png",
        data = "AAAA",
    )

    private val file = PromptAttachment(
        kind = PromptAttachment.Kind.FILE,
        name = "notes.md",
        receiptId = "rcpt-1",
    )

    private fun setComposer(
        draft: String = "",
        generating: Boolean = false,
        sending: Boolean = false,
        pending: List<PromptAttachment> = emptyList(),
        attachNotice: String? = null,
        onRemove: (Int) -> Unit = {},
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        compose.setContent {
            Composer(
                draft = draft,
                generating = generating,
                sending = sending,
                pending = pending,
                attachNotice = attachNotice,
                modelChip = {},
                permissionChip = {},
                onDraftChange = {},
                onSend = onSend,
                onStop = onStop,
                onRemoveAttachment = onRemove,
                onAttachImage = { _, _, _, _ -> null },
                onAttachFile = { _, _ -> },
                onAttachNotice = {},
            )
        }
    }

    @Test
    fun `只有文字没有附件时发送可用`() {
        setComposer(draft = "你好")
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
    }

    @Test
    fun `发送在途中按钮进入加载态且不可再点`() {
        // T5 设备验收：发送是网络调用，期间按钮一直亮着，用户会反复点。
        //
        // R016 把「发送中」文案换成了一个 14dp 的转圈（文案切换会让按钮宽度跳一下，
        // 而转圈 + AnimatedContent 是连续的）。所以这里不能再按文案断言，改成：
        // ① 用固定的 contentDescription（「发送按钮」）找到按钮断言 disabled；
        // ② 「发送」二字在在途期间消失，说明按钮内容确实被换掉了。
        var calls = 0
        setComposer(draft = "你好", sending = true, onSend = { calls++ })
        compose.onNodeWithContentDescription("发送按钮").assertIsNotEnabled()
        assertEquals(
            "在途期间不该还写着「发送」",
            0,
            compose.onAllNodesWithText("发送").fetchSemanticsNodes().size,
        )
        // 不可点的节点上 performClick 是空操作，但守卫是 Button 自己的 enabled。
        compose.onNodeWithContentDescription("发送按钮").performClick()
        assertEquals("在途期间不该再发一次", 0, calls)
    }

    @Test
    fun `只有附件没有文字时发送也可用`() {
        // 带图不发文字是常见用法；只判 draft 会让用户卡在「有图发不出去」。
        setComposer(draft = "", pending = listOf(image))
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
    }

    @Test
    fun `只有附件没有文字时点发送真的会把 onSend 叫出去`() {
        // M5-10 设备验收实测缺陷：UI 侧按钮是亮的，`ChatViewModel.send()` 却有一道
        // `if (text.isEmpty()) return`，点下去毫无反应也不报错。按钮「可点」与
        // 「点了有人答应」是两件事，必须分别钉住。
        var sends = 0
        setComposer(draft = "", pending = listOf(image), onSend = { sends++ })
        compose.onNodeWithContentDescription("发送按钮").performClick()
        compose.waitForIdle()
        assertEquals(1, sends)
    }

    @Test
    fun `文字和附件都没有时发送不可用`() {
        setComposer(draft = "", pending = emptyList())
        compose.onNodeWithContentDescription("发送按钮").assertIsNotEnabled()
    }

    @Test
    fun `生成中发送键变成排队并且仍然可点`() {
        // R017：生成中点发送的按钮文案从「发送」改成「排队」。这不是文案洁癖——
        // 按钮是用户唯一能提前知道「这条不会马上发出」的地方，写「发送」会让人
        // 以为消息已经出去了（用户 m21177 第 4 条反馈的配套修正）。
        //
        // R019（用户 m27990）：发送键改成圆形图标钮，按钮上不再有文字。告知改由
        // **占位符**承担（「生成中…（发送会排到当前轮之后）」）——它就在用户眼睛
        // 看着的地方，而且不占用按钮宽度。这里用 `pending` 把输入区推进展开态：
        // Robolectric 里拿不到真实焦点，而展开判据同时接受「有待发送附件」。
        var sends = 0
        setComposer(draft = "", generating = true, pending = listOf(image), onSend = { sends++ })
        compose.onNodeWithText("生成中…（发送会排到当前轮之后）").assertExists()
        compose.onNodeWithText("描述你的任务，或先聊几句…").assertDoesNotExist()
        // 「排队」不等于「不能发」：生成中仍然可以点，消息会排到当前轮之后。
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
        compose.onNodeWithContentDescription("发送按钮").performClick()
        compose.waitForIdle()
        assertEquals(1, sends)
    }

    @Test
    fun `非生成中发送键仍是发送`() {
        setComposer(draft = "第一条", generating = false)
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
    }

    @Test
    fun `点加号给出选图片与选文件两个入口`() {
        setComposer()
        // R017：「＋」字符换成 `Icons.Default.Add` 图标，带 contentDescription「添加附件」。
        // 判据从文本换成 contentDescription——这也更接近真实用户（读屏用户）的路径。
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("添加附件").assertIsDisplayed()
        compose.onNodeWithText("选图片").assertIsDisplayed()
        compose.onNodeWithText("选文件").assertIsDisplayed()
    }

    @Test
    fun `没有待发送附件时不出现附件卡片`() {
        setComposer()
        compose.onNodeWithText("图片 · shot.png").assertDoesNotExist()
        compose.onNodeWithText("文件 · notes.md").assertDoesNotExist()
    }

    @Test
    fun `图片与文件附件分别渲染成卡片`() {
        setComposer(pending = listOf(image, file))
        compose.onNodeWithText("图片 · shot.png").assertIsDisplayed()
        compose.onNodeWithText("文件 · notes.md").assertIsDisplayed()
    }

    @Test
    fun `点卡片的叉号删掉点的那一个`() {
        val removed = mutableListOf<Int>()
        setComposer(pending = listOf(image, file), onRemove = { removed += it })
        // 两张卡片的「×」文本完全相同，只能按位置取：第二个 = 第二张（文件）。
        assertEquals(2, compose.onAllNodesWithText("×").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("×")[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), removed)
    }

    @Test
    fun `点第一张卡片的叉号删的是第一张`() {
        val removed = mutableListOf<Int>()
        setComposer(pending = listOf(image, file), onRemove = { removed += it })
        compose.onAllNodesWithText("×")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(0), removed)
    }

    @Test
    fun `附件提示条出现时可以一键消掉`() {
        setComposer(attachNotice = "这张图片有 1.0 MB，超过单张上限 500 B。")
        compose.onNodeWithText("这张图片有 1.0 MB，超过单张上限 500 B。").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertIsDisplayed()
    }

    // ── R039 第 5 条（用户 m17042）：停止入口从顶栏的 × 搬到发送键。
    //
    // 这一改动的全部风险都在**判据**上：多一个条件或少一个条件，用户就会在想排队
    // 发消息时点成中止。四条用例各守一个边界：
    // ① 进行中 + 空输入 → 停止；② 进行中 + 有草稿 → 仍是发送（排队）；
    // ③ 进行中 + 挂着附件 → 仍是发送（附件的意图就是把它们发出去）；
    // ④ 发送在途 → 不变成停止（否则一次误触会在请求已经飞出去之后再补一刀）。
    //
    // 断言用 contentDescription 而不是图标形态：读屏用户听到的就是这个字符串，
    // 它也把「图标换了但语义没换」这类回归一并钉住。

    @Test
    fun `生成中且输入框为空时发送键变成停止键`() {
        var stops = 0
        var sends = 0
        setComposer(generating = true, draft = "", onStop = { stops++ }, onSend = { sends++ })
        compose.onNodeWithContentDescription("停止生成").assertIsEnabled()
        compose.onNodeWithContentDescription("发送按钮").assertDoesNotExist()
        compose.onNodeWithContentDescription("停止生成").performClick()
        compose.waitForIdle()
        assertEquals(1, stops)
        assertEquals(0, sends)
    }

    @Test
    fun `生成中输入框有草稿时仍是发送键并排队`() {
        var stops = 0
        var sends = 0
        setComposer(generating = true, draft = "排队一句", onStop = { stops++ }, onSend = { sends++ })
        compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
        compose.onNodeWithContentDescription("发送按钮").performClick()
        compose.waitForIdle()
        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test
    fun `生成中挂着附件时仍是发送键`() {
        setComposer(generating = true, draft = "", pending = listOf(image))
        compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送按钮").assertIsEnabled()
    }

    @Test
    fun `发送在途时不会变成停止键`() {
        // 请求已经飞出去了，此时再给一个「停止」会让人以为能撤回这一条。
        setComposer(generating = true, sending = true, draft = "")
        compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送按钮").assertIsNotEnabled()
    }

    @Test
    fun `没有生成时输入框为空仍然不可点`() {
        setComposer(generating = false, draft = "")
        compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送按钮").assertIsNotEnabled()
    }
}
