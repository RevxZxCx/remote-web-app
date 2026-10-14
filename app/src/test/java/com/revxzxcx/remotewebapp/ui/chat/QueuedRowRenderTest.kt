package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [QueuedStrip] / [QueuedRow] 的渲染测试（Robolectric，决策 D22）。
 *
 * 为什么值得写：这条气泡是**用户 m20380 亲身反馈的缺口**的修复本体——会话正在生成时
 * 发送，`mode:'queue'` 的消息要等当前轮结束才写进会话日志。修复前用户看到的是
 * 「输入框被清空、界面里什么都不出现」，无法判断消息发没发出去。
 *
 * 用户 m21177 第 4 条反馈又追加了两件事，也由这里的用例盯住：
 * ① 气泡必须在**输入框上方**（`QueuedStrip` 而不是消息列表里的一项）；
 * ② 必须能**编辑 / 删除 / 插队**——R009 曾判定「远程客户端拿不到 itemId 所以不做」，
 *    后来实测 itemId 就在 `inbox` 投影与 `agent/inbox/spliced` 事件里
 *    （见 [QueuedMessage] 的 KDoc），那三个按钮因此有了真实对象。
 *
 * 断言刻意只查「文本/按钮在不在语义树里」：真正容易被改坏的是**文案与入口**
 * （有人会把「排队中」提示删掉、把三个按钮漏掉、或让未确认的回调也显示按钮），
 * 而配色/圆角/是否在屏上由真机验收管。
 *
 * 三个写测试时踩到的坑（`QueuedRowDiagTest` dump 出的语义树证实）：
 * ① 气泡脚注是**拼接**出来的——`Bubble` 把 `TimeText.relative(...)` 和 footer 拼成
 *    一个 `Text`（线上是 `刚刚 排队中 · 等当前轮结束后发出`），所以必须
 *    `substring = true`，精确匹配永远找不到；
 * ② Robolectric 下 `Text` 节点的 bounds 是退化的（19px 宽装一整句），
 *    `assertIsDisplayed()` 会误报「没画出来」——同 `MdViewRenderTest`/`SettingsScreenRenderTest`
 *    的教训，这里统一用 `assertExists()`；
 * ③ 按钮在视口外时 `performClick()` 静默无效（`ComposerAttachmentTest` 踩过），
 *    所以断言点击回调时要么把容器限高到装得下，要么先 `performScrollTo()`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueuedRowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun echo(
        id: String,
        text: String = "等我这轮说完",
        attachments: Int = 0,
        confirmed: Boolean = true,
        awaiting: Boolean = true,
    ) = QueuedMessage(
        id = id,
        rpcId = "rpc-$id",
        text = text,
        attachmentCount = attachments,
        enqueuedAt = 1_000L,
        target = if (awaiting) "next-turn" else "next-step",
        confirmed = confirmed,
    )

    @Test
    fun `排队中的文字消息会显示正文与排队提示`() {
        compose.setContent {
            QueuedRow(
                echo = echo("a"),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("等我这轮说完").assertExists()
        compose.onNodeWithText("排队中 · 等当前轮结束后发出", substring = true).assertExists()
    }

    @Test
    fun `只带附件的排队消息也不会渲染成空白`() {
        // 「只发图不发字」是常见用法（服务端 `hasPromptContent` 接受纯附件）。
        // 这条消息的 text 是空的，气泡里必须留下别的东西，否则用户看到一块空白。
        compose.setContent {
            QueuedRow(
                echo = echo("a", text = "", attachments = 3),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("（3 个附件）").assertExists()
        compose.onNodeWithText("排队中 · 等当前轮结束后发出 · 含 3 个附件", substring = true)
            .assertExists()
    }

    @Test
    fun `多条排队回声各自独立渲染`() {
        // 连发三条时的形态：三条都要在，且顺序即入队顺序。
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                QueuedRow(echo("a", text = "第一条"), 2_000L, true, {}, {}, {})
                QueuedRow(echo("b", text = "第二条"), 2_000L, true, {}, {}, {})
            }
        }

        compose.onNodeWithText("第一条").assertExists()
        compose.onNodeWithText("第二条").assertExists()
    }

    @Test
    fun `已确认的排队消息给出编辑删除插队三个入口`() {
        // 用户 m21177 第 4 条反馈的本体。三个按钮一个都不能少——少一个就意味着
        // 用户对队列里的消息做不了对应的操作。
        compose.setContent {
            QueuedRow(
                echo = echo("a"),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("编辑").assertExists()
        compose.onNodeWithText("插队").assertExists()
        compose.onNodeWithText("删除").assertExists()
    }

    @Test
    fun `没拿到服务端 id 的回调不给操作按钮但说明原因`() {
        // 本地回声还没被 `agent/inbox/spliced` 确认：三个操作都发不出去（服务端只认
        // itemId），此时给按钮就是「点了没反应」。但必须留一句说明，否则用户以为坏了。
        compose.setContent {
            QueuedRow(
                echo = echo("a", confirmed = false),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("等待桌面端确认…").assertExists()
        listOf("编辑", "插队", "删除").forEach { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes()
                .let { assertEquals("不该有「$label」入口", 0, it.size) }
        }
    }

    @Test
    fun `不在生成中时不给插队入口`() {
        // 不生成时这条本来就排在空队列头部，下一个轮次就是它，插队没有意义；
        // 服务端此时也会回 `session/steer-unavailable`。编辑与删除仍然要给——
        // 撤一条还没发出去的消息与生成无关。
        compose.setContent {
            QueuedRow(
                echo = echo("a"),
                now = 2_000L,
                generating = false,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("编辑").assertExists()
        compose.onNodeWithText("删除").assertExists()
        compose.onAllNodesWithText("插队").fetchSemanticsNodes()
            .let { assertEquals("不该有插队入口", 0, it.size) }
    }

    @Test
    fun `点编辑会弹出对话框并把改后的文本交出去`() {
        var edited: String? = null
        compose.setContent {
            QueuedRow(
                echo = echo("a", text = "原来的话"),
                now = 2_000L,
                generating = true,
                onEdit = { edited = it },
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("编辑排队消息").assertExists()
        // 保存按钮把对话框里的当前值交出去（初始值就是原文）。
        compose.onNodeWithText("保存").performClick()
        assertEquals("原来的话", edited)
    }

    @Test
    fun `点删除与插队会分别触发回调`() {
        var removed = 0
        var steered = 0
        compose.setContent {
            QueuedRow(
                echo = echo("a"),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = { removed++ },
                onSteer = { steered++ },
            )
        }

        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("插队").performClick()
        assertEquals(1, removed)
        assertEquals(1, steered)
    }

    @Test
    fun `已经插队的消息脚注说明它在当前轮执行`() {
        // `target == 'next-step'`：服务端已把它并进当前轮，文案必须换，
        // 否则用户以为还要再等一轮。
        compose.setContent {
            QueuedRow(
                echo = echo("a", awaiting = false),
                now = 2_000L,
                generating = true,
                onEdit = {},
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("已插队 · 在当前轮执行", substring = true).assertExists()
    }

    @Test
    fun `队列条会报出条数`() {
        // 用户 m21177 的原话是「发送框上方会多一个气泡」。条数标题是这一条反馈的
        // 位置证据：它在 bottomBar 里，不在消息列表里。
        compose.setContent {
            QueuedStrip(
                messages = listOf(echo("a", text = "第一条"), echo("b", text = "第二条")),
                generating = true,
                now = 2_000L,
                onEdit = { _, _ -> },
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onNodeWithText("排队中 2 条").assertExists()
        compose.onNodeWithText("第一条").assertExists()
        compose.onNodeWithText("第二条").assertExists()
    }

    @Test
    fun `队列为空时整条不渲染`() {
        compose.setContent {
            QueuedStrip(
                messages = emptyList(),
                generating = true,
                now = 2_000L,
                onEdit = { _, _ -> },
                onRemove = {},
                onSteer = {},
            )
        }

        compose.onAllNodesWithText("排队中").fetchSemanticsNodes()
            .let { assertEquals("空队列不该占位", 0, it.size) }
    }
}
