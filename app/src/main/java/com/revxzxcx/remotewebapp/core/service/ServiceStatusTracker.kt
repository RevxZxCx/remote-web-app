package com.revxzxcx.remotewebapp.core.service

import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 把「服务是否活着」与 `CarrierState` 合成 [ServiceStatus]（R057）。
 *
 * ## 为什么单独一层，而不是在服务里直接看 CarrierState
 * 服务只需要「画什么」，不该知道协议层的五种载波状态怎么映射。
 * 更重要的是**两块原生时钟**在这里汇合：`CarrierState.Connected.sinceMillis` 与
 * `Reconnecting.retryAtMillis` 都是墙钟时刻，而通知里的时长要用 `now - since`。
 * 把这段算法抽成纯函数，它就能在 JVM 单测里逐条断言，不用建 Service、不用等真时钟。
 *
 * ## 🔴 为什么「服务是否活着」必须是独立的一维（R057 审查 P1）
 *
 * 第一版把相位**完全**由载波推导：`onCarrier` 一帧就能把相位写成 `Connected`。
 * 这在「服务已经死了」时是灾难——进程被 LMK 杀掉时 `onDestroy` 不保证送达，
 * 服务没了、常驻通知也没了，但只要那条 WebSocket 还连着（`AppContainer` 的连接
 * 与服务是两回事），下一帧 `Connected` 就会把状态写回「运行中 · 已连上桌面端」，
 * `tick()` 还会继续每秒推进时长。用户看到的是**一整套实时正常**的假象，
 * 而实际上一条通知也发不出去。这正是用户第一条要求「状态如实写」最要命的违反方式。
 *
 * 所以现在有两维：`serviceRunning`（由服务自己的 `onStarting`/`onStopped` 写）
 * 与载波相位。**载波只能在服务活着时影响相位**；服务不活着，载波来得再多，
 * 相位也钉在 [ServicePhase.Stopped]。
 *
 * ## 为什么要记住「上一次是什么相」
 * `CarrierState.Reconnecting` 只告诉你「正在重试」，它自己会随着每次重试被重新发射
 * （`attempt` 递增），但它**不带「从什么时候开始断的」**——那是重试第一次落下来时才算起
 * 的时刻。所以这里必须自己记 `downSince`，否则每来一帧新的 `Reconnecting`，
 * 「已断开」的时长都会从 0 重新数起，用户会看到它永远显示「已断开 2 秒」。
 */
class ServiceStatusTracker(
    /** 取当前时刻（注入以便单测）。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    /** 服务是否活着。**相位的第一道闸门**，见类 KDoc 的「🔴」一节。 */
    private var serviceRunning: Boolean = false

    /**
     * 服务此刻是否活着。
     *
     * 与 `status.value.running` 是同一件事（后者是 `phase != Stopped`），但语义更直白：
     * 恢复逻辑问的是「要不要起服务」，而不是「要画哪一相」。分开一个入口是为了让
     * 调用点读起来不会被相位干扰。
     */
    val serviceAlive: Boolean get() = serviceRunning

    /** 进入 [ServicePhase.Connected] 的时刻；`null` 表示没连着。 */
    private var connectedSince: Long? = null

    /** 第一次发现断开的时刻；`null` 表示没断。 */
    private var downSince: Long? = null

    /**
     * 最后一次收到的载波帧，**不管服务当时活着没有**（R057 审查第三轮 P1-2）。
     *
     * ## 为什么必须缓存
     * `AppContainer.watchCarrier` 订的是 `mux.stats.map { it.carrier }.distinctUntilChanged()`，
     * 而 `CarrierState.Connected` 只在 **socket 建起来那一刻**产生一次，之后不再重发
     * （`RemoteStreamMux` 里只有那一处 `_stats.update { it.copy(carrier = Connected(...)) }`）。
     *
     * 于是有这么一条再普通不过的路径：用户已经连着桌面端 → 进设置页打开「保持后台连接」
     * → 服务这时才起。等服务起来，`Connected` 那一帧早就过去了；`distinctUntilChanged`
     * 又不会因为订阅方换了就重放。结果常驻通知**永久**停在「正在连接桌面端」，
     * `connectedForMillis` 恒为 0，直到下一次真实断线重连才恢复——正好违反用户
     * 「状态要如实写」「要显示已连接时长」两条要求。
     *
     * 更早的帧连缓存都进不来：`onCarrier` 首行 `if (!serviceRunning) return` 会全部丢掉。
     * 所以缓存必须写在闸门**之前**。
     *
     * ## 为什么缓存的是整个 state 而不是只缓存相位
     * `Connected.sinceMillis` 是「连上的墙钟时刻」，重放时必须原样带回去，否则时长
     * 会从服务启动那一刻开始数，用户看到「已连接 3 秒」而实际已连了两小时。
     */
    private var lastCarrier: CarrierState? = null

    /**
     * 载波状态变了。
     *
     * 传 `null` 表示「不知道」（还没建连接 / 已丢弃），按 [ServicePhase.Stopped] 处理：
     * 服务没有连接的支撑就该退出，而不是挂一条「连接中」的僵尸通知。
     *
     * **服务不活着时直接忽略**：此时载波即使说 `Connected`，也不代表用户还能收到通知，
     * 写进相位就是撒谎。
     */
    fun onCarrier(state: CarrierState?) {
        // ⚠️ 缓存必须在闸门之前：服务没起来时那些帧正是「服务起来后要有话可说」的依据。
        lastCarrier = state
        if (!serviceRunning) return
        val at = now()
        when (state) {
            is CarrierState.Connected -> {
                // 已经在连着时不重置起点：同一相可能被重复发射（stats 每次计数更新都会走一遍
                // distinctUntilChanged，但「重连成功后又成功」这种路径仍可能到达这里）。
                if (connectedSince == null) connectedSince = state.sinceMillis
                downSince = null
                _status.value = ServiceStatus(
                    phase = ServicePhase.Connected,
                    connectedForMillis = (at - (connectedSince ?: at)).coerceAtLeast(0L),
                )
            }

            is CarrierState.Connecting -> {
                // ⚠️ **不清 `downSince`**（R057 审查 P3）。一次断线里可能先 `Reconnecting`
                // 再 `Connecting`（重建 socket、重新握手），此时把起点清掉会让「已断开 X」
                // 从 0 重数，用户看到时间倒退只能理解成「刚刚才断的」。
                _status.value = ServiceStatus(phase = ServicePhase.Connecting)
            }

            is CarrierState.Reconnecting -> {
                val since = downSince ?: at.also { downSince = it }
                connectedSince = null
                _status.value = ServiceStatus(
                    phase = ServicePhase.Reconnecting,
                    downForMillis = (at - since).coerceAtLeast(0L),
                    attempt = state.attempt.toInt(),
                )
            }

            is CarrierState.Unpaired -> {
                // 终态：连不上也不会自己好。**不能**归进 Reconnecting——那样通知栏会一直说
                // 「正在重连…（第 N 次）」，而它永远连不上。
                connectedSince = null
                downSince = null
                _status.value = ServiceStatus(
                    phase = ServicePhase.Unpaired,
                    unpairedReason = state.reason,
                )
            }

            CarrierState.Idle, null -> {
                // 🔴 **不能映射成 Stopped**（R057 审查 P2-1）。
                //
                // `Idle` 的语义是「socket 还没建起来」，它出现在两个时刻：刚构造出的
                // `RemoteStreamMux` 的第一帧，以及 `reset()`。而「服务没在跑」是另一回事。
                // 早先写成 `ServiceStatus(phase = Stopped)`，后果是换主机那一下
                // （`installRebuilt` 建了新连接、首帧就是 Idle）相位瞬间变 Stopped，
                // `ForegroundLinkService.render` 一见 Stopped 就 `stopSelf()`：
                // 常驻通知闪一下消失、服务被杀，下一帧 Connecting 才被 `ensureRunning`
                // 拉回来；若「自动恢复」关着，用户还会额外收到一条假的「后台连接断了」。
                //
                // 现在是「服务活着、socket 还没起来」= [ServicePhase.Connecting]。
                // 真的没有主机可连（退配对）不走这里，由
                // `LinkServiceController.onNoConnection()` 明确停服务。
                connectedSince = null
                downSince = null
                _status.value = ServiceStatus(phase = ServicePhase.Connecting)
            }
        }
    }

    /**
     * 刷新时长（当前相没变，只是时间过去了）。
     *
     * 服务每秒调一次：通知里的「已连接 2 小时 14 分」得往前走，
     * 否则用户看到的是一个停住的数字，会以为服务卡死了。
     *
     * ## 为什么这里**不需要**再判一次「服务是否活着」
     * 第一版有 `if (!serviceRunning) return`，R057 变异验证（M12）证明它不是冗余防御
     * 而是**死代码**：本类维持着一条不变量——`serviceRunning == false` 时相位必然是
     * [ServicePhase.Stopped]（只有 [onStopped]/[onNoConnection] 会把 `serviceRunning`
     * 置回 false，它们同时把相位写成 Stopped），而 Stopped 落到下面的 `else -> return`。
     * 换句话说那行 `return` 永远走不到，删掉它不影响任何行为。
     *
     * 它想防的场景（服务被 LMK 静默杀死、`tickJob` 还在跑）它也防不住：`onDestroy`
     * 没送达时 `serviceRunning` 仍是 true，守卫照样放行。那种情形靠
     * [ForegroundLinkService] 自己的生命周期兜，不靠这里。
     */
    fun tick() {
        val at = now()
        val current = _status.value
        _status.value = when (current.phase) {
            ServicePhase.Connected -> {
                val since = connectedSince ?: return
                current.copy(connectedForMillis = (at - since).coerceAtLeast(0L))
            }

            ServicePhase.Reconnecting -> {
                val since = downSince ?: return
                current.copy(downForMillis = (at - since).coerceAtLeast(0L))
            }

            // 其余三相没有会走的数字（Connecting 不显示时长，Unpaired/Stopped 同理）。
            else -> return
        }
    }

    /**
     * 服务刚起来、还没拿到载波状态。
     *
     * 为什么单独一个入口而不是复用 `onCarrier(Connecting)`：那条路径需要有一个
     * `CarrierState` 实例，而服务启动的那一刻连接层可能还没发出任何帧（而且现在
     * 服务没起来时 `onCarrier` 会被直接忽略，这条路径根本走不通）。
     * 若不在这里把相位置成 [ServicePhase.Connecting]，通知会在服务已起来、
     * 载波第一帧到达之前继续显示上一次的 `Stopped`——而 `Stopped` 的语义是
     * 「服务没在跑」，`ForegroundLinkService.render` 看到它会立刻 `stopSelf()`，
     * **刚起来的服务会把自己关掉**。
     */
    fun onStarting() {
        serviceRunning = true
        connectedSince = null
        downSince = null
        _status.value = ServiceStatus(phase = ServicePhase.Connecting)
        // 把服务没起来期间错过的最后一帧补上（第三轮 P1-2）。没有它，连接早就建好的
        // 情况下通知会永久停在「正在连接桌面端」。缓存为 null 时什么都不做——保持 Connecting。
        lastCarrier?.let { onCarrier(it) }
    }

    /**
     * 服务主动停了（用户点退出 / 启动失败）。
     *
     * 与 [onCarrier] 传 Idle 的区别：那条来自连接层，可能因为换主机而先变 Idle、
     * 随后新的连接又变 Connected。这里是被服务自己推的，意味着「这次真的不跑了」。
     */
    fun onStopped() {
        serviceRunning = false
        connectedSince = null
        downSince = null
        _status.value = ServiceStatus(phase = ServicePhase.Stopped)
    }

    /**
     * 连接层报告「**没有连接了**」（退配对 / 删掉最后一台主机）。
     *
     * 与 [onCarrier] 传 `Idle` 的区别，正是 R057 审查 P2-1 的核心：
     * - `Idle` 是「这一条 mux 的 socket 还没建起来」，换主机时也会出现，
     *   服务应当继续活着等新连接（见 [onCarrier] 的 Idle 分支）；
     * - 这里是「一台主机都没有了」，服务没有任何理由继续挂一条「正在连接」的僵尸通知。
     *
     * 早先两种情况都走 `onCarrier(Idle)` → `Stopped`，于是换主机时服务被误杀；
     * 反过来若把 `Idle` 一律当 `Connecting`（P2-1 的修法），退配对时又会留下僵尸通知。
     * 两种情形必须各有入口。
     */
    fun onNoConnection() {
        onStopped()
    }
}
