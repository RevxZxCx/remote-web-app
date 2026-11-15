package com.revxzxcx.remotewebapp.core.capsule

import com.revxzxcx.remotewebapp.core.session.TodoItem
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度条的 todo 来源选择（R062-B020）。
 *
 * ## 修的是什么
 * 修前只有「正在看的那个会话」有 todo，后台会话选成焦点时卡片没有进度条。
 * 而状态栏上的胶囊恰恰是「我不在 App 里」时才看的东西——所以主场景反而是空白。
 * `session/list` 的 `projections.values.todos` 本来就带着所有会话的 todo，
 * 只是解析层此前丢掉了，接上之后后台会话也能画进度条。
 *
 * ## 为什么这一组只有三条
 * 判据被抽成纯函数 [capsuleTodos]，它只有一个 if。三条覆盖三种输入组合
 * （有快照有 todo / 有快照无 todo / 无快照），正好穷尽。
 *
 * **这一组存在的理由不是「覆盖率」，而是 B020 的修法此前没有任何用例**——
 * 上一版把判断埋在 `private fun stateFor` 里，唯一入口是 `recompute()`，
 * 而它被四层焦点选举包着，测试怎么都测不到。R059 的 M2 变异体存活
 * 就是这么来的（修了缺陷却没留用例），这里是同一个坑的第二次。
 */
class CapsuleTodosTest {

    private fun todos(vararg pairs: Pair<String, TodoStatus>): TodoList =
        TodoList(pairs.map { TodoItem(it.first, it.second) })

    private fun snapshot(
        sessionId: String,
        todos: TodoList?,
    ) = CapsuleCoordinator.LiveSnapshot(
        sessionId = sessionId,
        todos = todos,
        report = null,
        action = null,
    )

    @Test
    fun `有前台快照时用快照里的 todo 不用列表投影`() {
        val fromLive = todos("正在看的" to TodoStatus.IN_PROGRESS)
        val fromList = todos("列表里的旧进度" to TodoStatus.COMPLETED)

        val picked = capsuleTodos(snapshot("s1", fromLive), fromList)

        // 必须是同一个对象：快照每帧都在更新，比 1.5s 去抖的列表投影及时。
        // 用 `assertSame` 而不是 `assertEquals`，是为了让「忘了返回快照那份、
        // 而是重新拼了一个内容相同的」也红——那种写法会把及时性丢掉。
        assertSame(fromLive, picked)
    }

    @Test
    fun `没有前台快照时回落到列表投影 这是 B020 的主场景`() {
        val fromList = todos(
            "第一步" to TodoStatus.COMPLETED,
            "第二步" to TodoStatus.IN_PROGRESS,
        )

        val picked = capsuleTodos(null, fromList)

        // 修前这里恒为 null → 后台会话的卡片没有进度条也没有步骤行。
        assertSame(fromList, picked)
        assertEquals(2, picked?.total)
        assertEquals(1, picked?.done)
    }

    @Test
    fun `协调器按焦点会话的 id 取回落 todo 键不能取错`() {
        // 上一轮的 P2：`capsuleTodos` 是纯函数，键查找在它外面，
        // 所以把 `todosBySession[focus.sessionId]` 写成别的键，三条纯函数用例全绿。
        // 这里用结构断言钉住那一行——它是这条链上唯一还在协调器里的判断。
        val source = listOf(
            java.io.File("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCoordinator.kt"),
            java.io.File("app/src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCoordinator.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("找不到 CapsuleCoordinator.kt")

        assertTrue(
            "回落 todo 必须按**焦点会话**的 id 取，键取错会拿到别人的进度",
            source.contains("todosBySession[focus.sessionId]"),
        )
        assertTrue(
            "取到的结果要按位置传进 stateFor 的最后一个参数，写成别人或写反都会拿到错的进度",
            source.contains(
                "stateFor(focus.sessionId, focus.title, others, phaseOf(focus), fallbackTodos)",
            ),
        )
    }

    @Test
    fun `有快照但这一帧还没 todo 时不能回落 否则会闪上一轮的旧进度`() {
        val staleFromList = todos("上一轮的第三步" to TodoStatus.IN_PROGRESS)

        val picked = capsuleTodos(snapshot("s1", todos = null), staleFromList)

        // 判据是「有没有快照」而不是「快照里有没有 todo」。这两个条件看着像，
        // 差别就在这一条：会话刚开跑、服务端还没推 `todo/write` 时，列表投影里
        // 躺着的是上一轮的进度，显示它会让进度条在「3/5」和「空白」之间来回跳。
        assertNull(picked)
    }
}
