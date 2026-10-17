package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户自己发的消息也要能复制（用户 m45428 第 6 条 / R029 审查 P2-1）。
 *
 * 首版 `Bubble` 只在 `background == null` 的**助手**分支渲染了 `copyText`，
 * `ChatItemRow` 给用户消息传的值被静默丢弃——助手消息有「复制消息」，用户消息一个都没有。
 * 而用户自己发的那条（路径、命令、贴进去的报错）恰恰是最常被复制的。
 *
 * 判据用**内容描述**而不是布局坐标：按钮是不是存在、挂的是不是同一条文本的语义，
 * 这才是「能复制」的实质。纯图片消息（无文本）必须**不出现**按钮——按了没反应更糟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserCopyButtonTest {

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

    /** 用户消息必须有复制按钮（首版这个断言会红）。 */
    @Test
    fun `用户消息有复制按钮`() {
        setItems(listOf(userItem(1, "帮我看下 F:\bot\remote-web-app 这个目录")))
        compose.onNodeWithContentDescription("复制消息").assertExists()
    }

    /** 只有图片、没有文字的消息不显示按钮（没有可复制的内容）。 */
    @Test
    fun `纯图片用户消息没有复制按钮`() {
        setItems(listOf(userItem(1, "")))
        compose.onNodeWithContentDescription("复制消息").assertDoesNotExist()
    }

    /** 多条用户消息各有一个按钮，不会只渲染第一个。 */
    @Test
    fun `每条用户消息各有自己的复制按钮`() {
        setItems(listOf(userItem(1, "第一条"), userItem(2, "第二条")))
        compose.onAllNodesWithContentDescription("复制消息").assertCountEquals(2)
    }

    /** 助手消息的复制按钮照旧存在（不能为了修用户侧把它弄丢）。 */
    @Test
    fun `助手消息仍有复制按钮`() {
        setItems(
            listOf(
                ChatItem.Assistant(
                    seq = 1, time = 0L, turn = 1L, step = 1L,
                    blocks = listOf(ContentBlock.Text("这是助手的回复。")),
                ),
            ),
        )
        compose.onNodeWithContentDescription("复制消息").assertExists()
    }
}
