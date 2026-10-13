package com.revxzxcx.remotewebapp.core

import android.content.Context
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.CredentialStore
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.credentials.KeystoreCredentialStore
import com.revxzxcx.remotewebapp.core.net.HttpClients
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairingCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用级的连接状态。 */
sealed interface AppState {

    /** 还在读本地凭证（冷启动第一帧）。 */
    data object Loading : AppState

    /** 没有可用凭证 → 显示配对页。 */
    data object Unpaired : AppState

    /** 已配对 → 显示工作区页。 */
    data class Paired(val credential: DeviceCredential) : AppState
}

/**
 * 手工依赖容器（本期不引入 Hilt：模块还少，多一个注解处理器不划算）。
 *
 * 生命周期与 `Application` 一致。持有：
 * - [credentialStore]：Keystore 加密的凭证存储（D16）；
 * - [pairing]：配对流程；
 * - [state]：当前是「未配对 / 已配对」；
 * - 当前 [DshConnection]（配对成功后建立，退出配对时关闭）。
 *
 * 线程：所有会碰磁盘 / 网络的方法都是 `suspend`。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val credentialStore: CredentialStore = KeystoreCredentialStore(appContext)

    private val unaryHttp = HttpClients.unary()
    private val streamHttp = HttpClients.stream()

    /** 配对页「探测地址」用；与 unary 同一个客户端（同一套超时策略）。 */
    fun probeHttp(): okhttp3.OkHttpClient = unaryHttp

    val pairing: PairingCoordinator = PairingCoordinator(credentialStore, PairingApi(unaryHttp))

    private val _state = MutableStateFlow<AppState>(AppState.Loading)

    /** 当前连接状态；UI 只观察这个。 */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 已配对时的连接句柄；未配对为 `null`。 */
    var connection: DshConnection? = null
        private set

    /** 冷启动：把落盘的凭证读回来。解不开 / 不存在都按未配对处理。 */
    suspend fun bootstrap() {
        val stored = credentialStore.load()?.takeIf { it.isUsable }
        if (stored == null) {
            _state.value = AppState.Unpaired
        } else {
            installConnection(stored)
        }
    }

    /** 配对成功后由 UI 调用。 */
    suspend fun onPaired(credential: DeviceCredential) {
        installConnection(credential)
    }

    /** 退出配对：关连接、清凭证、回到配对页。 */
    suspend fun unpair() {
        connection?.close()
        connection = null
        pairing.unpair()
        _state.value = AppState.Unpaired
    }

    private suspend fun installConnection(credential: DeviceCredential) {
        // 配置变更（旋转 / 深浅色切换）会重建 Activity，`AppRoot` 的 `LaunchedEffect(Unit)`
        // 因此会再跑一次 bootstrap。若凭证没变就**复用**现有连接：
        // `HomeViewModel` 被 ViewModelStore 保留着，仍持有旧连接；这里若关掉它再新建，
        // 旧流会以「正常完成」结束（`RemoteStreamMux.close()` 不带 cause），既不报错也不触发
        // `retryWhen`，页面就会停在旧列表和「已连接」上直到用户手动刷新。
        val current = connection
        if (current != null && current.credential == credential) {
            _state.value = AppState.Paired(credential)
            return
        }

        // 换地址 / 换设备时先把旧连接关掉，避免旧 mux 一直挂着。
        current?.close()
        connection = DshConnection(
            credential = credential,
            unaryHttp = unaryHttp,
            streamHttp = streamHttp,
        )
        _state.value = AppState.Paired(credential)
    }
}
