package com.revxzxcx.remotewebapp.core.service

import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务相位的合成（R057 用户要求第 1 条「状态如实写」）。
 *
 * ## 这个文件存在的全部理由
 * 子代理审查 P1 抓到的两条最严重缺陷都在这一层，而且**两条都是「屏幕上一套正常，
 * 实际上一条通知也发不出去」**：
 * 1. 相位完全由载波推导 → 服务被 LMK 杀掉后，只要那条 WebSocket 还连着
 *    （`AppContainer` 的连接与服务是两回事），下一帧 `Connected` 就把状态写回
 *    「运行中 · 已连上桌面端」，`tick()` 还继续每秒推进时长。
 * 2. 时长会从 0 重数 → 用户看到「已断开 2 秒」永远不动，只能理解成刚断的。
 *
 * 这两条在真机上**无法稳定复现**（要靠 LMK 恰好在某刻杀进程），所以只能靠这里守。
 */
class ServiceStatusTrackerTest {

    /** 可拨的假时钟：`at(1000)` 之后每次 `now()` 都读同一个值，方便断时长。 */
    private fun clock(start: Long = 1_000L): () -> Long {
        var current = start
        return { current }
    }

    @Test
    fun `刚建出来是停止 服务没跑就没有常驻通知`() {
        val tracker = ServiceStatusTracker(now = clock())
        assertEquals(ServicePhase.Stopped, tracker.status.value.phase)
        assertFalse(tracker.status.value.running)
        assertFalse("serviceAlive 与 running 必须是同一件事", tracker.serviceAlive)
    }

    @Test
    fun `服务没起来时载波说什么都不算 这是 P1 的核心`() {
        val tracker = ServiceStatusTracker(now = clock())
        // 服务没起来（没人调 onStarting），此时连接层照常在跑、照常发 Connected。
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 0L))
        assertEquals(
            "服务没在跑却把相位写成已连接，用户会看到一整套实时正常的假象",
            ServicePhase.Stopped,
            tracker.status.value.phase,
        )
        assertFalse(tracker.status.value.running)
    }

    @Test
    fun `服务没有报死后 载波也不能把它复活`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onStopped()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 0L))
        assertEquals(ServicePhase.Stopped, tracker.status.value.phase)
    }

    @Test
    fun `服务起来先置连接中 否则刚起来的服务会把自己关掉`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        assertTrue(tracker.serviceAlive)
        assertTrue(tracker.status.value.running)
    }

    @Test
    fun `连上之后时长按 sinceMillis 算 不是从零开始`() {
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 500L))
        now = 3_500L
        tracker.tick()
        // 3_500 - 500 = 3_000，而不是 3_500 - 1_000（服务启动时刻）。
        assertEquals(3_000L, tracker.status.value.connectedForMillis)
    }

    @Test
    fun `连着时再收一帧 Connected 不重置起点`() {
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 500L))
        now = 2_000L
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 500L))
        assertEquals(
            "重复帧不该让已连接时长倒退",
            1_500L,
            tracker.status.value.connectedForMillis,
        )
    }

    @Test
    fun `连着时来一帧起点更晚的 Connected 也不能把时长拉回去`() {
        // R057 变异验证 M14：把 `if (connectedSince == null)` 去掉后上面那条用例照样绿，
        // 因为两帧传的是同一个 sinceMillis，锚点写成谁都一样。这一条用**不同的**起点，
        // 才真正守住「已在连着时不再改锚点」。
        //
        // 真机上这意味着：底层 socket 内部重握手（没经过 Reconnecting 相）又发来一帧
        // Connected，锚点若跟着走，用户会看到「已连接 2 小时」突然变成「已连接 3 秒」。
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 500L))
        now = 7_200_500L
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 7_200_000L))
        assertEquals(
            "已在连着时锚点不能改：改了用户就会看到时长突然倒退",
            7_200_000L,
            tracker.status.value.connectedForMillis,
        )
    }

    @Test
    fun `断线时长从第一次发现断开算起 不跟着每帧重试重数`() {
        var now = 10_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 9_000L))
        now = 11_000L
        tracker.onCarrier(CarrierState.Reconnecting(attempt = 1, retryAtMillis = 0L, reason = "reset"))
        assertEquals(0L, tracker.status.value.downForMillis)
        // 第二次重试落在 5 秒后：时长必须接着数，不是从 0 重来。
        now = 16_000L
        tracker.onCarrier(CarrierState.Reconnecting(attempt = 2, retryAtMillis = 0L, reason = "reset"))
        assertEquals(5_000L, tracker.status.value.downForMillis)
        assertEquals(2, tracker.status.value.attempt)
    }

    @Test
    fun `重连期间走一次 Connecting 也不清零断开时长`() {
        var now = 10_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        now = 11_000L
        tracker.onCarrier(CarrierState.Reconnecting(attempt = 1, retryAtMillis = 0L, reason = "reset"))
        now = 13_000L
        // 重建 socket 时会先走一帧 Connecting。
        tracker.onCarrier(CarrierState.Connecting)
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        now = 15_000L
        tracker.onCarrier(CarrierState.Reconnecting(attempt = 2, retryAtMillis = 0L, reason = "reset"))
        assertEquals(
            "断开起点被 Connecting 清掉的话，用户看到时间倒退只能理解成刚刚才断",
            4_000L,
            tracker.status.value.downForMillis,
        )
    }

    @Test
    fun `配对失效是终态 不能归进正在重连`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Unpaired("凭证已被撤销"))
        assertEquals(ServicePhase.Unpaired, tracker.status.value.phase)
        assertEquals("凭证已被撤销", tracker.status.value.unpairedReason)
        // 仍然算在跑：服务不能自己停，否则用户连「去重新配对」的提示都没有了。
        assertTrue(tracker.status.value.running)
    }

    @Test
    fun `载波变 Idle 只是连接中 不能当成服务已停`() {
        // 子代理审查 P2 抓到的：换主机时新 mux 的第一帧就是 Idle（socket 还没建起来），
        // 旧语义把它映射成 Stopped，render() 一见 Stopped 就 stopSelf —— 常驻通知闪一下消失、
        // 服务被杀，下一帧 Connecting 才被 ensureRunning 拉回来。Idle ≠ 服务停了。
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Idle)
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        assertTrue("Idle 期间服务还活着，不能被 stopSelf 掉", tracker.serviceAlive)
    }

    @Test
    fun `载波变 null 也还是连接中`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onCarrier(null)
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        assertTrue(tracker.serviceAlive)
    }

    @Test
    fun `一台主机都没有才真的停 这是 onNoConnection 与 Idle 的分界`() {
        // AppContainer 只在连接对象为 null（凭证簿空了 / 撤掉最后一台）时调 onNoConnection，
        // 那时才是「服务没意义了」，要真停。
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Idle)
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        tracker.onNoConnection()
        assertEquals(ServicePhase.Stopped, tracker.status.value.phase)
        assertFalse(tracker.serviceAlive)
        assertFalse(tracker.status.value.running)
    }

    @Test
    fun `服务没活着时 tick 不加时长`() {
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 1_000L))
        now = 61_000L
        tracker.tick()
        assertEquals(60_000L, tracker.status.value.connectedForMillis)
        // 服务死了之后 tick 还在跑（tickJob 未必来得及取消）——数字不能再涨。
        tracker.onStopped()
        now = 121_000L
        tracker.tick()
        assertEquals(ServicePhase.Stopped, tracker.status.value.phase)
        assertEquals(0L, tracker.status.value.connectedForMillis)
    }

    @Test
    fun `连接中与配对失效没有会走的数字 tick 不该编一个出来`() {
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        now = 60_000L
        tracker.tick()
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        assertEquals(0L, tracker.status.value.connectedForMillis)
    }

    @Test
    fun `时钟回拨时长不变成负数`() {
        var now = 10_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 9_000L))
        now = 5_000L
        tracker.tick()
        assertEquals(0L, tracker.status.value.connectedForMillis)
    }

    @Test
    fun `服务起来过再停掉 再起来能重新连上`() {
        // 守的是「退出只影响这一次」：onStopped 不该留下任何再也解不开的状态。
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        tracker.onStopped()
        tracker.onStarting()
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 0L))
        assertEquals(ServicePhase.Connected, tracker.status.value.phase)
        assertTrue(tracker.serviceAlive)
    }

    /**
     * 第三轮审查 P1-2：**先连着、后开开关**这条路径。
     *
     * `CarrierState.Connected` 只在 socket 建好那一刻发一次，之后
     * `watchCarrier` 的 `distinctUntilChanged()` 不会再发。用户先聊天（连接已就绪）
     * 再进设置页打开「保持后台连接」，服务起来后如果只等新帧，就永远等不到——
     * 通知卡在「正在连接桌面端」，已连接时长恒为 0，违反「状态如实写」。
     */
    @Test
    fun `服务起来时把之前收到的已连接补上 否则开关打开后永远卡在连接中`() {
        var now = 1_000L
        val tracker = ServiceStatusTracker(now = { now })
        // 服务还没起，但连接早就建好了（载波唯一一次 Connected 就发生在这个窗口里）。
        tracker.onCarrier(CarrierState.Connected(sinceMillis = 500L))
        assertEquals(ServicePhase.Stopped, tracker.status.value.phase)

        tracker.onStarting()

        assertEquals(
            "服务起来后必须立刻补上那帧 Connected，否则常驻通知永远写「正在连接桌面端」",
            ServicePhase.Connected,
            tracker.status.value.phase,
        )
        now = 2_500L
        tracker.tick()
        assertEquals(
            "时长要按 socket 建好的时刻算，不能从服务启动那一刻重新计时",
            2_000L,
            tracker.status.value.connectedForMillis,
        )
    }

    /**
     * 缓存的是整帧载波，不只是相位：重连中补回来必须带上「第几次」。
     *
     * 断线时长补不回来，这是**如实**的做法而不是缺陷：[CarrierState.Reconnecting]
     * 里只有 `retryAtMillis`（下一次重试的**未来**时刻），没有「什么时候断的」，
     * 服务在自己的窗口里第一次看到这帧时只能从那一刻起算。硬凑一个数会让用户
     * 看到「已断开 0 秒」之外更假的数字。
     */
    @Test
    fun `服务起来时重连中也要补回来 带上第几次`() {
        var now = 10_000L
        val tracker = ServiceStatusTracker(now = { now })
        tracker.onCarrier(
            CarrierState.Reconnecting(attempt = 3, retryAtMillis = 12_000L, reason = "socket closed"),
        )

        tracker.onStarting()

        assertEquals(ServicePhase.Reconnecting, tracker.status.value.phase)
        assertEquals(3, tracker.status.value.attempt)
        now = 11_500L
        tracker.tick()
        assertEquals(1_500L, tracker.status.value.downForMillis)
    }

    /** 服务起来时上一帧就是「还没连上」，补回来的只能是连接中，不能凭空造一个已连接。 */
    @Test
    fun `服务起来时上一帧是空闲 补回来还是连接中`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onCarrier(CarrierState.Idle)

        tracker.onStarting()

        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
    }

    /** 一帧载波都没收到过时 onStarting 不能因此挂掉（`let` 的空安全）。 */
    @Test
    fun `没收到过任何载波时服务起来只是连接中`() {
        val tracker = ServiceStatusTracker(now = clock())
        tracker.onStarting()
        assertEquals(ServicePhase.Connecting, tracker.status.value.phase)
        assertTrue(tracker.serviceAlive)
    }
}
