package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.revxzxcx.remotewebapp.core.session.Goal
import com.revxzxcx.remotewebapp.core.session.GoalPhase
import com.revxzxcx.remotewebapp.core.session.TodoItem
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 目标横幅与任务胶囊的渲染测试（R031）。
 *
 * 守住四类契约：
 * 1. 目标横幅三态：进行中给「暂停」、暂停中给「开始」、完成时整条不渲染。
 * 2. 右侧三个纯图标按钮；删除**不直接删**，只触发回调（二次确认由调用方弹）。
 * 3. 任务胶囊的 n/m 计数与全完成文案；无任务时不占位。
 * 4. 目标与任务**互不干涉**（用户 m45632 第 4 点，已查证 DSH 源码）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoalBannerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun goal(
        phase: GoalPhase = GoalPhase.ACTIVE,
        objective: String = "完成 R031 三项需求",
        revision: Long = 3L,
        rounds: Int? = 12,
    ) = Goal(
        id = "goal-1",
        revision = revision,
        objective = objective,
        phase = phase,
        maxGoalRounds = rounds,
    )

    private fun setBanner(
        goal: Goal = goal(),
        collapsed: Boolean = false,
        editing: Boolean = false,
        onToggleCollapsed: () -> Unit = {},
        onPauseResume: () -> Unit = {},
        onRequestEdit: () -> Unit = {},
        onEdit: (String) -> Unit = {},
        onCancelEdit: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        compose.setContent {
            DshAssistantTheme {
                GoalBanner(
                    goal = goal,
                    collapsed = collapsed,
                    editing = editing,
                    onToggleCollapsed = onToggleCollapsed,
                    onPauseResume = onPauseResume,
                    onRequestEdit = onRequestEdit,
                    onEdit = onEdit,
                    onCancelEdit = onCancelEdit,
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun `进行中的目标显示暂停按钮与目标正文`() {
        setBanner(goal(GoalPhase.ACTIVE))
        compose.onNodeWithTag(GoalBannerTestTag).assertExists()
        compose.onNodeWithText("完成 R031 三项需求").assertExists()
        compose.onNodeWithText("目标进行中", substring = true).assertExists()
        compose.onNodeWithContentDescription("暂停目标").assertExists()
        compose.onNodeWithContentDescription("编辑目标").assertExists()
        compose.onNodeWithContentDescription("删除目标").assertExists()
    }

    @Test
    fun `暂停的目标把暂停按钮换成开始`() {
        setBanner(goal(GoalPhase.PAUSED))
        compose.onNodeWithContentDescription("开始目标").assertExists()
        compose.onAllNodesWithContentDescription("暂停目标").assertCountEquals(0)
        compose.onNodeWithText("目标已暂停", substring = true).assertExists()
    }

    @Test
    fun `已完成的目标整条不渲染`() {
        setBanner(goal(GoalPhase.COMPLETE))
        compose.onAllNodesWithTag(GoalBannerTestTag).assertCountEquals(0)
    }

    @Test
    fun `收起后横幅不渲染`() {
        setBanner(goal(GoalPhase.ACTIVE), collapsed = true)
        compose.onAllNodesWithTag(GoalBannerTestTag).assertCountEquals(0)
    }

    @Test
    fun `点横幅本体触发收起回调`() {
        var collapsed = 0
        setBanner(goal(GoalPhase.ACTIVE), onToggleCollapsed = { collapsed++ })
        compose.onNodeWithText("完成 R031 三项需求").performClick()
        assertEquals(1, collapsed)
    }

    @Test
    fun `点删除只触发回调不直接删`() {
        var deleted = 0
        setBanner(goal(GoalPhase.ACTIVE), onDelete = { deleted++ })
        compose.onNodeWithContentDescription("删除目标").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `点暂停触发回调`() {
        var toggled = 0
        setBanner(goal(GoalPhase.ACTIVE), onPauseResume = { toggled++ })
        compose.onNodeWithContentDescription("暂停目标").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `点编辑进入行内编辑态`() {
        var requested = 0
        setBanner(goal(GoalPhase.ACTIVE), onRequestEdit = { requested++ })
        compose.onNodeWithContentDescription("编辑目标").performClick()
        assertEquals(1, requested)
    }

    @Test
    fun `行内编辑态显示输入框与保存取消`() {
        setBanner(goal(GoalPhase.ACTIVE), editing = true)
        compose.onNodeWithTag(GoalEditFieldTestTag).assertExists()
        compose.onNodeWithContentDescription("保存目标").assertExists()
        compose.onNodeWithContentDescription("取消编辑").assertExists()
        compose.onAllNodesWithContentDescription("删除目标").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("暂停目标").assertCountEquals(0)
    }

    @Test
    fun `行内编辑保存把新目标交出去`() {
        var saved: String? = null
        setBanner(goal(GoalPhase.ACTIVE, objective = "旧目标"), editing = true, onEdit = { saved = it })

        // 🔴 必须真的敲字（R031 独立审查 P2）：只点保存时断言等于初始文本，等于只测了
        // 「回调被调用」——输入框换成 `Text` 也绿。敲进新内容再保存，才守住
        // 「编辑框真的把用户输入交出去」。
        compose.onNodeWithTag(GoalEditFieldTestTag).performTextInput("新目标")
        compose.onNodeWithContentDescription("保存目标").performClick()
        // `performTextInput` 在光标处插入，而输入框初始化时光标在开头 → 新字在前。
        assertEquals("新目标旧目标", saved)
    }

    @Test
    fun `行内编辑取消不交目标`() {
        var saved: String? = null
        setBanner(goal(GoalPhase.ACTIVE), editing = true, onEdit = { saved = it })
        compose.onNodeWithContentDescription("取消编辑").performClick()
        assertEquals(null, saved)
    }

    private fun todos(vararg pairs: Pair<String, TodoStatus>) =
        TodoList(pairs.map { TodoItem(it.first, it.second) })

    private fun setPill(
        list: TodoList,
        expanded: Boolean = false,
        onToggle: () -> Unit = {},
    ) {
        compose.setContent {
            DshAssistantTheme {
                TaskPill(todos = list, expanded = expanded, onToggle = onToggle)
            }
        }
    }

    @Test
    fun `任务胶囊显示 n 比 m`() {
        setPill(
            todos(
                "a" to TodoStatus.COMPLETED,
                "b" to TodoStatus.COMPLETED,
                "c" to TodoStatus.IN_PROGRESS,
                "d" to TodoStatus.PENDING,
            ),
        )
        compose.onNodeWithTag(TaskPillTestTag).assertExists()
        compose.onNodeWithText("2/4").assertExists()
        compose.onNodeWithText("任务进行中").assertExists()
    }

    @Test
    fun `全部完成时胶囊显示全部完成`() {
        setPill(todos("a" to TodoStatus.COMPLETED, "b" to TodoStatus.COMPLETED))
        compose.onNodeWithText("任务全部完成").assertExists()
        compose.onNodeWithText("2/2").assertExists()
    }

    @Test
    fun `没有任务时胶囊不渲染`() {
        setPill(TodoList())
        compose.onAllNodesWithTag(TaskPillTestTag).assertCountEquals(0)
    }

    @Test
    fun `点胶囊触发展开回调`() {
        var toggled = 0
        setPill(todos("a" to TodoStatus.PENDING), onToggle = { toggled++ })
        compose.onNodeWithTag(TaskPillTestTag).performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `任务列表按状态显示三种文案`() {
        compose.setContent {
            DshAssistantTheme {
                TaskPanel(
                    todos = todos(
                        "已完成的事" to TodoStatus.COMPLETED,
                        "正在做的事" to TodoStatus.IN_PROGRESS,
                        "还没做的事" to TodoStatus.PENDING,
                    ),
                )
            }
        }
        compose.onNodeWithTag(TaskPanelTestTag).assertExists()
        compose.onNodeWithText("已完成的事").assertExists()
        compose.onNodeWithText("正在做的事").assertExists()
        compose.onNodeWithText("还没做的事").assertExists()
        compose.onNodeWithText("1 / 3 已完成").assertExists()
    }

    @Test
    fun `任务面板为空时不渲染`() {
        compose.setContent {
            DshAssistantTheme { TaskPanel(todos = TodoList()) }
        }
        compose.onAllNodesWithTag(TaskPanelTestTag).assertCountEquals(0)
    }

    /**
     * 目标与任务互不干涉（用户 m45632 第 4 点，已查证 DSH 源码）。
     *
     * 同时渲染「目标已暂停」的横幅与「任务仍在进行中」的胶囊：横幅给「开始」按钮，
     * 胶囊仍是「任务进行中」+ n/m。若有人让胶囊跟随目标变色，这里会红。
     */
    @Test
    fun `目标暂停不影响任务胶囊`() {
        compose.setContent {
            DshAssistantTheme {
                androidx.compose.foundation.layout.Column {
                    GoalBanner(
                        goal = goal(GoalPhase.PAUSED),
                        collapsed = false,
                        editing = false,
                        onToggleCollapsed = {},
                        onPauseResume = {},
                        onRequestEdit = {},
                        onEdit = {},
                        onCancelEdit = {},
                        onDelete = {},
                    )
                    TaskPill(
                        todos = todos(
                            "a" to TodoStatus.COMPLETED,
                            "b" to TodoStatus.IN_PROGRESS,
                        ),
                        expanded = false,
                        onToggle = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("开始目标").assertExists()
        compose.onNodeWithText("任务进行中").assertExists()
        compose.onNodeWithText("1/2").assertExists()
        compose.onAllNodesWithText("任务已暂停").assertCountEquals(0)
    }

    /**
     * 删除确认框的文案必须是「干净」的，不能把 Markdown 的 `**` 原样显示出来。
     *
     * R031 设备验收抓到的真实缺陷：`Text("…**任务列表不受影响**。")` 在真机上原样显示
     * 两个星号——`Text` 不解析 Markdown。旧测试只断言标题「删除目标？」，于是全绿放过了。
     */
    @Test
    fun `删除确认框文案不把 Markdown 星号显示出来`() {
        compose.setContent {
            DshAssistantTheme { DeleteGoalDialog(onConfirm = {}, onDismiss = {}) }
        }

        compose.onNodeWithText("任务列表不受影响", substring = true).assertExists()
        compose.onAllNodesWithText("*", substring = true).assertCountEquals(0)
    }

    @Test
    fun `删除确认框的按钮各自回调`() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            DshAssistantTheme {
                DeleteGoalDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }

        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }
}
