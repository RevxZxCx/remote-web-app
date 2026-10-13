package com.revxzxcx.remotewebapp.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.AppContainer
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.net.HostPolicy
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairingException
import com.revxzxcx.remotewebapp.core.pairing.PairStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 配对页的状态。 */
data class PairUiState(
    /** 是否正在配对（禁用按钮 + 转圈）。 */
    val busy: Boolean = false,
    /** 失败提示；成功时为 `null`。 */
    val error: String? = null,
    /** 地址探测结果，仅用于展示「已连通 / 不可达」。 */
    val probe: String? = null,
    /** 上一次用过的地址，用于裸 token 补全。 */
    val lastBaseUrl: String? = null,
    /** 设备型号，作为凭证 label。 */
    val deviceLabel: String = "",
)

/**
 * 配对页的 ViewModel。
 *
 * 只做三件事：探测地址、用文本（扫码/粘贴）配对、手工登记。
 * 配对成功后由 [AppContainer.onPaired] 切换到工作区页，本 VM 不负责导航。
 */
class PairViewModel(
    private val container: AppContainer,
    private val deviceLabel: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PairUiState(deviceLabel = deviceLabel))
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = container.credentialStore.load()
            _state.value = _state.value.copy(lastBaseUrl = existing?.baseUrl)
        }
    }

    /** 扫码 / 粘贴得到一段文本后调用。 */
    fun pairWithText(rawText: String) {
        if (_state.value.busy) return
        val text = rawText.trim()
        if (text.isEmpty()) {
            _state.value = _state.value.copy(error = "内容为空")
            return
        }
        _state.value = _state.value.copy(busy = true, error = null, probe = null)
        viewModelScope.launch {
            try {
                val credential = container.pairing.pairWithText(
                    rawText = text,
                    fallbackBaseUrl = _state.value.lastBaseUrl,
                    label = deviceLabel,
                )
                onSuccess(credential)
            } catch (e: PairingException) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "配对失败")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "配对失败")
            }
        }
    }

    /** 手工填「地址 + 设备 id」登记。 */
    fun pairManually(baseUrl: String, deviceId: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, probe = null)
        viewModelScope.launch {
            try {
                val credential = container.pairing.pairManually(baseUrl, deviceId, deviceLabel)
                onSuccess(credential)
            } catch (e: PairingException) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "登记失败")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "登记失败")
            }
        }
    }

    /**
     * 探测一个地址是否可达（调未鉴权的 `GET /api/pair/status`）。
     *
     * 必须先规范化再校验，且必须**在发请求之前**做——两个原因：
     * 1. 用户手输 `192.168.1.5:19387`（UI 就是这么引导的）时，`Request.Builder().url()` 会抛
     *    `IllegalArgumentException`，逃出 `viewModelScope.launch` 会直接崩进程；
     * 2. `HostPolicy` 是明文流量的唯一代码级闸门（manifest 已放开 `usesCleartextTraffic`），
     *    探测同样会向目标地址发请求，所以校验不能只在 `pairWithLink` 里做。
     */
    fun probe(baseUrl: String) {
        val normalized = PairLinkParser.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            _state.value = _state.value.copy(error = "地址格式不对：请填 http(s)://主机[:端口]")
            return
        }
        HostPolicy.rejectionReason(normalized)?.let {
            _state.value = _state.value.copy(error = it)
            return
        }
        _state.value = _state.value.copy(busy = true, error = null, probe = null)
        viewModelScope.launch {
            try {
                val status: PairStatus = PairingApi(container.probeHttp()).status(normalized)
                val text = buildString {
                    append("已连通")
                    append(if (status.paired) "；桌面端已有配对设备" else "；桌面端当前无配对设备")
                    status.publicUrl?.let { append("\n公网地址：$it") }
                    if (status.lanAddresses.isNotEmpty()) {
                        append("\n局域网地址：${status.lanAddresses.joinToString("、")}")
                    }
                }
                _state.value = _state.value.copy(busy = false, probe = text)
            } catch (e: PairingException) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "地址不可达")
            } catch (e: Exception) {
                // 兜底：任何未预期异常都不能逃出 launch（否则进程崩溃）。
                _state.value = _state.value.copy(
                    busy = false,
                    error = e.message ?: "地址不可达（${e.javaClass.simpleName}）",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun onSuccess(credential: DeviceCredential) {
        _state.value = _state.value.copy(busy = false, error = null, probe = null)
        container.onPaired(credential)
    }

    companion object {
        fun factory(container: AppContainer, deviceLabel: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PairViewModel(container, deviceLabel) }
            }
    }
}
