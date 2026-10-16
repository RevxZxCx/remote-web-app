package com.revxzxcx.remotewebapp.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.cache.CacheRows
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.events.RemoteEventsStats
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.MuxError
import com.revxzxcx.remotewebapp.core.protocol.MuxStats
import com.revxzxcx.remotewebapp.ui.common.maskClientId
import com.revxzxcx.remotewebapp.ui.common.maskDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 调试页状态（需求文档非功能需求「可观测」）。
 *
 * 刻意只读：调试页**不**提供「清空缓存」「断开连接」之类的动作按钮，因为这些都是有副作用
 * 且会掩盖症状的操作，排查时最不需要的就是一个能让问题消失的按钮。缓存行数只作为事实展示。
 */
data class DebugUiState(
    /** 连接地址（配对时写入的基址）。 */
    val baseUrl: String = "",
    /** 设备 id（打码后展示，完整值没有任何诊断价值却是一把钥匙）。 */
    val maskedDeviceId: String = "",
    /** 配对时间。 */
    val pairedAt: Long = 0L,
    /** 设备标签（型号）。 */
    val deviceLabel: String = "",
    /** mux 的收发计数与载波状态。 */
    val stats: MuxStats = MuxStats(),
    /** 最近 N 条故障（时间倒序）。 */
    val errors: List<MuxError> = emptyList(),
    /** 当前分区键（缓存作用域）；空串表示未配对。 */
    val ownerId: String = "",
    /**
     * 本地缓存各表的行数。
     *
     * 三种事实必须分开（R006 P2-3）：`null` 单独一个字段表达不了它们。
     * - `Unknown`：还没读（未配对、或初始化尚未返回）——不该显示成失败。
     * - `Absent`：缓存功能没启用（没配对）。
     * - `Rows`：读成功了。
     * - `Failed`：真的读库失败。
     */
    val cacheRows: CacheRowsState = CacheRowsState.Unknown,
    /**
     * 审批通道（`$events` 流）的状态。
     *
     * 与 `stats`（mux 总帧数）分开：一条 WebSocket 上三条流复用，总帧数回答不了
     * 「审批通道通不通」。`clientId` 为空 = 还没收到 `ready` 帧 = 现在答不了任何请求。
     */
    val remoteEvents: RemoteEventsState = RemoteEventsState(),
)

/** 审批通道的展示状态（[DebugUiState.remoteEvents]）。 */
data class RemoteEventsState(
    /** 服务端分配的连接 id；空表示流还没就绪。 */
    val clientId: String? = null,
    /** 打码后的 id（完整值没有诊断价值）。 */
    val maskedClientId: String = "",
    val stats: RemoteEventsStats = RemoteEventsStats(),
    /** 订阅或回答失败的中文说明；排查审批问题时最关键的一条。 */
    val error: String? = null,
) {
    /** 流是否已就绪（收到过 `ready`）。 */
    val ready: Boolean get() = clientId != null
}

/** [DebugUiState.cacheRows] 的四态。 */
sealed interface CacheRowsState {
    /** 还没读，或缓存功能不可用（未配对）。 */
    data object Unknown : CacheRowsState

    /** 读成功。 */
    data class Rows(val value: CacheRows) : CacheRowsState

    /** 读库失败（不是「空」。两者在 UI 上必须看起来不同）。 */
    data class Failed(val reason: String) : CacheRowsState
}

/**
 * 调试页 ViewModel。
 *
 * 数据全部来自已经存在的只读来源（凭证、[DshConnection.mux] 的 stats/errors、缓存行数），
 * 因此不需要新增任何采样或埋点——`MuxStats` 本来就是为 M3 冷启动缺陷定位而加的。
 */
class DebugViewModel(
    private val connection: DshConnection,
    private val cache: DshCache? = null,
    private val ownerId: String = "",
) : ViewModel() {

    private val _state = MutableStateFlow(
        DebugUiState(
            baseUrl = connection.baseUrl,
            maskedDeviceId = maskDeviceId(connection.deviceId),
            pairedAt = connection.credential.pairedAt,
            deviceLabel = connection.credential.label,
            stats = connection.mux.stats.value,
            ownerId = ownerId,
        ),
    )
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    init {
        // mux 的 stats 与 errors 合成一个状态：分开订阅会在两帧之间出现「计数已更新、
        // 错误列表还是旧的」的瞬时不一致，调试页最不该给人这种误导。
        viewModelScope.launch {
            combine(connection.mux.stats, connection.mux.errors) { stats, errors ->
                stats to errors
            }.collect { (stats, errors) ->
                _state.value = _state.value.copy(stats = stats, errors = errors)
            }
        }
        // 审批通道独立订阅：它挂在应用级 scope 上（不随调试页开关），
        // 所以这里只是「读」，不负责启停。
        // error 必须一起 combine 进来——「授权没能送出」「还没连上审批通道」
        // 恰恰是排查审批问题最关键的一条，看不到它调试页就等于没这个段。
        viewModelScope.launch {
            combine(
                connection.remoteEvents.clientId,
                connection.remoteEvents.stats,
                connection.remoteEvents.error,
            ) { id, stats, error -> Triple(id, stats, error) }.collect { (id, stats, error) ->
                _state.value = _state.value.copy(
                    remoteEvents = RemoteEventsState(
                        clientId = id,
                        maskedClientId = id?.let { maskClientId(it) } ?: "（无）",
                        stats = stats,
                        error = error,
                    ),
                )
            }
        }
        viewModelScope.launch { refreshCacheRows() }
    }

    /** 重新读一次缓存行数（缓存内容会随聊天/工作区变化）。 */
    fun refresh() {
        viewModelScope.launch { refreshCacheRows() }
    }

    private suspend fun refreshCacheRows() {
        val store = cache ?: run {
            // 未配对：缓存功能本轮不可用。这不是失败，也不能显示成失败。
            _state.value = _state.value.copy(cacheRows = CacheRowsState.Unknown)
            return
        }
        // `countRows` 返回 null 表示**读库失败**（DshCache.guard 把异常折成 null），
        // 与「缓存是空的」是不同的事实。早前这里 `?: return` 会保留上一次的行数，读失败后
        // 旧数字一直挂在页面上（R006 P2-3），所以必须把失败也写进状态。
        val rows = store.countRows(ownerId)
        _state.value = _state.value.copy(
            cacheRows = rows?.let { CacheRowsState.Rows(it) }
                ?: CacheRowsState.Failed("读库失败"),
        )
    }

    companion object {
        fun factory(
            connection: DshConnection,
            cache: DshCache? = null,
            ownerId: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DebugViewModel(connection, cache, ownerId) }
        }
    }
}

/** 载波状态在调试页里的补充说明（状态条已经有一句，这里补「为什么」）。 */
internal fun CarrierState.explain(): String = when (this) {
    CarrierState.Idle -> "尚未发起任何连接。"
    CarrierState.Connecting -> "正在握手：WebSocket 已发出，等待 101 响应（上限 $HANDSHAKE_SECONDS 秒）。"
    is CarrierState.Connected -> "已建立连接。"
    is CarrierState.Reconnecting -> "上一帧失败后按指数退避等待重试：$reason"
    is CarrierState.Unpaired -> "凭证已失效，重试不会有结果，需要重新配对。"
}

private const val HANDSHAKE_SECONDS = 15
