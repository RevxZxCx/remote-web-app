package com.revxzxcx.remotewebapp.core

import android.content.Context
import android.util.Log
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
import com.revxzxcx.remotewebapp.core.notify.DeathWatcher
import com.revxzxcx.remotewebapp.core.notify.ExitHistoryReader
import com.revxzxcx.remotewebapp.core.notify.NoticeChannels
import com.revxzxcx.remotewebapp.core.notify.NoticeIntents
import com.revxzxcx.remotewebapp.core.notify.NoticeSettings
import com.revxzxcx.remotewebapp.core.notify.NoticeStatus
import com.revxzxcx.remotewebapp.core.notify.NotifyCoordinator
import com.revxzxcx.remotewebapp.core.capsule.CapsuleBridge
import com.revxzxcx.remotewebapp.core.capsule.CapsuleCenter
import com.revxzxcx.remotewebapp.core.capsule.CapsuleCoordinator
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter
import com.revxzxcx.remotewebapp.core.notify.SessionNameTable
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairingCoordinator
import com.revxzxcx.remotewebapp.core.prefs.PresetPreferences
import com.revxzxcx.remotewebapp.core.prefs.ThemePreferences
import com.revxzxcx.remotewebapp.core.service.LinkServiceController
import com.revxzxcx.remotewebapp.core.service.LinkServicePrefs
import com.revxzxcx.remotewebapp.core.service.LinkServiceUi
import com.revxzxcx.remotewebapp.core.service.ServicePreferences
import com.revxzxcx.remotewebapp.core.service.ServiceStatus
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import com.revxzxcx.remotewebapp.core.session.AskAnswer
import com.revxzxcx.remotewebapp.core.session.ChatFrame
import com.revxzxcx.remotewebapp.core.session.ChatRepository
import com.revxzxcx.remotewebapp.core.session.TurnProbe
import com.revxzxcx.remotewebapp.core.session.lastTurnEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
     * 当前那条「载波状态 → 通知层」的订阅（R056 缺口 3）。
     *
     * 换连接时必须先取消再重订：旧连接的 `stats` 是它自己的 `StateFlow`，
     * 留着不取消的话，旧连接最后那一帧 `Reconnecting` 会继续喂给判定器，
     * 新连接却连得好好的——表现是「明明连着，却一直说连不上」。
     */
    private var carrierJob: Job? = null

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
     * 新增会话页「模式」胶囊的上次选择（R055 / Q3）。
     *
     * 按主机分开记（key 形如 `preset_<hostId>`）：不同桌面端可用的档位不一定相同，
     * 「这台常用 PTC、那台常用标准」是真实场景。与 [themePreferences] 同样与容器同寿，
     * 构造时同步读一次 SharedPreferences。
     */
    val presetPreferences: PresetPreferences = PresetPreferences(appContext)

    /**
     * 通知相关偏好（R056）：两个 App 内开关 + 「权限问过没」。
     *
     * 与 [themePreferences] 同样与容器同寿，构造时同步读一次 SharedPreferences。
     */
    val noticeSettings: NoticeSettings = NoticeSettings(appContext)

    /**
     * 后台连接相关偏好（R057）：三个开关按主机分开记。
     *
     * 与 [noticeSettings] 分开一个 SharedPreferences 文件，是因为两者的生命周期不同：
     * 通知开关是全局的（一台手机一套），而后台连接开关是**按主机**的——用户完全可能
     * 只希望某台常连的保持后台连接。
     */
    val servicePreferences: ServicePreferences = ServicePreferences(appContext)

    /**
     * 系统通知的唯一出口（R056）。
     *
     * 只在这里建一个实例：`NotificationCenter` 内部持有「会话 → 通知 id」的池子
     * （[NoticeIdPool]），多建一个就会有两套 id 互相顶掉——表现为「跑完的会话点了没反应」。
     */
    val notificationCenter: NotificationCenter = NotificationCenter(appContext, noticeSettings)

    /**
     * 事件 → 通知的翻译层（R056）。
     *
     * 挂在 [appScope] 上而不是任何界面作用域：用户在聊天页跑任务、按 Home 切出去，
     * 只要进程还在，事件就该继续变成通知。这也是 Q1「只做事件→通知」的边界——
     * **进程被系统回收之后什么都收不到**，那种情况由设置页的提示来兜。
     */
    val notifier: NotifyCoordinator = NotifyCoordinator(
        scope = appScope,
        center = notificationCenter,
        settings = noticeSettings,
        names = SessionNameTable(),
        fetchLastTurn = { sessionId -> fetchLastTurnEnd(sessionId) },
    )

    /**
     * 后台连接的编排层（R057）。
     *
     * 与 [notifier] 并列放在容器里，理由相同：常驻通知与「任务跑完的提醒」都必须活过
     * 任何一个界面。区别是它还额外持有 [ServiceStatusTracker]——那个每秒推进的时长
     * 只能有一份，多看几份（服务一份、设置页一份）就会各自从不同起点开始数。
     *
     * `hostKeyProvider` 传的是**函数**而不是当前 hostId：开关按主机分开记，而主机随时
     * 会切换。存成固定值的话，换主机之后这个对象还在用旧 key 读写偏好，表现是
     * 「新主机明明没开开关，却起了服务」。
     */
    val linkService: LinkServiceController = LinkServiceController(
        context = appContext,
        scope = appScope,
        prefs = servicePreferences,
        notificationCenter = notificationCenter,
        hostKeyProvider = { linkStore.current().activeHost?.hostId ?: "" },
    )

    /**
     * 流体云胶囊的发布器（R059）。
     *
     * 单独一个而不是塞进 [NotificationCenter]：胶囊用的是 **Android 16 的 promoted
     * ongoing notification**，与现有的 `NotificationCompat` 那套（频道 ask/done/service）
     * 走的是两条不同的构造路径——胶囊要求 `ProgressStyle`、`setShortCriticalText`、
     * `android.requestPromotedOngoing` 这些 API 36 才有的东西，硬并进去会让
     * `NotificationCenter` 里每个方法都要先判一次 SDK_INT。
     */
    internal val capsuleCenter: CapsuleCenter = CapsuleCenter(appContext)

    /**
     * 把三路数据合成一条胶囊（R059）。
     *
     * 与 [notifier] 同级同作用域：胶囊是「我不在 App 里」的替代品，必须活过任何界面。
     */
    internal val capsule: CapsuleCoordinator = CapsuleCoordinator(
        scope = appScope,
        center = capsuleCenter,
    )

    /** 胶囊订阅事件流的作业（换连接时取消重建）。 */
    private var capsuleJobs: List<Job> = emptyList()

    /**
     * 把胶囊挂到某条连接上（R059）。传 `null` = 断开。
     *
     * ## 为什么胶囊自己订 `approvals` / `questions`，不借 `NotifyCoordinator`
     * `NotifyCoordinator` 订这两条流是为了**逐条发通知**（审批一条一个通知、提问一条
     * 一个通知）。胶囊要的是**当前全集**做焦点选举。两件事生命周期相同但语义不同，
     * 让前者转发后者会多一层「什么时候该转」的判断，而且转发必然晚一帧。
     *
     * `StateFlow` 的初值会在订阅瞬间立刻推一次，所以这里不需要额外拉一次现状。
     */
    private fun watchCapsule(client: com.revxzxcx.remotewebapp.core.events.RemoteEventsClient?) {
        capsuleJobs.forEach { it.cancel() }
        capsuleJobs = emptyList()
        if (client == null) {
            capsule.detach()
            // 断连后信箱里的会话/焦点/live 都属于**上一个连接**。不清掉的话，
            // 下次 attach 时 StateFlow 会把旧值立刻推给新连接，
            // 焦点选举据此选出一个不存在的会话（R059 第三轮审查 P2）。
            CapsuleBridge.reset()
            return
        }
        capsuleJobs = listOf(
            appScope.launch { client.approvals.collect { capsule.setApprovals(it) } },
            appScope.launch { client.questions.collect { capsule.setQuestions(it) } },
            // 界面那三路走信箱（`ChatViewModel` 拿不到容器）。订阅一次就够：
            // 信箱是进程级 `StateFlow`，换连接不会换掉它，但旧连接期间收到的
            // 会话列表属于上一台机器，所以开关胶囊功能时仍然跟着 `watchCapsule` 走。
            appScope.launch { CapsuleBridge.sessions.collect { capsule.setSessions(it) } },
            appScope.launch { CapsuleBridge.viewing.collect { capsule.setViewingSession(it) } },
            appScope.launch { CapsuleBridge.live.collect { capsule.setLive(it) } },
        )
    }

    /**
     * 首页把权威会话列表推进来（R059）。
     *
     * 胶囊与列表红点必须读**同一份**数据，否则会出现「列表显示有待批注意味着红点、
     * 胶囊却是绿的」这种自相矛盾。所以不在这里自己拉 `session/list`。
     */
    fun publishSessionsForCapsule(items: List<com.revxzxcx.remotewebapp.core.session.SessionSummary>) {
        capsule.setSessions(items)
    }

    /** 用户进/出某条会话（R059）：胶囊据此把焦点让给正在看的会话。 */
    fun publishViewingSessionForCapsule(sessionId: String?) {
        capsule.setViewingSession(sessionId)
    }

    /**
     * 正在看的会话的实时快照（R059）：todo 进度与汇报正文。
     *
     * 只有这条会话有这些数据（`session/list` 里没有 todo），所以后台会话选成焦点时
     * 卡片没有进度条——那是诚实的降级，不是 bug。
     */
    internal fun publishLiveForCapsule(snapshot: CapsuleCoordinator.LiveSnapshot?) {
        capsule.setLive(snapshot)
    }

    /**
     * 查一个会话窗口内最后一条 `turn/end`（R056 缺口 5）。
     *
     * 只在「检测到这一轮结束了」时调一次，拿到就取消订阅——**不常驻**。
     * 理由：常驻订阅要为每个正在跑的会话各开一条 `session/follow` 流，
     * 而通知层真正需要的只是「刚刚那一轮的结局」，一个瞬间值。
     *
     * 取快照而不是等流上的下一条事件：等下一事件意味着「已经结束的那一轮的
     * `turn/end` 永远不会再来」——它早就在历史窗口里了。
     *
     * 失败一律返回 null（连接断了、服务端拒绝、超时由调用方兜）：
     * 通知层据此退回「这一轮结束了」的旧口径，而不是彻底不发。
     */
    /**
     * 订阅一条连接的载波状态并喂给通知层（R056 缺口 3）。
     *
     * ## 为什么要订阅 `stats` 而不是等某个 `AppState` 变化
     * `AppState` 只有 `Loading / Unpaired / Paired` 三种，**不含「重连中」**：
     * 连接断了又自己在重试的过程里，`AppState` 一直是 `Paired`。也就是说
     * 「App 这会儿收不到东西」这件事在容器对外暴露的状态里**根本不存在**，
     * 只有 mux 自己知道（`MuxStats.carrier`）。
     *
     * ## 为什么要去重
     * `stats` 是 `StateFlow`，但 `MuxStats` 里还有帧计数等字段——它们每秒变几十次。
     * 每次都喂给判定器会让 90 秒宽限被「同一状态的重复值」不断刷新计时起点吗？
     * 不会（[LinkNoticeGate] 只在 Down 时记起点），但会产生大量无谓的调用。
     * 这里只把 `carrier` **真的变了**的那些帧转发下去。
     *
     * @param connection 新连接；传 `null` 表示没有连接了（退配对），此时退订即可。
     */
    private fun watchCarrier(connection: DshConnection?) {
        carrierJob?.cancel()
        carrierJob = null
        if (connection == null) {
            // 没有连接 = 不存在「正在重连」这回事，把判定器恢复成初始态。
            // 不复位的话，下次配对成功时会拿着上一台的断开计时立刻弹一条「连不上」。
            notifier.onCarrier(CarrierState.Idle, System.currentTimeMillis())
            // 服务层的状态也要跟着退：**一台主机都没有了**，留一条「正在连接」的
            // 常驻通知等于让用户一直等一个不会来的连接。
            //
            // 注意这里用的是 `onNoConnection` 而不是 `onCarrier(Idle)`（R057 审查 P2-1）：
            // `Idle` 在换主机时也会出现，那条路径必须让服务活着等新连接，
            // 详见 `ServiceStatusTracker.onCarrier` 的 Idle 分支与 `onNoConnection`。
            linkService.onNoConnection()
            return
        }
        carrierJob = appScope.launch {
            var last: CarrierState? = null
            connection.mux.stats
                .map { it.carrier }
                .distinctUntilChanged()
                .collect { carrier ->
                    // `distinctUntilChanged` 已经去重，这里的 `last` 只用于跳过
                    // 第一个值（订阅瞬间那帧不构成「变化」，否则每次切主机都会
                    // 把 Connected 当成一次「连上了」而去补一遍账）。
                    if (last == null) {
                        last = carrier
                        // 第一帧对通知层要跳过（见上），但对服务层不能跳过：
                        // 常驻通知里那句「运行中 · 已连上桌面端 · 2 分钟」的起点
                        // 就在这一帧里，跳过了它要等下一次状态变化才显示得出时长。
                        linkService.onCarrier(carrier)
                        return@collect
                    }
                    last = carrier
                    notifier.onCarrier(carrier, System.currentTimeMillis())
                    linkService.onCarrier(carrier)
                }
        }
    }

    private suspend fun fetchLastTurnEnd(sessionId: String): TurnProbe? {
        val link = linkStore.current()
        val connection = link.connection ?: return null
        return runCatching {
            val snapshot = ChatRepository(connection).follow(sessionId)
                .first { it is ChatFrame.Snapshot } as ChatFrame.Snapshot
            TurnProbe(
                lastEnd = lastTurnEnd(snapshot.envelopes),
                // 用快照自己算好的 `tailTurnOpen`，不拿信封重算一遍：这个字段是
                // `ChatFrameParser` 按窗口里最后一个 turn/start 与 turn/end 谁在后
                // 得出的，R004 P2-1 已经为它踩过一次坑，重算一份只会多一处会漂移的实现。
                tailTurnOpen = snapshot.tailTurnOpen,
            )
        }.getOrNull()
    }

    /**
     * 「上次进程是怎么没的」探测器（R056 / Q3）。
     *
     * **构造即探测**（[DeathWatcher.probe] 在初始化块里跑）：基线必须在这一刻写掉。
     * 若等到用户进首页再判，中间这段时间进程若又被杀（一加上完全可能），这次的历史
     * 会和上次重叠，下次启动提示的仍是旧事。
     *
     * 读的是一次 binder 调用（最多 20 条记录），开销远小于一次 Compose 首帧。
     */
    val deathWatcher: DeathWatcher = DeathWatcher(
        settings = noticeSettings,
        reader = ExitHistoryReader(appContext),
    ).also { it.probe() }

    /**
     * 回答一条来自通知按钮的审批（R056）。
     *
     * 由 [com.revxzxcx.remotewebapp.core.notify.NoticeActionReceiver] 调用：接收器
     * `onReceive` 返回后进程随时可能被收回，所以真正的网络往返必须在 [appScope] 上跑。
     *
     * ## 失败为什么要把通知改成「没送出」
     * 用户在通知栏点的按钮，此刻 App 可能根本没在前台，没有地方放错误横幅——这是
     * 「不弹提示」的正当理由，但**不是「什么都不说」的理由**。第一版就是 `runCatching {}`
     * 直接吞掉，结果是：连接断了，用户点「批准」，通知原样挂着、按钮原样能点，
     * 再点几次全是同样的沉默。他唯一能得出的结论是「这按钮坏了」，而任务正卡在那里等。
     *
     * 所以失败时调 [NotificationCenter.postAskFailed] 把那条通知原地改成
     * 「授权没能送出」+ 原因，按钮保留供重试。这是用户唯一能看到的通道。
     *
     * 成功时这里什么都不做：请求会从 `approvals` 消失，撤通知由
     * [NotifyCoordinator.syncApprovals] 的对账负责（单一职责，不在这里重复撤）。
     */
    fun answerFromNotification(eventId: String, allow: Boolean) {
        appScope.launch {
            val client = linkStore.current().connection?.remoteEvents
            if (client == null) {
                // 连接都还没建立：与其静默失败，不如立刻把原因写回那条通知。
                notificationCenter.postAskFailed(eventId, DISCONNECTED_HINT)
                return@launch
            }
            try {
                client.answer(eventId, allow)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 作用域被取消（进程要退），不是回答失败：不谎报「没送出」。
                throw e
            } catch (e: Exception) {
                notificationCenter.postAskFailed(eventId, describeAnswerFailure(e))
            }
        }
    }

    /**
     * 用户在流体云卡片上点了一个选项（R059）。
     *
     * ## 为什么先查 `questions` 再发
     * 卡片可能已经挂了几分钟，期间用户完全可能在桌面端答过同一道题。
     * 不查就发的话，服务端会回「这组问题已经不在等待回答」之类的错误，
     * 而卡片上那个按钮**看起来还能点**——用户会反复点、反复沉默。
     * 查过之后若确实不在等了，走与审批相同的「写回卡片」通路，把原因摆在明面上。
     *
     * 只提交**这一道题**：`answers` 数组只有一项。其余题目保持未答，
     * 服务端那边这组问题仍然处于等待状态——这与用户在 App 里逐题作答的语义一致
     * （多选题必须进 App，卡片上只放前两个选项，见 `CapsuleCoordinator`）。
     */
    fun answerQuestionOptionFromCapsule(
        eventId: String,
        callId: String,
        questionId: String,
        label: String,
    ) {
        appScope.launch {
            val client = linkStore.current().connection?.remoteEvents
            // 失败原因要写回**原来那条提问通知**，而它的 id 由 eventId 决定。
            // 用 callId 会落到审批段、凭空多一张孤儿卡片，且原提问卡片仍可点
            // （R059 审查 P2-2）。所以这里一律走 postQuestionFailed。
            // 胶囊传来的 id 可能是合成 id（`waterfall:<eventId>`，见
            // `QuestionRequest.capsuleCallId`），所以先剥前缀再比对 eventId，
            // 否则「有几个选项却找不到这个组」，失败原因会指错方向。
            val rawId = callId.removePrefix(com.revxzxcx.remotewebapp.core.events.RemoteEvents.WATERFALL_PREFIX)
            // 四种写法都必须认，且**必须与 `RemoteEventsClient.answerQuestions`
            // 里的条件逐条一致**。少认一种的后果不是「少匹配一次」，而是：
            // 这里判 `waiting == null` 就直接报「已经不在等待回答了」，
            // 而真正发请求的那一层其实能匹配上——用户看到一个假的失败原因，
            // 且原本可以成功的回答被拦下。两处条件必须一起改（R059 第二轮 P2）。
            val waiting = client?.questions?.value?.firstOrNull {
                it.callId == callId ||
                    it.callId == rawId ||
                    it.eventId == rawId ||
                    it.eventId == callId
            }
            val options = waiting?.questions?.firstOrNull { it.id == questionId }
                ?.options.orEmpty()
            fun fail(reason: String) {
                notificationCenter.postQuestionFailed(
                    eventId = eventId,
                    sessionId = waiting?.agentId,
                    questionId = questionId,
                    callId = callId,
                    options = options,
                    reason = reason,
                )
            }
            if (client == null) {
                fail(DISCONNECTED_HINT)
                return@launch
            }
            if (waiting == null || waiting.questions.none { it.id == questionId }) {
                fail("这组问题已经不在等待回答了（可能已在桌面端答过）。")
                return@launch
            }
            try {
                client.answerQuestions(
                    callId = callId,
                    answers = listOf(AskAnswer(id = questionId, selected = listOf(label))),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(describeAnswerFailure(e))
            }
        }
    }

    /**
     * 后台连接此刻的状态（R057）。
     *
     * 单一真相：常驻通知、设置页的状态行、「是否在跑」的判断全部读这一个流。
     * 服务自己**不持有**状态，它只把 `linkService.status` 画出来。
     */
    val serviceStatus: kotlinx.coroutines.flow.StateFlow<ServiceStatus> get() = linkService.status

    /**
     * 偏好（三个开关 + 确认标记 + 失败原因）的第几版，**每次写盘都 +1**。
     *
     * 设置页拿它当重读信号（第四轮审查 P2）。用信号而不是把偏好本身做成 `StateFlow`：
     * 设置页只在版本变化时同步读一次快照，不必为「偏好」再造一条每秒都可能被读的流。
     */
    val servicePrefsRevision: kotlinx.coroutines.flow.StateFlow<Int> get() = linkService.prefsRevision

    /**
     * 服务起来了（由 [ForegroundLinkService.onStartCommand] 调）。
     *
     * 委托给 [linkService]：清「本次已退出」、把状态推成「连接中」、开始每秒推进时长
     * 这三件事必须成组发生，散落在服务里就会漏掉其中一件。
     */
    fun onServiceStarted() {
        linkService.onStarted()
    }

    /** 服务停了（正常停止 / 用户点退出）。 */
    fun onServiceStopped() {
        linkService.onStopped()
    }

    /**
     * 服务**意外**停了（启动失败 / 被系统判定无效）。
     *
     * 与 [onServiceStopped] 的区别在于要不要惊动用户：这里按「自动恢复」开关的
     * 两个分支走（开着就静默拉起，关着才发通知），那条逻辑在
     * [LinkServiceController.ensureRunning] 里，这里只负责重新判一次。
     */
    fun onServiceUnexpectedStop() {
        linkService.onStopped()
        linkService.ensureRunning()
    }

    /**
     * 启动失败（`ForegroundServiceStartNotAllowedException` / `SecurityException` 等）。
     *
     * 记录原因，并按「这个失败用户能不能自己修」决定要不要把开关弹回去
     * （通知没授权时不弹——那是可恢复的前置条件，见 [LinkServiceController.onStartFailed]）。
     */
    fun onServiceStartFailed(cause: Throwable) {
        linkService.onStartFailed(cause)
    }

    /**
     * 服务**此刻该不该存在**（由 [ForegroundLinkService.onStartCommand] 每次校验）。
     *
     * ## 为什么服务要回头问容器（R057 审查 P1-3）
     *
     * 返回 `START_STICKY` 等于告诉系统「这个服务值得在被杀之后重起」。但系统只记得
     * 「它曾经值得」，不知道用户后来把「保持后台连接」关掉了。所以服务每次进来
     * （包括系统替我们重起的那次）都必须重新问一遍，而不是信任自己是被正当拉起的。
     *
     * 判据**不含「本次已退出」**：退出只影响用户点它的那一次，系统重起属于新的一次启动
     * （见 [ForegroundLinkService.onStartCommand] 的 KDoc）。
     */
    fun serviceWanted(): Boolean = linkService.wanted()

    /**
     * 按**当前活动主机**重新对账「服务该不该跑」：该跑就起来，不该跑就停掉。
     *
     * 三个开关是按主机分开记的，所以每次活动主机变了都要重估一次。
     * 之前只接了单向的 `ensureRunning()`，从「开着的 A」切到「关着的 B」时
     * 服务会一直挂着（第七轮审查 P2）。
     */
    private fun reconcileService() = linkService.reconcile()

    /** 设置页三个开关的写入入口（R057）；统一走控制器，避免调用点直接摸偏好。 */
    fun setServiceForegroundEnabled(enabled: Boolean) = linkService.setForegroundEnabled(enabled)

    fun setServiceAutoRecover(enabled: Boolean) = linkService.setAutoRecover(enabled)

    fun setServiceBootStart(enabled: Boolean) = linkService.setBootStart(enabled)

    fun markServiceConfirmSeen() = linkService.markConfirmSeen()

    /**
     * 只读三个开关 + 一次性确认标记，**不含每秒都在变的状态**（第三轮审查 P3-2）。
     *
     * ## 为什么要把「会变的」和「不变的」拆开
     * 拆分之前是一个 `linkServiceUi()`：一次读完状态 + 6 个偏好，设置页拿
     * `remember(serviceStatus, serviceUiEpoch)` 缓存它。`serviceStatus` 由
     * [ServiceStatusTracker.tick] 每秒 `copy` 一次，于是用户停在设置页的每一秒
     * 都会重建一次 UI 快照、做 **6 次 SharedPreferences 读取**
     * （status / enabled / autoRecover / bootStart / confirmSeen / lastFailure）。
     * 这些都是主线程上的同步磁盘读。
     *
     * 拆出来之后：状态走 `collectAsState`（本来就是每秒变），开关只在这个快照键变化时读，
     * 而它的键是 `serviceUiEpoch`——只在用户拨开关时递增。拼装交给
     * [LinkServicePrefs.withStatus]。
     */
    fun linkServicePrefs(): LinkServicePrefs = LinkServicePrefs(
        enabled = linkService.foregroundEnabled(),
        autoRecover = linkService.autoRecover(),
        bootStart = linkService.bootStart(),
        confirmSeen = linkService.confirmSeen(),
        lastFailure = linkService.lastFailure(),
    )

    /** 开机广播问「要不要起服务」（由 [BootReceiver] 调）。 */
    fun startServiceOnBoot(): Boolean = linkService.startOnBoot()

    /**
     * 用户在常驻通知里点了「退出」（R057）。
     *
     * 只是把意图转给 [linkService]：真正的三件事（置「本次已退出」、停服务、撤通知）
     * 只有它同时知道该怎么做，散在这里会让状态与服务不同步。
     */
    fun exitLinkService() {
        linkService.exitFromNotification()
    }


    private val DISCONNECTED_HINT = "连接已断开，进 App 重试。"

    /** 把回答失败的原因翻成一句用户看得懂的话（通知栏里没有错误码的位置）。 */
    private fun describeAnswerFailure(cause: Throwable): String {
        val code = (cause as? TypertException)?.code
        return when {
            code == null -> cause.message?.takeIf { it.isNotBlank() } ?: DISCONNECTED_HINT
            code.contains("transport", ignoreCase = true) -> DISCONNECTED_HINT
            code.contains("unavailable", ignoreCase = true) -> "这条请求已经失效了，进 App 看看。"
            else -> DISCONNECTED_HINT
        }
    }

    /**
     * 此刻四层通知开关的完整状态（R056 设置页用）。
     *
     * 四层是：系统总开关 / API 33 运行时权限 / 两个系统频道 / 两个 App 内开关。
     * 全部在这里读一次，是为了让 UI 只拿到一个纯数据对象——设置页不读 `DshConnection`
     * 也不读系统服务，它只负责把 [NoticeStatus] 画出来并回传动作。
     *
     * **只读，不修改任何状态。** 每次调用都现查，因为用户随时可能在系统设置里改完
     * 再切回来（这时不会有任何回调通知我们）。
     */
    fun noticeStatus(): NoticeStatus = NoticeStatus(
        systemEnabled = notificationCenter.systemEnabled(),
        permissionGranted = notificationCenter.runtimePermissionGranted(),
        requested = noticeSettings.permissionRequested,
        doneEnabled = noticeSettings.doneEnabled,
        askEnabled = noticeSettings.askEnabled,
        lockScreenDetail = noticeSettings.lockScreenDetail,
        askChannelEnabled = notificationCenter.isChannelEnabled(NoticeChannels.ASK),
        doneChannelEnabled = notificationCenter.isChannelEnabled(NoticeChannels.DONE),
        // 「后台连接」频道 App 内没有开关（不在 USER_TOGGLES 里），只能报它的系统状态。
        serviceChannelEnabled = notificationCenter.isChannelEnabled(NoticeChannels.SERVICE),
        // 流体云是**第五层**，与上面四层独立：它是 `prot=normal` 的普通权限，
        // 不弹运行时授权框，但对应的 AppOp 默认为 `ignore`，只能由用户手动开。
        capsuleSupported = capsuleCenter.supported(),
        capsulePromoted = capsuleCenter.promotedAllowed(),
        // 频道被单独关掉时 notify 会被系统静默丢弃，而 AppOp 仍是 allow，
        // 不查这一层设置页就会一直说「会在状态栏实时显示」（R059 审查 P2-3）。
        capsuleChannelEnabled = notificationCenter.isChannelEnabled(CapsuleCenter.CHANNEL_ID),
    )

    /**
     * 改 App 内开关（R056）。三条都只是写偏好——它们不影响已在队列里的通知，
     * 下一条事件到来时才生效，所以不需要在这里撤任何东西。
     */
    fun setNoticeChannelEnabled(channelId: String, enabled: Boolean) {
        noticeSettings.setChannelEnabled(channelId, enabled)
    }

    /** 锁屏是否显示详细内容（R056）。 */
    fun setLockScreenDetail(enabled: Boolean) {
        noticeSettings.lockScreenDetail = enabled
    }

    /**
     * 记录「已经主动请求过通知权限」（R056）。
     *
     * 必须在**调用 `requestPermissions` 之前**置位，不能等结果：权限被永久拒绝后，
     * 系统会直接回调拒绝且不再弹框，等结果就永远等不到，App 会一遍遍以为「还没问过」
     * 并一次次毫无反应地调下去。
     */
    fun markNoticePermissionRequested() {
        noticeSettings.permissionRequested = true
    }

    /**
     * 权限弹窗有了结果（R056）。
     *
     * 做两件事：
     * 1. 如果拿到了，把频道补齐——首次安装时 `ensureChannels()` 在
     *    `bootstrap()` 里跑过，但那之后用户可能已经清掉了 App 数据里的通知设置，
     *    系统里的 channel 也就跟着没了。再调一次是幂等的。
     * 2. **重试一次后台连接服务**（第五轮审查 P1）。
     *
     * ## 为什么第 2 件非做不可
     * Android 13+ 新装默认没有 `POST_NOTIFICATIONS`。用户首次打开「保持后台连接」时
     * `startService()` 因为 `noticeUsable()` 为假直接失败，而 `startFailurePolicy`
     * 对这种情况明确「不关开关、不挡重试」——开关亮着、服务没起来，是**正确**的中间态：
     * 用户去授权就能好。但「能好」得有代码把它变好：`ensureRunning()` 原来只有
     * 载波变化和意外停止两个调用点，而连接稳定之后不会再产生新载波帧，
     * 于是用户授权回来，服务永远起不来，设置页长期停在「开但未运行」。
     * 那正是用户第 1 条约束「通知必须如实写状态」要否定的东西。
     *
     * 放在这里而不是监听系统设置：这个回调是我们**确知**用户刚刚给了权限的时刻，
     * 比轮询或猜时机可靠。
     */
    fun onNoticePermissionResult(granted: Boolean) {
        if (!granted) return
        notificationCenter.ensureChannels()
        // R059：胶囊频道同一条理由——授权之后可能才第一次真正能发通知。
        capsuleCenter.ensureChannel()
        linkService.ensureRunning()
    }

    /**
     * App 回到前台（由 [DshApplication] 的生命周期观察者转发）。
     *
     * 同样补一次 [LinkServiceController.ensureRunning]：用户很可能是**离开 App 去系统设置
     * 里开通知权限**，回来时不会有任何权限回调（那个回调只在弹窗路径上有）。
     * 连接在这段时间里是稳定的，载波不会产生新帧，所以不补这一次就永远补不上。
     */
    fun onAppForeground() {
        linkService.ensureRunning()
    }

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

    /**
     * 进程级 bootstrap：**保证只跑一次**，谁先要谁触发。
     *
     * ## 为什么不能只由 UI 触发（R057 审查 P1）
     *
     * [bootstrap] 原先只在 `AppRoot` 的 `LaunchedEffect(Unit)` 里调。那条路径在
     * 「用户打开 App」时成立，但 R057 的两个场景**根本不会打开 App**：
     *
     * 1. 开机自启：[BootReceiver] 直接起服务，Activity 从未创建；
     * 2. `START_STICKY` 重启：系统拉起服务，同样不经过 UI。
     *
     * 这两种情况下没人建连接，`watchCarrier` 不跑，`linkService.onCarrier` 永远收不到帧，
     * 状态钉在 [ServicePhase.Connecting]——常驻通知写着「正在连接桌面端」写一整夜，
     * 时长永不出现。用户 Q1「状态如实写」在这里是直接违反的，而且是最糟的一种：
     * 唯一的后台入口看起来在工作，实际上从来没连过。
     *
     * 所以触发点必须**与进程同寿**，由最早的那个发起者（Application 或服务）拉起。
     * 用 `AtomicBoolean` 而不是 `@Volatile` 布尔：两个入口可能真的并发进来
     * （服务 `onStartCommand` 跑在主线程，`Application.onCreate` 之后紧接着就可能来），
     * 只有 CAS 才能保证 `installConnection` 不被跑两遍——跑两遍会建出两条连接，
     * 而审批 waterfall 只允许一条（见 [ForegroundLinkService] 的 KDoc）。
     *
     * 幂等：第二次之后直接返回。失败也不抛给调用方——bootstrap 失败等于「没有可用主机」，
     * 那是 [AppState.Unpaired] 的正常一相，不该让开机流程崩掉。
     */
    fun bootstrapOnce() {
        if (!bootstrapped.compareAndSet(false, true)) return
        appScope.launch {
            runCatching { bootstrap() }.onFailure { Log.w(TAG, "bootstrap 失败", it) }
        }
    }

    /** [bootstrapOnce] 的闸门。见它的 KDoc：必须 CAS，不能只是 if 判读。 */
    private val bootstrapped = AtomicBoolean(false)

    /** 冷启动：把落盘的凭证簿读回来。解不开 / 不存在都按未配对处理。 */
    suspend fun bootstrap() {
        // 频道必须在**任何 notify() 之前**建好：Android 8+ 往一个不存在的频道发通知，
        // 系统直接丢弃且不抛异常（`NO_CHANNEL` 只会打个日志）。放在 bootstrap 里而不是
        // 构造里，是因为建频道要碰系统服务，而本类的构造被承诺不碰磁盘/系统（见类 KDoc）。
        notificationCenter.ensureChannels()
        // R059：胶囊自己的频道（重要性 LOW，与 ask/done/service 三个都不同：
        // 频道重要性创建后**不可改**，所以只能另建一个）。
        capsuleCenter.ensureChannel()
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
    /**
     * 把「当前活动主机」记进 [ServicePreferences]（第三轮审查 P1-1 的另一半）。
     *
     * ## 为什么必须落盘一份
     * [LinkServiceController.hostKey] 读的是 `linkStore.current().activeHost?.hostId`，
     * 而 `linkStore` 要等 `bootstrapOnce()` 在 **IO 线程**跑完 `bootstrap()` →
     * `installConnection` → 本方法所在的发布点，才有第一台主机。
     *
     * 开机那条路径上这两件事是**并发**的：`Application.onCreate` 里
     * `container.bootstrapOnce()` 只是 `appScope.launch`（异步），紧接着
     * `BootReceiver.onReceive` 在主线程问 `startServiceOnBoot()`。那一刻 hostKey 还是空串，
     * `ServicePreferences.key()` 落到 `NO_HOST`（"none"）后缀，读到的是三个默认值——
     * `foregroundEnabled=false`，于是 [shouldStartOnBoot] 恒 false。
     * **「开机自启」整条链路是死的**，而不只是少一个按钮。
     *
     * 同一个空窗口也命中 `wanted()`：系统 `START_STICKY` 重启服务时判 false，
     * 服务当场被停——「被杀后自动恢复」同样不成立。
     *
     * 所以每次发布活动主机就把 hostId 落一份；`Controller.hostKey` 在拿到空串时
     * 回退读它。凭证簿仍然**不做**同步读（[CredentialStore] 全是 suspend 方法，
     * 加一个非 suspend 入口会让主线程读盘）。
     *
     * 簿里一台可用主机都没有时清掉：留着旧值会让「退配对之后开机还想连」复活。
     */
    private fun rememberServiceHost(book: CredentialBook) {
        val hostId = book.active?.hostId
        if (hostId.isNullOrBlank()) {
            servicePreferences.clearLastHostKey()
        } else {
            servicePreferences.setLastHostKey(hostId)
        }
    }

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
                rememberServiceHost(book)
                _state.value = AppState.Paired
                // 通知订阅跟着连接走。复用分支里 `attach` 收到的是同一个实例，
                // 它会立刻返回（不改订阅）——配置变更重跑 bootstrap 时不会重复订一遍。
                notifier.attach(plan.connection.remoteEvents)
                watchCapsule(plan.connection.remoteEvents)
                watchCarrier(plan.connection)
                // 换主机之后按**新主机**的开关重新对账（第七轮审查 P2）。
                reconcileService()
            }

            ConnectionPlan.Drop -> {
                // 没有可用主机了（空簿 / 全部失效）：回到配对页，再释放旧连接。
                // 顺序同 `installRebuilt`：先让「已无连接」这一事实对外可见，再关旧的，
                // 避免观察者在中间读到一条已经关掉的连接。
                val previous = linkStore.current().connection
                linkStore.publish(book = book, connection = null)
                rememberServiceHost(book)
                _state.value = AppState.Unpaired
                // 先退订再关连接：反过来的话，`RemoteEventsClient` 已经随连接一起死了，
                // 协调器手里那个引用指向一条不再发事件的流，重连后也不会自己好。
                notifier.attach(null)
                watchCapsule(null)
                watchCarrier(null)
                // 退配对 = 这台设备不再属于任何主机：通知栏里那些条目全是上一台机器的，
                // 留着只会让用户点进一个空 App。待打开的会话 id 同理。
                notificationCenter.cancelAll()
                // 「连不上」那条也必须撤：它说的是「等一下会重连」，
                // 而退配对之后再也不会重连了，留着等于一句永远兑现不了的承诺。
                notificationCenter.cancelLink()
                NoticeIntents.clear()
                previous?.close()
                // 一台主机都没有了：不管上一台的开关是什么，服务都不该继续挂着。
                reconcileService()
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
        // 上一台主机留下的通知必须先撤掉（R056）。它们背后是**另一台机器**上的会话：
        // id 在这台机器上不存在，点进去只会得到「会话不存在」；审批通知更危险——
        // 它的「批准」按钮会把回答发给一台根本不相干的机器，而用户看不出区别。
        // 放在 attach 之前：撤完刚好接上新连接的事件，中间不会漏也不会掺。
        notificationCenter.cancelAll()
        // 通知订阅同样要跟着新连接走：`NotifyCoordinator` 只在构造时订一次是不行的——
        // 换主机就换了一个 `RemoteEventsClient`，旧引用指向一条已死的流，
        // 而那种「静默不再响」最难被发现（用户只会觉得「最近怎么不提醒了」）。
        notifier.attach(rebuilt.remoteEvents)
        watchCapsule(rebuilt.remoteEvents)
        watchCarrier(rebuilt)
        // B004 的关键一行：新簿与新连接在**同一次**赋值里换掉。放到这里（而不是更早）是因为
        // 上面每个挂起点之后都不该让观察者看到半新半旧的一份。
        linkStore.publish(book = book, connection = rebuilt)
        rememberServiceHost(book)
        _state.value = AppState.Paired
        // 换主机之后按**新主机**的开关重新对账（第七轮审查 P2）。
        // 放在 publish 之后：上面那行已经把新 book 发布出去，`linkService.hostKey`
        // 读到的才是新主机的 hostId，否则读到的是上一台的开关。
        reconcileService()

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

        private const val TAG: String = "AppContainer"
    }
}
