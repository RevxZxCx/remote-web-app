package com.revxzxcx.remotewebapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.events.SessionNotification
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.MuxStats
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.CompletionLedger
import com.revxzxcx.remotewebapp.core.session.SessionActionRepository
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionListParser
import com.revxzxcx.remotewebapp.core.session.SessionListResult
import com.revxzxcx.remotewebapp.core.session.SessionListUpdates
import com.revxzxcx.remotewebapp.core.session.SessionRepository
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceRepository
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import com.revxzxcx.remotewebapp.ui.common.ConnectionDetails
import com.revxzxcx.remotewebapp.ui.common.maskDeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
    private val sessionActions = SessionActionRepository(connection)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * 正在执行管理动作的会话 id 集合（归档 / 取消归档 / 置顶 / 取消置顶）。
     *
     * 为什么是集合而不是单个 `String?`：长按菜单弹出后用户可能快速连点两个会话，
     * 单值会把前一个动作的进行态静默丢掉，UI 上表现为「点了没反应但其实是还在飞」。
     */
    private val _actionBusy = MutableStateFlow<Set<String>>(emptySet())
    val actionBusy: StateFlow<Set<String>> = _actionBusy.asStateFlow()

    /** 管理动作的失败文案；null 表示没有待展示的错误。 */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

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

    /**
     * 顶栏连接指示器要展示的细节（R028 ①）。
     *
     * 与 [carrier] 分开是因为两者**刷新频率差几个数量级**：载波状态只在连接建立/断开时变，
     * 而这里要跟着每一帧收发更新。合成一条会让状态条的每一次重组都白算一遍打码。
     *
     * 打码在 [connectionDetailsOf] 里做一次，不留「顺手显示完整 deviceId」的口子。
     *
     * **代价已知**：`maskDeviceId` 仍在 `combine` 内**每帧重算**（R028 审查 P3-12 指出这与
     * 「拆开是为了不每次白算打码」的自述理由不符）。保留原样是因为真实成本只有一次
     * SHA 前缀拼接级的字符串操作，而把它提出去要引入额外缓存与失效逻辑——不值得。
     * 自述理由已在上面改成准确表述：拆开是为了**限制重组范围**（消费端见 HomeScreen 的
     * `actions` lambda），不是为了省这次打码。
     */
    val connectionDetails: StateFlow<ConnectionDetails> = connection.mux.stats
        .combine(connection.remoteEvents.clientId) { stats, clientId -> stats to clientId }
        .map { (stats, clientId) -> connectionDetailsOf(stats, clientId) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            // 初值走**同一个**构造入口（R028 审查 P3-6）：早先这里逐字重复了一遍
            // `ConnectionDetails(...)`，`ConnectionDetails` 新增字段时两处必然漂移，
            // 而漂移方向恰好是「初值少一个字段 → 首帧闪一下空值」这种难查的形态。
            connectionDetailsOf(connection.mux.stats.value, connection.remoteEvents.clientId.value),
        )

    private fun connectionDetailsOf(stats: MuxStats, clientId: String?): ConnectionDetails =
        ConnectionDetails(
            baseUrl = connection.baseUrl,
            maskedDeviceId = maskDeviceId(connection.deviceId),
            channelReady = clientId != null,
            framesIn = stats.framesIn,
            framesOut = stats.framesOut,
            lastFailure = stats.lastFailure,
        )

    private var job: Job? = null
    private var sessionJob: Job? = null

    /** 订阅 `$events` 里 `api-session/…` 的协程；与连接同生共死。 */
    private var notificationsJob: Job? = null

    /** 去抖后的「重拉全量」协程（见 [scheduleSessionsRefresh]）。 */
    private var refreshJob: Job? = null

    /**
     * 去抖窗口内是否又来过 `activity`。
     *
     * 置位后由 [scheduleSessionsRefresh] 在本轮结束后补拉一次，避免「窗口内被折叠掉的消息
     * 要等下一次无关事件才更新」（审查 P3）。
     */
    private var refreshDeferred = false

    /**
     * [loadSessions] 因「上一次还在飞」而被跳过时置位；那次拉取结束后补拉一次。
     *
     * 与 [refreshDeferred] 分开：那个管的是去抖窗口，这个管的是在飞窗口，两处的触发点不同。
     */
    private var sessionsRefreshPending = false

    /** 复用它解析 `api-session/added` 推来的摘要（与 `session/list` 同形状）。 */
    private val listParser = SessionListParser()

    /**
     * 列表行「完成 / 失败」未读提醒的账本（R049，用户 m32994 + m33325 纠正）。
     *
     * 用户明确说过「这个点不是常驻的，而是一个完成的标记，提醒用户查看对话的」，
     * 所以它是**一次性提醒**而不是状态：跑完（边沿）→ 亮；用户进来看了 → 灭；
     * 又跑起来 → 灭。判据全部在 [CompletionLedger] 里，这里只负责把它接到事件流
     * 与列表刷新上。
     *
     * 只在主线程读写（`observeSessionNotifications` 的 collect 与 `loadSessions` 都在
     * `viewModelScope` 的默认调度器上），账本自身也不加锁，见其类 KDoc 的「线程」节。
     */
    private val ledger = CompletionLedger()

    init {
        warmStart()
        start()
        observeSessionNotifications()
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

    // ------------------------------------------------------------------
    // 会话管理动作（归档 / 置顶）
    //
    // 服务端**没有**删除会话的 API（`SessionActions` 的 KDoc 记录了全量 grep 的证据），
    // 所以这里提供的「移除」是归档：会话从「会话 / 置顶」段移到「已归档」段，
    // 仍然可以取消归档。UI 文案一律说「归档」，不说「删除」。
    // ------------------------------------------------------------------

    /** 归档一个会话（同时叫停它正在跑的活动）。 */
    fun archiveSession(sessionId: String) = runSessionAction(sessionId) {
        sessionActions.archive(sessionId)
    }

    /** 取消归档。 */
    fun unarchiveSession(sessionId: String) = runSessionAction(sessionId) {
        sessionActions.unarchive(sessionId)
    }

    /** 置顶。 */
    fun pinSession(sessionId: String) = runSessionAction(sessionId) {
        sessionActions.pin(sessionId)
    }

    /** 取消置顶。 */
    fun unpinSession(sessionId: String) = runSessionAction(sessionId) {
        sessionActions.unpin(sessionId)
    }

    /** 清掉管理动作的错误横幅。 */
    fun dismissActionError() {
        _actionError.value = null
    }

    /**
     * 四个管理动作的公共骨架：busy 标记 + 取消上抛 + 错误文案。
     *
     * 成功之后**不刷新列表**：`workspace/follow` 会推 `{type:'archived'|'pinned'}` 增量帧，
     * [com.revxzxcx.remotewebapp.core.workspace.WorkspaceReducer] 已经处理，
     * 再拉一次 `session/list` 只会多一次往返。详见 [SessionActionRepository] 的 KDoc。
     */
    private fun runSessionAction(sessionId: String, block: suspend () -> Unit) {
        if (sessionId in _actionBusy.value) return
        viewModelScope.launch {
            _actionBusy.value = _actionBusy.value + sessionId
            _actionError.value = null
            try {
                block()
            } catch (cause: Exception) {
                // 与 loadSessions 同一条铁律：取消不是失败。旋转屏幕 / 离开页面时
                // CancellationException 会被 catch (Exception) 接住，写一条假错误。
                if (cause is CancellationException) throw cause
                val typert = cause as? TypertException
                _actionError.value = typert?.let { "${it.code}：${it.message}" }
                    ?: (cause.message ?: cause.javaClass.simpleName)
            } finally {
                _actionBusy.value = _actionBusy.value - sessionId
            }
        }
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
            // 这里**不用** carryUnreadFlags 重盖未读标记：`warmStart()` 只在 init 里跑一次，
            // 那一刻账本三份账必然全空（早退），而缓存表也没有 completionUnread /
            // failureUnread / failureReason 这三列，读回来恒为默认值。包一层只会让后来者
            // 误以为「缓存里带着未读标记」。等缓存真的持久化这几列时再加回来。
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

    /**
     * 订阅 `$events` 里 `api-session/…` 的增量，落到会话列表上。
     *
     * ## 为什么必须有这条链路（R039 用户报的两个现象）
     * 1. **桌面端新建的会话在手机上永远不出现**：列表原来只在订阅建立那一刻拉一次
     *    `session/list`，之后再没有任何刷新触发点。用户在工作区里看到的是
     *    「读不到 N」——因为 `workspace.sessionIds` 里已经有这个 id，
     *    而摘要表里没有它（[SessionGrouping] 会把它记进 `missingIds`）。
     * 2. **会话跑完了「进行中」小柱还在转**：`running` 只在全量重拉时更新。
     *
     * ## 为什么不是「定时全量重拉」
     * `session/list` 实测 125 条 / **1.78 MB** / 2.6 s。轮询它是拿手机流量换一个
     * 本来就有推送的信号。而 `api-session/added` 推来的 `summary` 与 `session/list`
     * 的条目**同形状**（探针实测含 projections 全套），可以直接复用
     * [SessionListParser.summaryOf] 解析——增量已经够用了。
     *
     * ## 五条事件各管一件事
     * - `added`：新建 / agent 创建或销毁（带完整摘要，标题也在里面）→ 直接 upsert；
     *   摘要里没有 App 侧的未读标记，所以 upsert 之后要按账本回填（R049 审查 P2）；
     * - `status`：`(sessionId, running)` → 就地打补丁，这是「小柱」的唯一更新源，
     *   也是完成提醒的边沿来源；
     * - `activity`：用户发了新消息（Host 只在 `user/message` 且 `source.kind == "user"`
     *   时发它）→ 去抖后重拉一次全量。理由：`activity` 只给 `updatedAt`，而排序与
     *   标题可能一起变，光靠它无法就地改；它出现的频率恰好是「人打了一句话」，
     *   去抖后重拉的开销可以接受。
     * - `error`：这一轮跑挂了 → 记一枚红色未读提醒（R049）。`message` 经
     *   `SessionSummary.failureReason` 送进 `SessionStatusDot` 的 `contentDescription`，
     *   读屏能听到失败原因；列表行本身仍然不显示文字。
     * - `removed`：会话被 Host 真正销毁 → 从列表里删掉，并用 `ledger.forget` 清掉它在
     *   三份账里的残留（不清理会让一条同名新会话凭空继承旧提醒）。
     */
    private fun observeSessionNotifications() {
        notificationsJob = viewModelScope.launch {
            connection.remoteEvents.sessionNotifications
                .filterNotNull()
                .collect { notification ->
                    when (notification) {
                        is SessionNotification.Added -> applyAddedSummary(notification.summary)
                        is SessionNotification.Status ->
                            applyRunning(notification.sessionId, notification.running)
                        is SessionNotification.Removed -> applyRemoved(notification.sessionId)
                        is SessionNotification.Activity -> scheduleSessionsRefresh()
                        is SessionNotification.Error ->
                            applyError(notification.sessionId, notification.message)
                    }
                }
        }
    }

    /**
     * 用户进入了某个会话（`null` 表示回到列表页）。
     *
     * 由 `AppRoot` 在 `openSession` 置值 / 清空时调用。进入即「看过」：账本会把那条
     * 会话的两枚未读提醒都熄灭并同步进列表，否则提醒只能靠「它自己再跑一轮」才消失。
     */
    fun onSessionOpened(sessionId: String?) {
        ledger.setCurrentSession(sessionId)
        if (sessionId != null) {
            applySessions(
                SessionListUpdates.patchUnreadFlags(
                    items = _state.value.sessions.items,
                    sessionId = sessionId,
                    completionUnread = false,
                    failureUnread = false,
                ),
            )
        }
    }

    /** `api-session/added`：整条摘要插入或原位替换。 */
    private fun applyAddedSummary(summary: JsonObject) {
        // 解析失败绝不改状态：宁可少一条，也不要往列表里塞一条 sessionId 残缺的假会话。
        val parsed = listParser.summaryOf(summary) ?: return
        // 服务端摘要里没有 completionUnread / failureUnread（那两个字段只在 App 侧账本里），
        // 而 upsert 是**整条替换**，直接写回会把已经亮着的提醒抹掉。asar 里 `api-session/added`
        // 不只在 `session/created` 时发，`agent/created` / `agent/disposed` 也会对主 agent
        // 重发一次摘要，所以这条覆盖路径是常态，不是边界情况。
        val merged = SessionListUpdates.upsert(_state.value.sessions.items, parsed)
            .let {
                SessionListUpdates.patchUnreadFlags(
                    items = it,
                    sessionId = parsed.sessionId,
                    completionUnread = ledger.isUnread(parsed.sessionId),
                    failureUnread = ledger.isFailureUnread(parsed.sessionId),
                    failureReason = ledger.failureReason(parsed.sessionId),
                )
            }
        applySessions(merged)
        cacheIncrementally { it.upsertSession(ownerId, parsed) }
    }

    /**
     * `api-session/status`：翻运行态，并把「跑完了没看」的提醒落定（R049）。
     *
     * 顺序要紧：先让账本判边沿（它会更新自己记住的上一态），再把运行态与它的结论
     * 一次性写进列表。分两步写会让重组看到「已停下但仍标着运行中」的中间帧。
     */
    private fun applyRunning(sessionId: String, running: Boolean) {
        val completionUnread = ledger.observe(sessionId, running)
        val items = SessionListUpdates.patchRunning(_state.value.sessions.items, sessionId, running)
            .let {
                SessionListUpdates.patchUnreadFlags(
                    items = it,
                    sessionId = sessionId,
                    completionUnread = completionUnread,
                    failureUnread = ledger.isFailureUnread(sessionId),
                    failureReason = ledger.failureReason(sessionId),
                )
            }
        applySessions(items)
        // 落盘只写这一条：`patchRunning` 在未知 id 上会原样返回，那时找不到条目就不写。
        items.firstOrNull { it.sessionId == sessionId }
            ?.let { patched -> cacheIncrementally { it.upsertSession(ownerId, patched) } }
    }

    /**
     * `api-session/error`：这条会话上一轮跑挂了（R049）。
     *
     * `session/list` 不返回 outcome，`running` 也已经翻成 false，之后不会再有事件说明
     * 「它上次失败了」——所以失败必须由账本自己记一份，且同样是一次性提醒：
     * 用户进会话看失败卡片、或它重新跑起来，就灭。
     */
    private fun applyError(sessionId: String, reason: String) {
        val failureUnread = ledger.observeFailure(sessionId, reason)
        applySessions(
            SessionListUpdates.patchUnreadFlags(
                items = _state.value.sessions.items,
                sessionId = sessionId,
                completionUnread = ledger.isUnread(sessionId),
                failureUnread = failureUnread,
                failureReason = ledger.failureReason(sessionId),
            ),
        )
    }

    /** `api-session/removed`：会话被销毁。归档**不走这里**（见 [SessionListUpdates.remove]）。 */
    private fun applyRemoved(sessionId: String) {
        ledger.forget(sessionId)
        applySessions(SessionListUpdates.remove(_state.value.sessions.items, sessionId))
        cacheIncrementally { it.deleteSession(ownerId, sessionId) }
    }

    /**
     * 把一条增量落进缓存（逐行 upsert / 单条删除）。
     *
     * **绝不能复用 [DshCache.writeSessions]**：那是权威全量语义（整表替换 + 清孤儿），
     * 拿它写增量会在冷启动早期把「还没拉回来的会话」的消息与草稿当成孤儿删光（审查 P1）。
     */
    private fun cacheIncrementally(block: suspend (DshCache) -> Unit) {
        val store = cache ?: return
        if (ownerId.isBlank()) return
        viewModelScope.launch { block(store) }
    }

    /**
     * 把一份新的会话摘要列表落进状态，并**同一次**重算三份派生列表。
     *
     * 必须走 [withDerived]：`groups` / `recent` / `pinned` 都是构造属性，不参与自动派生。
     * 只改 `sessions` 而不重算它们，就会出现「摘要更新了、分组还是旧的」——
     * 表现正是用户报的「会话跑完了还在转」（`WorkspaceGroup.hasRunning` 是 `groups`
     * 里的字段，抽屉/工作区标题读的都是它）。
     */
    private fun applySessions(items: List<SessionSummary>) {
        val current = _state.value
        val result = SessionListResult(items = items)
        if (result == current.sessions) return
        _state.value = current
            .copy(sessions = result, sessionsError = null)
            .withDerived(current.snapshot, result)
        // 这里**不写缓存**。缓存写入分两条互不相同的路径：
        // - 全量：只有 [loadSessions] 拿到权威 `session/list` 时才 `writeSessions`；
        // - 增量：各调用点走 [cacheIncrementally]。
        // 早先在这里统一调 `writeSessions`，等于把增量当权威全量整表替换（审查 P1）。
    }

    /**
     * 去抖地重拉一次全量会话列表。
     *
     * 去抖而不是每次都拉：一轮工具调用会连着产生多条 `activity`，每条都拉 1.78 MB
     * 是灾难。窗口内只保留最后一次。
     */
    private fun scheduleSessionsRefresh() {
        if (refreshJob?.isActive == true) {
            // 窗口内又来了一条 `activity`：把「结束之后还要再拉一次」记下来。
            // 只节流不补拉的话，窗口关掉之后发生的消息要等下一次无关事件才会更新
            // （审查 P3：一次 `session/list` 要 2.6s，用户在这期间发的消息会永远落后）。
            refreshDeferred = true
            return
        }
        refreshJob = viewModelScope.launch {
            delay(SESSIONS_REFRESH_DEBOUNCE_MS)
            loadSessions()
            if (refreshDeferred) {
                refreshDeferred = false
                // 递归一次即可：`loadSessions` 自己会再判在飞，不会叠加。
                scheduleSessionsRefresh()
            }
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
        if (sessionJob?.isActive == true) {
            // 上一次还在飞（2.6s）：这次请求被丢掉，但必须留个记号让它结束后补拉，
            // 否则这段时间内的变化没有任何后续事件会再触发一次（审查 P3）。
            sessionsRefreshPending = true
            return
        }
        sessionJob = viewModelScope.launch {
            _state.value = _state.value.copy(sessionsLoading = true, sessionsError = null)
            try {
                // 权威全量里没有 outcome（`session/list` 顶层只有 8 个字段），
                // 把本次运行期攒下的报错标记重新盖上（R049）。
                //
                // 纪元必须在**发起请求前**记下：这次 RPC 要飞 2.6 秒，期间可能收到
                // 某条会话的 `status` 事件。种子若拿陈旧快照比对，会把「刚开始跑」
                // 读成「跑完了」，还会把基线改回 false 吞掉真正的完成边沿（二审 P2）。
                val epochAtRequest = ledger.currentEpoch()
                val loaded = sessions.load().items
                // 播种：把全量列表里各条会话的运行态补进账本（只补没见过的），
                // 再把本次运行期亮着的两枚提醒重新盖上去。
                val skipped = ledger.seed(loaded, sinceEpoch = epochAtRequest)
                // 被纪元守卫跳过的条目，其 `running` 必须以账本为准回填：只保住账本
                // 基线而不改列表，陈旧快照仍会把「刚开始跑」的行打回静止，而那条
                // `status=true` 已被消费，之后没有事件会再纠正它（二审 P2）。
                val corrected = SessionListUpdates.patchRunningFromLedger(
                    items = loaded,
                    updates = skipped.mapNotNull { id -> ledger.runningOf(id)?.let { id to it } }.toMap(),
                )
                val result = SessionListResult(
                    items = SessionListUpdates.carryUnreadFlags(
                        items = corrected,
                        completionIds = ledger.unreadIds(),
                        failureIds = ledger.failureIds(),
                        failureReasons = ledger.failureReasons(),
                    ),
                )
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
                if (cause is CancellationException) throw cause
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
                // 在飞期间被跳过的那次刷新，在这里补上（审查 P3）。放在 finally 而不是
                // 成功分支：失败时更需要重试一次，而 `retryWhen` 只重连工作区流、
                // 不会自动重拉会话列表。
                if (sessionsRefreshPending) {
                    sessionsRefreshPending = false
                    loadSessions()
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
        notificationsJob?.cancel()
        refreshJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * `api-session/activity` 之后等多久才重拉全量。
         *
         * 1.5 s 的取法：`activity` 在用户按下发送的那一刻就来，而这一轮的标题要到
         * 模型跑完才落库；窗口太短等于没等到，太长会让列表看起来发钝。取 1.5 s 是
         * 「用户已经打完字、界面该有反应」的下限，真要拿到新标题还得靠轮次结束时
         * Host 再发的 `added`（`agent/disposed` 会带全量摘要）。
         */
        private const val SESSIONS_REFRESH_DEBOUNCE_MS = 1_500L

        fun factory(
            connection: DshConnection,
            cache: DshCache? = null,
            ownerId: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(connection, cache, ownerId) }
        }
    }
}
