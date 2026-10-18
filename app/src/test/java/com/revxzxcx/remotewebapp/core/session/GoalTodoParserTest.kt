package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标 / 任务的数据层（R031）。
 *
 * 三条契约，全部来自真实会话日志的实测形状：
 * 1. `goal.phase` 的四个取值（active / paused / blocked / complete）都能解析，
 *    **complete 不渲染横幅**（DSH 的 `GoalBar` 就是 `return null`）。
 * 2. **目标与任务完全独立**——这是本轮最重要的一条（用户 m45632 让我去查 DSH 源码确认）。
 *    `goals/pause` 只改持久状态、不中止当前轮、不冻结任务，所以 `Goal` 里**没有**
 *    任何指向 `TodoList` 的字段，反过来也一样。这个测试用「解析后各管各的」把它钉住。
 * 3. `todos[].status` 的三种取值与 DSH 的 `todo.status.*` 逐一对应；认不出的状态退化成
 *    `pending` 而不是丢弃（少一条任务比状态不准更糟）。
 */
class GoalTodoParserTest {

    private fun values(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    // ---------------------------------------------------------------- goal

    @Test
    fun `轮次用尽时 roundsExhausted 为真`() {
        // 用户 m04197 第 4 条：轮次到上限后 DSH 会把目标 block 成 `round-limit`，
        // 此时 `goals/resume` 直接抛 `GOAL_INVALID_TRANSITION`
        // （`goal "…" exhausted N goal rounds; increase maxGoalRounds before resuming`），
        // 所以界面必须提前知道「已经跑满」并置灰。判据是两个数的比较。
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"goal":{"id":"goal-1","revision":9,"objective":"跑满的目标",
                 "phase":"blocked","maxGoalRounds":50,"blockedReason":{"code":"round-limit"}},
                 "roundsStarted":50,"createdAt":1,"updatedAt":2}}
                """.trimIndent(),
            ),
        )
        assertEquals(50, goal?.roundsStarted)
        assertEquals(50, goal?.maxGoalRounds)
        assertTrue("轮次跑满要判为已用尽", goal?.roundsExhausted == true)
        // blocked 相位下 canPause 为假（按钮已经是「开始」），配合 roundsExhausted 置灰。
        assertFalse(goal?.canPause == true)
    }

    @Test
    fun `roundsStarted 在外层也要读到`() {
        // 线上 `roundsStarted` 属于**外层那条目标记录**（`{goal:{…}, roundsStarted:12}`），
        // 不在 goal 本体里。首版只读内层 → 恒为 0 → 永远判不出「已跑满」。
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"goal":{"id":"goal-1","revision":2,"objective":"x",
                 "phase":"active","maxGoalRounds":8},"roundsStarted":3}}
                """.trimIndent(),
            ),
        )
        assertEquals(3, goal?.roundsStarted)
        assertFalse("3 < 8 还没跑满", goal?.roundsExhausted == true)
    }

    @Test
    fun `没有 maxGoalRounds 时不算跑满`() {
        // 无上限的目标永远不该被判成用尽，否则「开始」会被误置灰。
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"goal":{"id":"goal-1","revision":1,"objective":"x",
                 "phase":"blocked"},"roundsStarted":999}}
                """.trimIndent(),
            ),
        )
        assertNull(goal?.maxGoalRounds)
        assertFalse(goal?.roundsExhausted == true)
    }

    @Test
    fun `解析进行中的目标`() {
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"id":"goal-1","revision":3,"objective":"完成所有开发",
                 "phase":"active","maxGoalRounds":256}}
                """.trimIndent(),
            ),
        )
        assertEquals("goal-1", goal?.id)
        assertEquals(3L, goal?.revision)
        assertEquals("完成所有开发", goal?.objective)
        assertEquals(GoalPhase.ACTIVE, goal?.phase)
        assertEquals(256, goal?.maxGoalRounds)
        assertTrue("进行中要渲染横幅", goal?.visible == true)
        assertTrue("进行中给的是「暂停」按钮", goal?.canPause == true)
    }

    /**
     * 线上真实形状是**双层**包裹（R031 设备验收抓到的缺陷）。
     *
     * `projections.values.goal` = `{ goal: { … }, roundsStarted, createdAt, updatedAt }`。
     * 首版把它当本体读 `id`，恒为 null → 横幅在真机上从没出现过，而上面那条扁平样本
     * 的单测一直绿。这条用**抄袭真实报文**的样本把它钉死。
     */
    @Test
    fun `解析线上双层包裹的目标记录`() {
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"goal":{"id":"goal-de9d2af5","revision":1,
                 "objective":"验收 R031 目标横幅","phase":"active","maxGoalRounds":256},
                 "roundsStarted":0,"createdAt":1792280349343,"updatedAt":1792280349343}}
                """.trimIndent(),
            ),
        )
        assertEquals("goal-de9d2af5", goal?.id)
        assertEquals("验收 R031 目标横幅", goal?.objective)
        assertEquals(GoalPhase.ACTIVE, goal?.phase)
        assertEquals(256, goal?.maxGoalRounds)
    }

    /** 外层有 `goal` 但子对象里没有 id（例如 `goal: null`）→ 没有目标，不能崩。 */
    @Test
    fun `双层包裹但内层为空时不渲染`() {
        assertNull(GoalTodoParser.parseGoal(values("""{"goal":{"goal":null,"roundsStarted":0}}""")))
        assertNull(GoalTodoParser.parseGoal(values("""{"goal":null}""")))
    }

    @Test
    fun `暂停的目标给「开始」按钮`() {
        val goal = GoalTodoParser.parseGoal(
            values("""{"goal":{"id":"g","revision":1,"objective":"x","phase":"paused"}}"""),
        )
        assertEquals(GoalPhase.PAUSED, goal?.phase)
        assertTrue("暂停仍要渲染（用户要能点继续）", goal?.visible == true)
        assertFalse("暂停时给「开始」", goal?.canPause == true)
    }

    @Test
    fun `已完成的目标不渲染横幅`() {
        val goal = GoalTodoParser.parseGoal(
            values("""{"goal":{"id":"g","revision":9,"objective":"x","phase":"complete"}}"""),
        )
        assertEquals(GoalPhase.COMPLETE, goal?.phase)
        assertFalse("complete 不渲染（与 DSH 的 GoalBar 一致）", goal?.visible == true)
    }

    @Test
    fun `受阻的目标带原因且不渲染暂停按钮`() {
        val goal = GoalTodoParser.parseGoal(
            values(
                """
                {"goal":{"id":"g","revision":2,"objective":"x","phase":"blocked",
                 "blockedReason":{"message":"等待授权"}}}
                """.trimIndent(),
            ),
        )
        assertEquals(GoalPhase.BLOCKED, goal?.phase)
        assertEquals("等待授权", goal?.blockedReason)
        assertFalse(goal?.canPause == true)
    }

    @Test
    fun `没有 goal 投影时返回 null`() {
        assertNull(GoalTodoParser.parseGoal(values("""{"title":"x"}""")))
        assertNull(GoalTodoParser.parseGoal(null))
    }

    @Test
    fun `未知相位保留原文而不是崩`() {
        val goal = GoalTodoParser.parseGoal(
            values("""{"goal":{"id":"g","revision":1,"objective":"x","phase":"frozen"}}"""),
        )
        assertEquals(GoalPhase.UNKNOWN, goal?.phase)
        assertEquals("frozen", goal?.rawPhase)
        // 未知相位仍然渲染（有目标这个事实不能丢），但不给暂停按钮。
        assertTrue(goal?.visible == true)
        assertFalse(goal?.canPause == true)
    }

    // ---------------------------------------------------------------- todos

    @Test
    fun `解析任务列表并统计`() {
        val todos = GoalTodoParser.parseTodos(
            values(
                """
                {"todos":[
                  {"content":"第一步","status":"completed"},
                  {"content":"第二步","status":"completed"},
                  {"content":"第三步","status":"in_progress"},
                  {"content":"第四步","status":"pending"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(4, todos.total)
        assertEquals(2, todos.done)
        assertEquals(1, todos.active)
        assertEquals(1, todos.pending)
        assertEquals("2/4", todos.counter)
        assertTrue(todos.visible)
        assertTrue("有进行中就要转圈", todos.running)
        assertFalse(todos.allDone)
    }

    @Test
    fun `全部完成时 allDone 为真且不再 running`() {
        val todos = GoalTodoParser.parseTodos(
            values(
                """{"todos":[{"content":"a","status":"completed"},
                 {"content":"b","status":"completed"}]}""",
            ),
        )
        assertTrue(todos.allDone)
        assertFalse("全部完成时不能还在转", todos.running)
        assertEquals("2/2", todos.counter)
    }

    @Test
    fun `空任务列表不显示胶囊`() {
        val todos = GoalTodoParser.parseTodos(values("""{"todos":[]}"""))
        assertFalse(todos.visible)
        assertFalse(todos.allDone)
        assertFalse(todos.running)
    }

    @Test
    fun `未知状态退化成待办而不是丢弃`() {
        val todos = GoalTodoParser.parseTodos(
            values("""{"todos":[{"content":"a","status":"weird"}]}"""),
        )
        assertEquals(1, todos.total)
        assertEquals(TodoStatus.PENDING, todos.items.first().status)
    }

    @Test
    fun `缺 content 的条目被跳过`() {
        val todos = GoalTodoParser.parseTodos(
            values("""{"todos":[{"status":"completed"},{"content":"ok","status":"pending"}]}"""),
        )
        assertEquals(1, todos.total)
        assertEquals("ok", todos.items.first().content)
    }

    // ---------------------------------------------------------------- 独立性

    /**
     * 🔴 本轮最重要的一条：**目标与任务互不影响**。
     *
     * 用户 m45632 的原话是「这里你要参考 dsh 的设计逻辑，似乎当目标暂停时，对话还会继续
     * 输出，所以我认为这是独立的，但是实际还是要去找 dsh 的相关逻辑」。查证结论：
     *  - `goals/pause` 只让 Host GoalService 排队一条「模型可见的 goal 上下文消息」；
     *  - 整个客户端 bundle 里 goal 与 cancel / interrupt / abort **零关联**；
     *  - goal 的 activation（armed/disarmed）是**进程本地**状态，与会话无关。
     *
     * 所以「目标暂停」只意味着「下一轮不再自动继续」，**不冻结任务**。这条测试用
     * 「同一份快照里 goal 暂停、todos 仍在进行中」这种真实会出现的组合钉住它——
     * 如果哪天有人给 Goal 加了 todos 字段或让 Capsule 跟随 goal 变色，这里就会红。
     */
    @Test
    fun `目标暂停时任务仍在进行中`() {
        val v = values(
            """
            {"goal":{"id":"g","revision":1,"objective":"x","phase":"paused"},
             "todos":[{"content":"还在干","status":"in_progress"}]}
            """.trimIndent(),
        )
        val goal = GoalTodoParser.parseGoal(v)
        val todos = GoalTodoParser.parseTodos(v)

        assertEquals("目标确实暂停了", GoalPhase.PAUSED, goal?.phase)
        assertTrue("任务不受影响，仍有进行中", todos.running)
        assertEquals(TodoStatus.IN_PROGRESS, todos.items.first().status)
        assertFalse("任务没被全部标完成", todos.allDone)
    }

    /** 反向：任务全完成不代表目标完成。 */
    @Test
    fun `任务全部完成时目标仍在进行中`() {
        val v = values(
            """
            {"goal":{"id":"g","revision":1,"objective":"x","phase":"active"},
             "todos":[{"content":"a","status":"completed"}]}
            """.trimIndent(),
        )
        val goal = GoalTodoParser.parseGoal(v)
        val todos = GoalTodoParser.parseTodos(v)

        assertTrue("任务全完成", todos.allDone)
        assertEquals("目标仍进行中（两者独立）", GoalPhase.ACTIVE, goal?.phase)
        assertTrue(goal?.canPause == true)
    }

    // ---------------------------------------------------------------- goal/change

    @Test
    fun `goal change 的 create 与 pause 都带完整快照`() {
        val create = GoalTodoParser.parseGoalChange(
            values(
                """
                {"kind":"goal/change","operation":"create",
                 "goal":{"id":"goal-1","revision":1,"objective":"完成所有开发",
                  "phase":"active","maxGoalRounds":256}}
                """.trimIndent(),
            ),
        )
        assertTrue(create is GoalChange.Set)
        assertEquals(GoalPhase.ACTIVE, (create as GoalChange.Set).goal.phase)

        val pause = GoalTodoParser.parseGoalChange(
            values(
                """
                {"kind":"goal/change","operation":"pause",
                 "goal":{"id":"goal-1","revision":2,"objective":"完成所有开发","phase":"paused"}}
                """.trimIndent(),
            ),
        )
        assertTrue(pause is GoalChange.Set)
        assertEquals(GoalPhase.PAUSED, (pause as GoalChange.Set).goal.phase)
        assertEquals("revision 必须递增，CAS 靠它", 2L, pause.goal.revision)
    }

    @Test
    fun `goal change 的 clear 返回 Clear`() {
        val clear = GoalTodoParser.parseGoalChange(
            values("""{"kind":"goal/change","operation":"clear"}"""),
        )
        assertEquals(GoalChange.Clear, clear)
    }

    @Test
    fun `goal change 缺 goal 时按清除处理`() {
        val broken = GoalTodoParser.parseGoalChange(
            values("""{"kind":"goal/change","operation":"create"}"""),
        )
        assertEquals(GoalChange.Clear, broken)
    }
}
