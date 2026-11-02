package com.revxzxcx.remotewebapp.ui.home

import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.AgentPreset
import com.revxzxcx.remotewebapp.core.session.CreatedSession
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 新增会话页的状态机（R055）。
 *
 * 为什么值得测这么细：这一页只有「填草稿 → 点发送 → 建会话 → 跳走」四步，但每一步都有
 * 一条会导致**桌面端留下空会话**的错法（重复建、建完再建、失败当成功）。空会话会永久
 * 留在 `session/list` 里，用户没法从手机端删掉它（服务端没有删除 API）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val ws1 = Workspace(workspaceId = "w1", title = "项目甲")
    private val ws2 = Workspace(workspaceId = "w2", title = "项目乙")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        create: suspend (String?, AgentPreset) -> CreatedSession = { _, p ->
            CreatedSession("session-1", p)
        },
        preset: AgentPreset = AgentPreset.DEFAULT,
        remember: ((AgentPreset) -> Unit)? = null,
    ) = NewSessionViewModel(create, preset, remember)

    // ── 发送闸门 ────────────────────────────────────────────────────────

    @Test
    fun `空草稿不能发`() {
        val model = vm()
        model.onDraftChange("   ")
        assertEquals(NewSessionSendGate.EmptyDraft, newSessionSendGate(model.state.value))
        model.send()
        assertFalse("点发送也不该进入创建态", model.state.value.creating)
    }

    @Test
    fun `创建中重复点发送只有一条请求`() = runTest(dispatcher) {
        var calls = 0
        val model = vm(create = { _, p -> calls++; CreatedSession("session-1", p) })
        model.onDraftChange("hello")
        model.send()
        // 还没跑完，此刻再点一次必须被闸门挡住。
        assertEquals(NewSessionSendGate.Busy, newSessionSendGate(model.state.value))
        model.send()
        advanceUntilIdle()
        assertEquals("只能建一条", 1, calls)
    }

    @Test
    fun `建完之后再点发送不会建第二条空会话`() = runTest(dispatcher) {
        var calls = 0
        val model = vm(create = { _, p -> calls++; CreatedSession("session-1", p) })
        model.onDraftChange("hello")
        model.send()
        advanceUntilIdle()
        assertEquals(NewSessionSendGate.AlreadyCreated, newSessionSendGate(model.state.value))
        model.send()
        model.send()
        advanceUntilIdle()
        assertEquals("只能建一条", 1, calls)
    }

    // ── 成功路径 ────────────────────────────────────────────────────────

    @Test
    fun `成功后带回 sessionId 与当前档位`() = runTest(dispatcher) {
        val model = vm()
        model.onDraftChange("hello")
        model.selectPreset(AgentPreset.PTC)
        model.send()
        advanceUntilIdle()
        val created = model.state.value.created
        assertEquals("session-1", created?.sessionId)
        assertEquals(AgentPreset.PTC, created?.agentPreset)
        assertFalse(model.state.value.creating)
    }

    @Test
    fun `建完了才通知记住档位`() = runTest(dispatcher) {
        val remembered = mutableListOf<AgentPreset>()
        val model = vm(remember = { remembered += it })
        model.onDraftChange("hi")
        model.selectPreset(AgentPreset.MINIMAL)
        // 选的时候记一次（见 selectPreset 的 KDoc），此刻还没 send。
        assertEquals(listOf(AgentPreset.MINIMAL), remembered)
        model.send()
        advanceUntilIdle()
        // 发送成功后再记一次，值相同，不引入新档位。
        assertTrue(remembered.all { it == AgentPreset.MINIMAL })
    }

    @Test
    fun `选的档位原样传给创建请求`() = runTest(dispatcher) {
        var seenPreset: AgentPreset? = null
        var seenWorkspace: String? = "unset"
        val model = vm(
            create = { ws, preset ->
                seenPreset = preset
                seenWorkspace = ws
                CreatedSession("session-1", preset)
            },
        )
        model.onDraftChange("hi")
        model.selectWorkspace("ws-1")
        model.selectPreset(AgentPreset.CORDIS)
        model.send()
        advanceUntilIdle()
        assertEquals(AgentPreset.CORDIS, seenPreset)
        assertEquals("ws-1", seenWorkspace)
    }

    @Test
    fun `不选工作区时传 null 让服务端落默认值`() = runTest(dispatcher) {
        var seenWorkspace: String? = "unset"
        val model = vm(
            create = { ws, preset ->
                seenWorkspace = ws
                CreatedSession("session-1", preset)
            },
        )
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        assertNull("必须是 null 而不是空串", seenWorkspace)
    }

    // ── 失败路径 ────────────────────────────────────────────────────────

    @Test
    fun `失败时草稿必须原样保留`() = runTest(dispatcher) {
        val model = vm(create = { _, _ -> throw TypertException("transport", "挂了") })
        model.onDraftChange("这段字不能丢")
        model.send()
        advanceUntilIdle()
        assertEquals("这段字不能丢", model.state.value.draft)
        assertNull(model.state.value.created)
        assertFalse(model.state.value.creating)
    }

    @Test
    fun `凭证失效要说去重新配对 而不是重试`() = runTest(dispatcher) {
        val model = vm(create = { _, _ -> throw TypertException("unpaired", "unpaired") })
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        val error = model.state.value.error ?: error("必须给错误")
        assertTrue("实际是：$error", error.contains("重新配对"))
    }

    @Test
    fun `选错档位要说换一个 而不是怪网络`() = runTest(dispatcher) {
        val model = vm(
            create = { _, _ ->
                throw TypertException("agent-preset/not-found", "Unknown agent preset: bogus")
            },
        )
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        val error = model.state.value.error ?: error("必须给错误")
        assertTrue("实际是：$error", error.contains("模式"))
        assertFalse("不该甩出服务端原文", error.contains("bogus"))
    }

    @Test
    fun `工作区没了要说换一个工作区`() {
        val msg = describeCreateFailure(TypertException("workspace/not-found", "not found"))
        assertTrue(msg, msg.contains("工作区"))
    }

    @Test
    fun `网络不通要说检查网络`() {
        val msg = describeCreateFailure(TypertException(TypertException.CODE_TRANSPORT, "timeout"))
        assertTrue(msg, msg.contains("网络"))
    }

    @Test
    fun `坏响应不说网络问题 说版本不匹配`() {
        val msg = describeCreateFailure(TypertException(TypertException.CODE_BAD_ENVELOPE, "x"))
        assertTrue(msg, msg.contains("版本"))
    }

    @Test
    fun `未知异常退回它自己的消息 不能吞成空串`() {
        val msg = describeCreateFailure(IllegalStateException("奇怪的问题"))
        assertEquals("奇怪的问题", msg)
    }

    @Test
    fun `失败后还能再发一次 错误会被清掉`() = runTest(dispatcher) {
        var attempt = 0
        val model = vm(
            create = { _, p ->
                attempt++
                if (attempt == 1) throw TypertException("transport", "第一次失败")
                CreatedSession("session-2", p)
            },
        )
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        assertTrue(model.state.value.error != null)

        model.send()
        advanceUntilIdle()
        assertNull("第二次成功后错误要清掉", model.state.value.error)
        assertEquals("session-2", model.state.value.created?.sessionId)
    }

    @Test
    fun `dismissError 只清错误 不动草稿`() {
        val model = vm()
        model.onDraftChange("保留我")
        model.dismissError()
        assertEquals("保留我", model.state.value.draft)
    }

    @Test
    fun `clearCreated 之后回到可再发状态`() = runTest(dispatcher) {
        val model = vm()
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        assertTrue(model.state.value.created != null)
        model.clearCreated()
        assertNull(model.state.value.created)
        // 错误必须一起清掉：留着它的话，页面被复用（同一个 key）时会把上一轮的
        // 失败横幅带到下一次进入，用户会以为这次也失败了。
        assertNull("clearCreated 必须连错误一起清掉", model.state.value.error)
        // 草稿一起清：这段字已经交给对话页发出去了，留在本页会让用户下次进来
        // 看到上一轮的旧内容，很可能直接点发送、把同一句话又建一个新会话。
        assertEquals("clearCreated 必须连草稿一起清", "", model.state.value.draft)
    }

    @Test
    fun `交出去的草稿不能再留在新建页 否则下次进来会看到幽灵内容`() = runTest(dispatcher) {
        // 这条用例独立于上一条，是为了在真机验收发现「旧内容还在」时能精确指认：
        // 上一轮 `clearCreated` 只清 created 不清 draft，真机第二次进页面时输入框里
        // 还留着上次那句 `aaa`。
        val model = vm()
        model.onDraftChange("上一轮说的话")
        model.send()
        advanceUntilIdle()
        model.clearCreated()
        assertEquals("", model.state.value.draft)
        // 清完之后能正常再打一句新的。
        model.onDraftChange("这一轮的话")
        assertEquals("这一轮的话", model.state.value.draft)
    }

    // ── 创建中不许改 ────────────────────────────────────────────────────

    @Test
    fun `工作区列表到位后目标 id 落到第一项 与胶囊显示一致`() {
        val model = vm()
        assertNull("一开始还没拿到列表，不指定", model.state.value.workspaceId)
        model.updateWorkspaces(listOf(ws1, ws2))
        assertEquals("胶囊此时显示第一项，发送也必须用它", "w1", model.state.value.workspaceId)
    }

    @Test
    fun `用户点过某一台后 列表刷新不能把它改回第一项`() {
        val model = vm()
        model.updateWorkspaces(listOf(ws1, ws2))
        model.selectWorkspace("w2")
        model.updateWorkspaces(listOf(ws1, ws2))
        assertEquals("用户的选择优先于默认", "w2", model.state.value.workspaceId)
    }

    @Test
    fun `点过的工作区被删掉后 目标退回不指定 而不是偷偷换成第一项`() {
        val model = vm()
        model.updateWorkspaces(listOf(ws1, ws2))
        model.selectWorkspace("w2")
        model.updateWorkspaces(listOf(ws1))
        assertNull(
            "绝不把会话建到用户没选的那台",
            model.state.value.workspaceId,
        )
    }

    @Test
    fun `创建中列表刷新不改目标 免得和在途请求打架`() = runTest(dispatcher) {
        // create 挂起不返回 —— 请求一直「在途」，期间列表刷新必须被忽略。
        val model = vm(create = { _, _ -> awaitCancellation() })
        model.updateWorkspaces(listOf(ws1, ws2))
        assertEquals("w1", model.state.value.workspaceId)
        model.onDraftChange("hi")
        model.send()
        model.updateWorkspaces(listOf(ws2))
        assertEquals("创建期间目标是冻结的", "w1", model.state.value.workspaceId)
    }

    @Test
    fun `协程被取消时不能当成创建失败 取消要原样上抛`() = runTest(dispatcher) {
        // 换主机 / 退出页面会让 viewModelScope 被取消。若把 CancellationException
        // 当普通异常吞掉，就会写一个 `error = ...` 到一个已经没人看的 ViewModel 上，
        // 更糟的是 `creating` 的复位走的是 catch 分支——真实取消路径上那次写盘没有意义，
        // 而且会把「用户主动取消」显示成「创建失败」。
        val model = vm(create = { _, _ -> throw kotlinx.coroutines.CancellationException("换主机") })
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        assertNull("取消不是失败，不该写错误", model.state.value.error)
        assertNull("取消也不是成功", model.state.value.created)
    }

    @Test
    fun `建完之后列表刷新不改目标 否则胶囊会在跳走前闪成未选择`() = runTest(dispatcher) {
        // 建好之后 created != null，页面正在跳走。此时列表刷新若被接受，胶囊会从
        // 「1」变成「未选择」——用户会看到目标在最后一刻被改掉。而且 create 已经发出去，
        // 改 state 只会让显示与真正建出来的会话对不上。
        val model = vm()
        model.updateWorkspaces(listOf(ws1, ws2))
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        assertNotNull("前提：会话已经建好了", model.state.value.created)
        model.updateWorkspaces(listOf(ws2))
        assertEquals("建完之后目标是冻结的", "w1", model.state.value.workspaceId)
    }

    @Test
    fun `重复选同一个档位不重复记偏好 别把偏好表当日志写`() {
        val remembered = mutableListOf<AgentPreset>()
        val model = vm(remember = { remembered += it })
        model.selectPreset(AgentPreset.CORDIS)
        model.selectPreset(AgentPreset.CORDIS)
        model.selectPreset(AgentPreset.CORDIS)
        assertEquals("同一档位选三次只该记一次", 1, remembered.size)
        model.selectPreset(AgentPreset.PTC)
        assertEquals("换档位才再记一次", 2, remembered.size)
        assertEquals(AgentPreset.PTC, remembered.last())
    }

    @Test
    fun `创建中改草稿与换档位都无效 避免请求与界面不一致`() = runTest(dispatcher) {
        val model = vm()
        model.onDraftChange("原始")
        model.send()
        model.onDraftChange("被改了")
        model.selectPreset(AgentPreset.CORDIS)
        assertEquals("原始", model.state.value.draft)
        assertEquals(AgentPreset.DEFAULT, model.state.value.preset)
        advanceUntilIdle()
    }
}

/** 工作区胶囊的文案（R055 原型第 2 节 / 第 4 节的两个边界）。 */
class WorkspaceChipLabelTest {

    private fun ws(id: String, title: String) = Workspace(workspaceId = id, title = title)

    @Test
    fun `选了哪台就显示哪台`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        assertEquals("项目乙", workspaceChipLabel("b", list))
    }

    @Test
    fun `没选时显示列表第一项 而不是空白`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        assertEquals("项目甲", workspaceChipLabel(null, list))
    }

    @Test
    fun `零工作区显示无工作区 但仍然可以发`() {
        assertEquals("无工作区", workspaceChipLabel(null, emptyList()))
        assertEquals("无工作区", workspaceChipLabel("a", emptyList()))
    }

    @Test
    fun `选了一个已经不在列表里的 id 时不能崩 退回未选择`() {
        val list = listOf(ws("a", "项目甲"))
        assertEquals("未选择", workspaceChipLabel("gone", list))
    }

    @Test
    fun `标题为空时退回路径最后一段 而不是显示空胶囊`() {
        val list = listOf(Workspace(workspaceId = "a", path = "C:\\work\\my-proj", title = ""))
        assertEquals("my-proj", workspaceChipLabel("a", list))
    }
}


/**
 * 「显示哪台」和「建到哪台」必须是同一台。
 *
 * R055 真机验收暴露的真缺陷：胶囊在「没选过」时显示列表第一项，发送却带着
 * `workspaceId = null` 出去，服务端落到默认工作区——会话建到了上级目录，
 * 于是在工作区「1」的列表里找不到它。根因是同一个问题被写成了两份推断：
 * 胶囊自己 `firstOrNull()`，发送用 `state.workspaceId`。
 */
class ResolvedWorkspaceTest {

    private fun ws(id: String, title: String) = Workspace(workspaceId = id, title = title)

    @Test
    fun `没选过时 发送的目标就是胶囊显示的第一项`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        assertEquals("胶囊显示的是这一项", "项目甲", workspaceChipLabel(null, list))
        assertEquals("发送也必须用它，否则显示与动作各说各话", "a", resolvedWorkspaceId(null, list))
    }

    @Test
    fun `点过某一台时 发送的是那一台 不是第一项`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        assertEquals("b", resolvedWorkspaceId("b", list))
        assertEquals("项目乙", workspaceChipLabel("b", list))
    }

    @Test
    fun `列表为空时不指定工作区`() {
        assertNull(resolvedWorkspaceId(null, emptyList()))
        assertEquals("无工作区", workspaceChipLabel(null, emptyList()))
    }

    @Test
    fun `创建中且目标还没定下时 胶囊如实说默认工作区 不冒充第一项`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        // 列表在请求发出之后才回来：updateWorkspaces 此时不改目标，请求带的就是 null，
        // 会话真的会建到服务端默认工作区。胶囊必须说「默认工作区」。
        assertEquals("默认工作区", workspaceChipLabel(null, list, creating = true))
    }

    @Test
    fun `没在创建时 胶囊仍然拿第一项作默认 不能让用户以为没选`() {
        val list = listOf(ws("a", "项目甲"), ws("b", "项目乙"))
        assertEquals("项目甲", workspaceChipLabel(null, list, creating = false))
    }

    @Test
    fun `选过的 id 已经不在列表里 就退回不指定 而不是拿第一项顶替`() {
        val list = listOf(ws("a", "项目甲"))
        assertNull(
            "工作区被删了就退回默认，绝不把会话建到用户没选的地方",
            resolvedWorkspaceId("gone", list),
        )
        assertEquals("未选择", workspaceChipLabel("gone", list))
    }
}
