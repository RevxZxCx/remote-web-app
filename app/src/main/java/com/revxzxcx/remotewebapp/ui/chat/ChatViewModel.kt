package com.revxzxcx.remotewebapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.ChatArgs
import com.revxzxcx.remotewebapp.core.session.ChatReducer
import com.revxzxcx.remotewebapp.core.session.ChatRepository
import com.revxzxcx.remotewebapp.core.session.ChatUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * 聊天页的 ViewModel。
 *
 * 数据流：`session/follow`（流）→ [ChatFrameParser] → [ChatReducer] → [ChatUiState]。
 * 所有状态折叠都在 [ChatReducer] 里（纯函数，可单测）；本类只负责协程生命周期、
 * 重连退避与把用户操作翻译成 RPC。
 *
 * 重连策略与 [com.revxzxcx.remotewebapp.ui.home.HomeViewModel] 一致：指数退避
 * 1s→2s→4s…上限 30s；重连**不清空**已显示的消息（服务端会重新发快照，快照会替换
 * 窗口，这是正确行为——窗口内容来自持久化日志，不会丢）。凭证失效（403 `unpaired`）
 * 不重试，直接提示。
 */
class ChatViewModel(
    private val connection: DshConnection,
    private val sessionId: String,
) : ViewModel() {

    private val repository = ChatRepository(connection)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 输入框内容（放在 ViewModel 里，旋转屏幕不丢）。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private var streamJob: Job? = null
    private var pageJob: Job? = null
    private var sendJob: Job? = null

    init {
        start()
    }

    /** 更新输入框。 */
    fun onDraftChange(text: String) {
        _draft.value = text
    }

    /** 手动重连（点错误条上的「重试」）。 */
    fun retry() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(error = null, loading = _state.value.items.isEmpty())
        start()
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
                _state.value = _state.value.copy(error = null)
            } catch (cause: Exception) {
                _state.value = _state.value.copy(error = describe(cause))
            }
        }
    }

    /** 中止当前生成（`session/cancel`）。 */
    fun cancel() {
        viewModelScope.launch {
            try {
                repository.cancel(sessionId)
            } catch (cause: Exception) {
                _state.value = _state.value.copy(error = describe(cause))
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
            _state.value = _state.value.copy(loadingMore = true)
            try {
                val page = repository.loadPage(
                    sessionId = sessionId,
                    throughSeq = _state.value.cursor,
                    beforeSeq = earliest,
                )
                // 前置插入更早的条目并保持升序去重。
                val merged = (page.items + _state.value.items)
                    .distinctBy { it.seq }
                    .sortedBy { it.seq }
                _state.value = _state.value.copy(
                    items = merged,
                    hasMore = page.hasMore,
                    error = null,
                )
            } catch (cause: Exception) {
                _state.value = _state.value.copy(error = describe(cause))
            } finally {
                // 必须放 finally：协程被取消时抛的是 CancellationException，
                // 它既不进 try 的成功分支也不被 `catch (Exception)` 捕获，
                // 写在任一处都会让「加载更早」的转圈行永久留在列表顶部（R003 P1-2 同类）。
                _state.value = _state.value.copy(loadingMore = false)
                pageJob = null
            }
        }
    }

    private fun start() {
        streamJob = viewModelScope.launch {
            repository.follow(sessionId)
                .retryWhen { cause, attempt ->
                    if (cause is TypertException && cause.isUnpaired) {
                        false // 凭证失效：不重试，交给 UI 引导重新配对。
                    } else {
                        val backoffMs = backoffMillis(attempt)
                        _state.value = _state.value.copy(
                            error = "连接中断，${backoffMs / 1000}s 后重试…",
                        )
                        delay(backoffMs)
                        true
                    }
                }
                .catch { cause ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = describe(cause),
                    )
                }
                .collect { frame ->
                    _state.value = ChatReducer.reduce(_state.value, frame)
                }
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
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        fun factory(connection: DshConnection, sessionId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChatViewModel(connection, sessionId) }
            }
    }
}
