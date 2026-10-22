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
    fun `turn-end 正常结束不产生任何条目`() {
        // R043 之前的实现把每次 turn/end 都变成一条「第 N 轮结束」提示；
        // 现在正常结束是噪声（它在真实日志里占 646 次里的 465 次），
        // 只有非正常结局才落成 TurnResult 卡片。
        assertNull(
            parser.parse(
                obj("""{"type":"turn/end","seq":40,"time":9,"data":{"turn":2,"reason":{"kind":"completed"}}}"""),
            ),
        )
    }

    @Test
    fun `turn-end 失败落成带上游原文的结局卡`() {
        // R043 第 1 条的核心回归：`reason` 是对象，旧实现用字符串扩展读它
        // 恒得 null，于是模型报错被渲染成中性的「第 N 轮结束」，错误原文丢失。
        val item = parser.parse(
            obj(
                """
                {"type":"turn/end","seq":40,"time":9,"data":{"turn":2,
                 "reason":{"kind":"error","error":{"message":"429 too many","code":"RATE_LIMIT","status":429}}}}
                """.trimIndent(),
            ),
        )
        val result = item as ChatItem.TurnResult
        assertEquals(2L, result.turn)
        val outcome = result.outcome as TurnOutcome.Failed
        assertEquals("429 too many", outcome.message)
        assertEquals("RATE_LIMIT", outcome.code)
        assertEquals(429, outcome.status)
    }

    @Test
    fun `turn-end 截断与中止也落成结局卡`() {
        val truncated = parser.parse(
            obj("""{"type":"turn/end","seq":41,"time":9,"data":{"turn":3,"reason":{"kind":"max-tokens"}}}"""),
        ) as ChatItem.TurnResult
        assertEquals(TurnOutcome.Truncated, truncated.outcome)

        val aborted = parser.parse(
            obj("""{"type":"turn/end","seq":42,"time":9,"data":{"turn":4,"reason":{"kind":"aborted","reason":{"kind":"user"}}}}"""),
        ) as ChatItem.TurnResult
        assertEquals(TurnOutcome.Aborted(AbortCause.USER), aborted.outcome)
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
                 "data":{"source":{"kind":"user"},"content":[
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
                 "data":{"source":{"kind":"user"},"content":[{"type":"file",
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
                 "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"x"},
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

    // ---------------------------------------------------------------- 来源过滤
    //
    // `user/message` 这个事件类型被所有往会话日志追加用户可见文本的发起方共用，
    // `source.kind` 才是「人输入的」与「系统注入的」的分界。用户 m22856 反馈
    // 「聊天界面中出现很多不是我发送的提示词（看起来是插件构建的 prompt）」，
    // 根因就是这里没有过滤。
    //
    // 规模参照（`app开发 main`，31361 条事件里 227 条 `user/message`）：
    // 只有 40 条 kind 是 `user`，其余 187 条是注入物，其中 `time-context`
    // （「Time sampled while preparing turn N, step M…」）141 条。

    @Test
    fun `来源是 user 的消息渲染成用户气泡`() {
        // 桌面端人工输入：source 只有 kind。
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":70,"time":1,
                 "data":{"role":"user","content":[{"type":"text","text":"手工打的"}],
                         "source":{"kind":"user"}}}
                """.trimIndent(),
            ),
        )
        assertEquals("手工打的", (item as ChatItem.User).text)
    }

    @Test
    fun `远程客户端发来的消息也渲染成用户气泡`() {
        // `MessageSourceMap` 里 `user-rpc` 的 kind 同样是 'user'，只是多 rpcId 与
        // clientTimeZone。本 App 发出的每条消息都是这个形状（实测
        // source = {kind:'user', rpcId:'…'}），绝不能因为多两个键就被过滤掉。
        val item = parser.parse(
            obj(
                """
                {"type":"user/message","seq":71,"time":1,
                 "data":{"role":"user","content":[{"type":"text","text":"手机发的"}],
                         "source":{"kind":"user","rpcId":"8f3c…",
                                   "clientTimeZone":"Asia/Shanghai"}}}
                """.trimIndent(),
            ),
        )
        assertEquals("手机发的", (item as ChatItem.User).text)
    }

    @Test
    fun `系统注入的 user-message 不产生气泡`() {
        // 黑名单式穷举在本工程不可行（宿主插件会加新 kind），所以这里是**回归护栏**：
        // 把线上实测到的每一种非 user kind 都钉住，漏掉一种就是一次用户可见的噪声。
        val injected = mapOf(
            "time-context" to "Time sampled while preparing turn 22, step 2: 2026-10-14T12:17:21+08:00",
            "runtime-context" to "Current runtime context. This snapshot supersedes earlier runtime-context snapshots.",
            "skill-catalog" to "<system-reminder> A skill is a reusable set of task-specific instructions.",
            "subagent-settled" to "Background subagent ce0e9424 ran out of room before it finished.",
            "agent-message" to "Agent de6a98e0 sent a message: M3 独立审查结果",
            "model-selection" to "[model changed: assistant turns above this point were generated by …]",
            "tool-jobs" to "background job pwsh-168 (pwsh: …)",
            "goal" to "<goal_round> Objective: \"完成所有开发\" Round: 1/256",
            "tool-goal" to "<goal_complete> Objective: \"完成所有开发\"",
            "user-approval" to "The approval policy changed from \"ask\" to \"never\" (changed by the user).",
            "system-prompt" to "你是一个编码助手。",
            "tool" to "工具结果",
            "model" to "模型自述",
            "ptc-mode" to "ptc mode changed",
            "tool-registry" to "tool registry",
            "skill-invocation" to "skill invocation",
            "cordis-host-runner" to "cordis",
            "schedule" to "schedule",
            "compact-checkpoint" to "checkpoint",
            "session-reference" to "session reference",
            "user-question-reply" to "question reply",
        )
        injected.forEach { (kind, text) ->
            val envelope = """{"type":"user/message","seq":80,"time":1,
                "data":{"role":"user","content":[{"type":"text","text":"${text.replace("\"", "\\\"")}"}],
                        "source":{"kind":"$kind"}}}"""
            assertNull("kind=$kind 不该渲染成用户气泡", parser.parse(obj(envelope)))
        }
    }

    @Test
    fun `来源缺席的 user-message 不产生气泡`() {
        // 白名单策略：宁可少显示一行，也不要把注入物混进对话。
        // 实测 227/227 条 `user/message` 都带 source，所以这条不会误伤真实消息。
        assertNull(
            parser.parse(
                obj(
                    """{"type":"user/message","seq":81,"time":1,
                        "data":{"role":"user","content":[{"type":"text","text":"没有来源"}]}}""",
                ),
            ),
        )
    }

    @Test
    fun `来源不是对象的 user-message 不产生气泡`() {
        assertNull(
            parser.parse(
                obj(
                    """{"type":"user/message","seq":82,"time":1,
                        "data":{"role":"user","content":[{"type":"text","text":"x"}],
                                "source":"user"}}""",
                ),
            ),
        )
    }

    @Test
    fun `被过滤的注入消息不影响其它事件类型`() {
        // 过滤只能发生在 `user/message` 这一条分支上，不能顺手把 assistant/message 也挡了。
        val assistant = parser.parse(
            obj(
                """
                {"type":"assistant/message","seq":83,"time":1,
                 "data":{"turn":0,"step":0,
                   "message":{"id":"m","role":"assistant",
                     "content":[{"type":"text","text":"回复"}],
                     "source":{"kind":"model"}}}}
                """.trimIndent(),
            ),
        )
        assertEquals("回复", ((assistant as ChatItem.Assistant).blocks.single() as ContentBlock.Text).text)
    }

    // --------------------------------------------------- R012 P2-A / P2-B 回归
    //
    // `isCacheable` 是缓存写入的唯一判据（`ChatFrameParser.recordEnvelopes` 的快照路径
    // 与 `ChatViewModel.envelopesOf` 的实时路径共用）。它必须只挡「注入的 user/message」，
    // 不能按渲染性过滤——否则 `windowMinSeq` 退化、断线补齐把旧条目 prepend 回列表。

    private fun parsed(text: String) = parser.parseEvent(obj(text))!!

    @Test
    fun `isCacheable 放行真实用户消息`() {
        assertTrue(
            parser.isCacheable(
                parsed(
                    """{"type":"user/message","seq":1,"time":1,
                       "data":{"source":{"kind":"user"},
                               "content":[{"type":"text","text":"我发的"}]}}""",
                ),
            ),
        )
    }

    @Test
    fun `isCacheable 挡掉注入的 user-message`() {
        assertFalse(
            parser.isCacheable(
                parsed(
                    """{"type":"user/message","seq":2,"time":1,
                       "data":{"source":{"kind":"time-context"},
                               "content":[{"type":"text","text":"Time sampled ..."}]}}""",
                ),
            ),
        )
    }

    @Test
    fun `isCacheable 放行渲染不出卡片的事件`() {
        // turn/start、turn/end、step/start、step/end 全都 toChatItem == null，
        // 但它们是 windowMinSeq 与生成状态的原料，必须写进缓存。
        for (type in listOf("turn/start", "turn/end", "step/start", "step/end")) {
            assertTrue(
                parser.isCacheable(parsed("""{"type":"$type","seq":3,"time":1,"data":{}}""")),
            )
        }
    }

    @Test
    fun `isCacheable 放行其它全部事件类型`() {
        for (type in listOf("assistant/message", "tool/call", "tool/result", "session/title")) {
            assertTrue(parser.isCacheable(parsed("""{"type":"$type","seq":4,"time":1,"data":{}}""")))
        }
    }

    @Test
    fun turnEndWithoutTurnFieldStillProducesResultCard() {
        // 审查 P2-2：`turn` 只用于「第 N 轮」文案，缺失时不能连卡一起丢——
        // 那会把带上游错误原文的结局退化成 R043 之前的静默状态。
        val item = parser.parse(
            obj(
                """{"type":"turn/end","seq":9,"time":1,
                 "data":{"reason":{"kind":"error","error":{"message":"boom","code":"SERVER"}}}}""".trimIndent(),
            ),
        )
        val card = item as ChatItem.TurnResult
        assertEquals("boom", (card.outcome as TurnOutcome.Failed).message)
        assertFalse(card.turnKnown)
    }
}
