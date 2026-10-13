package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工作区页（原型 `#home`）。
 *
 * 结构：左侧会话抽屉（最近 / 置顶 / 按工作区分组）+ 主区工作区列表。
 * 点一个工作区就地展开它的会话（原型里的「点工作区展开会话列表」交互），
 * 点一条会话交给 [onOpenSession]（M3 接聊天页）。
 *
 * 断线重连时**不清空**列表，只改顶部状态条文案（代码审查清单 3.4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onUnpair: () -> Unit,
    onOpenSession: ((SessionSummary, Workspace?) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow()

    // 抽屉的开合由 `drawerState` 自己持有（`ModalNavigationDrawer` 已经处理了手势与返回键），
    // 不再镜像到 ViewModel —— 早先那个 `drawerOpen` 字段没有任何回写点，恒为 false，
    // 只会留下错误的心智模型（R003 P3-2）。

    val openSession: (SessionSummary, Workspace?) -> Unit = { session, workspace ->
        val handler = onOpenSession
        if (handler != null) {
            handler(session, workspace)
        } else {
            scope.launch {
                snackbar.showSnackbar("聊天页将在 M3 接入（会话 ${session.sessionId.takeLast(8)}）")
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawer(
                    state = state,
                    onOpenSession = { session, workspace ->
                        scope.launch { drawerState.close() }
                        openSession(session, workspace)
                    },
                    onRefreshSessions = viewModel::refreshSessions,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("工作区") },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("会话")
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::retry) { Text("刷新") }
                        TextButton(onClick = onUnpair) { Text("退出") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ConnectionBar(text = state.connection, ok = state.error == null)

                state.error?.let { error ->
                    Surface(color = Color(0xFF3A1E22), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (state.needsRepair) "配对已失效：$error" else "连接失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFB4AB),
                            )
                            if (state.needsRepair) {
                                Button(onClick = onUnpair) { Text("重新配对") }
                            } else {
                                Button(onClick = viewModel::retry) { Text("重试") }
                            }
                        }
                    }
                }

                // 会话摘要失败不阻塞工作区列表，只挂一条黄条。
                state.sessionsError?.let { error ->
                    Surface(color = Color(0xFF33280F), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "会话列表读取失败：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF3D08A),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::refreshSessions) { Text("重试") }
                        }
                    }
                }

                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取工作区…", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    state.snapshot.isEmpty -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "这个桌面端还没有工作区。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = state.groups,
                            key = { it.workspace.workspaceId },
                        ) { group ->
                            WorkspaceRow(
                                group = group,
                                expanded = group.workspace.workspaceId in state.expandedWorkspaceIds,
                                onToggle = { viewModel.toggleWorkspace(group.workspace.workspaceId) },
                                onOpenSession = { openSession(it, group.workspace) },
                                now = now,
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部连接状态条。 */
@Composable
private fun ConnectionBar(text: String, ok: Boolean) {
    Surface(
        color = if (ok) Color(0xFF12301F) else Color(0xFF33280F),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (ok) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(7.dp),
            ) {}
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) Color(0xFF9BE7B8) else Color(0xFFF3D08A),
            )
        }
    }
}

/**
 * 单个工作区行；[expanded] 时在下方列出它的会话。
 *
 * 计数显示 [SessionGrouping.WorkspaceGroup.activeCount]（非归档）而不是
 * `sessionIds.size`：归档会话默认折叠，显示总数会让人以为少了东西。
 */
@Composable
private fun WorkspaceRow(
    group: SessionGrouping.WorkspaceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val workspace = group.workspace
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = workspace.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (group.hasRunning) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF4ADE80),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.size(6.dp),
                        ) {}
                    }
                }
                Text(
                    text = workspace.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("${group.activeCount} 个会话")
                        if (group.archivedCount > 0) append(" · ${group.archivedCount} 个已归档")
                        if (group.missingIds.isNotEmpty()) append(" · ${group.missingIds.size} 个读取不到")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }

        if (expanded) {
            val pinned = group.pinnedRows
            val archived = group.rows.filter { it.isArchived }
            val recent = group.rows.filter { it.isArchived.not() && it.isPinned.not() }

            if (pinned.isNotEmpty()) {
                SectionLabel("置顶")
                pinned.forEach { SessionRowItem(it, onOpenSession, now) }
            }
            if (recent.isNotEmpty()) {
                if (pinned.isNotEmpty()) SectionLabel("会话")
                recent.forEach { SessionRowItem(it, onOpenSession, now) }
            }
            if (archived.isNotEmpty()) {
                SectionLabel("已归档 ${archived.size}")
                archived.forEach { SessionRowItem(it, onOpenSession, now) }
            }
            if (group.rows.isEmpty()) {
                Text(
                    text = "这个工作区还没有会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 40.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 40.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** 会话列表里的一行：标题 + 时间 + 状态点。 */
@Composable
private fun SessionRowItem(
    row: SessionGrouping.SessionRow,
    onOpenSession: (SessionSummary) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val summary = row.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSession(summary) }
            .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = summary.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (row.isArchived) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                summary.lastModel?.let { model ->
                    Text(
                        text = model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = TimeText.relative(summary.updatedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
        if (summary.running) {
            Surface(
                color = Color(0xFF4ADE80),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(6.dp),
            ) {}
        } else if (row.isArchived) {
            Text(
                text = "归档",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * 左侧会话抽屉：置顶 / 最近 / 按工作区分组。
 *
 * 为什么要有抽屉而不是只靠工作区展开：实测有 5 个工作区、47 条会话，
 * 用户「最近在哪个会话里说过话」的记忆通常强于「它在哪个工作区」，
 * 所以「最近」这一段是主入口（原型 `#home` 的会话抽屉）。
 */
@Composable
private fun SessionDrawer(
    state: HomeUiState,
    onOpenSession: (SessionSummary, Workspace?) -> Unit,
    onRefreshSessions: () -> Unit,
) {
    // 派生列表现在是 HomeUiState 的构造属性（不再是 get() 派生），在 LazyColumn 的
    // content lambda 里读也一定是当前值。这里取成局部变量只是少写几次 state.。
    val pinned = state.pinned
    val recent = state.recent
    val groups = state.groups
    val now = rememberNow()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("会话", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (state.sessionsLoading) {
                        "正在读取会话…"
                    } else {
                        val topLevel = state.snapshot.items.sumOf { it.sessionIds.size }
                        "共 ${state.sessions.items.size} 条 · 顶层 $topLevel · 已归档 ${state.snapshot.archivedSessionIds.size}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

        // 真机踩到的坑（M2 T009 首次出现，M3 复验时仍能复现）：抽屉先打开、会话列表后到，
        // 此时列表首项是「按工作区」；等 13 条「最近」会话插到它前面时，LazyColumn 会按
        // 「当前可见首项的 key」保持锚点，把整段「最近」顶到可视区之上——看起来像整段
        // 没渲染（截图证据见测试记录 T009）。
        //
        // ⚠️ 触发条件**不能**写成「会话列表非空」：`session/list`（unary）与
        // `workspace/follow`（流）是两条独立的通道，先后顺序不保证。真机实测到过
        // `session/list` 先返回、此时 `snapshot.items` 还是空、于是 `recent` 也是空
        // （抽屉标题显示「共 51 条 · 顶层 0」）——一次性标志在这一刻就被消耗掉，等
        // 工作区快照到达、`recent` 真正填满 13 条时再也不会回顶，「最近」整段就留在
        // 可视区之上。所以必须挂在**抽屉自己的行数据**上。
        //
        // ⚠️ 而且必须用 `requestScrollToItem` 而不是 `scrollToItem`：后者是挂起函数，
        // 在 item provider 更新前就执行完了，紧接着 LazyColumn 的锚点恢复会把滚动位置
        // 按 key 拉回原位（M3 真机实测：只滚一次无效）。`requestScrollToItem` 把滚动
        // 请求登记到**下一次测量**，恰好与锚点恢复在同一趟里生效。
        //
        // 只在**首次**出现行数据时回到顶部，不能写成「分段数量变化就回顶」：每次
        // refreshSessions() / 重连补拉都会改变 recent.size，会把正在浏览「按工作区」段
        // 的用户强制弹回顶部（R003 P2-1）。
        val hasDrawerRows = recent.isNotEmpty() || pinned.isNotEmpty()
        val listState = rememberLazyListState()
        var restoredForSessions by remember { mutableStateOf(false) }
        LaunchedEffect(hasDrawerRows) {
            if (hasDrawerRows && !restoredForSessions) {
                restoredForSessions = true
                listState.requestScrollToItem(0)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (pinned.isNotEmpty()) {
                item(key = "hdr-pinned") { DrawerHeader("置顶") }
                items(pinned, key = { "pin-${it.first.sessionId}" }) { pair ->
                    DrawerSessionRow(
                        summary = pair.first,
                        subtitle = pair.second?.displayName,
                        onClick = { onOpenSession(pair.first, pair.second) },
                        now = now,
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "hdr-recent") { DrawerHeader("最近") }
                items(recent, key = { "recent-${it.sessionId}" }) { summary ->
                    DrawerSessionRow(
                        summary = summary,
                        subtitle = state.snapshot.workspaceOf(summary.sessionId)?.displayName,
                        onClick = {
                            onOpenSession(summary, state.snapshot.workspaceOf(summary.sessionId))
                        },
                        now = now,
                    )
                }
            }

            item(key = "hdr-ws") { DrawerHeader("按工作区") }
            groups.forEachIndexed { index, group ->
                item(key = "ws-$index-${group.workspace.workspaceId}") {                    Text(
                        text = "${group.workspace.displayName} · ${group.activeCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(
                    // 排除归档与置顶：归档在「已归档」段、置顶在「置顶」段各有归属，
                    // 同一会话在抽屉里出现两次是 R003 指出的问题。
                    group.rows.filter { it.isArchived.not() && it.isPinned.not() },
                    // key 必须带上 workspaceId：同一个会话可能同时出现在两个工作区的
                    // sessionIds 里（`SessionGroupingTest` 有专门用例），只用 sessionId
                    // 会让 LazyColumn 因重复 key 直接抛 IllegalArgumentException。
                    key = { "drawer-${group.workspace.workspaceId}-${it.sessionId}" },
                ) { row ->
                    DrawerSessionRow(
                        summary = row.summary,
                        subtitle = null,
                        onClick = { onOpenSession(row.summary, group.workspace) },
                        now = now,
                    )
                }
            }

            // 空态必须能区分「读取失败」与「确实没有会话」。旧实现只写「没有读到会话。
            // 下拉主区刷新，或检查连接。」——而 `sessionsError` 的黄条只挂在主区，
            // 抽屉里根本看不到，于是超时失败与空列表长得一模一样（M3 T009 真机踩到：
            // 冷启动 `session/list` 要 2.6s，与 mux 建连抢带宽时会超时，用户只看到
            // 「没有读到会话」，既不知道是失败、也不知道可以点重试）。
            if (state.sessions.isEmpty && state.sessionsLoading.not()) {
                item(key = "empty") {
                    val failure = state.sessionsError
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (failure == null) {
                                "没有读到会话。下拉主区刷新，或检查连接。"
                            } else {
                                "会话列表读取失败：$failure"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        )
                        TextButton(onClick = onRefreshSessions) { Text("重试") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerSessionRow(
    summary: SessionSummary,
    subtitle: String?,
    onClick: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = summary.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    subtitle,
                    TimeText.relative(summary.updatedAt, now).ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (summary.running) {
            Surface(
                color = Color(0xFF4ADE80),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(6.dp),
            ) {}
        }
    }
}
