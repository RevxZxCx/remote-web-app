package com.revxzxcx.remotewebapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.TypertException
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

/** 工作区页的状态。 */
data class HomeUiState(
    /** 是否还在等第一帧 baseline。 */
    val loading: Boolean = true,
    /** 工作区列表（已按服务端顺序）。 */
    val snapshot: WorkspaceSnapshot = WorkspaceSnapshot(),
    /** 连接状态文案：`已连接` / `重连中…`。 */
    val connection: String = "连接中…",
    /** 不可恢复的错误（例如 403 unpaired）。 */
    val error: String? = null,
    /** 凭证是否已失效，需要重新配对。 */
    val needsRepair: Boolean = false,
    /** 最近一次收到的原始帧，供 M4 调试页使用。 */
    val lastRawFrame: String? = null,
)

/**
 * 工作区页的 ViewModel。
 *
 * 数据来自 `workspace/follow`（流，无参数）。首帧是 `baseline`，所以列表会在第一次 emit 时
 * 就填满；之后 `upsert` / `remove` / `order` 等帧增量更新（[WorkspaceRepository] 负责折叠）。
 *
 * 重连策略（需求文档 §7.3 / 代码审查清单 3.4）：指数退避 1s→2s→4s…上限 30s，
 * 重连期间**不清空**已有列表，只把连接状态改成「重连中…」。凭证失效（403 `unpaired`）
 * 属于不可恢复错误，直接停下并提示重新配对，不再无限重试。
 */
class HomeViewModel(
    private val connection: DshConnection,
) : ViewModel() {

    private val repository = WorkspaceRepository(connection)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    /** 手动重连（下拉刷新 / 点「重试」）。 */
    fun retry() {
        job?.cancel()
        _state.value = _state.value.copy(error = null, needsRepair = false, connection = "连接中…")
        start()
    }

    private fun start() {
        job = viewModelScope.launch {
            repository.snapshotFlow(onRawFrame = { raw ->
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
                    _state.value = _state.value.copy(
                        loading = false,
                        snapshot = snapshot,
                        connection = "已连接",
                        error = null,
                    )
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
        super.onCleared()
    }

    companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        fun factory(connection: DshConnection): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(connection) }
        }
    }
}
