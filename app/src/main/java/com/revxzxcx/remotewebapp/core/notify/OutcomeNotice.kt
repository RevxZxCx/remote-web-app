package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.session.AbortCause
import com.revxzxcx.remotewebapp.core.session.TurnEnd
import com.revxzxcx.remotewebapp.core.session.TurnOutcome

/**
 * 一轮结束后该发什么通知（R056 缺口 5）。
 *
 * ## 为什么需要这一层
 * 通知层原先只有两档：`api-session/status(false)` 的「跑完」与 `api-session/error`
 * 的「跑挂」。而线上真正会发生**六种**结局，其中几类两边都不占：
 *
 * | `turn/end.reason.kind` | 旧实现 | 用户实际看到的现象 |
 * | --- | --- | --- |
 * | `completed` | 跑完 | 正常 |
 * | `error` | 跑挂 | 正常（`api-session/error` 也会来） |
 * | `max-tokens` | **什么都不发** | 输出被切断，用户以为任务还在跑 |
 * | `blocked` | **什么都不发** | 被策略拦下等人批准，用户不知道 |
 * | `interrupted` | **什么都不发** | 上游断流，内容只有半截 |
 * | `aborted` | **什么都不发** | 进程被销毁/父会话取消，白等一场 |
 *
 * 这与 R043 之前对话页把六种结局都渲染成一行灰字是同一类错误——用户 m11638 说
 * 「这个逻辑错误在对话显示中你也犯过」，指的就是这件事。
 *
 * ## 为什么「用户自己按的停止」不通知
 * [AbortCause.USER] 是用户在主界面亲手停的：他知道自己停了，再来一条「本轮已中止」
 * 纯属噪声，而且这条噪音会盖掉通知栏里上一条真正需要处理的消息。其余三种来源
 * （`disposed` 进程销毁、`parent` 父会话取消、`unknown` 说不清）都不是用户此刻
 * 能感知的动作，必须告诉他。
 */
sealed interface OutcomeNotice {

    /** 完全不需要通知（用户自己停的那一轮）。 */
    data object Skip : OutcomeNotice

    /** 走「任务结束 / 任务出错」那套既有文案。 */
    data class Done(val failed: Boolean, val reason: String?) : OutcomeNotice

    /**
     * 既不是正常完成、也不是报错，但必须让用户知道的一类结局。
     *
     * 与 [Done] 分开是因为**文案与紧迫感都不同**：截断/拦下/中断/中止都不是
     * 「跑完了」，用「任务结束」的标题会让人以为一切正常。
     */
    data class Attention(val title: String, val text: String) : OutcomeNotice
}

/**
 * 把一个结局翻译成通知内容。
 *
 * @param outcome 查到的结局；传 `null` 表示**没查着**（连接刚断、订阅被取消、
 *   服务端拒绝等），此时退回「这一轮结束了」的旧口径——宁可按最含糊的说法告诉
 *   用户一声，也不要因为查不到而彻底沉默。
 * @param sessionName 已经过 [NoticeText.sessionName] 兜底的会话名。
 */
fun outcomeNotice(outcome: TurnOutcome?, sessionName: String): OutcomeNotice = when (outcome) {
    null -> OutcomeNotice.Done(failed = false, reason = null)
    is TurnOutcome.Completed -> OutcomeNotice.Done(failed = false, reason = null)
    is TurnOutcome.Failed -> OutcomeNotice.Done(failed = true, reason = outcome.message)
    is TurnOutcome.Truncated -> OutcomeNotice.Attention(
        NoticeText.truncatedTitle(sessionName),
        NoticeText.truncatedText(),
    )
    is TurnOutcome.Blocked -> OutcomeNotice.Attention(
        NoticeText.blockedTitle(sessionName),
        NoticeText.blockedText(),
    )
    is TurnOutcome.Interrupted -> OutcomeNotice.Attention(
        NoticeText.interruptedTitle(sessionName),
        NoticeText.interruptedText(),
    )
    is TurnOutcome.Aborted -> when (outcome.by) {
        AbortCause.USER -> OutcomeNotice.Skip
        else -> OutcomeNotice.Attention(
            NoticeText.abortedTitle(sessionName),
            NoticeText.abortedText(),
        )
    }
    is TurnOutcome.Unknown -> OutcomeNotice.Attention(
        NoticeText.unknownTitle(sessionName),
        NoticeText.unknownText(),
    )
}

/** [OutcomeLedger.decide] 的结论。 */
sealed interface OutcomeDecision {

    /**
     * 这一轮已经报过了（`api-session/status(false)` 与 `api-session/error` 常常
     * 一前一后描述同一件事），不要再响一次。
     */
    data object AlreadyNotified : OutcomeDecision

    /** 不需要通知，但**账要销**（用户自己停的那种结局）。 */
    data object Skip : OutcomeDecision

    /** 按 [notice] 发一条。 */
    data class Notify(val notice: OutcomeNotice) : OutcomeDecision
}

/**
 * 结局通知的去重账（R056 缺口 5）。
 *
 * ## 要解决的两件事
 * 1. **同一轮不要响两次**：一轮失败时，`api-session/status(false)` 与
 *    `api-session/error` 都会来；两次都去查结局，查回来必然是同一条 `turn/end`，
 *    它的 `seq` 相等——用 `seq` 记一笔就能认出「同一个事实」。
 * 2. **`api-session/error` 可能根本没有对应的 `turn/end`**：官方摘要是
 *    「One Agent failed **outside a durable turn position**」，即轮次之外的失败。
 *    此时查回来的是**上一轮**的旧 `turn/end`（常常是 `completed`），照它发一条
 *    「任务结束」会把真正的错误盖掉。所以错误原文单独记着，只要查到的结局不是
 *    `Failed`，就按错误发。
 *
 * ## 为什么不用时间戳判「新鲜度」
 * `turn/end` 信封里的 `time` 是**桌面端**的挂钟，手机上是另一块表。两台机器的时钟
 * 差几秒是常态（NTP 没同步时更糟），用它比「刚发生」会把正常结局判成旧的。
 * 上面那条「查到的结局不是 `Failed` 就信错误原文」的规则不需要任何时钟。
 *
 * 本类**不是线程安全的**：所有调用都发生在 `NotifyCoordinator` 的同一把锁里。
 */
class OutcomeLedger {

    /** 每个会话最后一条**已经就它发过通知**的 `turn/end.seq`。 */
    private val postedSeq = HashMap<String, Long>()

    /** 待用的 `api-session/error` 原文（同一会话后到的覆盖先到的）。 */
    private val pendingError = HashMap<String, String>()

    /**
     * 已经为哪几次「查不到结局」的翻转发过通知。
     *
     * ## 为什么这个也要记账
     * 同一次 `running` 翻转会从**两条路径**进来：`api-session/status`（running=false）
     * 与 `api-session/error`（失败时服务端未必再补一条 status）。两条路径各自查一次结局，
     * 若都查不到（超时、断连、会话已被回收），原先的实现会**各发一条**「任务结束」
     * ——用户为一件事被响两次。
     *
     * ## 为什么用「标记」而不是计数
     * 我们无从知道这一轮是哪一轮（连 `seq` 都没查到），所以只能记「这个会话刚报过一次
     * 无结局的结束」。下一条真正带 `seq` 的结局会覆盖它（[postedSeq]），下次查不到时
     * 只要 `pendingEnd` 还在就不再重复。
     */
    private val pendingEnd = HashSet<String>()

    /** 记下一条错误原文，等结局查询回来时一起判断。 */
    fun noteError(sessionId: String, message: String) {
        pendingError[sessionId] = message
    }

    /**
     * 判断这次查到的结局该不该发通知，并顺手记账。
     *
     * @param found 查到的最后一条 `turn/end`；`null` 表示没查到。
     * @param sessionName 已经兜底过的会话名，用来拼 [OutcomeNotice.Attention] 的标题。
     */
    fun decide(sessionId: String, found: TurnEnd?, sessionName: String): OutcomeDecision {
        // 错误原文是一次性的：无论这次走哪条分支，都已经被考虑过了。
        val error = pendingError.remove(sessionId)

        if (found == null) {
            // 同一轮的两条路径都查不到结局时只报一次。带了错误原文的仍然要报：
            // 那是一条**新的**事实（错误原文每次都可能是新的），不是重复。
            if (error == null && !pendingEnd.add(sessionId)) return OutcomeDecision.AlreadyNotified
            if (error != null) pendingEnd.add(sessionId)
            return OutcomeDecision.Notify(
                if (error != null) {
                    OutcomeNotice.Done(failed = true, reason = error)
                } else {
                    OutcomeNotice.Done(failed = false, reason = null)
                },
            )
        }

        // 查到了真正的结局：无结局那次记账作废，后面按 seq 判重。
        pendingEnd.remove(sessionId)

        val already = postedSeq[sessionId] == found.seq
        if (already) {
            // 这一轮报过了。但若这次带着一条错误原文、而那一轮当初报的不是失败，
            // 说明错误发生在轮次之外，是一条**新的**事实，仍然要报。
            if (error != null && found.outcome !is TurnOutcome.Failed) {
                return OutcomeDecision.Notify(OutcomeNotice.Done(failed = true, reason = error))
            }
            return OutcomeDecision.AlreadyNotified
        }

        postedSeq[sessionId] = found.seq
        if (error != null && found.outcome !is TurnOutcome.Failed) {
            return OutcomeDecision.Notify(OutcomeNotice.Done(failed = true, reason = error))
        }
        return when (val notice = outcomeNotice(found.outcome, sessionName)) {
            is OutcomeNotice.Skip -> OutcomeDecision.Skip
            else -> OutcomeDecision.Notify(notice)
        }
    }

    /** 会话被删掉时清账，避免同 id 会话重建后边沿判错。 */
    fun forget(sessionId: String) {
        postedSeq.remove(sessionId)
        pendingError.remove(sessionId)
        pendingEnd.remove(sessionId)
    }

    /** 换连接时全清（会话 id 是每台主机各自的）。 */
    fun clear() {
        postedSeq.clear()
        pendingError.clear()
        pendingEnd.clear()
    }
}
