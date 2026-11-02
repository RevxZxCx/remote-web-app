package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.protocol.TypertException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `session/create` 的参数形状与结果解析（R055）。
 *
 * 这些断言逐条对应 2026-11-02 的真实探针结果（`测试记录.md` T086-1）。为什么值得单独钉一遍：
 * 参数名写错（`request` → `_request`）、空值写成空串、`agentPreset` 放错层级，服务端都
 * 会**接受或拒绝得不明显**——前两种是 200 + 错误信封，后一种报 `unexpected "agentPreset"`。
 * 单测跑得比真机快几百倍，把它挡在这里。
 */
class SessionCreateArgsTest {

    private fun argsOf(json: JsonObject): JsonObject =
        json["request"]?.jsonObject ?: fail("缺少 request 包裹：$json").let { error("") }

    @Test
    fun `参数必须包在 request 里 而不是 session list 的 _request`() {
        val args = SessionCreateArgs.create()
        assertTrue("必须有 request 键", args.containsKey("request"))
        assertFalse("session create 不用 _request", args.containsKey("_request"))
    }

    @Test
    fun `什么都不传时 request 是空对象 不能写空串`() {
        val body = argsOf(SessionCreateArgs.create())
        assertEquals(emptySet<String>(), body.keys)
    }

    @Test
    fun `只给模式时 只出现 agentPreset 一个键`() {
        val body = argsOf(SessionCreateArgs.create(agentPreset = AgentPreset.PTC))
        assertEquals(setOf("agentPreset"), body.keys)
        assertEquals("ptc", body["agentPreset"]?.jsonPrimitive?.content)
    }

    @Test
    fun `workspaceId 为空串时不能写进参数 空串会让服务端去 mkdir 空路径`() {
        val body = argsOf(SessionCreateArgs.create(workspaceId = ""))
        assertFalse("空串必须省略", body.containsKey("workspaceId"))
        val body2 = argsOf(SessionCreateArgs.create(workspaceId = "   "))
        assertFalse("纯空白也必须省略", body2.containsKey("workspaceId"))
    }

    @Test
    fun `title 为空串时省略`() {
        val body = argsOf(SessionCreateArgs.create(title = ""))
        assertFalse(body.containsKey("title"))
    }

    @Test
    fun `四档模式发给服务端的是固定的 wire 值`() {
        assertEquals("standard", AgentPreset.STANDARD.wire)
        assertEquals("ptc", AgentPreset.PTC.wire)
        assertEquals("minimal", AgentPreset.MINIMAL.wire)
        assertEquals("cordis", AgentPreset.CORDIS.wire)
        // wire 必须等于枚举名小写，但不靠现算——改名字时这里会红，提醒同步服务端。
        AgentPreset.entries.forEach {
            assertEquals(it.name.lowercase(), it.wire)
        }
    }

    @Test
    fun `未知 wire 值退回默认档而不是抛异常`() {
        assertEquals(AgentPreset.DEFAULT, AgentPreset.fromWire(null))
        assertEquals(AgentPreset.DEFAULT, AgentPreset.fromWire("bogus"))
        assertEquals(AgentPreset.DEFAULT, AgentPreset.fromWire(""))
        assertEquals(AgentPreset.CORDIS, AgentPreset.fromWire("cordis"))
    }

    @Test
    fun `四档都有非空的标签和说明`() {
        AgentPreset.entries.forEach {
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
            assertTrue("${it.name} 缺说明", it.blurb.isNotBlank())
        }
    }
}

/** [SessionCreateRepository.parse] 的分支。用一个不发请求的解析入口单独测。 */
class SessionCreateParseTest {

    /**
     * 借 `SessionCreateRepository` 的 parse：它需要一个 connection，但 parse 不碰它。
     *
     * 这里不构造假 DshConnection —— 那需要 OkHttp 与凭据；`parse` 是 `internal` 且只用
     * 传入的 JsonElement，直接 new 一个 connection 为 null 语义的实例不可行，于是用
     * `java.lang.reflect`？不。改用「把 parse 的逻辑通过一个公开入口暴露」更干净：
     * 见下面的 companion。这里直接调用 [SessionCreateParse.parse]。
     */
    private fun parse(json: String) = SessionCreateParse.parse(
        kotlinx.serialization.json.Json.parseToJsonElement(json),
    )

    @Test
    fun `正常响应解析出 sessionId 与回显的模式`() {
        val out = parse("""{"sessionId":"session-abc","agentPreset":"ptc"}""")
        assertEquals("session-abc", out.sessionId)
        assertEquals(AgentPreset.PTC, out.agentPreset)
    }

    @Test
    fun `服务端没回 agentPreset 时退回默认档 但 sessionId 必须有`() {
        val out = parse("""{"sessionId":"session-abc"}""")
        assertEquals(AgentPreset.DEFAULT, out.agentPreset)
    }

    @Test
    fun `缺 sessionId 是硬错误 不能返回空串让上层拿假 id 去订阅`() {
        try {
            parse("""{"agentPreset":"ptc"}""")
            fail("缺 sessionId 必须抛")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_BAD_ENVELOPE, e.code)
        }
    }

    @Test
    fun `sessionId 是空串同样是硬错误`() {
        try {
            parse("""{"sessionId":"","agentPreset":"ptc"}""")
            fail("空 sessionId 必须抛")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_BAD_ENVELOPE, e.code)
        }
    }

    @Test
    fun `sessionId 不是字符串时也是硬错误`() {
        try {
            parse("""{"sessionId":123}""")
            fail("数字 sessionId 必须抛")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_BAD_ENVELOPE, e.code)
        }
    }

    @Test
    fun `响应不是对象时也是硬错误`() {
        try {
            parse("""["session-abc"]""")
            fail("数组必须抛")
        } catch (e: TypertException) {
            assertEquals(TypertException.CODE_BAD_ENVELOPE, e.code)
        }
    }

    @Test
    fun `回显了不认识的模式时退回默认档 而不是崩`() {
        val out = parse("""{"sessionId":"session-abc","agentPreset":"brand-new"}""")
        assertEquals(AgentPreset.DEFAULT, out.agentPreset)
        assertEquals("session-abc", out.sessionId)
    }
}
