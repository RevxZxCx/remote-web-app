package com.revxzxcx.remotewebapp.core

import android.content.Context
import com.revxzxcx.remotewebapp.core.cache.CacheMappers
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.cache.DshDatabase
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.CredentialStore
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.credentials.KeystoreCredentialStore
import com.revxzxcx.remotewebapp.core.net.HttpClients
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * 与 Application 同寿的协程作用域。
     *
     * 只用于「不依附任何界面、也不能因为界面消失而中断」的维护性工作（目前是清理别的
     * 设备分区）。用 `SupervisorJob`：一次维护失败不该取消整个作用域。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 配对页「探测地址」用；与 unary 同一个客户端（同一套超时策略）。 */
    fun probeHttp(): okhttp3.OkHttpClient = unaryHttp

    val pairing: PairingCoordinator = PairingCoordinator(credentialStore, PairingApi(unaryHttp))

    /**
     * 离线缓存（需求 FR-4.3）。
     *
     * 惰性建库：未配对的用户（只停在配对页）永远不该付出建 SQLite 文件的代价。
     * 用 `by lazy` 而不是在 `bootstrap()` 里建，也让「第一次真正需要缓存」这件事只有一个入口。
     *
     * **不要在主线程上第一次碰它**（R006 P2-6）：Room 的 `build()` 会真的打开数据库文件，
     * 而本容器的 KDoc 承诺「碰磁盘的方法都是 suspend」。`by lazy` 的初始化发生在**第一次读
     * `cache` 的线程**上，所以 [installConnection] 会先在自己那个挂起位置把它预热（见下），
     * 之后所有 getter 都只是读一个已初始化的字段。
     */
    private val cacheLazy: DshCache by lazy { DshCache.create(DshDatabase.open(appContext).cacheDao()) }

    /** 已配对时可用；未配对返回 `null`（UI 层无需判断，直接传 `null` 即可）。 */
    val cache: DshCache?
        get() = if (_state.value is AppState.Paired) cacheLazy else null

    /**
     * 当前分区键（凭证指纹）；未配对为空串。
     *
     * UI 拿它当缓存读写的作用域，因此必须与 [AppState.Paired] 同步——分开两次读状态会
     * 在配对/解绑的瞬间拿到不一致的组合。
     */
    val ownerId: String
        get() = when (val current = _state.value) {
            is AppState.Paired -> CacheMappers.ownerIdOf(current.credential)
            else -> ""
        }

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
        // 先把缓存库在 IO 线程上建好，**再**把状态切成 `Paired`：`cache` 的 getter 是普通
        // （非挂起）函数，任何 Composable 都可能在第一帧读它，若那时 `lazy` 还没被初始化，
        // `build()` 就会在那个线程上打开数据库文件（R006 P2-6）。这里预热后，之后所有读取
        // 都只是读一个已初始化的字段。
        withContext(Dispatchers.IO) { cacheLazy }

        current?.close()
        connection = DshConnection(
            credential = credential,
            unaryHttp = unaryHttp,
            streamHttp = streamHttp,
        )
        _state.value = AppState.Paired(credential)
        // 换设备/重新配对后，别的 owner 的缓存行永远查不到，但会一直占空间：
        // 在拿到新分区的这一刻清掉。用容器自己的 scope（而不是 GlobalScope）——
        // 它的生命周期与 Application 一致，而 GlobalScope 会让将来的测试无法收敛。
        appScope.launch { cacheLazy.pruneOtherOwners(CacheMappers.ownerIdOf(credential)) }
    }
}
