package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [firstArgumentHint] 的纯函数测试（R028b）。
 *
 * 它决定折叠分组里每条工具行右侧显示什么摘要。三条契约：
 * ① **不能**把 `null` 显示成字符串 `"null"`——`JsonNull` 也是 `JsonPrimitive` 的子类，
 *    `content` 就是字面量 `"null"`（R007 在 `ToolFilePaths` 里踩过同一个坑）；
 * ② 空对象 `{}` 与「全是 null 的对象」没有可显示的参数，必须留空而不是显示 `{}`；
 * ③ 只有「压根不是 JSON」时才退回原始字符串——那说明服务端给的不是 JSON，原样显示
 *    反而是最有用的信息。
 */
class FirstArgumentHintTest {

    @Test
    fun `空与空白参数返回空串`() {
        assertEquals("", firstArgumentHint("read", ""))
        assertEquals("", firstArgumentHint("read", "   "))
    }

    @Test
    fun `null 值不能被显示成字符串 null`() {
        // 这是本轮实测发现的缺陷：修复前返回字面量 "null"。
        assertEquals("", firstArgumentHint("read", """{"file_path":null}"""))
        assertEquals("", firstArgumentHint("read", """{"a":null,"b":null}"""))
    }

    @Test
    fun `空对象返回空串而不是一对大括号`() {
        // 修复前返回 "{}"——摘要栏里出现一对空括号，比留空更让人困惑。
        assertEquals("", firstArgumentHint("pwsh", "{}"))
    }

    @Test
    fun `不是 JSON 时退回原始字符串`() {
        // 服务端给的不是 JSON：原样显示是最有用的信息（总比空白好）。
        assertEquals("not a json", firstArgumentHint("pwsh", "not a json"))
        assertEquals("{broken", firstArgumentHint("pwsh", "{broken"))
    }

    @Test
    fun `优先取命令字段`() {
        assertEquals(
            "ls -la",
            firstArgumentHint("pwsh", """{"opts":{"a":1},"cmd":"ls -la"}"""),
        )
        assertEquals(
            "gradlew.bat build",
            firstArgumentHint("pwsh", """{"command":"gradlew.bat build"}"""),
        )
    }

    @Test
    fun `没有已知字段时取第一个标量`() {
        assertEquals("30", firstArgumentHint("pwsh", """{"timeout":30}"""))
        assertEquals("true", firstArgumentHint("pwsh", """{"ok":true}"""))
        assertEquals(
            "query-text",
            firstArgumentHint("grep", """{"unknown_key":"query-text"}"""),
        )
    }

    @Test
    fun `键序不影响结果`() {
        // 实测同一个 `write` 的 arguments 键序都不固定（一次 file_path 在前、
        // 一次 content 在前）——这是「必须 JSON 解析、不能正则捞」的根据。
        val a = firstArgumentHint("write", """{"file_path":"x.kt","content":"y"}""")
        val b = firstArgumentHint("write", """{"content":"y","file_path":"x.kt"}""")
        assertEquals("x.kt", a)
        assertEquals(a, b)
    }

    @Test
    fun `摘要会被截断且不超长`() {
        val long = "a".repeat(200)
        val hint = firstArgumentHint("pwsh", """{"command":"$long"}""")
        assertEquals(80, hint.length)
    }

    @Test
    fun `换行与连续空白被归一化`() {
        assertEquals("a b c", firstArgumentHint("pwsh", "a\n\n  b\tc"))
    }
}
