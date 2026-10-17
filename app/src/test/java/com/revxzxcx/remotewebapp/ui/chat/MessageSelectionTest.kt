package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复制方式：**长按选择**正文，只有代码块才有按钮（用户 m01082）。
 *
 * 用户原话：「我希望的是，用户可以长按复制界面中的内容，而只有代码块才会显示复制按钮，
 * 其他的文本不需要增加复制按钮」。R029 首版给每条消息底部画了个「复制消息」按钮，
 * 方向就是错的——消息里的路径、报错、一句话往往只想复制其中一段，长按选中才是系统
 * 原生的手感；右下的常驻按钮既占地方又只能整条复制。
 *
 * 这里守三条契约：
 *
 * 1. **消息上没有复制按钮了**（`onAllNodesWithContentDescription("复制消息")` 必须为 0）。
 *    这条防的是「改着改着又顺手加回来」，以及 `CopyMessageButton` 被谁复活的回归。
 * 2. **正文可选**：`Bubble` 给正文那层 `SelectionContainer` 打了
 *    [MessageSelectableTestTag]，用户消息与助手消息各有一处。
 * 3. **工具卡不可选**：工具调用 / 改动卡传 `selectable = false`——它们的展开体是几千字
 *    原始参数，包进选择容器会让整张卡吞掉点击（展开/收起就点不动了）。
 *
 * 为什么不测「长按后剪贴板里有什么」：Robolectric 的剪贴板 shadow 与真机行为差异大，
 * 而选择手柄、菜单属于系统实现，测不到也不该测。可断言的是「这一层是否存在选择容器」，
 * 那正是产品代码里我们唯一做的决定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val attachments = SessionAttachmentRepository(
        TypertClient(
            baseUrl = "http://127.0.0.1:1",
            deviceId = "test-device",
            http = OkHttpClient(),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        ),
    )

    private fun userItem(seq: Long, text: String) = ChatItem.User(
        seq = seq, time = 0L, turn = 1L,
        text = text,
        blocks = if (text.isEmpty()) emptyList() else listOf(ContentBlock.Text(text)),
    )

    private fun assistantItem(seq: Long, text: String) = ChatItem.Assistant(
        seq = seq, time = 0L, turn = 1L, step = 1L,
        blocks = listOf(ContentBlock.Text(text)),
    )

    /**
     * 真正的工具卡是 [ChatItem.ToolCall]，**不是** Assistant 里塞一个 ToolCall 块。
     *
     * 首版这里写成了 `ChatItem.Assistant(blocks = listOf(ContentBlock.ToolCall(...)))`，
     * 于是它走的是助手正文分支（`selectable` 默认 true），「工具卡不可选」这条**永远绿**。
     * 变异验证（把两处 `selectable = false` 改成 `true`）跑出来 6 条全过，才暴露出
     * 这个测试本身是空转的——这也正是 R029 审查 P3-2「只断言纯函数等于没测」的同族错误。
     */
    private fun toolCallItem(seq: Long, name: String) = ChatItem.ToolCall(
        seq = seq, time = 0L, turn = 1L, step = 1L,
        callId = "call-$seq", name = name, arguments = "{}",
    )

    private fun toolResultItem(seq: Long) = ChatItem.ToolResult(
        seq = seq, time = 0L, turn = 1L, step = 1L,
        callId = "call-$seq", text = "工具输出", isError = false,
    )

    private fun setItems(items: List<ChatItem>) {
        compose.setContent {
            DshAssistantTheme {
                LazyColumn {
                    items(items) { item ->
                        ChatItemRow(
                            item = item,
                            now = 0L,
                            changes = null,
                            changesLoading = false,
                            onOpenFile = { _: FileTarget -> },
                            onRetryChanges = {},
                            attachments = attachments,
                            sessionId = "session-test",
                            railColor = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }

    /** R030 的核心：消息上不再有「复制消息」按钮。 */
    @Test
    fun `消息上没有复制按钮`() {
        setItems(
            listOf(
                userItem(1, "帮我看下 F:\\bot\\remote-web-app 这个目录"),
                assistantItem(2, "这是助手的回复。"),
            ),
        )
        compose.onAllNodesWithContentDescription("复制消息").assertCountEquals(0)
    }

    /** 用户消息正文可选（长按即出系统的选择手柄与复制菜单）。 */
    @Test
    fun `用户消息正文可长按选择`() {
        setItems(listOf(userItem(1, "帮我看下这个目录")))
        assertSelectableOnce()
    }

    /** 助手消息正文同样可选。 */
    @Test
    fun `助手消息正文可长按选择`() {
        setItems(listOf(assistantItem(1, "这是助手的回复。")))
        assertSelectableOnce()
    }

    /**
     * 工具卡不包选择容器。
     *
     * 若这里变绿失败，说明有人把 `selectable = false` 去掉了——后果是点卡片不再展开，
     * 长按先选到一堆 `{"path":...}` 原始参数。
     */
    @Test
    fun `工具调用卡不可选`() {
        setItems(listOf(toolCallItem(1, "bash")))
        compose.onNodeWithTag(MessageSelectableTestTag).assertDoesNotExist()
    }

    /** 工具结果卡同理（展开体是几千字原始输出，包进选择容器会吞掉点击）。 */
    @Test
    fun `工具结果卡不可选`() {
        setItems(listOf(toolResultItem(1)))
        compose.onNodeWithTag(MessageSelectableTestTag).assertDoesNotExist()
    }

    /** 多条消息各有一处选择容器，不会只包第一条。 */
    @Test
    fun `每条消息各自可选`() {
        setItems(listOf(userItem(1, "第一条"), assistantItem(2, "第二条")))
        compose.onAllNodesWithTag(MessageSelectableTestTag)
            .assertCountEquals(2)
        compose.onAllNodesWithTag(MessageSelectableTestTag)
            .fetchSemanticsNodes()
            .forEach { node ->
                assertTrue(
                    "每个选择容器节点都必须带 RequestFocus 语义（证明它是真正的 SelectionContainer）",
                    node.config.contains(SemanticsActions.RequestFocus),
                )
            }
    }

    /**
     * 断言「恰好有一处**真正的**选择容器」。
     *
     * 只 `assertExists()` 是不够的——审查时把 `SelectionContainer` 换成裸 `Box`（等于完全去掉
     * 选择能力），仅查 tag 的那几条仍然全绿。`SelectionContainer` 与普通容器的语义差别在于
     * 它会给自己挂 `SemanticsActions.RequestFocus`（实测 1.12.1 上是 `Focused` + `RequestFocus`，
     * 没有 `CopyText`），因此断言这个动作是否定义，才能区分「能长按选择」与「只是个容器」。
     */
    private fun assertSelectableOnce() {
        val nodes = compose.onAllNodesWithTag(MessageSelectableTestTag).fetchSemanticsNodes()
        assertEquals("恰好一条消息应包选择容器", 1, nodes.size)
        assertTrue(
            "选择容器节点必须带 RequestFocus 语义，否则说明它已被换成普通容器",
            nodes.single().config.contains(SemanticsActions.RequestFocus),
        )
    }

    /** 正文文字仍然完整渲染（包选择容器没把内容吃掉）。 */
    @Test
    fun `包了选择容器后正文仍然可见`() {
        setItems(listOf(userItem(1, "这段文字必须能被看到")))
        compose.onNodeWithText("这段文字必须能被看到").assertExists()
    }
}
