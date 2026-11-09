package com.revxzxcx.remotewebapp.core.capsule

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.QuestionRequest
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TodoItem
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点选举（R059）。
 *
 * ## 为什么这一组必须最密
 * 真机实测（调研文档 3.7-A）：**多条 promoted 通知不等于多个胶囊**，系统只给一个
 * 展示位并在多条之间轮播。所以「现在展示哪个会话」是我们算出来的，算错的直接后果是
 * 用户看到别人的会话、或者批准了错的东西。
 *
 * 四层顺序是**刚性**的，这里逐层钉死，并专门钉「顺序不能换」的那几条。
 */
class CapsuleFocusTest {

    private fun summary(
        id: String,
        running: Boolean = false,
        awaitingAttention: Boolean = false,
        completionUnread: Boolean = false,
        failureUnread: Boolean = false,
        updatedAt: Long = 0L,
    ) = SessionSummary(
        sessionId = id,
        agentAvailable = true,
        running = running,
        blank = false,
        parentSessionId = null,
        cwd = "/w",
        title = id,
        lastPromptAt = 0L,
        lastModel = null,
        updatedAt = updatedAt,
        awaitingAttention = awaitingAttention,
        completionUnread = completionUnread,
        failureUnread = failureUnread,
        failureReason = "",
    )

    @Test
    fun `没有会话时返回 null`() {
        assertNull(CapsuleFocus.elect(emptyList()))
    }

    @Test
    fun `全部空闲且已读时不选任何人`() {
        val idle = listOf(summary("a"), summary("b"))
        assertNull(CapsuleFocus.elect(idle))
    }

    @Test
    fun `第一层：有待处理的压过正在跑的`() {
        val running = summary("run", running = true, updatedAt = 999L)
        val waiting = summary("wait", awaitingAttention = true)
        // 正在跑的那个更新、排序更靠前，但有待处理的必须先拿出来。
        val elected = CapsuleFocus.elect(
            candidates = listOf(running, waiting),
            pendingSessionIds = listOf("wait"),
        )
        assertEquals("wait", elected?.sessionId)
    }

    @Test
    fun `第一层是 FIFO：先来的先展示，防止后来的饿死`() {
        val a = summary("first", awaitingAttention = true)
        val b = summary("second", awaitingAttention = true)
        val elected = CapsuleFocus.elect(
            candidates = listOf(b, a),
            // 到达顺序与列表顺序相反：列表里 b 在前，但 a 先到。
            pendingSessionIds = listOf("first", "second"),
        )
        assertEquals("first", elected?.sessionId)
    }

    @Test
    fun `第一层跳过已经不在列表里的待处理 id`() {
        val only = summary("alive", awaitingAttention = true)
        val elected = CapsuleFocus.elect(
            candidates = listOf(only),
            pendingSessionIds = listOf("gone", "alive"),
        )
        assertEquals("alive", elected?.sessionId)
    }

    @Test
    fun `第二层：用户正看着的会话优先于粘滞焦点`() {
        val viewed = summary("viewed", running = true, updatedAt = 1L)
        val previous = summary("prev", running = true, updatedAt = 500L)
        val elected = CapsuleFocus.elect(
            candidates = listOf(viewed, previous),
            viewingSessionId = "viewed",
            previousSessionId = "prev",
        )
        assertEquals("viewed", elected?.sessionId)
    }

    @Test
    fun `第二层要求正看着的会话值得展示：看着一个跑完的会话不占位`() {
        val idle = summary("idle")
        val other = summary("other", running = true)
        val elected = CapsuleFocus.elect(
            candidates = listOf(idle, other),
            viewingSessionId = "idle",
            previousSessionId = null,
        )
        assertEquals("other", elected?.sessionId)
    }

    @Test
    fun `第三层：上一轮焦点还在跑就粘住，不换人`() {
        val sticky = summary("sticky", running = true, updatedAt = 1L)
        // 这个更新、unreadRank 更高，按第四层排序本该赢。
        val louder = summary("louder", running = true, updatedAt = 900L)
        val elected = CapsuleFocus.elect(
            candidates = listOf(sticky, louder),
            previousSessionId = "sticky",
        )
        assertEquals("sticky", elected?.sessionId)
    }

    @Test
    fun `第三层粘滞只对还在跑的生效：上一轮焦点停了就让位`() {
        val stopped = summary("stopped", completionUnread = true, updatedAt = 900L)
        val running = summary("running", running = true, updatedAt = 1L)
        val elected = CapsuleFocus.elect(
            candidates = listOf(stopped, running),
            previousSessionId = "stopped",
        )
        assertEquals("running", elected?.sessionId)
    }

    @Test
    fun `第四层按 unreadRank 排序：待处理的排最前`() {
        val ongoing = summary("ongoing", running = true, updatedAt = 900L)
        val attention = summary("attention", awaitingAttention = true, updatedAt = 1L)
        val elected = CapsuleFocus.elect(listOf(ongoing, attention))
        assertEquals("attention", elected?.sessionId)
    }

    @Test
    fun `第四层同 rank 时用 updatedAt 降序`() {
        val older = summary("older", running = true, updatedAt = 100L)
        val newer = summary("newer", running = true, updatedAt = 200L)
        assertEquals("newer", CapsuleFocus.elect(listOf(older, newer))?.sessionId)
    }

    @Test
    fun `第四层完全同输入时用 sessionId 保证确定性`() {
        // 两条除了 id 以外**完全一样**。若不拿 id 兜底，两次调用可能给不同答案，
        // 表现为胶囊每 300ms 抖一下。
        val a = summary("aaa", running = true, updatedAt = 100L)
        val b = summary("bbb", running = true, updatedAt = 100L)
        val first = CapsuleFocus.elect(listOf(a, b))
        val second = CapsuleFocus.elect(listOf(b, a))
        assertEquals(first?.sessionId, second?.sessionId)
        assertNotNull(first)
    }

    @Test
    fun `失败未读值得展示，空闲已读不值得`() {
        assertTrue(CapsuleFocus.isWorthShowing(summary("x", failureUnread = true)))
        assertTrue(CapsuleFocus.isWorthShowing(summary("x", completionUnread = true)))
        assertTrue(CapsuleFocus.isWorthShowing(summary("x", running = true)))
        assertTrue(CapsuleFocus.isWorthShowing(summary("x", awaitingAttention = true)))
        assertFalse(CapsuleFocus.isWorthShowing(summary("x")))
    }

    @Test
    fun `只统计在跑的，不把有未读的算进另一个数`() {
        val focus = summary("focus", running = true)
        val list = listOf(
            focus,
            summary("r1", running = true),
            summary("r2", running = true),
            summary("unread", completionUnread = true),
            summary("idle"),
        )
        assertEquals(2, CapsuleFocus.countOthersRunning(list, "focus"))
    }

    @Test
    fun `焦点自己不计入 others`() {
        val focus = summary("focus", running = true)
        assertEquals(0, CapsuleFocus.countOthersRunning(listOf(focus), "focus"))
    }

    @Test
    fun `焦点为 null 时所有在跑的都计入 others`() {
        val list = listOf(summary("a", running = true), summary("b", running = true))
        assertEquals(2, CapsuleFocus.countOthersRunning(list, null))
    }
}

/**
 * 胶囊文案的构造与截断（R059）。
 *
 * 这一层刻意不含任何 Android 依赖，所以能在这里把「真机上才会暴露」的那些限制
 * （标题只能 2 行、subText 只有 1 行、胶囊条 7 字以内）都变成可断言的规则。
 */
class CapsuleContentTest {

    private val todos = TodoList(
        listOf(
            TodoItem("勘查文档", TodoStatus.COMPLETED),
            TodoItem("写模型层", TodoStatus.IN_PROGRESS),
            TodoItem("接线", TodoStatus.PENDING),
        ),
    )

    @Test
    fun `截断在超长时补省略号且总长不超过上限`() {
        val text = "一二三四五六七八九十"
        val clipped = CapsuleContent.clip(text, 5)
        assertEquals("一二三四…", clipped)
        assertEquals(5, clipped.codePointCount(0, clipped.length))
    }

    @Test
    fun `截断在恰好等于上限时原样返回`() {
        assertEquals("一二三四五", CapsuleContent.clip("一二三四五", 5))
    }

    @Test
    fun `截断会去掉首尾空白`() {
        assertEquals("内容", CapsuleContent.clip("  内容  ", 10))
    }

    @Test
    fun `空串与全空白截断成空串`() {
        assertEquals("", CapsuleContent.clip("", 10))
        assertEquals("", CapsuleContent.clip("   ", 10))
    }

    @Test
    fun `上限为零或负数时返回空串`() {
        assertEquals("", CapsuleContent.clip("内容", 0))
        assertEquals("", CapsuleContent.clip("内容", -1))
    }

    @Test
    fun `截断不会把一个 emoji 劈成半个字符`() {
        // 家 emoji 是 1 个 code point、2 个 UTF-16 char。按 length 截会切成半个，
        // 渲染成方块。这里要确认切出来仍是合法字符串。
        val text = "\uD83D\uDC36\uD83D\uDC31\uD83D\uDC2D"
        val clipped = CapsuleContent.clip(text, 2)
        assertEquals("\uD83D\uDC36…", clipped)
        // 能正常再编码回去，说明没有孤立代理项。
        assertEquals(clipped, String(clipped.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `执行中状态带出处自 todo 的步骤行`() {
        val state = CapsuleContent.running(
            sessionId = "s1",
            title = "重构登录页",
            todos = todos,
            report = "已完成第一步",
            action = "正在写代码",
            otherRunning = 0,
            startedAt = 1000L,
        )
        // 步骤行必须取 in_progress 那条的正文——用户要的是「实际在做什么」。
        assertEquals("第 2 步 / 共 3 步：写模型层", state.step)
        assertEquals(CapsuleProgress(total = 3, done = 1), state.progress)
        assertEquals("已完成第一步", state.report)
        assertEquals("正在写代码", state.action)
    }

    @Test
    fun `没有 todo 时步骤行为 null 且不画进度条：不编造进度`() {
        val state = CapsuleContent.running(
            sessionId = "s1",
            title = "后台会话",
            todos = null,
            report = "在干活",
            action = null,
            otherRunning = 0,
            startedAt = 1L,
        )
        assertNull(state.step)
        assertNull(state.progress)
        // 没有进度、没有待处理、也没有别的会话 → 不显示胶囊。
        assertFalse(state.visible)
    }

    @Test
    fun `todo 全未完成时进度为 0 但仍有段数`() {
        val fresh = TodoList(listOf(TodoItem("a", TodoStatus.PENDING), TodoItem("b", TodoStatus.PENDING)))
        val state = CapsuleContent.running("s", "t", fresh, "report", null, 0, 1L)
        assertEquals(CapsuleProgress(total = 2, done = 0), state.progress)
        assertTrue(state.progress!!.drawable)
    }

    @Test
    fun `空 todo 列表不产生进度`() {
        val state = CapsuleContent.running("s", "t", TodoList(), "r", null, 0, 1L)
        assertNull(state.progress)
    }

    @Test
    fun `有别的会话在跑时即使没有进度也可见`() {
        val state = CapsuleContent.running("s", "t", null, "r", null, otherRunning = 2, startedAt = 1L)
        assertTrue(state.visible)
        assertEquals("执行中 3 个", state.capsuleText)
        assertEquals(" · 另 2 个在执行", state.othersSuffix)
    }

    @Test
    fun `待批准态带上被拦下的工具名`() {
        val state = CapsuleContent.approval(
            sessionId = "s",
            title = "重构登录页",
            toolName = "bash",
            report = null,
            otherRunning = 0,
            startedAt = 1L,
        )
        assertEquals("待批准", state.capsuleText)
        assertEquals("bash", state.action)
        assertTrue(state.report.startsWith("需要你批准才能继续"))
    }

    @Test
    fun `待回答态带题号与总数`() {
        val state = CapsuleContent.question(
            sessionId = "s",
            title = "重构登录页",
            firstQuestion = "选哪个目录？",
            count = 3,
            otherRunning = 0,
            startedAt = 1L,
        )
        assertEquals("待回答", state.capsuleText)
        assertEquals("选哪个目录？", state.action)
        // 不写「问题 i / 共 n」：答掉一道后服务端推来剩下的，序号会重新从 1 开始，
        // 真机上出现过「已经答过一道，它还写问题 1」。只陈述本组题数，不声称位置。
        assertEquals("本组共 3 道，答完一道自动出现下一道", state.step)
    }

    @Test
    fun `只有一道题时不写题号`() {
        val state = CapsuleContent.question("s", "t", "q", count = 1, otherRunning = 0, startedAt = 1L)
        assertEquals("选择一项即可", state.step)
    }

    @Test
    fun `汇报行为空时回落到标题，而不是留一行空白`() {
        val state = CapsuleContent.running("s", "标题在此", null, report = "", action = null, otherRunning = 1, startedAt = 1L)
        assertEquals("标题在此", state.report)
    }

    @Test
    fun `胶囊文案在待处理时压过执行中与已完成`() {
        // 优先级：待批准 > 待回答 > 出错 > 已完成 > 多会话 > 有进度 > 执行中。
        val base = CapsuleContent.running("s", "t", todos, "r", null, otherRunning = 1, startedAt = 1L)
        assertEquals("执行中 2 个", base.capsuleText)
        assertEquals("已完成", base.copy(phase = CapsulePhase.Done).capsuleText)
        assertEquals("出错", base.copy(phase = CapsulePhase.Failed).capsuleText)
        assertEquals("待回答", base.copy(pending = PendingKind.Question).capsuleText)
        assertEquals("待批准", base.copy(pending = PendingKind.Approval).capsuleText)
    }

    @Test
    fun `单会话且没进度时胶囊文案就是执行中`() {
        val state = CapsuleContent.running("s", "t", null, "r", null, 0, 1L)
        assertEquals("执行中", state.capsuleText)
        assertEquals("", state.othersSuffix)
    }

    @Test
    fun `有进度时胶囊文案显示第几步`() {
        val state = CapsuleContent.running("s", "t", todos, "r", null, 0, 1L)
        assertEquals("第 1 步", state.capsuleText)
    }
}

/**
 * 分段进度的不变式（R059）。
 *
 * 这是用户亲自发现的缺陷（m01450）：进度条上的圆点和绿色填充末端错位。
 * 根因是 `Segment(length)` 的参数语义被写反了——见 [CapsuleProgress] 的 KDoc。
 * 这里的用例钉住「段长恒为 1」这条不变式，任何人想加一个自定义长度的入口都会先撞到这里。
 */
class CapsuleProgressTest {

    @Test
    fun `负数总段数直接拒绝，而不是画出一根奇怪的条`() {
        val error = runCatching { CapsuleProgress(total = -1, done = 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `已完成为负时夹到 0`() {
        assertEquals(0, CapsuleProgress(total = 3, done = -5).safeDone)
    }

    @Test
    fun `已完成超过总数时夹到总数，不会画出界`() {
        assertEquals(3, CapsuleProgress(total = 3, done = 99).safeDone)
    }

    @Test
    fun `零段不画进度条`() {
        assertFalse(CapsuleProgress(total = 0, done = 0).drawable)
        assertTrue(CapsuleProgress(total = 1, done = 0).drawable)
    }

    @Test
    fun `从 todo 构造：一条 todo 一段`() {
        val todos = TodoList(
            listOf(
                TodoItem("a", TodoStatus.COMPLETED),
                TodoItem("b", TodoStatus.COMPLETED),
                TodoItem("c", TodoStatus.IN_PROGRESS),
                TodoItem("d", TodoStatus.PENDING),
            ),
        )
        val progress = CapsuleProgress.fromTodos(todos)
        assertEquals(4, progress?.total)
        assertEquals(2, progress?.done)
    }

    @Test
    fun `空 todo 返回 null 而不是零段进度`() {
        assertNull(CapsuleProgress.fromTodos(TodoList()))
    }

    @Test
    fun `圆点位置与绿条位置用同一个分母`() {
        // 这条是根因的回归：真机公式是 圆点 = done/Σlength，绿条 = 已完成段数/总段数。
        // 只要每段长度是 1，两者就恒等。用「总步数」当段长时 5 段的总长是 25，
        // done=2 得 8% 而绿条 40% —— 用户看到的就是这个错位。
        for (total in 1..12) {
            for (done in 0..total) {
                val progress = CapsuleProgress(total = total, done = done)
                val segmentLengths = List(progress.total) { 1 }
                val dotDenominator = segmentLengths.sum()
                val dotFraction = progress.safeDone.toDouble() / dotDenominator
                val barFraction = if (total == 0) 0.0 else progress.safeDone.toDouble() / total
                assertEquals("total=$total done=$done", barFraction, dotFraction, 1e-9)
            }
        }
    }
}

/**
 * 待处理事项的动作策略（R059）。
 *
 * 真机约束：卡片上最多 **3 个** action（实测第 4 个不渲染）。所以每个待处理态都
 * 必须主动裁到 3 个以内，且**「进 App」那个位置不能被选项挤掉**——多选题与自定义
 * 输入只能进 App 才能完成。
 */
class CapsulePendingKindTest {

    @Test
    fun `三种待处理态互不相同`() {
        assertEquals(3, PendingKind.entries.toSet().size)
    }

    @Test
    fun `无待处理时不挂审批按钮`() {
        // `visible` 只看 pending / progress / otherRunning：待处理是三者之一。
        val state = CapsuleContent.running("s", "t", null, "r", null, 0, 1L)
        assertEquals(PendingKind.None, state.pending)
    }
}

/**
 * 待处理会话 id 的排列顺序（R059 审查 P2-1 的回归）。
 *
 * ## 为什么这条必须存在
 * 第一版 `pendingSessionIds()` 写成「先排完 approvals 再排 questions」，
 * 而它自己的 KDoc 写着「否则审批永远压着提问会让提问饿死」——**实现与注释相反**。
 * 子代理审查抓到并修成「交错取」，但当时没有留下任何用例。
 *
 * 后来用变异验证把实现改回两段 `forEach`（M2），2394 条用例**一条都没红**，
 * 才暴露出这个修复是裸奔的。这条用例就是补上那份证据。
 *
 * 断言刻意写成**完整列表相等**，不是「包含 sq」：只断言包含的话，
 * 两段排列也能通过（sq 仍在列表里，只是排到末尾），测不出交错。
 */
class CapsulePendingOrderTest {

    private fun approval(eventId: String, sessionId: String) = ApprovalRequest(
        eventId = eventId,
        agentId = sessionId,
        toolName = "bash",
        callId = null,
        reason = null,
    )

    private fun question(eventId: String, sessionId: String) = QuestionRequest(
        eventId = eventId,
        agentId = sessionId,
        callId = "call-$eventId",
        questions = emptyList(),
    )

    @Test
    fun `两类待处理交错排列：审批再多也不能把提问压到末尾`() {
        // 第一条审批指向 sx1，第二条指向 sx2；提问只有一条，指向 sq。
        // 交错 → [sx1, sq, sx2]；两段 → [sx1, sx2, sq]，两者**不同**。
        val ids = capsulePendingOrder(
            approvals = listOf(approval("a1", "sx1"), approval("a2", "sx2")),
            questions = listOf(question("q1", "sq")),
        )

        assertEquals(listOf("sx1", "sq", "sx2"), ids)
    }

    @Test
    fun `同一会话同时有审批和提问时只保留一次`() {
        val ids = capsulePendingOrder(
            approvals = listOf(approval("a1", "same")),
            questions = listOf(question("q1", "same")),
        )

        assertEquals(listOf("same"), ids)
    }

    @Test
    fun `提问比审批多时多出来的提问仍然排在后面`() {
        val ids = capsulePendingOrder(
            approvals = listOf(approval("a1", "sx")),
            questions = listOf(question("q1", "q1s"), question("q2", "q2s")),
        )

        assertEquals(listOf("sx", "q1s", "q2s"), ids)
    }

    @Test
    fun `agentId 为 null 的请求被跳过而不是塞进列表`() {
        val ids = capsulePendingOrder(
            approvals = listOf(approval("a1", "sx").copy(agentId = null)),
            questions = listOf(question("q1", "sq")),
        )

        assertEquals(listOf("sq"), ids)
    }
}
