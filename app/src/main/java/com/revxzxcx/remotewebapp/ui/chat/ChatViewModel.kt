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
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.QuestionRequest
import com.revxzxcx.remotewebapp.core.events.RemoteEvents
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.ChatArgs
import com.revxzxcx.remotewebapp.core.session.ChatFrame
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ChatReducer
import com.revxzxcx.remotewebapp.core.session.ChatRepository
import com.revxzxcx.remotewebapp.core.session.ChatUiState
import com.revxzxcx.remotewebapp.core.session.FileUploadRepository
import com.revxzxcx.remotewebapp.core.session.ImageLimits
import com.revxzxcx.remotewebapp.core.session.ImageMeasure
import com.revxzxcx.remotewebapp.core.session.ImageRejection
import com.revxzxcx.remotewebapp.core.session.PermissionSnapshot
import com.revxzxcx.remotewebapp.core.session.QuestionSet
import com.revxzxcx.remotewebapp.core.session.AskAnswer
import com.revxzxcx.remotewebapp.core.session.SessionProjectionParser
import com.revxzxcx.remotewebapp.core.session.SessionProjectionRepository
import com.revxzxcx.remotewebapp.core.session.AskQuestion
import com.revxzxcx.remotewebapp.core.session.InteractiveComposerRequest
import com.revxzxcx.remotewebapp.core.session.UserQuestionsSnapshot
import com.revxzxcx.remotewebapp.core.session.permissionOf
import com.revxzxcx.remotewebapp.core.session.userQuestionsOf
import com.revxzxcx.remotewebapp.core.session.KEY_PERMISSIONS
import com.revxzxcx.remotewebapp.core.session.KEY_USER_QUESTIONS
import com.revxzxcx.remotewebapp.core.session.message
import com.revxzxcx.remotewebapp.core.session.ModelRef
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.core.session.SniffedImage
import com.revxzxcx.remotewebapp.core.session.sniffImage
import com.revxzxcx.remotewebapp.core.session.toBase64
import com.revxzxcx.remotewebapp.core.session.validateImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import com.revxzxcx.remotewebapp.core.session.Goal
import com.revxzxcx.remotewebapp.core.session.GoalPhase

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
    val sessionId: String,
    private val cache: DshCache? = null,
    private val ownerId: String = "",
) : ViewModel() {

    private val repository = ChatRepository(connection)
    private val changesRepository = ChangesRepository(connection)

    /** 提问与权限投影的读取（`session/projections` + `session/control` + `userQuestions/answer`）。 */
    private val projections = SessionProjectionRepository(connection)

    /**
     * 附件读取（需求 FR-5 的「图片」）。
     *
     * 与 `repository`/`changesRepository` 一样由 [connection] 提供：请求本身走
     * [DshConnection.typert]，失败时抛 [TypertException]，由 UI 决定显示什么。
     */
    val attachments: SessionAttachmentRepository = connection.attachments

    /** 文件上传（发文件前必须先换回执，见 [FileUploadRepository]）。 */
    private val uploads = connection.uploads

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

    /**
     * 等待本会话审批的请求。
     *
     * 数据源是**应用级**的 [DshConnection.remoteEvents]（订阅随连接长驻，不随聊天页开关），
     * 这里只按 `sessionId` 取子集。之所以不并进 [ChatUiState]：那些请求不是
     * `session/follow` 的产物，reducer 看不到它们；硬塞进去就得给 reducer 开一个
     * 「外来帧」入口，把「状态只有一个来源」这条规矩打破。
     *
     * `SharingStarted.Eagerly` 而不是 `WhileSubscribed`：聊天页随时可能被划掉，
     * 但审批卡片必须在用户回到聊天页时**立刻**就在（不能先闪一下空列表再加载）。
     */
    val approvals: StateFlow<List<ApprovalRequest>> = connection.remoteEvents.approvals
        .map { list -> list.filter { it.belongsTo(sessionId) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 审批通道本身的问题（没连上、回答没送出去）。 */
    val approvalError: StateFlow<String?> = connection.remoteEvents.error
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------------------------------------------------- 提问（用户 m29539）

    /**
     * 等待本会话回答的提问组（`userQuestions` 投影的 `active`）。
     *
     * 数据源与审批**刻意不同**：审批走 `$events` 转发流（桌面端推给我们的），
     * 提问走 `session/projections` 投影 + `session/control` 增量帧。原因是提问的
     * `callId` 只存在于投影里——`$events` 的 waterfall 帧 request 里
     * `wait?:{callId}` 是**可选**字段，拿不到 callId 就没法调
     * `userQuestions/answer`（args 键集恰好三个：`agentId`/`callId`/`answer`）。
     *
     * 同样不并进 [ChatUiState]：投影不是 `session/follow` 的产物。
     *
     * 刷新四条路（缺一不可，见 [refreshQuestions] 的 KDoc）：
     *  1. `start()` 里一次 unary 首读——`session/control` 的 baseline 只含桌面端
     *     当前选中会话，冷启动时我们这个会话大概率不在里面；
     *  2. `session/control` 的 `projection` 帧——桌面端/手机端任何一方回答了问题，
     *     服务端都会发增量帧，这是「防重复回答」的主要机制；
     *  3. `$events` 的 `user-questions/request` waterfall——有提问发生时立刻重读一次
     *     （投影帧可能因为会话不是当前选中会话而不发）；
     *  4. 生成中每 [QUESTION_REFRESH_MS] 重读一次——兜底，保证卡片在别人答掉后消失。
     */
    private val _questions = MutableStateFlow<List<QuestionSet>>(emptyList())
    val questions: StateFlow<List<QuestionSet>> = _questions.asStateFlow()

    /**
     * 当前应该**占住输入框**的请求（用户 m29539：与 DSH 桌面端一致）。
     *
     * 优先级：审批 > 提问。理由：审批卡住的是**正在执行的工具**（安全闸门），
     * 超时或漏答会让整轮停住；提问只是等一个输入。两个同时来时不并排堆两个卡片，
     * 先把安全闸门放行。被挡掉的那个不会丢——这几条源都是独立流，
     * 交互窗口消失后下一个会顶上来。
     *
     * 草稿从 [_state].questionDrafts 里读，所以这个流同时依赖 [_questions]（投影里的
     * 提问组）、[connection] 的 `remoteEvents.questions`（waterfall 立刻推来的提问）、
     * [state]（选了哪几项）与 [approvals]（有没有待批）——四者都是发射源，一个都少不得：
     * 审批通常恰好在生成停止（`_state` 不再变化）时到达，少列它就会出现「静默会话里
     * 审批卡片不渲染」。
     *
     * ## 两条提问路怎么合并
     * **投影是权威来源**（桌面端 README 原话）。waterfall 只负责「立刻弹」——
     * 投影最快也要等一次轮询（2.5s）。合并规则：
     *  - 投影有 open 组 → 用投影的（带 `callId`，能直接调 `userQuestions/answer`）；
     *  - 投影没有、waterfall 有 → 用 waterfall 的。
     *
     * ## 草稿键：有 `callId` 就用真实 callId
     * waterfall 帧的 `wait.callId` 与投影组的 `callId` 是**同一个问题**的同一个 id
     * （实测定案）。所以两边共用同一个草稿键，用户在「waterfall 刚弹出来」到
     * 「投影刷新覆盖它」这几百毫秒里点的选项、打的自定义答案才不会丢。
     * 只有 `callId` 取不到时才回落到 `waterfall:<eventId>` 合成 id。
     *
     * 合成 id 也解释了为什么回答要走两条不同的路（见 [answerQuestionGroup]）。
     */
    val interactive: StateFlow<InteractiveComposerRequest?> =
        combine(_questions, _state, approvals, connection.remoteEvents.questions) { questions, ui, pending, pushed ->
            pending.firstOrNull()?.let { request ->
                InteractiveComposerRequest.Approval(
                    eventId = request.eventId,
                    toolName = request.toolName,
                    reason = request.reason,
                )
            } ?: questions.firstOrNull()?.let { group ->
                InteractiveComposerRequest.Question(
                    callId = group.callId,
                    questions = group.questions,
                    drafts = ui.questionDrafts[group.callId].orEmpty(),
                )
            } ?: pushed.firstOrNull { it.belongsTo(sessionId) }?.let { request ->
                val key = request.callId ?: waterfallCallId(request.eventId)
                InteractiveComposerRequest.Question(
                    callId = key,
                    questions = request.questions,
                    drafts = ui.questionDrafts[key].orEmpty(),
                )
            }
        }
            // `_state` 在 assistant-stream 期间每个 token 都变，不打断的话每个 token 都会
            // 重建一个 Question 对象并重组整张卡片（流式高峰里这是白烧帧）。
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 提问通道的问题（读不到投影、回答没送出去）。 */
    private val _questionError = MutableStateFlow<String?>(null)
    val questionError: StateFlow<String?> = _questionError.asStateFlow()

    /**
     * 权限预设（`permissions` 投影的 `currentValue`），null = 还读不到。
     *
     * UI 实际读的是 [state].permission（经 `ChatReducer.applyPermissionProjection` 桥接），
     * 这里只留作「投影与事件折叠两条路是否一致」的旁证，暂无界面消费方。
     */
    private val _permission = MutableStateFlow<PermissionSnapshot?>(null)
    val permission: StateFlow<PermissionSnapshot?> = _permission.asStateFlow()

    private var pageJob: Job? = null
    private var sendJob: Job? = null

    /** 「编辑/删除/插队」排队消息的协程（`session/updateQueue`）。 */
    private var queueJob: Job? = null

    private var changesJob: Job? = null
    private var cacheJob: Job? = null

    /** 投影一次性首读 / 失败回补的协程（`session/projections`）。 */
    private var projectionJob: Job? = null

    /** 生成中周期重读的协程。**不能与 [projectionJob] 共用**。 */
    private var questionPollJob: Job? = null

    /** `session/control` 实时投影流的协程。 */
    private var controlJob: Job? = null

    /** 回答提问的协程（`userQuestions/answer`）。 */
    private var answerJob: Job? = null

    /**
     * 排队回声的本地序号源。
     *
     * 回声在拿到服务端 `itemId` 之前用 `local:<n>` 作 id（只做 Compose 的 `key` 与
     * 「RPC 失败时摘掉哪一条」的身份）。真正的关联键是 [QueuedMessage.rpcId]——也就是
     * 下面 `send()` 自己生成并传给 `session/prompt` 的 requestId，服务端会在
     * `UserMessage.source.rpcId` 里原样带回。
     */
    private var queuedEchoSeq = 0L

    /**
     * 拉可选模型目录的协程。
     *
     * 与 [modelJob] **必须分开**（R007 P1-2）：早先共用一个 `modelJob`，导致
     * 「目录还在加载」会把用户随后点的模型选择整个挡掉。
     */
    private var catalogJob: Job? = null

    /** 切换模型的协程（`session/selectModel`）。 */
    private var modelJob: Job? = null

    /** 「选文件 → 上传 → 入待发送」整条链的协程。 */
    private var attachJob: Job? = null
    private var streamJob: Job? = null

    /**
     * 附件相关的一次性提示：读文件失败、超出图片上限、上传失败。
     *
     * 放 ViewModel 而不是 Composable 的 `remember`（R007 P2-3）：上传跑在
     * [viewModelScope] 里，旋转屏幕不会取消它；提示若还在 UI 侧，旋转就被重置，
     * 用户看不到任何反馈。
     */
    private val _attachNotice = MutableStateFlow<String?>(null)
    val attachNotice: StateFlow<String?> = _attachNotice.asStateFlow()

    /** 待发送附件（同样放 ViewModel：旋转屏幕不丢，已上传换到回执的不必重传）。 */
    private val _pending = MutableStateFlow<List<PromptAttachment>>(emptyList())
    val pending: StateFlow<List<PromptAttachment>> = _pending.asStateFlow()

    init {
        warmStart()
        start()
        watchChanges()
        watchQuestions()
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
     *
     * 文字可以为空——只要挂了附件。「只发图不发字」是常见用法，服务端
     * `hasPromptContent` 明确要求的是「非空白文本**或**一个附件」
     * （`dsh-api-session-controller/lib/index.js:851`）。这里若在空文本时直接
     * return，输入区的「发送」按钮是亮的（`draft.isNotBlank() || pending.isNotEmpty()`），
     * 点下去却毫无反应也不报错——用户在设备上看到的就是「按钮坏了」。
     *
     * 排队回声（用户 m20380 的缺口）：`mode:'queue'` 的消息要等当前轮结束才进会话日志，
     * 所以发送前先挂一条本地回声，让用户立刻看到「我的话在排队」；RPC 失败时摘掉它。
     * 回声的 `rpcId` 就是本次的 requestId——服务端会在 `agent/inbox/spliced` 的
     * `inserted[].source.rpcId` 里原样带回，届时 [ChatReducer] 把它换成带 `itemId`
     * 的服务端条目，于是编辑/删除/插队三个操作才有对象可指（用户 m21177 第 4 条反馈）。
     *
     * 只在 [ChatUiState.generating] 为真时挂回声：不生成时消息会立刻进会话日志，
     * 真实 `user/message` 一两个往返内就到，此时挂一条「排队中 · 等当前轮结束后发出」
     * 是错的文案（用户 m20380 的场景只发生在生成中）。
     *
     * 输入框被审批/提问占用时**不允许发送**（用户 m29539：桌面端卡片占住编辑器时
     * 用户没法同时打字）。UI 已经把输入区换掉了，这里是第二道防线——防止以后有人
     * 加了第二条发送入口（比如快捷键）绕过 UI。
     */
    fun send() {
        val text = _draft.value.trim()
        val attachments = _pending.value
        if (text.isEmpty() && attachments.isEmpty()) return
        if (sendJob?.isActive == true) return
        if (interactive.value != null) {
            _attachNotice.value = "先回答上面的请求，再发新消息。"
            return
        }
        // T5 设备验收：发送是网络调用，期间「发送」按钮必须进入不可点的加载态。
        // 否则用户看到按钮一直亮着，会反复点（每次都白跑一次 RPC）。
        _state.value = _state.value.copy(sending = true)
        val echoId = "local:${++queuedEchoSeq}"
        val requestId = java.util.UUID.randomUUID().toString()
        // 只有真的在排队才需要回声（见 KDoc）。不在生成时也挂的话，每发一条正常消息
        // 都会闪一下「排队中」，而它并不排队。
        val echo = if (_state.value.generating) {
            QueuedMessage(
                id = echoId,
                rpcId = requestId,
                text = text,
                attachmentCount = attachments.size,
                enqueuedAt = System.currentTimeMillis(),
            ).also { added ->
                _state.value = _state.value.copy(queued = _state.value.queued + added)
            }
        } else {
            null
        }
        sendJob = viewModelScope.launch {
            try {
                // 文件附件在上面的 attachFile 里就已经上传换好回执了，这里直接发。
                // 上传失败时它不会进 _pending，所以 content 数组不会出现残缺块。
                repository.prompt(
                    sessionId = sessionId,
                    text = text,
                    mode = ChatArgs.PromptMode.Queue,
                    attachments = attachments,
                    clientTimeZone = java.util.TimeZone.getDefault().id,
                    requestId = requestId,
                )
                _draft.value = ""
                _pending.value = emptyList()
                _attachNotice.value = null
                _state.value = _state.value.copy(error = null, sendError = null)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                // 取消不是发送失败：`onCleared`/`retry` 会取消在途的发送，此时不该把
                // 「已取消」写成错误横幅（`CancellationException` 是 `Exception` 的子类）。
                // 回声也不动——服务端可能已经收下了，摘掉反而让用户以为没发出去。
                throw cause
            } catch (cause: Exception) {
                // 回声要摘掉：这条消息没有进服务端队列，留着就是一个永远不会被
                // `user/message` 换掉的幽灵气泡（只能等冷启动才消失）。
                _state.value = _state.value.copy(
                    queued = _state.value.queued.filterNot { it.id == echoId },
                    sendError = describe(cause),
                )
            } finally {
                _state.value = _state.value.copy(sending = false)
            }
        }
    }

    // ------------------------------------------------- 排队队列（用户 m21177 第 4 条）

    /**
     * 对排队里的消息做操作（编辑 / 删除 / 插队）。
     *
     * 三者都走 `session/updateQueue`（[ChatRepository.updateQueue]）。**失败时把服务端
     * 文案挂到 `sendError` 上并让 reducer 自行纠正**： splice 事件是权威的，服务端拒绝
     * 我们的操作时不会发 splice，所以界面会保持原样——那时用户看到的就是错误条。
     *
     * @param message 目标条目；`confirmed == false`（本地回声还没拿到 itemId）时直接忽略，
     *   因为服务端只认 `itemId`。
     */
    private fun queueAction(message: QueuedMessage, action: ChatArgs.QueueAction, pending: String?) {
        if (!message.confirmed) return
        queueJob?.cancel()
        queueJob = viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            try {
                repository.updateQueue(sessionId, message.id, action)
                _state.value = _state.value.copy(sendError = null)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Exception) {
                _state.value = _state.value.copy(sendError = describe(cause))
            } finally {
                _state.value = _state.value.copy(sending = false)
            }
        }
    }

    /** 编辑一条排队消息的文本（服务端只接受纯文本，附件不能跟着改）。 */
    fun editQueued(message: QueuedMessage, text: String) {
        if (text.isBlank()) {
            // 服务端对空白内容抛 `gateway/bad-request`（`index.js:940` 的 hasPromptContent）。
            // 这里先挡一道，别让用户看到一句英文错误码。
            _state.value = _state.value.copy(sendError = "排队消息不能改成空白内容")
            return
        }
        queueAction(message, ChatArgs.QueueAction.Edit(text.trim()), null)
    }

    /** 撤掉一条排队消息。 */
    fun removeQueued(message: QueuedMessage) = queueAction(message, ChatArgs.QueueAction.Remove, null)

    /**
     * 插队：不等当前轮结束，立刻进当前轮。
     *
     * 服务端要求该条目在 `next-turn` 且会话正在运行，否则抛 `session/steer-unavailable`
     * （`index.js:966`）。UI 因此只在 `generating` 时才显示这个按钮。
     */
    fun steerQueued(message: QueuedMessage) = queueAction(message, ChatArgs.QueueAction.Steer, null)

    // ---------------------------------------------------- 目标（R031）

    /**
     * 目标动词的**串行闸**。
     *
     * 不能用「`job?.cancel()` 再赋新值」那种单飞写法（R031 独立审查 P3）：goal 的
     * pause / resume / edit / clear 之间有**顺序语义**，且都带 `ref.revision` 做 CAS。
     * 取消在飞的那个请求会同时毁掉两件事——它已经发出的 RPC 可能已经到达服务端（目标
     * 状态实际变了），而协程被取消后又不会上报失败，于是界面停在旧状态且没有任何提示。
     * 用 Mutex 排队：后一个动作等前一个返回，读到的 revision 也就跟着 `goal/change`
     * 事件一起更新了。
     */
    private val goalMutex = Mutex()

    /** 目标动作的统一执行体：串行、失败落到 [goalError]、成功清掉它。 */
    private fun runGoalAction(failurePrefix: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            goalMutex.withLock {
                try {
                    action()
                    _state.value = _state.value.copy(goalError = null)
                } catch (cause: kotlinx.coroutines.CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    _state.value = _state.value.copy(
                        goalError = "$failurePrefix${describe(cause)}",
                    )
                }
            }
        }
    }

    /**
     * 暂停 / 继续目标。
     *
     * ⚠️ **不中止当前轮、不碰任务列表**：已查证 DSH 源码，`goals/pause` 只让 Host
     * GoalService 排一条「模型可见的 goal 上下文消息」，当前这一轮的对话继续输出完
     * （见 [Goal] 的 KDoc）。所以这里除了发 RPC 之外什么都不做。
     *
     * @param pause true = 暂停，false = 继续。由 [Goal.canPause] 决定。
     */
    fun setGoalPhase(pause: Boolean, goal: Goal) {
        if (goal.phase == GoalPhase.COMPLETE) return
        runGoalAction("目标操作失败：") {
            if (pause) {
                repository.pauseGoal(sessionId, goal.id, goal.revision)
            } else {
                repository.resumeGoal(sessionId, goal.id, goal.revision)
            }
            // 本地不乐观更新：权威结果由 `goal/change` 事件回灌（revision 也会跟着变，
            // 下一次操作的 CAS 才不会用陈旧值）。
        }
    }

    /** 编辑目标正文。`maxGoalRounds` 不可改（创建时参数）。 */
    fun editGoal(goal: Goal, objective: String) {
        val trimmed = objective.trim()
        if (trimmed.isEmpty()) return
        runGoalAction("编辑目标失败：") {
            repository.editGoal(sessionId, goal.id, goal.revision, trimmed)
        }
    }

    /** 删除（清除）目标。调用方必须先弹二次确认。 */
    fun clearGoal(goal: Goal) {
        runGoalAction("删除目标失败：") {
            repository.clearGoal(sessionId, goal.id, goal.revision)
        }
    }

    /** 关掉目标操作的错误提示。目标失败不提供「重试」——重新点按钮就是重试。 */
    fun dismissGoalError() {
        _state.value = _state.value.copy(goalError = null)
    }

    // ---------------------------------------------------- 审批（用户 m28009）

    /**
     * 回答一个审批请求。
     *
     * @param allow true = 允许一次；false = 拒绝。服务端**没有**「永久允许」，只有 one-shot
     *   （`@deepseek-ai/dsh-user-approval` 的 `PendingApproval` 只接受这两种）。
     *
     * 不需要挂起：回答走 unary RPC，由 [RemoteEventsClient] 自己在内部管协程。UI 因此可以
     * 在点击时立刻得到反馈（卡片消失），不必先置一个「正在回答」的中间态。
     */
    fun answerApproval(eventId: String, allow: Boolean) {
        viewModelScope.launch {
            try {
                connection.remoteEvents.answer(eventId, allow)
            } catch (cause: Throwable) {
                if (cause is kotlinx.coroutines.CancellationException) throw cause
                // RemoteEventsClient 自己已经把错误写进它的 `error` 流并保留卡片，
                // 这里不需要再写一份——否则同一个故障会在两处各显示一次。
            }
        }
    }

    /** 划掉一个审批请求（不回答）。 */
    fun dismissApproval(eventId: String) = connection.remoteEvents.dismiss(eventId)

    /** 清掉审批通道的错误说明。 */
    fun clearApprovalError() = connection.remoteEvents.clearError()

    /**
     * 提问/权限投影的读取与刷新。
     *
     * 四条刷新路（见 [_questions] 的 KDoc）：
     *  1. **unary 首读**：`session/control` 的 baseline 只含桌面端当前选中会话
     *     （实测 90 个会话只回 1 个），所以冷启动必须自己读一次；
     *  2. **control 流**：实时增量帧，只认 `sessionId` 匹配的本会话；
     *  3. **`$events` waterfall**：收到 `user-questions/request` 就重读一次投影；
     *  4. **生成中周期重读**：兜底，保证别人答掉后卡片会消失。
     *
     * 后三条都由 [_questions]/[ChatUiState.generating] 的变化驱动，所以整个逻辑是
     * 「先铺首读 → 之后只按事件刷新」，没有常驻轮询在会话不生成时跑。
     */
    private fun watchQuestions() {
        refreshQuestions()
        controlJob = viewModelScope.launch {
            projections.control()
                // 断线后必须**自己重订**。mux 上所有流共享一条 WebSocket，载体一断
                // `failCarrier` 就用同一个 cause 关掉全部流的 Channel；`callbackFlow` 把它
                // 作为**流的终止原因**抛给收集方。而 [watchQuestions] 只在 `init` 里调一次，
                // `start()`（`session/follow`）重连的是它自己那条流，**不会**重订 control ——
                // 所以这里不重试就等于「这条会话的实时投影永久失效」：桌面端把问题答掉之后，
                // 手机端不会消失卡片，只能等下一次 `questionSignal` 或生成中轮询兜底。
                .retryWhen { cause, attempt ->
                    if (cause is TypertException && cause.isUnpaired) {
                        // 凭证失效：重试只会每 30s 打一次 403，交给「重新配对」出口。
                        // 与 [start] 一致，把 unpaired 投影到载波（全局状态条是唯一出口）。
                        connection.mux.noteRetry(describe(cause), 0L, unpaired = true)
                        false
                    } else {
                        val backoffMs = backoffMillis(attempt)
                        // 与 [start] / `HomeViewModel` / `RemoteEventsClient` 一致：重试计划
                        // 由持有它的一方汇报给 mux，否则状态条会停在「已连接」而实际在退避。
                        connection.mux.noteRetry(describe(cause), backoffMs)
                        delay(backoffMs)
                        true
                    }
                }
                // 🔴 必须接住流的终止异常，**这就是真机崩溃的修复点**。
                // 真机实录（OnePlus PKG110，点开会话 25s 后）：桌面端进程退出让 TCP 断开且
                // 没有关闭帧，OkHttp 读循环抛 `java.io.EOFException`，`loopReader` 自己 catch
                // 后走 `failWebSocket` → `MuxListener.onFailure` → `RemoteStreamMux.failCarrier`
                // 用**同一个 cause** 关闭所有流的 Channel；`callbackFlow` 把它当作**流的终止
                // 原因**抛给收集方。这里原本没有 `.catch`，而 `viewModelScope` 是
                // `Dispatchers.Main.immediate` → 异常逃出协程 → `FATAL EXCEPTION: main`
                // → 进程死。用户看到的就是「点开一个对话软件自动退出」。
                //
                // ⚠️ `RemoteStreamMux.MuxListener` 那层 `runCatching` **挡不住这条路径**
                //（异常不从 OkHttp 回调线程逃逸），它只是纵深防御。所以每个 `mux.open()`
                // 的消费方都必须自己 `.catch` —— 这条不变式写在 `RemoteStreamMux.open()` 的 KDoc 里。
                .catch { cause ->
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    // 不写 `_questionError`：这是**连接级**故障，横幅已经由 carrier 状态条
                    // 承担，再写一条会把「投影读不到」和「断线了」混成同一句话。
                    // 走到这里只剩两种：`unpaired`（`retryWhen` 主动放弃，交给重新配对出口）
                    // 和 `ViewModel.onCleared` 的取消（已被上面重抛）。普通断线在上面的
                    // `retryWhen` 里就退避重试了，不会到这里。
                }
                .collect { frame ->
                    when (frame) {
                        is SessionProjectionRepository.ProjectionFrame.Projection -> {
                            if (frame.sessionId != sessionId) return@collect
                            when (frame.key) {
                                KEY_USER_QUESTIONS -> applyQuestions(
                                    SessionProjectionParser.questions(frame.value),
                                )

                                KEY_PERMISSIONS -> {
                                    _permission.value = SessionProjectionParser.permission(frame.value)
                                    _state.value = ChatReducer.applyPermissionProjection(
                                        _state.value,
                                        _permission.value?.preset,
                                    )
                                }
                            }
                        }

                        is SessionProjectionRepository.ProjectionFrame.Baseline -> {
                            // baseline 只含桌面端当前选中会话，所以绝大多数时候这里是空操作。
                            val values = (frame.projections[sessionId] as? JsonObject)
                                ?.get("values") as? JsonObject
                            if (values != null) {
                                userQuestionsOf(values)?.let { applyQuestions(it) }
                                permissionOf(values)?.let { found ->
                                    _permission.value = found
                                    _state.value = ChatReducer.applyPermissionProjection(
                                        _state.value,
                                        found.preset,
                                    )
                                }
                            }
                        }
                    }
                }
        }
        viewModelScope.launch {
            // waterfall 帧一到就**同步**铺默认草稿，不能只等 refreshQuestions() 的 unary
            // 往返：那张卡是 `interactive` 立刻渲染出来的，等投影的几百毫秒里用户点「提交」
            // 会撞上「还没有草稿」而被挡住，报的还是一句看不懂的错。
            connection.remoteEvents.questionSignal.collect {
                seedWaterfallDefaults()
                refreshQuestions()
            }
        }
        viewModelScope.launch {
            _state.map { it.generating }.distinctUntilChanged().collect { generating ->
                questionPollJob?.cancel()
                if (!generating) return@collect
                questionPollJob = viewModelScope.launch {
                    while (true) {
                        delay(QUESTION_REFRESH_MS)
                        refreshQuestions()
                    }
                }
            }
        }
    }

    /**
     * 读一次投影并写入 [_questions] / [_permission]。
     *
     * 失败**不清空**已有卡片：一次网络抖动就让待答问题消失，用户会以为问题已经被
     * 别人答掉了（实际上没有），比留着卡片更糟。错误只写进 [_questionError]。
     *
     * 重入守卫只保护**一次性读**（首读、失败回补）。生成中的周期轮询走
     * [questionPollJob]，与 [projectionJob] 分开——早先两者共用一个字段，轮询循环
     * 自己把自己 `isActive == true` 拦住，整段生成期里 unary 兜底刷新一次都没跑过，
     * 回答失败后的回读也被吞掉。
     */
    private fun refreshQuestions() {
        if (projectionJob?.isActive == true) return
        projectionJob = viewModelScope.launch {
            val snapshot = try {
                projections.read(sessionId)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Exception) {
                _questionError.value = describe(cause)
                return@launch
            }
            _questionError.value = null
            applyQuestions(snapshot.questions ?: UserQuestionsSnapshot.EMPTY)
            snapshot.permission?.let { found ->
                _permission.value = found
                _state.value = ChatReducer.applyPermissionProjection(_state.value, found.preset)
            }
        }
    }

    /**
     * 只保留还开着的提问组；`settled` 里的 callId 一律视为已被回答。
     *
     * `settled` 是**别人答掉**的账（桌面端或另一个客户端回答了同一个问题）。投影组从
     * `active` 消失时，`interactive` 会回退到 waterfall 上那张孪生卡片，用户看到一张
     * 刚刚被别人亲口答过的题——所以这里必须按 `settledCallIds` 把 waterfall 条目一起摘掉
     * （R026 审查 P2：这一条此前只写在注释里，没有实现）。
     */
    private fun applyQuestions(snapshot: UserQuestionsSnapshot) {
        val open = snapshot.active.filter { it.isOpen && it.questions.isNotEmpty() }
        _questions.value = open
        snapshot.settledCallIds.forEach { settled -> connection.remoteEvents.forgetQuestion(settled) }
        // 桌面端规格：**当前**那道题预选第一个选项（未提交草稿）。`enterQuestion` 对已有
        // 草稿的题是 no-op，所以重读投影不会把用户已经选好的东西冲掉。
        //
        // 只铺到「第一道还没有草稿的题」为止，**不能给组内每道题都铺**：铺满之后每道题的
        // 草稿都是 isAnswered，`InteractiveComposerRequest.Question.currentIndex()` 的
        // `indexOfFirst` 全部落空 → 回落到 lastIndex → 多题组只显示最后一道题，前 N-1 道
        // 被按预选项自动作答（R025 UI 层 P1-1，渲染测试因为直接构造空 drafts 而完全看不见）。
        open.forEach { set -> seedDefaults(set.callId, set.questions) }
        // waterfall 帧不带 `wait.callId` 时，卡片在投影刷新到位之前用的是 `waterfall:<eventId>`
        // 合成键，用户在这几百毫秒里点的选项、打的自定义答案都存在那个键下。投影一到位
        // `interactive` 就改读真实 callId，键不迁移的话这些输入从界面上消失
        // （map 里还在，但再也读不到）——R026 审查 P2。
        //
        // 迁移条件：合成键对应的 waterfall 条目**还活着**（说明投影组就是它），
        // 且真实 callId 那一侧还没有草稿（避免覆盖用户后来直接按真实 callId 填的东西）。
        connection.remoteEvents.questions.value
            .filter { it.belongsTo(sessionId) && it.callId == null }
            .forEach { request ->
                val from = waterfallCallId(request.eventId)
                val to = open.firstOrNull { it.callId == request.callId }?.callId
                    ?: return@forEach
                migrateQuestionDrafts(from, to)
            }
        // 卡片消失（被别人答掉 / 被撤销）时草稿也要跟着走，否则同一 callId 若被服务端
        // 复用，旧草稿会套到新问题上。
        //
        // `live` 必须**同时**包含投影 callId 与 waterfall 合成 id：waterfall 那组的草稿
        // 也存在同一个 map 里（键是 `waterfall:<eventId>`），漏掉它就会把用户正在填的
        // 自定义答案在下一次投影刷新时清空。
        val live = open.map { it.callId }.toSet() +
            connection.remoteEvents.questions.value
                .filter { it.belongsTo(sessionId) }
                .map { it.callId ?: waterfallCallId(it.eventId) }
        _state.value = ChatReducer.pruneQuestionDrafts(_state.value, live)
    }

    /**
     * 把一组草稿从一个键搬到另一个键（waterfall 合成键 → 投影真实 callId）。
     *
     * 目标键已经有草稿时**什么都不做**——那说明用户已经在按真实 callId 填了，
     * 搬过去会把后来者覆盖掉。
     */
    private fun migrateQuestionDrafts(from: String, to: String) {
        if (from == to) return
        val drafts = _state.value.questionDrafts[from] ?: return
        if (_state.value.questionDrafts.containsKey(to)) return
        _state.value = _state.value.copy(
            questionDrafts = _state.value.questionDrafts - from + (to to drafts),
        )
    }

    /** 给一组题铺默认草稿：只铺到「第一道还没有草稿的题」为止。见 [ChatReducer.seedQuestionDefaults]。 */
    private fun seedDefaults(callId: String, questions: List<AskQuestion>) {
        _state.value = ChatReducer.seedQuestionDefaults(_state.value, callId, questions)
    }

    /** waterfall 路径同样要铺默认草稿（投影路在 [applyQuestions] 里铺）。 */
    private fun seedWaterfallDefaults() {
        connection.remoteEvents.questions.value
            .filter { it.belongsTo(sessionId) }
            .forEach { request ->
                seedDefaults(request.callId ?: waterfallCallId(request.eventId), request.questions)
            }
    }

    /** 点某个选项（单选互斥 / 多选累加；单选下点已选项幂等，见 D65）。 */
    fun toggleQuestionOption(callId: String, question: AskQuestion, label: String) {
        _state.value = ChatReducer.toggleOption(_state.value, callId, question, label)
    }

    /** 写自定义答案（单选下自动清掉已选）。 */
    fun setQuestionCustom(callId: String, question: AskQuestion, value: String) {
        _state.value = ChatReducer.setCustomAnswer(_state.value, callId, question, value)
    }

    /**
     * 提交整组提问（桌面端规格：Enter 提交整组，缺答案时跳到第一道未完成的题）。
     *
     * 没答的题**挡住提交**，而不是补一条空答案静默发出去——后者会让用户以为
     * 「我点的就是我要的」，实际上前几道题被按预选项自动答掉了
     * （R025 UI 层 P1-1 的同一个根因，只是发生在提交那一刻）。
     *
     * **没有 `?: AskAnswer(id = …)` 回落**（R026 审查 P1）：守卫只看「有没有草稿键」的
     * 那个版本能被「键在但没有答案」绕过（无预设选项的题、多选取消预选项、自定义答案删空），
     * 于是走到这里补一条空答案发出。守卫改成数 [ChatReducer.unansweredCount]（答案维度）
     * 之后回落不可达，留着它就是下一次静默提交的通道。
     */
    fun submitQuestionGroup(callId: String) {
        val group = questionGroupOf(callId)
        if (group == null) {
            _questionError.value = "这一组问题已经不在等待回答了"
            return
        }
        val missing = ChatReducer.unansweredCount(_state.value, callId, group)
        if (missing > 0) {
            _questionError.value = "还有 $missing 道题没有答案，请先答完再提交"
            return
        }
        val answers = group.mapNotNull { question ->
            ChatReducer.answersOf(_state.value, callId, listOf(question)).firstOrNull()
        }
        if (answers.size != group.size) {
            // 守卫已经保证每道题都有答案，走到这里说明草稿在两次读之间变了（投影刷新、
            // 或者别的入口清了草稿）。宁可挡住也不发半组。
            _questionError.value = "答案刚刚发生了变化，请再点一次提交"
            return
        }
        answerQuestionGroup(callId, answers)
    }

    /** 整组跳过：全部发空答案。 */
    fun skipQuestionGroup(callId: String) {
        val group = questionGroupOf(callId)
        if (group == null) {
            _questionError.value = "这一组问题已经不在等待回答了"
            return
        }
        answerQuestionGroup(callId, group.map { AskAnswer(id = it.id) })
    }

    /**
     * 按 callId 找出题组。**两条路都查**：投影（真实 callId）与 waterfall。
     *
     * waterfall 那边按 `callId` 匹配（[InteractiveComposerRequest.Question] 现在用的就是
     * 真实 callId），取不到 `callId` 的老条目才按合成 id 兜底。
     *
     * 返回的 [AskQuestion] 列表对两条路是同一种东西，所以上层不必区分来源。
     */
    private fun questionGroupOf(callId: String): List<AskQuestion>? {
        val fromProjection = _questions.value.firstOrNull { it.callId == callId }
        if (fromProjection != null) return fromProjection.questions
        val eventId = callId.removePrefix(WATERFALL_PREFIX).takeIf { it != callId }
        return connection.remoteEvents.questions.value
            .firstOrNull { it.callId == callId || it.eventId == eventId }
            ?.questions
    }

    /**
     * 回答一组提问。
     *
     * ## 两条回答路（按「这个 callId 当前在不在投影里」分派）
     *  - **在投影里** → `userQuestions/answer`（`session` 命名空间的 unary RPC），
     *    args 恰好 `{agentId, callId, answer}`。这是服务端**记录在案**的提问：
     *    投影里看得见、别人也能看到它被回答。
     *  - **只在 waterfall 里** → `$events/result` 回
     *    `{kind:'result', value:{answers:[…]}}`。这种提问服务端还没落到投影上
     *    （`agent.status` 还没变成 `awaiting_user`），只能从 waterfall 本身回答。
     *
     * 分派判据**不能**是「callId 带不带 `waterfall:` 前缀」：waterfall 帧带 `callId` 时
     * 草稿与卡片用的都是真实 callId，前缀法会把它错路由到投影路（那边必然找不到组）。
     *
     * @param answers 该组所有问题的答案。**缺的题不要放**：服务端按 `answers`
     *   数组覆盖，缺的题会被当成空答案；桌面端的规则是「Enter 提交整组，缺答案时回到
     *   第一道未完成的题」，所以「整组都答完才提交」由调用方（UI）保证。
     */
    fun answerQuestionGroup(callId: String, answers: List<AskAnswer>) {
        if (answers.isEmpty()) {
            _questionError.value = "这一组问题还没有答案"
            return
        }
        if (answerJob?.isActive == true) return
        val fromProjection = _questions.value.any { it.callId == callId }
        answerJob = viewModelScope.launch {
            try {
                if (fromProjection) {
                    projections.answerQuestions(sessionId, callId, answers)
                    _questions.value = _questions.value.filterNot { it.callId == callId }
                    // waterfall 上可能还挂着同一条（`callId` 对上就是同一个问题）。
                    // 不摘的话投影组一消失，`interactive` 会回退到一张已经答过的卡片，
                    // 用户再点一次就是对同一个 eventId 发第二次 outcome。
                    connection.remoteEvents.forgetQuestion(callId)
                } else {
                    connection.remoteEvents.answerQuestions(callId, answers)
                }
                // 草稿跟着卡片一起走：答案已经进了服务端，留着草稿只会让下一组同 id 的题
                // 一开始就带着上一次的选项。**必须在成功分支里清**——放在协程外面的话，
                // 回答失败时用户手打的自定义答案已经没了，而卡片还在，只能重打一遍。
                _state.value = ChatReducer.clearQuestion(_state.value, callId)
                _questionError.value = null
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Exception) {
                _questionError.value = describe(cause)
                // 以服务端为准：可能别人已经答掉了（卡片不该回来），也可能还开着（该回来）。
                refreshQuestions()
            }
        }
    }

    /** 清掉提问通道的错误说明。 */
    fun clearQuestionError() {
        _questionError.value = null
    }

    /**
     * 由 waterfall 帧的 `eventId` 合成草稿命名空间用的 callId。
     *
     * **必须带前缀**：真实 `callId` 是服务端发的，理论上不与 `waterfall:` 开头冲突
     * （`userQuestions` 的 callId 形如 `call_00_…`），带前缀只是双保险。真正的原因是
     * 草稿按 callId 存进 `ChatUiState.questionDrafts`，两条路若共用裸 id，
     * 「投影那组」与「waterfall 那组」的草稿会互相覆盖。
     */
    private fun waterfallCallId(eventId: String): String = WATERFALL_PREFIX + eventId

    // ---------------------------------------------------------------- 附件（需求 FR-5）

    /**
     * 服务端对这一轮图片的硬限制（`projections.values.imageLimits`）。
     *
     * 取自 `session/follow` 快照的投影，随会话变化（换模型会变）。取不到时是
     * [ImageLimits] 默认值 = 不限制，由服务端自己挡。
     */
    var imageLimits: ImageLimits = ImageLimits()
        private set

    /**
     * 加入一张待发送的图片。
     *
     * @param bytes 原始字节（不是 base64 后）。
     * @param mediaType 声明出来的 MIME（通常来自 `contentResolver.getType`）。
     *   **会被字节嗅探覆盖**，见 [sniffImage]。
     * @param name 文件名，可为 null。
     * @return 被 [ImageLimits] 挡下时的中文理由；成功加入返回 null。
     */
    fun attachImage(
        bytes: ByteArray,
        mediaType: String,
        name: String?,
        measure: ImageMeasure,
    ): ImageRejection? {
        // T5 设备验收实测：只信声明值会让桌面端回
        // `session/attachment-invalid: Declared image type does not match its bytes.`
        // 这里先按魔数纠正，认得出的类型一律以字节为准。
        val effectiveType = when (val sniffed = sniffImage(bytes)) {
            is SniffedImage.Supported -> sniffed.mediaType
            is SniffedImage.Unsupported -> {
                val rejection = ImageRejection.UnsupportedType(sniffed.label)
                _attachNotice.value = rejection.message()
                return rejection
            }

            SniffedImage.Unknown -> mediaType
        }
        val already = _pending.value.filter { it.kind == PromptAttachment.Kind.IMAGE }
        // base64 后长度 ≈ 原始 × 4/3，反推已挂图片占用的原始字节。
        val alreadyBytes = already.sumOf { it.data.length * 3L / 4L }
        val rejection = imageLimits.validateImage(measure, effectiveType, already, alreadyBytes)
        // 提示一并落到 ViewModel（R007 P2-3）：早先由 UI 侧 `remember` 承载，
        // 旋转屏幕就没了。成功时清掉上一次的提示。
        _attachNotice.value = rejection?.message()
        if (rejection != null) return rejection
        _pending.value = _pending.value + PromptAttachment(
            kind = PromptAttachment.Kind.IMAGE,
            name = name,
            mediaType = effectiveType,
            data = bytes.toBase64(),
        )
        return null
    }

    /**
     * 加入一个待发送的文件：先上传换回执，成功才算加入。
     *
     * 上传是网络调用，所以这里是 `suspend`；失败由调用方（UI）转成错误文案。
     *
     * **不要**用 `runCatching` 包住它（R007 P2-1）：那会连
     * [kotlinx.coroutines.CancellationException] 一起吞掉，把「旋转屏幕取消上传」
     * 显示成「附件上传失败（JobCancellationException）」——用户完全看不懂。
     *
     * 回执为空时返回失败（R007 P2-2）：`file` 内容块只有一个 `receiptId` 键，
     * 空回执的附件发不出去。静默丢弃会让用户「选了文件却什么都没有」。
     */
    suspend fun attachFile(bytes: ByteArray, name: String?): Result<PromptAttachment> {
        val attachment = try {
            val result = uploads.upload(sessionId, bytes.toBase64(), name)
            PromptAttachment(
                kind = PromptAttachment.Kind.FILE,
                name = result.file.name.ifBlank { name.orEmpty() },
                receiptId = result.receiptId,
            )
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return Result.failure(cause)
        }
        if (attachment.receiptId.isNullOrEmpty()) {
            return Result.failure(
                IllegalStateException("桌面端没有返回文件回执，附件未添加，请重试"),
            )
        }
        _pending.value = _pending.value + attachment
        return Result.success(attachment)
    }

    /**
     * 「选文件 → 上传 → 入待发送」整条链，跑在 [viewModelScope] 里。
     *
     * 早先这条链在 Composer 的 `rememberCoroutineScope` 里：旋转屏幕会取消 composition，
     * 于是上传被取消、提示被重置，用户既看不到失败也看不到成功（R007 P2-3）。
     * 挪进来之后旋转不打断上传，提示也由 [attachNotice] 承载、不会丢。
     */
    fun attachFileInBackground(bytes: ByteArray, name: String?) {
        if (attachJob?.isActive == true) return
        _attachNotice.value = "正在上传${name?.let { " $it" } ?: ""}…"
        attachJob = viewModelScope.launch {
            val failure = try {
                attachFile(bytes, name).exceptionOrNull()
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            }
            // 上传途中被取消时不要覆盖提示：取消不是失败。
            if (failure != null) {
                _attachNotice.value = failure.message
                    ?: "附件上传失败（${failure::class.java.simpleName}）"
            }
        }
    }

    /** 直接设置附件提示（null = 清掉）。读文件失败这类「还没到上传」的错误走这里。 */
    fun setAttachNotice(text: String?) {
        _attachNotice.value = text
    }

    /** 清掉附件提示（用户点了提示上的关闭，或换了一个会话）。 */
    fun clearAttachNotice() {
        _attachNotice.value = null
    }

    /** 移除一个待发送附件（按在列表里的位置）。 */
    fun removeAttachment(index: Int) {
        if (index !in _pending.value.indices) return
        _pending.value = _pending.value.filterIndexed { i, _ -> i != index }
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
     * 打开模型选择器（拉一次可选目录）。
     *
     * 目录是全局的，所以只在第一次打开时拉；之后复用 [ChatUiState.catalog]。
     * 已在加载中或已拉到结果时直接返回，避免连点打出多个请求。
     *
     * 用 [catalogJob] 而不是与切换模型共用的 [modelJob]（R007 P1-2）：共用时
     * 「目录还在加载」会把用户随后点的模型选择整个挡掉。
     */
    fun openModelPicker() {
        val current = _state.value
        if (current.catalog != null || current.catalogLoading) return
        if (catalogJob?.isActive == true) return
        _state.value = current.copy(catalogLoading = true, modelError = null)
        catalogJob = viewModelScope.launch {
            val catalog = try {
                repository.modelCatalog()
            } catch (cause: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(catalogLoading = false)
                throw cause
            } catch (cause: Exception) {
                _state.value = _state.value.copy(
                    catalogLoading = false,
                    modelError = describe(cause),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                catalog = catalog,
                catalogLoading = false,
                modelError = null,
            )
        }
    }

    /**
     * 切换这个会话的模型（`session/selectModel`）。
     *
     * @param reasoningEffort 推理档；模型没有 `reasoning.efforts` 时必须传 null，
     *   否则服务端会因为多一个键回 `gateway/arguments-invalid`。
     *
     * 先乐观更新 [ChatUiState.model] 再发请求：RPC 往返有几百毫秒，期间按钮还显示旧模型
     * 会让用户以为「没点上」。失败时回滚到快照投影给的值，并给出可重试的错误条。
     *
     * **重入处理（R007 P1-2）**：守卫必须放在乐观更新**之前**。早先是
     * 「先改 UI，再 `if (modelJob?.isActive) return`」，于是一次在途请求会让用户的
     * 第二次选择被整个吞掉——RPC 从不发出，等前一个返回时又把 UI 覆盖回旧模型，
     * 界面与桌面端实际模型不一致。
     *
     * 现在改成：直接取消在途请求、发新的。`selectModel` 是「设成这个值」的幂等语义，
     * 最后一次生效即可；排队等待反而让用户多点的那一次迟迟不反映。
     */
    fun selectModel(
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ) {
        val previous = _state.value.model
        val optimistic = ModelRef(provider, model, reasoningEffort)
        // 取消在途的旧请求再发新的。被取消的那个协程走 CancellationException 分支
        // 原样上抛，不会回滚状态——否则它会把用户新选的值改回旧值。
        modelJob?.cancel()
        _state.value = _state.value.copy(model = optimistic, modelError = null)
        modelJob = viewModelScope.launch {
            val selected = try {
                repository.selectModel(
                    sessionId = sessionId,
                    provider = provider,
                    model = model,
                    reasoningEffort = reasoningEffort,
                )
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Exception) {
                // 只有在没有更新的选择覆盖它时才回滚：用户可能已经又点了别的模型，
                // 此时把状态改回 previous 会把新选择抹掉。
                if (_state.value.model == optimistic) {
                    _state.value = _state.value.copy(
                        model = previous,
                        modelError = describe(cause),
                    )
                }
                return@launch
            }
            // 以服务端确认的为准：它可能在返回里补齐/规范化我们没传的字段。
            _state.value = _state.value.copy(model = selected, modelError = null)
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
                    // 图片上限跟着会话投影走（换模型会变）。快照才带这个投影，
                    // 单条事件帧不带，所以只在 Snapshot 上更新。
                    if (frame is ChatFrame.Snapshot) {
                        imageLimits = frame.imageLimits ?: ImageLimits()
                    }
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
     *
     * 单条事件帧也要过 [SessionEventParser.isCacheable]：注入的 `user/message`
     * （`time-context` 等 187/227 条）不经这一步挡掉就会逐条写进缓存，把
     * `MESSAGE_CACHE_LIMIT`（600 条）的预算吃光——快照路径挡了、实时路径不挡，
     * 等于预算目标只完成一半（R012 P2-B）。
     */
    private fun envelopesOf(frame: ChatFrame): List<JsonObject> = when (frame) {
        is ChatFrame.Snapshot -> frame.envelopes
        is ChatFrame.Event -> repository.cacheable(listOfNotNull(frame.envelope))
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
        catalogJob?.cancel()
        modelJob?.cancel()
        attachJob?.cancel()
        queueJob?.cancel()
        projectionJob?.cancel()
        questionPollJob?.cancel()
        controlJob?.cancel()
        answerJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * 草稿命名空间里标记「这组提问来自 `$events` waterfall」的前缀。
         *
         * 值取自 [RemoteEvents.WATERFALL_PREFIX] 而不是在这里另写一份字面量：
         * `RemoteEventsClient.answerQuestions` 要剥同一个前缀才能找到条目，
         * 两边各写一份就是下一次「一边改了另一边没改」的静默失败。
         *
         * 见 [waterfallCallId] 与 [answerQuestionGroup]。
         */
        private val WATERFALL_PREFIX: String get() = RemoteEvents.WATERFALL_PREFIX

        /**
         * 生成中重读提问投影的间隔。
         *
         * 只在 [ChatUiState.generating] 为真时跑：提问只可能在工具执行期间出现，
         * 不生成时没有任何东西会新建提问。2.5s 足够让「别人答掉了」在两次心跳内
         * 反映到手机上，而每秒最多一次 unary RPC 的代价可以忽略。
         */
        private const val QUESTION_REFRESH_MS = 2_500L

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
