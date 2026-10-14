package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.count(): Int =
    fetchSemanticsNodes().size

/**
 * [GroupRow] 的渲染测试（Robolectric，决策 D22）。
 *
 * 这是**用户 m24940 需求**的修复本体：「DSH的标准模式下，会自动折叠各种操作比如思考、
 * 命令、读文件，会将这些操作折叠成『已思考、执行命令、读取文件等』」。
 *
 * 钉住四条契约：
 * ① 默认收起——组内成员的卡片**完全不在语义树里**，只留一行摘要标题；
 * ② 点一下才展开；
 * ③ 再点一下收回（否则用户没有回头路）；
 * ④ 进行中的分组多一条实时细节，已结束的分组没有。
 *
 * ⚠️ 标题里的 chevron 与文字是**两个独立的 Text 节点**（[GroupRow] 把 chevron 单独放）。
 * 所以断言要按文字本身找，点也点在文字上——首跑我把 "▸ 已读取文件" 当一个节点找，7 项全挂。
 *
 * ⚠️ R016 之后 chevron 改成「同一个 `▸` 字形旋转 90°」而不是 `▸`/`▾` 换字符
 * （两个字形宽度不同，切换时标题会被推着左右跳），所以**展开态不再有 `▾` 文本节点**。
 * 展开/收起的判据统一改成「成员卡片在不在语义树里」——那才是用户关心的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupRowRenderTest {

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

    private fun toolCall(seq: Long, name: String, args: String = "{}") =
        ChatItem.ToolCall(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            callId = "c$seq", name = name, arguments = args,
        )

    private fun toolResult(seq: Long) =
        ChatItem.ToolResult(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            callId = "c$seq", text = "结果文本", isError = false,
        )

    private fun reasoning(seq: Long, text: String) =
        ChatItem.Assistant(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            blocks = listOf(ContentBlock.Reasoning(text)),
        )

    private fun setGroup(
        members: List<ChatItem>,
        running: Boolean,
    ) {
        compose.setContent {
            DshAssistantTheme {
                GroupRow(
                    members = members,
                    running = running,
                    now = 0L,
                    changes = emptyMap(),
                    changesLoading = emptySet(),
                    onOpenFile = { _: FileTarget -> },
                    onRetryChanges = {},
                    attachments = attachments,
                    sessionId = "session-test",
                )
            }
        }
    }

    @Test
    fun `默认收起且组内成员不在语义树里`() {
        val members = listOf(
            toolCall(1, "read"),
            toolCall(2, "read"),
            toolResult(3),
            toolCall(4, "write"),
        )
        setGroup(members, running = false)

        compose.onNodeWithText("已读取文件并写入文件").assertExists()
        compose.onNodeWithText("▸").assertExists()
        // 折叠态下成员的卡片必须完全不在语义树里——TalkBack 与 find 都不该摸到。
        assertEquals(0, compose.onAllNodesWithText("工具 · read", substring = true).count())
        assertEquals(0, compose.onAllNodesWithText("工具 · write", substring = true).count())
        assertEquals(0, compose.onAllNodesWithText("结果文本").count())
        // 已结束的分组不显示实时细节，也不标「进行中」。
        assertEquals(0, compose.onAllNodesWithText("进行中").count())
    }

    @Test
    fun `点一下展开组内成员`() {
        val members = listOf(toolCall(1, "read"), toolResult(2))
        setGroup(members, running = false)

        compose.onNodeWithText("已读取文件").performClick()
        compose.runOnIdle { }

        assertEquals(1, compose.onAllNodesWithText("工具 · read", substring = true).count())
        compose.onNodeWithText("结果文本").assertExists()
    }

    @Test
    fun `再点一下收回组内成员`() {
        val members = listOf(toolCall(1, "read"))
        setGroup(members, running = false)

        compose.onNodeWithText("已读取文件").performClick()
        compose.runOnIdle { }
        assertEquals(1, compose.onAllNodesWithText("工具 · read", substring = true).count())

        compose.onNodeWithText("已读取文件").performClick()
        compose.runOnIdle { }

        assertEquals(0, compose.onAllNodesWithText("工具 · read", substring = true).count())
    }

    @Test
    fun `折叠时思考正文也不在语义树里`() {
        // 只含思考的分组标题落到「已完成分析」，而推理正文同样必须折叠掉。
        val members = listOf(reasoning(1, "先读一遍文件，再决定怎么改。"))
        setGroup(members, running = false)

        compose.onNodeWithText("已完成分析").assertExists()
        assertEquals(0, compose.onAllNodesWithText("先读一遍文件，再决定怎么改。").count())
    }

    @Test
    fun `进行中的分组显示实时细节`() {
        // 桌面端 `POLICIES.standard.liveProcessDetail`：进行中仍要看到在干什么。
        val members = listOf(toolCall(1, "read", """{"file_path":"settings.gradle.kts"}"""))
        setGroup(members, running = true)

        compose.onNodeWithText("已读取文件").assertExists()
        compose.onNodeWithText("settings.gradle.kts").assertExists()
        compose.onNodeWithText("进行中").assertExists()
    }

    @Test
    fun `进行中但没有可用细节时不画细节行`() {
        // 纯思考且文本为空时 detail 是空串，多画一行只会留空白。
        val members = listOf(reasoning(1, "   "))
        setGroup(members, running = true)

        compose.onNodeWithText("已完成分析").assertExists()
        compose.onNodeWithText("进行中").assertExists()
        assertEquals(0, compose.onAllNodesWithText("想", substring = true).count())
    }

    @Test
    fun `空分组不画任何东西`() {
        setGroup(emptyList(), running = false)
        // 标题仍然是「已完成分析」（桌面端 `processTitle` 对空 summary 的行为）。
        compose.onNodeWithText("已完成分析").assertExists()
        assertEquals(0, compose.onAllNodesWithText("进行中").count())
    }
}
