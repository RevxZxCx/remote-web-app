package com.revxzxcx.remotewebapp.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.revxzxcx.remotewebapp.DshApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 保持后台连接的前台服务（R057）。
 *
 * ## 这个类**不自己建连接**
 *
 * 它只做三件事：把进程提到前台服务态、把载波状态画成一条常驻通知、把「退出」按钮
 * 落到实处。真正的 socket 由 [com.revxzxcx.remotewebapp.core.AppContainer] 的
 * `linkStore` / `DshConnection` 持有——这与 R056 已建立的架构一致，通知层从来都是
 * 旁路观察者，不是连接的拥有者。
 *
 * 为什么要这么分工：如果服务自己建一条连接，就会出现「服务里一条、UI 里一条」，
 * 两条连接各自收事件流——而 `RemoteEventsClient` 的审批订阅是 **waterfall**，
 * 两处订阅会让服务端把审批抛给随机一个，用户点了批准、聊天页却不知道。R056 已经
 * 为此把事件订阅收敛到 `AppContainer` 里唯一一份，这次不能再拆开。
 *
 * ## 服务存在的唯一目的：让进程别被冻结
 * 一加/ColorOS 在 App 切后台约 30 秒后会把进程 `freezing`（实测见 R056 真机记录），
 * 冻结期间 WebSocket 收不到事件 → 通知不响。前台服务把进程提到前台服务态，
 * 显著降低被冻结的概率。**这是「尽量活」不是「一定活」**：dontkillmyapp 上
 * 一加仍是 5 💩 最差档，官方写「No known solution on the developer end」。
 *
 * ## `specialUse` 而不是 `dataSync`
 * `dataSync` 受 Android 15 的「24 小时累计 6 小时」限制，超时抛
 * `RemoteServiceException: A foreground service of type dataSync did not stop within
 * its timeout`。`specialUse` 不在限制名单里，官方文档明确「目前此项限制仅适用于
 * dataSync 与 mediaProcessing」，且 `BOOT_COMPLETED` 的禁用清单也不含它。
 */
class ForegroundLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private lateinit var notice: ServiceNotice
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notice = ServiceNotice(this)
        // 频道必须在这里保证存在：服务可能被开机自启拉起，那时 bootstrap() 从没跑过。
        ensureServiceChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as? DshApplication)?.container
        if (container == null) {
            // Application 不是我们的（理论上不会，清单里写死了）：不能假装在跑。
            requestStop(tellUser = false)
            return START_NOT_STICKY
        }

        // 🔴 每次（含系统因 `START_STICKY` 重新拉起）都要重新校验开关（R057 审查 P1-3）。
        //
        // 系统记住的是「这个服务值得重起」，它不知道用户后来把开关关了。不加这道判断，
        // 用户关掉开关、服务恰好在那一刻被系统回收，几秒后它自己又回来了——
        // 常驻通知重新出现，而设置页写着「关」。这是最难让用户相信的 bug 类型：
        // 他看着开关是关的，通知栏却有条通知，只能认为 App 坏了。
        //
        // 也不能顺手把「本次已退出」当成阻止条件：退出只影响用户点它的那一次，
        // 系统重起属于新的启动（用户没有新表达），而 Q4 之后重新拉起的路径本来就包含
        // 「服务被系统停掉 → 自动恢复」。挡住它会让「退出」变成永久的。
        if (!container.serviceWanted()) {
            requestStop(tellUser = false)
            return START_NOT_STICKY
        }

        // 5 秒窗口：`startForeground()` 必须在 onStartCommand 返回前调用，
        // 否则进程被 ANR。所以先用「连接中」这一相顶上，再去订阅真实状态。
        //
        // 🔴 失败必须**就地返回**（R057 审查 P1）。早先这里不看返回值，catch 之后照旧
        // 往下走 `started = true; container.onServiceStarted()`，而 `onStarted()` 里有一句
        // 「起来了就把上次失败原因清掉」——于是刚写进去的失败原因被自己抹掉：开关被关、
        // 设置页因 `!enabled` 连状态行都不画，用户看到的是「开关自己弹回去了，没有任何解释」。
        // 返回 START_NOT_STICKY 也必不可少：此刻并没有真的在前台，让系统「按原样再来一次」
        // 只会把同一个失败重复一遍。
        if (!raiseForeground(ServiceStatus(phase = ServicePhase.Connecting))) {
            return START_NOT_STICKY
        }
        started = true

        // 「本次已退出」在这里清掉：服务真的起来了，说明这条路径没被退出挡住。
        // 调控制器而不是直接摸标记：标记清了之后还得把状态推成「连接中」并开始
        // 每秒推进时长，三件事必须成组发生（见 LinkServiceController.onStarted）。
        container.onServiceStarted()
        renderJob?.cancel()
        renderJob = scope.launch { render(container.serviceStatus) }
        return START_STICKY
    }

    /**
     * 订阅载波状态并刷新通知。
     *
     * 用 `collect` 而不是 `collectLatest`：每次都要把最新的那一相完整画出来，
     * 中间帧被取消会让时长停在一个旧值上（通知里显示「已连接 3 秒」卡住不动）。
     *
     * ## 为什么按「可见文案」去重，而不是每帧都 notify
     * 控制器每秒 `tick()` 一次推进时长，`ServiceStatus` 因此**每秒都是新对象**
     * （`connectedForMillis` 变了），`collect` 也就每秒收到一帧。但用户看得见的
     * 那几行字是 [ServiceText.title] / [ServiceText.text] 拼出来的，而
     * `formatDuration` 只到「分钟」这一级——**一分钟里 59 帧画出来的通知一模一样**。
     *
     * 真机上照原样每帧 `notify()` 的后果是实测到的：通知栏每秒被重发一次，
     * 系统因此**永远进不了静止态**（`uiautomator dump` 直接报
     * `ERROR: could not get idle state`），自动化点按全都没法做；顺带还白耗电、
     * 白占 `NotificationManager` 的 binder 往返。
     *
     * 所以这里把「画出来的两个字串」当去重键：只有标题或正文真的变了才发。
     * 时长跨过分钟边界时它自然会变，通知照样跟着走。
     */
    private suspend fun render(status: StateFlow<ServiceStatus>) {
        var lastKey: String? = null
        status.collect { current ->
            if (current.phase == ServicePhase.Stopped) {
                stopSelf()
                return@collect
            }
            val built = notice.build(current)
            if (built == null) {
                stopSelf()
                return@collect
            }
            val key = ServiceText.title(current) + '\u0000' + ServiceText.text(current)
            if (key == lastKey) return@collect
            lastKey = key
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            // 直接 notify 而不是再调 startForeground：第二条以后的更新用 notify 即可，
            // 反复 startForeground 在部分 ROM 上会重置「用户是否划过」的状态。
            manager?.notify(ServiceNotice.NOTICE_ID, built)
        }
    }

    /**
     * 把自己提成前台服务。
     *
     * ## 为什么要按版本分支
     * API 34 起 `startForeground` 要求显式声明类型（`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`），
     * 不传的话直接 `InvalidForegroundServiceTypeException`。API 29~33 也需要类型参数
     * 但只支持 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST]（"用清单里写的那个"）。
     * API 28 及以下没有类型概念。
     *
     * ## 失败必须兜住
     * `ForegroundServiceStartNotAllowedException`（后台启动限制）和
     * `SecurityException`（类型没在清单里声明）都可能在用户看不见的时候抛出来
     * （开机自启、自动恢复）。不兜住就是进程崩溃——而这条路径恰恰是「用户不在场」的，
     * 崩溃只会表现为「App 莫名其妙重启过」。所以这里 catch 之后把失败原因记进偏好，
     * 让设置页能显示出来（见 [ServicePreferences.lastFailure]）。
     *
     * @return true 表示真的进了前台服务态；false 表示失败，调用方**必须**就地返回，
     *   不能再走「启动成功」那段（否则 `onStarted()` 会把这里刚写的失败原因清掉，
     *   见 [LinkServiceController.onStarted] 与审查 P1-2）。
     */
    private fun raiseForeground(status: ServiceStatus): Boolean {
        val built = notice.build(status)
        if (built == null) {
            // 画不出通知 = 通知对用户不可用，起一个没有通知的前台服务必被回收。
            Log.w(TAG, "画不出常驻通知，放弃前台化")
            (application as? DshApplication)?.container?.onServiceStartFailed(
                IllegalStateException("通知不可用（未授权或已被关闭）"),
            )
            requestStop(tellUser = true)
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ServiceNotice.NOTICE_ID,
                    built,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ServiceNotice.NOTICE_ID,
                    built,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
                )
            } else {
                startForeground(ServiceNotice.NOTICE_ID, built)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 失败", e)
            (application as? DshApplication)?.container?.onServiceStartFailed(e)
            requestStop(tellUser = true)
            false
        }
    }

    /**
     * 补上「前台化」这一步，让随后的 `stopSelf()` 合法（第三轮审查 P2-4）。
     *
     * ## 这条约束从哪来
     * 调用方用的是 `startForegroundService()`。那是一个**契约**：服务必须在 5 秒内
     * 调用一次 `startForeground()`。若在没调过的情况下直接 `stopSelf()`，Android 12+
     * 会抛 `ForegroundServiceDidNotStartInTimeException`（8~11 是 `RemoteServiceException`），
     * 表现是 App 当场闪退。
     *
     * ## 哪几条路径会踩到
     * `onStartCommand` 里所有「还没进前台就要退出」的分支：
     * `container == null`、`!serviceWanted()`、[raiseForeground] 画不出通知 / 抛异常。
     * 这些分支当时都直接 `requestStop(...)` → `stopSelf()`，一次都没调过 `startForeground`。
     *
     * 其中 `!serviceWanted()` 最容易命中：`wanted()` 同时要求「开关开着」与「通知可用」，
     * 而用户在系统设置里关掉通知之后，这两者之间就存在时间窗；再叠加 hostKey 尚未发布
     * 的空窗口，那条分支几乎必然走到。
     *
     * ## 为什么可以「先提上来再立刻停」
     * 契约只要求「调过 `startForeground`」，不要求之后一直前台。所以这里用一条最小通知
     * 把契约兑掉，紧接着 [requestStop] 会撤掉通知并 `stopSelf()`——用户最多看到一个
     * 一闪而过的图标，比起闪退可以接受。
     *
     * ## 为什么失败只记日志、不抛
     * 这个函数是**兜底**，本身不该成为新的失败点。Android 12+ 在某些后台状态下
     * 调 `startForeground` 也会抛 `ForegroundServiceStartNotAllowedException`；
     * 那种情况下我们确实无能为力（已经尽力了），记日志后照常走停止流程。
     */
    private fun satisfyForegroundContract() {
        // 已经进过前台（或正在前台）：契约早已兑付，不必再插一条通知。
        if (started) return
        val built = notice.build(ServiceStatus(phase = ServicePhase.Connecting)) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ServiceNotice.NOTICE_ID,
                    built,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ServiceNotice.NOTICE_ID,
                    built,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
                )
            } else {
                startForeground(ServiceNotice.NOTICE_ID, built)
            }
        } catch (e: Exception) {
            // 兜底成功与否都不影响接下来的停止流程：能兑付最好，兑付不了也只能照停。
            Log.w(TAG, "补前台化失败（即将停止服务）", e)
        }
    }

    /**
     * 停掉自己。
     *
     * 顺序有讲究：**先撤通知再 stopSelf**。反过来的话系统会先解绑前台服务、
     * 通知条留到下一个 GC 周期——用户会看到一条点不动、也划不掉的残影。
     *
     * @param tellUser true 表示这是「异常停止」（启动失败 / 被系统判定无效），
     *   需要按用户开关决定要不要发一条「后台连接断了」。
     *   用户自己点「退出」时传 false——他刚亲手关掉，再弹一条等于问「你确定吗」。
     */
    private fun requestStop(tellUser: Boolean) {
        // 🔴 停止之前先把「前台化」契约兑掉（第三轮 P2-4）。见 [satisfyForegroundContract]：
        // 这几条提前退出的路径从未调过 startForeground，直接 stopSelf 会让系统抛
        // ForegroundServiceDidNotStartInTimeException，用户看到的是 App 闪退。
        satisfyForegroundContract()
        if (!started && !tellUser) {
            // 从没起来过（onStartCommand 里直接判停）：撤掉可能残留的通知就走。
            notice.cancel()
            stopSelf()
            return
        }
        val container = (application as? DshApplication)?.container
        notice.cancel()
        // UI 侧要回到「未运行」：状态流是唯一真相，主动推一次，
        // 免得等载波下一帧（可能永远不会来——连接还在，只是服务停了）。
        container?.onServiceStopped()
        if (tellUser) container?.onServiceUnexpectedStop()
        started = false
        stopSelf()
    }

    override fun onDestroy() {
        renderJob?.cancel()
        scope.cancel()
        notice.cancel()
        (application as? DshApplication)?.container?.onServiceStopped()
        started = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ForegroundLinkService"

        /** 启动服务。调用方必须先确认三个开关与通知可用（见 `planRecovery`）。 */
        fun start(context: Context) {
            val intent = Intent(context, ForegroundLinkService::class.java)
            context.startForegroundService(intent)
        }

        /** 停服务（用户点「退出」或在设置里关掉开关）。 */
        fun stop(context: Context) {
            val intent = Intent(context, ForegroundLinkService::class.java)
            // 🔴 用 `stopService` 而不是 `startService(ACTION_STOP)`（第五轮审查 P3）。
            //
            // `startService` 在服务**本来没跑**的时候也会把进程拉起来走一遍
            // `onCreate` → `onStartCommand`：于是先 `satisfyForegroundContract()`
            // 真的 `startForeground` 出一条「正在连接桌面端」，紧接着才
            // `cancel + stopSelf`。用户看到的是「刚关掉的功能先闪一条通知再消失」，
            // 与 Q4「退出即退干净」的观感相反。`stopService` 没这个副作用：
            // 服务没跑就什么都不发生，在跑就直接走 `onDestroy`。
            //
            // `onDestroy` 里已经有 `notice.cancel()` 与 `container.onServiceStopped()`，
            // 所以停止所需的善后一样都不会少。
            context.stopService(intent)
        }
    }
}
