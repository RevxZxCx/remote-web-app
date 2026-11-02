package com.revxzxcx.remotewebapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.session.AgentPreset
import com.revxzxcx.remotewebapp.core.session.CreatedSession
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「新增会话」页的状态（R055）。
 *
 * ## 为什么会话在**发送时**才建（Q2）
 *
 * 进页面就建的话，用户按返回、切走、或者只是点错了加号，桌面端就多出一条空会话。
 * `session/list` 会把它列出来（`blank = true`），用户在一堆「无标题」里找自己刚才那条。
 * 发送时建则天然只有「用户真的想说点什么」时才留下痕迹。
 *
 * ## 为什么把创建动作做成回调而不是直接用仓库
 *
 * [NewSessionViewModel] 需要的全部能力就是「给我一个 workspaceId 和档位，建一条，
 * 告诉我 id」。换成仓库实例就要构造 `DshConnection`（OkHttp + 凭据），单测里
 * 造不出来。注入 lambda 后，失败、超时、返回坏形状三条分支都能在纯 JVM 里钉死。
 */
data class NewSessionState(
    /** 输入框内容。 */
    val draft: String = "",
    /**
     * **要建到哪台工作区**的 id；null = 不指定，由服务端落到默认工作区。
     *
     * 它是 [resolvedWorkspaceId] 解析后的结果，不是「用户点过哪个」的原始记录：
     * 列表读回来后由 [NewSessionViewModel.updateWorkspaces] 落到具体 id 上，
     * 这样胶囊显示的名字与实际发出去的 id 永远是同一台。
     *
     * 用 id 而不是 [Workspace] 对象：工作区列表会随 `workspace/follow` 变化，
     * 存对象会在列表刷新后指向一个已经不在列表里的旧实例。
     */
    val workspaceId: String? = null,
    /** 选中的模式。 */
    val preset: AgentPreset = AgentPreset.DEFAULT,
    /** 正在创建。期间发送键禁用、页面显示「正在创建…」。 */
    val creating: Boolean = false,
    /** 创建失败的原因。 */
    val error: String? = null,
    /**
     * 建成功了：带上新会话 id。
     *
     * 非 null 即表示页面该走了，由调用方消费后调 [NewSessionViewModel.clearCreated]。
     * 草稿不塞进这个事件：它一直就在 [draft] 里，页面跳转时把它一起交给对话页即可
     * （`NewSessionScreen` 的 `onCreated(created, state.draft)`）。让同一份数据只存一处，
     * 事件里再抄一份只会多出一个可能与 [draft] 不一致的副本。
     */
    val created: CreatedSession? = null,
)

/** 发送前校验的结果：发送键该不该真的发出请求。 */
sealed interface NewSessionSendGate {
    /** 可以发。 */
    data object Ok : NewSessionSendGate

    /** 内容为空——不是异常，只是把发送键画成不可用。 */
    data object EmptyDraft : NewSessionSendGate

    /** 正在创建中，重复点击必须被忽略。 */
    data object Busy : NewSessionSendGate

    /** 已经建好了，等页面消费跳转，不能再建第二条。 */
    data object AlreadyCreated : NewSessionSendGate
}

/**
 * 发送闸门（纯函数，便于逐分支钉死）。
 *
 * 三个「不发」的理由要分开：`EmptyDraft` 只是按钮置灰、不该弹错；`Busy` 要静默忽略
 * （用户手快点了两下）；`AlreadyCreated` 也必须静默——但它比 `Busy` 更危险，因为此时
 * 第一条已经真的在桌面端建出来了，再发一次就是两条空会话。
 */
internal fun newSessionSendGate(state: NewSessionState): NewSessionSendGate = when {
    state.created != null -> NewSessionSendGate.AlreadyCreated
    state.creating -> NewSessionSendGate.Busy
    state.draft.isBlank() -> NewSessionSendGate.EmptyDraft
    else -> NewSessionSendGate.Ok
}

/**
 * 工作区胶囊的文案（原型第 2 节）。
 *
 * 三种情况分开显示：选中的那台、列表未就绪（空列表）、列表里有东西但没选
 * （取第一项作默认）。**不显示「无工作区」当作错误**——不指定工作区是完全合法的用法，
 * 服务端会落到默认工作区。
 */
internal fun workspaceChipLabel(
    workspaceId: String?,
    workspaces: List<Workspace>,
    creating: Boolean = false,
): String {
    // 创建中且目标还没定（列表在请求发出之后才回来，`updateWorkspaces` 这时不再改目标）：
    // 这次请求带的确实是 `null`，也就是服务端的默认工作区。此时**不能**退回「显示列表
    // 第一项」——那会让胶囊写着「1」而会话实际建在默认工作区，正是 R055 真机验收抓到的
    // 那种「显示与动作各说各话」。
    if (creating && workspaceId == null) {
        return if (workspaces.isEmpty()) "无工作区" else "默认工作区"
    }
    val resolved = resolvedWorkspaceId(workspaceId, workspaces)
    val picked = resolved?.let { id -> workspaces.firstOrNull { it.workspaceId == id } }
    if (picked != null) return picked.displayName
    return if (workspaces.isEmpty()) "无工作区" else "未选择"
}

/**
 * **这一次真的会建到哪台工作区**。
 *
 * 胶囊和发送必须共用这一个解析，否则就会出现「界面写着建到 1、实际建到了服务端默认
 * 工作区」——动作与显示各说各话，用户只能从会话列表里发现自己的会话跑到别处去了。
 *
 * @return 要传给 `session/create` 的 id；`null` 表示不指定（服务端落到默认工作区）。
 *   三种落空的情形都返回 null，因为它们对用户是同一件事：这次没有指定工作区。
 *   - 列表还没读回来（空列表）
 *   - 选过的 id 已经不在列表里（工作区被删了）——这时**不**顺手拿第一项顶替，
 *     那等于把用户的会话建到他没选的地方
 */
internal fun resolvedWorkspaceId(workspaceId: String?, workspaces: List<Workspace>): String? {
    if (workspaceId != null) {
        return if (workspaces.any { it.workspaceId == workspaceId }) workspaceId else null
    }
    return workspaces.firstOrNull()?.workspaceId
}

/**
 * 新增会话页的 ViewModel。
 *
 * @param createSession 建会话。注入而非内部 new 仓库，见类头的说明。
 * @param rememberPreset 记住这次选的档位（按主机分开，见 `PresetPreferences`）。
 *   传 `null` 表示不记忆（测试或「不记忆」场景）。
 */
class NewSessionViewModel(
    private val createSession: suspend (workspaceId: String?, preset: AgentPreset) -> CreatedSession,
    private val initialPreset: AgentPreset = AgentPreset.DEFAULT,
    private val rememberPreset: ((AgentPreset) -> Unit)? = null,
) : ViewModel() {

    // 工作区起点固定是 null（= 还没解析）。进页面时工作区列表通常已经在手，
    // `NewSessionScreen` 的 `LaunchedEffect(workspaces)` 会立刻调 `updateWorkspaces`
    // 把它落到第一台上——只有那一条路径，所以不再留一个没人传的初始值参数。
    private val _state = MutableStateFlow(NewSessionState(preset = initialPreset))

    val state: StateFlow<NewSessionState> = _state.asStateFlow()

    /**
     * 输入框变化。创建中/已创建时忽略——那两个窗口里改草稿没有意义（马上要跳走）。
     *
     * 清空走 [clearCreated]：草稿是「这次要发的话」，交出去之后就不该还留在本页。
     */
    fun onDraftChange(text: String) {
        val current = _state.value
        if (current.creating || current.created != null) return
        _state.value = current.copy(draft = text, error = null)
    }

    /**
     * 工作区列表读回来了（或在流里更新了）。
     *
     * 胶囊在「没选过」时显示列表第一项，所以**发送也必须用第一项**——两边共用
     * [resolvedWorkspaceId]。否则界面写着「1」，实际却带着 `null` 出去，会话会落到
     * 服务端的默认工作区（R055 真机验收就是这么发现的：会话建到了 `/opt/dsh` 而不是
     * 工作区「1」的 `/opt/dsh/1`，于是在「1」的列表里根本找不到它）。
     *
     * 创建中/已创建时不动：那两个窗口里改目标没有意义，而且会和已经在途的请求打架。
     */
    fun updateWorkspaces(workspaces: List<Workspace>) {
        val current = _state.value
        if (current.creating || current.created != null) return
        val resolved = resolvedWorkspaceId(current.workspaceId, workspaces)
        if (resolved != current.workspaceId) {
            _state.value = current.copy(workspaceId = resolved)
        }
    }

    /** 换工作区。 */
    fun selectWorkspace(workspaceId: String?) {
        val current = _state.value
        if (current.creating || current.created != null) return
        _state.value = current.copy(workspaceId = workspaceId)
    }

    /**
     * 换模式。
     *
     * 换的当下就记住：用户选完之后往往会先打字再发，如果等到发送成功才记，
     * 中途返回就白选了。记忆的语义是「我倾向用这个档」，与「这次有没有发出去」无关。
     */
    fun selectPreset(preset: AgentPreset) {
        val current = _state.value
        if (current.creating || current.created != null) return
        if (current.preset == preset) return
        _state.value = current.copy(preset = preset)
        rememberPreset?.invoke(preset)
    }

    /** 清掉错误提示。 */
    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 调用方消费掉 [NewSessionState.created] 之后清空，避免返回时又跳一次。 */
    fun clearCreated() {
        // 草稿也要一起清：这段文字已经交给了对话页并由那边的 send() 发出去，
        // 留在本页会让用户下次进来看到上一轮的旧内容（很可能直接点了发送，
        // 把同一句话又建一个新会话）。`created` 与 `draft` 是一次性的配套状态，
        // 只清一半会留下另一半的幽灵。
        _state.value = _state.value.copy(created = null, draft = "")
    }

    /**
     * 发送：建会话。
     *
     * 失败时把原因写进 [NewSessionState.error]，**草稿原样保留**——用户打了半天字，
     * 失败后清空输入框是不可接受的。
     */
    fun send() {
        when (newSessionSendGate(_state.value)) {
            NewSessionSendGate.EmptyDraft, NewSessionSendGate.Busy, NewSessionSendGate.AlreadyCreated -> return
            NewSessionSendGate.Ok -> Unit
        }
        val current = _state.value
        _state.value = current.copy(creating = true, error = null)
        viewModelScope.launch {
            try {
                val created = createSession(current.workspaceId, current.preset)
                // 草稿不在这里动：它本来就还在 `_state.value.draft` 里，
                // 页面跳转时由 `NewSessionScreen` 连同事件一起交给对话页。
                _state.value = _state.value.copy(
                    creating = false,
                    error = null,
                    created = created,
                )
                rememberPreset?.invoke(current.preset)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消必须原样上抛：VM 被销毁（换主机 / 退出页面）时协程被取消，
                // 那不是「创建失败」，吞掉之后 `_state` 会被写在一个已经没人看的
                // ViewModel 上，`creating` 也可能永远停在 true。
                throw e
            } catch (e: Exception) {
                // 不用 `e.message ?: "..."` 一句话概括：新建会话的失败原因分三类
                // （服务端拒绝 / 网络不通 / 返回坏形状），它们的处置完全不同，
                // 用户至少要能分辨「是我选错档」还是「网断了」。
                _state.value = _state.value.copy(
                    creating = false,
                    error = describeCreateFailure(e),
                )
            }
        }
    }

    companion object {
        fun factory(
            createSession: suspend (String?, AgentPreset) -> CreatedSession,
            initialPreset: AgentPreset = AgentPreset.DEFAULT,
            rememberPreset: ((AgentPreset) -> Unit)? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NewSessionViewModel(
                    createSession = createSession,
                    initialPreset = initialPreset,
                    rememberPreset = rememberPreset,
                )
            }
        }
    }
}

/**
 * 把创建失败翻译成用户能行动的句子。
 *
 * 三个分支对应三种真实处置：
 * - 服务端拒绝（`agent-preset/not-found` / `workspace/not-found`）：换一个再试。
 * - 凭证失效（`unpaired`）：去重新配对，重试没有意义。
 * - 网络/其它：检查连接后重试。
 */
internal fun describeCreateFailure(e: Exception): String {
    val typert = e as? com.revxzxcx.remotewebapp.core.protocol.TypertException
    if (typert != null) {
        return when {
            typert.isUnpaired -> "配对已失效，需要重新配对这台桌面端。"
            typert.code.startsWith("agent-preset/") -> "这台桌面端不支持所选模式，换一个再试。"
            typert.code.startsWith("workspace/") -> "那个工作区已经不在了，换一个再试。"
            typert.code == com.revxzxcx.remotewebapp.core.protocol.TypertException.CODE_TRANSPORT ->
                "连不上桌面端，检查网络后重试。"
            typert.code == com.revxzxcx.remotewebapp.core.protocol.TypertException.CODE_BAD_ENVELOPE ->
                "桌面端返回的内容看不懂，可能是版本不匹配。"
            else -> typert.message ?: "新建会话失败（${typert.code}）"
        }
    }
    return e.message ?: "新建会话失败"
}
