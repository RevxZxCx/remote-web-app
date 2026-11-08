package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.session.AbortCause
import com.revxzxcx.remotewebapp.core.session.TurnEnd
import com.revxzxcx.remotewebapp.core.session.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一轮结束后该发什么通知」的翻译表（R056 缺口 5）。
 *
 * 这组测试守的是**语义**而不是文案措辞：六种 `turn/end` 结局里，
 * 哪几种必须让用户知道、哪几种必须闭嘴、哪一种要看中止来源。
 *
 * 之所以单独成文件而不是塞进 `NoticeGateTest`：`NoticeGate` 判的是
 * 「现在该不该打扰用户」（前后台、频道、权限），这里判的是
 * 「这件事本身说不说得出口」。两件事混在一起，改文案时会莫名踩到门控断言。
 */
class OutcomeNoticeTest {

    private fun done(failed: Boolean, reason: String? = null) = OutcomeNotice.Done(failed, reason)

    // ── 正常完成 ────────────────────────────────────────────────────────

    @Test
    fun `正常跑完就是任务结束 不是异常`() {
        assertEquals(done(false), outcomeNotice(TurnOutcome.Completed, "S"))
    }

    @Test
    fun `查不到结局时退回任务结束 不能因为查不到就彻底沉默`() {
        // 用户已经知道那一轮停了。查不到结局是「我们看不见」，不是「什么都没发生」。
        assertEquals(done(false), outcomeNotice(null, "S"))
    }

    // ── 失败 ────────────────────────────────────────────────────────────

    @Test
    fun `失败要带出服务端给的原因原文`() {
        val notice = outcomeNotice(
            TurnOutcome.Failed("you have no active step plan subscription", "request_params_invalid", 400),
            "S",
        )
        assertEquals(done(true, "you have no active step plan subscription"), notice)
    }

    @Test
    fun `失败原因不能吞成空串 空原因也要有一条`() {
        val notice = outcomeNotice(TurnOutcome.Failed("", null, null), "S")
        assertEquals(done(true, ""), notice)
    }

    // ── 四种「没跑成也不算报错」 ────────────────────────────────────────

    @Test
    fun `输出被截断要说出来 不能当成正常跑完`() {
        // 这是本缺口最初的样子：max-tokens 结局一条通知都没有，
        // 用户看着内容断在半截，以为任务还在跑。
        val notice = outcomeNotice(TurnOutcome.Truncated, "S")
        assertTrue(notice is OutcomeNotice.Attention)
        assertNotEquals(done(false), notice)
    }

    @Test
    fun `被策略拦下要说出来`() {
        assertTrue(outcomeNotice(TurnOutcome.Blocked, "S") is OutcomeNotice.Attention)
    }

    @Test
    fun `上游断流要说出来`() {
        assertTrue(outcomeNotice(TurnOutcome.Interrupted, "S") is OutcomeNotice.Attention)
    }

    @Test
    fun `认不出的结局也要说一声 不能装没发生`() {
        assertTrue(outcomeNotice(TurnOutcome.Unknown("quantum-entangled"), "S") is OutcomeNotice.Attention)
    }

    @Test
    fun `四种没跑成 的标题互不相同 否则用户分不出发生了什么`() {
        val titles = listOf(
            outcomeNotice(TurnOutcome.Truncated, "S"),
            outcomeNotice(TurnOutcome.Blocked, "S"),
            outcomeNotice(TurnOutcome.Interrupted, "S"),
            outcomeNotice(TurnOutcome.Aborted(AbortCause.DISPOSED), "S"),
            outcomeNotice(TurnOutcome.Unknown("x"), "S"),
        ).map { (it as OutcomeNotice.Attention).title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `四种没跑成 的标题都不能写成任务结束`() {
        // 「任务结束」的意思是「一切按预期收尾」，而这几种恰恰不是。
        // 复用那四个字等于把没跑成报成跑成了——R043 之前对话页犯的就是这个错。
        val titles = listOf(
            outcomeNotice(TurnOutcome.Truncated, "S"),
            outcomeNotice(TurnOutcome.Blocked, "S"),
            outcomeNotice(TurnOutcome.Interrupted, "S"),
            outcomeNotice(TurnOutcome.Aborted(AbortCause.DISPOSED), "S"),
            outcomeNotice(TurnOutcome.Unknown("x"), "S"),
        ).map { (it as OutcomeNotice.Attention).title }
        for (title in titles) {
            assertFalse("标题不应伪装成正常结束：$title", title.contains("任务结束"))
        }
    }

    @Test
    fun `标题都带会话名 一眼知道是哪个会话`() {
        val notices = listOf(
            outcomeNotice(TurnOutcome.Truncated, "重构登录"),
            outcomeNotice(TurnOutcome.Blocked, "重构登录"),
            outcomeNotice(TurnOutcome.Interrupted, "重构登录"),
            outcomeNotice(TurnOutcome.Aborted(AbortCause.DISPOSED), "重构登录"),
            outcomeNotice(TurnOutcome.Unknown("x"), "重构登录"),
        )
        for (notice in notices) {
            assertTrue((notice as OutcomeNotice.Attention).title.contains("重构登录"))
        }
    }

    // ── 中止来源 ────────────────────────────────────────────────────────

    @Test
    fun `用户自己按的停止不发通知 他知道自己停了`() {
        assertEquals(OutcomeNotice.Skip, outcomeNotice(TurnOutcome.Aborted(AbortCause.USER), "S"))
    }

    @Test
    fun `进程被销毁要通知 用户对此无能为力`() {
        val notice = outcomeNotice(TurnOutcome.Aborted(AbortCause.DISPOSED), "S")
        assertTrue(notice is OutcomeNotice.Attention)
    }

    @Test
    fun `父会话取消要通知 子代理的用户看不到父会话在干什么`() {
        assertTrue(outcomeNotice(TurnOutcome.Aborted(AbortCause.PARENT), "S") is OutcomeNotice.Attention)
    }

    @Test
    fun `来源不明的中止按要通知处理 宁可多说一句`() {
        assertTrue(outcomeNotice(TurnOutcome.Aborted(AbortCause.UNKNOWN), "S") is OutcomeNotice.Attention)
    }
}

/**
 * 结局通知的去重账。
 *
 * 要守住的两件事：
 * 1. 同一轮不能响两次——`status(false)` 与 `error` 两条路会各查一次，查回来是同一条 `turn/end`；
 * 2. `error` 可能不属于任何一轮（官方摘要是「outside a durable turn position」），
 *    此时不能因为「查到的旧结局是 completed」就把错误静默掉。
 */
class OutcomeLedgerTest {

    private fun failed(message: String) = TurnOutcome.Failed(message, null, null)

    private fun end(seq: Long, outcome: TurnOutcome) = TurnEnd(seq = seq, turn = 1L, outcome = outcome)

    @Test
    fun `第一次查到结局要发`() {
        val ledger = OutcomeLedger()
        val decision = ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(false, null)), decision)
    }

    @Test
    fun `同一轮再查一次不再响 否则 status 与 error 会响两遍`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        assertEquals(OutcomeDecision.AlreadyNotified, ledger.decide("s1", end(10, TurnOutcome.Completed), "S"))
    }

    @Test
    fun `下一轮是一个新事实 要再响一次`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        val second = ledger.decide("s1", end(20, TurnOutcome.Completed), "S")
        assertTrue(second is OutcomeDecision.Notify)
    }

    @Test
    fun `两个会话互不影响 一个报过不该让另一个闭嘴`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        val other = ledger.decide("s2", end(10, TurnOutcome.Completed), "S")
        assertTrue(other is OutcomeDecision.Notify)
    }

    @Test
    fun `查不到结局时 带着错误就报失败 不带错误报结束`() {
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "连接被重置")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(true, "连接被重置")),
            ledger.decide("s1", null, "S"),
        )
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(false, null)),
            ledger.decide("s2", null, "S"),
        )
    }

    @Test
    fun `错误发生在轮次之外时 不能被旧结局盖掉`() {
        // 官方摘要：api-session/error 报的是「outside a durable turn position」。
        // 此时查到的最后一条 turn/end 常常是上一轮的 completed——
        // 照它发「任务结束」会把真正的错误静默掉，而用户根本不知道出过事。
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        ledger.noteError("s1", "网关过载")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(true, "网关过载")),
            ledger.decide("s1", end(10, TurnOutcome.Completed), "S"),
        )
    }

    @Test
    fun `查到的结局本身就是失败时 用结局里的原因而不是 error 帧的一句话`() {
        // 429 那种 error 帧只有一句概括，turn/end 里带着具体 message 与 code。
        // 两者都在时信前者之外还要信后者：以结局为准，因为它更具体。
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "gateway: request failed")
        val decision = ledger.decide("s1", end(10, failed("你还没有订阅")), "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(true, "你还没有订阅")), decision)
    }

    @Test
    fun `错误原文是一次性的 不能留到下一轮再报一次`() {
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "网关过载")
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        // 下一轮已经成功，不该再把上一轮的错误翻出来。
        val next = ledger.decide("s1", end(20, TurnOutcome.Completed), "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(false, null)), next)
    }

    @Test
    fun `用户自己停的那一轮不记账也不想发`() {
        val ledger = OutcomeLedger()
        val decision = ledger.decide("s1", end(10, TurnOutcome.Aborted(AbortCause.USER)), "S")
        assertEquals(OutcomeDecision.Skip, decision)
    }

    @Test
    fun `用户停过之后 下一轮照常要通知`() {
        // Skip 也要记账：记的是「这一轮处理过了」，不是「以后都不用报了」。
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Aborted(AbortCause.USER)), "S")
        val next = ledger.decide("s1", end(20, TurnOutcome.Completed), "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(false, null)), next)
    }

    @Test
    fun `会话被删掉后清账 同 id 重建不能因为旧账闭嘴`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        ledger.forget("s1")
        assertTrue(ledger.decide("s1", end(10, TurnOutcome.Completed), "S") is OutcomeDecision.Notify)
    }

    @Test
    fun `换主机后全清 seq 是每台机器各自的编号`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        ledger.clear()
        assertTrue(ledger.decide("s1", end(10, TurnOutcome.Completed), "S") is OutcomeDecision.Notify)
    }

    @Test
    fun `clear 之后连错误原文也不留 否则新连接上会凭空报一次失败`() {
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "旧连接的错误")
        ledger.clear()
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(false, null)),
            ledger.decide("s1", end(10, TurnOutcome.Completed), "S"),
        )
    }

    @Test
    fun `两条路径都查不到结局时只报一次 不能为一件事响两遍`() {
        // 场景：status(running=false) 与 error 帧先后进来，各查一次结局都没查到
        // （超时 / 断连 / 会话已被回收）。两次都 `found == null`，若各自照发，
        // 用户会为**同一轮**收到两条一模一样的「任务结束」。
        val ledger = OutcomeLedger()
        val first = ledger.decide("s1", null, "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(false, null)), first)
        assertEquals(OutcomeDecision.AlreadyNotified, ledger.decide("s1", null, "S"))
    }

    @Test
    fun `查不到结局时报过一次 之后查到真结局仍然要报`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", null, "S")
        val decision = ledger.decide("s1", end(10, TurnOutcome.Completed), "S")
        assertEquals(OutcomeDecision.Notify(OutcomeNotice.Done(false, null)), decision)
    }

    @Test
    fun `查不到结局时带着不同错误 每条都要报 那是新的失败`() {
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "第一次失败")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(true, "第一次失败")),
            ledger.decide("s1", null, "S"),
        )
        ledger.noteError("s1", "第二次失败")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(true, "第二次失败")),
            ledger.decide("s1", null, "S"),
        )
    }

    @Test
    fun `forget 之后 查不到结局也能重新报一次`() {
        val ledger = OutcomeLedger()
        ledger.decide("s1", null, "S")
        ledger.forget("s1")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(false, null)),
            ledger.decide("s1", null, "S"),
        )
    }

    @Test
    fun `forget 也要清错误原文`() {
        val ledger = OutcomeLedger()
        ledger.noteError("s1", "旧错误")
        ledger.forget("s1")
        assertEquals(
            OutcomeDecision.Notify(OutcomeNotice.Done(false, null)),
            ledger.decide("s1", end(10, TurnOutcome.Completed), "S"),
        )
    }
}
