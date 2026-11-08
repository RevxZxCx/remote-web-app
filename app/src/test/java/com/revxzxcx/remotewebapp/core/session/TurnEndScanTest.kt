package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从 `session/follow` 快照里捞最后一条 `turn/end`（R056 缺口 5）。
 *
 * ## 为什么需要这一层
 * 通知层只订阅轻量旁路（29 条白名单里的 emit 事件），拿不到 `turn/end`——
 * 那是 `session/follow` 流上的逐会话事件。所以「跑完的那一刻」要回头查一次快照。
 *
 * ## 守什么
 * - 取**最后**一条而不是第一条：窗口里通常留着好几轮的历史；
 * - `seq` 必须带出来，它是去重键（同一轮会被 status 与 error 两条路各查一次）；
 * - 信封缺 `seq` / `data` / `reason` 一律跳过而不是抛异常：这份 JSON 来自桌面端，
 *   版本比我们新时多出字段是常态，少字段也不该让整条链路崩掉。
 */
class TurnEndScanTest {

    private fun envelope(
        type: String = "turn/end",
        seq: Long? = 1L,
        reason: String = """{"kind":"completed"}""",
        turn: Long? = 1L,
    ): JsonObject {
        val parts = mutableListOf<String>()
        parts += """"type":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(type))}"""
        if (seq != null) parts += """"seq":$seq"""
        val data = mutableListOf<String>()
        if (turn != null) data += """"turn":$turn"""
        data += """"reason":$reason"""
        parts += """"data":{${data.joinToString(",")}}"""
        return Json.parseToJsonElement("{${parts.joinToString(",")}}") as JsonObject
    }

    @Test
    fun `窗口里最后一条 turn end 就是刚结束的那一轮`() {
        val found = lastTurnEnd(listOf(envelope(seq = 1), envelope(seq = 5), envelope(seq = 9)))
        assertEquals(9L, found?.seq)
    }

    @Test
    fun `没有 turn end 时返回 null 不抛异常`() {
        assertNull(lastTurnEnd(listOf(envelope(type = "turn/start"), envelope(type = "step/end"))))
    }

    @Test
    fun `空窗口返回 null`() {
        assertNull(lastTurnEnd(emptyList()))
    }

    @Test
    fun `带上 turn 编号 方便与对话页对齐`() {
        assertEquals(7L, lastTurnEnd(listOf(envelope(seq = 3, turn = 7)))?.turn)
    }

    @Test
    fun `turn 缺失不影响取结局 线上真的有这种信封`() {
        // turnEndNotice 的 KDoc 记过这个坑：把 turn 当出卡前提会丢掉整条结局。
        val found = lastTurnEnd(listOf(envelope(seq = 3, turn = null)))
        assertEquals(3L, found?.seq)
        assertNull(found?.turn)
    }

    @Test
    fun `最新那条缺 seq 时返回 null 绝不回退到更早那一轮`() {
        // 没有 seq 就无法判断「这一轮报过没」。更重要的是**不能往前找一条替补**：
        // 拿上一轮的 seq 去比账，多半命中「已经报过」→ 用户一条通知都没有；
        // 没命中时则会用很早以前那一轮的结局编一条「任务结束」，说的事情与当前
        // 这一轮无关（审查 A11）。
        assertNull(lastTurnEnd(listOf(envelope(seq = 2), envelope(seq = null))))
    }

    @Test
    fun `只有缺 seq 的信封时返回 null 而不是编号 0`() {
        assertNull(lastTurnEnd(listOf(envelope(seq = null))))
    }

    @Test
    fun `没有 data 的信封跳过`() {
        val raw = Json.parseToJsonElement("""{"type":"turn/end","seq":4}""") as JsonObject
        assertNull(lastTurnEnd(listOf(raw)))
    }

    @Test
    fun `最新那条结局解析不出时返回 null 不能拿上一轮顶替`() {
        val weird = envelope(seq = 8, reason = """{"kind":"quantum"}""")
        // kind 认不出 → Unknown（要报）；reason 根本解析不出 → 这一轮结局不可知。
        val unparsable = envelope(seq = 9, reason = """42""")
        // 最新一条不可知 → 返回 null，**不回退**到 seq=8 那条（审查 A11）。
        assertNull(lastTurnEnd(listOf(weird, unparsable)))
        // 只有 seq=8 时它自己是能报的，证明上一条断言不是因为整段解析坏掉。
        val only = lastTurnEnd(listOf(weird))
        assertEquals(8L, only?.seq)
        assertTrue(only?.outcome is TurnOutcome.Unknown)
    }

    @Test
    fun `旧格式的字符串 reason 也认 回放历史里真的存在`() {
        val found = lastTurnEnd(listOf(envelope(seq = 2, reason = """"completed"""")))
        assertEquals(TurnOutcome.Completed, found?.outcome)
    }

    @Test
    fun `截断结局要能解析出来 这是本缺口的主要目标之一`() {
        val found = lastTurnEnd(listOf(envelope(seq = 2, reason = """{"kind":"max-tokens"}""")))
        assertEquals(TurnOutcome.Truncated, found?.outcome)
    }

    @Test
    fun `失败的结局要带出原因`() {
        val found = lastTurnEnd(
            listOf(
                envelope(
                    seq = 2,
                    reason = """{"kind":"error","error":{"message":"你还没有订阅","code":"request_params_invalid","status":400}}""",
                ),
            ),
        )
        val outcome = found?.outcome as TurnOutcome.Failed
        assertEquals("你还没有订阅", outcome.message)
        assertEquals("request_params_invalid", outcome.code)
        assertEquals(400, outcome.status)
    }

    @Test
    fun `中止结局要带出来源 用户自己停的不该通知`() {
        val found = lastTurnEnd(
            listOf(envelope(seq = 2, reason = """{"kind":"aborted","reason":{"kind":"user"}}""")),
        )
        assertEquals(TurnOutcome.Aborted(AbortCause.USER), found?.outcome)
    }

    @Test
    fun `中间夹杂别的事件不影响定位`() {
        val found = lastTurnEnd(
            listOf(
                envelope(seq = 1),
                envelope(type = "user/message", seq = 2),
                envelope(seq = 5, reason = """{"kind":"max-tokens"}"""),
                envelope(type = "step/end", seq = 6),
            ),
        )
        assertEquals(5L, found?.seq)
        assertEquals(TurnOutcome.Truncated, found?.outcome)
    }
}
