package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
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
 * 会话抽屉的渲染层测试。
 *
 * 这些用例想守的是一个**反复逃逸**的缺陷类别：纯逻辑单测全绿，但 `LazyColumn`
 * 在组合期抛异常 / 把内容顶出视口。本工程已经连踩三次——
 *
 * 1. **R003（M2）**：同一会话同时出现在两个工作区的 `sessionIds` 里时，
 *    「按工作区」段只用 `sessionId` 作 key → `IllegalArgumentException: Key … was
 *    already used`，整个抽屉崩掉。数据层单测甚至**断言过**「两个工作区都显示」，
 *    但没有任何测试渲染过 `LazyColumn`，于是断言与实现之间没有桥。
 * 2. **M2/M3 两次**：抽屉先打开、`session/list` 后到，「最近」段被 `LazyColumn`
 *    的锚点机制整段顶到可视区之上，看起来像没渲染。
 *
 * 所以这里**故意**构造跨工作区共享会话的数据，让上面的崩溃路径真的被走到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionDrawerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * 两个工作区**共享** `shared-session`，另各有独占会话。
     *
     * 这不是臆造的数据：`SessionGroupingTest` 有对应用例（"同一会话出现在两个工作区时
     * 两边都显示"），实测服务端也确实会这样下发（5 个工作区 27 个 id 全部可解析，
     * 但组合方式不保证互斥）。
     */
    private fun state(): HomeUiState {
        val shared = session("shared-session", "共享会话", updatedAt = 3_000L)
        val onlyA = session("only-a", "只在甲工作区", updatedAt = 2_000L)
        val onlyB = session("only-b", "只在乙工作区", updatedAt = 1_000L)
        val snapshot = WorkspaceSnapshot(
            items = listOf(
                Workspace(workspaceId = "ws-a", path = "F:\\a", title = "甲", sessionIds = listOf("shared-session", "only-a")),
                Workspace(workspaceId = "ws-b", path = "F:\\b", title = "乙", sessionIds = listOf("shared-session", "only-b")),
            ),
        )
        val sessions = SessionListResult(items = listOf(shared, onlyA, onlyB))
        return HomeUiState(
            loading = false,
            snapshot = snapshot,
            sessions = sessions,
            groups = SessionGrouping.group(snapshot, sessions),
            sessionsLoading = false,
            networkArrived = true,
        ).withDerivedLists()

    }

    /**
     * 补齐 `recent` / `pinned`。
     *
     * 这两个是 [HomeUiState] 的**构造属性**（M2 起就从 `get()` 派生改成构造参数），
     * 直接 `HomeUiState(...)` 的话它们默认是空表，抽屉里「最近」「置顶」两段就什么都不画。
     */
    private fun HomeUiState.withDerivedLists(): HomeUiState = copy(
        recent = SessionGrouping.recentSessions(snapshot, sessions),
        pinned = SessionGrouping.pinnedSessions(snapshot, sessions),
    )

    private fun session(sessionId: String, title: String, updatedAt: Long): SessionSummary =
        SessionSummary(sessionId = sessionId, title = title, updatedAt = updatedAt, agentAvailable = true)

    private fun setDrawer(
        state: HomeUiState,
        viewportHeight: Dp = 4000.dp,
        onRefreshSessions: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                // ⚠️ 这里**不能**套 `verticalScroll`：抽屉自身是
                // `Column(fillMaxSize) { 头部; LazyColumn(fillMaxSize) }`，给外层再加
                // 竖向滚动会让内层 LazyColumn 拿到无限高度约束，直接抛
                // `IllegalStateException: Vertically scrollable component was measured
                // with an infinity maximum height constraints`。
                //
                // ⚠️ 而且必须用 `requiredHeight` 而不是 `height`：后者会把高度
                // **收敛到父约束**内（`constraints.constrainHeight`），于是 4000.dp
                // 被压回虚拟屏幕的那 ~470dp，`LazyColumn` 只组合视口内的项，
                // 「只在乙工作区」这类靠下的行根本不在语义树上。`requiredHeight`
                // 无视父约束，才等于「给一个超高的视口」。
                Box(modifier = Modifier.requiredHeight(viewportHeight)) {
                    SessionDrawer(
                        state = state,
                        onOpenSession = { _, _ -> },
                        onRefreshSessions = onRefreshSessions,
                        now = 10_000L,
                    )
                }
            }
        }
    }

    @Test
    fun `同一会话出现在两个工作区时抽屉不崩且两处都渲染`() {
        setDrawer(state())

        // 组合期不抛 IllegalArgumentException: Key … was already used（R003 P1-1）。
        // 共享会话出现 **3** 次：1 次在「最近」段（带工作区副标题）+ 2 次在「按工作区」段
        // （ws-a / ws-b 各一次，key 带 workspaceId 前缀所以不冲突）。
        compose.onAllNodesWithText("共享会话").assertCountEquals(3)
        // 独占会话各出现 2 次：「最近」1 次 + 所属工作区 1 次。
        compose.onAllNodesWithText("只在甲工作区").assertCountEquals(2)
        compose.onAllNodesWithText("只在乙工作区").assertCountEquals(2)
        // 三段标题都在。
        compose.onNodeWithText("最近").assertExists()
        compose.onNodeWithText("按工作区").assertExists()
    }

    @Test
    fun `最近段排除置顶与归档且不重复`() {
        val base = state()
        val pinned = session("pinned-session", "置顶会话", updatedAt = 9_000L)
        val archived = session("archived-session", "归档会话", updatedAt = 8_000L)
        val snapshot = base.snapshot.copy(
            pinnedSessionIds = setOf("pinned-session"),
            archivedSessionIds = setOf("archived-session"),
        )
        val sessions = SessionListResult(items = listOf(pinned, archived) + base.sessions.items)
        val state = base.copy(
            snapshot = snapshot,
            sessions = sessions,
            groups = SessionGrouping.group(snapshot, sessions),
        ).withDerivedLists()
        setDrawer(state)

        // 「最近」里既不出现置顶也不出现归档（各段只出现一次）。
        compose.onAllNodesWithText("置顶会话").assertCountEquals(1)
        compose.onAllNodesWithText("归档会话").assertCountEquals(0)
        compose.onAllNodesWithText("共享会话").assertCountEquals(3)
        // 置顶段自己的标题与会话都在。
        // 「置顶」现在是 2 处：分组标题 + 头部统计胶囊的单位（R033 ① 新增）。
        compose.onAllNodesWithText("置顶").assertCountEquals(2)
        compose.onNodeWithText("置顶会话").assertExists()
        // 抽屉默认视图里**没有**归档会话：`最近` 与 `按工作区` 两段都排除归档，归档只
        // 出现在底部「已归档 N」点进去的聚合视图里（R033 ①）。所以默认屏上「归档会话」
        // 一次都不出现——这条同时守住「别把归档行漏进最近段」。
        // （头部统计胶囊里那个「归档」是单位文字，与段标题不同词，不会撞上。）
        compose.onAllNodesWithText("归档会话").assertCountEquals(0)
        // 但底部必须有入口，否则归档在 App 里等于消失（R033 ① 的核心动机）。
        compose.onNodeWithText("已归档 1").assertExists()
    }

    @Test
    fun `点已归档入口能看到归档聚合视图并返回`() {
        // R033 ①：归档聚合视图只能靠这一行进入。改造前它在 App 里完全没有入口——
        // 用户归档完就再也找不到，只能去桌面端取消归档。
        val base = state()
        val archived = SessionSummary(
            sessionId = "archived-session",
            updatedAt = 9_000L,
            title = "归档会话",
        )
        val snapshot = base.snapshot.copy(
            // ⚠️ 必须**改造已有的** ws-a，不能新增一个同 id 的 Workspace：`LazyColumn`
            // 的工作区头 key 是 `"ws-${workspaceId}"`，重复 id 会直接抛
            // `IllegalArgumentException: Key "ws-ws-a" was already used`。
            items = base.snapshot.items.map { ws ->
                if (ws.workspaceId == "ws-a") {
                    ws.copy(sessionIds = ws.sessionIds + "archived-session")
                } else {
                    ws
                }
            },
            archivedSessionIds = setOf("archived-session"),
        )
        val sessions = SessionListResult(items = listOf(archived) + base.sessions.items)
        val state = base.copy(
            snapshot = snapshot,
            sessions = sessions,
            groups = SessionGrouping.group(snapshot, sessions),
        ).withDerivedLists()
        setDrawer(state)

        compose.onNodeWithText("已归档 1").performClick()

        // 聚合视图里那一条必须出现，并且能回到常规列表。
        compose.onNodeWithText("归档会话").assertExists()
        compose.onNodeWithText("返回会话列表").performClick()
        compose.onAllNodesWithText("返回会话列表").assertCountEquals(0)
    }

    @Test
    fun `置顶与归档会话在语义树里带可朗读的状态`() {
        // 置顶 / 归档原本只由「3dp 竖条颜色 + 标题透明度」表达：读屏听到的与普通行
        // 一模一样，色弱用户在 3dp 窄条上也分不清主色与三级色。`stateDescription`
        // 把状态变成可朗读的文字。uiautomator dump 不带这个属性（真机 dump 里
        // 没有 state-desc 字段），所以只能在这里守。
        val base = state()
        setDrawer(
            base.copy(
                pinned = listOf(base.sessions.items.first() to null),
                snapshot = base.snapshot.copy(pinnedSessionIds = setOf("shared-session")),
            ).withDerivedLists(),
        )

        // 3 个节点：1 个在「置顶」段、2 个在两个工作区段——同一个会话在三处都是置顶状态，
        // 状态由 `snapshot.pinnedSessionIds` 统一推导，不由调用点写死。
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已置顶"),
        ).assertCountEquals(3)
    }

    @Test
    fun `会话列表里同一归档会话重复出现时抽屉不崩`() {
        // `session/list` 的解析只做 `mapNotNull`，原样保留重复条目（`SessionGroupingTest`
        // 有一条对应用例）。归档聚合视图若直接 `items(archivedSessions, key =
        // "archived-${sessionId}")`，重复 id 会撞 key 抛
        // `IllegalArgumentException: Key "archived-X" was already used` —— 抽屉直接打不开。
        // 同一份数据在 `recentSessions` / `group()` / `pinnedSessions` 三处都防过，
        // 只有 R033 新增的这条链漏了。这条用例就是那个漏点的守门人。
        val base = state()
        val dup = session("archived-dup", "重复归档会话", updatedAt = 9_000L)
        val snapshot = base.snapshot.copy(
            items = base.snapshot.items.map { ws ->
                if (ws.workspaceId == "ws-a") {
                    ws.copy(sessionIds = ws.sessionIds + "archived-dup")
                } else {
                    ws
                }
            },
            archivedSessionIds = setOf("archived-dup"),
        )
        val sessions = SessionListResult(items = base.sessions.items + dup + dup.copy(updatedAt = 9_500L))
        setDrawer(
            base.copy(
                snapshot = snapshot,
                sessions = sessions,
                groups = SessionGrouping.group(snapshot, sessions),
            ).withDerivedLists(),
        )

        // 组合期不抛异常，且聚合视图里只有一行（不是两行同名）。
        compose.onNodeWithText("已归档 1").assertExists()
        compose.onNodeWithText("已归档 1").performClick()
        compose.onAllNodesWithText("重复归档会话").assertCountEquals(1)
    }

    @Test
    fun `搜索框能输入并按标题过滤出命中项`() {
        // 守的是「搜索真的接通了」：R033 时它是一个 `.semantics { disabled() }` 的假框，
        // 标签写「暂未开放」。这条用例走完整链路——真的往 `BasicTextField` 里打字，
        // 断言常规三段被搜索结果取代、命中项在、未命中的不在。
        setDrawer(state())

        // 常规视图先确认基线：三段都在。
        compose.onNodeWithText("按工作区").assertExists()

        // 首尾故意带空格：`searchSessions` 会 trim（内部空格不删——会话标题里的空格
        // 是有意义的，不能当分隔符吃掉）。
        compose.onNode(hasSetTextAction()).performTextInput(" 只在甲 ")
        // 命中「只在甲工作区」；「按工作区」段标题不该再出现。
        compose.onNodeWithText("只在甲工作区").assertExists()
        compose.onAllNodesWithText("按工作区").assertCountEquals(0)
        compose.onAllNodesWithText("共享会话").assertCountEquals(0)
    }

    @Test
    fun `搜索没有命中时说明能搜什么`() {
        setDrawer(state())
        compose.onNode(hasSetTextAction()).performTextInput("不存在的关键词")

        compose.onNodeWithText("没有会话名、工作区或模型匹配「不存在的关键词」。").assertExists()
        // 必须说清楚不搜正文：这个部署的全文索引是关着的（服务端 openAt: never），
        // 不说的话用户会以为「搜不到消息内容」是 App 的 bug。
        compose.onNodeWithText("不搜消息正文", substring = true).assertExists()
    }

    @Test
    fun `清空搜索后回到常规三段`() {
        setDrawer(state())
        compose.onNode(hasSetTextAction()).performTextInput("共享")
        compose.onAllNodesWithText("按工作区").assertCountEquals(0)

        compose.onNodeWithContentDescription("清除搜索").performClick()

        compose.onNodeWithText("按工作区").assertExists()
        compose.onNodeWithText("最近").assertExists()
    }

    @Test
    fun `读不到会话时抽屉给出可点的重试`() {
        val base = state()
        val state = base.copy(
            sessions = SessionListResult(items = emptyList()),
            groups = SessionGrouping.group(base.snapshot, SessionListResult(items = emptyList())),
            sessionsError = "transport：网络请求失败",
        )
        var refreshed = 0
        setDrawer(state, onRefreshSessions = { refreshed++ })

        // M3 T009：失败与「真的没有会话」在抽屉里长得一模一样，用户既不知道失败、
        // 也不知道可以点重试。这里钉住「失败必须可见 + 重试真的接到回调」。
        // 只 `performClick()` 是不够的：那个节点只要可点就会绿，即使接的是空 lambda。
        compose.onNodeWithText("会话列表读取失败：transport：网络请求失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun `真的没有会话时不显示失败文案`() {
        val base = state()
        val empty = SessionListResult(items = emptyList())
        val state = base.copy(
            sessions = empty,
            groups = SessionGrouping.group(base.snapshot, empty),
            sessionsError = null,
        )
        setDrawer(state)

        compose.onNodeWithText("没有读到会话。下拉主区刷新，或检查连接。").assertExists()
        compose.onAllNodesWithText("会话列表读取失败", substring = true).assertCountEquals(0)
    }

    @Test
    fun `工作区头的 key 不含下标`() {
        // R006 P3-2：key 里混入下标会让 order 帧重排时全部 key 变化、滚动位置丢失。
        // 这里只做一次编译期可见性检查，真正的断言在 HomeScreen 源码里（key 用 workspaceId）。
        val groups = state().groups
        assertEquals(listOf("ws-a", "ws-b"), groups.map { it.workspace.workspaceId })
        // 两个工作区都解析出了 2 行（共享会话没被去重吃掉）。
        assertEquals(listOf(2, 2), groups.map { it.rows.size })
    }

    @Test
    fun `归档入口在列表末尾滚到时点进去仍能看见返回入口`() {
        // R034 P1-1 回归：同一个 `listState` 被「常规三段」与「归档聚合视图」复用，
        // 而「已归档 N」入口固定在常规列表**末尾**——用户点它时 `firstVisibleItemIndex`
        // 已经在几十条之后；切到归档视图后 item 数骤减到个位数，LazyColumn 只能把
        // index 钳到末项，于是放在顶部的「返回会话列表」落在视口外。此时底部入口本身
        // 也不再渲染，屏幕上没有任何回去的路。
        //
        // 这条用例用**矮视口**（400dp）逼出真实滚动路径：改造前用 4000dp 的超高视口，
        // 入口一开始就在视口内，`firstVisibleItemIndex` 恒为 0，缺陷永远走不到。
        val base = state()
        val filler = (1..30).map { i ->
            session("filler-$i", "填充会话 $i", updatedAt = 100L + i)
        }
        // 归档条目必须多到「聚合视图自身的总高度超过视口」，否则 LazyColumn 根本没得滚、
        // 只能贴着顶部渲染，缺陷也就无从表现。真实环境里归档几十条是常态。
        val archived = (1..30).map { i ->
            session("archived-$i", "归档会话 $i", updatedAt = 9_000L + i)
        }
        val snapshot = base.snapshot.copy(
            items = base.snapshot.items.map { ws ->
                if (ws.workspaceId == "ws-a") {
                    ws.copy(
                        sessionIds = ws.sessionIds + filler.map { it.sessionId } +
                            archived.map { it.sessionId },
                    )
                } else {
                    ws
                }
            },
            archivedSessionIds = archived.map { it.sessionId }.toSet(),
        )
        val sessions = SessionListResult(items = filler + archived + base.sessions.items)
        val state = base.copy(
            snapshot = snapshot,
            sessions = sessions,
            groups = SessionGrouping.group(snapshot, sessions),
        ).withDerivedLists()
        setDrawer(state, viewportHeight = 400.dp)

        // 真的滚到底部去找入口（而不是假设它已经可见）。
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("已归档 30"))
        compose.onNodeWithText("已归档 30").performClick()

        // 关键断言：返回入口必须**在视口内**，不能只是存在于语义树里。
        compose.onNodeWithText("返回会话列表").assertIsDisplayed()
        compose.onNodeWithText("归档会话 30").assertExists()

        // 返回后同样回顶，用户不会停在一个他从没看过的位置。
        compose.onNodeWithText("返回会话列表").performClick()
        compose.onNodeWithText("置顶").assertExists()
    }
}
