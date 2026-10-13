package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatArgs] 的单测 —— **本项目最容易出错的地方**。
 *
 * 为什么这些断言值得写：Typert 是严格 schema，参数 wire 名拼错不会被「宽容处理」，而是直接
 * `gateway/arguments-invalid: args fields do not match the descriptor: missing "x"; unexpected "y"`。
 * M1/M2 已踩过两次（`session/list` 用 `_request` 而非 `request`；时间字段线上是 ISO 字符串）。
 * 这里把四个 chat 方法的形状钉死，改错立刻红灯。
 *
 * 期望值来自 `dsh-api-session-controller/lib/typert.remote-client.js` 的 descriptor
 * （每个参数的 `name` 与 `wire`）以及各参数的 zod schema。
 *
 * 注意这些测试打的是**生产代码** [ChatArgs]（不是测试里复刻的一份），所以它真能拦住回归。
 */
class ChatArgsTest {

    @Test
    fun `follow 的顶层键恰好是 request`() {
        // 多一个键服务端就报 unexpected；少一个就报 missing。
        assertEquals(setOf("request"), ChatArgs.follow("session-1").keys)
    }

    @Test
    fun `follow 的 request 形状`() {
        val request = ChatArgs.follow("session-abc").obj("request")
        assertEquals(setOf("address", "assistantStream", "maxMessages"), request.keys)
        val address = request.obj("address")
        assertEquals("session", address.str("kind"))
        assertEquals("session-abc", address.str("sessionId"))
        // `assistantStream` 是 z.literal(true)：必须是布尔 true，不是字符串 "true"。
        assertEquals(JsonPrimitive(true), request["assistantStream"])
        assertEquals(50, request.int("maxMessages"))
    }

    @Test
    fun `follow 的 maxMessages 可覆盖`() {
        val request = ChatArgs.follow("s", maxMessages = 200).obj("request")
        assertEquals(200, request.int("maxMessages"))
    }

    @Test
    fun `page 的 request 形状`() {
        val request = ChatArgs.page("session-1", throughSeq = 42, beforeSeq = 7).obj("request")
        assertEquals(setOf("address", "throughSeq", "beforeSeq", "maxMessages"), request.keys)
        assertEquals(42L, request.long("throughSeq"))
        assertEquals(7L, request.long("beforeSeq"))
        assertEquals("session-1", request.obj("address").str("sessionId"))
    }

    @Test
    fun `page 的 throughSeq 允许 -1 表示到日志头`() {
        // 官方 `validatePageRequest`：throughSeq 必须 >= -1（-1 = 到日志头）。
        val request = ChatArgs.page("s", throughSeq = -1, beforeSeq = 0).obj("request")
        assertEquals(-1L, request.long("throughSeq"))
    }

    @Test
    fun `prompt 的 request 形状`() {
        val request = ChatArgs.prompt("req-1", "session-1", "你好").obj("request")
        assertEquals(setOf("requestId", "sessionId", "mode", "content"), request.keys)
        assertEquals("req-1", request.str("requestId"))
        assertEquals("session-1", request.str("sessionId"))
        assertEquals("queue", request.str("mode"))
    }

    @Test
    fun `prompt 的 content 是单个文本块数组`() {
        val content = ChatArgs.prompt("r", "s", "你好")["request"].let { it as JsonObject }["content"] as JsonArray
        assertEquals(1, content.size)
        val block = content[0] as JsonObject
        assertEquals("text", block.str("type"))
        assertEquals("你好", block.str("text"))
    }

    @Test
    fun `prompt 默认是 queue 模式`() {
        assertEquals("queue", ChatArgs.prompt("r", "s", "x").obj("request").str("mode"))
    }

    @Test
    fun `prompt 的 steer 模式`() {
        val request = ChatArgs.prompt("r", "s", "插话", ChatArgs.PromptMode.Steer).obj("request")
        assertEquals("steer", request.str("mode"))
    }

    @Test
    fun `prompt 不带 clientTimeZone 时不出现该键`() {
        // 未定义的可选参数不能发送，否则服务端报 unexpected。
        val request = ChatArgs.prompt("r", "s", "x", clientTimeZone = null).obj("request")
        assertFalse(request.containsKey("clientTimeZone"))
    }

    @Test
    fun `prompt 带 clientTimeZone 时出现该键`() {
        val request = ChatArgs.prompt("r", "s", "x", clientTimeZone = "Asia/Shanghai").obj("request")
        assertEquals("Asia/Shanghai", request.str("clientTimeZone"))
    }

    @Test
    fun `cancel 的 request 只有 sessionId`() {
        val args = ChatArgs.cancel("session-xyz")
        assertEquals(setOf("request"), args.keys)
        val request = args.obj("request")
        assertEquals(setOf("sessionId"), request.keys)
        assertEquals("session-xyz", request.str("sessionId"))
    }

    @Test
    fun `四个 chat 方法都用 request 而不是 _request`() {
        // 与 `session/list` 的关键区别：只有 list 用下划线前缀（M2 实测）。
        listOf(
            ChatArgs.follow("s"),
            ChatArgs.page("s", 1, 0),
            ChatArgs.prompt("r", "s", "x"),
            ChatArgs.cancel("s"),
        ).forEach { args ->
            assertTrue("顶层键应含 request：$args", args.containsKey("request"))
            assertFalse("不应含 _request：$args", args.containsKey("_request"))
        }
    }

    @Test
    fun `PromptMode 的 wire 值与服务端字面量一致`() {
        // 官方 schema：z.union([z.literal("queue"), z.literal("steer")])。
        assertEquals("queue", ChatArgs.PromptMode.Queue.wire)
        assertEquals("steer", ChatArgs.PromptMode.Steer.wire)
    }

    @Test
    fun `默认 maxMessages 与服务端默认值一致`() {
        // history.js:59 DEFAULT_MAX_MESSAGES = 50。
        assertEquals(50, ChatArgs.DEFAULT_MAX_MESSAGES)
    }

    @Test
    fun `address 用 session 形态而不是 subagent`() {
        // 子代理地址需要 parentSessionId/childSessionId/mode，M3 不支持；
        // 用错会被服务端回 session/agent-busy 或 subagent/unauthorized。
        val address = ChatArgs.follow("s").obj("request").obj("address")
        assertEquals(setOf("kind", "sessionId"), address.keys)
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}
