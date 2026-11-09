package com.revxzxcx.remotewebapp.core.service

import android.content.Context
import android.util.Log
import com.revxzxcx.remotewebapp.core.notify.NoticeChannels
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台连接的编排层（R057）：把三个开关、五相状态、服务的启停串在一起。
 *
 * ## 为什么要有这一层
 * 服务（[ForegroundLinkService]）活在独立组件里，它拿不到「用户刚点了退出」这种
 * 只在进程内的意图，也不该自己去读偏好、判三个开关。容器（`AppContainer`）又太忙，
 * 不适合塞进「每秒刷一次时长」这种细节。所以中间加这一层：
 *
 * - 它持有 [ServiceStatusTracker]（状态的唯一真相）与 [ServiceExitMarker]（本次退出）。
 * - 它决定该不该起服务（[planRecovery] / [shouldStartOnBoot]），而非服务自己决定。
 * - 它是唯一同时知道「开关」「状态」「用户意图」的地方——三样凑齐才能回答
 *   「现在该不该有条常驻通知」。
 *
 * ## `hostKey` 为什么是一个取值的函数而不是一个字段
 * 开关按主机分开记（见 [ServicePreferences]），而主机随时可能被切换。如果把 hostKey
 * 存成构造参数，换主机之后这个对象还在用旧 key 读写偏好——表现是「新主机明明没开开关，
 * 却起了服务」。传一个 `() -> String` 让它在每次判断的**那一刻**去问当前主机，
 * 与 R054 的「簿与连接必须同一份快照」是同一个教训。
 *
 * @param hostKeyProvider 当前主机 id；没有主机时返回空串（会被映射成 `NO_HOST`）。
 */
class LinkServiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: ServicePreferences,
    private val notificationCenter: NotificationCenter,
    private val hostKeyProvider: () -> String,
    private val tracker: ServiceStatusTracker = ServiceStatusTracker(),
    private val exitMarker: ServiceExitMarker = ServiceExitMarker(),
) {

    /** 服务此刻的状态；常驻通知与设置页都读它。 */
    val status: StateFlow<ServiceStatus> = tracker.status

    /**
     * 偏好（三个开关 + 确认标记 + 失败原因）的第几版，**每次写盘都 +1**。
     *
     * 为什么需要它（第四轮审查 P2）：设置页原来靠一个只由用户回调递增的本地
     * `epoch` 决定「什么时候重读偏好」。但偏好**不只被用户改**——
     * [onStartFailed] 会在启动失败时自己把「保持后台连接」关掉并写一条失败原因。
     * 那是后台触发的，不走任何用户回调，于是设置页停在原地：磁盘上开关已关，
     * 界面还亮着「开」，连「上次启动失败：<原因>」那行都不出现——而它正是
     * 用户唯一能看到失败原因的地方。
     *
     * 反过来把写盘集中到 [writePrefs] 一个出口，任何一条路径改偏好都会让版本变，
     * 界面自然重读。用一个计数而不是把偏好塞进 `StateFlow<LinkServicePrefs>`，
     * 是为了让「读」仍然是同步的一次性快照（设置页只在版本变化时读一次）。
     */
    private val _prefsRevision = MutableStateFlow(0)
    val prefsRevision: StateFlow<Int> = _prefsRevision.asStateFlow()

    /** 偏好写入的唯一出口；每次调用都把版本推高，界面据此重读。 */
    private fun writePrefs(block: () -> Unit) {
        block()
        _prefsRevision.value = _prefsRevision.value + 1
    }

    /** 时长刷新（服务活着时每秒一次，停了就结束）。 */
    private var tickJob: Job? = null

    /**
     * 本次进程内是否已经为「当前这次死亡」发过「没在跑」的提醒。
     *
     * 见 [notifyServiceStoppedOnce]：`ensureRunning()` 现在每次回前台都会跑，
     * 不去重的话用户划掉提醒、切出去再切回来，同一条会被反复重新投递。
     */
    private var stoppedNotified = false

    /**
     * 当前主机 id，**冷启动窗口里退回到上一次见过的那台**（R057 审查 P1-1）。
     *
     * `hostKeyProvider` 的源头是 `AppContainer.linkStore.current().activeHost?.hostId`，
     * 而 `linkStore` 要等 `bootstrapOnce()` 在 IO 线程上读完凭证簿、建好连接、
     * `publish()` 之后才有值。开机自启正是这种情况：`Application.onCreate` 只是
     * 起了一个协程，`BootReceiver.onReceive` 紧接着就在主线程问「要不要起服务」。
     *
     * 那一刻若直接拿空串，`ServicePreferences.key()` 会退化成 `keep_alive_none`，
     * 三个 getter 全返回默认值（`foregroundEnabled = false`）→
     * [shouldStartOnBoot] 恒 false、[wanted] 恒 false，**开机自启与 START_STICKY
     * 恢复整条链路都是死的**，而且设置页开关亮着——又一次「显示与实际不符」。
     *
     * 所以空串时读 [ServicePreferences.lastHostKey]。它由
     * `AppContainer.publish` 每次发布连接时写回，凭证簿真空了才清，
     * 因此不可能停留在「一台已经不存在的机器」上。
     */
    private val hostKey: String
        get() = hostKeyProvider().ifBlank { prefs.lastHostKey() }

    // ---------------------------------------------------------------- 开关读取

    fun foregroundEnabled(): Boolean = prefs.foregroundEnabled(hostKey)

    fun autoRecover(): Boolean = prefs.autoRecover(hostKey)

    fun bootStart(): Boolean = prefs.bootStart(hostKey)

    fun lastFailure(): String = prefs.lastFailure(hostKey)

    fun confirmSeen(): Boolean = prefs.confirmSeen(hostKey)

    fun markConfirmSeen() {
        writePrefs { prefs.setConfirmSeen(hostKey, true) }
    }

    // ---------------------------------------------------------------- 开关写入

    /**
     * 打开/关掉「保持后台连接」。
     *
     * 两个方向都要清「本次已退出」：它是「这一次」的意图，而用户刚刚表达了新的意图
     * （不论开还是关）。留着的话，「关掉再打开」这个最直觉的恢复动作会失效——
     * 而它正是用户 Q4 之后唯一还剩下的手动恢复手段。
     */
    fun setForegroundEnabled(enabled: Boolean) {
        val key = hostKey
        writePrefs {
            prefs.setForegroundEnabled(key, enabled)
            if (enabled) {
                // 开了就把失败记录清掉：上一次失败是上一次的事，新的开启是一次新的尝试。
                prefs.setLastFailure(key, "")
                prefs.setLastFailureBlocksRetry(key, false)
            }
        }
        exitMarker.reset()
        if (enabled) {
            startService()
        } else {
            requestStop()
        }
    }

    fun setAutoRecover(enabled: Boolean) {
        writePrefs { prefs.setAutoRecover(hostKey, enabled) }
    }

    fun setBootStart(enabled: Boolean) {
        writePrefs { prefs.setBootStart(hostKey, enabled) }
    }

    // ---------------------------------------------------------------- 启停

    /**
     * **恢复的唯一入口。**
     *
     * 在四个时机调用：冷启动装好连接后、切换主机后、载波状态变化时、App 回到前台时。
     * 每次进来都重新判一遍「该不该跑」，所以不需要在别处散落「服务是不是被系统杀了」的
     * 判断——那种判断没有可靠的数据源（进程被杀时什么都收不到）。
     *
     * 这正是用户 Q2 要的语义：**自动恢复开着就静默拉起，关着才发通知**。
     * 两者是 `when` 的两个分支，不可能同时发生。
     */
    fun ensureRunning() {
        val key = hostKey
        if (!prefs.foregroundEnabled(key)) return
        // 🔴 按主机判「本次已退出」（第三轮 P2-5）：用户在这台机器上点过退出，
        // 之后桌面端下线、改地址、换到别的机器，都不该被这条布尔永久挡住。
        if (exitMarker.isExitedFor(key)) return
        // 🔴 必须问「服务是否活着」，不能问 `status.phase != Stopped`。
        // 两者现在等价，但**语义不同**：`phase` 是给用户看的画法，`serviceAlive` 是事实。
        // 第一版这里用 `status.running`，而那时相位完全由载波推导——`onCarrier` 先更新
        // 相位再调本函数，于是这一行永远为真、下面整段是死代码（R057 子代理审查 P1-1）。
        if (tracker.serviceAlive) return
        // 上一次启动失败过，且**这类失败重试也没用**：不再自动重试，等用户明确动一次开关。
        // 否则每一帧载波都会重新尝试一次，形成「失败→重试→失败」的无声循环，
        // 而用户只会看到电量和日志在涨。开关本身在 `setForegroundEnabled` 里会清掉这条记录。
        //
        // 🔴 只挡「用户改不了」的那一类（R057 审查 P2-2）。通知未授权引起的失败是
        // **用户能修的**，早先也一并挡住，于是「去系统里授权 → 回来 → 服务永远起不来」，
        // 只能靠关掉开关再打开这种没人想得到的动作才恢复。判据见
        // `ServicePreferences.lastFailureBlocksRetry`。
        if (prefs.lastFailureBlocksRetry(key)) return
        when (
            planRecovery(
                foregroundEnabled = true,
                autoRecover = prefs.autoRecover(key),
                userExited = exitMarker.isExitedFor(key),
                noticeUsable = noticeUsable(),
            )
        ) {
            RecoveryAction.RestartSilently -> startService()
            // 🔴 走 SERVICE 频道、且内部先查频道没被关（第三轮 P2-2）。
            // 原来借的是 `postLink` —— 那一条固定落在「任务结束」频道里，于是用户
            // 关掉「任务结束」（多半只是不想每跑一轮响一声）就顺手把「后台连接掉了」
            // 一起静音了，而这条恰恰是 `NotifyUser` 唯一的可见产物。
            RecoveryAction.NotifyUser -> notifyServiceStoppedOnce()

            RecoveryAction.DoNothing -> Unit
        }
    }

    /**
     * 「自动恢复关着、服务又没在跑」时发一次提醒，**同一次死亡只发一条**。
     *
     * ## 为什么需要去重（第六轮审查 P2）
     * 第六轮把 [ensureRunning] 接进了「每次回前台」（`AppContainer.onAppForeground`）
     * 与「拿到通知权限」。调用频率从「连接状态变化时」变成「每次切回 App」，
     * 而这个分支恰好就是在「服务没在跑」时才会走到的——于是用户划掉那条提醒、
     * 切出去再切回来，它又被重新投递一次，看起来像功能在抽风。
     *
     * `postServiceStopped` 里的 `setOnlyAlertOnce(true)` 只能压掉声音与弹出，
     * **挡不住划掉之后的重新出现**：`notify()` 是「按 id 覆盖或新建」，用户划掉
     * 就等于把那条记录删了，下一次 `notify` 自然会建一条新的。
     *
     * ## 为什么记在这里而不是偏好里
     * 这是「本次进程内已经提醒过这次死亡」的账，重启进程后应当重新提醒一次
     * （用户可能整晚没看手机）。落盘会让一次提醒永久生效，反而漏掉后面的死亡。
     * 复位点是 [onStarted]（服务活了，下一次死是新的一次）与 [requestStop]
     * （用户主动收工，不该再留提示）。
     */
    private fun notifyServiceStoppedOnce() {
        if (stoppedNotified) return
        stoppedNotified = true
        notificationCenter.postServiceStopped(
            title = ServiceText.stoppedTitle(),
            text = ServiceText.stoppedText(),
        )
    }

    /**
     * 用户在通知栏点了「退出」。
     *
     * 语义是「这一次不要了」：**不改开关**、不发任何通知、不再自动恢复。
     * 三条路能重新起来：关掉再打开开关、退出 App 再打开、手机重启（若开了开机自启）。
     */
    fun exitFromNotification() {
        // 记住是**哪台主机**上点的（第三轮 P2-5）：换主机后这条标记不该再挡着。
        exitMarker.markExited(hostKey)
        // 🔴 只有这条路径撤「连不上桌面端了」那条卡片（第五轮审查 P3）。
        //
        // 它由 `LinkNoticeGate` 维护，撤掉之后 `warnedPhase` 仍占着位，直到真正
        // 重连成功才会再发。用户点「退出」是明确表示「这次不要后台连接了」，
        // 连带撤掉那条过期的断连提示是合理的。但「在设置里关掉开关」不是这个意思：
        // 连接本身可能仍然是断的，用户还在用 App，那条提醒对他依然有效。
        // 两个动作都走 [requestStop]，所以不能把它放在那里。
        notificationCenter.cancelLink()
        requestStop()
    }

    /** 停服务并让状态回到「未运行」。 */
    fun requestStop() {
        tracker.onStopped()
        stopTicking()
        // `cancelServiceStopped()` 撤的是「自动恢复关着」时那条一次性恢复提示
        // （`SERVICE_STOPPED_ID`）。没有这一句它**永远撤不掉**：全仓库只有这里和
        // `exitLinkService` 这两条「用户主动收工」的路径该撤它，别处都不该动。
        //
        // ⚠️ 这里**不撤** `LINK_ID`（「连不上桌面端了」）：那条卡片的生命周期属于连接层，
        // 不由「要不要跑前台服务」决定。撤它的地方是 [exitFromNotification]——第五轮审查 P3。
        notificationCenter.cancelServiceStopped()
        // 用户主动收工，下一次该不该提醒是「下一次死亡」的事，账要清掉。
        stoppedNotified = false
        try {
            ForegroundLinkService.stop(context)
        } catch (e: Exception) {
            // 停止走 `stopService`，正常情况下不会抛；后台限制等极端情况下可能失败。
            // 这次调用的目的已经达到了（状态已回未运行），不要因为清理失败而崩。
            Log.w(TAG, "停止服务失败", e)
        }
    }

    /** 开机要不要起（由 [BootReceiver] 调）；返回是否真的尝试了启动。 */
    fun startOnBoot(): Boolean {
        val should = shouldStartOnBoot(
            foregroundEnabled = prefs.foregroundEnabled(hostKey),
            bootStart = prefs.bootStart(hostKey),
            noticeUsable = noticeUsable(),
        )
        if (should) startService()
        return should
    }

    /**
     * 服务此刻该不该存在（由 [ForegroundLinkService.onStartCommand] 每次校验）。
     *
     * 判据只有两个，且都不看「本次已退出」（理由见服务的 KDoc）：
     * - 开关得是开的；
     * - 通知得能发出去——前台服务没有通知会被系统判为无效并在 5 秒后 ANR。
     */
    fun wanted(): Boolean = prefs.foregroundEnabled(hostKey) && noticeUsable()

    // ---------------------------------------------------------------- 服务回调

    /**
     * 服务起来了。
     *
     * 顺序要紧：先清「本次已退出」（服务真的起来了，那条路径没被挡住），
     * **再**把状态置成「连接中」。反过来的话，`markRunning` 触发的任何重入
     * 都会看到 `isExited = true` 而把刚起来的服务又停掉。
     */
    fun onStarted() {
        exitMarker.markRunning()
        // 服务真的活过来了：下一次死是新的一次，提醒账要重置，
        // 否则「死过一次 → 提醒过 → 又活 → 又死」的第二次会拿不到提醒。
        stoppedNotified = false
        if (prefs.lastFailure(hostKey).isNotEmpty()) {
            writePrefs {
                prefs.setLastFailure(hostKey, "")
                prefs.setLastFailureBlocksRetry(hostKey, false)
            }
        }
        tracker.onStarting()
        startTicking()
    }

    /** 服务自己报了「我停了」（`onDestroy`）。不触发恢复判定，由 [ensureRunning] 统一负责。 */
    fun onStopped() {
        tracker.onStopped()
        stopTicking()
    }

    /**
     * 启动失败（`ForegroundServiceStartNotAllowedException` / `SecurityException`）。
     *
     * ## 什么时候处理开关和重试（R057 审查 P1-5 + 真机验收缺陷）
     *
     * 开关代表**用户意图**；这个函数处理的是**执行结果**。用户没变主意，就不该替他关。
     * 无条件的后果很具体：用户刚装上 App 还没给通知权限，他一打开「保持后台连接」，
     * `startService` 立刻失败 → 开关自己弹回去 → 他看到的是「按下去又弹回来」，
     * 而没有任何一行字说明为什么。
     *
     * 分类规则全在 [startFailurePolicy] 里（纯函数，有独立测试），这里只负责取输入：
     * - **时机性**（系统当下不许起前台服务）：不关、不挡。真机踩过——开机时撞一次
     *   就把开关永久关掉，之后每次重启都因为开关是 false 静默什么都不做。
     * - **通知不可用**：不关、不挡，用户去授权就能好。
     * - **其它异常**（类型没声明、ROM 拒绝）：关开关 + 挡重试，别让亮着的开关骗人。
     *
     * 三种都记 `lastFailure`——它是用户唯一能看到原因的地方。
     */
    fun onStartFailed(cause: Throwable) {
        val key = hostKey
        val reason = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        Log.w(TAG, "前台服务启动失败：$reason")
        val policy = startFailurePolicy(
            temporary = isTemporaryStartFailure(cause),
            noticeUsable = noticeUsable(),
        )
        // 🔴 这一整段必须走 `writePrefs`（第四轮审查 P2）：它关掉开关、写失败原因，
        // 却完全由后台触发，不走任何用户回调。原来设置页靠一个只由用户回调递增的
        // 本地 epoch 决定何时重读偏好，于是这条路径改完盘界面毫无反应——开关还亮着
        // 「开」，失败原因那行也不出现，用户只看到「开着但没在跑」。
        writePrefs {
            prefs.setLastFailure(key, reason)
            prefs.setLastFailureBlocksRetry(key, policy.blocksRetry)
            if (policy.turnsSwitchOff) {
                prefs.setForegroundEnabled(key, false)
            }
        }
        tracker.onStopped()
        stopTicking()
    }

    /**
     * 载波状态变了。
     *
     * 由 `AppContainer.watchCarrier` 转发。除了更新状态，还顺手触发一次 [ensureRunning]：
     * 用户在设置里打开开关那一刻可能还没有连接（服务起来也是空的），等连接真正建立后
     * 才该把它拉起来——这里就是那个「连接建立」的时刻。
     */
    fun onCarrier(carrier: CarrierState?) {
        tracker.onCarrier(carrier)
        if (carrier == null || carrier == CarrierState.Idle) return
        ensureRunning()
    }

    /**
     * 连接层报告「**一台主机都没有了**」（退配对 / 删掉最后一台）。
     *
     * 必须与 `onCarrier(Idle)` 分开：`Idle` 在换主机时也会出现，那时服务要继续活着等
     * 新连接（R057 审查 P2-1）。而这里是真的没得连了——留着服务只会挂一条
     * 「正在连接桌面端」的僵尸通知，用户会一直等一个永远不会来的连接。
     *
     * 只停服务，**不动开关**：用户并没有关掉「保持后台连接」，配对回来之后
     * `onCarrier(Connecting)` → [ensureRunning] 会把它重新拉起来。
     */
    fun onNoConnection() {
        tracker.onNoConnection()
        stopTicking()
        try {
            ForegroundLinkService.stop(context)
        } catch (e: Exception) {
            Log.w(TAG, "没有主机时停止服务失败", e)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun startService() {
        if (!noticeUsable()) {
            // 前台服务没有通知会被系统判定无效并在 5 秒后 ANR。
            // 与其起一个注定被杀的，不如把原因如实写出来。
            onStartFailed(IllegalStateException("通知不可用（未授权或已被关闭）"))
            return
        }
        try {
            ForegroundLinkService.start(context)
        } catch (e: Exception) {
            onStartFailed(e)
        }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                if (!tracker.status.value.running) break
                tracker.tick()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    /**
     * 通知此刻能不能发出去。
     *
     * 前台服务**必须**挂一条通知，发不出去会被系统判为无效服务，5 秒后 ANR。
     * 所以这是所有启动路径的前置条件，而不是「发通知时再判一次」。
     *
     * 🔴 **还要单独查「后台连接」这个频道**（第三轮 P2-1）。`canPost()` 的 KDoc 自己
     * 写明「用户单独关掉某个频道时仍返回 true」——它只查总开关与运行时权限。
     * 而常驻通知走的是 [NoticeChannels.SERVICE] 频道，用户把它关掉之后：开关还亮着、
     * 服务照常前台化并常驻耗电，可那条状态条被系统静默丢弃，用户什么都看不到。
     * 频道说明里写着「关掉会让后台连接无法维持」，这一段就是把那句话兑现。
     */
    private fun noticeUsable(): Boolean =
        notificationCenter.canPost() && notificationCenter.isChannelEnabled(NoticeChannels.SERVICE)

    private companion object {
        const val TAG = "LinkServiceController"

        /** 时长刷新间隔。1 秒是通知栏能分辨的最小粒度，再快只是浪费电。 */
        const val TICK_MILLIS = 1_000L
    }
}
