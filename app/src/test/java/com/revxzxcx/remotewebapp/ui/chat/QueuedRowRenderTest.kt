package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 排队区的渲染测试（R031 重构后）。
 *
 * 旧实现每条是一个 Bubble（带时间、footer、三个文字按钮），三条就把 composer 上方
 * 吃掉 200dp 以上。用户 m45580 第 3 条要求重构成：
 *  - 单条 -> 显示内容 + 右侧三个图标按钮；
 *  - 多条 -> 收起成一行，不显示任何内容，点开才逐条展开；
 *  - 编辑改成行内（单行输入 + 勾/叉），不再是对话框（用户 m45632 第 5 条）。
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

    private fun setStrip(
        messages: List<QueuedMessage>,
        generating: Boolean = true,
        expanded: Boolean = false,
        onToggleExpanded: () -> Unit = {},
        onEdit: (QueuedMessage, String) -> Unit = { _, _ -> },
        onRemove: (QueuedMessage) -> Unit = {},
        onSteer: (QueuedMessage) -> Unit = {},
    ) {
        compose.setContent {
            QueuedStrip(
                messages = messages,
                generating = generating,
                now = 2_000L,
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                onEdit = onEdit,
                onRemove = onRemove,
                onSteer = onSteer,
            )
        }
    }

    @Test
    fun `单条排队显示内容与三个图标按钮`() {
        setStrip(listOf(echo("a")))
        compose.onNodeWithText("等我这轮说完").assertExists()
        compose.onNodeWithText("等当前轮结束后发出", substring = true).assertExists()
        compose.onNodeWithContentDescription("插队").assertExists()
        compose.onNodeWithContentDescription("编辑排队消息").assertExists()
        compose.onNodeWithContentDescription("删除排队消息").assertExists()
    }

    @Test
    fun `单条不显示排队收起行`() {
        setStrip(listOf(echo("a")))
        compose.onAllNodesWithTag(QueueHeaderTestTag).assertCountEquals(0)
    }

    @Test
    fun `未确认的单条不给按钮但说明原因`() {
        setStrip(listOf(echo("a", confirmed = false)))
        compose.onNodeWithText("等待桌面端确认…").assertExists()
        compose.onAllNodesWithContentDescription("编辑排队消息").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("删除排队消息").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("插队").assertCountEquals(0)
    }

    @Test
    fun `只有附件的排队消息不渲染成空白且不显示附件数`() {
        setStrip(listOf(echo("a", text = "", attachments = 3)))
        compose.onNodeWithText("（含附件，不支持编辑）").assertExists()
        compose.onAllNodesWithText("个附件", substring = true).assertCountEquals(0)
    }

    @Test
    fun `已插队的消息说明它在当前轮执行`() {
        setStrip(listOf(echo("a", awaiting = false)))
        compose.onNodeWithText("已插队 · 在当前轮执行", substring = true).assertExists()
    }

    @Test
    fun `多条排队收起时不显示任何消息内容`() {
        setStrip(
            listOf(echo("a", text = "第一条"), echo("b", text = "第二条")),
            expanded = false,
        )
        compose.onNodeWithTag(QueueHeaderTestTag).assertExists()
        compose.onNodeWithText("2 条").assertExists()
        compose.onAllNodesWithText("第一条").assertCountEquals(0)
        compose.onAllNodesWithText("第二条").assertCountEquals(0)
    }

    @Test
    fun `多条排队展开后每条都有三个图标`() {
        setStrip(
            listOf(echo("a", text = "第一条"), echo("b", text = "第二条")),
            expanded = true,
        )
        compose.onNodeWithText("第一条").assertExists()
        compose.onNodeWithText("第二条").assertExists()
        compose.onAllNodesWithContentDescription("编辑排队消息").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("删除排队消息").assertCountEquals(2)
    }

    @Test
    fun `点收起行会触发展开回调`() {
        var toggled = 0
        setStrip(
            listOf(echo("a"), echo("b")),
            expanded = false,
            onToggleExpanded = { toggled++ },
        )
        compose.onNodeWithTag(QueueHeaderTestTag).performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `点编辑进入行内编辑并显示保存与取消`() {
        setStrip(listOf(echo("a", text = "原来的话")))
        compose.onNodeWithContentDescription("编辑排队消息").performClick()

        compose.onNodeWithTag(QueueEditFieldTestTag).assertExists()
        compose.onNodeWithContentDescription("保存排队消息").assertExists()
        compose.onNodeWithContentDescription("取消编辑").assertExists()
        compose.onAllNodesWithContentDescription("删除排队消息").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("插队").assertCountEquals(0)
    }

    @Test
    fun `行内编辑保存会把文本交出去`() {
        var edited: String? = null
        setStrip(
            listOf(echo("a", text = "原来的话")),
            onEdit = { _, text -> edited = text },
        )
        compose.onNodeWithContentDescription("编辑排队消息").performClick()

        // 🔴 必须真的敲字（R031 独立审查 P2）：早先这里点开编辑就直接点保存，断言
        // `edited == "原来的话"` 恒成立——把 `BasicTextField` 换成一个只显示原文的
        // `Text`，甚至把 `onValueChange` 删掉，测试照样全绿。输入再保存才能证明
        // 「输入框可编辑」「敲进去的字进到了回调里」这两件事。
        compose.onNodeWithTag(QueueEditFieldTestTag).performTextInput("改的")
        compose.onNodeWithContentDescription("保存排队消息").performClick()
        // `performTextInput` 在**光标处**插入，而 `BasicTextField` 初始化时光标在开头，
        // 所以新字落在前面。断言这个顺序而不是「拼在后面」——重点是证明敲进去的字
        // 进了回调（原文仍在），不是证明插入位置。
        assertEquals("改的原来的话", edited)
    }

    @Test
    fun `行内编辑取消不交文本`() {
        var edited: String? = null
        setStrip(
            listOf(echo("a", text = "原来的话")),
            onEdit = { _, text -> edited = text },
        )
        compose.onNodeWithContentDescription("编辑排队消息").performClick()
        compose.onNodeWithContentDescription("取消编辑").performClick()
        assertEquals(null, edited)
        compose.onNodeWithContentDescription("编辑排队消息").assertExists()
    }

    @Test
    fun `点删除与插队分别触发回调`() {
        var removed = 0
        var steered = 0
        setStrip(
            listOf(echo("a")),
            onRemove = { removed++ },
            onSteer = { steered++ },
        )
        compose.onNodeWithContentDescription("删除排队消息").performClick()
        compose.onNodeWithContentDescription("插队").performClick()
        assertEquals(1, removed)
        assertEquals(1, steered)
    }

    @Test
    fun `空队列整条不渲染`() {
        setStrip(emptyList())
        compose.onAllNodesWithTag(QueueHeaderTestTag).assertCountEquals(0)
        compose.onAllNodesWithText("排队中").assertCountEquals(0)
    }
}
