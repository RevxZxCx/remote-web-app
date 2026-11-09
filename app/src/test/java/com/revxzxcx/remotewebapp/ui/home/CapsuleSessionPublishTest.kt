package com.revxzxcx.remotewebapp.ui.home

import com.revxzxcx.remotewebapp.core.capsule.CapsuleBridge
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 流体云必须收到**新鲜**的会话列表（R059-B017）。
 *
 * ## 这个 bug 长什么样
 * 用户 m04765 第 7 条：「当任务已经完成后，流体云中的计数器还在运行」。
 * 表面看是计时器的问题，实际是**发布链断了**：
 *
 * `CapsuleCoordinator.phaseOf(summary)` 用会话摘要里的 `running` 算阶段，
 * 而 `setUsesChronometer` 只在 `phase == Running` 时为真。只要胶囊层手里的
 * `sessions` 停在「上一次全量刷新」的快照上，`running` 就永远是 true，
 * 计时器就一直挂着——而 App 里那张列表明明已经灭了绿点。
 *
 * 修复要同时满足两条，缺一条就复发：
 * 1. **权威全量路径**（`loadSessions`）要发布——它直接写 `_state`，不走 `applySessions`；
 * 2. **增量路径**（`applySessions`）也要发布，且发布必须发生在
 *    `if (result == current.sessions) return` 这个相等短路**之前**。
 *
 * 第 2 条的第一版修复恰好踩了坑：把全量那处删掉、只留 `applySessions`，
 * 结果两条路一起失效（冷启动后胶囊拿到空列表）。所以这里两条都钉。
 */
class CapsuleSessionPublishTest {

    private fun homeSource(): String {
        val candidates = listOf(
            File("src/main/java/com/revxzxcx/remotewebapp/ui/home/HomeViewModel.kt"),
            File("app/src/main/java/com/revxzxcx/remotewebapp/ui/home/HomeViewModel.kt"),
            File("../app/src/main/java/com/revxzxcx/remotewebapp/ui/home/HomeViewModel.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 HomeViewModel.kt，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

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
    fun `权威全量路径自己就要发布 不能指望 applySessions`() {
        // `loadSessions` 直接写 `_state.value = ...`，根本不经过 `applySessions`。
        // 早先把这处删掉、只留增量那处，冷启动后胶囊层拿到的是 emptyList()，
        // 要等第一条真正改变列表的增量帧才会出现（审查 P1）。
        val body = bodyOf(homeSource(), "private fun loadSessions()")
        assertTrue(
            "loadSessions 里必须有 CapsuleBridge.publishSessions(result.items)——" +
                "它是唯一写入权威全量的路径，不走 applySessions",
            body.contains("CapsuleBridge.publishSessions(result.items)"),
        )
    }

    @Test
    fun `增量路径的发布必须在相等短路之前`() {
        // 相等意味着 `_state` 不用改，但胶囊层的快照未必最新：进程内可能刚经历过
        // 一次 `CapsuleBridge.reset()`（断连），或者协调器刚被重建。
        // 「内容没变」不等于「对面已经知道了」。
        val body = bodyOf(homeSource(), "private fun applySessions(items: List<SessionSummary>)")
        val publish = body.indexOf("CapsuleBridge.publishSessions(items)")
        val shortCircuit = body.indexOf("if (result == current.sessions) return")
        assertTrue("applySessions 里要发布", publish >= 0)
        assertTrue("applySessions 里应有相等短路", shortCircuit >= 0)
        assertTrue(
            "发布必须在 `if (result == current.sessions) return` 之前——" +
                "放在后面时，内容相同的增量帧会被短路掉，胶囊层永远追不上" +
                "（B017 正是这个形态：state 里灭了绿点，胶囊还挂着计时器）",
            publish < shortCircuit,
        )
    }

    @Test
    fun `四条增量入口都汇流到 applySessions 所以就一处发布`() {
        // 这条是防「多点发布」的：将来加第五个入口时，只要它也走 applySessions
        // 就自动被覆盖；若有人在某个 applyXxx 里单独再发一次，重复发布不会出错，
        // 但会掩盖「某个入口没走 applySessions」这个真正的隐患。
        val text = homeSource()
        for (entry in listOf("applyAddedSummary", "applyRunning", "applyError", "applyRemoved")) {
            // 只留函数体内那半个判断。早先还挂了个 `|| text.contains(...)` 的兜底，
            // 而 `text.contains("$entry(")`（函数自己的定义）与 `text.contains("applySessions(")`
            // 在整个文件里恒为真——于是这个断言恒真，什么都抓不到（审查 P3）。
            assertTrue(
                "$entry 里应调用 applySessions（本类是单一发布点）",
                bodyOf(text, "private fun $entry(").contains("applySessions("),
            )
        }
    }

    @Test
    fun `bridge 收到什么就存什么 不做二次过滤`() {
        // 过滤（子代理）在 `CapsuleCoordinator.setSessions` 里做，Bridge 只负责搬运。
        // 若 Bridge 也滤一道，两处规则会漂移；而且 Bridge 是 object，测试之间会串状态。
        val a = summary("s-a", running = true)
        val b = summary("s-b", running = false)
        val before = CapsuleBridge.sessions.value
        try {
            CapsuleBridge.publishSessions(listOf(a, b))
            assertEquals(listOf("s-a", "s-b"), CapsuleBridge.sessions.value.map { it.sessionId })
            assertEquals("running 字段要原样带过去，胶囊靠它算阶段", true, CapsuleBridge.sessions.value[0].running)
        } finally {
            CapsuleBridge.publishSessions(before)
        }
    }

    @Test
    fun `reset 之后是空列表 因为旧主机的会话不属于新连接`() {
        val before = CapsuleBridge.sessions.value
        try {
            CapsuleBridge.publishSessions(listOf(summary("s-x", running = true)))
            assertTrue(CapsuleBridge.sessions.value.isNotEmpty())

            CapsuleBridge.reset()

            assertTrue(
                "换连接后必须是空列表：StateFlow 会把当前值推给新订阅者，" +
                    "留着旧会话就会选出一个属于上一台机器的焦点",
                CapsuleBridge.sessions.value.isEmpty(),
            )
        } finally {
            CapsuleBridge.publishSessions(before)
        }
    }

    private fun summary(id: String, running: Boolean) = SessionSummary(
        sessionId = id,
        agentAvailable = true,
        running = running,
        blank = false,
        parentSessionId = null,
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

    @After
    fun tearDown() {
        // 进程级 object：不清理的话会影响同 JVM 里别的用例。
        CapsuleBridge.reset()
    }
}
