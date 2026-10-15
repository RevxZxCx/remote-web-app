package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.events.ApprovalPolicy
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.SessionPermission
import com.revxzxcx.remotewebapp.core.session.ModelRef
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import org.junit.Assert.assertEquals
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
 * ⑤ 权限徽标要说清当前策略（`不问直接放行` / `每次要批准`），这是用户
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
        permission: SessionPermission = SessionPermission(approvalPolicy = ApprovalPolicy.NEVER),
        model: ModelRef? = ModelRef("stepfun", "step-5-preview"),
        onDraftChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onRemove: (Int) -> Unit = {},
        onOpenPermissions: () -> Unit = {},
    ) {
        compose.setContent {
            Composer(
                draft = draft,
                generating = generating,
                sending = sending,
                pending = pending,
                attachNotice = attachNotice,
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
        compose.onNodeWithText("每次要批准").assertExists()
    }

    @Test
    fun `自动放行策略写成不同文案`() {
        // `never` 与 `ask` 是同一枚徽标上的两种完全不同的行为，文案必须能区分。
        setComposer(
            draft = "hi",
            permission = SessionPermission(approvalPolicy = ApprovalPolicy.NEVER),
        )
        compose.onNodeWithText("不问直接放行").assertExists()
        compose.onNodeWithText("每次要批准").assertDoesNotExist()
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

    private fun request(
        id: String,
        tool: String = "pwsh",
        callId: String? = "call-1",
        reason: String? = null,
    ) = ApprovalRequest(
        eventId = id,
        agentId = "agent-1",
        toolName = tool,
        callId = callId,
        reason = reason,
    )

    private fun setBanner(
        approvals: List<ApprovalRequest>,
        onAllow: (String) -> Unit = {},
        onReject: (String) -> Unit = {},
    ) {
        compose.setContent {
            ApprovalBanner(approvals = approvals, onAllow = onAllow, onReject = onReject)
        }
    }

    @Test
    fun `没有待审批请求时卡片不占位`() {
        setBanner(approvals = emptyList())
        compose.onNodeWithText("桌面端在等你授权").assertDoesNotExist()
        compose.onNodeWithText("允许一次").assertDoesNotExist()
    }

    @Test
    fun `有待审批请求时卡片列出工具名`() {
        setBanner(approvals = listOf(request("e-1")))
        compose.onNodeWithText("桌面端在等你授权（1）").assertExists()
        compose.onNodeWithText("pwsh").assertExists()
    }

    @Test
    fun `点允许交出的是那一条 eventId`() {
        val allowed = mutableListOf<String>()
        setBanner(
            approvals = listOf(request("e-1"), request("e-2", tool = "write")),
            onAllow = { allowed += it },
        )
        // 两条请求各有自己的按钮，按文案取第一条（pwsh）的那一颗。
        compose.onAllNodesWithText("允许一次")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("e-1"), allowed)
    }

    @Test
    fun `两条请求时两条都渲染出来`() {
        setBanner(approvals = listOf(request("e-1"), request("e-2", tool = "write")))
        compose.onNodeWithText("pwsh").assertExists()
        compose.onNodeWithText("write").assertExists()
        assertEquals(2, compose.onAllNodesWithText("允许一次").fetchSemanticsNodes().size)
    }

    @Test
    fun `拒绝按钮交出的是那一条 eventId`() {
        val rejected = mutableListOf<String>()
        setBanner(approvals = listOf(request("e-9")), onReject = { rejected += it })
        compose.onNodeWithText("拒绝").performClick()
        compose.waitForIdle()
        assertEquals(listOf("e-9"), rejected)
    }

    @Test
    fun `没有理由时卡片仍可读`() {
        // 服务端 reason / displayReason 都是可选的（契约探针实测 403 里两者皆无）。
        // 缺字段时卡片不能因此崩或变成一行空白——`reasonText()` 有四级回落。
        setBanner(approvals = listOf(request("e-1", reason = null)))
        compose.onNodeWithText("pwsh").assertExists()
        compose.onNodeWithText("工具 pwsh 需要你的授权").assertExists()
    }
}
