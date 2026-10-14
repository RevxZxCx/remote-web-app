package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui/chat/ChatFold.kt` 的纯函数测试。
 *
 * 桌面端折叠逻辑逐字移植自 `dsh-client-ui-chat` 的 bundle，所以这里钉的是
 * **移植保真度**：映射表、并列顺序、键优先级、段落切分。
 */
class ChatFoldTest {

    // ---------- activityKind ----------

    @Test
    fun `activityKind 逐字映射桌面端的活动类别`() {
        assertEquals(WorkKind.READ, activityKind("read"))
        assertEquals(WorkKind.READ_IMAGE, activityKind("read_image"))
        assertEquals(WorkKind.SEARCH, activityKind("grep"))
        assertEquals(WorkKind.SEARCH, activityKind("glob"))
        assertEquals(WorkKind.SEARCH, activityKind("skill_inspect"))
        assertEquals(WorkKind.WRITE, activityKind("write"))
        assertEquals(WorkKind.EDIT, activityKind("edit"))
        assertEquals(WorkKind.EDIT, activityKind("apply_patch"))
        assertEquals(WorkKind.COMMANDS, activityKind("bash"))
        assertEquals(WorkKind.COMMANDS, activityKind("pwsh"))
        assertEquals(WorkKind.COMMANDS, activityKind("exec_command"))
        assertEquals(WorkKind.COMMANDS, activityKind("write_stdin"))
        assertEquals(WorkKind.CODE, activityKind("run_code"))
        assertEquals(WorkKind.WEB_SEARCH, activityKind("web_search"))
        assertEquals(WorkKind.WEB_FETCH, activityKind("web_fetch"))
        assertEquals(WorkKind.SUBAGENTS, activityKind("subagent"))
        assertEquals(WorkKind.SUBAGENTS, activityKind("subagent_list"))
        assertEquals(WorkKind.PLAN, activityKind("todo_write"))
        assertEquals(WorkKind.PLAN, activityKind("create_goal"))
        assertEquals(WorkKind.PLAN, activityKind("update_goal"))
        assertEquals(WorkKind.PLAN, activityKind("get_goal"))
        assertEquals(WorkKind.QUESTIONS, activityKind("ask_user_question"))
        assertEquals(WorkKind.QUESTIONS, activityKind("request_user_input"))
        // 未知工具名落到兜底类别，而不是抛异常——服务端会加新工具。
        assertEquals(WorkKind.TOOLS, activityKind("compress"))
        assertEquals(WorkKind.TOOLS, activityKind(""))
    }

    @Test
    fun `activityKind 的前缀判断落在精确判断之后`() {
        // `terminal_read` 在桌面端算 COMMANDS（`startsWith("terminal_")` 在后半段），
        // 但 `read` 本身是 READ。顺序写反就会把 `terminal_read` 误判成读取文件。
        assertEquals(WorkKind.COMMANDS, activityKind("terminal_read"))
        assertEquals(WorkKind.READ, activityKind("read"))
        // 命中的是 `endsWith("_inspect")`：`skill_inspect` 是 SEARCH，
        // 而只是包含 inspect 的 `inspect_skill` 不是（首跑我把两条写反了）。
        assertEquals(WorkKind.SEARCH, activityKind("skill_inspect"))
        assertEquals(WorkKind.TOOLS, activityKind("inspect_skill"))
    }

    // ---------- foldTitle ----------

    @Test
    fun `没有任何操作时标题是已完成分析`() {
        assertEquals("已完成分析", foldTitle(emptyList()))
    }

    @Test
    fun `单个类别直接用它的标签`() {
        assertEquals("已读取文件", foldTitle(listOf(WorkCount(WorkKind.READ, 3))))
        assertEquals("执行了命令", foldTitle(listOf(WorkCount(WorkKind.COMMANDS, 1))))
    }

    @Test
    fun `两个类别用并连接且去掉重复的已`() {
        val title = foldTitle(
            listOf(WorkCount(WorkKind.READ, 3), WorkCount(WorkKind.WRITE, 2)),
        )
        // 桌面端 `joinTwo`：两边都以「已」开头时第二个去掉前缀，
        // 否则会拼出「已读取文件并已写入文件」。
        assertEquals("已读取文件并写入文件", title)
    }

    @Test
    fun `两个类别前缀不同时不剥离`() {
        val title = foldTitle(
            listOf(WorkCount(WorkKind.READ, 3), WorkCount(WorkKind.COMMANDS, 2)),
        )
        assertEquals("已读取文件并执行了命令", title)
    }

    @Test
    fun `三个类别用逗号并列`() {
        val title = foldTitle(
            listOf(
                WorkCount(WorkKind.READ, 5),
                WorkCount(WorkKind.COMMANDS, 3),
                WorkCount(WorkKind.WRITE, 1),
            ),
        )
        assertEquals("已读取文件，执行了命令，已写入文件", title)
    }

    @Test
    fun `超过三个类别只取前三个并加等`() {
        val title = foldTitle(
            listOf(
                WorkCount(WorkKind.READ, 9),
                WorkCount(WorkKind.COMMANDS, 8),
                WorkCount(WorkKind.WRITE, 7),
                WorkCount(WorkKind.SEARCH, 6),
                WorkCount(WorkKind.EDIT, 5),
            ),
        )
        assertEquals("已读取文件，执行了命令，已写入文件等", title)
    }

    @Test
    fun `标题按次数降序而不是按传入顺序`() {
        val title = foldTitle(
            listOf(WorkCount(WorkKind.WRITE, 1), WorkCount(WorkKind.READ, 7)),
        )
        assertEquals("已读取文件并写入文件", title)
    }

    // ---------- liveToolDetail ----------

    @Test
    fun `参数不是合法 JSON 时退回工具名`() {
        // 流式过程中 arguments 是半截 JSON，`JSON.parse` 必炸——桌面端同样退回工具名。
        assertEquals("pwsh", liveToolDetail("pwsh", "{\"command\":\"git st"))
        assertEquals("pwsh", liveToolDetail("pwsh", null))
        assertEquals("pwsh", liveToolDetail("pwsh", ""))
        assertEquals("pwsh", liveToolDetail("pwsh", "[]"))
        assertEquals("pwsh", liveToolDetail("pwsh", "42"))
    }

    @Test
    fun `细节键按桌面端的优先级取第一个`() {
        // `title` 排在 `command` 前面，两个都给时取 title。
        val args = """{"title":"跑测试","command":"gradle test"}"""
        assertEquals("跑测试", liveToolDetail("bash", args))
    }

    @Test
    fun `文件类工具显示 file_path`() {
        assertEquals(
            "F:\\bot\\remote-web-app\\settings.gradle.kts",
            liveToolDetail("read", """{"file_path":"F:\\bot\\remote-web-app\\settings.gradle.kts","offset":1}"""),
        )
    }

    @Test
    fun `字符串数组用逗号连接`() {
        assertEquals(
            "a, b",
            liveToolDetail("grep", """{"pattern":["a","b"]}"""),
        )
    }

    @Test
    fun `非字符串数组被忽略并继续找下一个键`() {
        // `queries` 的值是对象数组 → 跳过；落到后面的 `query`。
        val args = """{"queries":[{"q":1}],"query":"kotlin"}"""
        assertEquals("kotlin", liveToolDetail("grep", args))
    }

    @Test
    fun `questions 从问题数组里取第一条`() {
        val args = """{"questions":[{"question":"要装到哪台设备？"},{"question":"第二个"}]}"""
        assertEquals("要装到哪台设备？", liveToolDetail("ask_user_question", args))
    }

    @Test
    fun `questions 里全是空问题时退回工具名`() {
        val args = """{"questions":[{"question":""},{"other":"x"}]}"""
        assertEquals("ask_user_question", liveToolDetail("ask_user_question", args))
    }

    @Test
    fun `细节超过 160 字符被截断并加省略号`() {
        val long = "x".repeat(400)
        val detail = liveToolDetail("read", """{"file_path":"$long"}""")
        assertEquals(160, detail.length)
        assertTrue(detail.endsWith("…"))
    }

    @Test
    fun `细节里的连续空白被折叠`() {
        assertEquals(
            "a b",
            liveToolDetail("bash", """{"command":"a\n\t b"}"""),
        )
    }

    // ---------- reasoningSummary ----------

    @Test
    fun `已沉淀的思考取全文第一行`() {
        assertEquals("先读一遍文件。", reasoningSummary("先读一遍文件。\n然后决定怎么改。", running = false))
    }

    @Test
    fun `已沉淀的思考去掉加粗标记`() {
        assertEquals("先读一遍文件。", reasoningSummary("**先读一遍文件。**\n第二行", running = false))
    }

    @Test
    fun `进行中的思考取最后一个完整段落的第一行`() {
        val text = "第一段第一行\n第一段第二行\n\n第二段第一行\n第二段第二行\n\n还在写的第三段"
        assertEquals(
            "第二段第一行",
            reasoningSummary(text, running = true),
        )
    }

    @Test
    fun `进行中的思考没有完整段落时退回第一行`() {
        assertEquals("唯一一段", reasoningSummary("唯一一段\n还在写", running = true))
    }

    @Test
    fun `思考摘要对空文本安全`() {
        assertEquals("", reasoningSummary("", running = false))
        assertEquals("", reasoningSummary("", running = true))
        assertEquals("", reasoningSummary("\n\n\n", running = true))
    }

    @Test
    fun `空行里有空白字符也算段落分隔`() {
        val text = "A\nx\n \n \nB\ny"
        assertEquals("B", reasoningSummary(text, running = true))
    }

    @Test
    fun `CRLF 的段落分隔同样生效`() {
        val text = "A\r\nx\r\n\r\nB\r\ny"
        assertEquals("B", reasoningSummary(text, running = true))
    }

    // ---------- foldChatItems ----------

    private fun user(seq: Long) = ChatItem.User(seq = seq, time = 0L, text = "u$seq")
    private fun toolCall(seq: Long, name: String = "read") =
        ChatItem.ToolCall(seq = seq, time = 0L, turn = 1L, step = 1L, callId = "c$seq", name = name, arguments = "{}")
    private fun toolResult(seq: Long) =
        ChatItem.ToolResult(seq = seq, time = 0L, turn = 1L, step = 1L, callId = "c$seq", text = "ok", isError = false)
    private fun reasoning(seq: Long, text: String = "想") =
        ChatItem.Assistant(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            blocks = listOf(ContentBlock.Reasoning(text)),
        )
    private fun reply(seq: Long) =
        ChatItem.Assistant(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            blocks = listOf(ContentBlock.Text("答案")),
        )
    private fun notice(seq: Long) = ChatItem.Notice(seq = seq, time = 0L, turn = 1L, text = "第 1 轮结束")
    private fun changes(seq: Long) = ChatItem.Changes(seq = seq, time = 0L, turn = 1L)

    @Test
    fun `空列表折成空行列表`() {
        assertEquals(emptyList<ChatRow>(), foldChatItems(emptyList()))
    }

    @Test
    fun `工具调用与结果被折进同一组`() {
        val rows = foldChatItems(listOf(toolCall(1), toolResult(2), toolCall(3), toolResult(4)))
        assertEquals(1, rows.size)
        val group = rows.single() as ChatRow.Group
        assertEquals(4, group.members.size)
        // key 是「封住这一组的独立条目的 seq」而不是 anchor：整列表都是过程项，
        // 这一组一直延伸到末尾，所以是 tail 哨兵（见 ChatRow.Group 的 KDoc）。
        assertEquals("group-tail", group.key)
    }

    @Test
    fun `用户消息是分组边界且单独成行`() {
        val rows = foldChatItems(listOf(toolCall(1), user(2), toolCall(3)))
        assertEquals(3, rows.size)
        assertTrue(rows[0] is ChatRow.Group)
        assertTrue(rows[1] is ChatRow.Single)
        assertTrue(rows[2] is ChatRow.Group)
        assertEquals(2L, rows[1].key)
    }

    @Test
    fun `含正文的助手消息单独成行并把前后的过程切开`() {
        val rows = foldChatItems(listOf(toolCall(1), reply(2), toolCall(3)))
        assertEquals(3, rows.size)
        assertEquals(1, (rows[0] as ChatRow.Group).members.size)
        assertEquals(2L, rows[1].key)
        assertEquals(1, (rows[2] as ChatRow.Group).members.size)
    }

    @Test
    fun `只含思考的助手消息留在分组里`() {
        val rows = foldChatItems(listOf(reasoning(1), toolCall(2)))
        assertEquals(1, rows.size)
        assertEquals(2, (rows.single() as ChatRow.Group).members.size)
    }

    @Test
    fun `提示行与改动卡片都是分组边界`() {
        val rows = foldChatItems(listOf(toolCall(1), notice(2), changes(3), toolCall(4)))
        assertEquals(4, rows.size)
        assertEquals(1, (rows[0] as ChatRow.Group).members.size)
        assertEquals(2L, rows[1].key)
        assertEquals(3L, rows[2].key)
        assertEquals(1, (rows[3] as ChatRow.Group).members.size)
    }

    @Test
    fun `中断的助手消息保留工具块所以仍算单独成行`() {
        // 被中止的消息 `visibleAssistantBlocks` 不过滤 tool-call 块，于是它有正文，
        // 按桌面端规则必须是独立行（那可能就是这条消息的全部内容）。
        val interrupted = ChatItem.Assistant(
            seq = 9, time = 0L, turn = 1L, step = 1L, interrupted = true,
            blocks = listOf(ContentBlock.ToolCall("c", "pwsh", "{}")),
        )
        val rows = foldChatItems(listOf(toolCall(1), interrupted))
        assertEquals(2, rows.size)
        assertEquals(9L, rows[1].key)
    }

    @Test
    fun `行 key 全局唯一`() {
        val items = listOf(
            toolCall(1), toolResult(2), user(3), reply(4),
            reasoning(5), toolCall(6), notice(7), changes(8), reply(9),
        )
        val keys = foldChatItems(items).map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    // ---------- summarizeGroup ----------

    @Test
    fun `分组摘要统计工具调用次数`() {
        val summary = summarizeGroup(
            listOf(toolCall(1, "read"), toolCall(2, "read"), toolCall(3, "write"), toolResult(4)),
            running = false,
        )
        assertEquals("已读取文件并写入文件", summary.title)
        assertEquals(2, summary.counts.first().count)
        assertEquals(WorkKind.READ, summary.counts.first().kind)
        assertEquals("", summary.detail)
    }

    @Test
    fun `纯思考的分组标题是已完成分析`() {
        val summary = summarizeGroup(listOf(reasoning(1), reasoning(2)), running = false)
        assertEquals("已完成分析", summary.title)
        assertTrue(summary.counts.isEmpty())
    }

    @Test
    fun `进行中的分组显示最后一个工具的细节`() {
        val summary = summarizeGroup(
            listOf(toolCall(1, "read"), toolCall(2, "pwsh")),
            running = true,
        )
        // 最后一个工具的参数是 `{}`，没有可用细节 → 退回工具名。
        assertEquals("pwsh", summary.detail)
    }

    @Test
    fun `进行中的分组没有工具时显示思考的最后一段`() {
        val summary = summarizeGroup(
            listOf(reasoning(1, "第一段\nx\n\n第二段\ny\n\n还在写")),
            running = true,
        )
        assertEquals("第二段", summary.detail)
    }

    @Test
    fun `已结束的分组不显示细节`() {
        val summary = summarizeGroup(listOf(toolCall(1, "pwsh")), running = false)
        assertEquals("", summary.detail)
    }

    @Test
    fun `次数相同时保持首次出现顺序`() {
        val summary = summarizeGroup(
            listOf(toolCall(1, "write"), toolCall(2, "read")),
            running = false,
        )
        // 桌面端用稳定的 `Array.prototype.sort`，Kotlin 的 sortedWith 同样稳定。
        assertEquals(listOf(WorkKind.WRITE, WorkKind.READ), summary.counts.map { it.kind })
        assertEquals("已写入文件并读取文件", summary.title)
    }

    @Test
    fun `畸形 arguments 不会让摘要抛异常`() {
        val broken = ChatItem.ToolCall(
            seq = 1, time = 0L, turn = 1L, step = 1L,
            callId = "c", name = "pwsh", arguments = "{\"command\":",
        )
        val summary = summarizeGroup(listOf(broken), running = true)
        assertEquals("pwsh", summary.detail)
    }

    @Test
    fun `空分组不会抛异常`() {
        val summary = summarizeGroup(emptyList(), running = true)
        assertEquals("已完成分析", summary.title)
        assertNull(summary.counts.firstOrNull())
        assertEquals("", summary.detail)
    }
}
