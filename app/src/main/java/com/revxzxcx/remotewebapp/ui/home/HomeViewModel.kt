package com.revxzxcx.remotewebapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionListResult
import com.revxzxcx.remotewebapp.core.session.SessionRepository
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceRepository
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * 工作区页的状态。
 *
 * 两个数据源：工作区来自 `workspace/follow`（流，实时），会话摘要来自 `session/list`
 * （unary，手动/自动刷新一次）。两者合并后由 [SessionGrouping] 产出 [groups]。
 */
data class HomeUiState(
    /** 是否还在等第一帧 baseline。 */
    val loading: Boolean = true,
    /** 工作区列表（已按服务端顺序）。 */
    val snapshot: WorkspaceSnapshot = WorkspaceSnapshot(),
    /** 会话摘要列表。 */
    val sessions: SessionListResult = SessionListResult(),
    /** 工作区 × 会话的分组结果，UI 直接渲染它。 */
    val groups: List<SessionGrouping.WorkspaceGroup> = emptyList(),
    /** 会话列表是否正在加载（与工作区流的 loading 分开，刷新会话不该让整页转圈）。 */
    val sessionsLoading: Boolean = false,
    /** 会话列表加载失败的原因；工作区仍然可用。 */
    val sessionsError: String? = null,
    /** 展开的工作区 id 集合。 */
    val expandedWorkspaceIds: Set<String> = emptySet(),
    /** 连接状态文案：`已连接` / `重连中…`。 */
    val connection: String = "连接中…",
    /** 不可恢复的错误（例如 403 unpaired）。 */
    val error: String? = null,
    /** 凭证是否已失效，需要重新配对。 */
    val needsRepair: Boolean = false,
    /** 最近一次收到的原始帧，供 M4 调试页使用。 */
    val lastRawFrame: String? = null,
    /**
     * 全部工作区里最近活动的顶层会话（抽屉「最近」用）。
     *
     * 为什么是构造属性而不是 `get()` 派生属性：`get()` 不参与 data class 的相等性判断，
     * 状态里只有 [groups] 变化时 Compose 可能跳过重组。放进状态里让三份派生列表
     * （[groups] / [recent] / [pinned]）始终由同一次 [withDerived] 一起更新，
     * 避免出现「分组是新的、抽屉还是旧的」这种自相矛盾的状态。
     */
    val recent: List<SessionSummary> = emptyList(),
    /** 全局置顶会话（抽屉「置顶」用），带归属工作区。 */
    val pinned: List<Pair<SessionSummary, Workspace?>> = emptyList(),
)

/**
 * 工作区 + 会话列表页的 ViewModel。
 *
 * 数据来源：
 * - 工作区：`workspace/follow`（流，无参数）。首帧 `baseline`，之后 `upsert`/`remove`/`order`
 *   增量更新（[WorkspaceRepository] 负责折叠）。
 * - 会话：`session/list`（unary）。**不是流**——实测 `invocation.kind == 'direct'`；
 *   实时状态变化走 `session/control`，M2 不做（留 M4）。
 *
 * 两者关系（T009 实测）：`session/list` 有 47 条（含 20 条子代理），而
 * `workspace.sessionIds` 只含顶层会话，**子代理不在任何工作区里**。所以合并规则是
 * 「以 `workspace.sessionIds` 为准，用 `session/list` 补摘要」。
 *
 * 重连策略（需求文档 §7.3 / 代码审查清单 3.4）：指数退避 1s→2s→4s…上限 30s，
 * 重连期间**不清空**已有列表，只把连接状态改成「重连中…」。凭证失效（403 `unpaired`）
 * 属于不可恢复错误，直接停下并提示重新配对，不再无限重试。
 */
class HomeViewModel(
    private val connection: DshConnection,
) : ViewModel() {

    private val workspaces = WorkspaceRepository(connection)
    private val sessions = SessionRepository(connection)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var sessionJob: Job? = null

    init {
        start()
    }

    /** 手动重连（下拉刷新 / 点「重试」）。 */
    fun retry() {
        job?.cancel()
        sessionJob?.cancel()
        // 必须显式置空：`cancel()` 是异步的，紧接着的 `start()` 拿到的首帧会调
        // `loadSessions()`，而那时旧 job 的 `isActive` 可能仍为 true，会被重入保护
        // 静默丢掉——会话列表就再也刷不出来了（R003 P1-2）。
        sessionJob = null
        _state.value = _state.value.copy(
            error = null,
            needsRepair = false,
            connection = "连接中…",
            sessionsLoading = false,
            sessionsError = null,
        )
        start()
    }

    /** 只刷新会话列表，不动工作区流（下拉刷新用）。 */
    fun refreshSessions() = loadSessions()

    /** 展开/收起一个工作区的会话列表。 */
    fun toggleWorkspace(workspaceId: String) {
        val current = _state.value.expandedWorkspaceIds
        _state.value = _state.value.copy(
            expandedWorkspaceIds = if (workspaceId in current) current - workspaceId else current + workspaceId,
        )
    }

    private fun start() {
        job = viewModelScope.launch {
            // 每次订阅（含手动 retry 后重启）只补一次会话摘要。用局部变量而不是读
            // `_state.value.loading`：后者是全局标志，重连时已被置 false，会导致
            // retry 之后会话列表再也不刷新（R003 P1-2）。
            var firstSnapshot = true
            workspaces.snapshotFlow(onRawFrame = { raw ->
                _state.value = _state.value.copy(lastRawFrame = raw)
            })
                .retryWhen { cause, attempt ->
                    if (cause is TypertException && cause.isUnpaired) {
                        false // 凭证失效：不再重试，交给 UI 引导重新配对。
                    } else {
                        val backoffMs = backoffMillis(attempt)
                        _state.value = _state.value.copy(
                            loading = false,
                            connection = "重连中…（${backoffMs / 1000}s 后重试）",
                        )
                        delay(backoffMs)
                        // 重连成功后重新补一次会话摘要（断线期间的变更要补回来）。
                        firstSnapshot = true
                        true
                    }
                }
                .catch { cause ->
                    val typert = cause as? TypertException
                    _state.value = _state.value.copy(
                        loading = false,
                        connection = "已断开",
                        error = typert?.let { "${it.code}：${it.message}" }
                            ?: (cause.message ?: cause.javaClass.simpleName),
                        needsRepair = typert?.isUnpaired == true,
                    )
                }
                .collect { snapshot ->
                    _state.value = _state.value
                        .copy(
                            loading = false,
                            snapshot = snapshot,
                            connection = "已连接",
                            error = null,
                        )
                        .withDerived(snapshot, _state.value.sessions)
                    // 工作区刚拿到时补一次会话摘要。已在跑就不重复发。
                    if (firstSnapshot) {
                        firstSnapshot = false
                        loadSessions()
                    }
                }
        }
    }

    /**
     * 重算所有由「工作区快照 + 会话列表」派生的字段。
     *
     * 三个字段必须在同一次 `copy` 里一起更新，否则会出现「分组已更新、抽屉还是旧的」
     * 这种自相矛盾的状态（真机实测过：标题显示 13 条，列表却是空的）。
     */
    private fun HomeUiState.withDerived(
        snapshot: WorkspaceSnapshot,
        sessions: SessionListResult,
    ): HomeUiState = copy(
        groups = SessionGrouping.group(snapshot, sessions),
        recent = SessionGrouping.recentSessions(snapshot, sessions),
        pinned = SessionGrouping.pinnedSessions(snapshot, sessions),
    )

    /**
     * 拉一次会话摘要并重新合并分组。
     *
     * 失败**不清空**已加载的会话，只把错误文案挂到 `sessionsError`；工作区列表继续可用。
     */
    private fun loadSessions() {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            _state.value = _state.value.copy(sessionsLoading = true, sessionsError = null)
            try {
                val result = sessions.load()
                _state.value = _state.value
                    .copy(
                        sessionsLoading = false,
                        sessions = result,
                        sessionsError = null,
                    )
                    .withDerived(_state.value.snapshot, result)
            } catch (cause: Exception) {
                val typert = cause as? TypertException
                _state.value = _state.value.copy(
                    sessionsLoading = false,
                    sessionsError = typert?.let { "${it.code}：${it.message}" }
                        ?: (cause.message ?: cause.javaClass.simpleName),
                )
            } finally {
                // 协程被取消时 `CancellationException` 既不进 try 的成功分支也不进
                // `catch (Exception)`，`sessionsLoading` 会永久停在 true（抽屉一直转圈）。
                // 放在 finally 里保证任何退出路径都复位（R003 P1-2）。
                if (_state.value.sessionsLoading) {
                    _state.value = _state.value.copy(sessionsLoading = false)
                }
            }
        }
    }

    /** 1s, 2s, 4s, 8s, 16s, 30s, 30s… */
    private fun backoffMillis(attempt: Long): Long {
        val shift = attempt.coerceAtMost(5)
        val ms = 1_000L shl shift.toInt()
        return ms.coerceAtMost(MAX_BACKOFF_MS)
    }

    override fun onCleared() {
        job?.cancel()
        sessionJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        fun factory(connection: DshConnection): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(connection) }
        }
    }
}
