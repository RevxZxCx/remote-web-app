package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionEventParser] 的单测。
 *
 * 形状全部来自源码（`SessionEventMap` + `MessageBase` / `ContentBlockMap` 声明），
 * 以及 `assertSessionWireEvent` 的**封闭键集**规则。
 */
class SessionEventParserTest {

    private val parser = SessionEventParser()
    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `解析 user-message`() {
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":10,"time":1791875000000,
                 "data":{"id":"msg-1","role":"user",
                   "content":[{"type":"text","text":"你好"}],
                   "source":{"kind":"user"}}}
                """.trimIndent(),
            ),
        )
        val user = item as ChatItem.User
        assertEquals(10L, user.seq)
        assertEquals(1791875000000L, user.time)
        assertEquals("你好", user.text)
        assertEquals(1, user.blocks.size)
    }

    @Test
    fun `解析 assistant-message 并取 turn 与 step`() {
        val item = parser.parse(
            obj(
                """
                {"type":"assistant/message","seq":20,"time":1791875001000,
                 "data":{"turn":3,"step":1,
                   "message":{"id":"m","role":"assistant",
                     "content":[{"type":"text","text":"好的"}],
                     "source":{"kind":"model","provider":"x","model":"y"}},
                   "stream":[]}}
                """.trimIndent(),
            ),
        )
        val assistant = item as ChatItem.Assistant
        assertEquals(3L, assistant.turn)
        assertEquals(1L, assistant.step)
        assertEquals("好的", (assistant.blocks.single() as ContentBlock.Text).text)
        assertFalse(assistant.interrupted)
    }

    @Test
    fun `assistant-message 的 interrupted 标记被读出`() {
        val item = parser.parse(
            obj(
                """
                {"type":"assistant/message","seq":21,"time":1,
                 "data":{"turn":0,"step":0,"interrupted":true,
                   "message":{"id":"m","role":"assistant",
                     "content":[{"type":"text","text":"被打断"}],
                     "source":{"kind":"model"}}}}
                """.trimIndent(),
            ),
        )
        assertTrue((item as ChatItem.Assistant).interrupted)
    }

    @Test
    fun `只带工具调用没有文本的助手消息也要保留`() {
        // 这种消息是那次工具调用的载体，丢掉会让用户看不到工具名。
        val item = parser.parse(
            obj(
                """
                {"type":"assistant/message","seq":22,"time":1,
                 "data":{"turn":0,"step":0,
                   "message":{"id":"m","role":"assistant",
                     "content":[{"type":"tool-call","id":"c1","name":"read",
                                 "arguments":"{\"path\":\"a.kt\"}"}],
                     "source":{"kind":"model"}}}}
                """.trimIndent(),
            ),
        )
        val assistant = item as ChatItem.Assistant
        val call = assistant.blocks.single() as ContentBlock.ToolCall
        assertEquals("read", call.name)
        assertEquals("{\"path\":\"a.kt\"}", call.arguments)
    }

    @Test
    fun `解析 tool-call 事件`() {
        val item = parser.parse(
            obj(
                """
                {"type":"tool/call","seq":30,"time":5,
                 "data":{"turn":1,"step":2,"callId":"call-9","name":"edit",
                         "arguments":"{\"file_path\":\"x.md\"}"}}
                """.trimIndent(),
            ),
        )
        val call = item as ChatItem.ToolCall
        assertEquals("call-9", call.callId)
        assertEquals("edit", call.name)
        assertEquals(2L, call.step)
        // `arguments` 保持原始 JSON 字符串，M3.5 才解析它取 file_path。
        assertEquals("{\"file_path\":\"x.md\"}", call.arguments)
    }

    @Test
    fun `解析 tool-result 并识别错误`() {
        val item = parser.parse(
            obj(
                """
                {"type":"tool/result","seq":31,"time":6,
                 "data":{"turn":1,"step":2,
                   "message":{"id":"m","role":"tool","toolCallId":"call-9",
                     "content":[{"type":"text","text":"ok"}],
                     "source":{"kind":"tool","callId":"call-9"}},
                   "error":{"name":"Error","code":"E_FAIL"}}}
                """.trimIndent(),
            ),
        )
        val result = item as ChatItem.ToolResult
        assertEquals("call-9", result.callId)
        assertEquals("ok", result.text)
        assertTrue(result.isError)
    }

    @Test
    fun `tool-result 从 message 里取 toolCallId`() {
        val item = parser.parse(
            obj(
                """
                {"type":"tool/result","seq":32,"time":6,
                 "data":{"turn":0,"step":0,
                   "message":{"id":"m","role":"tool","toolCallId":"from-message",
                     "content":[{"type":"text","text":"out"}],
                     "source":{"kind":"tool","callId":"from-message"}}}}
                """.trimIndent(),
            ),
        )
        assertEquals("from-message", (item as ChatItem.ToolResult).callId)
    }

    @Test
    fun `turn-end 变成提示并带事件类型`() {
        val item = parser.parse(
            obj("""{"type":"turn/end","seq":40,"time":9,"data":{"turn":2,"reason":"completed"}}"""),
        )
        val notice = item as ChatItem.Notice
        assertEquals("turn/end", notice.eventType)
        assertEquals(2L, notice.turn)
        assertTrue(notice.text.contains("completed"))
    }

    @Test
    fun `噪声事件不产生卡片`() {
        // 这些在日志里占比很高，绝不能出现在聊天气泡里。
        val noise = listOf(
            """{"type":"turn/start","seq":1,"time":1,"data":{"turn":0}}""",
            """{"type":"step/start","seq":2,"time":1,"data":{"turn":0,"step":0}}""",
            """{"type":"step/end","seq":3,"time":1,"data":{"turn":0,"step":0}}""",
            """{"type":"request/header","seq":4,"time":1,"data":{"reason":"initial"}}""",
            """{"type":"request/context","seq":5,"time":1,"data":{}}""",
            """{"type":"system/message","seq":6,"time":1,"data":{"turn":0,"step":0,"message":{}}}""",
            """{"type":"developer/message","seq":7,"time":1,"data":{"turn":0,"step":0,"message":{}}}""",
            """{"type":"sandbox/mode","seq":8,"time":1,"data":{"mode":"workspace-write"}}""",
        )
        noise.forEach { text ->
            assertNull("应当忽略：$text", parser.parse(obj(text)))
        }
    }

    @Test
    fun `缺 seq 的事件被拒`() {
        assertNull(parser.parse(obj("""{"type":"user/message","time":1,"data":{}}""")))
    }

    @Test
    fun `负 seq 被拒`() {
        assertNull(parser.parse(obj("""{"type":"user/message","seq":-1,"time":1,"data":{}}""")))
    }

    @Test
    fun `缺 type 的事件被拒`() {
        assertNull(parser.parse(obj("""{"seq":1,"time":1,"data":{}}""")))
    }

    @Test
    fun `data 缺失时退化成空对象而不是崩溃`() {
        // `user/message` 没有 content → 没有可渲染内容 → 返回 null。
        assertNull(parser.parse(obj("""{"type":"user/message","seq":1,"time":1}""")))
    }

    @Test
    fun `image 块解析出附件信息`() {
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":50,"time":1,
                 "data":{"content":[
                   {"type":"text","text":"看图"},
                   {"type":"image","offloaded":true,
                    "attachment":{"attachmentId":"att-1","mediaType":"image/png",
                                  "bytes":123,"width":10,"height":20,"name":"a.png"}}]}}
                """.trimIndent(),
            ),
        )
        val blocks = (item as ChatItem.User).blocks
        val image = blocks[1] as ContentBlock.Image
        assertEquals("att-1", image.attachmentId)
        assertEquals("image/png", image.mediaType)
        assertEquals("a.png", image.name)
        assertTrue(image.offloaded)
    }

    @Test
    fun `file 块解析出附件名`() {
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":51,"time":1,
                 "data":{"content":[{"type":"file",
                   "attachment":{"attachmentId":"f1","name":"报告.pdf","bytes":9}}]}}
                """.trimIndent(),
            ),
        )
        val file = (item as ChatItem.User).blocks.single() as ContentBlock.File
        assertEquals("报告.pdf", file.name)
    }

    @Test
    fun `未知内容块落进 Unknown 而不是丢弃`() {
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":52,"time":1,
                 "data":{"content":[{"type":"text","text":"x"},
                                    {"type":"tool-addition","toolName":"t"}]}}
                """.trimIndent(),
            ),
        )
        val blocks = (item as ChatItem.User).blocks
        assertEquals(2, blocks.size)
        assertEquals("tool-addition", (blocks[1] as ContentBlock.Unknown).type)
    }

    @Test
    fun `image 块缺 attachment 时跳过该块而不是崩溃`() {
        val blocks = parser.contentBlocks(
            json.parseToJsonElement("""[{"type":"image"}]"""),
        )
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `content 不是数组时返回空列表`() {
        assertTrue(parser.contentBlocks(json.parseToJsonElement("""{"a":1}""")).isEmpty())
        assertTrue(parser.contentBlocks(null).isEmpty())
    }

    @Test
    fun `parseEvent 保留未知事件类型`() {
        // 服务端的 SessionEventMap 是 merge-extensible 的；未知类型也要能解析出信封，
        // 只是不产生卡片（toChatItem 返回 null）。
        val event = parser.parseEvent(
            obj("""{"type":"future/event","seq":7,"time":123,"data":{"x":1}}"""),
        )
        assertEquals("future/event", event?.type)
        assertEquals(123L, event?.time)
    }

    @Test
    fun `session-title 变成提示`() {
        val item = parser.parse(
            obj("""{"type":"session/title","seq":60,"time":1,"data":{"title":"新标题"}}"""),
        )
        assertTrue((item as ChatItem.Notice).text.contains("新标题"))
    }
}
