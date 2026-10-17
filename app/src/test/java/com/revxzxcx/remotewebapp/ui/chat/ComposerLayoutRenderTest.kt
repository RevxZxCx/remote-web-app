package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.revxzxcx.remotewebapp.core.events.ApprovalPolicy
import com.revxzxcx.remotewebapp.core.events.SandboxMode
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
    fun `权限徽标只留图标不再显示策略文字`() {
        // R028 ④（用户 m42063）：「可以将权限只用图标表示，因为目前也不能修改，展示实际的
        // 文字也没有很大的作用，主要起到一个显示的作用」。这条断言是那次改动的守护——
        // 文字一旦回来，底排就又要挤成两行（m42248/m42277 明确不要两行）。
        setComposer(
            draft = "hi",
            permission = SessionPermission(approvalPolicy = ApprovalPolicy.ASK),
        )
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("权限 未知 · 审批 每次询问").assertDoesNotExist()
        compose.onNodeWithText("每次询问").assertDoesNotExist()
    }

    @Test
    fun `策略不同也不影响图标形态`() {
        // `never` 与 `ask` 是两种完全不同的行为，但**图标不表达它**——级别只在点开后的
        // 面板里用文字说明（那里是权威展示位）。这里钉住「图标不随策略变形」，
        // 免得下一个人又想「顺手在图标旁边补两个字」。
        setComposer(
            draft = "hi",
            permission = SessionPermission(approvalPolicy = ApprovalPolicy.NEVER),
        )
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("权限 未知 · 审批 自动允许").assertDoesNotExist()
        compose.onNodeWithText("自动允许").assertDoesNotExist()
    }

    @Test
    fun `没有待批请求时不显示角标`() {
        // 全 null 是「会话日志里没有任何权限事件」的正常状态。没有待批时**不许**有数字——
        // 一个常驻的「0」会让用户以为需要处理什么。
        setComposer(draft = "hi", permission = SessionPermission())
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `有待批请求时角标显示数量`() {
        // 「待批 N」的**数量**不能跟着文字一起砍掉：它是「现在有东西卡住了，去点它」的
        // 唯一信号（R028 ① 之后横幅与抽屉都已经不再承担这件事）。
        compose.setContent {
            PermissionChip(
                permission = SessionPermission(approvalPolicy = ApprovalPolicy.ASK),
                pendingCount = 2,
                onClick = {},
            )
        }
        compose.onNodeWithText("2").assertExists()
    }

    @Test
    fun `策略文字在点开的面板里完整保留`() {
        // 图标化的**唯一信息损失**是级别默认不可见，所以面板必须真的把它写出来——
        // 否则「只留图标」就从「省宽度」变成了「丢信息」。
        compose.setContent {
            PermissionSheet(
                permission = SessionPermission(approvalPolicy = ApprovalPolicy.ASK),
                pendingCount = 0,
                error = null,
                onDismissRequest = {},
            )
        }
        compose.onNodeWithText("权限 未知 · 审批 每次询问").assertExists()
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
        options: List<AskOption> = listOf(AskOption("Postgres", "关系型"), AskOption("SQLite", "单文件")),
    ) = AskQuestion(
        id = id,
        question = text,
        options = options,
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

    // ---- 交互窗口：提问的「其他答案」钉住区（R025 UI 层 P1-1 / P1-2）----
    //
    // 真机缺陷：选项多于 4 个时正文滚动区占满 220dp，「其他答案（可留空）」被滚出视野，
    // 用户根本不知道要往下滚。修法把它从滚动内容挪到 `ComposerShell` 的 `pinned` 槽。
    // 这三条用例就是那道修复的守护：存在性 / 在滚动区外 / 输入真的送到回调。

    /** 自定义答案框必须一直在（用户 m29539：「也可以点击输入回答选项」）。 */
    @Test
    fun `自定义答案框一直在提问窗口里`() {
        setInteractive(questionRequest())
        compose.onNodeWithText("其他答案（可留空）").assertExists()
    }

    /** 选项多到把滚动区占满时，自定义答案框仍要在滚动区**下面**（= 钉住，不跟着滚）。 */
    @Test
    fun `自定义答案框钉在滚动区外`() {
        val many = (1..6).map { AskOption("选项 $it", "说明 $it") }
        setInteractive(questionRequest(questions = listOf(question(options = many))))
        compose.onNodeWithText("选项 6").assertExists()

        val root = compose.onRoot().getBoundsInRoot()
        val scroll = compose.onNodeWithText("用哪个数据库？").getBoundsInRoot()
        val pinned = compose.onNodeWithText("其他答案（可留空）").getBoundsInRoot()
        // 在滚动内容之后（纵向位置更低），且完整落在窗口内。
        assertTrue(
            "自定义答案框顶边 ${pinned.top} 没有低于题目 ${scroll.top}，说明它还在滚动区里",
            pinned.top >= scroll.top,
        )
        assertTrue("自定义答案框被裁掉", pinned.bottom <= root.bottom)
    }

    /** 打字必须真的送到 `onCustomAnswer`，且带上 callId 与题目 id。 */
    @Test
    fun `自定义答案输入会交出 callId 与题目 id`() {
        val seen = mutableListOf<Triple<String, String, String>>()
        setInteractive(
            questionRequest(callId = "call-7", questions = listOf(question(id = "q-9"))),
            onCustom = { callId, question, value -> seen += Triple(callId, question.id, value) },
        )
        compose.onNodeWithText("其他答案（可留空）").performTextInput("用 Postgres")
        compose.waitForIdle()
        assertEquals(listOf(Triple("call-7", "q-9", "用 Postgres")), seen)
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

    // ---- R028 ④ 底排：四件同一排（用户 m42277 定案）----
    //
    // 用户 m41448 第 4 条原话：「添加文件按钮和发送按钮放在输入框内，三者合为一体…
    // 各种操作按钮排布在输入框的最下面一排」。m42063 又把权限降成纯图标，
    // m42277 据此确认「按你的方向、第一个口径」= 权限图标 + 模型胶囊 + ＋ + 发送
    // **同一排**。这条是那次定案的几何守护——「排布在最下面一排」不是修辞，
    // 四件的纵向中心必须对齐。

    /** 展开态的四个入口必须在同一排（纵向中心对齐到 2dp 内）。 */
    @Test
    fun `展开态底排四件在同一排`() {
        setComposer(draft = "hi")
        val attach = compose.onNodeWithContentDescription("添加附件").getBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送按钮").getBoundsInRoot()
        val permission = compose.onNodeWithContentDescription("审批权限").getBoundsInRoot()
        val model = compose.onNodeWithText("stepfun/step-5-preview").getBoundsInRoot()

        val centers = listOf(
            "添加附件" to (attach.top.value + attach.bottom.value) / 2f,
            "发送按钮" to (send.top.value + send.bottom.value) / 2f,
            "审批权限" to (permission.top.value + permission.bottom.value) / 2f,
            "模型胶囊" to (model.top.value + model.bottom.value) / 2f,
        )
        val max = centers.maxOf { it.second }
        val min = centers.minOf { it.second }
        assertTrue("底排四件不在同一排：${centers.joinToString { "${it.first}=${it.second}" }}", max - min <= 2f)
    }

    /**
     * 收起态只有 ＋ 与发送在框内，权限与模型都不许出现。
     *
     * 用户 m27990 要的「未输入时输入框会收束成一行圆角方框」——配置项一露头就变成两行，
     * 这一条同时守着「收起态不许有附件行 / 提示行 / 底排」三件事。
     */
    @Test
    fun `收起态只有加号与发送在框内`() {
        setComposer()
        compose.onNodeWithContentDescription("添加附件").assertExists()
        compose.onNodeWithContentDescription("发送按钮").assertExists()
        compose.onNodeWithContentDescription("审批权限").assertDoesNotExist()
        compose.onNodeWithText("stepfun/step-5-preview").assertDoesNotExist()
    }

    /** 收起态里 ＋、发送、占位符三件的纵向中心也要对齐（用户要的「三者合为一体」）。 */
    @Test
    fun `收起态三件在同一行`() {
        setComposer()
        val attach = compose.onNodeWithContentDescription("添加附件").getBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送按钮").getBoundsInRoot()
        val placeholder = compose.onNodeWithText("描述你的任务，或先聊几句…").getBoundsInRoot()
        val centers = listOf(
            (attach.top.value + attach.bottom.value) / 2f,
            (send.top.value + send.bottom.value) / 2f,
            (placeholder.top.value + placeholder.bottom.value) / 2f,
        )
        assertTrue("收起态三件不在同一行：$centers", centers.max() - centers.min() <= 2f)
    }

    /**
     * 生成中发一条排队消息：占位符要换成能解释「现在发会怎样」的文案。
     *
     * 这条守住 R028 ④ 重构时的**回归**：把输入区从 `OutlinedTextField` 换成
     * `TextField` + 透明指示线时，占位符的分派很容易被漏在旧函数里。
     */
    @Test
    fun `生成中占位符在收起态也正确`() {
        setComposer(generating = true)
        compose.onNodeWithText("描述你的任务，或先聊几句…").assertExists()
    }

    /**
     * 点输入框必须真的把它展开，而且**展开之后不许自己缩回去**。
     *
     * 这条是本轮真机验收抓到的缺陷的守护（R028 ④）：早先的写法是
     * `if (!expanded) { Row{＋,输入框,发送} } else { 输入框 }`——输入框在两个分支里
     * 是两个不同的调用点，Compose 视作**不同节点**。于是点一下：聚焦 → `expanded` 变真
     * → 重建出没有焦点的新节点 → `collectIsFocusedAsState` 立刻变假 → `expanded` 变假 →
     * 回到原分支。用户的观感是「点输入框毫无反应、永远展不开」，而当时 18 条渲染测试
     * 全绿——因为它们都用 `draft`/`pending` 把界面直接摆到展开态，没有一条走**交互**
     * 这条路。所以这一条必须靠点击，不能靠传参。
     *
     * 判据用配置行（展开态独有）而不是输入框本身：输入框两态都在，它出现不能说明任何事。
     */
    @Test
    fun `点输入框会展开并且不会自己缩回去`() {
        setComposer()
        compose.onNodeWithText("描述你的任务，或先聊几句…").performClick()
        compose.waitForIdle()
        // 展开态的证据：配置行（权限图标 + 模型胶囊）出现。
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("stepfun/step-5-preview").assertExists()
        // 再等一次空闲：如果展开态会因焦点丢失而反弹，这一步之后配置行就消失了。
        compose.waitForIdle()
        compose.onNodeWithContentDescription("审批权限").assertExists()
    }

    // ---- R028b（用户 m44550）：＋ 在最左，权限按钮按权限换图标 ----

    /**
     * 用户 m44550 原话：「将加号按钮移动到最左边，这样显得整体更为协调一些」。
     *
     * 判据用横坐标比较而不是写死 dp：底排左右内边距将来可能微调，
     * 但「＋ 在权限徽标左边、且是这一排最左的那个」是不变量。
     */
    @Test
    fun `展开态加号在最左边`() {
        setComposer(draft = "hi")
        val attach = compose.onNodeWithContentDescription("添加附件").getBoundsInRoot()
        val permission = compose.onNodeWithContentDescription("审批权限").getBoundsInRoot()
        val model = compose.onNodeWithText("stepfun/step-5-preview").getBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送按钮").getBoundsInRoot()

        assertTrue(
            "加号 (${attach.left.value}) 必须在权限徽标 (${permission.left.value}) 左边",
            attach.left.value < permission.left.value,
        )
        assertTrue(
            "加号 (${attach.left.value}) 必须在模型胶囊 (${model.left.value}) 左边",
            attach.left.value < model.left.value,
        )
        assertTrue(
            "加号 (${attach.left.value}) 必须在发送键 (${send.left.value}) 左边",
            attach.left.value < send.left.value,
        )
    }

    /** ＋ 与发送必须分居两端（中间是两个只读徽标），否则「最左边」只是把拥挤挪了个位置。 */
    @Test
    fun `发送键仍在最右边`() {
        setComposer(draft = "hi")
        val model = compose.onNodeWithText("stepfun/step-5-preview").getBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送按钮").getBoundsInRoot()
        assertTrue(
            "发送键 (${send.left.value}) 必须在模型胶囊 (${model.left.value}) 右边",
            send.left.value > model.left.value,
        )
    }

    /**
     * 用户 m44550 原话：「对于权限按钮，我不希望只显示一个锁的图标，而是不同权限情况下，
     * 有不同的图标」。三种沙箱模式必须给出三个**不同**的图标。
     *
     * 这条断言 `permissionIcon` 这个纯函数而不是渲染结果：渲染层只能看到像素，
     * 看不到「换没换图标」；纯函数比 `ImageVector` 更快也更准。
     */
    @Test
    fun `三种沙箱模式给出三个不同的权限图标`() {
        val icons = listOf(
            SessionPermission(sandboxMode = SandboxMode.READ_ONLY),
            SessionPermission(sandboxMode = SandboxMode.WORKSPACE_WRITE),
            SessionPermission(sandboxMode = SandboxMode.DANGER_FULL_ACCESS),
        ).map { permissionIcon(it).icon }
        assertEquals(
            "三种沙箱模式必须给出三个不同图标，实际只有 ${icons.toSet().size} 种",
            3,
            icons.toSet().size,
        )
    }

    /** 未知权限必须落到保守图标（锁），不能落到「完全权限」那个警告图标。 */
    @Test
    fun `未知权限回落到锁图标`() {
        val unknown = permissionIcon(SessionPermission())
        val danger = permissionIcon(SessionPermission(sandboxMode = SandboxMode.DANGER_FULL_ACCESS))
        assertEquals("权限未知", SessionPermission().summary())
        assertTrue("未知权限不该显示成完全权限", unknown.icon !== danger.icon)
    }

    /** `permission/preset` 是另一条写入路径：只要 preset 在，图标也要跟着走。 */
    @Test
    fun `只有 preset 时图标也跟着沙箱模式走`() {
        val byMode = permissionIcon(SessionPermission(sandboxMode = SandboxMode.WORKSPACE_WRITE))
        val byPreset = permissionIcon(SessionPermission(preset = SandboxMode.WORKSPACE_WRITE))
        assertEquals(byMode.icon, byPreset.icon)
    }

    /** 审批策略「自动允许」时高亮，与「每次询问」区分开（颜色承担第二维）。 */
    @Test
    fun `自动允许时权限图标要高亮`() {
        assertTrue(
            permissionIcon(SessionPermission(approvalPolicy = ApprovalPolicy.NEVER)).autoApprove,
        )
        assertTrue(
            !permissionIcon(SessionPermission(approvalPolicy = ApprovalPolicy.ASK)).autoApprove,
        )
    }
}
