package com.revxzxcx.remotewebapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import com.revxzxcx.remotewebapp.core.changes.ChangesRepository
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.ChatArgs
import com.revxzxcx.remotewebapp.core.session.ChatFrame
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ChatReducer
import com.revxzxcx.remotewebapp.core.session.ChatRepository
import com.revxzxcx.remotewebapp.core.session.ChatUiState
import com.revxzxcx.remotewebapp.core.session.SessionEventParser
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
import kotlinx.serialization.json.JsonObject

/**
 * 聊天页的 ViewModel。
 *
 * 数据流：`session/follow`（流）→ [ChatFrameParser] → [ChatReducer] → [ChatUiState]。
 * 所有状态折叠都在 [ChatReducer] 里（纯函数，可单测）；本类只负责协程生命周期、
 * 重连退避、离线缓存（FR-4.3）与把用户操作翻译成 RPC。
 *
 * 重连策略与 [com.revxzxcx.remotewebapp.ui.home.HomeViewModel] 一致：指数退避
 * 1s→2s→4s…上限 30s；重连**不清空**已显示的消息。重连后服务端会重新发快照，
 * [ChatReducer.onSnapshot] 只替换快照覆盖到的那段窗口、保留更早的已加载历史（FR-4.2）。
 * 凭证失效（403 `unpaired`）不重试，直接提示。
 */
class ChatViewModel(
    private val connection: DshConnection,
    private val sessionId: String,
    private val cache: DshCache? = null,
    private val ownerId: String = "",
) : ViewModel() {

    private val repository = ChatRepository(connection)
    private val changesRepository = ChangesRepository(connection)
    private val events = SessionEventParser()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 输入框内容（放在 ViewModel 里，旋转屏幕不丢）。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * 全局连接状态（需求 FR-4.4），直接取自唯一的载波。
     *
     * 与 `HomeViewModel.carrier` 是**同一个** [RemoteStreamMux.stats] 的投影，所以两个页面
     * 永远显示同一种状态——这正是把它放在 mux 上而不是各页面状态里的原因。
     */
    val carrier: StateFlow<CarrierState> = connection.mux.stats
        .map { it.carrier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, connection.mux.stats.value.carrier)

    private var streamJob: Job? = null
    private var pageJob: Job? = null
    private var sendJob: Job? = null
    private var changesJob: Job? = null
    private var cacheJob: Job? = null

    init {
        warmStart()
        start()
        watchChanges()
    }

    /**
     * 冷启动先铺缓存（需求 FR-4.3）。
     *
     * 与工作区页同一策略：缓存先上屏，网络帧到了再覆盖。**必须在 [start] 之前发起**，
     * 但两者都是 `viewModelScope.launch`，所以顺序只影响「谁先跑」、不影响正确性——
     * `ChatReducer` 对快照的合并规则（保留更早历史）保证缓存里的旧消息不会被后面的窗口冲掉。
     *
     * 缓存读取失败不报错：它只是加速，没有它也只是慢一点（[DshCache] 自身已把异常折成 null）。
     */
    private fun warmStart() {
        val store = cache ?: return
        viewModelScope.launch {
            // `readChat` 内部已经把每条信封重新过一遭 `SessionEventParser`（缓存里存的是
            // 服务端原文，见 `CachedMessage`），所以这里拿到的是可直接渲染的条目。
            val cached = store.readChat(ownerId, sessionId).items
            if (cached.isEmpty()) return@launch
            val current = _state.value
            // 只铺「还没被网络覆盖过」的那一份：网络快照一旦到达就是权威窗口，
            // 此时再回填缓存会把更旧的条目插回去（屏幕上表现为历史突然变长又变短）。
            if (current.ready) return@launch
            // 顺序很重要：同 seq 冲突时**以已在状态里的为准**（`distinctBy` 保留首次出现）。
            // 写成 `cached + current.items` 会让缓存副本在同 seq 上胜出，于是「已经有了的
            // 更新内容」被旧缓存盖掉（R006 P3-7）。
            val merged = (current.items + cached).distinctBy { it.seq }.sortedBy { it.seq }
            _state.value = current.copy(items = merged, fromCache = true)
        }
    }

    /** 更新输入框。 */
    fun onDraftChange(text: String) {
        _draft.value = text
    }

    /** 手动重连（点错误条上的「重试」）。 */
    fun retry() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(
            error = null,
            loading = _state.value.items.isEmpty(),
        )
        start()
    }

    /**
     * 重新发送输入框里的文本（发送失败的错误条上的「重试」）。
     *
     * 不能复用 [retry]：那会重启整条 follow 流——比失败本身重得多，且根本不会把消息发出去。
     * 用户在这个按钮上期待的就是「再发一次」（R006 P3-6）。
     */
    fun retrySend() {
        _state.value = _state.value.copy(sendError = null)
        send()
    }

    /**
     * 发送输入框里的文本。
     *
     * 成功后清空输入框；失败保留输入内容并把错误挂到状态上（不能让用户白打一遍字）。
     * 发送用 `mode:'queue'`：排到当前轮之后，不会打断正在生成的回复。
     */
    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            try {
                repository.prompt(
                    sessionId = sessionId,
                    text = text,
                    mode = ChatArgs.PromptMode.Queue,
                    clientTimeZone = java.util.TimeZone.getDefault().id,
                )
                _draft.value = ""
                _state.value = _state.value.copy(error = null, sendError = null)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                // 取消不是发送失败：`onCleared`/`retry` 会取消在途的发送，此时不该把
                // 「已取消」写成错误横幅（`CancellationException` 是 `Exception` 的子类）。
                throw cause
            } catch (cause: Exception) {
                _state.value = _state.value.copy(sendError = describe(cause))
            }
        }
    }

    /** 中止当前生成（`session/cancel`）。 */
    fun cancel() {
        viewModelScope.launch {
            try {
                repository.cancel(sessionId)
                _state.value = _state.value.copy(sendError = null)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Exception) {
                _state.value = _state.value.copy(sendError = describe(cause))
            }
        }
    }

    /**
     * 向上滚动到顶时加载更早的历史。
     *
     * `beforeSeq` 用当前最早条目的 `seq`；没有条目（空会话）时无事可做。
     */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.items.isEmpty()) return
        if (pageJob?.isActive == true) return
        val earliest = current.items.first().seq
        pageJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, pageError = null)
            try {
                val page = repository.loadPage(
                    sessionId = sessionId,
                    throughSeq = _state.value.cursor,
                    beforeSeq = earliest,
                )
                // 翻页得到的更早历史也落盘：它是用户真的翻出来的内容，冷启动后应当还在。
                persist(page.envelopes)
                // 前置插入更早的条目并保持升序去重。
                val merged = (page.items + _state.value.items)
                    .distinctBy { it.seq }
                    .sortedBy { it.seq }
                _state.value = _state.value.copy(
                    items = merged,
                    hasMore = page.hasMore,
                    pageError = null,
                )
            } catch (cause: kotlinx.coroutines.CancellationException) {
                // 取消不是失败：`retry()` 会取消在途的翻页，此时不该在界面上留下错误条
                //（`CancellationException` 是 `Exception` 的子类，不单独拦会走到下面的分支）。
                throw cause
            } catch (cause: Exception) {
                _state.value = _state.value.copy(pageError = describe(cause))
            } finally {
                // 必须放 finally：协程被取消时抛的是 CancellationException，
                // 它既不进 try 的成功分支也不被 `catch (Exception)` 捕获，
                // 写在任一处都会让「加载更早」的转圈行永久留在列表顶部（R003 P1-2 同类）。
                _state.value = _state.value.copy(loadingMore = false)
                pageJob = null
            }
        }
    }

    /**
     * 重新加载更早历史（翻页失败的错误条上的「重试」）。
     *
     * 与 [retry] 分开：翻页失败只需要再请求一页，重启整条流既慢又会让用户从窗口尾巴重新
     * 滚起（R006 P3-6）。
     */
    fun retryLoadMore() {
        _state.value = _state.value.copy(pageError = null)
        loadMore()
    }

    private fun start() {
        streamJob = viewModelScope.launch {
            repository.follow(sessionId)
                .retryWhen { cause, attempt ->
                    if (cause is TypertException && cause.isUnpaired) {
                        // 凭证失效：不重试，交给 UI 引导重新配对。
                        //
                        // 必须把「配对失效」投影回归属的载波状态（R006 P2-1）：只在自己这
                        // 条流上 `false` 结束的话，连接条会继续说「已连接 / 重连中…」，而
                        // 聊天页只有一条不会自愈的错误文案，没有「重新配对」的出口。
                        // 载波状态是全局唯一的，配对失效就该全局可见。
                        connection.mux.noteRetry(describe(cause), 0L, unpaired = true)
                        false
                    } else {
                        val backoffMs = backoffMillis(attempt)
                        connection.mux.noteRetry(describe(cause), backoffMs)
                        _state.value = _state.value.copy(
                            error = "连接中断，${backoffMs / 1000}s 后重试…",
                        )
                        delay(backoffMs)
                        true
                    }
                }
                .catch { cause ->
                    // `catch` 也会捕获取消（`retry()` 取消旧流）：那不是「连接断了」，
                    // 不该写成错误横幅，否则新流建立后这条误导文案会一直留着。
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    _state.value = _state.value.copy(
                        loading = false,
                        error = describe(cause),
                    )
                }
                .collect { frame ->
                    _state.value = ChatReducer.reduce(_state.value, frame)
                    // 写穿缓存：帧里带来的原始信封落盘，供下次冷启动先铺（需求 FR-4.3）。
                    persist(envelopesOf(frame))
                }
        }
    }

    /**
     * 从一帧里取出可缓存的事件信封。
     *
     * 快照带的是整段窗口（可能几百条），单条事件帧只有一条。瞬时流式帧
     * （`ChatFrame.Live`）**不缓存**：它按定义不落盘，存下去会让下次冷启动把一段从未
     * 确认过的半成品当成历史画出来。
     */
    private fun envelopesOf(frame: ChatFrame): List<JsonObject> = when (frame) {
        is ChatFrame.Snapshot -> frame.envelopes
        is ChatFrame.Event -> listOfNotNull(frame.envelope)
        is ChatFrame.Live -> emptyList()
    }

    /**
     * 落盘一批事件信封。
     *
     * 串行化：`cacheJob` 只在空闲时发下一批，避免一次性收到几百条事件时并发开一堆
     * 写事务互相踩（Room 会串行化，但排队产生的协程开销是纯浪费）。丢弃一批的代价只是
     * 「那几条要等下次冷启动才补」，而缓存本来就是尽力而为的。
     */
    private fun persist(envelopes: List<JsonObject>) {
        val store = cache ?: return
        if (envelopes.isEmpty()) return
        if (cacheJob?.isActive == true) return
        cacheJob = viewModelScope.launch { store.appendEvents(ownerId, sessionId, envelopes) }
    }

    /**
     * 观察 [ChatUiState.items] 里还没拿到摘要的 [ChatItem.Changes] 占位卡片，去拉该轮摘要。
     *
     * 为什么用「观察状态」而不是在 `start()` 的 collect 里顺带处理：摘要是**异步且可以失败**
     * 的（Host 重启后返回 404），把它写在流的主路径上会让一次失败的摘要请求和会话流
     * 的生命周期纠缠在一起——而它们完全无关。单独一个协程也便于在 `onCleared` 里一起取消。
     *
     * **待办集合完全从状态推导**（R005 P1-1 / P2-2）：条件只有「这个 seq 既没有结果、
     * 也不在加载中」。早前版本另存一份 `requestedChanges` 台账，它与 [ChatReducer] 在快照
     * 替换时对 [ChatUiState.changes] 的裁剪不同步——重连后卡片被裁掉、台账却还留着 seq，
     * 于是卡片滚回窗口时永远不会重新拉取，永久显示「记录已不可用」。改由状态自身表达
     * 「有没有结果」之后，两者天然同步，也不会自激（发起前先把 seq 写进 [ChatUiState.changesLoading]）。
     *
     * ⚠️ `changes.summary` 是一条**普通鉴权 HTTP 路由**（不是 Typert RPC），返回裸 JSON，
     * 由 [ChangesRepository] 封装。
     */
    private fun watchChanges() {
        changesJob = viewModelScope.launch {
            _state.collect { current ->
                val pending = pendingChangeSeqs(current)
                pending.forEach { seq -> loadChanges(seq) }
            }
        }
    }

    /**
     * 需要现在去拉摘要的 seq：窗口内、还没结果、也不在加载中。
     *
     * `seq < 0` 直接跳过：那不是真实的 `workspace/changes` 事件（解析兜底），既拉不到
     * 也不该在卡片上永远转圈——卡片会按「没有结果」渲染成不可用。
     */
    private fun pendingChangeSeqs(current: ChatUiState): List<Long> = current.items
        .asSequence()
        .filterIsInstance<ChatItem.Changes>()
        .map { it.seq }
        .filter { it >= 0L && it !in current.changes && it !in current.changesLoading }
        .distinct()
        .toList()

    /**
     * 重新拉某一轮的摘要（卡片上的「重试」/「读取改动」）。
     *
     * 只对**可重试**的情形有意义：404（[ChangesEntry.Unavailable]）是终态，记录确实随
     * Host 进程一起没了，再点多少次都是 404，所以直接忽略。其余两种要动：
     *  - [ChangesEntry.Failed]：把结果删掉，回到「没有结果」；
     *  - `null`（还没读到、也没在加载）：没有可删的，直接发起。
     *
     * 注意不能只依赖 `_state` 的重新发射：`StateFlow` 只在值真的变化时发射，而
     * 「entry 为 null 且不在加载中」点重试时值本来就没变，不会被 [watchChanges] 拾起。
     */
    fun retryChanges(seq: Long) {
        when (_state.value.changes[seq]) {
            is ChangesEntry.Failed -> _state.value = _state.value.copy(
                changes = _state.value.changes - seq,
            )
            // 已经拉到结果（Loaded / Unavailable）就没什么可重试的。
            null -> loadChanges(seq)
            else -> Unit
        }
    }

    /**
     * 拉取某个 `workspace/changes` seq 的摘要，结果写进 [ChatUiState.changes]。
     *
     * 三态分开处理（R005 P1-2）：404 → [ChangesEntry.Unavailable]（终态）；
     * 其它异常（超时、403、解析失败）→ [ChangesEntry.Failed]（**可重试**，卡片给按钮）。
     * 把两者混成同一个「取不到」会让一次网络抖动被说成「桌面端重启后记录丢了」，
     * 而且错误归因完全是错的。
     */
    private fun loadChanges(seq: Long) {
        if (seq in _state.value.changesLoading) return
        _state.value = _state.value.copy(changesLoading = _state.value.changesLoading + seq)
        viewModelScope.launch {
            val entry = try {
                changesRepository.summary(sessionId, seq)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                // 取消不算失败：把 loading 标记收回来，否则转圈会永久留在卡片上。
                _state.value = _state.value.copy(
                    changesLoading = _state.value.changesLoading - seq,
                )
                throw cause
            } catch (cause: Exception) {
                ChangesEntry.Failed(describe(cause))
            }
            // 代数校验（R005 P3-10）：快照替换会把窗口外的 seq 从 `changes`/`changesLoading`
            // 一起裁掉。若这次请求在裁剪之后才回来，直接丢弃——否则会把一个已经不在窗口里的
            // seq 重新写回 `changes`，一直留到下个快照。丢弃是安全的：卡片真回到窗口时，
            // 「没有结果也不在加载中」的条件会重新成立，自然会再拉一次。
            if (seq !in _state.value.changesLoading) return@launch
            _state.value = _state.value.copy(
                changes = _state.value.changes + (seq to entry),
                changesLoading = _state.value.changesLoading - seq,
            )
        }
    }

    /** 1s, 2s, 4s, 8s, 16s, 30s, 30s… */
    private fun backoffMillis(attempt: Long): Long {
        val shift = attempt.coerceAtMost(5)
        return (1_000L shl shift.toInt()).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** 把异常翻译成给用户看的一句话。 */
    private fun describe(cause: Throwable): String {
        val typert = cause as? TypertException
        return typert?.let { "${it.code}：${it.message}" }
            ?: (cause.message ?: cause.javaClass.simpleName)
    }

    override fun onCleared() {
        streamJob?.cancel()
        pageJob?.cancel()
        sendJob?.cancel()
        changesJob?.cancel()
        cacheJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        fun factory(
            connection: DshConnection,
            sessionId: String,
            cache: DshCache? = null,
            ownerId: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(connection, sessionId, cache, ownerId) }
        }
    }
}
