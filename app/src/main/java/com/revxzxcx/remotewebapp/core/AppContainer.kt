package com.revxzxcx.remotewebapp.core

import android.content.Context
import com.revxzxcx.remotewebapp.core.cache.CacheMappers
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.cache.DshDatabase
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.connection.HostProbe
import com.revxzxcx.remotewebapp.core.connection.HostProbeClient
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.isSameEndpoint
import com.revxzxcx.remotewebapp.core.credentials.CredentialStore
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.credentials.KeystoreCredentialStore
import com.revxzxcx.remotewebapp.core.net.HttpClients
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairingCoordinator
import com.revxzxcx.remotewebapp.core.prefs.ThemePreferences
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用级的连接状态。
 *
 * R054：从「一台手机一台桌面端」升级为多主机后，[AppState] 只表示「有没有可用的主机」，
 * **不再携带凭证**——当前是哪一台要单独问 [AppContainer.activeHost]，否则每换一次主机
 * 就要重建整棵 UI 树（HomeViewModel 挂在 `key = "home-${deviceId}"` 上，换 key 就是换
 * ViewModel，聊天页会丢状态、列表要重拉 1.78 MB）。
 */
sealed interface AppState {

    /** 还在读本地凭证簿（冷启动第一帧）。 */
    data object Loading : AppState

    /** 簿里一条主机都没有 → 显示配对页。 */
    data object Unpaired : AppState

    /** 至少有一台主机可用。当前是哪一台见 [AppContainer.activeHost]。 */
    data object Paired : AppState
}

/**
 * 手工依赖容器（本期不引入 Hilt：模块还少，多一个注解处理器不划算）。
 *
 * 生命周期与 `Application` 一致。持有：
 * - [credentialStore]：Keystore 加密的**凭证簿**（D16 / R054 多主机）；
 * - [pairing]：配对流程；
 * - [themePreferences]：主题偏好（M5-9）；
 * - [book]：当前凭证簿快照（[StateFlow]，UI 观察它画设备栏）；
 * - [state]：有没有可用主机；
 * - 当前 [DshConnection]（切换主机时整体换掉，旧连接关闭）。
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
     * 只用于「不依附任何界面、也不能因为界面消失而中断」的维护性工作（目前是删除主机后
     * 清理它那片缓存分区）。用 `SupervisorJob`：一次维护失败不该取消整个作用域。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 凭证簿的「读-改-写」串行锁（R054）。
     *
     * 改名、改地址、删除、切换、配对落盘五个操作都要读整本再写回。并发是真实存在的：
     * 用户可能一边在设备栏改名，一边因为切换主机触发 `bootstrap`；两个调用方各自读到
     * 一份旧簿再写回，后写的会静默覆盖前一个改动。把所有写路径收进这一把锁，
     * 「一次改动 = 一次整本替换」才成立。
     */
    private val bookLock = Mutex()

    /**
     * 在容器自己的作用域里跑一段不能被界面取消的工作（R054）。
     *
     * 设备栏的四个动作（切换 / 改名 / 改址 / 移除）都会改写凭证簿，而其中切换会让
     * 整个 home 分支重建（VM key 带 deviceId）。若用 UI 层的 `rememberCoroutineScope`，
     * 切换进行到一半就会因为那个组合被抛弃而被取消——凭证写了一半、
     * 连接没建。这里用与 Application 同寿的 [appScope]。
     */
    fun runInBackground(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    /** 配对页「探测地址」用；与 unary 同一个客户端（同一套超时策略）。 */
    fun probeHttp(): okhttp3.OkHttpClient = unaryHttp

    val pairing: PairingCoordinator = PairingCoordinator(credentialStore, PairingApi(unaryHttp))

    /**
     * 设备栏「测试连接」的真实鉴权探测（M57）。
     *
     * 与 unary 共用同一个 HTTP 客户端：探测是一次普通 unary 请求，超时策略应当一致；
     * 共用连接池也让「刚连过的地址」探测更快。
     */
    private val hostProbe: HostProbeClient = HostProbeClient(unaryHttp, DshJson)

    /**
     * 主题偏好（M5-9）。与容器同寿，构造时同步读一次 SharedPreferences（一个字符串，
     * 比 Room 建库便宜得多，不违反上面的线程承诺）。
     */
    val themePreferences: ThemePreferences = ThemePreferences(appContext)

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
     * 当前激活的主机；未配对 / 空簿为 `null`。
     *
     * **这是读取「当前连的是哪一台」的唯一入口**（R054）。早先 UI 从
     * `AppState.Paired.credential` 取，那样每换主机就得换一次状态对象；现在凭证簿变化
     * 只更新 [book]，`AppState` 稳定在 `Paired`，UI 树不重建。
     */
    val activeHost: DeviceCredential?
        get() = _book.value.active

    /**
     * 当前分区键（凭证指纹）；未配对为空串。
     *
     * UI 拿它当缓存读写的作用域，因此必须与 [activeHost] 同步——分开两次读会
     * 在换主机的瞬间拿到不一致的组合。
     */
    val ownerId: String
        get() = activeHost?.let { CacheMappers.ownerIdOf(it) } ?: ""

    private val _state = MutableStateFlow<AppState>(AppState.Loading)

    /** 当前连接状态；UI 只观察这个。 */
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _book = MutableStateFlow(CredentialBook.EMPTY)

    /** 当前凭证簿（设备栏直接观察它；改名 / 改地址 / 删除都会推新值）。 */
    val book: StateFlow<CredentialBook> = _book.asStateFlow()

    /** 已配对时的连接句柄；未配对为 `null`。 */
    var connection: DshConnection? = null
        private set

    /** 冷启动：把落盘的凭证簿读回来。解不开 / 不存在都按未配对处理。 */
    suspend fun bootstrap() {
        // 已在运行中且簿没变：复用现有连接（配置变更会重跑 bootstrap，见 installConnection）。
        val loaded = credentialStore.loadBook()
        val active = loaded.active?.takeIf { it.isUsable }
        if (active == null) {
            _book.value = loaded
            _state.value = AppState.Unpaired
            return
        }
        _book.value = loaded
        installConnection(active)
    }

    /**
     * 配对成功后由 UI 调用：把新主机加入凭证簿并设为当前。
     *
     * 返回落盘后的那条凭证（已带 `hostId`），调用方通常不需要它。
     */
    suspend fun onPaired(credential: DeviceCredential): DeviceCredential = bookLock.withLock {
        val next = credentialStore.loadBook().withUpsert(credential)
        credentialStore.saveBook(next)
        _book.value = next
        val active = checkNotNull(next.active) { "刚写入的主机必须是可激活的" }
        installConnection(active)
        active
    }

    /**
     * 添加远程（R054）：把新主机写进凭证簿，**但不切换当前主机**。
     *
     * 与 [onPaired] 的唯一区别就是「要不要激活」。首次配对必须激活（否则用户配对完还
     * 停在配对页）；而「添加远程」是给已有连接的用户再加一台，此时把当前主机抢走是
     * 有害的——用户可能只是想先把它存进列表，等下再切。
     *
     * 所以这里**不碰连接、不碰 `AppState`**：当前连接继续跑，UI 停在原处，只在
     * 设备栏里多出一行。要不要切由调用方随后调 [switchHost]。
     *
     * @return 落盘后的那条凭证（已带 `hostId`）。若是同一台（baseUrl + deviceId 相同），
     *   [CredentialBook.withUpsert] 会就地更新而不是新增一行。
     */
    suspend fun addHost(credential: DeviceCredential): DeviceCredential = bookLock.withLock {
        val next = credentialStore.loadBook().withUpsert(credential, makeActive = false)
        credentialStore.saveBook(next)
        _book.value = next
        next.active ?: credential
    }

    /**
     * 切换到簿里的另一台主机。
     *
     * 连接会**整体换掉**：旧连接先 close（`RemoteStreamMux.close()` 会让所有订阅流以
     * 正常完成结束，`retryWhen` 不介入），HomeViewModel 因 `key` 带 deviceId 而重建，
     * 缓存分区也随之切到新的 `ownerId`。
     *
     * @return 切换后的主机；id 不存在时返回 `null` 且不做任何事。
     */
    suspend fun switchHost(hostId: String): DeviceCredential? = bookLock.withLock {
        val current = credentialStore.loadBook()
        val target = current.hostOf(hostId)?.takeIf { it.isUsable } ?: return@withLock null
        if (current.activeHostId != hostId) {
            val next = current.withActive(hostId)
            credentialStore.saveBook(next)
            _book.value = next
        }
        installConnection(target)
        target
    }

    /** 改显示名。空名字按「清掉自定义名」处理，UI 会回退显示地址。 */
    suspend fun renameHost(hostId: String, label: String) = bookLock.withLock {
        val next = credentialStore.loadBook().withRenamed(hostId, label)
        credentialStore.saveBook(next)
        _book.value = next
        // 改名不影响连接（socket 只认 baseUrl + deviceId），所以**不重建连接**。
        // 这一点很关键：`installConnection` 的复用判据只看 endpoint，不看 label，
        // 见 `sameEndpoint`。若这里也调 installConnection，它内部的 endpoint 比较会
        // 命中复用分支，等于空转；显式不调，意图更清楚。
        Unit
    }

    /**
     * 改地址。
     *
     * 地址是 socket 的一部分，必须**重建连接**。若改的正好是当前激活的那台，
     * 重建后缓存分区也会变（`ownerId` 含 baseUrl）——旧分区的数据留在库里不动，
     * 切回来还能用。
     */
    suspend fun updateEndpoint(hostId: String, baseUrl: String): DeviceCredential? = bookLock.withLock {
        val before = credentialStore.loadBook()
        val next = before.withEndpoint(hostId, baseUrl)
        if (next == before) return@withLock null
        credentialStore.saveBook(next)
        _book.value = next
        val active = next.active ?: return@withLock null
        if (active.hostId == hostId) {
            installConnection(active)
        }
        active
    }

    /**
     * 把一台主机从列表里移除，并清掉它那片缓存分区。
     *
     * 移除的是当前激活那台时，自动切到剩下的第一台（[CredentialBook.active] 的回退），
     * 一台都不剩则回到配对页。
     *
     * @return 移除后是否还有可用主机（`false` 表示已经掉回未配对）。
     */
    suspend fun removeHost(hostId: String): Boolean = bookLock.withLock {
        val before = credentialStore.loadBook()
        val removed = before.hostOf(hostId) ?: return@withLock (before.active != null)
        val next = before.withRemoved(hostId)
        credentialStore.saveBook(next)
        _book.value = next
        // 缓存分区按 ownerId 精确认领：只清这台自己的，别的主机不受影响（R054）。
        // 用容器 scope 而不是当前协程：删除动作的调用方（UI）可能立刻退出组合。
        val removedOwner = CacheMappers.ownerIdOf(removed)
        appScope.launch { cacheLazy.pruneOwner(removedOwner) }

        val active = next.active?.takeIf { it.isUsable }
        if (active == null) {
            connection?.close()
            connection = null
            _state.value = AppState.Unpaired
            false
        } else {
            // 删掉的不是当前那台时，连接不动（`installConnection` 会因 endpoint 相同而复用）。
            installConnection(active)
            true
        }
    }

    /**
     * 测一下某台主机现在能不能用（R054 设备栏的「测试连接」，M57 起做真实鉴权探测）。
     *
     * ## R054 的旧实现为什么不够
     *
     * 它打的是 `GET <base>/api/pair/status`——**无需凭证的公开探针**。对「地址通不通」
     * 这个问题它是对的，但它无法回答「这台桌面端还认不认我的凭证」：配对被撤销后它照样
     * 200，于是设备栏报「连得上」，用户切过去才收到 `unpaired (HTTP 403)`。
     *
     * ## 现在怎么测
     *
     * 用 [HostProbeClient] 发一次 `session/modelCatalog`：无参数、只读、必须带设备头。
     * 它不构造 [DshConnection]（那条路会连带持有一条长驻 WebSocket），探测完即挂断。
     *
     * 返回四态见 [HostProbe]：可达且鉴权通过 / 鉴权被拒 / 未通或响应不对 / 本地就被拒。
     */
    suspend fun testHost(host: DeviceCredential): HostProbe =
        hostProbe.probe(host.baseUrl, host.deviceId)

    /** 退出配对：关连接、清整本凭证簿、回到配对页。 */
    suspend fun unpair() = bookLock.withLock {
        connection?.close()
        connection = null
        pairing.unpair()
        _book.value = CredentialBook.EMPTY
        _state.value = AppState.Unpaired
        Unit
    }

    /**
     * 连接是否可以直接复用。
     *
     * 只比**影响 socket 的字段**（`baseUrl` + `deviceId`），不比整个 [DeviceCredential]。
     * R054 之前是 `current.credential == credential`，那在「凭证不可变」时成立；
     * 多主机后 `label` / `hostId` 都会变，用整体相等会让「改个显示名」触发重建连接，
     * 而重建正是下面那段注释警告的坑：`HomeViewModel` 在 Activity 的 ViewModelStore 里
     * 仍持有旧连接，旧流以「正常完成」结束、不报错也不重试，页面就停在旧列表上。
     */
    private fun reusable(current: DshConnection?, target: DeviceCredential): Boolean =
        isSameEndpoint(current?.credential, target)

    private suspend fun installConnection(credential: DeviceCredential) {
        // 配置变更（旋转 / 深浅色切换）会重建 Activity，`AppRoot` 的 `LaunchedEffect(Unit)`
        // 因此会再跑一次 bootstrap。若 endpoint 没变就**复用**现有连接：
        // `HomeViewModel` 被 ViewModelStore 保留着，仍持有旧连接；这里若关掉它再新建，
        // 旧流会以「正常完成」结束（`RemoteStreamMux.close()` 不带 cause），既不报错也不触发
        // `retryWhen`，页面就会停在旧列表和「已连接」上直到用户手动刷新。
        if (reusable(connection, credential)) {
            // 但凭证对象本身要更新（label 可能刚被改过），否则顶栏标题读的是旧名字。
            connection?.updateCredential(credential)
            _state.value = AppState.Paired
            return
        }

        // 换地址 / 换设备时先把旧连接关掉，避免旧 mux 一直挂着。
        connection?.close()
        // 先把缓存库在 IO 线程上建好，**再**建连接、把状态切成 `Paired`：`cache` 的 getter
        // 是普通（非挂起）函数，任何 Composable 都可能在第一帧读它，若那时 `lazy` 还没被
        // 初始化，`build()` 就会在那个线程上打开数据库文件（R006 P2-6）。这里预热后，之后
        // 所有读取都只是读一个已初始化的字段。
        withContext(Dispatchers.IO) { cacheLazy }
        connection = DshConnection(
            initialCredential = credential,
            unaryHttp = unaryHttp,
            streamHttp = streamHttp,
            scope = appScope,
        )
        // 审批请求是 waterfall：订阅必须**在连接建立的那一刻就活着**，不能等用户打开某个
        // 聊天页。晚一步的代价是「服务端已经把请求抛给第一个监听者」——没有监听者时它会
        // 一直等到请求自己的 signal 超时，用户看到的就是毫无反应（RemoteEventsClient KDoc）。
        connection?.remoteEvents?.start()
        _state.value = AppState.Paired

        // 每次激活主机都刷新「这个分区刚用过」并顺手做一次缓存维护（M57）。
        // 放在 `_state.value = Paired` 之后：这两件事都不该挡住首屏，而且维护失败
        // （`guard` 兜住）也绝不能影响连接本身——缓存只是优化，坏了就当没有。
        val owner = CacheMappers.ownerIdOf(credential)
        appScope.launch {
            // 三步（记最近使用 / 清孤儿 / 按 LRU 限量）的**顺序**与 **protect 的算法**
            // 都收在 `DshCache.maintainOnActivate` 里。刻意不在这里展开：这段编排曾写在
            // 本地，结果 protect 传错成整个凭证簿、上限静默失效，而这个文件没有测试覆盖，
            // 把错误写法改回去单测依然全绿（M57 复审 P1）。挪进缓存层之后，同样的错误
            // 会立刻让 DshCacheTest 变红。
            val known = credentialStore.loadBook().hosts.mapNotNull { host ->
                runCatching { CacheMappers.ownerIdOf(host) }.getOrNull()
            }
            cacheLazy.maintainOnActivate(
                activeOwner = owner,
                knownOwners = known.toSet(),
                maxOwners = MAX_CACHED_OWNERS,
            )
        }
    }

    companion object {
        /**
         * 最多保留多少个主机缓存分区（M57）。
         *
         * 4 是个有意的保守值：每个分区的 `messages` 表按「打开过的会话」累积，重度用户
         * 单分区可达数 MB。上限太大等于没有上限（低端机 SQLite 文件膨胀到几百 MB 后，
         * 连 `SELECT` 都会变慢）；太小则多主机用户频繁换主机时反复重拉 1.78 MB 的
         * `session/list`。4 台覆盖「公司 + 家里 + 笔记本 + 备用」这类常见组合。
         */
        private const val MAX_CACHED_OWNERS: Int = 4
    }
}
