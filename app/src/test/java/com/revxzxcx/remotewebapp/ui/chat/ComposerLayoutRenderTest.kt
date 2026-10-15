package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.events.ApprovalPolicy
import com.revxzxcx.remotewebapp.core.events.SessionPermission
import com.revxzxcx.remotewebapp.core.session.AskIntent
import com.revxzxcx.remotewebapp.core.session.AskOption
import com.revxzxcx.remotewebapp.core.session.AskQuestion
import com.revxzxcx.remotewebapp.core.session.InteractiveComposerRequest
import com.revxzxcx.remotewebapp.core.session.ModelRef
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R019（用户 m27990 / m28009）输入区两态 + 审批权限徽标 + 审批卡片的渲染测试。
 *
 * 为什么值得写：这一轮把输入区从「固定高度」改成「收起一行 / 展开一屏」的两态，
 * 并在展开态加了配置行。三条可回归契约**只有渲染层能钉住**：
 *
 * ① 收起态不能出现配置行。配置行一露头，单行输入框就变成两行，用户 m27990 要的
 *    「未输入时自动收起呈整体一行」直接不算数。
 * ② 展开判据是「焦点 / 草稿 / 附件 / 附件提示」，**不含键盘高度**。MuMu + 搜狗的
 *    ime 窗口高度实测恒 0，拿 insets 当判据会让「点了输入框却没展开」。
 * ③ 审批卡片只在该有待审批请求时出现；没有请求时它不许占高度。
 *
 * 另外两条来自审批链路本身：
 * ④ 有请求时「允许」必须把**那条** eventId 交出去，同时允许列表里剩下的还在；
 * ⑤ 权限徽标要说清当前策略（`每次询问` / `自动允许`），这是用户
 *    m28009「会话审批权限」唯一能在输入区看见的常驻反馈。
 *
 * **踩坑记录**：`SessionPermission` 是三个独立字段（sandboxMode / approvalPolicy /
 * preset）+ asOfSeq，不是一个枚举。桌面端有三条互不相同的写入路径（`permission/preset`、
 * `sandbox/mode`、`approval/policy`），合成一个「档位」在三条路径交叉时必然与桌面端
 * 显示不一致——见 `core/events/RemoteEvents.kt` 的 KDoc。测试按枚举写会编译不过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerLayoutRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val image = PromptAttachment(
        kind = PromptAttachment.Kind.IMAGE,
        name = "shot.png",
        mediaType = "image/png",
        data = "AAAA",
    )

    private fun setComposer(
        draft: String = "",
        generating: Boolean = false,
        sending: Boolean = false,
        pending: List<PromptAttachment> = emptyList(),
        attachNotice: String? = null,
        interactive: InteractiveComposerRequest? = null,
        interactiveError: String? = null,
        permission: SessionPermission = SessionPermission(approvalPolicy = ApprovalPolicy.NEVER),
        model: ModelRef? = ModelRef("stepfun", "step-5-preview"),
        onDraftChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onRemove: (Int) -> Unit = {},
        onOpenPermissions: () -> Unit = {},
        onAnswerApproval: (String, Boolean) -> Unit = { _, _ -> },
        onToggleOption: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
        onCustomAnswer: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
        onSubmitQuestions: (String) -> Unit = {},
        onSkipQuestions: (String) -> Unit = {},
    ) {
        compose.setContent {
            Composer(
                draft = draft,
                generating = generating,
                sending = sending,
                pending = pending,
                attachNotice = attachNotice,
                interactive = interactive,
                interactiveError = interactiveError,
                modelChip = {
                    ModelBar(
                        model = model,
                        loading = false,
                        error = null,
                        onOpen = {},
                    )
                },
                permissionChip = {
                    PermissionChip(
                        permission = permission,
                        pendingCount = 0,
                        onClick = onOpenPermissions,
                    )
                },
                onAnswerApproval = onAnswerApproval,
                onToggleQuestionOption = onToggleOption,
                onQuestionCustom = onCustomAnswer,
                onSubmitQuestions = onSubmitQuestions,
                onSkipQuestions = onSkipQuestions,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onRemoveAttachment = onRemove,
                onAttachImage = { _, _, _, _ -> null },
                onAttachFile = { _, _ -> },
                onAttachNotice = {},
            )
        }
    }

    // ---- ① 两态结构 ----

    @Test
    fun `空输入时配置行不出现`() {
        setComposer()
        // 配置行里两个入口的语义都不该存在。用语义判据而不是「数 Row」——
        // Robolectric 下 bounds 退化，数布局节点不可靠。
        compose.onNodeWithContentDescription("审批权限").assertDoesNotExist()
        compose.onNodeWithText("stepfun/step-5-preview").assertDoesNotExist()
    }

    @Test
    fun `草稿非空时配置行出现`() {
        setComposer(draft = "帮我看看这个仓库")
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("stepfun/step-5-preview").assertExists()
    }

    @Test
    fun `有待发送附件时配置行也出现`() {
        // 只带了图、没打字：用户仍需要能改模型与审批权限，否则发出去就是默认策略。
        setComposer(pending = listOf(image))
        compose.onNodeWithContentDescription("审批权限").assertExists()
    }

    @Test
    fun `生成中提示替换占位符`() {
        // 收起态占位符贴近参考图的「描述你的任务，或先聊几句…」；生成中必须换成
        // 能解释「现在发会怎样」的文案，否则用户在排队与立即发送之间误解。
        //
        // 用 `pending` 把输入区推进展开态：Robolectric 里拿不到真实焦点，而展开判据
        // 同时接受「有待发送附件」。草稿留空才能看到占位符（有字时占位符不渲染）。
        setComposer(draft = "", generating = true, pending = listOf(image))
        compose.onNodeWithText("生成中…（发送会排到当前轮之后）").assertExists()
        compose.onNodeWithText("描述你的任务，或先聊几句…").assertDoesNotExist()
    }

    // ---- ② 审批权限徽标 ----

    @Test
    fun `权限徽标显示当前策略`() {
        setComposer(
            draft = "hi",
            permission = SessionPermission(approvalPolicy = ApprovalPolicy.ASK),
        )
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("权限 未知 · 审批 每次询问").assertExists()
    }

    @Test
    fun `自动放行策略写成不同文案`() {
        // `never` 与 `ask` 是同一枚徽标上的两种完全不同的行为，文案必须能区分。
        setComposer(
            draft = "hi",
            permission = SessionPermission(approvalPolicy = ApprovalPolicy.NEVER),
        )
        compose.onNodeWithText("权限 未知 · 审批 自动允许").assertExists()
        compose.onNodeWithText("每次询问").assertDoesNotExist()
    }

    @Test
    fun `一条权限事件都没收到时显示未知`() {
        // 全 null 是「会话日志里没有任何权限事件」的正常状态，不能显示成空白。
        setComposer(draft = "hi", permission = SessionPermission())
        compose.onNodeWithText("权限未知").assertExists()
    }

    @Test
    fun `有待批请求时徽标变成计数`() {
        // 徽标在有待批请求时切到「待批 N」——用户不必展开面板就知道有几件事等着。
        compose.setContent {
            PermissionChip(
                permission = SessionPermission(approvalPolicy = ApprovalPolicy.ASK),
                pendingCount = 2,
                onClick = {},
            )
        }
        compose.onNodeWithText("待批 2").assertExists()
    }

    @Test
    fun `点权限徽标真的打开权限面板`() {
        var opens = 0
        setComposer(draft = "hi", onOpenPermissions = { opens++ })
        compose.onNodeWithContentDescription("审批权限").performClick()
        compose.waitForIdle()
        assertEquals(1, opens)
    }

    // ---- ③ 审批卡片 ----

    private fun setInteractive(
        request: InteractiveComposerRequest?,
        onAnswerApproval: (String, Boolean) -> Unit = { _, _ -> },
        onToggleOption: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
        onCustom: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
        onSubmit: (String) -> Unit = {},
        onSkip: (String) -> Unit = {},
    ) {
        compose.setContent {
            InteractiveWindow(
                request = request,
                error = null,
                onAnswerApproval = onAnswerApproval,
                onToggleOption = onToggleOption,
                onCustomAnswer = onCustom,
                onSubmit = onSubmit,
                onSkip = onSkip,
            )
        }
    }

    // ---- 交互窗口：审批 ----

    @Test
    fun `审批请求占住输入区并给出允许与拒绝`() {
        setInteractive(InteractiveComposerRequest.Approval("e-1", "pwsh", null))
        compose.onNodeWithText("需要授权").assertExists()
        compose.onNodeWithText("pwsh").assertExists()
        compose.onNodeWithText("允许一次").assertExists()
        compose.onNodeWithText("拒绝").assertExists()
    }

    @Test
    fun `点允许交出的是那一条 eventId`() {
        val allowed = mutableListOf<String>()
        setInteractive(
            InteractiveComposerRequest.Approval("e-7", "write", null),
            onAnswerApproval = { id, allow -> if (allow) allowed += id },
        )
        compose.onNodeWithText("允许一次").performClick()
        compose.waitForIdle()
        assertEquals(listOf("e-7"), allowed)
    }

    @Test
    fun `点拒绝交出的是那一条 eventId 且 allow 为假`() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        setInteractive(
            InteractiveComposerRequest.Approval("e-9", "pwsh", null),
            onAnswerApproval = { id, allow -> seen += id to allow },
        )
        compose.onNodeWithText("拒绝").performClick()
        compose.waitForIdle()
        assertEquals(listOf("e-9" to false), seen)
    }

    @Test
    fun `没有理由时审批窗口仍可读`() {
        // 服务端 reason / displayReason 都是可选的（契约探针实测 403 里两者皆无）。
        // 缺字段时窗口不能因此崩或变成一行空白。
        setInteractive(InteractiveComposerRequest.Approval("e-1", "pwsh", null))
        compose.onNodeWithText("pwsh").assertExists()
        compose.onNodeWithText("允许后这一步会立刻执行；拒绝后本轮会停在这里。").assertExists()
    }

    @Test
    fun `服务端给了理由时原样展示`() {
        setInteractive(InteractiveComposerRequest.Approval("e-1", "pwsh", "要删掉一个目录"))
        compose.onNodeWithText("要删掉一个目录").assertExists()
    }

    // ---- 交互窗口：提问 ----

    private fun question(
        id: String = "q-1",
        text: String = "用哪个数据库？",
        multiSelect: Boolean = false,
    ) = AskQuestion(
        id = id,
        question = text,
        options = listOf(AskOption("Postgres", "关系型"), AskOption("SQLite", "单文件")),
        multiSelect = multiSelect,
    )

    private fun questionRequest(
        callId: String = "call-1",
        questions: List<AskQuestion> = listOf(question()),
    ) = InteractiveComposerRequest.Question(callId, questions)

    @Test
    fun `提问窗口一次只显示一道题`() {
        setInteractive(
            questionRequest(questions = listOf(question("q-1", "第一题"), question("q-2", "第二题"))),
        )
        compose.onNodeWithText("第 1 / 2 题").assertExists()
        compose.onNodeWithText("第一题").assertExists()
        compose.onNodeWithText("第二题").assertDoesNotExist()
    }

    @Test
    fun `多选可以累加两个选项`() {
        val toggled = mutableListOf<String>()
        setInteractive(
            questionRequest(questions = listOf(question(multiSelect = true))),
            onToggleOption = { _, _, label -> toggled += label },
        )
        compose.onAllNodesWithText("Postgres")[0].performClick()
        compose.onAllNodesWithText("SQLite")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("Postgres", "SQLite"), toggled)
    }

    @Test
    fun `提交交出的是 callId`() {
        val submitted = mutableListOf<String>()
        setInteractive(questionRequest(callId = "call-42"), onSubmit = { submitted += it })
        compose.onNodeWithText("提交").performClick()
        compose.waitForIdle()
        assertEquals(listOf("call-42"), submitted)
    }

    /**
     * 动作区必须钉在滚动区**外面**。
     *
     * 这条契约差点靠「测试碰巧通过」蒙混过关：R022 P1-2 第一次修「提交按钮被裁掉」时只给
     * 内容包了 `verticalScroll`，按钮仍在滚动区里。内容一超高，按钮就被推到视口下方，
     * 而 Robolectric 的窗口只有 386px 高 —— `performClick` 往窗口外注入触摸，**不报错、
     * 也不触发回调**，两个用例静默失败（`expected:<[call-42]> but was:<[]>`）。
     *
     * 所以这里直接钉几何：动作按钮的底边必须在根节点底边之内。
     */
    @Test
    fun `提交按钮钉在滚动区外且完整可见`() {
        setInteractive(questionRequest(callId = "call-9"))
        val root = compose.onRoot().getBoundsInRoot()
        val submit = compose.onNodeWithText("提交").getBoundsInRoot()
        assertTrue(
            "提交按钮底边 ${submit.bottom} 超出窗口底边 ${root.bottom}，用户点不到",
            submit.bottom <= root.bottom,
        )
        val skip = compose.onNodeWithText("跳过").getBoundsInRoot()
        assertTrue("跳过按钮被裁掉", skip.bottom <= root.bottom)
    }

    /** 审批的两个动作同样必须钉住：允许/拒绝是被动等待的请求，滚下去就永远点不到。 */
    @Test
    fun `审批动作钉在滚动区外且完整可见`() {
        setInteractive(InteractiveComposerRequest.Approval("e-1", "pwsh", "要删掉一个目录"))
        val root = compose.onRoot().getBoundsInRoot()
        val allow = compose.onNodeWithText("允许一次").getBoundsInRoot()
        val reject = compose.onNodeWithText("拒绝").getBoundsInRoot()
        assertTrue("允许按钮被裁掉", allow.bottom <= root.bottom)
        assertTrue("拒绝按钮被裁掉", reject.bottom <= root.bottom)
    }

    @Test
    fun `多题时提交按钮写的是提交整组`() {
        setInteractive(
            questionRequest(questions = listOf(question("q-1"), question("q-2"))),
            onSubmit = {},
        )
        compose.onNodeWithText("提交整组").assertExists()
    }

    @Test
    fun `跳过与提交走不同的回调`() {
        val skipped = mutableListOf<String>()
        setInteractive(questionRequest(callId = "call-3"), onSkip = { skipped += it })
        compose.onNodeWithText("跳过").performClick()
        compose.waitForIdle()
        assertEquals(listOf("call-3"), skipped)
    }

    @Test
    fun `plan-review 换成计划确认文案`() {
        setInteractive(
            questionRequest(
                questions = listOf(
                    question().copy(
                        intent = AskIntent(kind = "plan-review", approve = "按这个计划执行"),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("计划确认").assertExists()
    }

    // ---- Composer 分派 ----

    @Test
    fun `输入区被审批占用时发送按钮让位`() {
        setComposer(
            interactive = InteractiveComposerRequest.Approval("e-1", "pwsh", null),
        )
        compose.onNodeWithContentDescription("发送按钮").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertDoesNotExist()
        compose.onNodeWithText("允许一次").assertExists()
    }

    @Test
    fun `输入区被提问占用时草稿输入框让位`() {
        setComposer(interactive = questionRequest())
        compose.onNodeWithText("需要你回答").assertExists()
        compose.onNodeWithContentDescription("发送按钮").assertDoesNotExist()
    }
}
