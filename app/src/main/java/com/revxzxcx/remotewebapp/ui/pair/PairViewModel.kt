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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 地址探测的结构化结果（R033 ③）。
 *
 * 为什么不是一段拼好的字符串：探测要分「成功 / 失败」两种**视觉**（成功绿描边、
 * 失败红描边）。字符串只能靠 `startsWith("已连通")` 猜，一旦文案微调就静默走错分支。
 * 把结论拆成 [ok] + 标题 + 细节行，UI 直接按 [ok] 选色。
 */
data class PairProbe(
    /** 地址是否可达。 */
    val ok: Boolean,
    /**
     * 这次结论对应的地址（规范化后）。
     *
     * 为什么要回显它：`clearProbe()` 只能在「用户改地址」时清卡，挡不住**在途请求**——
     * 地址 A 探测很慢、用户改成 B 又点了一次，A 的后到结论会覆盖 B 的。把被探测的地址
     * 一起带到卡上，错配就从「看不见的幽灵」变成界面上可自证的事实。
     */
    val baseUrl: String = "",
    /** 一句话结论，如「地址可达」「连不上这个地址」。 */
    val headline: String,
    /** 细节行，逐行展示（配对状态、公网/局域网地址、失败原因）。 */
    val lines: List<String> = emptyList(),
)

/** 配对页的状态。 */
data class PairUiState(
    /**
     * 是否正在配对（禁用按钮 + 转圈）。
     *
     * 与 [probing] 分开而不是共用一个 `busy`：探测地址也走这条状态通道的话，在「手工」
     * 入口点「探测地址」会让整页顶部冒出「正在配对…」，用户以为登记已经提交了；同时
     * `ScanCard(enabled = !busy)` 会被无理由禁掉——三个入口互相干扰。
     */
    val busy: Boolean = false,
    /** 是否正在探测地址（只禁用探测/登记，不显示「正在配对…」）。 */
    val probing: Boolean = false,
    /** 失败提示；成功时为 `null`。 */
    val error: String? = null,
    /** 地址探测结果，仅用于展示「已连通 / 不可达」。 */
    val probe: PairProbe? = null,
    /** 上一次用过的地址，用于裸 token 补全。 */
    val lastBaseUrl: String? = null,
    /** 设备型号，作为凭证 label。 */
    val deviceLabel: String = "",
    /**
     * 「添加远程」模式下配对成功后的新主机（R054）。
     *
     * 为什么要把它放进状态而不是直接跳转：首次配对时成功就意味着
     * `AppState` 变成 `Paired`，界面自己会切走；而「添加远程」时连接根本没动，
     * 界面不会自己切，需要一个信号告诉调用方「加完了，可以退出配对页」。
     */
    val addedHost: DeviceCredential? = null,
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
    /**
     * 是否处于「添加远程」模式（R054）。
     *
     * `false` = 首次配对：成功后新主机成为当前（调 `onPaired`）。
     * `true` = 添加一台：成功后**不抢当前主机**，只写进凭证簿（调 `addHost`）。
     */
    private val addMode: Boolean = false,
) : ViewModel() {

    /**
     * 在途的探测请求。
     *
     * 保存它是为了能取消：`clearProbe()`（用户改地址）与新一次 `probe()` 都必须让旧的
     * 那发作废，否则「A 慢、B 快」时 A 的结论会盖掉 B 的。
     */
    private var probeJob: Job? = null

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
        // 与 `pairWithText` 的「内容为空」对称：空值在这里就挡掉，而不是让它走完整个
        // 请求、把 busy 拉起来、再在错误条里落一句「登记失败」——那个错误本就不该发生。
        if (baseUrl.isBlank() || deviceId.isBlank()) {
            _state.value = _state.value.copy(error = "地址和设备 id 都要填")
            return
        }
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
        // 与 `pairWithText` / `pairManually` 一致地挡住并发点击：连点「探测地址」会发出
        // 多个请求，晚回的会覆盖早回的结论。
        if (_state.value.busy || _state.value.probing) return
        val normalized = PairLinkParser.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            // 格式问题也是「探测结论」，要贴着输入框显示，不进全局错误条（R033 ③）。
            _state.value = _state.value.copy(
                probing = false,
                error = null,
                probe = PairProbe(
                    ok = false,
                    baseUrl = baseUrl.trim(),
                    headline = "地址格式不对",
                    lines = listOf("请填 http(s)://主机[:端口]，例如 http://192.168.1.5:19387"),
                ),
            )
            return
        }
        HostPolicy.rejectionReason(normalized)?.let { reason ->
            _state.value = _state.value.copy(
                probing = false,
                error = null,
                probe = PairProbe(
                    ok = false,
                    baseUrl = normalized,
                    headline = "这个地址不能用",
                    lines = listOf(reason),
                ),
            )
            return
        }
        // 上一发探测即使还在飞也作废：它的结论对应的已经不是当前地址了。
        probeJob?.cancel()
        _state.value = _state.value.copy(probing = true, error = null, probe = null)
        probeJob = viewModelScope.launch {
            try {
                val status: PairStatus = PairingApi(container.probeHttp()).status(normalized)
                val lines = buildList {
                    add(if (status.paired) "桌面端已有配对设备" else "桌面端当前无配对设备")
                    status.publicUrl?.let { add("公网地址 $it") }
                    if (status.lanAddresses.isNotEmpty()) {
                        add("局域网地址 ${status.lanAddresses.joinToString("、")}")
                    }
                }
                _state.value = _state.value.copy(
                    probing = false,
                    probe = PairProbe(ok = true, baseUrl = normalized, headline = "地址可达", lines = lines),
                )
            } catch (e: PairingException) {
                _state.value = _state.value.copy(
                    probing = false,
                    probe = PairProbe(
                        ok = false,
                        baseUrl = normalized,
                        headline = "连不上这个地址",
                        lines = listOf(e.message ?: "地址不可达"),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 🔴 取消**必须原样重抛**，不能被下面的兜底吞掉。
                // 上面那两处 `probeJob?.cancel()`（改地址、发起新探测）就是靠它生效的；
                // 一旦被 `catch (Exception)` 接住，取消就不再是「作废」，而会反过来写一张
                // 红卡「连不上这个地址」——用户只是改了个地址，却被告知网络不通。
                // 真机上这属于「改了输入框，界面自己报错」的不可解释行为。
                throw e
            } catch (e: Exception) {
                // 兜底：任何未预期异常都不能逃出 launch（否则进程崩溃）。
                _state.value = _state.value.copy(
                    probing = false,
                    probe = PairProbe(
                        ok = false,
                        baseUrl = normalized,
                        headline = "连不上这个地址",
                        lines = listOf(e.message ?: "地址不可达（${e.javaClass.simpleName}）"),
                    ),
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 消费掉「已加入列表」这个一次性事件。
     *
     * [addedHost] 描述的是**刚刚发生的一件事**，不是页面的常态：喂给 UI 之后就该熄灭。
     * 早先 ViewModel 的 key 带 `activeHost.deviceId`，加完一台就整体重建、状态天然归零，
     * 这个问题被掩盖了；key 改成稳定的 `"pair-add"` 之后实例会活下来，若不显式消费，
     * 下次再进「添加远程」页面时上一轮的「已加入列表 / PGBM10」还挂在那儿，
     * 看起来像这一轮刚加成功的一样。
     */
    fun clearAddedHost() {
        if (_state.value.addedHost == null) return
        _state.value = _state.value.copy(addedHost = null)
    }

    /**
     * 地址被改动时清掉上一次探测结论（R034 P2）。
     *
     * 不清的话会出现「绿灯贴着一个没探过的地址」：地址 A 探测成功 → 用户把输入框改成 B
     * → 绿描边「地址可达」还紧贴在 B 下面，同时 `登记` 对 B 可点。这张卡的唯一职责就是
     * 「告诉你这个地址通不通」，结论一旦与地址错配就从证据变成了误导。
     */
    fun clearProbe() {
        // 在途请求也要一起打断：光把 `probe` 置 null 挡不住它回来之后写状态。
        probeJob?.cancel()
        probeJob = null
        if (_state.value.probe == null && !_state.value.probing) return
        _state.value = _state.value.copy(probe = null, probing = false)
    }

    private suspend fun onSuccess(credential: DeviceCredential) {
        if (addMode) {
            // 先写入凭证簿（不切主机），再告诉 UI 可以退出配对页。
            val added = container.addHost(credential)
            _state.value = _state.value.copy(
                busy = false,
                error = null,
                probe = null,
                addedHost = added,
            )
            return
        }
        _state.value = _state.value.copy(busy = false, error = null, probe = null)
        container.onPaired(credential)
    }

    companion object {
        fun factory(
            container: AppContainer,
            deviceLabel: String,
            addMode: Boolean = false,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PairViewModel(container, deviceLabel, addMode) }
            }
    }
}
