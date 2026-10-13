package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AssistantStreamExpander] 的单测。
 *
 * 期望值来自官方 `expandAssistantStream`（`dsh-api-session-controller/lib/client.js:1251-1291`）
 * 与其校验器 `validateRun`（同文件 `:1330+`）的逐行语义：
 * - `time = time0`，随后 `time += dt[i-1]`
 * - `dt.length` 必须恰好是 `members.length - 1`
 * - 成员数组非空
 *
 * 官方在越界时抛 `TypeError`；本实现选择跳过畸形记录（返回空列表），
 * 因为客户端解析历史时不该因为一条坏记录让整页崩掉——这里把这个**有意偏离**测下来。
 */
class AssistantStreamExpanderTest {

    private val expander = AssistantStreamExpander()
    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `text-chunks 按 dt 递推时间`() {
        val deltas = expander.expandRecord(
            obj(
                """
                {"type":"text-chunks","time0":1000,"index":0,"dt":[10,20],
                 "texts":["你","好","世界"]}
                """.trimIndent(),
            ),
        )

        assertEquals(3, deltas.size)
        val texts = deltas.map { (it as AssistantStreamExpander.Delta.Text).text }
        assertEquals(listOf("你", "好", "世界"), texts)
        // time0=1000；第 2 个 +10；第 3 个 +20。
        assertEquals(listOf(1000L, 1010L, 1030L), deltas.map { it.time })
        // index 是**内容块**下标，同一条记录里所有成员相同。
        assertEquals(listOf(0, 0, 0), deltas.map { it.index })
    }

    @Test
    fun `单成员记录的 dt 必须为空数组`() {
        val deltas = expander.expandRecord(
            obj("""{"type":"text-chunks","time0":5,"index":2,"dt":[],"texts":["只有一个"]}"""),
        )
        assertEquals(1, deltas.size)
        assertEquals(5L, deltas[0].time)
        assertEquals(2, deltas[0].index)
    }

    @Test
    fun `reasoning-chunks 产出 Reasoning 而不是 Text`() {
        val deltas = expander.expandRecord(
            obj("""{"type":"reasoning-chunks","time0":1,"index":1,"dt":[3],"texts":["想","一下"]}"""),
        )
        assertEquals(2, deltas.size)
        assertTrue(deltas.all { it is AssistantStreamExpander.Delta.Reasoning })
        assertEquals(listOf(1L, 4L), deltas.map { it.time })
    }

    @Test
    fun `tool-call-chunks 产出带 id 与 name 的 ToolCall`() {
        val deltas = expander.expandRecord(
            obj(
                """
                {"type":"tool-call-chunks","time0":100,"index":0,"dt":[7],
                 "id":"call-1","name":"read","args":["{\"pa","th\":\"a\"}"]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, deltas.size)
        val first = deltas[0] as AssistantStreamExpander.Delta.ToolCall
        assertEquals("call-1", first.id)
        assertEquals("read", first.name)
        assertEquals("{\"pa", first.argumentsDelta)
        val second = deltas[1] as AssistantStreamExpander.Delta.ToolCall
        // `name` 是**记录级**字段，不是「只在首块出现」：官方 `expandAssistantStream`
        // （`dsh-api-session-controller/lib/client.js:1281`）对每条记录写成
        // `...Object.hasOwn(record, "name") ? { name: record.name } : {}`，
        // 即同一记录展开出的每个 chunk 都带同一个 name。
        assertEquals("read", second.name)
        assertEquals(107L, second.time)
    }

    @Test
    fun `tool-call-chunks 没有 name 时全部为 null`() {
        // `name` 在记录上是可选字段；缺失时由 reducer 沿用同 id 已有的名字。
        val deltas = expander.expandRecord(
            obj("""{"type":"tool-call-chunks","time0":0,"index":0,"dt":[1],"id":"c1","args":["a","b"]}"""),
        )
        assertEquals(2, deltas.size)
        assertTrue(deltas.all { (it as AssistantStreamExpander.Delta.ToolCall).name == null })
    }

    @Test
    fun `dt 长度与成员数不匹配时整条跳过`() {
        // 3 个成员却只有 1 个 dt（正确应是 2 个）→ 官方 validateRun 抛错，本实现跳过。
        val deltas = expander.expandRecord(
            obj("""{"type":"text-chunks","time0":0,"index":0,"dt":[1],"texts":["a","b","c"]}"""),
        )
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `空成员数组跳过`() {
        val deltas = expander.expandRecord(
            obj("""{"type":"text-chunks","time0":0,"index":0,"dt":[],"texts":[]}"""),
        )
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `缺 time0 或 index 跳过`() {
        assertTrue(
            expander.expandRecord(
                obj("""{"type":"text-chunks","index":0,"dt":[],"texts":["a"]}"""),
            ).isEmpty(),
        )
        assertTrue(
            expander.expandRecord(
                obj("""{"type":"text-chunks","time0":0,"dt":[],"texts":["a"]}"""),
            ).isEmpty(),
        )
    }

    @Test
    fun `tool-call-chunks 缺 id 跳过`() {
        assertTrue(
            expander.expandRecord(
                obj("""{"type":"tool-call-chunks","time0":0,"index":0,"dt":[],"args":["x"]}"""),
            ).isEmpty(),
        )
    }

    @Test
    fun `chunk 形态直接透传`() {
        val deltas = expander.expandRecord(
            obj("""{"type":"chunk","time":42,"chunk":{"type":"text-delta","index":3,"text":"hi"}}"""),
        )
        assertEquals(1, deltas.size)
        val raw = deltas[0] as AssistantStreamExpander.Delta.Raw
        assertEquals(42L, raw.time)
        assertEquals(3, raw.index)
    }

    @Test
    fun `未知记录类型跳过而不是抛异常`() {
        assertTrue(expander.expandRecord(obj("""{"type":"future-thing","time0":0}""")).isEmpty())
    }

    @Test
    fun `expand 接受非数组输入并返回空`() {
        assertTrue(expander.expand(null).isEmpty())
        assertTrue(expander.expand(json.parseToJsonElement("""{"not":"array"}""")).isEmpty())
    }

    @Test
    fun `expand 串联多条记录`() {
        val deltas = expander.expand(
            json.parseToJsonElement(
                """
                [{"type":"text-chunks","time0":0,"index":0,"dt":[],"texts":["A"]},
                 {"type":"chunk","time":9,"chunk":{"type":"block-end","index":0,"block":{"type":"text","text":"A"}}}]
                """.trimIndent(),
            ),
        )
        assertEquals(2, deltas.size)
        assertTrue(deltas[0] is AssistantStreamExpander.Delta.Text)
        assertTrue(deltas[1] is AssistantStreamExpander.Delta.Raw)
    }

    @Test
    fun `deltaOf 解析 text-delta`() {
        val delta = expander.deltaOf(77L, obj("""{"type":"text-delta","index":1,"text":"流"}"""))
        val text = delta as AssistantStreamExpander.Delta.Text
        assertEquals("流", text.text)
        assertEquals(1, text.index)
        assertEquals(77L, text.time)
    }

    @Test
    fun `deltaOf 解析 tool-call-delta`() {
        val delta = expander.deltaOf(
            5L,
            obj("""{"type":"tool-call-delta","index":0,"id":"c9","name":"write","argumentsDelta":"{\"a\""}"""),
        )
        val tool = delta as AssistantStreamExpander.Delta.ToolCall
        assertEquals("c9", tool.id)
        assertEquals("write", tool.name)
        assertEquals("{\"a\"", tool.argumentsDelta)
    }

    @Test
    fun `deltaOf 对 block-end 保留成 Raw 以便处理块边界`() {
        val delta = expander.deltaOf(
            3L,
            obj("""{"type":"block-end","index":0,"block":{"type":"text","text":"done"}}"""),
        )
        assertTrue(delta is AssistantStreamExpander.Delta.Raw)
    }
}
