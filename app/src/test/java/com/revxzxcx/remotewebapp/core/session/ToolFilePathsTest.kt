package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolFilePaths] 的单测。
 *
 * 这个类存在的理由本身就是一条测试依据：`arguments` 是**JSON 字符串**，而且**键序不固定**——
 * 真机实测（2026-10-13，51 个会话全量日志）里两个 `write` 调用，一个把 `file_path` 放在最前、
 * 另一个把 `content` 放在最前。所以下面专门有两组「同样内容、不同键序」的用例：它们钉死的是
 * **实现必须 JSON 解析，而不能用正则去捞 `"file_path"` 前面的东西**。谁要是哪天把它改成正则，
 * `content 在前` 的那一组会立刻失败——这正是这组用例的价值。
 *
 * 另一条同样重要的语义：`read` **不算改动**（[ToolFilePaths.isMutating] 为 false）。读取成功
 * 不代表文件被改过，把它算进去会让「这一轮改了哪些文件」凭空多出一堆只读过的文件。
 */
class ToolFilePathsTest {

    // ------------------------------------------------------------------ extract：正常路径

    @Test
    fun `write 从 arguments 里取出 file_path`() {
        assertEquals(
            "a/b.kt",
            ToolFilePaths.extract("write", """{"file_path":"a/b.kt","content":"x"}"""),
        )
    }

    @Test
    fun `键序不影响结果 file_path 在前`() {
        // 实测样本 A：file_path 在最前。
        assertEquals(
            "F:\\bot\\remote-web-app\\app\\src\\main\\Main.kt",
            ToolFilePaths.extract(
                "write",
                """{"file_path":"F:\\bot\\remote-web-app\\app\\src\\main\\Main.kt","content":"package x"}""",
            ),
        )
    }

    @Test
    fun `键序不影响结果 content 在前`() {
        // 实测样本 B：同一次会话里 content 跑到最前。正则实现在这里必然出错，
        // 所以这条用例就是「必须 JSON 解析」的回归护栏。
        assertEquals(
            "F:\\bot\\remote-web-app\\app\\src\\main\\Main.kt",
            ToolFilePaths.extract(
                "write",
                """{"content":"package x","file_path":"F:\\bot\\remote-web-app\\app\\src\\main\\Main.kt"}""",
            ),
        )
    }

    @Test
    fun `两种键序得到完全相同的路径`() {
        val path = "src/a/b.kt"
        val a = ToolFilePaths.extract("write", """{"file_path":"$path","content":"x"}""")
        val b = ToolFilePaths.extract("write", """{"content":"x","file_path":"$path"}""")

        assertEquals(a, b)
        assertEquals(path, a)
    }

    @Test
    fun `edit 与 read 同样走 file_path`() {
        assertEquals(
            "a/b.kt",
            ToolFilePaths.extract("edit", """{"file_path":"a/b.kt","old_string":"a","new_string":"b"}"""),
        )
        assertEquals(
            "a/b.kt",
            ToolFilePaths.extract("read", """{"file_path":"a/b.kt","offset":1,"limit":50}"""),
        )
    }

    @Test
    fun `multi_edit 的 operations 在前时也能取到 file_path`() {
        assertEquals(
            "lib/x.kt",
            ToolFilePaths.extract(
                "multi_edit",
                """{"operations":[{"old_string":"a","new_string":"b"}],"file_path":"lib/x.kt"}""",
            ),
        )
    }

    @Test
    fun `支持全部备选参数名`() {
        // PATH_KEYS = file_path / path / filePath / notebook_path（顺序即优先级）。
        // `path` 与 `notebook_path` 是给桌面端可能启用的 str_replace_editor 留的；
        // 多认一个键的代价远小于静默丢一行改动。
        assertEquals("p1", ToolFilePaths.extract("str_replace_editor", """{"path":"p1","command":"view"}"""))
        assertEquals("p2", ToolFilePaths.extract("write", """{"filePath":"p2","content":"x"}"""))
        assertEquals("p3", ToolFilePaths.extract("edit", """{"notebook_path":"p3"}"""))
        assertEquals("p4", ToolFilePaths.extract("create_file", """{"file_path":"p4","content":"x"}"""))
        assertEquals("p5", ToolFilePaths.extract("apply_patch", """{"path":"p5"}"""))
    }

    @Test
    fun `多个参数名同时出现时按 file_path path filePath notebook_path 的优先级取第一个`() {
        assertEquals(
            "优先取 file_path",
            "from-file-path",
            ToolFilePaths.extract("write", """{"notebook_path":"n","filePath":"fp","path":"p","file_path":"from-file-path"}"""),
        )
        assertEquals("p", ToolFilePaths.extract("write", """{"notebook_path":"n","path":"p"}"""))
        assertEquals("fp", ToolFilePaths.extract("write", """{"notebook_path":"n","filePath":"fp"}"""))
    }

    @Test
    fun `路径两侧的空白被裁掉`() {
        assertEquals("a/b.kt", ToolFilePaths.extract("write", """{"file_path":"  a/b.kt  "}"""))
    }

    @Test
    fun `arguments 里混有未知字段时不受影响`() {
        assertEquals(
            "a.kt",
            ToolFilePaths.extract("write", """{"file_path":"a.kt","content":"x","brandNewField":{"deep":[1,2]}}"""),
        )
    }

    // ------------------------------------------------------------------ extract：非文件工具

    @Test
    fun `非文件工具一律返回 null 即使 arguments 里有 file_path`() {
        // 这是刻意为之：`pwsh`/`grep` 的参数里出现 `file_path` 样的键（比如脚本正文里的 JSON）
        // 不代表这个工具动了那个文件，据此给聊天流加文件行会全是噪声。
        val looksLikeFileCall = """{"file_path":"a/b.kt","content":"x"}"""
        for (tool in listOf("pwsh", "grep", "compress", "glob", "todo_write", "decompress", "job_kill", "search_context")) {
            assertNull("$tool 不是文件工具", ToolFilePaths.extract(tool, looksLikeFileCall))
        }
    }

    @Test
    fun `pwsh 的脚本正文里含 file_path 时依然为 null`() {
        assertNull(
            ToolFilePaths.extract(
                "pwsh",
                """{"command":"Write-Output '{\"file_path\":\"a.kt\"}'","description":"echo"}""",
            ),
        )
    }

    // ------------------------------------------------------------------ extract：畸形输入

    @Test
    fun `畸形或空的 arguments 返回 null 且不抛异常`() {
        // 一条畸形的工具调用不该让整个聊天流崩掉。
        assertNull(ToolFilePaths.extract("write", null))
        assertNull(ToolFilePaths.extract("write", ""))
        assertNull(ToolFilePaths.extract("write", "   "))
        assertNull(ToolFilePaths.extract("write", "{}"))
        assertNull(ToolFilePaths.extract("write", "not json"))
        assertNull(ToolFilePaths.extract("write", "{not json"))
        assertNull(ToolFilePaths.extract("write", "[1,2]"))
        assertNull(ToolFilePaths.extract("write", "\"a string\""))
        assertNull(ToolFilePaths.extract("write", "123"))
        assertNull(ToolFilePaths.extract("write", "null"))
    }

    @Test
    fun `file_path 为空字符串或空白时返回 null`() {
        assertNull(ToolFilePaths.extract("write", """{"file_path":""}"""))
        assertNull(ToolFilePaths.extract("write", """{"file_path":"   "}"""))
        assertNull(ToolFilePaths.extract("write", """{"content":"x"}"""))
    }

    @Test
    fun `file_path 为 JSON null 时返回 null`() {
        // R005 审查发现并修复：JsonNull 也是 JsonPrimitive，`content` 是字面量 "null"，
        // 不加 `takeIf { it.isString }` 守卫时 `{"file_path":null}` 会返回 "null"（长度 4、
        // 非空），下游拿它去请求文件，得到一个莫名其妙的 not-found。
        // 修复后与 `ChangesParser.string()` 的守卫一致。
        assertNull(ToolFilePaths.extract("write", """{"file_path":null}"""))
        assertNull(ToolFilePaths.extract("write", """{"path":null}"""))
        assertNull(ToolFilePaths.extract("write", """{"filePath":null}"""))
        assertNull(ToolFilePaths.extract("write", """{"notebook_path":null}"""))
    }

    @Test
    fun `参数名大小写敏感`() {
        // PATH_KEYS 都是小写，JSON 对象取键是精确匹配：`FILE_PATH` 取不到。
        assertNull(ToolFilePaths.extract("write", """{"FILE_PATH":"a.kt"}"""))
        assertNull(ToolFilePaths.extract("write", """{"File_Path":"a.kt"}"""))
    }

    @Test
    fun `file_path 不是字符串时返回 null 而不是字面量`() {
        // R005 审查发现并修复：取值走 `(obj[key] as? JsonPrimitive)?.content` 时
        // JsonPrimitive 不区分 JSON 类型，数字/布尔会被当成路径返回。
        assertNull(ToolFilePaths.extract("write", """{"file_path":123}"""))
        assertNull(ToolFilePaths.extract("write", """{"file_path":true}"""))
        assertNull(ToolFilePaths.extract("write", """{"file_path":1.5}"""))
        assertNull(ToolFilePaths.extract("write", """{"file_path":["a.kt"]}"""))
        assertNull(ToolFilePaths.extract("write", """{"file_path":{"a":1}}"""))
    }

    @Test
    fun `file_path 非字符串时继续尝试后面的键`() {
        // 守卫只决定「这个键算不算数」，不该中断整轮查找。
        assertEquals(
            "b.kt",
            ToolFilePaths.extract("write", """{"file_path":null,"path":"b.kt"}"""),
        )
        assertEquals(
            "c.kt",
            ToolFilePaths.extract("write", """{"file_path":7,"filePath":"c.kt"}"""),
        )
    }

    // ------------------------------------------------------------------ isMutating / isReading

    @Test
    fun `isMutating 认全部写类工具`() {
        for (tool in listOf("write", "edit", "str_replace_editor", "create_file", "multi_edit", "apply_patch")) {
            assertTrue("$tool 会改动文件", ToolFilePaths.isMutating(tool))
        }
    }

    @Test
    fun `read 不是改动工具`() {
        // 读不是写。这条是「改动列表里不该出现只读过的文件」的护栏。
        assertFalse(ToolFilePaths.isMutating("read"))
        assertFalse(ToolFilePaths.isMutating("read_file"))
    }

    @Test
    fun `isReading 只认读取类工具`() {
        assertTrue(ToolFilePaths.isReading("read"))
        assertTrue(ToolFilePaths.isReading("read_file"))
        // write 反过来不算「读」，两边不重叠。
        assertFalse(ToolFilePaths.isReading("write"))
        assertFalse(ToolFilePaths.isReading("pwsh"))
        assertFalse(ToolFilePaths.isReading("grep"))
    }

    @Test
    fun `工具名为空或未知时两边都是 false`() {
        for (tool in listOf("", "  ", "brand_new_tool", "kubectl")) {
            assertFalse(ToolFilePaths.isMutating(tool))
            assertFalse(ToolFilePaths.isReading(tool))
            assertFalse(ToolFilePaths.isFileTool(tool))
        }
    }

    @Test
    fun `工具名大小写不敏感`() {
        // 实现用了 lowercase() 归一化，所以大写也能认。
        assertTrue(ToolFilePaths.isMutating("Write"))
        assertTrue(ToolFilePaths.isMutating("EDIT"))
        assertTrue(ToolFilePaths.isReading("Read"))
        assertTrue(ToolFilePaths.isReading("Read_File"))
        assertEquals("a/b.kt", ToolFilePaths.extract("WRITE", """{"file_path":"a/b.kt"}"""))
        assertEquals("a/b.kt", ToolFilePaths.extract("Read", """{"file_path":"a/b.kt"}"""))
    }

    @Test
    fun `isFileTool 是读写两类的并集`() {
        assertTrue(ToolFilePaths.isFileTool("write"))
        assertTrue(ToolFilePaths.isFileTool("read"))
        assertTrue(ToolFilePaths.isFileTool("multi_edit"))
        assertTrue(ToolFilePaths.isFileTool("read_file"))
        assertFalse(ToolFilePaths.isFileTool("pwsh"))
        assertFalse(ToolFilePaths.isFileTool("compress"))
    }

    @Test
    fun `文件工具名单与 isFileTool 完全一致`() {
        // 两道门必须一致：extract 的第一行就是 `if (!isFileTool(toolName)) return null`，
        // 所以「能取到路径」的工具集合必须恰好等于「isFileTool 为真」的集合。
        val args = """{"file_path":"a/b.kt"}"""
        val accepted = listOf("write", "read", "edit", "str_replace_editor", "create_file", "multi_edit", "apply_patch", "read_file")
        val rejected = listOf("pwsh", "grep", "compress", "glob", "todo_write", "decompress", "job_kill")

        for (tool in accepted) {
            assertTrue("$tool 应当在文件工具名单里", ToolFilePaths.isFileTool(tool))
            assertEquals("a/b.kt", ToolFilePaths.extract(tool, args))
        }
        for (tool in rejected) {
            assertFalse("$tool 不该被当成文件工具", ToolFilePaths.isFileTool(tool))
            assertNull(ToolFilePaths.extract(tool, args))
        }
    }
}
