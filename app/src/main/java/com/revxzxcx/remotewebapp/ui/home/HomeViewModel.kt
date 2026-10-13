package com.revxzxcx.remotewebapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
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
    /** 不可恢复的错误（例如 403 unpaired）。 */
    val error: String? = null,
    /** 凭证是否已失效，需要重新配对。 */
    val needsRepair: Boolean = false,
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
    /**
     * 当前列表来自本地缓存、还没被网络帧覆盖（需求 FR-4.3）。
     *
     * UI 用它显示「缓存」角标。为什么需要显式标志而不是比较时间戳：缓存是**同一次进程
     * 冷启动**里铺上来的，时间戳与网络数据几乎同时，靠时间无法判断「屏幕上这份是不是
     * 还没对齐」。
     */
    val fromCache: Boolean = false,
    /**
     * 是否已经收到过**网络**的工作区快照（与 [loading] 分开）。
     *
     * 为什么不能拿 [loading] 代替：`loading` 在重试退避时也会被置 false（那时网络并没有
     * 数据），用它当「网络数据已到」的判据会让缓存铺不上去。这个标志只为
     * [HomeViewModel.warmStart] 的竞态守卫服务——读缓存是挂起操作，期间 baseline 可能
     * 已经先到，此时**绝不能让旧缓存盖掉权威快照**（R006 P1-1）。
     */
    val networkArrived: Boolean = false,
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
    private val cache: DshCache? = null,
    private val ownerId: String = "",
) : ViewModel() {

    private val workspaces = WorkspaceRepository(connection)
    private val sessions = SessionRepository(connection)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * 全局连接状态（需求 FR-4.4），直接取自唯一的载波。
     *
     * 为什么不像别的字段那样塞进 [HomeUiState]：那不是「本页面的状态」，而是全局事实，
     * 聊天页读的是同一个 [DshConnection.mux]。放进页面状态里就要在每一处 `copy` 里同步它，
     * 迟早会漏一处而让状态条显示旧值。
     */
    val carrier: StateFlow<CarrierState> = connection.mux.stats
        .map { it.carrier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, connection.mux.stats.value.carrier)

    private var job: Job? = null
    private var sessionJob: Job? = null

    init {
        warmStart()
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
        job = null
        _state.value = _state.value.copy(
            error = null,
            needsRepair = false,
            sessionsLoading = false,
            sessionsError = null,
            // 重连后即将拿到权威数据，缓存标记必须复位：不复位会让横幅一直说
            // 「当前显示的是上次的缓存内容」，即使网络数据早就到了（R006 P3-1）。
            fromCache = false,
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

    /**
     * 冷启动先铺缓存（需求 FR-4.3）。
     *
     * 这一步**必须先于** `workspace/follow` 的首帧：网络是异步且可能慢到几秒的，
     * 而用户看到的第一屏不该是空白转圈。铺缓存后立即显示，网络第几秒回来再覆盖即可。
     *
     * 注意这里刻意**不**把 `loading` 置 false：缓存只是「先看一眼」，真正的权威数据
     * 还没到；`loading=true` 同时驱动那条细进度条与「这个桌面端还没有工作区」占位文案的
     * 判定（见 `HomeScreen`）。用 [fromCache] 让 UI 能区分「这是缓存」。
     */
    private fun warmStart() {
        val store = cache ?: return
        viewModelScope.launch {
            val cachedWorkspaces = store.readWorkspaces(ownerId)
            val cachedSessions = store.readSessions(ownerId)
            if (cachedWorkspaces == null && cachedSessions == null) return@launch
            // 必须守住「网络帧已经到了」这一条件（R006 P1-1）：`warmStart()` 与 `start()`
            // 都是 `viewModelScope.launch`，而读缓存会在 `readWorkspaces` 处挂起。如果
            // baseline 先返回，这里就把**旧缓存**盖在刚到的权威快照上，并把 `fromCache`
            // 重新置真——网络数据只在下一帧快照到达时才复位，期间屏幕上显示的是过期数据
            // 却标着「正在同步」。与 `ChatViewModel.warmStart` 的 `if (current.ready) return`
            // 同一策略。
            val current = _state.value
            if (current.networkArrived) return@launch
            val snapshot = cachedWorkspaces ?: current.snapshot
            val sessions = cachedSessions?.let { SessionListResult(items = it) } ?: current.sessions
            // 读完后再判一次：上面的读是挂起点，期间可能已有网络帧写入并置位。
            if (_state.value.networkArrived) return@launch
            _state.value = current.copy(
                snapshot = snapshot,
                sessions = sessions,
                fromCache = true,
            ).withDerived(snapshot, sessions)
        }
    }

    private fun start() {
        job = viewModelScope.launch {
            // 每次订阅（含手动 retry 后重启）只补一次会话摘要。用局部变量而不是读
            // `_state.value.loading`：后者是全局标志，重连时已被置 false，会导致
            // retry 之后会话列表再也不刷新（R003 P1-2）。
            var firstSnapshot = true
            workspaces.snapshotFlow()
                .retryWhen { cause, attempt ->
                    if (cause is TypertException && cause.isUnpaired) {
                        // 凭证失效：不再重试，交给 UI 引导重新配对。同时把这一事实投影到
                        // 唯一的载波状态上，免得别处的状态条还在说「重连中…」（R002 P1-1）。
                        connection.mux.noteRetry(
                            reason = cause.message ?: "设备未配对",
                            delayMillis = 0L,
                            unpaired = true,
                        )
                        false
                    } else {
                        val backoffMs = backoffMillis(attempt)
                        // 把重试计划汇报给 mux：它是唯一的载波，全局状态条读它的 stats。
                        connection.mux.noteRetry(
                            reason = (cause as? TypertException)?.let { "${it.code}：${it.message}" }
                                ?: (cause.message ?: cause.javaClass.simpleName),
                            delayMillis = backoffMs,
                        )
                        _state.value = _state.value.copy(loading = false)
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
                            error = null,
                            fromCache = false,
                            // 网络数据到了：此后缓存不得再覆盖状态（warmStart 的竞态守卫）。
                            networkArrived = true,
                        )
                        .withDerived(snapshot, _state.value.sessions)
                    // 写穿缓存：拿到权威快照就落盘，供下次冷启动先铺。
                    cache?.let { store ->
                        viewModelScope.launch { store.writeWorkspaces(ownerId, snapshot) }
                    }
                    // 工作区刚拿到时补一次会话摘要。已在跑就不重复发。
                    if (firstSnapshot) {
                        firstSnapshot = false
                        loadSessions()
                    } else if (_state.value.sessionsError != null) {
                        // 上次摘要读取失败（冷启动实测 `session/list` 要 2.6s，与 mux 建连
                        // 抢带宽时可能超时/被取消）→ 每次再来一帧就重试一次，直到成功。
                        // 旧实现只有一次性 `firstSnapshot` 标志：首拉一失败，会话列表就永久
                        // 为空，抽屉里只显示「没有读到会话」，用户完全不知道还能重试
                        // （M3 真机踩到：「共 0 条 · 顶层 27」）。
                        //
                        // 判据必须是 `sessionsError != null` 而**不是** `sessions.items.isEmpty()`：
                        // 后者会让「本来就没有会话」的账号在每一帧都白拉一次。
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
                // 会话列表是权威全量（实测无游标、limit 被忽略），直接整表覆盖。
                cache?.let { store ->
                    viewModelScope.launch { store.writeSessions(ownerId, result.items) }
                }
            } catch (cause: Exception) {
                // `CancellationException` 是 Exception 的子类，绝不能在这里被吞掉：
                // 协程被取消时（离开页面 / retry() 取消旧 job）如果写一条
                // 「会话列表读取失败：JobCancellationException」，用户会看到一条假错误，
                // 而且 sessionsError 会一直挂在界面上（R003 P1-2 / R004 P3-1 同类）。
                if (cause is kotlinx.coroutines.CancellationException) throw cause
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

        fun factory(
            connection: DshConnection,
            cache: DshCache? = null,
            ownerId: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(connection, cache, ownerId) }
        }
    }
}
