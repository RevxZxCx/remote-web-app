package com.revxzxcx.remotewebapp.core.changes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChangesParser] 的单测。
 *
 * 这里用的 JSON 是从**真实运行实例**上抓下来的形状（`ChangesModel.kt` 顶部注释记录的
 * seq=6237 那次探针），不是照 Kotlin 数据类反推的。之所以强调这点：路由回的是
 * **裸 JSON**（不是 Typert 信封），字段由桌面端插件拼装，所以会有两件反直觉的事——
 *  1. diff 里的存在性字段线上就叫 `before` / `after`，**不是** `beforeExists` / `afterExists`；
 *  2. 摘要里的 `total` 是「截断前的文件总数」，与 `files.size` **可以不等**。
 *
 * 解析器的立场是「全部宽容、解析不出返回 null」，所以大量用例在钉死**降级路径**。
 */
class ChangesParserTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ------------------------------------------------------------------ summary 正常路径

    @Test
    fun `解析真实形状的 summary`() {
        val summary = ChangesParser.summary(
            json(
                """{"turn":6,"total":19,"added":2314,"deleted":110,
                   "files":[{"path":"_dexcheck.js","display":"_dexcheck.js","added":55,"deleted":0,
                             "binary":false,"oversized":false}]}""",
            ),
        )!!

        assertEquals(6L, summary.turn)
        assertEquals(19, summary.total)
        assertEquals(2314, summary.added)
        assertEquals(110, summary.deleted)
        assertEquals(1, summary.files.size)

        val file = summary.files.single()
        assertEquals("_dexcheck.js", file.path)
        assertEquals("_dexcheck.js", file.display)
        assertEquals(55, file.added)
        assertEquals(0, file.deleted)
        assertFalse(file.binary)
        assertFalse(file.oversized)
    }

    @Test
    fun `total 是截断前的总数所以与 files 条数无关`() {
        // 实测 total=19 而 files 可能有上限（默认 500）；这里用 total=19 / 1 条来钉死语义。
        val summary = ChangesParser.summary(
            json("""{"turn":6,"total":19,"added":1,"deleted":1,"files":[{"path":"a"}]}"""),
        )!!

        assertEquals(19, summary.total)
        assertEquals(1, summary.files.size)
        assertTrue("total 不是 files.size", summary.total != summary.files.size)
    }

    @Test
    fun `缺 total added deleted 时由 files 推导`() {
        val summary = ChangesParser.summary(
            json(
                """{"turn":6,"files":[
                     {"path":"a","added":3,"deleted":1},
                     {"path":"b","added":2,"deleted":4}]}""",
            ),
        )!!

        assertEquals(2, summary.total)
        assertEquals(5, summary.added)
        assertEquals(5, summary.deleted)
    }

    @Test
    fun `只有 turn 也能解析出空摘要`() {
        val summary = ChangesParser.summary(json("""{"turn":7}"""))!!

        assertEquals(7L, summary.turn)
        assertEquals(0, summary.total)
        assertEquals(0, summary.added)
        assertEquals(0, summary.deleted)
        assertTrue(summary.files.isEmpty())
    }

    @Test
    fun `files 中的布尔与数字字段缺失时取默认值`() {
        val file = ChangesParser.summary(json("""{"turn":1,"files":[{"path":"a"}]}"""))!!.files.single()

        assertEquals("a", file.path)
        // display 缺省时退回 path —— UI 的展示与点击都不能出现空标签。
        assertEquals("a", file.display)
        assertEquals(0, file.added)
        assertEquals(0, file.deleted)
        assertFalse(file.binary)
        assertFalse(file.oversized)
    }

    // ------------------------------------------------------------------ summary 降级路径

    @Test
    fun `缺 turn 时返回 null`() {
        // turn 是这一轮改动的唯一标识（后续 changes.diff 要用它），缺了就整条都不要。
        assertNull(ChangesParser.summary(json("""{"total":19,"files":[]}""")))
        assertNull(ChangesParser.summary(json("""{"turn":null}""")))
        assertNull(ChangesParser.summary(json("""{"turn":"abc"}""")))
    }

    @Test
    fun `path 为空的文件条目被丢弃`() {
        val summary = ChangesParser.summary(
            json("""{"turn":1,"files":[{"path":"ok"},{"path":""},{"display":"only-display"},{"path":null}]}"""),
        )!!

        // 没有 path 的条目无法点开（后续 diff / readBytes 都要 path），保留它只会得到死行。
        assertEquals(listOf("ok"), summary.files.map { it.path })
    }

    @Test
    fun `display 缺失或为空时回退到 path`() {
        val files = ChangesParser.summary(
            json("""{"turn":1,"files":[{"path":"a/b.kt"},{"path":"c.kt","display":""},{"path":"d.kt","display":"D"}]}"""),
        )!!.files

        assertEquals(listOf("a/b.kt", "c.kt", "D"), files.map { it.display })
        assertEquals(listOf("a/b.kt", "c.kt", "d.kt"), files.map { it.path })
    }

    @Test
    fun `负的增删行数被钳到 0`() {
        // 服务端给负数没有意义，显示成「+-3 -0」只会让人以为解析坏了。
        val summary = ChangesParser.summary(
            json("""{"turn":1,"total":1,"added":-3,"deleted":-1,"files":[{"path":"a","added":-2,"deleted":-5}]}"""),
        )!!

        assertEquals(0, summary.added)
        assertEquals(0, summary.deleted)
        assertEquals(0, summary.files.single().added)
        assertEquals(0, summary.files.single().deleted)
    }

    @Test
    fun `total 小于 files 条数时被抬到 files 条数`() {
        // total 是「截断前的完整数」，不可能比已列出的还少；否则 UI 会显示「还有 -2 个」。
        val summary = ChangesParser.summary(
            json("""{"turn":1,"total":1,"files":[{"path":"a"},{"path":"b"},{"path":"c"}]}"""),
        )!!

        assertEquals(3, summary.total)
    }

    @Test
    fun `files 缺失或不是数组时得到空列表`() {
        assertTrue(ChangesParser.summary(json("""{"turn":1}"""))!!.files.isEmpty())
        assertTrue(ChangesParser.summary(json("""{"turn":1,"files":null}"""))!!.files.isEmpty())
        assertTrue(ChangesParser.summary(json("""{"turn":1,"files":"nope"}"""))!!.files.isEmpty())
        // 数组里混入非对象元素时逐条丢弃，而不是整条摘要作废。
        assertEquals(
            listOf("a"),
            ChangesParser.summary(json("""{"turn":1,"files":["str",1,null,{"path":"a"}]}"""))!!.files.map { it.path },
        )
    }

    @Test
    fun `输入不是对象时返回 null`() {
        assertNull(ChangesParser.summary(json("\"nope\"")))
        assertNull(ChangesParser.summary(json("""[1,2]""")))
        assertNull(ChangesParser.summary(json("""123""")))
        assertNull(ChangesParser.summary(JsonNull))
    }

    @Test
    fun `数字写成字符串时仍能解析`() {
        // 实际行为：long()/int() 用的是 JsonPrimitive.content.toLongOrNull()，
        // 不看 JSON 类型，所以 `"19"` 与 `19` 等价。这是宽容策略的一部分（也意味着
        // 服务端把数字改成字符串不会崩）。
        val summary = ChangesParser.summary(
            json("""{"turn":"6","total":"19","added":"2314","deleted":"110","files":[]}"""),
        )!!

        assertEquals(6L, summary.turn)
        assertEquals(19, summary.total)
        assertEquals(2314, summary.added)
        assertEquals(110, summary.deleted)
    }

    @Test
    fun `布尔写成字符串时仍能解析`() {
        val file = ChangesParser.summary(
            json("""{"turn":1,"files":[{"path":"a","binary":"true","oversized":"true"}]}"""),
        )!!.files.single()

        // toBooleanStrictOrNull() 只看字面量，不看 JSON 类型。
        assertTrue(file.binary)
        assertTrue(file.oversized)
        // 非法布尔字面量不抛异常，退化为 false。
        assertFalse(
            ChangesParser.summary(json("""{"turn":1,"files":[{"path":"a","binary":1}]}"""))!!.files.single().binary,
        )
    }

    // ------------------------------------------------------------------ diff：text

    @Test
    fun `解析真实形状的 text diff`() {
        val diff = ChangesParser.diff(
            json(
                """{"kind":"text","path":"_d.js","display":"_d.js","before":false,"after":true,
                   "hunks":[{"oldStart":1,"oldLines":0,"newStart":1,"newLines":14,
                             "lines":["+const fs = require('fs');"]}],
                   "coarse":false}""",
            ),
        )

        val text = diff as? ChangesDiff.Text ?: error("应当是 ChangesDiff.Text，实际是 $diff")
        assertEquals("_d.js", text.path)
        assertEquals("_d.js", text.display)
        assertFalse(text.beforeExists)
        assertTrue(text.afterExists)
        assertFalse(text.coarse)
        assertEquals(1, text.hunks.size)

        val hunk = text.hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(0, hunk.oldLines)
        assertEquals(1, hunk.newStart)
        assertEquals(14, hunk.newLines)
        assertEquals(listOf("+const fs = require('fs');"), hunk.lines)
        assertEquals("@@ -1,0 +1,14 @@", hunk.header)
    }

    @Test
    fun `diff 的线上键是 before 与 after 而不是 beforeExists`() {
        // 这是最容易写错的一处：Kotlin 数据类字段叫 beforeExists/afterExists，
        // 但**线上 JSON 的键**是 `before`/`after`。写错键名的后果是「新建/删除」标记恒为 false，
        // 而且不会报错——所以这里正面钉死，并顺带证明长名字的键会被忽略。
        val correct = ChangesParser.diff(json("""{"kind":"text","path":"a","before":true,"after":false}""")) as ChangesDiff.Text
        assertTrue(correct.beforeExists)
        assertFalse(correct.afterExists)

        val wrongKeys = ChangesParser.diff(
            json("""{"kind":"text","path":"a","beforeExists":true,"afterExists":true}"""),
        ) as ChangesDiff.Text
        assertFalse("beforeExists 不是线上键，读了它就会恒为 false", wrongKeys.beforeExists)
        assertFalse("afterExists 不是线上键，读了它就会恒为 false", wrongKeys.afterExists)
    }

    @Test
    fun `text diff 的 before after coarse 缺失时默认 false`() {
        val text = ChangesParser.diff(json("""{"kind":"text","path":"a"}""")) as ChangesDiff.Text

        assertFalse(text.beforeExists)
        assertFalse(text.afterExists)
        assertFalse(text.coarse)
        assertTrue(text.hunks.isEmpty())
        assertEquals("a", text.display)
    }

    @Test
    fun `coarse 为真表示整文件替换`() {
        val text = ChangesParser.diff(
            json(
                """{"kind":"text","path":"big.js","coarse":true,
                   "hunks":[{"oldStart":1,"oldLines":0,"newStart":1,"newLines":900,"lines":["+x"]}]}""",
            ),
        ) as ChangesDiff.Text

        assertTrue(text.coarse)
        assertEquals(1, text.hunks.size)
    }

    @Test
    fun `缺少 path 的 diff 返回 null`() {
        assertNull(ChangesParser.diff(json("""{"kind":"text","display":"x"}""")))
        assertNull(ChangesParser.diff(json("""{"kind":"text","path":""}""")))
    }

    // ------------------------------------------------------------------ diff：binary / oversized / 未知

    @Test
    fun `kind 为 binary 时得到 Binary`() {
        val diff = ChangesParser.diff(json("""{"kind":"binary","path":"logo.png","display":"logo.png"}"""))

        val binary = diff as? ChangesDiff.Binary ?: error("应当是 Binary，实际是 $diff")
        assertEquals("logo.png", binary.path)
        assertEquals("logo.png", binary.display)
    }

    @Test
    fun `kind 为 oversized 时得到 Oversized`() {
        val diff = ChangesParser.diff(json("""{"kind":"oversized","path":"big.bin"}"""))

        val oversized = diff as? ChangesDiff.Oversized ?: error("应当是 Oversized，实际是 $diff")
        assertEquals("big.bin", oversized.path)
        assertEquals("big.bin", oversized.display)
    }

    @Test
    fun `未知 kind 返回 null 而不是当成文本`() {
        // 宁可显示「没有可预览的差异」，也不要把不认识的形状误渲染成文本 diff。
        assertNull(ChangesParser.diff(json("""{"kind":"future-kind","path":"a"}""")))
        assertNull(ChangesParser.diff(json("""{"kind":null,"path":"a"}""")))
        assertNull(ChangesParser.diff(json("""{"path":"a"}""")))
    }

    @Test
    fun `diff 输入不是对象时返回 null`() {
        assertNull(ChangesParser.diff(json("\"nope\"")))
        assertNull(ChangesParser.diff(json("""[]""")))
        assertNull(ChangesParser.diff(JsonNull))
    }

    // ------------------------------------------------------------------ hunk 过滤

    @Test
    fun `不以加减空格开头的行被丢弃`() {
        val text = ChangesParser.diff(
            json(
                """{"kind":"text","path":"a","hunks":[{"oldStart":1,"oldLines":3,"newStart":1,"newLines":3,
                   "lines":["+add","-del"," ctx","","裸行","@@ -1,3 +1,3 @@"]}]}""",
            ),
        ) as ChangesDiff.Text

        // 空串补成上下文空行（否则渲染行数少于 hunk 头声明的行数，行号会错位）；
        // 「裸行」与「@@」这种没有 `+`/`-`/空格 前缀的行仍被丢掉。
        assertEquals(listOf("+add", "-del", " ctx", " "), text.hunks.single().lines)
    }

    @Test
    fun `非字符串的行元素被丢弃`() {
        val text = ChangesParser.diff(
            json("""{"kind":"text","path":"a","hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,
                   "lines":["+ok",1,null,{"a":1},"-ok2"]}]}"""),
        ) as ChangesDiff.Text

        assertEquals(listOf("+ok", "-ok2"), text.hunks.single().lines)
    }

    @Test
    fun `只有一个加号或一个空格的行被保留`() {
        val text = ChangesParser.diff(
            json("""{"kind":"text","path":"a","hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,
                   "lines":["+"," ","-"]}]}"""),
        ) as ChangesDiff.Text

        assertEquals(listOf("+", " ", "-"), text.hunks.single().lines)
    }

    @Test
    fun `缺少任一数字字段的 hunk 被丢弃`() {
        // 与 `dsh-client-ui-deliverables/lib/client.js:52-61` 的 isHunk 一致：四个都要有。
        // 注意 key 必须带引号——测试用的 Json 不是 lenient 到允许裸键的程度，
        // 拼裸键会得到 JsonDecodingException 而不是「字段缺失」。
        fun textWithHunkFields(fields: String): ChangesDiff.Text =
            ChangesParser.diff(
                json("""{"kind":"text","path":"a","hunks":[$fields]}"""),
            ) as ChangesDiff.Text

        val full = """{"oldStart":1,"oldLines":0,"newStart":1,"newLines":14,"lines":["+x"]}"""
        assertEquals(1, textWithHunkFields(full).hunks.size)

        assertEquals(0, textWithHunkFields("""{"oldLines":0,"newStart":1,"newLines":14,"lines":["+x"]}""").hunks.size)
        assertEquals(0, textWithHunkFields("""{"oldStart":1,"newStart":1,"newLines":14,"lines":["+x"]}""").hunks.size)
        assertEquals(0, textWithHunkFields("""{"oldStart":1,"oldLines":0,"newLines":14,"lines":["+x"]}""").hunks.size)
        assertEquals(0, textWithHunkFields("""{"oldStart":1,"oldLines":0,"newStart":1,"lines":["+x"]}""").hunks.size)
    }

    @Test
    fun `负数行号或负行数的 hunk 被丢弃`() {
        fun hunksOf(fields: String): Int =
            (ChangesParser.diff(
                json("""{"kind":"text","path":"a","hunks":[$fields]}"""),
            ) as ChangesDiff.Text).hunks.size

        fun hunk(vararg fields: String): String = fields.joinToString(",", "{", "}")

        assertEquals(0, hunksOf(hunk(""""oldStart":-1,"oldLines":0,"newStart":1,"newLines":1,"lines":["+x"]""")))
        assertEquals(0, hunksOf(hunk(""""oldStart":1,"oldLines":-1,"newStart":1,"newLines":1,"lines":["+x"]""")))
        assertEquals(0, hunksOf(hunk(""""oldStart":1,"oldLines":0,"newStart":-1,"newLines":1,"lines":["+x"]""")))
        assertEquals(0, hunksOf(hunk(""""oldStart":1,"oldLines":0,"newStart":1,"newLines":-1,"lines":["+x"]""")))
        // 0 是合法值（纯插入/纯删除）。
        assertEquals(1, hunksOf(hunk(""""oldStart":1,"oldLines":0,"newStart":1,"newLines":0,"lines":["+x"]""")))
    }

    @Test
    fun `hunks 缺失或元素非法时得到空列表或部分结果`() {
        assertEquals(0, (ChangesParser.diff(json("""{"kind":"text","path":"a"}""")) as ChangesDiff.Text).hunks.size)
        assertEquals(
            0,
            (ChangesParser.diff(json("""{"kind":"text","path":"a","hunks":"nope"}""")) as ChangesDiff.Text).hunks.size,
        )
        // 一条合法 + 一条非法：非法的丢，合法的留。
        val mixed = ChangesParser.diff(
            json(
                """{"kind":"text","path":"a","hunks":[
                     {"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["+ok"]},
                     "nope",
                     {"oldStart":1,"lines":["+no"]}]}""",
            ),
        ) as ChangesDiff.Text
        assertEquals(1, mixed.hunks.size)
        assertEquals(listOf("+ok"), mixed.hunks.single().lines)
    }

    @Test
    fun `hunk 的 lines 缺失时行列表为空而不是丢弃整段`() {
        // 纯删除/纯插入的 hunk 可能没有上下文行。
        val hunk = (ChangesParser.diff(
            json("""{"kind":"text","path":"a","hunks":[{"oldStart":1,"oldLines":3,"newStart":1,"newLines":0}]}"""),
        ) as ChangesDiff.Text).hunks.single()

        assertTrue(hunk.lines.isEmpty())
        assertEquals("@@ -1,3 +1,0 @@", hunk.header)
    }

    @Test
    fun `DiffHunk header 的格式`() {
        assertEquals("@@ -1,0 +1,14 @@", DiffHunk(1, 0, 1, 14, emptyList()).header)
        assertEquals("@@ -120,7 +118,9 @@", DiffHunk(120, 7, 118, 9, emptyList()).header)
    }

    // ------------------------------------------------------------------ 畸形输入

    @Test
    fun `畸形 JSON 文本在 JSON 层就失败而不是在解析器里抛异常`() {
        // 解析器的入参已经是 JsonElement（调用方负责 parseToJsonElement），所以这里
        // 明确记录：畸形文本死于调用方的 runCatching，解析器本身对任何 JsonElement 都不抛。
        assertTrue(runCatching { Json.parseToJsonElement("{not json") }.isFailure)

        // 解析器能拿到的一切畸形形态都返回 null。
        for (element in listOf<JsonElement>(JsonNull, json("\"str\""), json("""[1]"""), json("""1"""))) {
            assertNull(ChangesParser.summary(element))
            assertNull(ChangesParser.diff(element))
        }
    }

    @Test
    fun `字段为 null 时按缺失处理`() {
        // JSON null 也是 JsonPrimitive，content 是字面量 "null"；
        // longOrNull/intOrNull 对它取不到值，于是按「字段缺失」走派生逻辑。
        assertNull(ChangesParser.summary(json("""{"turn":null,"files":[]}""")))
        val summary = ChangesParser.summary(
            json("""{"turn":1,"total":null,"added":null,"deleted":null,"files":[{"path":"a","added":2,"deleted":1}]}"""),
        )!!
        assertEquals(1, summary.total)
        assertEquals(2, summary.added)
        assertEquals(1, summary.deleted)

        val text = ChangesParser.diff(
            json("""{"kind":"text","path":"a","before":null,"after":null,"coarse":null,"hunks":null}"""),
        ) as ChangesDiff.Text
        assertFalse(text.beforeExists)
        assertFalse(text.afterExists)
        assertFalse(text.coarse)
        assertTrue(text.hunks.isEmpty())
    }
}
