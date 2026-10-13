package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatReducer] 的单测 —— 本次改动里最需要保护的部分。
 *
 * 三条不变量（见 [ChatReducer] 的 KDoc）：
 * 1. `items` 按 `seq` 严格升序且不重复
 * 2. 同 `(turn, step)` 的助手消息落盘时，预览必须被丢弃
 * 3. 快照**替换**而不是追加条目
 */
class ChatReducerTest {

    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private fun event(seq: Long, text: String = "x"): ChatFrame.Event =
        parse(
            """
            {"type":"user/message","seq":$seq,"time":${seq * 1000},
             "data":{"content":[{"type":"text","text":"$text"}]}}
            """.trimIndent(),
        )

    private fun assistant(seq: Long, turn: Long, step: Long, text: String = "a"): ChatFrame.Event =
        parse(
            """
            {"type":"assistant/message","seq":$seq,"time":${seq * 1000},
             "data":{"turn":$turn,"step":$step,
               "message":{"id":"m","role":"assistant",
                 "content":[{"type":"text","text":"$text"}],"source":{"kind":"model"}}}}
            """.trimIndent(),
        )

    /**
     * 用真实解析路径造一帧 [ChatFrame.Event]。
     *
     * 走 [SessionEventParser] 而不是手搓对象，是为了让 `eventType`/`seq` 与线上一致——
     * reducer 靠它们判断轮次生命周期，手搓容易与解析器漂移。
     */
    private fun parse(raw: String): ChatFrame.Event {
        val parser = SessionEventParser()
        val json = obj(raw)
        val event = requireNotNull(parser.parseEvent(json)) { "信封解析失败：$raw" }
        return ChatFrame.Event(
            eventType = event.type,
            seq = event.seq,
            item = parser.toChatItem(event),
            turn = event.data.longOrNull("turn"),
            step = event.data.longOrNull("step"),
        )
    }

    @Test
    fun `初始状态是 loading 且没有条目`() {
        val state = ChatUiState()
        assertTrue(state.loading)
        assertTrue(state.items.isEmpty())
        assertNull(state.live)
        assertFalse(state.ready)
    }

    @Test
    fun `快照替换条目并设置游标与 hasMore`() {
        val frame = ChatFrame.Snapshot(
            items = listOf(
                ChatItem.User(5, 500, text = "b"),
                ChatItem.User(3, 300, text = "a"),
            ),
            cursor = 9,
            hasMore = true,
            title = "标题",
            cwd = "F:\\bot",
            baseline = emptyList(),
        )
        val state = ChatReducer.reduce(ChatUiState(), frame)

        // 快照里给的顺序是乱的，reducer 必须排好。
        assertEquals(listOf(3L, 5L), state.items.map { it.seq })
        assertEquals(9L, state.cursor)
        assertTrue(state.hasMore)
        assertEquals("标题", state.title)
        assertEquals("F:\\bot", state.cwd)
        assertFalse(state.loading)
        assertTrue(state.ready)
    }

    @Test
    fun `第二次快照替换而不是追加`() {
        val first = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(listOf(ChatItem.User(1, 1, text = "旧")), 1, false, null, null, emptyList()),
        )
        val second = ChatReducer.reduce(
            first,
            ChatFrame.Snapshot(listOf(ChatItem.User(7, 7, text = "新")), 7, false, null, null, emptyList()),
        )
        // 窗口被替换：只剩新的那条，不是 1 + 7。
        assertEquals(listOf(7L), second.items.map { it.seq })
    }

    @Test
    fun `快照不带标题时不覆盖已有标题`() {
        val withTitle = ChatUiState(title = "已知道")
        val state = ChatReducer.reduce(
            withTitle,
            ChatFrame.Snapshot(emptyList(), 0, false, title = null, cwd = null, baseline = emptyList()),
        )
        assertEquals("已知道", state.title)
    }

    @Test
    fun `事件按 seq 升序插入`() {
        var state = ChatReducer.reduce(ChatUiState(), event(5, "第二"))
        state = ChatReducer.reduce(state, event(2, "第一"))
        assertEquals(listOf(2L, 5L), state.items.map { it.seq })
    }

    @Test
    fun `重复 seq 的事件只保留一条`() {
        // 重连时服务端可能重发已见过的 seq；重复插入会让 LazyColumn 的 key 冲突。
        var state = ChatReducer.reduce(ChatUiState(), event(5, "第一次"))
        state = ChatReducer.reduce(state, event(5, "第二次"))
        assertEquals(1, state.items.size)
        assertEquals(5L, state.items.single().seq)
    }

    @Test
    fun `不可渲染的事件不改变条目`() {
        val before = ChatReducer.reduce(ChatUiState(), event(1))
        // `request/header` 这类噪声事件解析不出卡片，但信封本身是合法的。
        val after = ChatReducer.reduce(
            before,
            parse("""{"type":"request/header","seq":9,"time":1,"data":{}}"""),
        )
        assertEquals(before.items, after.items)
    }

    @Test
    fun `游标随最大 seq 推进且不回退`() {
        var state = ChatReducer.reduce(ChatUiState(), event(10))
        assertEquals(10L, state.cursor)
        // 后到的 seq 更小（乱序/补历史）不能让游标倒退，否则翻页会重复拉同一段。
        state = ChatReducer.reduce(state, event(4))
        assertEquals(10L, state.cursor)
    }

    @Test
    fun `start 帧建立预览并置 generating`() {
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", turn = 2, step = 0)),
        )
        assertNotNull(state.live)
        assertEquals("att-1", state.live?.attemptId)
        assertEquals(2L, state.live?.turn)
        assertTrue(state.generating)
    }

    @Test
    fun `chunk 帧按 index 顺序拼接文本`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "你"))
        state = reduceChunk(state, "att-1", 1, AssistantStreamExpander.Delta.Text(2, 0, "好"))
        assertEquals("你好", state.live?.text)
        assertEquals(2, state.live?.nextIndex)
    }

    @Test
    fun `认不出的 attemptId 的 chunk 被忽略`() {
        // 控制器在 Host 看到该 attempt 之后才挂上时没有 start 帧可重建；
        // 官方 acceptFrame 同样忽略（`assistant-stream.js:103-104`）。
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Chunk("unknown", 0, 1, null)),
        )
        assertNull(state.live)
    }

    @Test
    fun `reasoning 与 text 分别拼接`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Reasoning(1, 0, "想"))
        state = reduceChunk(state, "att-1", 1, AssistantStreamExpander.Delta.Text(2, 0, "答"))
        assertEquals("想", state.live?.reasoning)
        assertEquals("答", state.live?.text)
    }

    @Test
    fun `工具参数增量按 id 归并`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(
            state,
            "att-1",
            0,
            AssistantStreamExpander.Delta.ToolCall(1, 0, "c1", "write", "{\"pa"),
        )
        state = reduceChunk(
            state,
            "att-1",
            1,
            AssistantStreamExpander.Delta.ToolCall(2, 0, "c1", null, "th\":\"a\"}"),
        )
        assertEquals("{\"path\":\"a\"}", state.live?.toolArgs?.get("c1"))
        assertEquals("write", state.live?.toolNames?.get("c1"))
    }

    @Test
    fun `abandoned 的 end 帧丢弃预览`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        assertNotNull(state.live)

        state = ChatReducer.reduce(
            state,
            ChatFrame.Live(ChatFrame.LiveFrame.End("att-1", 1, committedSeq = null)),
        )
        assertNull(state.live)
        assertFalse(state.generating)
    }

    @Test
    fun `committed 的 end 帧保留预览等持久化事件到达`() {
        // 若在这里清掉，落盘事件与预览之间会闪一下空白。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        state = ChatReducer.reduce(
            state,
            ChatFrame.Live(ChatFrame.LiveFrame.End("att-1", 1, committedSeq = 42)),
        )
        assertNotNull(state.live)
    }

    @Test
    fun `同轮次的助手消息落盘时预览被丢弃 —— 不变量 2`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", turn = 3, step = 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        assertNotNull(state.live)

        // 同 (turn, step) 的权威消息到达 → 预览作废，避免同一句话显示两遍。
        state = ChatReducer.reduce(state, assistant(50, turn = 3, step = 0, text = "完整回答"))

        assertNull(state.live)
        assertEquals(1, state.items.size)
        assertEquals("完整回答", ((state.items.single() as ChatItem.Assistant).blocks.single() as ContentBlock.Text).text)
    }

    @Test
    fun `不同轮次的助手消息不清掉预览`() {
        // 第 3 轮还在流式，第 2 轮的旧消息补历史到达 → 预览必须留着。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", turn = 3, step = 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        state = ChatReducer.reduce(state, assistant(50, turn = 2, step = 0))
        assertNotNull(state.live)
    }

    @Test
    fun `多步轮次里助手消息落盘后 generating 仍为真`() {
        // 实测 turn 27 有 45 个步骤、45 条 assistant/message，全部在 turn/end 之前落盘。
        // 若拿「助手消息落盘」置假，中止按钮会在第 1 步之后就消失（R004 P2-1）。
        var state = ChatReducer.reduce(
            ChatUiState(),
            parse("""{"type":"turn/start","seq":1,"time":1,"data":{"turn":0}}"""),
        )
        assertTrue(state.generating)
        state = ChatReducer.reduce(state, assistant(2, turn = 0, step = 0))
        assertTrue("第 1 步落盘后仍在生成", state.generating)
        state = ChatReducer.reduce(state, assistant(3, turn = 0, step = 1))
        assertTrue("第 2 步落盘后仍在生成", state.generating)
        state = ChatReducer.reduce(
            state,
            parse("""{"type":"turn/end","seq":4,"time":1,"data":{"turn":0,"reason":"completed"}}"""),
        )
        assertFalse("turn/end 才结束生成", state.generating)
    }

    @Test
    fun `turn-start 让 generating 变真`() {
        // prompt 之后到首个 assistant-stream 帧之间只有 turn/start 可依靠；
        // 不认它，中止按钮在这一两秒内不会出现（R004 P2-1）。
        assertFalse(ChatUiState().generating)
        val state = ChatReducer.reduce(
            ChatUiState(),
            parse("""{"type":"turn/start","seq":1,"time":1,"data":{"turn":0}}"""),
        )
        assertTrue(state.generating)
    }

    @Test
    fun `content 为空的助手消息也能清掉同一步的预览`() {
        // R004 P2-3：`assistant/message` 的 content 为空时解析不出卡片（item == null），
        // 若清预览只看 item，流式气泡会永久残留在权威内容旁边。中止场景最典型。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", turn = 2, step = 3)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        assertNotNull(state.live)

        val empty = parse(
            """
            {"type":"assistant/message","seq":20,"time":1,
             "data":{"turn":2,"step":3,"interrupted":true,
               "message":{"id":"m","role":"assistant","source":{"kind":"model"},"content":[]}}}
            """.trimIndent(),
        )
        assertEquals("解析不出卡片", null, empty.item)
        state = ChatReducer.reduce(state, empty)
        assertNull("预览仍然被清掉", state.live)
    }

    @Test
    fun `只承载工具调用的助手消息不占一行`() {
        // 同一次调用既有独立 tool/call 事件、又有 assistant/message 里的 tool-call 块
        // （实测 182:182 一一对应）。助手那条若也占行就会渲染两遍。
        val onlyToolCall = parse(
            """
            {"type":"assistant/message","seq":10,"time":1,
             "data":{"turn":0,"step":0,
               "message":{"id":"m","role":"assistant","source":{"kind":"model"},
                 "content":[{"type":"tool-call","id":"c1","name":"read","arguments":"{}"}]}}}
            """.trimIndent(),
        )
        val state = ChatReducer.reduce(ChatUiState(), onlyToolCall)
        assertTrue("不占行", state.items.isEmpty())
    }

    @Test
    fun `纯工具调用的助手消息仍然清掉同一步的预览`() {
        // 「不占行」不等于「可忽略」：它是本步已落盘的信号，预览必须靠它清掉，
        // 否则流式气泡会永久残留在权威内容旁边。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", turn = 0, step = 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        assertNotNull(state.live)

        val onlyToolCall = parse(
            """
            {"type":"assistant/message","seq":10,"time":1,
             "data":{"turn":0,"step":0,
               "message":{"id":"m","role":"assistant","source":{"kind":"model"},
                 "content":[{"type":"tool-call","id":"c1","name":"read","arguments":"{}"}]}}}
            """.trimIndent(),
        )
        state = ChatReducer.reduce(state, onlyToolCall)
        assertNull("预览被清掉", state.live)
    }

    @Test
    fun `被中止的消息即使只含工具调用也保留`() {
        // 中止时可能没有对应的 tool/call 事件，滤掉就真的丢内容了。
        val interrupted = parse(
            """
            {"type":"assistant/message","seq":11,"time":1,
             "data":{"turn":0,"step":0,"interrupted":true,
               "message":{"id":"m","role":"assistant","source":{"kind":"model"},
                 "content":[{"type":"tool-call","id":"c1","name":"read","arguments":"{}"}]}}}
            """.trimIndent(),
        )
        val state = ChatReducer.reduce(ChatUiState(), interrupted)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `turn-end 让 generating 变假`() {
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        assertTrue(state.generating)
        state = ChatReducer.reduce(
            state,
            parse("""{"type":"turn/end","seq":70,"time":1,"data":{"turn":0,"reason":"completed"}}"""),
        )
        assertFalse(state.generating)
    }

    @Test
    fun `重连基线回放成预览`() {
        val baseline = listOf(
            AssistantStreamExpander.Delta.Text(100, 0, "断线期间"),
            AssistantStreamExpander.Delta.Text(110, 0, "生成的内容"),
        )
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(emptyList(), 5, false, null, null, baseline),
        )
        assertEquals("断线期间生成的内容", state.live?.text)
        assertTrue(state.generating)
    }

    @Test
    fun `空基线不产生预览`() {
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(emptyList(), 5, false, null, null, emptyList()),
        )
        assertNull(state.live)
        assertFalse(state.generating)
    }

    @Test
    fun `只含空文本的基线不产生预览`() {
        // delta 有 time 但没有可见内容（例如纯 block-end）时不该冒出一个空气泡。
        val baseline = listOf(
            AssistantStreamExpander.Delta.Raw(1, obj("""{"type":"block-start","index":0}""")),
        )
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(emptyList(), 5, false, null, null, baseline),
        )
        assertNull(state.live)
    }

    @Test
    fun `index 乱序时不推进 nextIndex 但也不丢内容`() {
        // 官方在 index 不连续时要求 rebaseline（重连对齐，M4 做）。
        // M3 的选择：宁可多拼一段也不静默丢字。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "A"))
        state = reduceChunk(state, "att-1", 5, AssistantStreamExpander.Delta.Text(2, 0, "B"))
        assertEquals("AB", state.live?.text)
        assertEquals(1, state.live?.nextIndex)
    }

    @Test
    fun `多轮消息按 seq 排列`() {
        var state = ChatReducer.reduce(ChatUiState(), event(1, "问"))
        state = ChatReducer.reduce(state, assistant(2, turn = 0, step = 0, text = "答"))
        state = ChatReducer.reduce(state, event(3, "再问"))
        state = ChatReducer.reduce(state, assistant(4, turn = 1, step = 0, text = "再答"))
        assertEquals(listOf(1L, 2L, 3L, 4L), state.items.map { it.seq })
        assertTrue(state.items[1] is ChatItem.Assistant)
    }

    @Test
    fun `快照里重复 seq 的条目被去重`() {
        // 重复 key 会让 ChatScreen 的 LazyColumn 直接抛 IllegalArgumentException
        // （与 R003 P1-1 同类）。服务端窗口理论上不会重复，但去重是零成本的保险。
        val state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(
                items = listOf(ChatItem.User(1, 1, text = "a"), ChatItem.User(1, 1, text = "a")),
                cursor = 1,
                hasMore = false,
                title = null,
                cwd = null,
                baseline = emptyList(),
            ),
        )
        assertEquals(1, state.items.size)
    }

    private fun reduceChunk(
        state: ChatUiState,
        attemptId: String,
        index: Long,
        delta: AssistantStreamExpander.Delta,
    ): ChatUiState = ChatReducer.reduce(
        state,
        ChatFrame.Live(ChatFrame.LiveFrame.Chunk(attemptId, index, 1L, delta)),
    )
}
