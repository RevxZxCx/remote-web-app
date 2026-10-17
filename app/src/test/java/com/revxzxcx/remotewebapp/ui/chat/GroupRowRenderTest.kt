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
 * ⚠️ R016 之后 chevron 改成「同一个字形旋转 90°」而不是 `▸`/`▾` 换字符
 * （两个字形宽度不同，切换时标题会被推着左右跳），所以**展开态不再有 `▾` 文本节点**。
 * 展开/收起的判据统一改成「成员卡片在不在语义树里」——那才是用户关心的东西。
 *
 * ⚠️ R017 之后 chevron 又换成 `KeyboardArrowRight` **图标**（`contentDescription = null`），
 * 语义树里不再有任何 chevron 节点。收起态的判据相应换成右侧的 `×N` 徽标。
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
        // R017：chevron 从 `▸` 字形换成 `KeyboardArrowRight` 图标（`contentDescription = null`）。
        // 图标节点不带文本，所以判据换成「摘要右侧的 `×N`」——它同样是收起态独有的
        // 可见元素，且比字符 chevron 更能表达「这里面有 N 条」。
        // 组内 3 个 tool/call（read×2 + write×1）+ 1 个 tool/result，
        // `count` 只数工具调用（桌面端同样只访问 tool-call 节点）→ ×3。
        compose.onNodeWithText("×3").assertExists()
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

        // R028b（用户 m44550 第二张图）：组内成员改成**行式**——工具名单独是一个
        // 等宽 chip（`read`），不再是 `工具 · read` 那样一整句。判据相应改成 chip 本身。
        assertEquals(1, compose.onAllNodesWithText("read", substring = false).count())
        compose.onNodeWithText("结果文本").assertExists()
    }

    @Test
    fun `再点一下收回组内成员`() {
        val members = listOf(toolCall(1, "read"))
        setGroup(members, running = false)

        compose.onNodeWithText("已读取文件").performClick()
        compose.runOnIdle { }
        assertEquals(1, compose.onAllNodesWithText("read", substring = false).count())

        compose.onNodeWithText("已读取文件").performClick()
        compose.runOnIdle { }

        assertEquals(0, compose.onAllNodesWithText("read", substring = false).count())
    }

    @Test
    fun `组内工具条目是行式而不是一张张卡片`() {
        // 这是用户 m44550 第二条的原话要求：「操作和工具调用的样式还是和之前一样，
        // 你需要查看原型图中的表现，做对应的更改」。原型 `docs/R028-效果图.html` 的
        // `.grow` 是一行：`[tool chip] [arg] ....... [tag]`，不是带描边的卡。
        val members = listOf(
            toolCall(1, "write", """{"file_path":"app/build.gradle.kts","content":"x"}"""),
        )
        setGroup(members, running = false)
        compose.onNodeWithText("已写入文件").performClick()
        compose.runOnIdle { }

        // 工具名 chip：等宽、裸文本，没有 `工具 · ` 前缀。
        compose.onNodeWithText("write", substring = false).assertExists()
        assertEquals(0, compose.onAllNodesWithText("工具 · write", substring = true).count())
        // 参数摘要：等宽路径。
        compose.onNodeWithText("app/build.gradle.kts", substring = true).assertExists()
        // 状态 tag。
        compose.onNodeWithText("已编辑").assertExists()
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

    @Test
    fun `先思考再调工具的成员里思考也是行式`() {
        // 🔴 这条钉住「第 2 条首版没生效」那个根因。
        //
        // 真实会话（f3c843ab）里 `[reasoning, tool-call]` 占 5738/12221 条，是主流形态。
        // 首版判据拿 `item.blocks` 原样做 `all { it is Reasoning }`，被那个 tool-call 块
        // 判假 → 整条落到 `ChatItemRow`，思考被画成 `ReasoningBlock` 卡片
        // （「思考过程 · N 字」+ 实底描边）。设备验收一眼可见：组内其余成员是行，
        // 唯独思考是一张卡。
        val members = listOf(
            ChatItem.Assistant(
                seq = 1, time = 0L, turn = 1L, step = 1L,
                blocks = listOf(
                    ContentBlock.Reasoning("先读一遍文件，再决定怎么改。"),
                    ContentBlock.ToolCall(id = "c1", name = "bash", arguments = "{}"),
                ),
            ),
        )
        setGroup(members, running = false)
        compose.onNodeWithText("已完成分析").performClick()
        compose.runOnIdle { }

        // 行式：`思考过程` 是裸 chip 文本。
        compose.onNodeWithText("思考过程", substring = false).assertExists()
        // 卡片形态的标题带字数（`思考过程 · N 字`），这里必须一个都没有。
        assertEquals(
            0,
            compose.onAllNodesWithText("思考过程 · ", substring = true).count(),
        )
        // 折叠态的行式思考显示首行预览。
        compose.onNodeWithText("先读一遍文件，再决定怎么改。", substring = true).assertExists()
    }

    @Test
    fun `正文与思考并存的成员仍是卡片`() {
        // 用户原话的后半句：「没有被折叠的思考过程保持原有的样式即可」。
        // 模型边想边说（`[reasoning, text]`）时思考是这条回复的一部分，
        // 拆成一行会把一条回复劈开——这条守住那个边界。
        val members = listOf(
            ChatItem.Assistant(
                seq = 1, time = 0L, turn = 1L, step = 1L,
                blocks = listOf(
                    ContentBlock.Reasoning("我在想。"),
                    ContentBlock.Text("这是正文。"),
                ),
            ),
        )
        // 🔴 必须真的组合 `GroupRow`（R029 审查 P3-2）。
        //
        // 首版这条测试只对 `visibleAssistantBlocks` 断言 `size == 2` 与
        // `all { it is Reasoning } == false`——那等价于重复测一个既有纯函数，
        // 既没组合 UI 也没点开分组。若有人把 `ChatScreen.kt` 的行式判据改成
        // `visible.all { it is Reasoning || it is Text }` 这种错误写法，这条测试照样绿。
        setGroup(members, running = false)
        compose.onNodeWithText("已完成分析").performClick()
        compose.runOnIdle { }

        // 卡片形态：`ReasoningBlock` 的标题带「· N 字」后缀（[ReasoningRow] 的行式 chip
        // 是裸的「思考过程」+ 独立字数徽标，没有这个间隔号）。
        compose.onNodeWithText("思考过程 · 4 字").assertExists()
        // 正文照常渲染。
        compose.onNodeWithText("这是正文。").assertExists()
    }
}
