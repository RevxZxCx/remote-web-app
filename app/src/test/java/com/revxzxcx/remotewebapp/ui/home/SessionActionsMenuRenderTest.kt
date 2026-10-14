package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionListResult
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话长按菜单的渲染层测试。
 *
 * 守的是两件事：
 * 1. **长按**（不是点击）才出菜单——行本身仍然是「点一下打开会话」，两条手势不能互相吞。
 * 2. 文案跟着状态走：普通行显示「归档」，已归档行显示「取消归档」；置顶同理。
 *    以及**不能出现「删除」**：服务端没有删除会话的 API（`SessionActions` 的 KDoc 记了
 *    全量 grep 的证据），菜单里出现「删除」就是承诺了一个做不到的操作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionActionsMenuRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setMenu(
        expanded: Boolean,
        archived: Boolean = false,
        pinned: Boolean = false,
        busy: Boolean = false,
        onArchive: () -> Unit = {},
        onUnarchive: () -> Unit = {},
        onPin: () -> Unit = {},
        onUnpin: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SessionActionsMenu(
                    expanded = expanded,
                    onDismiss = {},
                    archived = archived,
                    pinned = pinned,
                    busy = busy,
                    onArchive = onArchive,
                    onUnarchive = onUnarchive,
                    onPin = onPin,
                    onUnpin = onUnpin,
                    modifier = Modifier,
                )
            }
        }
    }

    @Test
    fun `收起时什么都不显示`() {
        setMenu(expanded = false)
        compose.onAllNodesWithText("归档").assertCountEquals(0)
    }

    @Test
    fun `普通行显示归档与置顶`() {
        setMenu(expanded = true)
        compose.onNodeWithText("归档").assertIsDisplayed()
        compose.onNodeWithText("置顶").assertIsDisplayed()
        // 已归档的行才会出现「取消归档」。
        compose.onAllNodesWithText("取消归档").assertCountEquals(0)
        compose.onAllNodesWithText("取消置顶").assertCountEquals(0)
    }

    @Test
    fun `已归档行显示取消归档`() {
        setMenu(expanded = true, archived = true)
        compose.onNodeWithText("取消归档").assertIsDisplayed()
        compose.onAllNodesWithText("归档").assertCountEquals(0)
        compose.onNodeWithText("置顶").assertIsDisplayed()
    }

    @Test
    fun `置顶行显示取消置顶`() {
        setMenu(expanded = true, pinned = true)
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.onAllNodesWithText("置顶").assertCountEquals(0)
        compose.onNodeWithText("归档").assertIsDisplayed()
    }

    @Test
    fun `进行中的动作禁用两个入口`() {
        setMenu(expanded = true, busy = true)
        compose.onNodeWithText("归档").assertIsNotEnabled()
        compose.onNodeWithText("置顶").assertIsNotEnabled()
    }

    @Test
    fun `菜单里没有删除入口`() {
        // 服务端没有 session 删除 API（SDK 全量 grep 零命中）。这条断言是为了让
        // 「加一个删除按钮」这个念头在测试层面就被挡住。
        setMenu(expanded = true, archived = true, pinned = true)
        compose.onAllNodesWithText("删除", substring = true).assertCountEquals(0)
    }

    @Test
    fun `点归档会调一次回调`() {
        var archiveCalls = 0
        var pinCalls = 0
        setMenu(expanded = true, onArchive = { archiveCalls++ }, onPin = { pinCalls++ })
        compose.onNodeWithText("归档").performClick()
        compose.onNodeWithText("置顶").performClick()
        assertEquals(1, archiveCalls)
        assertEquals(1, pinCalls)
    }

    @Test
    fun `抽屉里长按一行真的能唤出菜单并点到归档`() {
        // 这一条守的是 `combinedClickable(onClick, onLongClick)` 的接线：只写 onClick
        // 不长按出菜单，菜单就永远点不到；反过来长按若同时触发 onClick 会一边打开
        // 会话一边弹菜单。Compose 的 combinedClickable 已经保证互斥，这里把整条
        // 「长按 → 菜单出现 → 点归档 → 回调」的链路钉住。
        var opened = 0
        var archived = emptyList<String>()
        val summary = com.revxzxcx.remotewebapp.core.session.SessionSummary(
            sessionId = "s-row",
            title = "可长按的会话",
            updatedAt = 5_000L,
            agentAvailable = true,
        )
        val snapshot = com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot(
            items = listOf(
                com.revxzxcx.remotewebapp.core.workspace.Workspace(
                    workspaceId = "ws-a",
                    path = "F:\\a",
                    title = "甲",
                    sessionIds = listOf("s-row"),
                ),
            ),
        )
        val sessions = com.revxzxcx.remotewebapp.core.session.SessionListResult(items = listOf(summary))
        val state = HomeUiState(
            loading = false,
            snapshot = snapshot,
            sessions = sessions,
            groups = com.revxzxcx.remotewebapp.core.session.SessionGrouping.group(snapshot, sessions),
            sessionsLoading = false,
            networkArrived = true,
        ).let {
            it.copy(
                recent = com.revxzxcx.remotewebapp.core.session.SessionGrouping.recentSessions(snapshot, sessions),
                pinned = emptyList(),
            )
        }

        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.requiredHeight(4000.dp),
                ) {
                    SessionDrawer(
                        state = state,
                        onOpenSession = { _, _ -> opened++ },
                        onRefreshSessions = {},
                        now = 10_000L,
                        onArchive = { archived = archived + it },
                    )
                }
            }
        }

        // 同一条会话会同时出现在「最近」与「按工作区 → 甲」两段（这是 M2 起就有的
        // 既有行为，`SessionDrawerRenderTest` 专门守它），所以这里取第一个节点。
        compose.onAllNodesWithText("可长按的会话")[0].performTouchInput { longClick() }
        // 长按只出菜单，不打开会话。
        assertEquals(0, opened)
        compose.onNodeWithText("归档").assertIsDisplayed()
        compose.onNodeWithText("归档").performClick()
        assertEquals(listOf("s-row"), archived)
    }
}
