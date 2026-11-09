package com.revxzxcx.remotewebapp.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复决策与「本次已退出」标记（用户 Q2 + Q4 的拍板）。
 *
 * 这一层是「服务被停之后到底会发生什么」的全部逻辑，但它跑在通知接收器、
 * 服务线程、载波回调三条路径上，**没有任何一处能在真机上稳定复现**
 * （要靠 LMK 恰好在某个时刻杀进程）。所以它必须是纯函数 + 单测，
 * 否则用户拍板的「自动恢复可开关」只能靠读代码相信。
 */
class PlanRecoveryTest {

    @Test
    fun `总开关关着时什么都不做 用户已经说过不要这个功能`() {
        val action = planRecovery(
            foregroundEnabled = false,
            autoRecover = true,
            userExited = false,
            noticeUsable = true,
        )
        assertEquals(RecoveryAction.DoNothing, action)
    }

    @Test
    fun `用户刚点了退出就不该被悄悄拉回来`() {
        val action = planRecovery(
            foregroundEnabled = true,
            autoRecover = true,
            userExited = true,
            noticeUsable = true,
        )
        // 这是「退出」按钮有意义的前提：不然服务一停自动恢复立刻拉起，
        // 用户点了跟没点一样，而且那条常驻通知还会画回来。
        assertEquals(RecoveryAction.DoNothing, action)
    }

    @Test
    fun `通知不可用时也不做 起了也会被系统回收`() {
        val action = planRecovery(
            foregroundEnabled = true,
            autoRecover = true,
            userExited = false,
            noticeUsable = false,
        )
        assertEquals(RecoveryAction.DoNothing, action)
    }

    @Test
    fun `自动恢复开着就静默拉起 不打扰`() {
        val action = planRecovery(
            foregroundEnabled = true,
            autoRecover = true,
            userExited = false,
            noticeUsable = true,
        )
        assertEquals(RecoveryAction.RestartSilently, action)
    }

    @Test
    fun `自动恢复关着才发通知 这是用户原话的互斥分支`() {
        val action = planRecovery(
            foregroundEnabled = true,
            autoRecover = false,
            userExited = false,
            noticeUsable = true,
        )
        assertEquals(RecoveryAction.NotifyUser, action)
    }

    @Test
    fun `四个输入里只有 autoRecover 能翻转结论 其余三个都在允许侧`() {
        // 守的是「自动恢复」这个开关真的接在决策上：把它写成常量这里立刻红。
        assertEquals(
            RecoveryAction.RestartSilently,
            planRecovery(foregroundEnabled = true, autoRecover = true, userExited = false, noticeUsable = true),
        )
        assertEquals(
            RecoveryAction.NotifyUser,
            planRecovery(foregroundEnabled = true, autoRecover = false, userExited = false, noticeUsable = true),
        )
    }
}

/**
 * 开机自启的三个条件。
 *
 * 少判任何一条的后果都不是「多起一次」，而是**用户在系统里关掉了通知，
 * 开机时我们却起一个注定被回收的服务**，然后 `onStartFailed` 把开关也关掉——
 * 用户会发现「我明明开着它，重启后自己关了」。
 */
class ShouldStartOnBootTest {

    @Test
    fun `三个条件都满足才起`() {
        assertTrue(shouldStartOnBoot(foregroundEnabled = true, bootStart = true, noticeUsable = true))
    }

    @Test
    fun `总开关关着不起`() {
        assertFalse(shouldStartOnBoot(foregroundEnabled = false, bootStart = true, noticeUsable = true))
    }

    @Test
    fun `开机自启关着不起`() {
        assertFalse(shouldStartOnBoot(foregroundEnabled = true, bootStart = false, noticeUsable = true))
    }

    @Test
    fun `通知不可用不起 免得起来就被回收`() {
        // 即使开机自启开着也不行：漏判它就会起一个注定被回收的服务，
        // 然后 onStartFailed 把用户的开关关掉，用户看到「我明明开着它，重启后自己关了」。
        assertFalse(shouldStartOnBoot(foregroundEnabled = true, bootStart = true, noticeUsable = false))
    }
}

/**
 * 「本次已退出」标记。
 *
 * 它只有三种合法转移：退出 → 运行（解锁）、退出 → 复位（换主机）、运行 → 退出。
 * 真正要守的是**它不落盘**：一旦有人给它加 `SharedPreferences`，
 * 「退出一次」就变成了「永久关闭」，与用户「后续启动还是会启动」的要求相反。
 */
class ServiceExitMarkerTest {

    @Test
    fun `默认没退出`() {
        assertFalse(ServiceExitMarker().isExited)
    }

    @Test
    fun `点了退出就记下`() {
        val marker = ServiceExitMarker()
        marker.markExited()
        assertTrue(marker.isExited)
    }

    @Test
    fun `服务真的起来了就把标记解开 否则退出变成解不开的锁`() {
        val marker = ServiceExitMarker()
        marker.markExited()
        marker.markRunning()
        assertFalse(marker.isExited)
    }

    @Test
    fun `复位后可以重新退出`() {
        val marker = ServiceExitMarker()
        marker.markExited()
        marker.reset()
        assertFalse(marker.isExited)
        marker.markExited()
        assertTrue(marker.isExited)
    }

    @Test
    fun `重复退出不出错`() {
        val marker = ServiceExitMarker()
        marker.markExited()
        marker.markExited()
        assertTrue(marker.isExited)
    }

    /**
     * 第三轮审查 P2-5。用户点「退出」之后又换了主机 / 重新配对，
     * 那一次「我不要这个服务」说的是**上一台主机**，不该把新主机也一起锁死。
     */
    @Test
    fun `退出只锁那一台主机 换主机之后还能自动起`() {
        val marker = ServiceExitMarker()
        marker.markExited("host-a")
        assertTrue(marker.isExitedFor("host-a"))
        assertFalse(marker.isExitedFor("host-b"))
    }

    /**
     * 没带主机信息的退出（老调用点）要按「全都算退出」处理：
     * 宁可少起一次服务，也不能把用户亲口说的「退出」当成没说过。
     */
    @Test
    fun `没记主机时退出对谁都算数`() {
        val marker = ServiceExitMarker()
        marker.markExited()
        assertTrue(marker.isExitedFor("host-a"))
        assertTrue(marker.isExitedFor("host-b"))
        assertTrue(marker.isExitedFor(""))
    }

    @Test
    fun `服务起来了之后任何主机都不再算退出`() {
        val marker = ServiceExitMarker()
        marker.markExited("host-a")
        marker.markRunning()
        assertFalse(marker.isExited)
        assertFalse(marker.isExitedFor("host-a"))
    }

    @Test
    fun `复位要连主机一起忘掉 否则换回来会被旧账挡住`() {
        val marker = ServiceExitMarker()
        marker.markExited("host-a")
        marker.reset()
        marker.markExited("host-b")
        assertFalse(marker.isExitedFor("host-a"))
        assertTrue(marker.isExitedFor("host-b"))
    }
}
