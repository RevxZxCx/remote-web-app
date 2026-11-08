package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 载波状态的语义分类。
 *
 * 这一层看起来只是 `when`，但它决定了「什么情况算不通」——
 * 把 `Connecting` 漏成「通」会让断线提醒永不触发；把 `Idle` 算成「不通」
 * 会让 App 刚启动就报一条「连不上」。
 */
class LinkPhaseTest {

    @Test
    fun `连上了就是通`() {
        assertEquals(LinkPhase.Up, linkPhaseOf(CarrierState.Connected(0L)))
    }

    @Test
    fun `等重试是不通`() {
        val state = CarrierState.Reconnecting(attempt = 3, retryAtMillis = 0L, reason = "reset")
        assertEquals(LinkPhase.Down, linkPhaseOf(state))
    }

    @Test
    fun `正在握手也算不通 握手期间同样收不到东西`() {
        assertEquals(LinkPhase.Down, linkPhaseOf(CarrierState.Connecting))
    }

    @Test
    fun `配对失效是单独一类 与不通分开`() {
        assertEquals(LinkPhase.Unpaired, linkPhaseOf(CarrierState.Unpaired("revoked")))
    }

    @Test
    fun `从未连接既不是通也不是不通`() {
        assertEquals(LinkPhase.Idle, linkPhaseOf(CarrierState.Idle))
    }

    @Test
    fun `没有状态时按从未连接处理 不能当成断线`() {
        assertEquals(LinkPhase.Idle, linkPhaseOf(null))
    }

    @Test
    fun `不通与配对失效都算问题 从未连接不算`() {
        assertTrue(LinkPhase.Down.isProblem)
        assertTrue(LinkPhase.Unpaired.isProblem)
        assertFalse(LinkPhase.Up.isProblem)
        assertFalse(LinkPhase.Idle.isProblem)
    }
}

/**
 * 连接通知的判定状态机。
 *
 * 这里的每一条都在防一个具体的坏结果：
 * - 抖动提醒太多 → 用户关掉频道（这条频道一旦被关，真正断线时也不会响）；
 * - 断线提醒太少 → 用户以为「一切正常」，其实什么都没收到；
 * - 恢复不发 → 那条「连不上」永远挂在通知栏里，比不发更糟。
 */
class LinkNoticeGateTest {

    private val grace = LinkNoticeGate.DROP_GRACE_MILLIS

    @Test
    fun `刚开始就通着 什么都不用做`() {
        val gate = LinkNoticeGate()
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Up, nowMillis = 0L))
        assertFalse(gate.isWarning)
    }

    @Test
    fun `断一下马上就好 不打扰用户`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        val action = gate.onCarrier(LinkPhase.Up, nowMillis = 5_000L)
        // 没到宽限就恢复了：既没发过警告，也就没有「撤」这回事。
        assertEquals(LinkAction.Silent, action)
        assertFalse(gate.isWarning)
    }

    @Test
    fun `断得够久才提醒`() {
        val gate = LinkNoticeGate()
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = 0L))
        // 差一毫秒都不算。
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = grace - 1))
        val action = gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        assertEquals(LinkAction.Warn(LinkPhase.Down), action)
        assertTrue(gate.isWarning)
    }

    @Test
    fun `提醒过之后不重复提醒 否则每次退避失败都弹一条`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        assertEquals(LinkAction.Warn(LinkPhase.Down), gate.onCarrier(LinkPhase.Down, nowMillis = grace))
        // 后续每一次 Down（重试失败、退避重排）都必须是静默。
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = grace + 1_000))
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = grace * 10))
    }

    @Test
    fun `恢复时要撤掉那条提醒`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        assertEquals(LinkAction.Clear, gate.onCarrier(LinkPhase.Up, nowMillis = grace + 1_000))
        assertFalse(gate.isWarning)
    }

    @Test
    fun `恢复之后再断一次 还要能重新提醒`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        gate.onCarrier(LinkPhase.Up, nowMillis = grace + 1)
        // 第二轮断开：计时必须从新的起点算，不能沿用上一轮的 downSince。
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = 10 * grace))
        assertEquals(LinkAction.Warn(LinkPhase.Down), gate.onCarrier(LinkPhase.Down, nowMillis = 11 * grace))
    }

    @Test
    fun `配对失效立刻提醒 不等宽限`() {
        val gate = LinkNoticeGate()
        // 再等也不会好了，没有理由让用户白等 90 秒。
        assertEquals(LinkAction.Warn(LinkPhase.Unpaired), gate.onCarrier(LinkPhase.Unpaired, nowMillis = 0L))
        assertTrue(gate.isWarning)
    }

    @Test
    fun `配对失效不重复提醒`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Unpaired, nowMillis = 0L)
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Unpaired, nowMillis = 1L))
    }

    @Test
    fun `从等重试变成配对失效 要改说成重新配对`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        // 这条是**关键**：已经挂着「连不上，会自己重连」，而现在确定不会再好了。
        // 如果这里静默，用户会一直等一个永远不会发生的重连。
        val action = gate.onCarrier(LinkPhase.Unpaired, nowMillis = grace + 1)
        assertEquals(LinkAction.Warn(LinkPhase.Unpaired), action)
    }

    @Test
    fun `从未连接不该被当成断线 报一条假故障`() {
        val gate = LinkNoticeGate()
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Idle, nowMillis = 0L))
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Idle, nowMillis = grace * 10))
        assertFalse(gate.isWarning)
    }

    @Test
    fun `断的时间要能被读出来 文案里要写断了多久`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 1_000L)
        assertEquals(1_000L, gate.downSinceMillis())
    }

    @Test
    fun `恢复之后不再有断开起点`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 1_000L)
        gate.onCarrier(LinkPhase.Up, nowMillis = 2_000L)
        assertNull(gate.downSinceMillis())
    }

    @Test
    fun `提醒过之后再断 起点要重新记`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        // 已经 warned 的后续 Down 不该改起点吗？—— 该改：`downSince` 在发出警告时
        // 是**保留**的（文案要读它算「断了多久」），所以后续 Down 必须继续保留原起点。
        assertEquals(0L, gate.downSinceMillis())
    }
}

/**
 * 补账判定：只补「断过」的那些，且补完一次就销账。
 */
class LinkSettleTest {

    private val grace = LinkNoticeGate.DROP_GRACE_MILLIS

    @Test
    fun `一直连着没有要补的`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Up, nowMillis = 0L)
        assertFalse(gate.takeSettle())
    }

    @Test
    fun `断过就要补 哪怕没到宽限没提醒`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Up, nowMillis = 5_000L)
        // 断的 5 秒里丢的事件一样丢了，一样要补——只是不该为此打扰用户。
        assertTrue(gate.takeSettle())
    }

    @Test
    fun `断得久也要补`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        gate.onCarrier(LinkPhase.Up, nowMillis = grace + 1)
        assertTrue(gate.takeSettle())
    }

    @Test
    fun `补过一次就销账 同一次恢复不能补两遍`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Up, nowMillis = 1_000L)
        assertTrue(gate.takeSettle())
        // `Connected` 的 `sinceMillis` 变化会让状态流再发一次，若不销账会重复补。
        assertFalse(gate.takeSettle())
    }

    @Test
    fun `补完再断一次 还要能补`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Up, nowMillis = 1_000L)
        gate.takeSettle()
        gate.onCarrier(LinkPhase.Down, nowMillis = 2_000L)
        gate.onCarrier(LinkPhase.Up, nowMillis = 3_000L)
        assertTrue(gate.takeSettle())
    }

    @Test
    fun `换主机后全部复位 不能拿上一台的断开计时`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.onCarrier(LinkPhase.Down, nowMillis = grace)
        gate.reset()
        assertFalse(gate.isWarning)
        assertNull(gate.downSinceMillis())
        assertFalse(gate.takeSettle())
    }

    @Test
    fun `复位后重新断开 计时从新起点算`() {
        val gate = LinkNoticeGate()
        gate.onCarrier(LinkPhase.Down, nowMillis = 0L)
        gate.reset()
        gate.onCarrier(LinkPhase.Down, nowMillis = 100 * grace)
        assertEquals(100 * grace, gate.downSinceMillis())
        // 没有沿用复位前那个 0，所以不会立刻判成超时。
        assertEquals(LinkAction.Silent, gate.onCarrier(LinkPhase.Down, nowMillis = 100 * grace + 1))
    }
}
