package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatFrameParser] 的单测。
 *
 * 帧形状来自 `SessionFollowFrame`（`typert.remote-client.js:221-424`）与
 * `SessionPage`（同文件 `:638-653`）。
 */
class ChatFrameParserTest {

    private val parser = ChatFrameParser()
    private val json = Json

    private fun element(text: String) = json.parseToJsonElement(text)

    private fun obj(text: String): JsonObject = element(text) as JsonObject

    @Test
    fun `解析 snapshot 帧`() {
        val frame = parser.parse(
            element(
                """
                {"type":"snapshot",
                 "header":{"version":1,"id":"session-1","createdAt":1,"cwd":"F:\\bot","isSeeded":true},
                 "cursor":42,
                 "records":[
                   {"type":"event","event":{"type":"user/message","seq":1,"time":100,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"早"}]}}},
                   {"type":"event","event":{"type":"assistant/message","seq":2,"time":200,
                     "data":{"turn":0,"step":0,
                       "message":{"id":"m","role":"assistant",
                         "content":[{"type":"text","text":"晚"}],"source":{"kind":"model"}}}}}],
                 "hasMore":true,
                 "projections":{"asOfSeq":42,"values":{"title":"标题"}}}
                """.trimIndent(),
            ),
        )
        val snapshot = frame as ChatFrame.Snapshot
        assertEquals(42L, snapshot.cursor)
        assertTrue(snapshot.hasMore)
        assertEquals("标题", snapshot.title)
        assertEquals("F:\\bot", snapshot.cwd)
        assertEquals(listOf(1L, 2L), snapshot.items.map { it.seq })
        assertTrue(snapshot.baseline.isEmpty())
    }

    @Test
    fun `snapshot 的 records 乱序时排好并去重`() {
        val frame = parser.parse(
            element(
                """
                {"type":"snapshot","cursor":9,
                 "records":[
                   {"type":"event","event":{"type":"user/message","seq":5,"time":1,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"b"}]}}},
                   {"type":"event","event":{"type":"user/message","seq":3,"time":1,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"a"}]}}},
                   {"type":"event","event":{"type":"user/message","seq":5,"time":1,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"b again"}]}}}],
                 "hasMore":false,
                 "projections":{"asOfSeq":9,"values":{}}}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(3L, 5L), (frame as ChatFrame.Snapshot).items.map { it.seq })
    }

    @Test
    fun `snapshot 缺 projections 时不崩`() {
        val frame = parser.parse(
            element("""{"type":"snapshot","cursor":1,"records":[],"hasMore":false}"""),
        )
        val snapshot = frame as ChatFrame.Snapshot
        assertNull(snapshot.title)
        assertNull(snapshot.cwd)
        assertTrue(snapshot.items.isEmpty())
    }

    @Test
    fun `解析 event 帧`() {
        val frame = parser.parse(
            element(
                """
                {"type":"event","event":{"type":"user/message","seq":7,"time":1,
                  "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}}}
                """.trimIndent(),
            ),
        )
        val item = (frame as ChatFrame.Event).item
        assertEquals("hi", (item as ChatItem.User).text)
    }

    @Test
    fun `噪声 event 帧的 item 是 null`() {
        val frame = parser.parse(
            element("""{"type":"event","event":{"type":"turn/start","seq":1,"time":1,"data":{"turn":0}}}"""),
        )
        assertNull((frame as ChatFrame.Event).item)
    }

    @Test
    fun `解析 assistant-stream 的 start 帧`() {
        val frame = parser.parse(
            element(
                """
                {"type":"assistant-stream","frame":{"type":"start","attemptId":"att-1",
                  "revision":1,"startedAfterSeq":9,"turn":2,"step":0}}
                """.trimIndent(),
            ),
        )
        val start = (frame as ChatFrame.Live).frame as ChatFrame.LiveFrame.Start
        assertEquals("att-1", start.attemptId)
        assertEquals(2L, start.turn)
    }

    @Test
    fun `解析 assistant-stream 的 chunk 帧并转成 delta`() {
        val frame = parser.parse(
            element(
                """
                {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"att-1",
                  "revision":1,"index":0,"time":1234,
                  "chunk":{"type":"text-delta","index":0,"text":"字"}}}
                """.trimIndent(),
            ),
        )
        val chunk = (frame as ChatFrame.Live).frame as ChatFrame.LiveFrame.Chunk
        assertEquals("att-1", chunk.attemptId)
        assertEquals(1234L, chunk.time)
        assertEquals("字", (chunk.delta as AssistantStreamExpander.Delta.Text).text)
    }

    @Test
    fun `解析 committed 的 end 帧`() {
        val frame = parser.parse(
            element(
                """
                {"type":"assistant-stream","frame":{"type":"end","attemptId":"att-1",
                  "revision":1,"index":3,
                  "outcome":{"kind":"committed","eventType":"assistant/message","seq":88}}}
                """.trimIndent(),
            ),
        )
        val end = (frame as ChatFrame.Live).frame as ChatFrame.LiveFrame.End
        assertEquals(88L, end.committedSeq)
    }

    @Test
    fun `解析 abandoned 的 end 帧`() {
        val frame = parser.parse(
            element(
                """
                {"type":"assistant-stream","frame":{"type":"end","attemptId":"att-1",
                  "revision":1,"index":3,"outcome":{"kind":"abandoned"}}}
                """.trimIndent(),
            ),
        )
        assertNull(((frame as ChatFrame.Live).frame as ChatFrame.LiveFrame.End).committedSeq)
    }

    @Test
    fun `unknown 帧返回 null`() {
        assertNull(parser.parse(element("""{"type":"future-frame"}""")))
        assertNull(parser.parse(element("""{"no":"type"}""")))
    }

    @Test
    fun `缺 attemptId 的流帧返回 null`() {
        assertNull(parser.parse(element("""{"type":"assistant-stream","frame":{"type":"start"}}""")))
    }

    @Test
    fun `snapshot 里的重连基线被展开`() {
        val frame = parser.parse(
            element(
                """
                {"type":"snapshot","cursor":5,"records":[],"hasMore":false,
                 "projections":{"asOfSeq":5,"values":{}},
                 "assistantStream":{"revision":2,"activeAttempt":{
                   "attemptId":"att-9","startedAfterSeq":4,"turn":0,"step":0,"nextIndex":2,
                   "stream":[{"type":"text-chunks","time0":10,"index":0,"dt":[5],"texts":["甲","乙"]}]}}}
                """.trimIndent(),
            ),
        )
        val baseline = (frame as ChatFrame.Snapshot).baseline
        assertEquals(2, baseline.size)
        assertEquals("甲", (baseline[0] as AssistantStreamExpander.Delta.Text).text)
        assertEquals("乙", (baseline[1] as AssistantStreamExpander.Delta.Text).text)
    }

    @Test
    fun `没有 activeAttempt 时基线为空`() {
        val frame = parser.parse(
            element(
                """
                {"type":"snapshot","cursor":5,"records":[],"hasMore":false,
                 "projections":{"asOfSeq":5,"values":{}},"assistantStream":{"revision":0}}
                """.trimIndent(),
            ),
        )
        assertTrue((frame as ChatFrame.Snapshot).baseline.isEmpty())
    }

    @Test
    fun `parsePage 解析记录与 hasMore`() {
        val (items, hasMore) = parser.parsePage(
            element(
                """
                {"records":[
                   {"type":"event","event":{"type":"user/message","seq":1,"time":1,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"更早"}]}}}],
                 "hasMore":true}
                """.trimIndent(),
            ),
        )
        assertEquals(1, items.size)
        assertEquals("更早", (items.single() as ChatItem.User).text)
        assertTrue(hasMore)
    }

    @Test
    fun `parsePage 对畸形输入返回空`() {
        val (items, hasMore) = parser.parsePage(element("""{"records":"not-an-array"}"""))
        assertTrue(items.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun `records 跳过非 event 包裹的条目`() {
        val items = parser.records(
            element(
                """
                [{"type":"event","event":{"type":"user/message","seq":1,"time":1,
                   "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"ok"}]}}},
                 {"notAnEvent":true}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `records 接受 null`() {
        assertTrue(parser.records(null).isEmpty())
    }

    // ------------------------------------------------------------ R012 P2-A 回归
    //
    // `recordEnvelopes` 一度按「能不能渲染成卡片」过滤，把 `turn/start`、`step/end`
    // 这些不占行的事件也挡在缓存外。后果是 `windowMinSeq` 退化成「首个可渲染事件的
    // seq」，与条目最小 seq 重合，重连合并时旧缓存里 seq 低于新窗口起点的条目会被
    // 判成「窗口之外的旧历史」而 prepend 回列表——升级场景下注入物复活。
    //
    // 判据必须收窄成「只挡注入的 user/message」，turn 系列照旧进 envelopes。

    /** 构造一段含「不可渲染事件 + 真实用户消息 + 注入用户消息」的 records。 */
    private fun cacheableRecords() = element(
        """
        [{"type":"event","event":{"type":"turn/start","seq":10,"time":1000,"data":{"turn":1}}},
         {"type":"event","event":{"type":"user/message","seq":11,"time":1100,
           "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"我发的"}]}}},
         {"type":"event","event":{"type":"user/message","seq":12,"time":1200,
           "data":{"source":{"kind":"time-context"},
                   "content":[{"type":"text","text":"Time sampled while preparing turn 1, step 1: ..."}]}}},
         {"type":"event","event":{"type":"step/end","seq":13,"time":1300,"data":{"turn":1,"step":0}}}]
        """.trimIndent(),
    )

    @Test
    fun `recordEnvelopes 保留不可渲染的 turn 与 step 事件`() {
        val envelopes = parser.recordEnvelopes(cacheableRecords())
        val types = envelopes.map { (it as JsonObject)["type"]?.toString()?.trim('"') }
        // `turn/start` 与 `step/end` 都渲染不出卡片，但必须留在 envelopes 里：
        // windowMinSeq 靠它们兜住「服务端窗口内夹带非渲染事件」的旧条目残留。
        assertTrue(types.contains("turn/start"))
        assertTrue(types.contains("step/end"))
    }

    @Test
    fun `recordEnvelopes 挡掉注入的 user-message 但保留真实用户消息`() {
        val envelopes = parser.recordEnvelopes(cacheableRecords())
        val texts: List<String?> = envelopes.map { envelope ->
            val data = (envelope as JsonObject)["data"] as? JsonObject
            val content = data?.get("content") as? kotlinx.serialization.json.JsonArray
            content
                ?.mapNotNull { block ->
                    (block as? JsonObject)?.get("text")?.toString()?.trim('"')
                }
                ?.joinToString("\n")
        }
        assertTrue(texts.contains("我发的"))
        assertFalse(texts.any { it?.startsWith("Time sampled") == true })
        assertEquals(3, envelopes.size)
    }

    @Test
    fun `snapshot 的 windowMinSeq 由全部可解析事件决定而不是可渲染事件`() {        val frame = parser.parse(
            element(
                """
                {"type":"snapshot",
                 "header":{"version":1,"id":"session-1","createdAt":1,"cwd":"F:\\bot","isSeeded":true},
                 "cursor":13,
                 "records":[
                   {"type":"event","event":{"type":"user/message","seq":11,"time":1100,
                     "data":{"source":{"kind":"user"},"content":[{"type":"text","text":"我发的"}]}}},
                   {"type":"event","event":{"type":"turn/start","seq":10,"time":1000,"data":{"turn":1}}}],
                 "hasMore":true,
                 "projections":{"asOfSeq":13,"values":{}}}
                """.trimIndent(),
            ),
        ) as ChatFrame.Snapshot
        // 若按渲染性过滤，windowMinSeq 会变成 11（首个可渲染事件），turn/start 的 10 丢失。
        assertEquals(10L, frame.windowMinSeq)
    }

    // ---------------------------------------------------- R012 P2-B 回归
    //
    // 实时路径（`ChatViewModel.envelopesOf` 的 `ChatFrame.Event` 分支）必须与快照路径
    // 共用同一条判据。只修快照路径的话，缓存预算只在「打开会话那一瞬间」生效，
    // 挂机期间服务端推进的事件仍会把注入物逐条写进去——而这件事在 UI 上完全看不出来。

    private fun envelope(text: String) = obj(text)

    @Test
    fun `cacheable 对已取出的信封同样只挡注入的 user-message`() {
        val envelopes = listOf(
            envelope(
                """{"type":"turn/start","seq":1,"time":1,"data":{"turn":1}}""",
            ),
            envelope(
                """{"type":"user/message","seq":2,"time":1,
                    "data":{"source":{"kind":"user"},
                            "content":[{"type":"text","text":"我发的"}]}}""",
            ),
            envelope(
                """{"type":"user/message","seq":3,"time":1,
                    "data":{"source":{"kind":"time-context"},
                            "content":[{"type":"text","text":"Time sampled ..."}]}}""",
            ),
        )
        val kept = parser.cacheable(envelopes)
        val seqs = kept.map { it["seq"].toString().toLong() }
        assertEquals(listOf(1L, 2L), seqs)
    }

    @Test
    fun `cacheable 接受畸形信封而不抛`() {
        val kept = parser.cacheable(
            listOf(
                envelope("""{"noType":true}"""),
                envelope("""{"type":"user/message"}"""),
            ),
        )
        assertTrue(kept.isEmpty())
    }
}
