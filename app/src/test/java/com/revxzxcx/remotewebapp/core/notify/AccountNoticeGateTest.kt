package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号提醒的判定器（R056 缺口 4）。
 *
 * 判的是「该不该打扰用户」，所以两头的错都要防：**该报的被压掉**（用户不知道
 * 所有任务为什么一起失败）、**不该报的连报**（一次凭据失效刷出一串通知）。
 */
class AccountNoticeGateTest {

    @Test
    fun `第一次失效要报`() {
        val gate = AccountNoticeGate()
        assertTrue(gate.onProblem(AccountProblem.SessionExpired))
    }

    @Test
    fun `同一条问题连着报多次只报第一次`() {
        // 凭据失效期间每个请求都会失败，服务端可能连发多条同一事件。
        val gate = AccountNoticeGate()
        assertTrue(gate.onProblem(AccountProblem.SessionExpired))
        assertFalse(gate.onProblem(AccountProblem.SessionExpired))
        assertFalse(gate.onProblem(AccountProblem.SessionExpired))
    }

    @Test
    fun `两类问题交替出现也只报一次 不能来回刷`() {
        // 审查 A9：原先判据是 `warned == problem`，于是 session-expired 与
        // model-sign-in-required 交替时会各响一次，凭据失效一次就刷屏。
        // 它们说的是**同一件事**（桌面端凭据出问题了），只是修的地方不同。
        val gate = AccountNoticeGate()
        assertTrue(gate.onProblem(AccountProblem.SessionExpired))
        assertFalse(gate.onProblem(AccountProblem.ModelSignIn))
        assertFalse(gate.onProblem(AccountProblem.SessionExpired))
        assertFalse(gate.onProblem(AccountProblem.ModelSignIn))
    }

    @Test
    fun `凭据变化后要能重新报 否则用户下次真失效时一声不吭`() {
        val gate = AccountNoticeGate()
        gate.onProblem(AccountProblem.SessionExpired)
        assertTrue("之前报过，这次要通知调用方去更新那条通知", gate.onCredentialsChanged())
        assertTrue("问题解决之后再失效，必须能再报", gate.onProblem(AccountProblem.SessionExpired))
    }

    @Test
    fun `没报过时凭据变化什么都不用做`() {
        val gate = AccountNoticeGate()
        assertFalse(gate.onCredentialsChanged())
    }

    @Test
    fun `换主机之后重新开始 账号是每台机器各自的`() {
        val gate = AccountNoticeGate()
        gate.onProblem(AccountProblem.SessionExpired)
        gate.reset()
        assertTrue(gate.onProblem(AccountProblem.SessionExpired))
    }
}
