package com.revxzxcx.remotewebapp.core

import android.content.Context
import com.revxzxcx.remotewebapp.core.cache.CacheMappers
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.cache.DshDatabase
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.connection.HostProbe
import com.revxzxcx.remotewebapp.core.connection.HostProbeClient
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.AddedHost
import com.revxzxcx.remotewebapp.core.credentials.planHostAdd
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
 * **不再携带凭证**——当前是哪一台要问 [AppContainer.link]（B004 起簿与连接粘成一份
 * 原子快照，不再有单独的 `activeHost` 入口），否则每换一次主机
 * 就要重建整棵 UI 树（HomeViewModel 挂在 `key = "home-${deviceId}"` 上，换 key 就是换
 * ViewModel，聊天页会丢状态、列表要重拉 1.78 MB）。
 */
sealed interface AppState {

    /** 还在读本地凭证簿（冷启动第一帧）。 */
    data object Loading : AppState

    /** 簿里一条主机都没有 → 显示配对页。 */
    data object Unpaired : AppState

    /** 至少有一台主机可用。当前是哪一台见 [AppContainer.link]。 */
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
    private val _state = MutableStateFlow<AppState>(AppState.Loading)

    /** 当前连接状态；UI 只观察这个。 */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * 「当前凭证簿 + 它对应那条连接」的**唯一发布点**（B004）。
     *
     * 早先 `book` 与 `connection` 是两个各自独立的字段，切换主机时簿先发布、连接后安装，
     * 中间隔着 `withContext(Dispatchers.IO)` 这个真挂起点，于是重组时能读到「新簿 + 旧连接」，
     * 而 `Paired → Paired` 的 `AppState` 不发射、没有任何后续事件纠正它——用户看到的就是
     * 「切了设备，首页还是上一台的工作区」（B004）。
     */
    private val linkStore = LinkStore()

    /**
     * 当前快照。**UI 唯一该读的 flow**（B004）。
     *
     * 刻意**不再**提供 `book` / `connection` / `activeHost` / `ownerId` 四个独立入口：
     * 它们曾各自从不同字段读，切换主机时能读到「新簿 + 旧连接」的半新半旧组合，而
     * `Paired → Paired` 不发射、没有后续事件纠正，页面就停在上一台的数据上（B004）。
     * 把入口削到只剩这一个，正确性就不再依赖调用方自觉。
     */
    val link: StateFlow<ActiveLink> = linkStore.link

    /** 当前连接（非响应式读；仅供容器内部与不关心重组的调用方）。 */
    private val connection: DshConnection?
        get() = linkStore.current().connection

    /** 冷启动：把落盘的凭证簿读回来。解不开 / 不存在都按未配对处理。 */
    suspend fun bootstrap() {
        // 已在运行中且簿没变：复用现有连接（配置变更会重跑 bootstrap，见 installConnection）。
        val loaded = credentialStore.loadBook()
        val active = loaded.active?.takeIf { it.isUsable }
        // 一台可用的都没有时 `installConnection` 会走 `Drop` 分支（关连接 + Unpaired）。
        installConnection(loaded)
    }

    /**
     * 配对成功后由 UI 调用：把新主机加入凭证簿并设为当前。
     *
     * 返回落盘后的那条凭证（已带 `hostId`），调用方通常不需要它。
     */
    suspend fun onPaired(credential: DeviceCredential): DeviceCredential = bookLock.withLock {
        val next = credentialStore.loadBook().withUpsert(credential)
        credentialStore.saveBook(next)
        val active = checkNotNull(next.active) { "刚写入的主机必须是可激活的" }
        installConnection(next)
        active
    }

    /**
     * [addHost] 的带重复判断版本（B005）。
     *
     * 重复的判据**只在这里判一次**，而且必须在锁内、拿「落盘前」的簿判：
     * `withUpsert` 落盘之后同一个地址一定只剩一行，事后回查永远看不出「原来就有」。
     *
     * 为什么不让 [PairingCoordinator] 顺手判了带回来：那样就有两套判据了。
     * 配对协调器看不到锁，也不能保证判完之后簿没被别人改过——
     * B004 的教训正是「判据重复 → 真实执行走的是没被测的那一份」。
     */
    suspend fun addHostChecked(credential: DeviceCredential): AddedHost = bookLock.withLock {
        addHostLocked(credential)
    }

    /**
     * 添加远程（R054）：把新主机写进凭证簿，**但不切换当前主机**。
     *
     * 与 [onPaired] 的唯一区别就是「要不要激活」。首次配对必须激活（否则用户配对完还
     * 停在配对页）；而「添加远程」是给已有连接的用户再加一台，此时把当前主机抢走是
     * 有害的——用户可能只是想先把它存进列表，等下再切。
     *
     * 所以这里**不会抢走当前主机**：当前连接继续跑，UI 停在原处，只在设备栏里多出
     * 一行。要不要切由调用方随后调 [switchHost]。
     *
     * 但它**要经过一次 [installConnection]**：加进来的如果是「当前正在用那台」的地址，
     * `withUpsert` 会把那一行的 `deviceId` 换成新的（桌面端每次 accept 都发新 id），
     * 连接里嵌的旧 id 就作废了。交给 `planFor` 判：别的地址 → `Reuse`（实例原样带过、
     * 代次不变）；同一地址但 deviceId 变了 → `Rebuild`。
     *
     * @return 落盘后的**刚添加那台**（已带 `hostId`）。同一个地址此前登记过时，
     *   [CredentialBook.withUpsert] 会就地更新那一行而不是新增。
     */
    suspend fun addHost(credential: DeviceCredential): DeviceCredential = bookLock.withLock {
        addHostLocked(credential).credential
    }

    /**
     * [addHost] 的实现体，**调用方必须已经持有 [bookLock]**。
     *
     * 抽出来是为了让 [addHost] 与 [addHostChecked] 共用同一条写入路径：
     * 若后者在外层 `withLock` 里再调前者，会二次加锁而死锁。
     */
    private suspend fun addHostLocked(credential: DeviceCredential): AddedHost {
        // 两个判断（先判重复、返回按地址回查）都在 planHostAdd 里，纯 JVM 可测。
        val plan = planHostAdd(credentialStore.loadBook(), credential)
        credentialStore.saveBook(plan.book)
        // ⚠️ 这里**不能**只把旧连接原样带过（早先就是这么写的，是 B004 的同形缺陷）：
        // 重复添加**当前正在用那台**的地址时，`withUpsert` 会把那一行的 `deviceId` 刷新成
        // 新的（桌面端每次 accept 都发新 id），而连接里嵌的还是旧 `deviceId`——簿说新的、
        // 连接用旧的，之后每个请求都 403 unpaired，且没有任何事件会纠正它。
        // 交给 `installConnection`：地址不同 → `Reuse`（实例原样带过、代次不变，不重建 VM）；
        // 地址相同但 deviceId 变了 → `Rebuild`（关旧连接、建新连接、代次自增）。
        installConnection(plan.book)
        return plan.added
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
        val next = if (current.activeHostId != hostId) {
            current.withActive(hostId).also { credentialStore.saveBook(it) }
        } else {
            current
        }
        // B004：簿与连接一次性发布。这里是缺陷的原始现场——早先 `_book.value = next`
        // 在这里、`installConnection` 在下面一行，中间隔着预热缓存库的挂起点。
        installConnection(next)
        target
    }

    /** 改显示名。空名字按「清掉自定义名」处理，UI 会回退显示地址。 */
    suspend fun renameHost(hostId: String, label: String) = bookLock.withLock {
        val next = credentialStore.loadBook().withRenamed(hostId, label)
        credentialStore.saveBook(next)
        linkStore.publish(book = next, connection = linkStore.current().connection)
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
        val active = next.active ?: return@withLock null
        // 地址是 socket 的一部分：改的是当前那台时 [planFor] 会判 `Rebuild`，改的是别的主机
        // 则判 `Reuse`（连接实例原样带过、代次不变）。两种情况都由一次发布完成。
        installConnection(next)
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
        // 缓存分区按 ownerId 精确认领：只清这台自己的，别的主机不受影响（R054）。
        // 用容器 scope 而不是当前协程：删除动作的调用方（UI）可能立刻退出组合。
        val removedOwner = CacheMappers.ownerIdOf(removed)
        appScope.launch { cacheLazy.pruneOwner(removedOwner) }

        // 删掉的不是当前那台时，连接不动（`planFor` 会判 `Reuse`，实例原样带过）。
        // 删掉的是当前那台时，`CredentialBook.active` 会回退到剩下的第一台 → `Rebuild`；
        // 一台不剩则 `Drop`（关连接 + 回到配对页）。
        installConnection(next)
        next.active?.takeIf { it.isUsable } != null
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
        pairing.unpair()
        // 空簿必然走 `Drop`：关连接、发布空快照、状态回 `Unpaired`。
        installConnection(CredentialBook.EMPTY)
        Unit
    }

    /**
     * 把 [book] 这个快照与它的连接**一次性**发布出去（B004）。
     *
     * 为什么参数是整本簿而不只是「目标凭证」：早先这个方法的签名只收一条凭证，调用方
     * （`switchHost` / `updateEndpoint` / `removeHost`）各自先写 `_book.value` 再进来，于是
     * 「簿换了吗」与「连接换了吗」分属两次发布，中间隔着下面那个 `withContext(Dispatchers.IO)`
     * 真挂起点。收成一次调用之后，该方法内部保证**只有一次** [LinkStore.publish]，
     * 且它发生在连接就绪之后——观察者要么看到旧的一整份，要么看到新的一整份。
     *
     * 「该复用还是该重建」的判断本身不写在这里，而是交给 [planFor]：那个纯函数能在 JVM
     * 单测里直接断言，而本方法要真建 [DshConnection]，测不了。编排留在测不到的地方，
     * 改回错误写法单测依然全绿是有前科的（M57 复审 P1）。
     */
    private suspend fun installConnection(book: CredentialBook) {
        when (val plan = planFor(linkStore.current().connection, book)) {
            is ConnectionPlan.Reuse -> {
                // 配置变更（旋转 / 深浅色切换）会重建 Activity，`AppRoot` 的 `LaunchedEffect(Unit)`
                // 因此会再跑一次 bootstrap。endpoint 没变时**必须复用**现有连接：
                // `HomeViewModel` 被 ViewModelStore 保留着，仍持有它；若关掉重建，旧流会以
                // 「正常完成」结束（`RemoteStreamMux.close()` 不带 cause），既不报错也不触发
                // `retryWhen`，页面就停在旧列表和「已连接」上直到用户手动刷新。
                //
                // 凭证对象本身要更新（label 可能刚被改过），否则顶栏标题读的是旧名字。
                plan.connection.updateCredential(plan.credential)
                // 复用 = 同一个连接实例，`publish` 据此保持代次不变（不会重建整组 ViewModel）。
                linkStore.publish(book = book, connection = plan.connection)
                _state.value = AppState.Paired
            }

            ConnectionPlan.Drop -> {
                // 没有可用主机了（空簿 / 全部失效）：回到配对页，再释放旧连接。
                // 顺序同 `installRebuilt`：先让「已无连接」这一事实对外可见，再关旧的，
                // 避免观察者在中间读到一条已经关掉的连接。
                val previous = linkStore.current().connection
                linkStore.publish(book = book, connection = null)
                _state.value = AppState.Unpaired
                previous?.close()
            }

            is ConnectionPlan.Rebuild -> {
                installRebuilt(book, plan.credential)
            }
        }
    }

    /** [ConnectionPlan.Rebuild] 分支：新建连接、发布、再关旧连接、最后触发一次缓存维护。 */
    private suspend fun installRebuilt(book: CredentialBook, credential: DeviceCredential) {
        // ⚠️ 旧连接要**等新连接发布之后**才关（见方法末尾），不能在这里先关。
        // 这一行早先是 `linkStore.current().connection?.close()`，之后要跨过
        // `withContext(Dispatchers.IO) { cacheLazy }` 这个挂起点才发布新连接——中间任何
        // 一步抛异常（缓存库建不起来、`DshConnection` 构造失败），`link` 就永远停在一个
        // **已经 close 的**旧连接上，而 `_state` 还是 `Paired`：界面不报错、不重试、也不
        // 回配对页，用户只能杀进程。先发布再关旧连接，这个窗口就不存在。
        //
        // 先把缓存库在 IO 线程上建好，**再**建连接、把状态切成 `Paired`：`cache` 的 getter
        // 是普通（非挂起）函数，任何 Composable 都可能在第一帧读它，若那时 `lazy` 还没被
        // 初始化，`build()` 就会在那个线程上打开数据库文件（R006 P2-6）。这里预热后，之后
        // 所有读取都只是读一个已初始化的字段。
        withContext(Dispatchers.IO) { cacheLazy }
        // 发布之前先从 `link` 里捉住旧连接，发布之后就取不到了。
        val previous = linkStore.current().connection
        val rebuilt = DshConnection(
            initialCredential = credential,
            unaryHttp = unaryHttp,
            streamHttp = streamHttp,
            scope = appScope,
        )
        // 审批请求是 waterfall：订阅必须**在连接建立的那一刻就活着**，不能等用户打开某个
        // 聊天页。晚一步的代价是「服务端已经把请求抛给第一个监听者」——没有监听者时它会
        // 一直等到请求自己的 signal 超时，用户看到的就是毫无反应（RemoteEventsClient KDoc）。
        rebuilt.remoteEvents.start()
        // B004 的关键一行：新簿与新连接在**同一次**赋值里换掉。放到这里（而不是更早）是因为
        // 上面每个挂起点之后都不该让观察者看到半新半旧的一份。
        linkStore.publish(book = book, connection = rebuilt)
        _state.value = AppState.Paired

        // 新连接已经对外可见，这时才轮到释放旧的（见方法开头）。放在这里也顺带保证：
        // 即便 close 抛异常，用户手上的已经是能用的新连接。
        previous?.close()

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
