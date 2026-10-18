package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `goal/change` 事件的解析（目标横幅的实时来源）。
 *
 * 与 [GoalTodoParserTest] 的分工：那边测的是 `projections.values` 里的**快照**形状
 * （双层 `{goal:{goal:{…}}}`），这里测的是事件 `data` 里的**扁平**形状。两者不同，
 * 混用会让横幅要么不渲染、要么在暂停后不更新。
 */
class GoalTodoEventTest {

    private val json = Json

    private fun data(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `暂停事件带上新的相位与 revision`() {
        val change = GoalTodoParser.parseGoalChange(
            data(
                """
                {"kind":"goal","version":1,"operation":"pause",
                 "goal":{"id":"goal-1","revision":4,"objective":"目标正文",
                          "phase":"paused","maxGoalRounds":50},
                 "roundsStarted":0}
                """.trimIndent(),
            ),
        )

        val goal = (change as GoalChange.Set).goal
        assertEquals("goal-1", goal.id)
        assertEquals(4L, goal.revision)
        assertEquals(GoalPhase.PAUSED, goal.phase)
        // revision 必须跟着事件走：下一次操作要用它做 CAS，用陈旧值会被服务端拒绝。
        assertTrue(goal.canPause.not())
        assertEquals("开始目标", goal.labelFallback())
    }

    @Test
    fun `清除事件没有 goal`() {
        val change = GoalTodoParser.parseGoalChange(
            data("""{"kind":"goal","version":1,"operation":"clear"}"""),
        )
        assertTrue(change is GoalChange.Clear)
    }

    @Test
    fun `完成相位的目标不渲染横幅`() {
        val change = GoalTodoParser.parseGoalChange(
            data(
                """
                {"operation":"complete",
                 "goal":{"id":"goal-2","revision":9,"objective":"完事","phase":"complete"}}
                """.trimIndent(),
            ),
        )
        val goal = (change as GoalChange.Set).goal
        assertEquals(GoalPhase.COMPLETE, goal.phase)
        assertFalse(goal.visible)
    }

    @Test
    fun `认不出的相位保留原文且不崩`() {
        val change = GoalTodoParser.parseGoalChange(
            data(
                """
                {"operation":"edit",
                 "goal":{"id":"goal-3","revision":1,"objective":"x","phase":"blocked"}}
                """.trimIndent(),
            ),
        )
        val goal = (change as GoalChange.Set).goal
        assertEquals(GoalPhase.BLOCKED, goal.phase)
    }

    @Test
    fun `data 不是对象时返回 null`() {
        assertNull(GoalTodoParser.parseGoalChange(null))
    }

    private fun Goal.labelFallback(): String = when (phase) {
        GoalPhase.ACTIVE -> "暂停目标"
        GoalPhase.PAUSED -> "开始目标"
        else -> label
    }

    @Test
    fun `goal_change 事件里的 roundsStarted 从外层读`() {
        // 审查 P3-2：实时路径每收到一条 `goal/change` 就把整个 Goal 覆盖一次，
        // `roundsStarted` 只从快照（parseGoal）读的话，暂停/恢复之后又会退回 0，
        // 「轮次已用完」的置灰与本地早退会静默失效，而快照用例仍然全绿。
        //
        // 线上形状：`roundsStarted` 在 goal 本体**外面**那一层。
        val change = GoalTodoParser.parseGoalChange(
            data(
                """
                {"kind":"goal","version":1,"operation":"pause",
                 "goal":{"id":"goal-9","revision":7,"objective":"目标",
                          "phase":"paused","maxGoalRounds":50},
                 "roundsStarted":50}
                """.trimIndent(),
            ),
        )

        val goal = (change as GoalChange.Set).goal
        assertEquals(50, goal.roundsStarted)
        assertTrue("50/50 必须判定为轮次已用完", goal.roundsExhausted)
        assertFalse(goal.canPause)
    }

    @Test
    fun `goal_change 事件没带 roundsStarted 时退化为 0`() {
        // 缺字段不能崩，也不能把上限判成已用完（否则「开始」按钮永远置灰）。
        val change = GoalTodoParser.parseGoalChange(
            data(
                """
                {"kind":"goal","version":1,"operation":"resume",
                 "goal":{"id":"goal-10","revision":1,"objective":"目标",
                          "phase":"active","maxGoalRounds":50}}
                """.trimIndent(),
            ),
        )

        val goal = (change as GoalChange.Set).goal
        assertEquals(0, goal.roundsStarted)
        assertFalse(goal.roundsExhausted)
    }
}
