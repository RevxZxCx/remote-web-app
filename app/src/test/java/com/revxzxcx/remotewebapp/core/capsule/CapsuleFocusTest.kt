package com.revxzxcx.remotewebapp.core.capsule

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.QuestionRequest
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TodoItem
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    }

    /**
     * R059 第三轮审查 P1 的回归。
     *
     * ## 这条断言原来是反的
     * 上一版这里写的是 `assertFalse(state.visible)`，注释还理直气壮：
     * 「没有进度、没有待处理、也没有别的会话 → 不显示胶囊」。
     * 但**这正是最常见的主场景**——一个会话在跑、用户没打开它（所以拿不到 todo）、
     * 也没有别的会话。按那条断言，胶囊在这种情况下永远不出现，
     * 而胶囊存在的全部意义就是「我不在 App 里也能看见它在干活」。
     *
     * 断言把缺陷固化成了预期，所以连跑 2398 项全绿也发现不了。
     */
    @Test
    fun `单会话在跑但拿不到 todo 时仍然要显示：这是主场景`() {
        val state = CapsuleContent.running(
            sessionId = "s1",
            title = "后台会话",
            todos = null,
            report = "在干活",
            action = "跑测试",
            otherRunning = 0,
            startedAt = 1L,
            phase = CapsulePhase.Running,
        )
        assertTrue(state.visible)
        assertEquals("执行中", state.capsuleText)
    }

    @Test
    fun `空闲会话不显示胶囊：不是书签`() {
        val state = CapsuleContent.running(
            sessionId = "s1", title = "t", todos = null, report = "r",
            action = null, otherRunning = 0, startedAt = 1L,
            phase = CapsulePhase.Idle,
        )
        assertFalse(state.visible)
    }

    @Test
    fun `已完成未读也要显示：否则跑完了反而没提示`() {
        val state = CapsuleContent.running(
            sessionId = "s1", title = "t", todos = null, report = "r",
            action = null, otherRunning = 0, startedAt = 1L,
            phase = CapsulePhase.Done,
        )
        assertTrue(state.visible)
        assertEquals("已完成", state.capsuleText)
    }

    @Test
    fun `出错未读显示为出错而不是执行中`() {
        val state = CapsuleContent.running(
            sessionId = "s1", title = "t", todos = null, report = "r",
            action = null, otherRunning = 0, startedAt = 1L,
            phase = CapsulePhase.Failed,
        )
        assertTrue(state.visible)
        assertEquals("出错", state.capsuleText)
    }

    @Test
    fun `todo 全未完成时进度为 0 但仍有段数`() {
        val fresh = TodoList(listOf(TodoItem("a", TodoStatus.PENDING), TodoItem("b", TodoStatus.PENDING)))
        val state = CapsuleContent.running("s", "t", fresh, "report", null, 0, 1L)
        assertEquals(CapsuleProgress(total = 2, done = 0), state.progress)
        assertTrue(state.progress!!.drawable)
    }

    /**
     * R059 第三轮审查 P2 的回归：步骤行整行必须装得进一行 subText。
     *
     * 上一版只把 `STEP_MAX_CHARS` 作用在 todo 正文上，拼上前缀后整行能到 39 字，
     * 被系统从右边切掉——切掉的恰好是裁剪想保住的那几个字。
     */
    @Test
    fun `步骤行连同前缀不超过预算`() {
        val todos = TodoList(
            listOf(
                TodoItem("一", TodoStatus.COMPLETED),
                TodoItem("这是一个特别特别长的待办事项正文用来把整行撑爆", TodoStatus.IN_PROGRESS),
            ),
        )
        val state = CapsuleContent.running("s", "t", todos, "r", null, 0, 1L)
        val step = state.step
        assertNotNull(step)
        assertTrue(
            "步骤行整行 ${step!!.length} 字，超了 ${CapsuleContent.STEP_MAX_CHARS} 字预算：$step",
            step.length <= CapsuleContent.STEP_MAX_CHARS,
        )
        assertTrue("正文被切了但没留省略号：$step", step.endsWith("…"))
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
        // B018 之后 report 直接就是会话名：胶囊条上已经是「待批准」，
        // 卡片第一行再说一遍那句 9 字前缀，真机上被挤成「需要你批准才能继续：按…」，
        // 剩下一个字毫无信息量。省下的空间留给会话名。
        assertEquals("重构登录页", state.report)
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
    fun `汇报行为空时留空而不是回落到会话标题`() {
        // B018 之前的写法是 `report.orEmpty().ifEmpty { title }`，于是「这一帧没有汇报」
        // 会变成「把会话标题抄到第一行」：第一行永远有字，而第二行的动作因为没有新内容
        // 而消失，用户看到的就是「标题一直是那个，操作一会儿就没了」。
        // 会话标题本来也不是汇报——它是用户自己起的名字，抄过来是纯噪音。
        val state = CapsuleContent.running("s", "标题在此", null, report = "", action = null, otherRunning = 1, startedAt = 1L)
        assertTrue(state.report.isBlank())
    }

    @Test
    fun `汇报行的字数上限与操作行不同 否则两行会互相挤`() {
        // 变异体：把 clipReportForCapsule 的默认上限改成 ACTION_MAX_CHARS 时，
        // 这条必须红。两行可用宽度不同（汇报是整句人话，操作是工具名），
        // 共用一个上限要么砍汇报太狠、要么让操作行超出可用宽度。
        val long = "字".repeat(60)
        val report = clipReportForCapsule(long)
        val action = clipForCapsule(long)
        assertEquals(CapsuleContent.REPORT_MAX_CHARS, report.length)
        assertEquals(CapsuleContent.ACTION_MAX_CHARS, action.length)
        assertNotEquals(CapsuleContent.REPORT_MAX_CHARS, CapsuleContent.ACTION_MAX_CHARS)
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
        assertEquals("执行中", state.capsuleText)
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

class CapsuleStickyTextTest {

    @Test
    fun `没有新内容时保留上一次 而不是清空`() {
        assertEquals("正在改 ChatViewModel", stickyText("正在改 ChatViewModel", null))
    }

    @Test
    fun `空串与空白也算没有新内容 否则空帧会把上一行顶掉`() {
        assertEquals("上一句汇报", stickyText("上一句汇报", ""))
        assertEquals("上一句汇报", stickyText("上一句汇报", "   "))
    }

    @Test
    fun `有新的就换成新的`() {
        assertEquals("新的一句", stickyText("旧的一句", "新的一句"))
    }

    @Test
    fun `首次没有历史时返回 null 而不是空串`() {
        assertNull(stickyText(null, null))
        assertNull(stickyText(null, ""))
    }

    @Test
    fun `两行各自独立粘滞 一行更新不该把另一行顶掉`() {
        var report: String? = "汇报 A"
        var action: String? = "bash"
        // 只来新汇报
        report = stickyText(report, "汇报 B")
        action = stickyText(action, null)
        assertEquals("汇报 B", report)
        assertEquals("bash", action)
        // 只来新操作
        report = stickyText(report, null)
        action = stickyText(action, "grep")
        assertEquals("汇报 B", report)
        assertEquals("grep", action)
    }
}

class CapsuleTitleTest {

    @Test
    fun `两行都在时按汇报在前操作在后拼成两行`() {
        assertEquals("汇报 A" + "\n" + "▸ bash", capsuleTitle("汇报 A", "bash"))
    }

    @Test
    fun `同时有操作行时 汇报必须压到一行 否则操作行会被挤没`() {
        val longReport = "这是一句非常非常长的汇报文本内容会超过一行"
        val title = capsuleTitle(longReport, "bash")
        val lines = title.split("\n")
        assertEquals(2, lines.size)
        // 第一行必须在一行的容量以内，且带省略号
        assertEquals(CapsuleContent.TITLE_LINE_MAX_CHARS, lines[0].length)
        assertTrue(lines[0].endsWith("…"))
        assertTrue(lines[1].startsWith("▸ "))
    }

    @Test
    fun `只有汇报时 不再额外压缩`() {
        // 没有操作行就不该再砍到一行：多显示几个字是纯收益。
        // 注意上游（CapsuleContent.running / approval / question）已经按
        // REPORT_MAX_CHARS 截过一次，capsuleTitle 不应重复截。
        val report = "abcdefghijklmnopqrstuvwxyz"
        val title = capsuleTitle(report, null)
        assertEquals(1, title.split("\n").size)
        assertEquals(report, title)
    }

    @Test
    fun `两行都空时用会话名顶上 而不是交白卷`() {
        // 真机实测：换轮次瞬间 LiveAttempt 被清空、新的汇报还没到，标题成了空串，
        // 系统仍按两行排版，卡片上半块整片空白。拿会话名顶上比空白强。
        assertEquals("某个会话", capsuleTitle(null, null, fallback = "某个会话"))
        assertEquals("某个会话", capsuleTitle("", "  ", fallback = "某个会话"))
    }

    @Test
    fun `有内容时优先用自己的内容 不用会话名`() {
        assertEquals("汇报 A", capsuleTitle("汇报 A", null, fallback = "某个会话"))
    }

    @Test
    fun `会话名也是空时保持空串 不能凭空造字`() {
        assertEquals("", capsuleTitle(null, null, fallback = null))
        assertEquals("", capsuleTitle(null, null, fallback = "   "))
    }

    @Test
    fun `没有汇报时不能以换行开头 否则白占掉第一行`() {
        // 旧写法是 report、换行、动作三段直接相加，report 为空时得到「换行 + ▸ 动作」：
        // 第一行是空的，系统仍按两行排版，真正的内容被挤到第二行。
        val title = capsuleTitle("", "bash")
        assertEquals("▸ bash", title)
        assertFalse(title.startsWith("\n"))
    }

    @Test
    fun `没有操作时只有汇报一行`() {
        assertEquals("汇报 A", capsuleTitle("汇报 A", null))
    }

    @Test
    fun `两行都没有时是空串 而不是一个孤零零的换行`() {
        assertEquals("", capsuleTitle(null, null))
        assertEquals("", capsuleTitle("   ", "  "))
    }

    @Test
    fun `空白行也算没有内容 不能被拼进去`() {
        assertEquals("▸ bash", capsuleTitle("   ", "bash"))
        assertEquals("汇报 A", capsuleTitle("汇报 A", "   "))
    }
/**
 * B018、审查第一轮 P2：粘滞的轮次边界。
 *
 * 这条逻辑很短（一个 `&&`），但它决定的事情不小：同一会话跑第二轮时，
 * 卡片上该不该还挂着上一轮的末句与最后一个工具名。
 * 写测试而不是直接写在协程里，是因为后者测不到（同 [CapsuleStickyTextTest]）。
 */
class CapsuleBeginsNewTurnTest {

    @Test
    fun `上一帧在跑 这一帧不跑 不是新轮开始`() {
        assertFalse(beginsNewTurn(wasRunning = true, nowRunning = false))
    }

    @Test
    fun `上一帧不跑 这一帧在跑 才是新轮开始`() {
        assertTrue(beginsNewTurn(wasRunning = false, nowRunning = true))
    }

    @Test
    fun `一直在跑的轮内不能反复认为是新轮 否则粘滞被取消`() {
        assertFalse(beginsNewTurn(wasRunning = true, nowRunning = true))
    }

    @Test
    fun `一直不在跑就没有新轮`() {
        assertFalse(beginsNewTurn(wasRunning = false, nowRunning = false))
    }
}

}

/**
 * R062：工具名 → 人话。
 *
 * 用户原话：「而不是显示 bash、write 此类名称」。
 */
class CapsuleToolPhraseTest {

    @Test
    fun `命令执行类工具都说正在运行命令`() {
        listOf("bash", "shell", "ops_bash", "terminal", "BASH").forEach {
            assertEquals("正在运行命令…", toolPhrase(it))
        }
    }

    @Test
    fun `读写文件类工具分开说`() {
        assertEquals("正在读取文件…", toolPhrase("read"))
        assertEquals("正在读取文件…", toolPhrase("ops_read_files"))
        assertEquals("正在写入文件…", toolPhrase("write"))
        assertEquals("正在写入文件…", toolPhrase("edit"))
    }

    @Test
    fun `未知工具不显示原名 而是统一兜底`() {
        val phrase = toolPhrase("some_weird_tool_vendor_specific")
        assertEquals("正在调用工具…", phrase)
        // 原名不能泄漏到展示文案里。
        assertFalse(phrase.contains("some_weird_tool"))
    }

    @Test
    fun `没有工具在走时说正在思考`() {
        assertEquals(THINKING_TEXT, toolPhrase(null))
        assertEquals(THINKING_TEXT, toolPhrase(""))
        assertEquals(THINKING_TEXT, toolPhrase("   "))
    }

    @Test
    fun `工具名不区分大小写也不在意首尾空格`() {
        assertEquals(toolPhrase("bash"), toolPhrase("  BaSh  "))
    }

    @Test
    fun `短语长度必须在一行预算内 否则用户会一直看到截断`() {
        // 真机：标题一行 12 字。带 "▸ " 与 "×NN" 后仍要能放下。
        val samples = listOf(
            "bash", "read", "write", "grep", "web_search", "task", "todo_write", null,
        )
        samples.forEach { name ->
            val shown = actionLine(name, 13)
            // "▸ " + 短语(<=7) + "×13"(=3) <= 12
            assertTrue(
                "动作行太长会被系统截断: $shown",
                shown.length + 2 <= CapsuleContent.TITLE_LINE_MAX_CHARS,
            )
        }
    }
    @Test
    fun `上网类工具要说联网 不能落到万能兼容那句`() {
        // 审查点名的一批工具名里带 search 但实际上网，
        // 旧写法让它们全部落到「正在调用工具…」——用户看不出区别。
        val online = listOf(
            "multi_search", "advanced_search", "platform_search",
            "domain_search", "cache_search", "free_search_test", "video_search",
        )
        online.forEach { assertEquals(it, "正在联网查询…", toolPhrase(it)) }
    }

    @Test
    fun `多智能体协作要说派子代理 不能落到万能兼容`() {
        // 第三轮审查 P3：只有真的在创建子代理才能说「派子代理」。
        // 把「查看 / 中断 / 等待」也写成「派子代理」，方向是相反的。
        listOf("task", "subagent", "subagent_fork", "spawn_teammate").forEach {
            assertEquals(it, "正在派子代理…", toolPhrase(it))
        }
        listOf("wait_agent", "interrupt_agent", "list_agents").forEach {
            assertNotEquals(it, "正在派子代理…", toolPhrase(it))
            assertNotEquals(it, "正在调用工具…", toolPhrase(it))
        }
        assertEquals("正在发消息…", toolPhrase("send_message"))
        listOf("update_goal", "create_goal").forEach {
            assertEquals(it, "正在更新计划…", toolPhrase(it))
        }
        assertEquals("正在读取文件…", toolPhrase("read_image"))
    }

    @Test
    fun `未知工具不该吐出内部名字`() {
        // 用户原话：「而不是显示 bash、write 此类名称」。
        // 未知工具同理：它的名字是给程序看的，不是给人看的。
        val phrase = toolPhrase("mcp__github__create_pull_request")
        assertEquals("正在调用工具…", phrase)
        assertFalse(phrase.contains("mcp"))
        assertFalse(phrase.contains("github"))
    }

}

/**
 * R062：重复动作计数。
 *
 * 用户原话：模型一直连续运行 bash，流体云上一直显示 bash，「让人以为是卡住了」。
 */
class CapsuleRepeatCountTest {

    @Test
    fun `第一次出现计为 1`() {
        assertEquals(1, nextRepeat(previousPhrase = null, freshPhrase = "A", previousRepeat = 0))
    }

    @Test
    fun `连续同一个短语递增`() {
        assertEquals(2, nextRepeat("A", "A", 1))
        assertEquals(3, nextRepeat("A", "A", 2))
        assertEquals(13, nextRepeat("A", "A", 12))
    }

    @Test
    fun `换了动作就从 1 重新数`() {
        assertEquals(1, nextRepeat("A", "B", 9))
    }

    @Test
    fun `刚开始或刚换轮时 previous 为 null 也从 1 开始`() {
        assertEquals(1, nextRepeat(null, "A", 7))
    }

    @Test
    fun `计数顺序连着跑不会错位`() {
        var phrase: String? = null
        var repeat = 0
        val seen = mutableListOf<Int>()
        // A A A B B A
        listOf("A", "A", "A", "B", "B", "A").forEach { fresh ->
            repeat = nextRepeat(phrase, fresh, repeat)
            phrase = fresh
            seen.add(repeat)
        }
        assertEquals(listOf(1, 2, 3, 1, 2, 1), seen)
    }
}

/** R062：[actionLine] 的显示形态。 */
class CapsuleActionLineTest {

    @Test
    fun `第一次不显示乘号 因为×1 是噪音`() {
        val line = actionLine("bash", 1)
        assertEquals("正在运行命令…", line)
        assertFalse(line.contains("×"))
    }

    @Test
    fun `第二次起显示乘号与次数`() {
        assertEquals("正在运行命令…×2", actionLine("bash", 2))
        assertEquals("正在运行命令…×13", actionLine("bash", 13))
    }

    @Test
    fun `计数封顶在 99 不能无限涨`() {
        // 一行只有 12 字预算："▸ "(2) + 短语(7) + "×99"(3) = 12。
        // → ×100 是 4 位数，溢出后被系统切掉的恰好是这个功能唯一新增的信息。
        assertEquals("正在运行命令…×99", actionLine("bash", 100))
        assertEquals("正在运行命令…×99", actionLine("bash", 250))
        // 封顶后长度恒定，不会因次数变大而挤掉别的字。
        assertEquals(actionLine("bash", 100).length, actionLine("bash", 9_999).length)
    }

    @Test
    fun `没有工具时说正在思考`() {
        assertEquals(THINKING_TEXT, actionLine(null, 1))
        // 即使连续两次也不该说「正在思考 ×2」——那不是一个「动作」。
        assertEquals("正在思考…×2", actionLine(null, 2))
    }
}

/**
 * R062：`thinking` 是一个独立开关，不是 action 行的一个取值。
 *
 * 审查第一轮 P1 的教训：最初把「正在思考」写进 action，再用
 * `action != THINKING_TEXT` 把它抑制掉。可 `actionLine` 在连续次数 ≥ 2 时
 * 输出的是「正在思考…×2」，不再全等于 `THINKING_TEXT`，抑制**一次都没生效**。
 * 现在两者结构上互斥：action 只装真实动作，thinking 只在两行全空时兜底。
 */
class CapsuleThinkingSuppressTest {

    @Test
    fun `有汇报时 不给思考行留位置 汇报要吃满两行`() {
        val title = capsuleTitle(
            report = "一个非常长的汇报内容占满两行",
            action = null,
            fallback = "会话名",
            thinking = true,
        )
        assertFalse(title.contains(THINKING_TEXT))
        assertFalse(title.contains("\n"))
        assertEquals("一个非常长的汇报内容占满两行", title)
    }

    @Test
    fun `既没汇报也没动作时 正在思考才出现`() {
        val title = capsuleTitle(
            report = null,
            action = null,
            fallback = "会话名",
            thinking = true,
        )
        assertEquals("▸ 正在思考…", title)
    }

    @Test
    fun `不在跑的时候 不该说正在思考`() {
        val title = capsuleTitle(
            report = null,
            action = null,
            fallback = "会话名",
            thinking = false,
        )
        assertEquals("会话名", title)
    }

    @Test
    fun `有真实动作时 动作行照常占第二行`() {
        val title = capsuleTitle(
            report = "汇报",
            action = "正在运行命令…×3",
            thinking = false,
        )
        val lines = title.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("汇报"))
        assertTrue(lines[1].startsWith("▸ 正在运行命令"))
    }

    @Test
    fun `思考行与动作行不会同时出现`() {
        val withAction = capsuleTitle(report = null, action = "正在读取文件…", thinking = true)
        assertFalse(withAction.contains(THINKING_TEXT))
    }
}

/**
 * R062：动作行的跨帧状态转移。
 *
 * 这组用例是专门为一个存活的变异体补的（M10：把 `collect` 里的
 * `capsuleLastPhrase = null; capsuleRepeat = 0` 删掉，89 项全绿）。
 * 根因是那两句埋在回调里，单测够不到。
 *
 * 第一轮审查 P2-1 又改了身份口径：原来靠「短语相同」判断连续，
 * 但调用点在 `distinctUntilChanged()` 之后，流式期间正文每变一帧就记一次，
 * 同一次工具调用被数成 ×37。现在用 `actionKey`（attemptId）当身份。
 */
class CapsuleAdvanceActionTest {

    private fun adv(
        m: ActionMemory,
        wasRunning: Boolean = true,
        nowRunning: Boolean = true,
        actionKey: String?,
        phrase: String = "正在运行命令…",
    ): ActionMemory = advanceAction(
        memory = m,
        wasRunning = wasRunning,
        nowRunning = nowRunning,
        actionKey = actionKey,
        phrase = phrase,
    )

    @Test
    fun `同一个动作在不同 step 里连着来 次数递增`() {
        var m = ActionMemory()
        m = adv(m, actionKey = "a1")
        assertEquals(1, m.repeat)
        m = adv(m, actionKey = "a2")
        assertEquals(2, m.repeat)
        m = adv(m, actionKey = "a3")
        assertEquals(3, m.repeat)
    }

    @Test
    fun `同一次工具调用在流式吐字时不能重复计数`() {
        // 这是审查 P2-1 的核心：同一 attemptId 下正文每变一帧都会调一次，
        // 计数若跟着涨，用户看到的就是「正在运行命令…×37」。
        var m = ActionMemory()
        repeat(20) { m = adv(m, actionKey = "same-attempt") }
        assertEquals(1, m.repeat)
    }

    @Test
    fun `换了工具就从 1 重新数`() {
        var m = ActionMemory()
        repeat(5) { i -> m = adv(m, actionKey = "step-$i") }
        assertEquals(5, m.repeat)
        m = adv(m, actionKey = "step-read", phrase = "正在读取文件…")
        assertEquals(1, m.repeat)
        assertEquals("正在读取文件…", m.phrase)
    }

    @Test
    fun `换轮必须归零 否则上一轮的次数会带进新一轮`() {
        var m = ActionMemory()
        repeat(7) { i -> m = adv(m, actionKey = "step-$i") }
        assertEquals(7, m.repeat)
        // 一轮结束：原样返回，不改写成「正在思考」
        m = adv(m, wasRunning = true, nowRunning = false, actionKey = null, phrase = "正在思考…")
        assertEquals(7, m.repeat)
        assertEquals("正在运行命令…", m.phrase)
        // 下一轮开始，第一个动作又是 bash
        m = adv(m, wasRunning = false, nowRunning = true, actionKey = "fresh")
        assertEquals(1, m.repeat)
    }

    @Test
    fun `收尾时保留末态 不能把动作行改写成正在思考`() {
        // 审查 P2-2：旧写法在 nowRunning == false 时把动作行换成 toolPhrase(null)，
        // 于是「任务已完成」的卡片上写着「▸ 正在思考…」，与设计直接矛盾。
        var m = adv(ActionMemory(), actionKey = "a1")
        val ended = adv(m, wasRunning = true, nowRunning = false, actionKey = null, phrase = "正在思考…")
        assertEquals(m, ended)
    }

    @Test
    fun `轮内的工具间隔不会把计数归零 否则「一直显示 bash」又回来了`() {
        // 这条用例的前提被第一版写错了（断言 `expected:1 but was:4`）。
        //
        // 没有工具的那一帧，`watchCapsule` 里 `action` 直接为 null，
        // **根本不会调 `advanceAction`**，所以计数原封不动。
        // 下一帧 bash 带新 attemptId 继续 +1——这正是用户要的
        // 「一直连续运行 bash 就显示 ×N」。
        var m = ActionMemory()
        repeat(3) { i -> m = adv(m, actionKey = "step-$i") }
        assertEquals(3, m.repeat)
        // 空档帧不调 advanceAction：状态原样传下去。
        val afterGap = m
        assertEquals(3, afterGap.repeat)
        // 下一次 bash（新 attemptId、同一句短语）应该继续数到 4。
        m = adv(m, actionKey = "after-gap")
        assertEquals(4, m.repeat)
    }

    @Test
    fun `新轮里继续同一个工具 重新从 1 数到 2`() {
        var m = adv(ActionMemory(), actionKey = "x1")
        assertEquals(1, m.repeat)
        m = adv(m, actionKey = "x2")
        assertEquals(2, m.repeat)
        // 换轮
        m = adv(m, wasRunning = true, nowRunning = false, actionKey = null)
        m = adv(m, wasRunning = false, nowRunning = true, actionKey = "y1")
        assertEquals(1, m.repeat)
        m = adv(m, actionKey = "y2")
        assertEquals(2, m.repeat)
    }
}

/**
 * R062 第二轮的抽取：`advanceLive` 把「每一帧该显示什么」收敛成一个纯函数。
 *
 * 第一版这些判定全埋在 `ChatViewModel.watchCapsule` 的 `collect { }` 里，
 * 12 个变异体只杀掉 7 个——`没有工具也生成动作行`、`thinking 不看 tool`、
 * `换轮不清动作记忆` 三个改坏了测试一条都不红。抽出来才测得到。
 */
class CapsuleAdvanceLiveTest {

    private fun frame(
        running: Boolean = true,
        report: String? = null,
        tool: String? = null,
        attemptKey: String? = null,
        // 默认与 running 一致：对绝大多数用例来说「有 live 预览」就是「在生成」。
        // 真正区分二者的场景见下面「上一轮结束」那几条。
        generating: Boolean = running,
    ) = CapsuleLiveFrame(
        generating = generating,
        running = running,
        report = report,
        tool = tool,
        attemptKey = attemptKey,
    )

    @Test
    fun `有工具时动作行是它翻译过来的短语`() {
        val st = advanceLive(CapsuleLiveState(), frame(tool = "bash", attemptKey = "a1"))
        assertEquals("正在运行命令…", st.action)
        assertFalse(st.thinking)
    }

    @Test
    fun `没有工具时不生成动作行 把整行让给汇报`() {
        val st = advanceLive(CapsuleLiveState(), frame(report = "正在梳理数据链路"))
        assertNull(st.action)
        assertEquals("正在梳理数据链路", st.report)
        // 有汇报就不该再冒一句「正在思考」把汇报挤回一行。
        assertFalse(st.thinking)
    }

    @Test
    fun `没有工具也没有汇报时才说正在思考`() {
        val st = advanceLive(CapsuleLiveState(), frame())
        assertNull(st.action)
        assertTrue(st.thinking)
    }

    @Test
    fun `有工具时绝不会说正在思考`() {
        val st = advanceLive(CapsuleLiveState(), frame(tool = "bash", attemptKey = "a1"))
        assertFalse(st.thinking)
    }

    @Test
    fun `换轮要清掉汇报与计数`() {
        // 第一轮跑到连做 3 次 bash、有汇报
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(report = "第一轮的汇报", tool = "bash", attemptKey = "a1"))
        st = advanceLive(st, frame(report = "第一轮的汇报", tool = "bash", attemptKey = "a2"))
        st = advanceLive(st, frame(report = "第一轮的汇报", tool = "bash", attemptKey = "a3"))
        assertEquals(3, st.memory.repeat)
        // 一轮结束
        st = advanceLive(st, frame(running = false))
        // 新一轮开始，模型还没吐字
        st = advanceLive(st, frame(running = true))
        assertNull(st.report)
        assertEquals(0, st.memory.repeat)
        assertNull(st.memory.phrase)
        assertNull(st.lastAction)
    }

    @Test
    fun `一轮结束时保留最后一个真实动作 不能改写成正在思考`() {
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a1"))
        assertEquals("正在运行命令…", st.action)
        st = advanceLive(st, frame(running = false))
        assertEquals("正在运行命令…", st.action)
        assertFalse(st.thinking)
    }

    @Test
    fun `同一 attemptId 的流式帧不会把计数推上去`() {
        var st = CapsuleLiveState()
        repeat(15) {
            st = advanceLive(
                st,
                frame(report = "逐字增长的汇报 $it", tool = "bash", attemptKey = "same"),
            )
        }
        assertEquals(1, st.memory.repeat)
        assertEquals("正在运行命令…", st.action)
    }

    @Test
    fun `连续三次不同的 bash 调用要显示乘号`() {
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a1"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a2"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a3"))
        assertEquals("正在运行命令…×3", st.action)
    }

    @Test
    fun `汇报为空时不要用空串把上一句顶掉`() {
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(report = "第一句汇报", tool = "bash", attemptKey = "a1"))
        assertEquals("第一句汇报", st.report)
        st = advanceLive(st, frame(report = "", tool = "bash", attemptKey = "a2"))
        assertEquals("第一句汇报", st.report)
        st = advanceLive(st, frame(report = "   ", tool = "bash", attemptKey = "a3"))
        assertEquals("第一句汇报", st.report)
    }

    @Test
    fun `同一次工具调用推多帧不能重复计数`() {
        // 流式期间同一次调用会推很多帧（参数逐渐完整）。
        // 计数身份必须是调用 id，同 id 只能算一次。
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "call-1"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "call-1"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "call-1"))
        assertEquals(1, st.memory.repeat)
        assertEquals("正在运行命令…", st.action)
    }

    @Test
    fun `同一 step 内连跑同名工具要能数到第三次`() {
        // attemptId 是 step 级的，一个 step 里跑 3 次 bash 它不变。
        // 拿它当计数身份就只会算一次——用户要的 ×3 出不来。
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a-call-1"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a-call-2"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a-call-3"))
        assertEquals(3, st.memory.repeat)
        assertEquals("正在运行命令…×3", st.action)
    }

    @Test
    fun `step 之间 live 被清不算换轮 否则连续 bash 永远计不到 2`() {
        // 真实帧序：ChatReducer 在每个 step 的 assistant/message 落盘时都 `dropLive`，
        // 但 `generating` 仍为 true。旧写法拿 `live != null` 当轮次边界，
        // 于是每一步都被当成新一轮，计数每格清零——用户要的 ×N 永远出不来。
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "s1-bash"))
        st = advanceLive(st, frame(generating = true, running = false, report = "第一步说完了"))
        st = advanceLive(st, frame(tool = "bash", attemptKey = "s2-bash"))
        assertEquals(2, st.memory.repeat)
        assertEquals("正在运行命令…×2", st.action)
    }

    @Test
    fun `轮内没工具时不能挂上上一个动作行`() {
        // 工具已经跑完、模型正在写文字：卡片上还写着「正在运行命令」是假话。
        var st = CapsuleLiveState()
        st = advanceLive(st, frame(tool = "bash", attemptKey = "a1"))
        assertEquals("正在运行命令…", st.action)
        st = advanceLive(st, frame(generating = true, running = false, report = "正在整理结果"))
        assertNull(st.action)
        assertEquals("正在整理结果", st.report)
    }

    @Test
    fun `一轮结束后 thinking 必须是假 否则已完成卡片会说正在思考`() {
        var st = advanceLive(CapsuleLiveState(), frame())
        assertTrue(st.thinking)
        st = advanceLive(st, frame(running = false))
        assertFalse(st.thinking)
    }
}

/**
 * R062：源码契约断言。
 *
 * 这几条测不了行为（逻辑在私有 `@RequiresApi` 方法或另一个包的回调里），
 * 只能钉住「调用点有没有把该传的东西传下去」。写得窄但比没有强：
 * 它们在第二轮变异验证里各自对应一个变异体。
 */
class CapsuleThinkingWiringTest {

    private fun source(rel: String): String = java.io.File(SRC_ROOT, rel).readText()

    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `CapsuleCenter 必须把 state 的 thinking 传进 capsuleTitle`() {
        val body = codeOnly(source("core/capsule/CapsuleCenter.kt"))
        assertTrue(
            "build() 里要把 thinking 传下去，否则「��在思考」永远不显示",
            body.contains("thinking = state.thinking"),
        )
    }

    @Test
    fun `ViewModel 的 thinking 必须三个条件都看`() {
        // 它现在住在 advanceLive 里，不在 VM 里了；这条盯的是纯函数实现。
        val body = codeOnly(source("core/capsule/CapsuleModel.kt"))
        assertTrue(
            "thinking 必须同时要求 generating、没有工具、没有汇报",
            body.contains("val thinking = frame.generating && frame.tool == null && report == null"),
        )
    }

    @Test
    fun `ViewModel 必须整块用 advanceLive 的返回值`() {
        val body = codeOnly(source("ui/chat/ChatViewModel.kt"))
        assertTrue(body.contains("advanceLive("))
        assertTrue(
            "动作行必须来自 next.action，不能自己另拼一份",
            body.contains("action = next.action"),
        )
        assertTrue(
            "thinking 必须来自 next.thinking",
            body.contains("thinking = next.thinking"),
        )
    }

    @Test
    fun `没有工具时不能生成动作行`() {
        val body = codeOnly(source("core/capsule/CapsuleModel.kt"))
        assertTrue(
            "advanceLive 里只有 tool != null 才产出 action",
            body.contains("frame.tool != null && frame.running"),
        )
    }

    companion object {
        private val SRC_ROOT = java.io.File(
            "src/main/java/com/revxzxcx/remotewebapp",
        )
    }
}
