package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `turn/end` 的 `reason` 解析（R043 第 1 条，用户 m26743）。
 *
 * 这份测试守的是「**对象不再被当成 null 丢掉**」这条主线。改之前
 * `SessionEventParser.turnEndNotice` 用 `data.string("reason")` 读，而字符串
 * 扩展只认 `JsonPrimitive` 字符串；`reason` 在真实日志里 646 次全是对象，
 * 于是每一次失败、截断、中止都被渲染成中性的「第 N 轮结束」，错误原文彻底丢失。
 *
 * 夹具全部取自真实会话日志（`~/.dsh/sessions/` 下每个会话的 `session.v4.jsonl.zst`），不是手编的理想形状：
 * 错误 message 里带嵌套 JSON、`status` 有的出现有的缺失、`aborted` 的
 * `reason.kind` 有三种取值。
 */
class TurnOutcomeTest {

    private fun parse(raw: String): TurnOutcome? =
        parseTurnOutcome(Json.parseToJsonElement(raw))

    // ---------------------------------------------------------------- 成功与缺失

    @Test
    fun completedIsRecognized() {
        assertEquals(TurnOutcome.Completed, parse("""{"kind":"completed"}"""))
    }

    @Test
    fun missingReasonYieldsNull() {
        // 顶层没有 reason 字段：解析器必须给 null，让调用方决定「什么都不显示」，
        // 而不是造一个 Unknown 卡片出来。
        assertNull(parseTurnOutcome(null))
    }

    // ---------------------------------------------------------------- 失败

    @Test
    fun errorKeepsMessageCodeAndStatus() {
        val outcome = parse(
            """
            {"kind":"error","error":{"message":"429 too many requests","code":"RATE_LIMIT","status":429}}
            """.trimIndent(),
        )
        assertTrue(outcome is TurnOutcome.Failed)
        outcome as TurnOutcome.Failed
        assertEquals("429 too many requests", outcome.message)
        assertEquals("RATE_LIMIT", outcome.code)
        assertEquals(429, outcome.status)
    }

    @Test
    fun errorMessageWithNestedJsonSurvivesVerbatim() {
        // 真实样本：上游把整段 JSON 塞进 message。不能让解析器「美化」它，
        // 用户要看到的就是上游原话（`chatcmpl-…` 这类 request id 是报障时的唯一凭据）。
        val message = """429 {"error":{"message":"UID rate limit reached for TPM for UID (request_id: chatcmpl-abc)"}}"""
        val outcome = parse(
            """{"kind":"error","error":{"message":${Json.encodeToString(message)},"code":"RATE_LIMIT"}}""",
        )
        outcome as TurnOutcome.Failed
        assertEquals(message, outcome.message)
    }

    @Test
    fun errorWithoutStatusLeavesStatusNull() {
        // 实测里 status 只在部分 error 上出现（400/200/403/413 都见过，也有完全缺失的）。
        val outcome = parse("""{"kind":"error","error":{"message":"boom","code":"UNKNOWN"}}""")
        outcome as TurnOutcome.Failed
        assertNull(outcome.status)
        assertEquals("UNKNOWN", outcome.code)
    }

    @Test
    fun errorWithoutCodeKeepsNullCode() {
        val outcome = parse("""{"kind":"error","error":{"message":"boom"}}""")
        outcome as TurnOutcome.Failed
        assertNull(outcome.code)
    }

    @Test
    fun errorWithoutMessageStillParses() {
        // 只有 code 没有 message：卡片标题按 code 走，正文退回 code/status。
        val outcome = parse("""{"kind":"error","error":{"code":"TRANSPORT"}}""")
        outcome as TurnOutcome.Failed
        assertEquals("", outcome.message)
        assertEquals("TRANSPORT", outcome.code)
    }

    // ---------------------------------------------------------------- 截断

    @Test
    fun maxTokensIsTruncated() {
        assertEquals(TurnOutcome.Truncated, parse("""{"kind":"max-tokens"}"""))
    }

    // ---------------------------------------------------------------- 中止

    @Test
    fun abortedByUser() {
        val outcome = parse("""{"kind":"aborted","reason":{"kind":"user"}}""")
        assertEquals(TurnOutcome.Aborted(AbortCause.USER), outcome)
    }

    @Test
    fun abortedByParentAndDisposed() {
        assertEquals(
            TurnOutcome.Aborted(AbortCause.PARENT),
            parse("""{"kind":"aborted","reason":{"kind":"parent"}}"""),
        )
        assertEquals(
            TurnOutcome.Aborted(AbortCause.DISPOSED),
            parse("""{"kind":"aborted","reason":{"kind":"disposed"}}"""),
        )
    }

    @Test
    fun abortedWithUnknownCauseFallsBackToUnknown() {
        // 服务端以后加了新的中止原因（比如「被另一台设备接管」），不能让整轮
        // 直接消失——退成 UNKNOWN 并按中止渲染。
        assertEquals(
            TurnOutcome.Aborted(AbortCause.UNKNOWN),
            parse("""{"kind":"aborted","reason":{"kind":"taken-over"}}"""),
        )
    }

    @Test
    fun abortedWithoutReasonObject() {
        assertEquals(
            TurnOutcome.Aborted(AbortCause.UNKNOWN),
            parse("""{"kind":"aborted"}"""),
        )
    }

    // ---------------------------------------------------------------- 阻断与中断

    @Test
    fun blockedAndInterrupted() {
        assertEquals(TurnOutcome.Blocked, parse("""{"kind":"blocked"}"""))
        assertEquals(TurnOutcome.Interrupted, parse("""{"kind":"interrupted"}"""))
    }

    // ---------------------------------------------------------------- 未知形状

    @Test
    fun unknownKindIsPreservedNotSwallowed() {
        // 把未知 kind 原样留住：用户看到「本轮结束异常 · FUTURE_KIND」，
        // 比什么都不显示、或谎称「已完成」都强。
        val outcome = parse("""{"kind":"future-kind"}""")
        assertEquals(TurnOutcome.Unknown("future-kind"), outcome)
    }

    @Test
    fun plainStringReasonIsUnknownNotDropped() {
        // 兜底：认不出来的字符串形式 reason 也不能当成 null 静默丢弃。
        //
        // ⚠️ R043 审查 P2-1 之后，**已知** kind 的字符串要先被翻译成对应结局
        // （`interrupted` 现在解析成 `Interrupted`，见 `otherStringKindsAreTranslatedToo`），
        // 只有真认不出来的才落到 `Unknown`。这条用例改用未知识别符，
        // 否则它固化的是「正常结束也报故障」那个错误行为。
        val outcome = parse(""""something-we-do-not-know"""")
        assertEquals(TurnOutcome.Unknown("something-we-do-not-know"), outcome)
    }

    // ---------------------------------------------------------------- AbortCause.of

    @Test
    fun abortCauseOfIsCaseSensitiveToWire() {
        assertEquals(AbortCause.USER, AbortCause.of("user"))
        assertEquals(AbortCause.PARENT, AbortCause.of("parent"))
        assertEquals(AbortCause.DISPOSED, AbortCause.of("disposed"))
        // 大小写不是 wire 约定的一部分，出现就说明是别的东西 → UNKNOWN。
        assertEquals(AbortCause.UNKNOWN, AbortCause.of("USER"))
        assertEquals(AbortCause.UNKNOWN, AbortCause.of(null))
        assertEquals(AbortCause.UNKNOWN, AbortCause.of(""))
    }

    // ---------------------------------------------------------------- parseTurnOutcome 直传 JsonElement

    @Test
    fun acceptsRawJsonElement() {
        val element: JsonElement = Json.parseToJsonElement("""{"kind":"max-tokens"}""")
        assertEquals(TurnOutcome.Truncated, parseTurnOutcome(element))
    }
}
