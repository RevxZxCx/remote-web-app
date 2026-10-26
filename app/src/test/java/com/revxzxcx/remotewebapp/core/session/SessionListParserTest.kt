package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionListParser] 的单测。
 *
 * 用的 JSON 是从**真实运行实例**上抓下来的形状（M2 探针，见 `测试记录.md` T009），
 * 不是照类型声明编的——这一点很关键，因为实测发现 `updatedAt` 是数字而
 * `workspace/follow` 是 ISO 字符串、`session/list` 含子代理会话。
 */
class SessionListParserTest {

    private val parser = SessionListParser()
    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `解析真实形状的条目`() {
        val result = parser.parse(
            obj(
                """
                {"items":[{
                  "sessionId":"session-ea0e57ee-1232-4e1c-8edd-d011a1d18eee",
                  "updatedAt":1791875609869,
                  "agentAvailable":true,
                  "running":false,
                  "blank":false,
                  "parentSessionId":"session-f3c843ab-d68b-4f5f-bce9-90c07ddae30d",
                  "cwd":"F:\\bot",
                  "projections":{
                    "kind":"sequenced",
                    "asOfSeq":4518,
                    "values":{
                      "title":"隧道问题修复分支",
                      "sessionListMetadata":{"blank":false,"lastPromptAt":1791875700090},
                      "modelSelection":{"lastUsed":{"provider":"stepfun","model":"step-5-preview"},
                                        "next":{"provider":"stepfun","model":"step-5-preview"}}
                    }
                  }
                }]}
                """.trimIndent(),
            ),
        )

        assertEquals(1, result.items.size)
        val s = result.items[0]
        assertEquals("session-ea0e57ee-1232-4e1c-8edd-d011a1d18eee", s.sessionId)
        assertEquals(1791875609869L, s.updatedAt)
        assertTrue(s.agentAvailable)
        assertFalse(s.running)
        assertFalse(s.blank)
        assertEquals("session-f3c843ab-d68b-4f5f-bce9-90c07ddae30d", s.parentSessionId)
        assertEquals("F:\\bot", s.cwd)
        assertEquals("隧道问题修复分支", s.title)
        assertEquals(1791875700090L, s.lastPromptAt)
        assertEquals("step-5-preview", s.lastModel)
        assertFalse(s.isSubagent)
    }

    @Test
    fun `updatedAt 是数字而不是 ISO 字符串`() {
        // 与 workspace/follow 不同：这里服务端给数字。DshTime 两种都要吃。
        val s = parser.parse(
            obj("""{"items":[{"sessionId":"s1","updatedAt":1791875609869}]}"""),
        ).items.single()
        assertEquals(1791875609869L, s.updatedAt)
    }

    @Test
    fun `updatedAt 为 ISO 字符串时也能解析`() {
        // 防御性：同一服务端两套形态，将来若统一成字符串不该崩。
        val s = parser.parse(
            obj("""{"items":[{"sessionId":"s1","updatedAt":"2026-10-13T07:15:00.090Z"}]}"""),
        ).items.single()
        assertEquals(1791875700090L, s.updatedAt)
    }

    @Test
    fun `origin 为 subagent 时 isSubagent 为真`() {
        val s = parser.parse(
            obj("""{"items":[{"sessionId":"1baa4707-5897-4718-bf2a-7fa382893581","origin":"subagent"}]}"""),
        ).items.single()
        assertTrue(s.isSubagent)
        assertEquals("subagent", s.origin)
    }

    @Test
    fun `agentAvailable 为 false 是常态而不是错误`() {
        // 实测 47 条里 40 条是 false（没有活跃 Agent 附着）。解析必须保留这个值，
        // 但 UI 侧不能据此报错——这里只保证值被正确读出来。
        val result = parser.parse(
            obj(
                """{"items":[
                     {"sessionId":"a","agentAvailable":false},
                     {"sessionId":"b","agentAvailable":true}]}""",
            ),
        )
        assertFalse(result.items[0].agentAvailable)
        assertTrue(result.items[1].agentAvailable)
    }

    @Test
    fun `缺失 sessionId 的条目被丢弃`() {
        val result = parser.parse(
            obj("""{"items":[{"sessionId":"ok"},{"updatedAt":1},{"sessionId":""}]}"""),
        )
        assertEquals(listOf("ok"), result.items.map { it.sessionId })
    }

    @Test
    fun `缺失 projections 时退化为空标题而不是崩掉`() {
        val s = parser.parse(obj("""{"items":[{"sessionId":"s1"}]}""")).items.single()
        assertEquals("", s.title)
        assertNull(s.lastPromptAt)
        assertNull(s.lastModel)
        assertNull(s.cwd)
        assertNull(s.origin)
        assertFalse(s.agentAvailable)
        assertEquals(0L, s.updatedAt)
    }

    @Test
    fun `projections 里的未知字段被忽略`() {
        // 实测 projections.values 有 23 个键；将来服务端还会加。绝不能因此崩掉。
        val s = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","projections":{"kind":"cached","asOfSeq":1,
                   "values":{"title":"T","tokenUsage":{"a":1},"goal":null,
                             "turnOutline":[{"turn":1}],"agentTeam":{"x":1},
                             "brandNewProjection":{"deep":{"nested":true}}}}}]}""",
            ),
        ).items.single()
        assertEquals("T", s.title)
    }

    @Test
    fun `items 不是数组时返回空结果`() {
        assertTrue(parser.parse(obj("""{"items":"nope"}""")).isEmpty)
        assertTrue(parser.parse(obj("""{}""")).isEmpty)
    }

    @Test
    fun `lastPromptAt 为 null 时不产生时间戳`() {
        val s = parser.parse(
            obj("""{"items":[{"sessionId":"s1","projections":{"values":{"sessionListMetadata":{"blank":true,"lastPromptAt":null}}}}]}"""),
        ).items.single()
        assertNull(s.lastPromptAt)
    }

    @Test
    fun `没有标题时 displayName 有稳定兜底`() {
        val s = parser.parse(obj("""{"items":[{"sessionId":"session-abcdef123456"}]}""")).items.single()
        assertEquals("未命名会话 ef123456", s.displayName)
    }

    @Test
    fun `有标题时 displayName 用标题`() {
        val s = parser.parse(
            obj("""{"items":[{"sessionId":"s1","projections":{"values":{"title":"我的会话"}}}]}"""),
        ).items.single()
        assertEquals("我的会话", s.displayName)
    }

    @Test
    fun `有活跃提问时标记为需要关注`() {
        // R049：`userQuestions.active` 非空 = 有提问在等回答。
        val s = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","projections":{"values":{
                    "userQuestions":{"active":[{"callId":"c1"}],"settled":[]}}}}]}""",
            ),
        ).items.single()
        assertTrue(s.awaitingAttention)
    }

    @Test
    fun `有排队消息时标记为需要关注`() {
        // `inbox.next-turn` 是「排队但还没发出去」的用户消息。
        val s = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","projections":{"values":{
                    "inbox":{"next-turn":[{"id":"m1"}],"next-step":[]}}}}]}""",
            ),
        ).items.single()
        assertTrue(s.awaitingAttention)
    }

    @Test
    fun `空提问与空排队不算需要关注`() {
        val s = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","projections":{"values":{
                    "userQuestions":{"active":[],"settled":[]},
                    "inbox":{"next-turn":[],"next-step":[]}}}}]}""",
            ),
        ).items.single()
        assertFalse(s.awaitingAttention)
    }

    @Test
    fun `没有投影时不标记需要关注`() {
        val s = parser.parse(obj("""{"items":[{"sessionId":"s1"}]}""")).items.single()
        assertFalse(s.awaitingAttention)
    }

    @Test
    fun `两条未读提醒都不从 session list 解析`() {
        // `session/list` 顶层只有 8 个字段，既没有 outcome 也没有「用户看没看过」。
        // 即便服务端将来塞同名字段进来，这里也不该认——两个标记只由 CompletionLedger
        // 从事件流边沿写入（R049，用户 m33325：「这个点不是常驻的」）。
        val s = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","completionUnread":true,"failureUnread":true}]}""",
            ),
        ).items.single()
        assertFalse(s.completionUnread)
        assertFalse(s.failureUnread)
    }

    // ── T076：用量随同一次响应切出来 ────────────────────────────────────────

    @Test
    fun `用量从 projections 切出来并挂到对应会话`() {
        // 四条用量投影就在同一条记录里。这条用例守的是「顺手切出来」这件事本身：
        // 若 parse 忘了填 usages，首页洞察会因为拿不到任何用量而整页空白
        // （真机上就发生过：全量刷新路径漏传 usages，抽屉显示「没有可统计的会话」）。
        val result = parser.parse(
            obj(
                """{"items":[{"sessionId":"s1","updatedAt":100,
                     "projections":{"values":{
                       "tokenUsage":{"uncachedInputTokens":100,"outputTokens":200,
                                     "cacheReadTokens":300,"cacheWriteTokens":400},
                       "sessionStats":{"turns":3,"steps":9,"llmMs":1000,"toolMs":500}}}}]}""",
            ),
        )
        val usage = result.usages.getValue("s1")
        assertEquals(100L, usage.tokens.uncachedInput)
        assertEquals(200L, usage.tokens.output)
        assertEquals(300L, usage.tokens.cacheRead)
        assertEquals(400L, usage.tokens.cacheWrite)
        assertEquals(1000L, usage.tokens.total)
        assertEquals(3, usage.turns)
        assertEquals(9, usage.steps)
    }

    @Test
    fun `读不到用量的会话不进 usages 表`() {
        // 「没有数据」与「用量是 0」是两件事：塞一条全 0 进去会把分母撑大，
        // 界面就会出现「2 个会话，合计 0 token」这种自相矛盾的读数。
        val result = parser.parse(
            obj(
                """{"items":[
                     {"sessionId":"withData","updatedAt":100,
                      "projections":{"values":{"tokenUsage":{"cacheReadTokens":7}}}},
                     {"sessionId":"noData","updatedAt":200},
                     {"sessionId":"emptyValues","updatedAt":300,"projections":{"values":{}}}]}""",
            ),
        )
        assertEquals(setOf("withData"), result.usages.keys)
        // 摘要里三条都还在：用量缺失不影响列表本身。
        assertEquals(3, result.items.size)
    }

    @Test
    fun `缺 sessionId 的条目既不进 items 也不进 usages`() {
        // 没有 id 就无法成为列表项，用量表也不该出现一个空键。
        val result = parser.parse(
            obj(
                """{"items":[{"updatedAt":100,
                     "projections":{"values":{"tokenUsage":{"cacheReadTokens":7}}}}]}""",
            ),
        )
        assertTrue(result.items.isEmpty())
        assertTrue(result.usages.isEmpty())
    }

    @Test
    fun `topLevel 排除子代理并按时间降序`() {
        val result = parser.parse(
            obj(
                """{"items":[
                     {"sessionId":"old","updatedAt":100,"cwd":"F:\\a"},
                     {"sessionId":"sub","updatedAt":999,"origin":"subagent"},
                     {"sessionId":"new","updatedAt":500,"cwd":"F:\\a"}]}""",
            ),
        )
        assertEquals(listOf("new", "old"), result.topLevel.map { it.sessionId })
    }
}
