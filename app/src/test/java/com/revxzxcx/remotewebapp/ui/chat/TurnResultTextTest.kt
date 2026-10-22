package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.AbortCause
import com.revxzxcx.remotewebapp.core.session.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮次结局卡的**文案与按钮出现条件**（R043 第 1 条）。
 *
 * 这一层是纯函数，不碰 Compose，所以能在 JVM 上跑。图形本身（颜色、圆角、
 * 折叠行数）只能靠渲染测试与设备验收，这里守的是**决定用户看到什么**的那部分：
 * 标题怎么分、tag 显示什么、错误正文退化成什么、何时给「重试本轮」、何时给
 * 「继续写」、以及灰字提示说的对不对。
 *
 * 用户 m26743 的原话是「app 端是看不到具体的内容的」——所以最要紧的一条是
 * `errorTextOf` 必须吐出上游原话，而不是「本轮失败」这种什么都没说的标题。
 */
class TurnResultTextTest {

    private fun failed(message: String = "", code: String? = null, status: Int? = null) =
        TurnOutcome.Failed(message = message, code = code, status = status)

    // ---------------------------------------------------------------- 标题

    @Test
    fun quotaAndCredentialGetTheirOwnTitles() {
        assertEquals("本轮失败 · 额度已用完", titleOf(failed(code = "QUOTA_EXCEEDED")))
        assertEquals("本轮失败 · 未登录", titleOf(failed(code = "MISSING_CREDENTIAL")))
        assertEquals("本轮失败 · 模型限流", titleOf(failed(code = "RATE_LIMIT")))
        assertEquals("本轮失败 · 鉴权被拒", titleOf(failed(code = "AUTH")))
        assertEquals("本轮失败 · 没有可用通道", titleOf(failed(code = "NO_ADAPTER")))
        assertEquals("本轮失败 · 思考陷入循环", titleOf(failed(code = "REASONING_LOOP")))
    }

    @Test
    fun unknownCodeFallsBackToGenericFailure() {
        // 服务端加了新 code，标题退成通用的「本轮失败」，正文与 tag 仍然准。
        assertEquals("本轮失败", titleOf(failed(code = "SOMETHING_NEW")))
        assertEquals("本轮失败", titleOf(failed(code = null)))
    }

    @Test
    fun truncationAndInterruptionHaveDistinctTitles() {
        assertEquals("输出被截断", titleOf(TurnOutcome.Truncated))
        assertEquals("生成被中断", titleOf(TurnOutcome.Interrupted))
        assertEquals("本轮被拦下", titleOf(TurnOutcome.Blocked))
        assertEquals("本轮结束异常", titleOf(TurnOutcome.Unknown("future")))
    }

    @Test
    fun abortTitlesDistinguishWhoAborted() {
        // 三种中止原因文案必须不同：用户自己按的停止、父会话被停、连接断了，
        // 是三种完全不同的处境，混成一句「本轮已中止」会误导。
        assertEquals("本轮已中止", titleOf(TurnOutcome.Aborted(AbortCause.USER)))
        assertEquals("被父会话中止", titleOf(TurnOutcome.Aborted(AbortCause.PARENT)))
        assertEquals("连接中断，本轮中止", titleOf(TurnOutcome.Aborted(AbortCause.DISPOSED)))
        assertEquals("本轮已中止", titleOf(TurnOutcome.Aborted(AbortCause.UNKNOWN)))
    }

    // ---------------------------------------------------------------- tag

    @Test
    fun tagPrefersCodeOverKind() {
        assertEquals("RATE_LIMIT", tagOf(failed(code = "RATE_LIMIT")))
        // 没有 code 时用 kind 的大写形态：用户至少知道这是「哪种失败」。
        assertEquals("ERROR", tagOf(failed(code = null)))
    }

    @Test
    fun abortTagsUseStopVocabulary() {
        assertEquals("STOPPED", tagOf(TurnOutcome.Aborted(AbortCause.USER)))
        assertEquals("PARENT", tagOf(TurnOutcome.Aborted(AbortCause.PARENT)))
        assertEquals("DISPOSED", tagOf(TurnOutcome.Aborted(AbortCause.DISPOSED)))
    }

    @Test
    fun normalEndingsHaveNoTag() {
        // 截断 / 中断 / 拦下 都不是「错误分类」，给 tag 只会制造一行噪音。
        assertNull(tagOf(TurnOutcome.Truncated))
        assertNull(tagOf(TurnOutcome.Interrupted))
        assertNull(tagOf(TurnOutcome.Blocked))
    }

    // ---------------------------------------------------------------- 错误正文

    @Test
    fun errorTextIsVerbatimUpstreamMessage() {
        // 这条是整个 R043 第 1 条的核心：上游原话必须原样到用户眼前。
        val message = """429 {"error":{"message":"UID rate limit reached (request_id: chatcmpl-abc)"}}"""
        assertEquals(message, errorTextOf(failed(message = message, code = "RATE_LIMIT")))
    }

    @Test
    fun errorTextFallsBackToCodeWhenMessageIsBlank() {
        // 只有 code 时不能显示空块，退成「code + HTTP 状态」。
        assertEquals("TRANSPORT", errorTextOf(failed(message = "   ", code = "TRANSPORT")))
        assertEquals("HTTP 413", errorTextOf(failed(code = null, status = 413)))
        assertEquals("TRANSPORT · HTTP 502", errorTextOf(failed(code = "TRANSPORT", status = 502)))
    }

    @Test
    fun nonFailureOutcomesHaveNoErrorBody() {
        // 截断、中止、拦下 都没有「错误原文」可显示；给一个空的黑底块
        // 只会让人以为出了别的问题。
        assertNull(errorTextOf(TurnOutcome.Truncated))
        assertNull(errorTextOf(TurnOutcome.Interrupted))
        assertNull(errorTextOf(TurnOutcome.Aborted(AbortCause.USER)))
        assertNull(errorTextOf(TurnOutcome.Blocked))
    }

    // ---------------------------------------------------------------- 重试

    @Test
    fun retryIsOfferedForTransientFailures() {
        assertTrue(canRetry(failed(code = "RATE_LIMIT")))
        assertTrue(canRetry(failed(code = "TRANSPORT")))
        assertTrue(canRetry(failed(code = "SERVER")))
        assertTrue(canRetry(failed(code = "UNKNOWN")))
        assertTrue(canRetry(failed(code = null)))
    }

    @Test
    fun retryIsHiddenWhenItCannotHelp() {
        // 这六个不是「重发一次就好了」：额度、凭据、鉴权、适配器、请求过大，
        // 再点一次必然再失败一次。给按钮等于骗用户。
        listOf(
            "QUOTA_EXCEEDED",
            "MISSING_CREDENTIAL",
            "AUTH",
            "NO_ADAPTER",
            "HTTP_413",
            "CLIENT_ERROR",
        ).forEach { code ->
            assertFalse("$code 不该给重试", canRetry(failed(code = code)))
        }
    }

    @Test
    fun retryIsOfferedForInterruptionAndUnknownKind() {
        // 中断与未知结局都可能是瞬时问题，重发一次是合理的默认动作。
        assertTrue(canRetry(TurnOutcome.Interrupted))
        assertTrue(canRetry(TurnOutcome.Unknown("future")))
    }

    @Test
    fun retryIsNotOfferedForTruncationAbortOrBlock() {
        // 截断要的是「继续写」不是「重试」；中止与拦下重发等于无视用户的意图。
        assertFalse(canRetry(TurnOutcome.Truncated))
        assertFalse(canRetry(TurnOutcome.Aborted(AbortCause.USER)))
        assertFalse(canRetry(TurnOutcome.Blocked))
    }

    // ---------------------------------------------------------------- 继续写

    @Test
    fun continueIsOnlyForTruncation() {
        // 只有「输出被 max-tokens 截断」才有「继续写」这个语义动作；
        // 别的结局继续写会得到一段接不上的文字。
        assertTrue(canContinue(TurnOutcome.Truncated))
        assertFalse(canContinue(failed(code = "RATE_LIMIT")))
        assertFalse(canContinue(TurnOutcome.Interrupted))
        assertFalse(canContinue(TurnOutcome.Aborted(AbortCause.USER)))
    }

    // ---------------------------------------------------------------- 提示

    @Test
    fun hintExplainsWhyRetryIsMissing() {
        // 不可重试的结局，灰字必须说清楚「接下来该干嘛」，否则卡片只有一个
        // 复制按钮，用户不知道怎么办。
        assertTrue(hintOf(failed(code = "QUOTA_EXCEEDED")).contains("桌面端"))
        assertTrue(hintOf(failed(code = "MISSING_CREDENTIAL")).contains("登录"))
        assertTrue(hintOf(failed(code = "HTTP_413")).contains("附件"))
        assertTrue(hintOf(failed(code = "CLIENT_ERROR")).contains("附件"))
    }

    @Test
    fun hintForRetryableFailureTellsUserToResend() {
        assertTrue(hintOf(failed(code = "RATE_LIMIT")).contains("重发"))
    }

    @Test
    fun hintForTruncationMentionsContinue() {
        assertTrue(hintOf(TurnOutcome.Truncated).contains("继续"))
    }

    @Test
    fun everyOutcomeHasANonBlankHint() {
        // 空灰字会留下一个只剩按钮的空行。
        listOf(
            failed(code = "RATE_LIMIT"),
            failed(code = "QUOTA_EXCEEDED"),
            TurnOutcome.Truncated,
            TurnOutcome.Interrupted,
            TurnOutcome.Blocked,
            TurnOutcome.Aborted(AbortCause.USER),
            TurnOutcome.Aborted(AbortCause.PARENT),
            TurnOutcome.Aborted(AbortCause.DISPOSED),
            TurnOutcome.Unknown("future"),
        ).forEach { outcome ->
            assertTrue("${outcome::class.simpleName} 的提示不能为空", hintOf(outcome).isNotBlank())
        }
    }
}
