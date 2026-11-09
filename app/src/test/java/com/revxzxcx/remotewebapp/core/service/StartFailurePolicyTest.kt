package com.revxzxcx.remotewebapp.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动失败的分类（真机验收发现的缺陷，对应缺陷记录 B010）。
 *
 * 这一层决定「一次启动失败之后开关还在不在、还会不会自动重试」，而它跑在
 * 系统广播、后台调度这些**没法在真机上稳定复现**的路径上。所以必须是纯函数 + 单测。
 */
class StartFailurePolicyTest {

    @Test
    fun `时机性失败不能关开关也不能挡住重试`() {
        val policy = startFailurePolicy(temporary = true, noticeUsable = true)
        assertFalse("开机时撞一次就关掉开关，用户之后再也不会自动连上", policy.turnsSwitchOff)
        assertFalse("挡住重试之后只有用户亲手重开开关才解得开", policy.blocksRetry)
    }

    @Test
    fun `通知不可用时同样不关不挡 用户去授权就能好`() {
        val policy = startFailurePolicy(temporary = false, noticeUsable = false)
        assertFalse(policy.turnsSwitchOff)
        assertFalse(policy.blocksRetry)
    }

    @Test
    fun `通知可用却还是起不来 说明重试没用 该关该挡`() {
        val policy = startFailurePolicy(temporary = false, noticeUsable = true)
        assertTrue("亮着的开关在骗人，用户以为任务跑完会提醒", policy.turnsSwitchOff)
        assertTrue(policy.blocksRetry)
    }

    @Test
    fun `时机性优先于通知可用 两者不能互相override`() {
        // 通知不可用 + 时机性：仍然是「不关不挡」。若哪天写成 `temporary && noticeUsable`
        // 就会在这一格翻车，而这一格正是开机那一刻最常见的组合。
        val policy = startFailurePolicy(temporary = true, noticeUsable = false)
        assertFalse(policy.turnsSwitchOff)
        assertFalse(policy.blocksRetry)
    }
}

/**
 * 「这次失败是时机性的吗」——按类名和 message 认，不看具体类型。
 *
 * ## 为什么不用 `is ForegroundServiceStartNotAllowedException`
 * 那个类 API 31 才有，minSdk 是 26。`is` 编成 `instanceof`，在 26~30 的机器上
 * 遇到不存在的类会 `NoClassDefFoundError`——为了一条诊断把 App 搞崩不值。
 */
class TemporaryStartFailureTest {

    /** 真机上抓到的原文，一个字都不能改：`startForegroundService() not allowed due to ...`。 */
    private val realMessage =
        "startForegroundService() not allowed due to mAllowStartForeground false: " +
            "service com.revxzxcx.remotewebapp/.core.service.ForegroundLinkService"

    @Test
    fun `按 message 认出真机上抓到的那条`() {
        assertTrue(isTemporaryStartFailure(IllegalStateException(realMessage)))
    }

    @Test
    fun `按类名也能认 不依赖 message 长什么样`() {
        // 类名恰好的假异常：真实机型上抛的就是这个 simpleName。
        // 不直接用 android 的那个类，是因为它 API 31 才有、JVM 单测里也没有真实实现。
        assertTrue(isTemporaryStartFailure(ForegroundServiceStartNotAllowedException("随便什么话")))
    }

    @Test
    fun `包在别的异常里也要能认出来`() {
        val inner = IllegalStateException(realMessage)
        val wrapped = RuntimeException("startService 失败", inner)
        assertTrue(isTemporaryStartFailure(wrapped))
    }

    @Test
    fun `多层嵌套也要走到底`() {
        var current: Throwable = IllegalStateException(realMessage)
        repeat(4) { current = RuntimeException("第 $it 层", current) }
        assertTrue(isTemporaryStartFailure(current))
    }

    @Test
    fun `别的异常不能误判成时机性 否则真的永久失败会被无限重试`() {
        assertFalse(isTemporaryStartFailure(SecurityException("Permission Denial")))
        assertFalse(isTemporaryStartFailure(IllegalStateException("通知不可用（未授权或已被关闭）")))
    }

    @Test
    fun `message 为 null 也不能崩`() {
        assertFalse(isTemporaryStartFailure(RuntimeException()))
        assertFalse(isTemporaryStartFailure(RuntimeException(null as String?)))
    }

    @Test
    fun `超出深度上限就不再往下找 换来的是绝不会死循环`() {
        // 把真消息埋到第 20 层，深度上限 8 应当认不出来。
        // 真实嵌套不会这么深，而上限的意义就是「无论链多长都会停」。
        var current: Throwable = IllegalStateException(realMessage)
        repeat(20) { current = RuntimeException("第 $it 层", current) }
        assertFalse(isTemporaryStartFailure(current))
    }
}

/** 类名恰好是系统那个异常（simpleName 一致），用来验「按名字认」这一路。 */
private class ForegroundServiceStartNotAllowedException(message: String) : RuntimeException(message)
