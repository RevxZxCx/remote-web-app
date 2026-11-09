package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.capsule.CapsuleCoordinator
import com.revxzxcx.remotewebapp.core.capsule.CapsulePhase
import com.revxzxcx.remotewebapp.core.capsule.CapsuleState
import com.revxzxcx.remotewebapp.core.capsule.CapsuleCenter
import com.revxzxcx.remotewebapp.core.capsule.PendingKind
import com.revxzxcx.remotewebapp.core.capsule.iconRes
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * 子代理不该被当成独立会话（R059-B011 / B012）。
 *
 * ## 为什么这两个 bug 值得一组专门的用例
 * 项目里同一个错误已经复发**三次**：
 * 1. 会话列表把子代理当会话列出来（`WorkspaceSessions.items` 的过滤）；
 * 2. 统计口径算多了（`ContextTotals`）；
 * 3. 本轮：完成通知与流体云又各犯一次。
 *
 * 三次的形态一模一样——**判定依据 `origin == "subagent"` 只在摘要里，而消费点读的
 * 是只带 id 的 `status` / `error` 帧**。所以这里不只钉逻辑，还钉「消费点确实调了
 * 那个单一事实源」，因为漏掉第四处的代价是又一个静默缺陷。
 */
class SubagentRegistryTest {

    private fun summary(
        id: String,
        origin: String? = null,
        running: Boolean = false,
        parentSessionId: String? = null,
    ) = SessionSummary(
        sessionId = id,
        agentAvailable = true,
        running = running,
        blank = false,
        parentSessionId = parentSessionId,
        origin = origin,
        cwd = "/w",
        title = id,
        lastPromptAt = 0L,
        lastModel = null,
        updatedAt = 0L,
        awaitingAttention = false,
        completionUnread = false,
        failureUnread = false,
        failureReason = "",
    )

    @Test
    fun `登记过 origin 是 subagent 的会话之后 查得出来`() {
        val registry = SubagentRegistry()
        registry.remember(listOf(summary("s-sub", origin = "subagent")))

        assertTrue(registry.isSubagent("s-sub"))
    }

    @Test
    fun `没登记过的会话一律当作主会话 宁可多发一条通知也不要静默`() {
        // 这条是整个设计的地基：三态里「不知道」偏向「发通知」。
        // 反过来的话，任何一次漏记都会让主会话的完成通知静默消失。
        val registry = SubagentRegistry()
        assertFalse(registry.isSubagent("s-never-seen"))
        assertFalse(registry.isSubagent(null))
        assertFalse(registry.isSubagent(""))
    }

    @Test
    fun `主会话被显式登记后 也不会被子代理表命中`() {
        val registry = SubagentRegistry()
        registry.remember(listOf(summary("s-main", origin = null)))

        assertFalse(registry.isSubagent("s-main"))
    }

    @Test
    fun `批量校准只做补记 不会把别处记下的结论抹掉`() {
        // 真实调用序列：`added` 帧先记下 s1 是子代理，随后一次局部刷新只带了 s2。
        // 若"整体覆盖"，s1 的登记就没了，下一秒它的 status 帧就会发一条完成通知。
        val registry = SubagentRegistry()
        registry.remember(summary("s1", origin = "subagent"))
        registry.remember(listOf(summary("s2", origin = null)))

        assertTrue("s1 的结论不能被 s2 那批抹掉", registry.isSubagent("s1"))
        assertFalse(registry.isSubagent("s2"))
    }

    @Test
    fun `同一会话先后判成两种结论时 以最新一次为准`() {
        // 服务端确实会改这个字段：摘要初次到达时 `origin` 可能还没填，
        // 后一次刷新才带上 `subagent`。互斥保证不会两个集合里都有。
        val registry = SubagentRegistry()
        registry.remember(summary("s1", origin = null))
        assertFalse(registry.isSubagent("s1"))

        registry.remember(summary("s1", origin = "subagent"))
        assertTrue("后到的结论覆盖先到的", registry.isSubagent("s1"))
    }

    @Test
    fun `子代理被提升成普通会话时 结论必须翻过来`() {
        // 审查 P2：早先的实现里 `overlap` 只从 primaries 减，于是「子 → 主」这个方向
        // 永远翻不过来。一旦某 id 被记成子代理，服务端后来把它改成普通会话，
        // 它也会一直留在 subagents 里——之后这条真实主会话的完成与失败通知会被
        // **静默吞掉**，用户根本不知道任务结束了。这正是本类 KDoc 要避免的失效模式。
        val registry = SubagentRegistry()
        registry.remember(summary("s1", origin = "subagent"))
        assertTrue("前提：先记成子代理", registry.isSubagent("s1"))

        registry.remember(summary("s1", origin = null))

        assertFalse("服务端改回普通会话后必须翻过来，否则它的通知会被吞掉", registry.isSubagent("s1"))
    }

    @Test
    fun `同一批里同一个 id 出现两种 origin 时 后出现的说了算`() {
        // 审查 P2：早先是边遍历边往两个集合里写。同一批里若 s1 先以 subagent、
        // 后以非子代理出现，它会被同时写进两边，紧接着两次减法各摘一次——
        // 最终**两个集合里都没有它**，等价于「不知道」，整个登记表对这条会话失效。
        //
        // 现在先按 id 归并出本批最终结论再动全局，规则与单条序列一致：后者覆盖前者。
        val registry = SubagentRegistry()
        registry.remember(
            listOf(
                summary("s1", origin = "subagent"),
                summary("s1", origin = null),
            ),
        )

        assertFalse("后出现的（普通会话）必须赢，且不能两边都不留", registry.isSubagent("s1"))
    }

    @Test
    fun `同一批里同 id 反向出现时 子代理结论同样要能生效`() {
        // 上一条的镜像：先普通、后子代理。两边都要能赢，才算「以最新一次为准」。
        val registry = SubagentRegistry()
        registry.remember(
            listOf(
                summary("s1", origin = null),
                summary("s1", origin = "subagent"),
            ),
        )

        assertTrue("后出现的（子代理）必须赢", registry.isSubagent("s1"))
    }

    @Test
    fun `forget 之后回到不知道 也就是当作主会话`() {
        val registry = SubagentRegistry()
        registry.remember(summary("s1", origin = "subagent"))
        registry.forget("s1")

        assertFalse(registry.isSubagent("s1"))
    }

    @Test
    fun `clear 用于换主机 因为不同机器上同一个 id 是另一条会话`() {
        val registry = SubagentRegistry()
        registry.remember(listOf(summary("s1", origin = "subagent")))
        registry.clear()

        assertFalse(registry.isSubagent("s1"))
    }

    @Test
    fun `空白 id 不登记 否则集合里会多一个永远命不中的空串`() {
        val registry = SubagentRegistry()
        registry.remember(summary("", origin = "subagent"))

        assertFalse(registry.isSubagent(""))
    }
}

/**
 * B011 的接线：通知层必须真的调登记表。
 *
 * `NotifyCoordinator` 要活的 `RemoteEventsClient`（内含 WebSocket），单测进不去，
 * 所以这里用源码断言钉「调用发生了、且发生在该发生的位置」。R056 六轮审查的教训：
 * 每一块零件都对、只有接线错，纯逻辑单测全绿也发现不了。
 */
class SubagentNotifyWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun coordinatorSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/notify/NotifyCoordinator.kt",
    )

    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) error("源码里找不到：$signature")
        val open = text.indexOf('{', start)
        if (open < 0) error("$signature 后面没有函数体")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的大括号没有配对")
    }

    @Test
    fun `added 帧要把摘要记进子代理表 否则后面的 status 帧永远判不出来`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle(notification: SessionNotification)"))
        val addedAt = body.indexOf("is SessionNotification.Added ->")
        assertTrue("handle 里应有 Added 分支", addedAt >= 0)
        val statusAt = body.indexOf("is SessionNotification.Status ->")
        assertTrue("handle 里应有 Status 分支", statusAt > addedAt)
        val addedBlock = body.substring(addedAt, statusAt)

        assertTrue(
            "Added 分支必须 subagents.remember(...)——`origin` 只在这条帧里出现，" +
                "不记下来的话 status 帧拿到的是一个光秃秃的 id，无从分辨",
            addedBlock.contains("subagents.remember("),
        )
    }

    @Test
    fun `status 帧必须在发通知之前拦掉子代理`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle(notification: SessionNotification)"))
        val statusAt = body.indexOf("is SessionNotification.Status ->")
        val errorAt = body.indexOf("is SessionNotification.Error ->")
        assertTrue(statusAt >= 0 && errorAt > statusAt)
        val block = body.substring(statusAt, errorAt)

        val guard = block.indexOf("subagents.isSubagent(")
        assertTrue(
            "Status 分支必须查 subagents.isSubagent 并提前 return——" +
                "子代理跑完发一条「任务结束」，用户会以为主任务已经做完",
            guard >= 0,
        )
        val outcome = block.indexOf("launchOutcome(")
        assertTrue("Status 分支里应有 launchOutcome", outcome >= 0)
        assertTrue(
            "拦截必须发生在 launchOutcome **之前**，否则通知已经发出去了",
            guard < outcome,
        )
        // 但边沿账仍要喂：不喂的话账里留一个永不过期的 true，
        // 下一次真正的主会话轮次会被判成「本来就在跑」而永远不发完成通知。
        val observe = block.indexOf("edge.observe(")
        assertTrue("Status 分支里应有 edge.observe", observe >= 0)
        assertTrue("edge 仍要在拦截之前喂，否则账会卡住", observe < guard)
    }

    @Test
    fun `error 帧也要拦掉子代理 否则一次失败响两声`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle(notification: SessionNotification)"))
        val errorAt = body.indexOf("is SessionNotification.Error ->")
        assertTrue(errorAt >= 0)
        val tail = body.substring(errorAt, minOf(body.length, errorAt + 1600))

        assertTrue(
            "Error 分支必须查 subagents.isSubagent——主会话会把子代理的失败一并报出来",
            tail.contains("subagents.isSubagent("),
        )
        // 只断言「出现过 isSubagent」是不够的：把拦截挪到 launchOutcome 之后，
        // 通知已经发出去了，而这种写法照样能通过（一审 E1）。
        val guard = tail.indexOf("subagents.isSubagent(")
        val outcome = tail.indexOf("launchOutcome(")
        assertTrue("Error 分支里应有 launchOutcome", outcome >= 0)
        assertTrue(
            "拦截必须发生在 launchOutcome **之前**，否则失败通知已经发出去了",
            guard < outcome,
        )
    }

    @Test
    fun `换连接要清掉子代理表 因为不同机器上同一个 id 是另一条会话`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?)"))
        val earlyReturn = body.indexOf("if (client == null) return")
        val clear = body.indexOf("subagents.clear()")
        assertTrue("attach 里要调 subagents.clear()", clear >= 0)
        assertTrue("清表必须在 `if (client == null) return` 之前", clear in 0 until earlyReturn)
    }

    @Test
    fun `会话移除时要 forget 否则表只增不减`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle(notification: SessionNotification)"))
        // 必须限定在 Removed 分支里。一审 E1 指出：早先这条是在整个 handle 体里找
        // `subagents.forget(`，把它挪到 Status 分支照样通过——而挪到 Status 分支
        // 意味着「子代理一跑完就丢掉登记」，下一次它的 status 帧就判不出来了。
        val removedAt = body.indexOf("is SessionNotification.Removed ->")
        val statusAt = body.indexOf("is SessionNotification.Status ->")
        assertTrue("handle 里应有 Removed 分支", removedAt >= 0)
        assertTrue("handle 里应有 Status 分支", statusAt > removedAt)
        val removedBlock = body.substring(removedAt, statusAt)
        assertTrue(
            "Removed 分支要 subagents.forget(...)——否则被销毁的会话 id 一直占着表",
            removedBlock.contains("subagents.forget("),
        )
    }
}

/**
 * B012 的接线：胶囊层必须在**入口**就把子代理滤掉。
 *
 * 只钉「有过滤」不够——`sessions` 同时被焦点选举和 `countOthersRunning` 用，
 * 若把过滤写进选举里，计数那一路就会把子代理数进去，用户仍然看到「执行中 2 个」。
 */
class CapsuleSubagentFilterTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) error("源码里找不到：$signature")
        val open = text.indexOf('{', start)
        if (open < 0) error("$signature 后面没有函数体")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的大括号没有配对")
    }

    private fun coordinatorSource() = codeOnly(
        source("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCoordinator.kt"),
    )

    @Test
    fun `setSessions 在赋值时就滤掉子代理 而不是等选举时再滤`() {
        val body = bodyOf(coordinatorSource(), "fun setSessions(items: List<SessionSummary>)")
        assertTrue(
            "setSessions 里必须出现 isSubagent 过滤——`sessions` 同时喂焦点选举与「另外几个在跑」" +
                "的计数，只在选举里滤的话计数照样把子代理算进去",
            body.contains("isSubagent"),
        )
        assertTrue(
            "要按摘要自带的 `isSubagent` 过滤，这是权威字段",
            body.contains(".isSubagent"),
        )
        assertTrue(
            "还要兜一层旁路登记表：缓存与增量路径流出来的摘要可能丢了 origin",
            body.contains("subagents?.isSubagent("),
        )
    }

    @Test
    fun `等用户处理的那几个会话即使被判成子代理也要留下`() {
        // 第三轮审查 P3-2，但它其实是「该显示的不显示」，比噪音严重。
        //
        // 子代理会向用户发起审批与提问（实测里那三个选项的提问就是从子代理那条路上来的）。
        // 早先入口过滤一刀切，`CapsuleFocus.elect` 第一层 `byId[id]?.let { ... }`
        // 找不到人就直接落到下一层——卡片既不显示这条待处理，也不提示任何异常。
        // 用户被一条永远不会出现的确认卡住，任务就那么停着，谁都不知道为什么。
        val body = bodyOf(coordinatorSource(), "fun setSessions(items: List<SessionSummary>)")
        assertTrue(
            "过滤条件里必须排除「正在等用户处理」的会话：先算出 waiting 集合",
            body.contains("pendingSessionIds()") && body.contains("waiting"),
        )
        assertTrue(
            "子代理判定要 && 上「不在 waiting 里」，否则有待处理的子代理会被一起滤掉",
            body.contains("!in waiting"),
        )
    }

    @Test
    fun `setSessions 赋值时就用过滤后的结果 而不是原样存下`() {
        // 用户 m04765 第 2 条原话：「一个对话和他的子代理运行时，会显示两个会话在运行」。
        //
        // 一审 E1 指出早先这条只断言「setSessions 出现在 countOthersRunning 之前」，
        // 近乎恒真——那两个位置本来就固定。真正要钉的是**存进字段的就是过滤后的列表**：
        // 若写成 `sessions = items` 之后再在选举里过滤，`countOthersRunning` 照样
        // 把子代理数进去，「执行中 2 个」原样复现。
        val body = bodyOf(coordinatorSource(), "fun setSessions(items: List<SessionSummary>)")
        val at = body.indexOf("sessions = ")
        assertTrue("setSessions 里应有对 sessions 的赋值", at >= 0)
        // 只看这一行会漏掉链式闭包（`filterNot { summary -> ... }` 是跨行的），
        // 所以取赋值点到函数体末尾整段来判。
        val rhs = body.substring(at)
        assertTrue(
            "赋的值必须带过滤（isSubagent），不能是裸的 items——" +
                "裸存的话计数那一路会把子代理算进去。实际是：$rhs",
            rhs.contains("filterNot") && rhs.contains("isSubagent"),
        )
    }
}

/**
 * B016：胶囊图标必须按状态分开（R059-B016）。
 *
 * 之前所有状态共用 `ic_capsule`（三条横线）。用户的原话是「所有状态的图标都没有
 * 实际和原型契合，只是使用了重复的图标」——而图标位本身在向用户承诺
 * 「你能从图标看出状态」，看不出来就是每个状态都在骗人。
 */
class CapsuleIconTest {

    private fun state(
        pending: PendingKind = PendingKind.None,
        phase: CapsulePhase = CapsulePhase.Running,
    ) = CapsuleState(
        sessionId = "s1",
        title = "标题",
        report = "汇报",
        action = null,
        step = null,
        progress = null,
        startedAt = 0L,
        pending = pending,
        otherRunning = 0,
        phase = phase,
    )

    @Test
    fun `运行中的图标是运行图标`() {
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_running,
            state(phase = CapsulePhase.Running).iconRes(),
        )
    }

    @Test
    fun `完成显示对勾 与原型一致`() {
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_done,
            state(phase = CapsulePhase.Done).iconRes(),
        )
    }

    @Test
    fun `出错显示叹号`() {
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_failed,
            state(phase = CapsulePhase.Failed).iconRes(),
        )
    }

    @Test
    fun `待批准显示手 不是对勾也不是叹号`() {
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_wait,
            state(pending = PendingKind.Approval).iconRes(),
        )
    }

    @Test
    fun `待回答显示问号`() {
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_question,
            state(pending = PendingKind.Question).iconRes(),
        )
    }

    @Test
    fun `待批准优先于相态 与胶囊文案的顺序一致`() {
        // 胶囊条上图标与文字并排。若两处顺序不同，会出现「对勾 + 待批准」这种自相矛盾。
        // `capsuleText` 的 when 分支里 `pending == Approval` 排在最前，这里必须一样。
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_wait,
            state(pending = PendingKind.Approval, phase = CapsulePhase.Failed).iconRes(),
        )
        assertEquals(
            com.revxzxcx.remotewebapp.R.drawable.ic_capsule_question,
            state(pending = PendingKind.Question, phase = CapsulePhase.Done).iconRes(),
        )
    }

    @Test
    fun `五个状态的图标互不相同 否则「按状态分开」是假的`() {
        val icons = listOf(
            state(phase = CapsulePhase.Running).iconRes(),
            state(phase = CapsulePhase.Done).iconRes(),
            state(phase = CapsulePhase.Failed).iconRes(),
            state(pending = PendingKind.Approval).iconRes(),
            state(pending = PendingKind.Question).iconRes(),
        )
        assertEquals("五种状态必须给出五个不同的 drawable", 5, icons.toSet().size)
    }

    @Test
    fun `build 里用的是 state 的图标 而不是写死的常量`() {
        val candidates = listOf(
            File("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
            File("app/src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
            File("../app/src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 CapsuleCenter.kt")
        val text = file.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

        assertTrue(
            "setSmallIcon 必须按状态取：之前写死 ic_capsule，结果所有状态同一个图标",
            text.contains("setSmallIcon(state.iconRes())"),
        )
        assertFalse(
            "不能再写死 ic_capsule（那正是 B016 的形态）",
            text.contains("setSmallIcon(R.drawable.ic_capsule)"),
        )
    }
}

/**
 * B013：进度段必须显式配色。
 *
 * 不设 `setColor` 时颜色完全由系统主题决定——真机上已完成段与未完成段同为灰色，
 * 进度条看上去整条不动。用户报的「已完成的进度条不会变成绿色」就是这个。
 */
class CapsuleSegmentColorTest {

    private fun centerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
            File("app/src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
            File("../app/src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCenter.kt"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 CapsuleCenter.kt")
        return file.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /** 把连续空白压成一个空格：源码里的链式调用是跨行写的，贴字面量断言会误报。 */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `每一段都要 setColor 并且按是否已完成分成两色`() {
        val text = flat(centerSource())
        assertTrue(
            "进度段必须显式 setColor：不设的话颜色由系统主题定，" +
                "真机上已完成段与未完成段同为灰色",
            text.contains("Notification.ProgressStyle.Segment(1) .setColor("),
        )
        assertTrue(
            "已完成段用 SEGMENT_DONE_COLOR",
            text.contains("SEGMENT_DONE_COLOR"),
        )
        assertTrue(
            "未完成段用 SEGMENT_TODO_COLOR",
            text.contains("SEGMENT_TODO_COLOR"),
        )
        assertTrue(
            "判定条件是「这一段的序号小于已完成数」——用 index 而不是 done，否则整条同色",
            text.contains("if (index < done)"),
        )
    }

    @Test
    fun `段长仍然一律是 1 分母才与圆点一致`() {
        // R059 已确认的根因：`Segment(length)` 的参数是「这一段代表多少个进度单位」，
        // 圆点按 `done / Σlength` 算、绿条按「已完成段数 / 总段数」算。
        // 给 Segment(1) 两者分母相同；一旦有人按段宽去给 length，圆点立刻错位。
        val text = centerSource()
        assertTrue(
            "段长必须固定为 1",
            text.contains("Notification.ProgressStyle.Segment(1)"),
        )
        assertFalse(
            "不能出现 Segment(total) 或 Segment(progress.total)——那会让圆点与绿条分母不同",
            text.contains("Segment(progress.total)") || text.contains("Segment(total)"),
        )
    }
}
