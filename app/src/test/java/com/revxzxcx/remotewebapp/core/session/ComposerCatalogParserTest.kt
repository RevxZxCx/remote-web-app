package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令清单与文件引用的解析（R043 第 2 条，用户 m26743）。
 *
 * 夹具照抄真实响应：`commands/list` 实测 19 条，`input.attachments` 只有部分命令
 * 带、`hint` 有带也有不带；`fileReferences/list` 的 `kind` 只有 `"file"` 与
 * `"directory"` 两种。
 */
class ComposerCatalogParserTest {

    private fun json(raw: String) = Json.parseToJsonElement(raw)

    // ---------------------------------------------------------------- 命令

    @Test
    fun parsesCommandsWithHintAndAttachments() {
        val commands = CommandCatalogParser.parse(
            json(
                """
                {"value":[
                  {"name":"goal","description":"设定目标","input":{"hint":"[<objective>|clear]","attachments":"true"}},
                  {"name":"plan","description":"计划模式"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, commands.size)
        assertEquals("goal", commands[0].name)
        assertEquals("/goal", commands[0].trigger)
        assertEquals("[<objective>|clear]", commands[0].hint)
        assertTrue(commands[0].acceptsAttachments)
        assertEquals("plan", commands[1].name)
        assertNull(commands[1].hint)
        assertTrue(!commands[1].acceptsAttachments)
    }

    @Test
    fun emptyHintBecomesNull() {
        // 服务端对「没有参数」的命令会给空串而不是省略字段。空串当成「有参数」
        // 会让提示条渲染成一整块空白。
        val commands = CommandCatalogParser.parse(
            json("""{"value":[{"name":"a","description":"d","input":{"hint":""}}]}"""),
        )
        assertNull(commands.single().hint)
    }

    @Test
    fun oneBadEntryDoesNotDropTheWholeList() {
        // 真实清单里掺了没有 name 的条目时，逐条 mapNotNull，坏的影响不到好的。
        val commands = CommandCatalogParser.parse(
            json(
                """
                {"value":[
                  {"description":"没有 name"},
                  {"name":"btw","description":"顺便说一句"},
                  "不是对象"
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("btw"), commands.map { it.name })
    }

    @Test
    fun acceptsResultWrapperToo() {
        // 不同端点包一层的习惯不一致：`commands/list` 是 `value`，
        // 别的端点是 `result`，所以解析器三个都认。
        val commands = CommandCatalogParser.parse(
            json("""{"result":[{"name":"x","description":"d"}]}"""),
        )
        assertEquals("x", commands.single().name)
    }

    @Test
    fun acceptsBareArray() {
        val commands = CommandCatalogParser.parse(
            json("""[{"name":"y","description":"d"}]"""),
        )
        assertEquals("y", commands.single().name)
    }

    @Test
    fun malformedPayloadYieldsEmptyList() {
        assertTrue(CommandCatalogParser.parse(json("""{"value":"not-an-array"}""")).isEmpty())
        assertTrue(CommandCatalogParser.parse(null).isEmpty())
    }

    @Test
    fun definitionIdIsOptional() {
        val commands = CommandCatalogParser.parse(
            json("""{"value":[{"name":"review","description":"d","definitionId":"@deepseek-ai/dsh-review"}]}"""),
        )
        assertEquals("@deepseek-ai/dsh-review", commands.single().definitionId)
    }

    // ---------------------------------------------------------------- compose

    @Test
    fun composeJoinsNameAndArgument() {
        val command = SlashCommand("goal", "设定目标", null, false, null)
        assertEquals("/goal 完成 R043", CommandCatalogParser.compose(command, "完成 R043"))
    }

    @Test
    fun composeWithoutArgumentIsBareTrigger() {
        // 末尾不留空格：多出来的空格会进服务端的命令解析器，
        // 对 `clear`/`pause` 这类无参命令是噪声。
        val command = SlashCommand("goal", "设定目标", null, false, null)
        assertEquals("/goal", CommandCatalogParser.compose(command, "   "))
    }

    // ---------------------------------------------------------------- 引用

    @Test
    fun parsesFileReferences() {
        val refs = FileReferenceParser.parse(
            json(
                """
                {"value":[
                  {"path":"remote-web-app/docs/R043-效果图.html","kind":"file"},
                  {"path":"remote-web-app/docs","kind":"directory"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, refs.size)
        assertEquals("remote-web-app/docs/R043-效果图.html", refs[0].path)
        assertTrue(!refs[0].isDirectory)
        assertTrue(refs[1].isDirectory)
    }

    @Test
    fun bareArrayOfReferencesIsAccepted() {
        // `callRaw` 已把回包的 `result.value` 拆出来，所以真实输入是裸数组。
        // 首版只认 `{value: […]}`，裸数组被静默当成空列表，引用选择器
        // 在真机上一直显示「没有可引用的文件」。
        val refs = FileReferenceParser.parse(
            json("""[{"path":"a.kt","kind":"file"},{"path":"docs","kind":"directory"}]"""),
        )
        assertEquals(listOf("a.kt", "docs"), refs.map { it.path })
        assertTrue(refs[1].isDirectory)
    }

    @Test
    fun referenceWithBlankPathIsDropped() {
        // 空路径拼成 `@` 会变成一条无意义的引用，发出去服务端也解析不了。
        val refs = FileReferenceParser.parse(
            json("""{"value":[{"path":"","kind":"file"},{"path":"a.kt","kind":"file"}]}"""),
        )
        assertEquals(listOf("a.kt"), refs.map { it.path })
    }

    @Test
    fun referenceKindOtherThanDirectoryIsFile() {
        // 只有 `"directory"` 是目录；未知 kind 一律当文件，不能反过来
        // （当目录会让用户以为能展开）。
        val refs = FileReferenceParser.parse(
            json("""{"value":[{"path":"a","kind":"symlink"}]}"""),
        )
        assertTrue(!refs.single().isDirectory)
    }
}
