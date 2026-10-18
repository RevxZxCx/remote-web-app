package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话长按菜单的渲染层测试。
 *
 * 守的是四件事：
 * 1. **长按**（不是点击）才出菜单——行本身仍然是「点一下打开会话」，两条手势不能互相吞。
 * 2. 文案跟着状态走：普通行显示「移入归档」，已归档行显示「取消归档」；置顶同理。
 * 3. **移入归档要二次确认**（R033 ②）：点菜单项不能直接回调 `onArchive`，必须先出
 *    「移入归档？」对话框。这条断言的意义是——归档是唯一会让行从列表消失的操作，
 *    误触的代价是一条会话在 App 里再也看不到。
 * 4. **不能出现「删除」**：服务端没有删除会话的 API（`SessionActions` 的 KDoc 记了
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
        title: String? = null,
        subtitle: String? = null,
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SessionActionsMenu(
                    expanded = expanded,
                    onDismiss = onDismiss,
                    archived = archived,
                    pinned = pinned,
                    busy = busy,
                    onArchive = onArchive,
                    onUnarchive = onUnarchive,
                    onPin = onPin,
                    onUnpin = onUnpin,
                    modifier = Modifier,
                    title = title,
                    subtitle = subtitle,
                )
            }
        }
    }

    @Test
    fun `收起时什么都不显示`() {
        setMenu(expanded = false)
        compose.onAllNodesWithText("移入归档").assertCountEquals(0)
    }

    @Test
    fun `普通行显示移入归档与置顶`() {
        setMenu(expanded = true)
        compose.onNodeWithText("移入归档").assertIsDisplayed()
        compose.onNodeWithText("置顶").assertIsDisplayed()
        // 已归档的行才会出现「取消归档」。
        compose.onAllNodesWithText("取消归档").assertCountEquals(0)
        compose.onAllNodesWithText("取消置顶").assertCountEquals(0)
    }

    @Test
    fun `已归档行显示取消归档`() {
        setMenu(expanded = true, archived = true)
        compose.onNodeWithText("取消归档").assertIsDisplayed()
        compose.onAllNodesWithText("移入归档").assertCountEquals(0)
        compose.onNodeWithText("置顶").assertIsDisplayed()
    }

    @Test
    fun `置顶行显示取消置顶`() {
        setMenu(expanded = true, pinned = true)
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.onAllNodesWithText("置顶").assertCountEquals(0)
        compose.onNodeWithText("移入归档").assertIsDisplayed()
    }

    @Test
    fun `进行中的动作禁用两个入口`() {
        setMenu(expanded = true, busy = true)
        compose.onNodeWithText("移入归档").assertIsNotEnabled()
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
    fun `菜单顶部显示会话上下文`() {
        // 长按之后菜单会盖住被按的那一行，没有上下文头就不知道在操作哪一条。
        setMenu(
            expanded = true,
            title = "被按住的会话",
            subtitle = "远程 Web · 3 分钟前",
        )
        compose.onNodeWithText("被按住的会话").assertIsDisplayed()
        compose.onNodeWithText("远程 Web · 3 分钟前").assertIsDisplayed()
    }

    @Test
    fun `点移入归档先收菜单再弹二次确认且不立即回调`() {
        var archiveCalls = 0
        var dismissed = 0
        setMenu(
            expanded = true,
            onArchive = { archiveCalls++ },
            onDismiss = { dismissed++ },
        )

        compose.onNodeWithText("移入归档").performClick()

        // 关键断言：点完菜单项，onArchive 还不能被调用。
        assertEquals(0, archiveCalls)
        // 而且必须先收起菜单：菜单与 AlertDialog 是两个独立 Popup，同时存在时层级由
        // 最后请求者决定，随平台/版本变。`setMenu` 原先把 `onDismiss` 写死成空 lambda，
        // 所以这个顺序从来没被守过——把实现里的 `onDismiss()` 删掉，本条仍会全绿。
        assertTrue("点「移入归档」必须先收起菜单", dismissed >= 1)
        compose.onNodeWithText("移入归档？").assertIsDisplayed()
        // 对话框里说明「可以从已归档里找回」，让这一步是可预期的。
        compose.onNodeWithText("可以从「已归档」里找回。", substring = true).assertIsDisplayed()
    }

    @Test
    fun `二次确认点取消不回调`() {
        var archiveCalls = 0
        setMenu(expanded = true, onArchive = { archiveCalls++ })

        compose.onNodeWithText("移入归档").performClick()
        compose.onNodeWithText("取消").performClick()

        assertEquals(0, archiveCalls)
        compose.onAllNodesWithText("移入归档？").assertCountEquals(0)
    }

    @Test
    fun `二次确认点确认才回调一次`() {
        var archiveCalls = 0
        setMenu(expanded = true, onArchive = { archiveCalls++ })

        compose.onNodeWithText("移入归档").performClick()
        // 对话框的确认按钮是「确认移入」，与菜单项不同名——同名会让两处节点共存时
        // `onNodeWithText` 命中两个（R033 首跑踩到）。
        compose.onNodeWithText("确认移入").performClick()

        assertEquals(1, archiveCalls)
    }

    @Test
    fun `点置顶不弹确认直接回调`() {
        // 置顶可逆且无破坏性，不该多一次打扰。
        var pinCalls = 0
        setMenu(expanded = true, onPin = { pinCalls++ })

        compose.onNodeWithText("置顶").performClick()

        assertEquals(1, pinCalls)
        compose.onAllNodesWithText("移入归档？").assertCountEquals(0)
    }

    @Test
    fun `取消归档不弹确认`() {
        // 取消归档是把会话放回来，不是破坏性动作。
        var unarchiveCalls = 0
        setMenu(expanded = true, archived = true, onUnarchive = { unarchiveCalls++ })

        compose.onNodeWithText("取消归档").performClick()

        assertEquals(1, unarchiveCalls)
        compose.onAllNodesWithText("移入归档？").assertCountEquals(0)
    }

    @Test
    fun `抽屉里长按一行真的能唤出菜单并走完二次确认`() {
        // 这一条守的是 `combinedClickable(onClick, onLongClick)` 的接线：只写 onClick
        // 不长按出菜单，菜单就永远点不到；反过来长按若同时触发 onClick 会一边打开
        // 会话一边弹菜单。Compose 的 combinedClickable 已经保证互斥，这里把整条
        // 「长按 → 菜单出现 → 移入归档 → 二次确认 → 回调」的链路钉住。
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
                Box(
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
        compose.onNodeWithText("移入归档").assertIsDisplayed()
        compose.onNodeWithText("移入归档").performClick()
        assertEquals(emptyList<String>(), archived)
        compose.onNodeWithText("确认移入").performClick()
        assertEquals(listOf("s-row"), archived)
    }
}
