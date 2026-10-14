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
 * 3. 快照替换它覆盖的窗口，但保留窗口之前已加载的历史（FR-4.2）
 */
class ChatReducerTest {

    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    /**
     * 造一条 `user/message` 帧。
     *
     * @param rpcId 消息的 `source.rpcId`。**排队相关的用例必须传**，而且要跟
     *   [echo] 生成的 `rpcId` 对上——`ChatReducer.dropDispatched` 靠它把
     *   「服务端取出了我们哪一条排队消息」精确匹配上，匹配不上就当作
     *   「别人（桌面端）发的消息」而不动队列。
     */
    private fun event(seq: Long, text: String = "x", rpcId: String? = null): ChatFrame.Event =
        parse(
            """
            {"type":"user/message","seq":$seq,"time":${seq * 1000},
             "data":{"content":[{"type":"text","text":"$text"}]
              ${if (rpcId == null) "" else ""","source":{"rpcId":"$rpcId"}"""}}}
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
            // 与生产一致：`ChatFrameParser.eventFrame` 把原始信封一路带出来，
            // reducer 的 `model/selection` 处理要从它上面取 `data`。
            envelope = json,
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

    // ---------------------------------------------------- 模型选择（T4）

    /**
     * 回归：**桌面端改模型也要同步到手机上**。
     *
     * `model/selection` 由 session controller 的 `selectForNextRequest` 追加
     * （`agent.js:349-352`），桌面端与手机端走同一条路。早先 reducer 只从**快照投影**
     * 读模型，于是一条实时事件被当成噪声丢掉——用户看到的就是「桌面端改了模型，
     * 手机端不动，退出会话重进才看到」（T4 设备验收实测）。
     */
    @Test
    fun `model-selection 事件更新当前模型`() {
        var state = ChatUiState()
        state = ChatReducer.reduce(state, event(1, "hi"))
        assertNull("起初没有模型", state.model)

        state = ChatReducer.reduce(
            state,
            parse(
                """
                {"type":"model/selection","seq":2,"time":2000,
                 "data":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"}}
                """.trimIndent(),
            ),
        )
        assertNotNull(state.model)
        assertEquals("deepseek-official", state.model?.provider)
        assertEquals("deepseek-flash", state.model?.model)
        assertEquals("high", state.model?.reasoningEffort)
        // 噪声事件不占行，但游标必须推进（R006 P3-15 的同类要求）。
        assertEquals(2L, state.cursor)
        assertEquals("不该多出卡片", 1, state.items.size)
    }

    @Test
    fun `model-selection 不带推理档时档位是 null`() {
        var state = ChatUiState()
        state = ChatReducer.reduce(
            state,
            parse(
                """
                {"type":"model/selection","seq":1,"time":1000,
                 "data":{"provider":"stepfun","model":"step-5-preview"}}
                """.trimIndent(),
            ),
        )
        assertEquals("stepfun", state.model?.provider)
        assertNull("没有档位就不能编一个", state.model?.reasoningEffort)
    }

    @Test
    fun `畸形的 model-selection 不破坏状态`() {
        var state = ChatUiState(model = ModelRef("stepfun", "step-5-preview"))
        state = ChatReducer.reduce(
            state,
            parse(
                """
                {"type":"model/selection","seq":1,"time":1000,"data":{"provider":"","model":""}}
                """.trimIndent(),
            ),
        )
        assertEquals("解析不出来就保持原值", "stepfun", state.model?.provider)
    }

    @Test
    fun `快照里的模型不会被后面的噪声事件清掉`() {
        var state = ChatUiState()
        state = ChatReducer.reduce(
            state,
            ChatFrame.Snapshot(
                items = emptyList(),
                cursor = 5,
                hasMore = false,
                baseline = emptyList(),
                title = null,
                cwd = null,
                model = ModelRef("deepseek-official", "deepseek-v41-pro"),
            ),
        )
        assertEquals("deepseek-v41-pro", state.model?.model)
        state = ChatReducer.reduce(state, event(6, "hi"))
        assertEquals("普通事件不动模型", "deepseek-v41-pro", state.model?.model)
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
    fun `第二次快照替换窗口但保留更早的已翻页历史`() {
        val first = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(listOf(ChatItem.User(1, 1, text = "旧")), 1, false, null, null, emptyList()),
        )
        val second = ChatReducer.reduce(
            first,
            ChatFrame.Snapshot(listOf(ChatItem.User(7, 7, text = "新")), 7, false, null, null, emptyList()),
        )
        // FR-4.2：seq 1 在快照窗口（最小 seq = 7）之前，属于用户已经翻出来的历史，
        // 重连重发窗口时**不能**把它丢掉；窗口内的内容由快照权威替换。
        assertEquals(listOf(1L, 7L), second.items.map { it.seq })
    }

    @Test
    fun `重连快照不丢已翻页的更早历史`() {
        // 造出「翻过页」的状态：更早的第 1、2 条 + 快照带来的第 50、51 条。
        val paged = ChatUiState(
            items = listOf(
                ChatItem.User(1, 1, text = "最早"),
                ChatItem.User(2, 2, text = "次早"),
                ChatItem.User(50, 50, text = "窗口头"),
                ChatItem.User(51, 51, text = "窗口尾"),
            ),
            ready = true,
            loading = false,
            cursor = 51,
        )
        // 重连后服务端只重发尾部窗口（50、51，外加新到的 52）。
        val reconnected = ChatReducer.reduce(
            paged,
            ChatFrame.Snapshot(
                items = listOf(
                    ChatItem.User(50, 50, text = "窗口头"),
                    ChatItem.User(51, 51, text = "窗口尾"),
                    ChatItem.User(52, 52, text = "新的"),
                ),
                cursor = 52,
                hasMore = true,
                title = null,
                cwd = null,
                baseline = emptyList(),
            ),
        )
        assertEquals(listOf(1L, 2L, 50L, 51L, 52L), reconnected.items.map { it.seq })
        assertEquals(52L, reconnected.cursor)
        // 已经翻到 seq=1（日志开头）就不该再说「还有更早的」：快照的 `hasMore=true` 是相对
        // **快照窗口**说的，而合并后的 items 已经延伸到窗口之前。直取快照值会留下一个点了
        // 没用的「加载更早」（R006 P2-9）。
        assertFalse(reconnected.hasMore)
    }

    @Test
    fun `快照窗口之下的历史仍会有更早的`() {
        // 与上一条相反：只翻到 seq=7（窗口最小 seq 是 8），日志里确实还有更早的内容。
        val paged = ChatUiState(
            items = listOf(ChatItem.User(7, 7, text = "更早")),
            ready = true,
            loading = false,
            cursor = 8,
        )
        val reconnected = ChatReducer.reduce(
            paged,
            ChatFrame.Snapshot(
                items = listOf(ChatItem.User(8, 8, text = "窗口")),
                cursor = 9,
                hasMore = true,
                title = null,
                cwd = null,
                baseline = emptyList(),
            ),
        )

        assertEquals(listOf(7L, 8L), reconnected.items.map { it.seq })
        assertTrue(reconnected.hasMore)
    }

    @Test
    fun `空快照不丢弃已加载的历史`() {
        // 空窗口只说明「这个窗口没有消息」，不说明日志是空的（例如窗口参数把尾部截没了）。
        val loaded = ChatUiState(items = listOf(ChatItem.User(3, 3, text = "有")), ready = true)
        val after = ChatReducer.reduce(
            loaded,
            ChatFrame.Snapshot(emptyList(), 9, false, null, null, emptyList()),
        )
        assertEquals(listOf(3L), after.items.map { it.seq })
    }

    @Test
    fun `窗口内的同 seq 由快照内容权威覆盖`() {
        // 旧的同 seq 条目（例如缓存里的占位）必须被快照里的真实内容替换，而不是两份并存。
        val cached = ChatUiState(items = listOf(ChatItem.User(7, 7, text = "缓存版")), ready = false)
        val after = ChatReducer.reduce(
            cached,
            ChatFrame.Snapshot(listOf(ChatItem.User(7, 7, text = "服务端版")), 7, false, null, null, emptyList()),
        )
        assertEquals(1, after.items.size)
        assertEquals("服务端版", (after.items.single() as ChatItem.User).text)
    }

    @Test
    fun `收到任何一帧后 fromCache 归假`() {
        val cached = ChatUiState(fromCache = true)
        assertFalse(ChatReducer.reduce(cached, event(1, "来网了")).fromCache)
        assertFalse(
            ChatReducer.reduce(cached, ChatFrame.Snapshot(emptyList(), 1, false, null, null, emptyList())).fromCache,
        )
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
        // **不动 generating**：不变量 4 规定它只由 `turn/start`/`turn/end` 翻转。一次尝试被
        // 丢弃不等于本轮结束——多步轮次里控制器会立刻开新尝试，若在这里置假，中止按钮会提前
        // 消失且直到下一轮 `turn/start` 才恢复（R006 P2-11）。
        assertTrue(state.generating)
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

    // ---------------------------------------------------------------- R006 回归

    @Test
    fun `窗口内噪声事件的 seq 也属于快照覆盖范围`() {
        // `turn/start` / `step/end` 不占行、解析不出条目，但同样在快照窗口内。若窗口下界只取
        // **条目**的最小 seq，这些事件会被当成「窗口之外」而从旧 items 里保留同 seq 的副本，
        // `distinctBy` 又保留首次出现 → 旧内容胜出（R006 P3-1）。
        val stale = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Snapshot(
                items = listOf(ChatItem.User(8, 8, text = "旧的窗口头")),
                cursor = 9,
                hasMore = false,
                title = null,
                cwd = null,
                baseline = emptyList(),
                // 解析器给的窗口下界：噪声事件 seq=5 比条目最小 seq=8 更小。
                windowMinSeq = 5L,
            ),
        )

        val merged = ChatReducer.reduce(
            stale,
            ChatFrame.Snapshot(
                items = listOf(ChatItem.User(8, 8, text = "新的窗口头")),
                cursor = 9,
                hasMore = false,
                title = null,
                cwd = null,
                baseline = emptyList(),
                windowMinSeq = 5L,
            ),
        )

        // seq=5 落在窗口内，旧 items 里那条必须被替换而不是留下第二份。
        assertEquals(listOf(8L), merged.items.map { it.seq })
        assertEquals("新的窗口头", (merged.items.single() as ChatItem.User).text)
    }

    @Test
    fun `同 seq 的新事件替换旧事件而不是保留首条`() {
        // 写成 `items + item` 再 `distinctBy` 会保留**首次**出现的那份（旧内容），于是一条
        // 被重新投递、内容已更新的同 seq 事件被静默丢弃（R006 P3-13）。
        val first = ChatReducer.reduce(ChatUiState(), event(1, "第一版"))
        val second = ChatReducer.reduce(first, event(1, "第二版"))

        assertEquals(1, second.items.size)
        assertEquals("第二版", (second.items.single() as ChatItem.User).text)
    }

    @Test
    fun `噪声事件也推进游标`() {
        // 早前「与预览无关的噪声事件」提前 return state，走不到游标推进：断线重连后 cursor
        // 会落后于真实收到的事件，下一次翻页会重复请求已经拿到的区间（R006 P3-15）。
        var state = ChatReducer.reduce(ChatUiState(), event(10, "早"))
        state = ChatReducer.reduce(
            state,
            ChatFrame.Event(eventType = "step/end", seq = 11, item = null, turn = 0, step = 0),
        )

        assertEquals(11L, state.cursor)
        // 收到过真实事件就不再是「正在加载」——即使这条不占行（R006 P3-14）。
        assertFalse(state.loading)
        assertTrue(state.ready)
        // 噪声事件本身不占一行。
        assertEquals(listOf(10L), state.items.map { it.seq })
    }

    @Test
    fun `committedSeq 兜底作废时同时清预览`() {
        // `committed` 的落盘事件若因信封畸形被解析器整帧丢弃，「seq == committedSeq」这条永远
        // 等不到。游标一旦越过它，预览就该被放弃——否则会一直显示到本轮 turn/end，看上去像
        // 同一条消息渲染两遍。**清 committedSeq 的地方必须同时清 live**（R006 P2-10）。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "半句"))
        state = ChatReducer.reduce(
            state,
            ChatFrame.Live(ChatFrame.LiveFrame.End("att-1", 1, committedSeq = 7)),
        )
        assertNotNull(state.live)

        // seq=9 > 7：落盘事件（seq 7）没来，游标已经越过它。
        state = ChatReducer.reduce(state, event(9, "后面的消息"))

        assertNull("committed 的落盘事件已不可能到达，预览必须作废", state.live)
        assertEquals(-1L, state.committedSeq)
    }

    @Test
    fun `index 超出 Int 范围时不推进 nextIndex`() {
        // `index.toInt()` 高位截断会让畸形的巨大 index 恰好等于 nextIndex 而错误推进
        // （R006 P3-17）。
        var state = ChatReducer.reduce(
            ChatUiState(),
            ChatFrame.Live(ChatFrame.LiveFrame.Start("att-1", 0, 0)),
        )
        state = reduceChunk(state, "att-1", 0, AssistantStreamExpander.Delta.Text(1, 0, "一"))
        val before = state.live!!.nextIndex

        // 2^32 + 0 → toInt() == 0 == nextIndex，错误地会推进一次。
        state = reduceChunk(
            state,
            "att-1",
            (1L shl 32) + 0L,
            AssistantStreamExpander.Delta.Text(2, 0, "二"),
        )

        assertEquals(before, state.live!!.nextIndex)
        // 不推进但也不丢内容：宁可多拼一段，也不静默丢字。
        assertTrue(state.live!!.text.contains("二"))
    }

    @Test
    fun `三类错误字段互不干扰`() {
        // R006 P3-6：流断开 / 发送失败 / 翻页失败共用一个 `error` 字段时，发送失败的「重试」
        // 也去重启整条 follow 流。三个字段必须能独立存在。
        val state = ChatUiState(error = "流断了", sendError = "发送失败", pageError = "翻页失败")

        assertEquals("流断了", state.error)
        assertEquals("发送失败", state.sendError)
        assertEquals("翻页失败", state.pageError)

        // 只清其中一个（ViewModel 的 retrySend / retryLoadMore / retry 各清各的）。
        val afterRetrySend = state.copy(sendError = null)
        assertEquals("流断了", afterRetrySend.error)
        assertNull(afterRetrySend.sendError)
        assertEquals("翻页失败", afterRetrySend.pageError)
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

    // ------------------------------------------------ 排队回声（用户 m20380 的缺口）

    /**
     * 造一帧任意类型的事件信封。
     *
     * 排队回声的摘除规则要看 `type`，所以这里必须能自由指定类型，不能复用
     * [event]（那个写死了 `user/message`）。
     */
    private fun rawEvent(seq: Long, type: String, text: String = "x"): ChatFrame.Event =
        parse(
            """
            {"type":"$type","seq":$seq,"time":${seq * 1000},
             "data":{"content":[{"type":"text","text":"$text"}]}}
            """.trimIndent(),
        )

    private fun echo(id: Long, text: String = "排队的话") = QueuedMessage(
        id = "local:$id",
        rpcId = "rpc-$id",
        text = text,
        enqueuedAt = 1_000L * id,
    )

    @Test
    fun `排队回声不会混进 items 也没有 seq 字段`() {
        // 回声没有 seq 是刻意的：进了 items 就会污染 `session/page` 的游标语义和
        // `distinctBy{seq}`，而 `ChatScreen` 用 `key = { it.seq }` 渲染 `LazyColumn`，
        // 给回声编一个假 seq 会直接抛 `Key was already used`。
        // 这条断言把「两条时间轴必须分开」钉住：回声既不在 items 里，也不共享 seq 空间。
        val state = ChatUiState(queued = listOf(echo(1L), echo(2L)))
        assertTrue(state.items.isEmpty())
        assertTrue(state.queued.all { it !is ChatItem })
        assertEquals(2, state.queued.size)
    }

    @Test
    fun `新的用户消息到达时摘掉最老的一条排队回声`() {
        val state = ChatUiState(queued = listOf(echo(1L, "第一句"), echo(2L, "第二句")))
        val next = ChatReducer.reduce(state, event(seq = 5L, text = "第一句", rpcId = "rpc-1"))

        // FIFO：服务端按入队顺序出队，所以摘最老的那条。
        assertEquals(listOf(echo(2L, "第二句")), next.queued)
        // 同时真消息要正常进列表。
        assertEquals(1, next.items.size)
        assertEquals(5L, next.items.single().seq)
    }

    @Test
    fun `新的轮次开始不会摘掉还在排队的回声`() {
        // ⚠️ 这条断言是用户 m20729/m20733 的实测数据逼出来的反例。
        //
        // 早先的实现在 `turn/start` 上清空全部回声，理由的是「自愈」：新一轮开始了，
        // 若我们的消息是它的触发者，`user/message` 会先到、回声已摘掉；没先到就说明
        // 这批回声过期了。**这个推理错了**——服务端是一条一清的，不是一次清完：
        // 连着发两条排队消息时，序列是
        //     user/message(A) → turn/start → … → turn/end → user/message(B) → turn/start
        // 所以第一个 `turn/start` 到达时，B 还在服务端队列里。此时清空全部回声，
        // 就等于让 B 重新变成看不见的消息——正是用户 m20380 反馈的缺口，只是窗口短一些。
        //
        // 回声是服务端队列的本地镜像，唯一的权威摘除信号就是 `user/message`。
        val state = ChatUiState(queued = listOf(echo(1L, "第一句"), echo(2L, "第二句")))
        val next = ChatReducer.reduce(state, rawEvent(seq = 9L, type = "turn/start"))

        assertEquals(listOf(echo(1L, "第一句"), echo(2L, "第二句")), next.queued)
        assertTrue(next.generating)
    }

    @Test
    fun `连着发两条排队消息时回声按服务端出队顺序一条一条被换掉`() {
        // 用户 m20729/m20733 在设备上真实跑出来的序列（两条消息都在生成期发出）。
        // 这条把整条时间轴钉住：回声数量必须始终等于「已 accepted 但还没进日志」的条数。
        var state = ChatUiState(generating = true, queued = listOf(echo(1L, "one"), echo(2L, "two")))
        assertEquals(2, state.queued.size)

        // 服务端取出第一条：先写日志，再开新一轮。
        state = ChatReducer.reduce(state, event(seq = 10L, text = "one", rpcId = "rpc-1"))
        assertEquals(listOf(echo(2L, "two")), state.queued)
        assertEquals(1, state.items.size)

        state = ChatReducer.reduce(state, rawEvent(seq = 11L, type = "turn/start"))
        // 关键：新一轮开始了，但 two 还在队列里，回声必须留着。
        assertEquals(listOf(echo(2L, "two")), state.queued)

        state = ChatReducer.reduce(state, assistant(seq = 12L, turn = 1L, step = 1L, text = "第一轮的回答"))
        assertEquals(listOf(echo(2L, "two")), state.queued)

        state = ChatReducer.reduce(state, rawEvent(seq = 13L, type = "turn/end"))
        assertEquals(listOf(echo(2L, "two")), state.queued)
        assertFalse(state.generating)

        // 服务端取出第二条。
        state = ChatReducer.reduce(state, event(seq = 14L, text = "two", rpcId = "rpc-2"))
        assertTrue(state.queued.isEmpty())
        assertEquals(3, state.items.size)
        assertEquals(14L, state.items.last().seq)
    }

    @Test
    fun `同 seq 事件被重新投递时不会消耗排队回声`() {
        // 重连时服务端会重发已覆盖区间的事件（R006 P3-13 的场景）。那是内容更新，
        // 不是新的用户消息，不该把回声吃掉。
        val first = ChatReducer.reduce(ChatUiState(), event(seq = 5L, text = "旧内容"))
        val state = first.copy(queued = listOf(echo(1L)))
        val next = ChatReducer.reduce(state, event(seq = 5L, text = "新内容"))

        assertEquals(listOf(echo(1L)), next.queued)
        assertEquals("新内容", (next.items.single() as ChatItem.User).text)
    }

    @Test
    fun `噪声事件不会摘掉排队回声`() {
        val state = ChatUiState(queued = listOf(echo(1L)))
        val next = ChatReducer.reduce(state, rawEvent(seq = 6L, type = "step/end"))

        assertEquals(listOf(echo(1L)), next.queued)
    }

    @Test
    fun `快照合并不清掉排队回声`() {
        // 重连不该让用户正在排队的消息从眼前消失——服务端队列与本地回声无关。
        val state = ChatUiState(queued = listOf(echo(1L)))
        val snapshot = ChatFrame.Snapshot(
            items = listOf(requireNotNull(SessionEventParser().toChatItem(
                SessionEventParser().parseEvent(obj("""{"type":"user/message","seq":3,"time":3000,
                    "data":{"content":[{"type":"text","text":"旧消息"}]}}"""))!!,
            ))),
            cursor = 3L,
            hasMore = false,
            title = null,
            cwd = null,
            baseline = emptyList(),
        )
        val next = ChatReducer.reduce(state, snapshot)

        assertEquals(listOf(echo(1L)), next.queued)
        assertEquals(1, next.items.size)
    }

    @Test
    fun `回声摘除后普通事件照旧推进游标`() {
        // 摘回声不能顺手把别的事件处理逻辑短路掉（R006 P3-15 的同族风险）。
        val state = ChatUiState(queued = listOf(echo(1L)))
        val next = ChatReducer.reduce(state, event(seq = 7L, rpcId = "rpc-1"))

        assertTrue(next.queued.isEmpty())
        assertEquals(7L, next.cursor)
        assertTrue(next.ready)
        assertFalse(next.loading)
    }
}
