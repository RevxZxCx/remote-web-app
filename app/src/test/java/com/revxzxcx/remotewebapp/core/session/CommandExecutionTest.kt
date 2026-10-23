package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `commands/execute` 的判定与解析（R045）。
 *
 * 这些断言全部取自真实线上形状：`F:/bot` 会话日志里 `/skill-review` 与 `/skill-stats`
 * 各跑过两次，`command/done` 的 `kind` 只有 `success`，`text` 是命令自己的输出。
 */
class CommandExecutionTest {

    // ── 该不该走 commands/execute ────────────────────────────────────────────

    @Test
    fun knownCommandIsRoutedToTheCommandService() {
        assertTrue(isExecutableCommand("/export", setOf("export", "goal")))
    }

    @Test
    fun commandWithArgumentsIsStillRouted() {
        assertTrue(isExecutableCommand("/goal 把测试补全", setOf("goal")))
    }

    /**
     * 不认得的斜杠行**必须**当普通文本发出去。
     *
     * 服务端对未知命令回 `{ok:true}` 且没有 value——既没有结果、也没有错误。若这里
     * 判成命令，用户那条消息会凭空消失（不执行、不发送），比 R043 的行为更糟。
     */
    @Test
    fun unknownSlashLineFallsBackToPlainMessage() {
        assertFalse(isExecutableCommand("/tmp/foo.log 看一下", setOf("export")))
    }

    @Test
    fun emptyCommandListTreatsEverythingAsPlainText() {
        assertFalse(isExecutableCommand("/export", emptySet()))
    }

    @Test
    fun plainTextIsNeverACommand() {
        assertFalse(isExecutableCommand("帮我看下 /export 这个命令", setOf("export")))
    }

    @Test
    fun commandWordStopsAtWhitespace() {
        assertEquals("export", parseCommandWord("/export now"))
        assertEquals("goal", parseCommandWord("  /goal   写测试  "))
    }

    @Test
    fun loneSlashHasNoWord() {
        assertNull(parseCommandWord("/"))
        assertNull(parseCommandWord("/   "))
    }

    @Test
    fun nonSlashTextHasNoWord() {
        assertNull(parseCommandWord("hello"))
        assertNull(parseCommandWord(""))
    }

    // ── commands/execute 返回值 ──────────────────────────────────────────────

    @Test
    fun successResultCarriesText() {
        val raw = buildJsonObject {
            put("commandId", JsonPrimitive("cmd-661b45a5-1"))
            put(
                "result",
                buildJsonObject {
                    put("kind", JsonPrimitive("success"))
                    put("text", JsonPrimitive("No skills are managed by this plugin yet."))
                },
            )
        }
        val parsed = CommandExecutionParser.parse(raw)!!
        assertEquals("cmd-661b45a5-1", parsed.commandId)
        assertTrue(parsed.succeeded)
        assertEquals("No skills are managed by this plugin yet.", parsed.text)
    }

    @Test
    fun errorResultIsNotSuccess() {
        val raw = buildJsonObject {
            put("commandId", JsonPrimitive("cmd-1"))
            put(
                "result",
                buildJsonObject {
                    put("kind", JsonPrimitive("error"))
                    put("text", JsonPrimitive("missing argument"))
                },
            )
        }
        val parsed = CommandExecutionParser.parse(raw)!!
        assertFalse(parsed.succeeded)
        assertEquals("missing argument", parsed.text)
    }

    /**
     * `{ok:true}` 但没有 value —— 服务端不认这条命令。
     *
     * `callRaw` 对缺省的 value 返回 `JsonNull`，解析必须是 `null` 而不是抛异常：
     * 用户手打一个不存在的 `/foo` 不该让界面红一片。
     */
    @Test
    fun missingValueMeansUnknownCommand() {
        assertNull(CommandExecutionParser.parse(JsonNull))
        assertNull(CommandExecutionParser.parse(null))
    }

    @Test
    fun resultWithoutCommandIdIsNotAnExecution() {
        val raw = buildJsonObject { put("result", buildJsonObject { put("kind", JsonPrimitive("success")) }) }
        assertNull(CommandExecutionParser.parse(raw))
    }

    /** kind 缺失时按失败对待：反过来会把「执行出错」渲染成绿勾，比不显示更糟。 */
    @Test
    fun missingKindIsTreatedAsFailure() {
        val raw = buildJsonObject {
            put("commandId", JsonPrimitive("cmd-1"))
            put("result", buildJsonObject { put("text", JsonPrimitive("嗯")) })
        }
        assertFalse(CommandExecutionParser.parse(raw)!!.succeeded)
    }

    // ── command/run 与 command/done ──────────────────────────────────────────

    @Test
    fun runEventCarriesCommandNameAndArgs() {
        val run = CommandExecutionParser.parseRun(
            buildJsonObject {
                put("commandId", JsonPrimitive("cmd-1"))
                put("name", JsonPrimitive("skill-review"))
                put("args", JsonPrimitive(""))
            },
        )!!
        assertEquals("cmd-1", run.commandId)
        assertEquals("/skill-review", run.line)
    }

    @Test
    fun runEventWithArgsKeepsThemInTheLine() {
        val run = CommandExecutionParser.parseRun(
            buildJsonObject {
                put("commandId", JsonPrimitive("cmd-2"))
                put("name", JsonPrimitive("goal"))
                put("args", JsonPrimitive("把测试补全"))
            },
        )!!
        assertEquals("/goal 把测试补全", run.line)
    }

    /**
     * `command/done` **不带 name**（实测 `{commandId, kind, text}`）。
     *
     * 所以解析器只能给出 commandId，命令名必须靠 reducer 与 `command/run` 合并。
     * 这条用例把这个事实钉住：将来有人以为 done 里有 name，加断言时会立刻发现。
     */
    @Test
    fun doneEventHasNoName() {
        val data: JsonObject = buildJsonObject {
            put("commandId", JsonPrimitive("cmd-3"))
            put("kind", JsonPrimitive("success"))
            put("text", JsonPrimitive("done"))
        }
        assertNull(data["name"])
        val done = CommandExecutionParser.parseDone(data)!!
        assertEquals("cmd-3", done.commandId)
        assertTrue(done.succeeded)
        assertEquals("done", done.text)
    }

    @Test
    fun doneEventWithoutTextStillParses() {
        val done = CommandExecutionParser.parseDone(
            buildJsonObject {
                put("commandId", JsonPrimitive("cmd-4"))
                put("kind", JsonPrimitive("error"))
            },
        )!!
        assertFalse(done.succeeded)
        assertNull(done.text)
    }

    @Test
    fun eventsWithoutCommandIdAreDropped() {
        assertNull(CommandExecutionParser.parseRun(buildJsonObject { put("name", JsonPrimitive("x")) }))
        assertNull(CommandExecutionParser.parseDone(buildJsonObject { put("kind", JsonPrimitive("success")) }))
    }
}
