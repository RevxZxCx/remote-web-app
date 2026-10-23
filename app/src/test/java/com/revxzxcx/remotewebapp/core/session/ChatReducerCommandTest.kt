package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令的两条事件在 reducer 里的合并（R045）。
 *
 * ## 为什么单独一个文件
 *
 * `ChatReducerTest` 已经很大，而这条逻辑有独立的关注点：`command/run` 与
 * `command/done` 是**两条事件、两个 seq、且 done 不带命令名**。合并错了的两种表现
 * 都很难在界面上看出来——要么永远留着一张「执行中」，要么卡片标题空白。
 */
class ChatReducerCommandTest {

    private val parser = SessionEventParser()

    private fun frame(raw: String): ChatFrame.Event {
        val json = Json.parseToJsonElement(raw).jsonObject
        val event = requireNotNull(parser.parseEvent(json)) { "信封解析失败：$raw" }
        return ChatFrame.Event(
            eventType = event.type,
            seq = event.seq,
            item = parser.toChatItem(event),
            turn = (event.data["turn"] as? JsonPrimitive)?.content?.toLongOrNull(),
            step = (event.data["step"] as? JsonPrimitive)?.content?.toLongOrNull(),
            envelope = json,
        )
    }

    private fun run(seq: Long, id: String, name: String, args: String = ""): ChatFrame.Event = frame(
        """{"type":"command/run","seq":$seq,"time":${seq * 1000},
            "data":{"commandId":"$id","name":"$name","args":"$args","source":{"kind":"user"}}}""",
    )

    private fun done(seq: Long, id: String, kind: String, text: String?): ChatFrame.Event {
        val textField = if (text == null) "" else """"text":"$text","""
        return frame(
            """{"type":"command/done","seq":$seq,"time":${seq * 1000},
                "data":{${textField}"commandId":"$id","kind":"$kind"}}""",
        )
    }

    private fun apply(vararg frames: ChatFrame.Event): ChatUiState =
        frames.fold(ChatUiState()) { state, f -> ChatReducer.reduce(state, f) }

    // ── 基本产出 ────────────────────────────────────────────────────────────

    @Test
    fun `run 事件产出一张执行中的卡`() {
        val state = apply(run(10, "cmd-1", "skill-review"))
        val card = state.items.single() as ChatItem.CommandResult
        assertTrue(card.running)
        assertEquals("/skill-review", card.line)
        assertNull(card.text)
    }

    @Test
    fun `done 到达后卡片变成完成态并带上输出`() {
        val state = apply(
            run(10, "cmd-1", "skill-review"),
            done(11, "cmd-1", "success", "No skill candidates are waiting for approval."),
        )
        val card = state.items.single() as ChatItem.CommandResult
        assertFalse(card.running)
        assertTrue(card.succeeded)
        assertEquals("No skill candidates are waiting for approval.", card.text)
    }

    /**
     * 最关键的一条：done 到达后**只能剩一张卡**，且标题是命令名。
     *
     * 不合并的话界面上会并排两张：一张永远停在「执行中」，一张只有结果没有主语。
     */
    @Test
    fun `done 到达后 run 那张卡被合并掉而不是并排留下`() {
        val state = apply(
            run(10, "cmd-1", "skill-review"),
            done(11, "cmd-1", "success", "done"),
        )
        assertEquals(1, state.items.size)
        val card = state.items.single() as ChatItem.CommandResult
        assertEquals("/skill-review", card.line)
    }

    @Test
    fun `合并后的卡用 done 的 seq 排序`() {
        val state = apply(
            run(10, "cmd-1", "export"),
            done(11, "cmd-1", "success", "exported"),
        )
        assertEquals(11L, state.items.single().seq)
    }

    @Test
    fun `带参数的命令行保留参数`() {
        val state = apply(run(10, "cmd-1", "goal", "把测试补全"))
        assertEquals("/goal 把测试补全", (state.items.single() as ChatItem.CommandResult).line)
    }

    @Test
    fun `失败的命令也出卡并标记为失败`() {
        val state = apply(
            run(10, "cmd-1", "export"),
            done(11, "cmd-1", "error", "no session to export"),
        )
        val card = state.items.single() as ChatItem.CommandResult
        assertFalse(card.succeeded)
        assertEquals("no session to export", card.text)
    }

    @Test
    fun `没有文本的结果也能出卡`() {
        val state = apply(run(10, "cmd-1", "y"), done(11, "cmd-1", "success", null))
        val card = state.items.single() as ChatItem.CommandResult
        assertNull(card.text)
        assertTrue(card.succeeded)
    }

    // ── 边界：只看到一半 ────────────────────────────────────────────────────

    /**
     * 窗口从 `command/done` 开始（中途连上 follow 流）。
     *
     * 此时没有 run 可合并，卡片只能靠 commandId 的后几位做主语——显示得不完美，
     * 但比「什么都不显示」好：用户至少知道有条命令跑完了。
     */
    @Test
    fun `只看到 done 时仍然出卡并用命令 id 兜底标题`() {
        val state = apply(done(10, "cmd-abcd1234", "success", "ok"))
        val card = state.items.single() as ChatItem.CommandResult
        assertEquals("", card.line)
        assertEquals("命令 cd1234", commandTitleOf(card))
    }

    /** 两条同名命令先后执行：不能把前一条的 done 拿来合并后一条的 run。 */
    @Test
    fun `不同 commandId 的两条命令互不干扰`() {
        val state = apply(
            run(10, "cmd-1", "export"),
            run(11, "cmd-2", "review"),
            done(12, "cmd-1", "success", "exported"),
        )
        assertEquals(2, state.items.size)
        val cards = state.items.filterIsInstance<ChatItem.CommandResult>()
        assertEquals("/export", cards.first { !it.running }.line)
        assertTrue(cards.first { it.running }.line == "/review")
    }

    /**
     * 历史窗口是成批进来的，不走「done 到达时摘掉 run」那条实时路径。
     *
     * 探针会话里 `command/run seq=30` 与 `command/done seq=31` 同时落在首屏窗口，
     * 界面上就是一张永不结束的「执行中」+ 一张「已完成」（R045 设备验收抓到的）。
     */
    /**
     * 历史窗口是成批进来的，不走「done 到达时摘掉 run」那条实时路径。
     *
     * 探针会话里 `command/run seq=30` 与 `command/done seq=31` 同时落在首屏窗口，
     * 界面上就是一张永不结束的「执行中」+ 一张「已完成」（R045 设备验收抓到的）。
     */
    @Test
    fun `首屏窗口同时含 run 与 done 时只留一条已完成`() {
        val state = ChatReducer.reduce(
            ChatUiState(),
            snapshot(
                run(30, "cmd-2", "skill-stats"),
                done(31, "cmd-2", "success", "ok"),
            ),
        )
        val cards = state.items.filterIsInstance<ChatItem.CommandResult>()
        assertEquals(1, cards.size)
        assertFalse(cards.single().running)
        // done 事件不带 name，行号只能从 run 抄回来，否则卡片标题会退成「命令 xxxxxx」。
        assertEquals("/skill-stats", cards.single().line)
    }

    @Test
    fun `窗口里的 run 还没等到 done 时保持执行中`() {
        val state = ChatReducer.reduce(
            ChatUiState(),
            snapshot(run(30, "cmd-2", "skill-stats")),
        )
        val cards = state.items.filterIsInstance<ChatItem.CommandResult>()
        assertEquals(1, cards.size)
        assertTrue(cards.single().running)
    }

    /** 把几条事件当成一个历史窗口（`session/page` 首屏）。 */
    private fun snapshot(vararg events: ChatFrame.Event): ChatFrame.Snapshot {
        val items = events.mapNotNull { it.item }
        return ChatFrame.Snapshot(
            items = items,
            cursor = events.maxOf { it.seq },
            hasMore = false,
            title = "探针会话",
            cwd = "F:\bot",
            baseline = emptyList(),
            envelopes = events.mapNotNull { it.envelope },
            windowMinSeq = events.minOf { it.seq },
        )
    }

    /** 命令不占 `generating`：它不是一轮对话，不能让中止按钮亮起来。 */
    @Test
    fun `命令事件不改变生成状态`() {
        val state = apply(
            frame("""{"type":"turn/start","seq":5,"time":5000,"data":{"turn":1}}"""),
            run(10, "cmd-1", "export"),
        )
        assertTrue(state.generating)
    }

    private fun commandTitleOf(card: ChatItem.CommandResult): String =
        com.revxzxcx.remotewebapp.ui.chat.commandTitle(card)
}
