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
        // 吃参数的命令，插进输入框时带一个尾空格（R048，用户 m32662）。
        assertEquals("/goal ", commands[0].insertion)
        assertEquals("[<objective>|clear]", commands[0].hint)
        assertTrue(commands[0].acceptsAttachments)
        assertEquals("plan", commands[1].name)
        assertNull(commands[1].hint)
        // 没有 hint 按「不吃参数」处理——这是**启发式**（见 `SlashCommand.insertion` 的
        // KDoc）：`/n`、`/y` 没有 `input.hint` 却要求 `#编号`，也会落进这个分支。
        // 不补空格的实际收益只是编辑器里不多个空尾空格；发送前会 `trim()`，服务端收不到它。
        assertEquals("/plan", commands[1].insertion)
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
    fun composeWithoutArgumentKeepsTrailingSpaceForArgumentCommands() {
        // 吃参数的命令必须留一个尾空格（R048，用户 m32662）：光标落在末尾、
        // 用户接着打参数，没有这个空格就会粘成 `/goal完成`。
        val command = SlashCommand("goal", "设定目标", "[<objective>]", false, null)
        assertEquals("/goal ", CommandCatalogParser.compose(command, "   "))
    }

    @Test
    fun composeWithoutArgumentLeavesBareTriggerForParameterlessCommands() {
        // 不吃参数（`hint == null`）的命令不补空格：`/clear` 后面本来就什么都不该有，
        // 留个空尾空格只是编辑器里的噪声（发送前 `trim()`，服务端根本收不到它）。
        //
        // 注意这是启发式：`/n`、`/y` 没有 `input.hint` 却要求 `#编号`，它们会落进这个分支
        // ——见 `SlashCommand.insertion` 的 KDoc「已知缺口」。
        val command = SlashCommand("clear", "清空", null, false, null)
        assertEquals("/clear", CommandCatalogParser.compose(command, ""))
    }

    @Test
    fun composeWithArgumentKeepsSingleSpace() {
        // 带参分支不受影响：`/goal 完成 R043`，命令与参数之间恰好一个空格。
        val command = SlashCommand("goal", "设定目标", "[<objective>]", false, null)
        assertEquals("/goal 完成 R043", CommandCatalogParser.compose(command, "  完成 R043  "))
    }

    // -------------------------------------------------------------- 快捷入口

    @Test
    fun shortcutGoalInsertsTrailingSpace() {
        // 审查 R048 P1：兜底里曾写 `hint = null`，于是「计划模式」插入的 `/plan` 不带
        // 尾空格，用户打参数就粘成 `/plan重构登录`。这两条必须一直带尾空格。
        assertEquals("/goal ", CommandShortcuts.goal.insertion)
        assertEquals("/plan ", CommandShortcuts.plan.insertion)
    }

    @Test
    fun shortcutFallbacksMatchServerRegistration() {
        // 服务端 `commands/list` 实测（2026-10-23）：
        //   goal | definitionId=@deepseek-ai/dsh-command-goal | hint="[<objective>|clear|edit <objective>|pause|resume]" | att=true
        //   plan | definitionId=@deepseek-ai/dsh-plan-mode     | hint="[off|message]"                                    | att=true
        //
        // 逐字断言，不只看「不为空」：审查 R048 第二轮抓到 plan 的 definitionId 被写成
        // `@deepseek-ai/dsh-command-plan`（asar 里 0 次，真值是 `dsh-plan-mode` 40 次），
        // 当时因为测试只断言了尾空格与 attachments，这个倒退没被拦住。
        assertEquals("[<objective>|clear|edit <objective>|pause|resume]", CommandShortcuts.goal.hint)
        assertEquals("@deepseek-ai/dsh-command-goal", CommandShortcuts.goal.definitionId)
        assertTrue(CommandShortcuts.goal.acceptsAttachments)

        assertEquals("[off|message]", CommandShortcuts.plan.hint)
        assertEquals("@deepseek-ai/dsh-plan-mode", CommandShortcuts.plan.definitionId)
        assertTrue(CommandShortcuts.plan.acceptsAttachments)
    }

    @Test
    fun composeOnShortcutKeepsSpaceForArguments() {
        // 端到端：点「计划模式」后正文必须是 `/plan `，接着打「重构登录」才是命令 + 参数。
        assertEquals("/plan ", CommandCatalogParser.compose(CommandShortcuts.plan, ""))
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
