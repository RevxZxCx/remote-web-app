package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.common.CacheNotice
import com.revxzxcx.remotewebapp.ui.common.ConnectionBanner
import com.revxzxcx.remotewebapp.ui.common.GeneratingGlyph
import com.revxzxcx.remotewebapp.ui.common.GlyphSize
import com.revxzxcx.remotewebapp.ui.common.OverflowItem
import com.revxzxcx.remotewebapp.ui.common.OverflowMenuIconButton
import com.revxzxcx.remotewebapp.ui.common.OverflowMenuDivider
import com.revxzxcx.remotewebapp.ui.common.rememberPressScale
import com.revxzxcx.remotewebapp.ui.common.rememberPressTint
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.Motion
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape
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
    onOpenDebug: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val carrier by viewModel.carrier.collectAsState()
    val actionBusy by viewModel.actionBusy.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow()

    // 「退出」= 擦掉本机凭证。M4 验收时误触过一次（当时「诊断」与「退出」在顶栏
    // 只差 131 px），结果得回桌面端重新发起配对才能恢复——配对只能桌面端发起，
    // App 自己无能为力。R017 把四个文字按钮收成一个溢出菜单之后，破坏性动作靠
    // **四层**防护：① 不与常用项相邻；② 分隔线隔开；③ error 色；④ 二次确认框。
    // ④ 现在由 AppRoot 统一渲染（R018 P1-1），本屏只负责调 onUnpair。
    //
    // rememberSaveable（R018 P3-4）：旋屏时菜单收起只是要重开一次，
    // 但若确认框的可见性挂在 remember 上，旋屏会让它静默消失 = 防护被绕过。
    var menuOpen by rememberSaveable { mutableStateOf(false) }

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
                    busySessionIds = actionBusy,
                    onArchive = viewModel::archiveSession,
                    onUnarchive = viewModel::unarchiveSession,
                    onPin = viewModel::pinSession,
                    onUnpin = viewModel::unpinSession,
                    // 复用 HomeScreen 那一个时钟：抽屉自己再起一个定时器是多余的
                    // （R006 P3-3），而且两边的 `now` 还不完全同步。
                    now = now,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            AppIconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                contentDescription = "会话列表",
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = null)
                            }
                        },
                        title = {
                            // 两行：App 名 + 副标题。原来顶栏横排的「已连接 5 · 会话 58」
                            // 与「刷新/诊断/设置/退出」四个文字按钮都收进来——顶栏从
                            // 4 个文字 + 1 个文字导航挤成 2 个图标 + 1 个菜单。
                            Column {
                                Text(
                                    "远程 Web",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = when {
                                    state.loading -> "连接中…"
                                    state.error != null -> state.error.orEmpty()
                                    state.fromCache -> "缓存内容 · 工作区 ${state.snapshot?.items?.size ?: 0}"
                                    else -> "已连接 · 工作区 ${state.snapshot?.items?.size ?: 0}"
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            // R017 ①：actions **只准一个**图标按钮。刷新曾是一个独立的
                            // Refresh 图标钮，与 MoreVert 并排——那样顶栏右端有两个图标，
                            // 既违反规范也让「刷新」离「退出配对」只差几十像素（误触风险）。
                            // 现在刷新收进溢出菜单，放在第一项（最高频）。
                            OverflowMenuIconButton(
                                expanded = menuOpen,
                                onExpandedChange = { menuOpen = it },
                                contentDescription = "更多",
                                icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                            ) {
                                OverflowItem(
                                    text = "刷新",
                                    leadingIcon = {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    },
                                ) { viewModel.retry() }
                                if (onOpenDebug != null) {
                                    OverflowItem(
                                        text = "诊断",
                                        leadingIcon = {
                                            Icon(Icons.Default.Info, contentDescription = null)
                                        },
                                    ) { onOpenDebug() }
                                }
                                if (onOpenSettings != null) {
                                    OverflowItem(
                                        text = "设置",
                                        leadingIcon = {
                                            Icon(Icons.Default.Settings, contentDescription = null)
                                        },
                                    ) { onOpenSettings() }
                                }
                                // 分隔线 + error 色：破坏性动作不与常用项相邻，
                                // 也不会和「刷新」只差几十像素。
                                OverflowMenuDivider()
                                OverflowItem(
                                    text = "退出配对",
                                    leadingIcon = {
                                        Icon(Icons.Default.ExitToApp, contentDescription = null)
                                    },
                                    destructive = true,
                                ) { onUnpair() }
                            }
                        },
                    )
                    ConnectionBanner(
                        carrier = carrier,
                        // 「重新配对」= 退出配对 + 重新配对，是破坏性操作（会擦凭证）。
                        // AppRoot 层的 onUnpair 现在只置位 pendingUnpair，确认框由
                        // AppRoot 统一渲染（R018 P1-1）。
                        onRepairRequest = onUnpair,
                        onRetry = viewModel::retry,
                    )

                    if (state.fromCache) {
                        CacheNotice("正在同步：当前显示的是上次的缓存内容。")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                state.error?.let { error ->
                    // 配色跟随主题（早先是硬编码深红底 + 亮红字，浅色主题下与整个界面不协调）。
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (state.needsRepair) "配对已失效：$error" else "连接失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "会话列表读取失败：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::refreshSessions) { Text("重试") }
                        }
                    }
                }

                // 管理动作（归档 / 置顶）失败必须可见：这四类动作成功后 UI 靠
                // `workspace/follow` 的 `archived` / `pinned` 增量帧自动更新，
                // 失败时什么都不会变——不挂横幅的话用户只以为「点了没反应」。
                actionError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "操作失败：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::dismissActionError) { Text("知道了") }
                        }
                    }
                }

                when {
                    // 只有「还没有任何可显示的内容」时才整屏转圈。`warmStart` 刻意不复位
                    // `loading`（缓存不是权威数据），若这里只看 `loading`，缓存铺好之后屏幕
                    // 上仍然是转圈——FR-4.3「先出缓存再对齐」等于没实现，而顶部的
                    // 「当前显示的是上次的缓存内容」横幅还在，画面与文案自相矛盾
                    //（R006 P1-2；与 `ChatScreen` 的同类条件保持同形）。
                    state.loading && state.snapshot.isEmpty && state.groups.isEmpty() -> Box(
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

                    // 只有「真的拿到了权威数据」才说没有工作区。`fromCache` 为真时列表来自
                    // 上一次的缓存，空缓存不能断言「桌面端没有工作区」。
                    state.snapshot.isEmpty && state.groups.isEmpty() && !state.fromCache -> Box(
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
                                busySessionIds = actionBusy,
                                onArchive = viewModel::archiveSession,
                                onUnarchive = viewModel::unarchiveSession,
                                onPin = viewModel::pinSession,
                                onUnpin = viewModel::unpinSession,
                            )
                        }
                    }
                }
            }
        }
    }
    // 退出配对的确认框已上移到 AppRoot 层统一渲染（R018 P1-1）：本屏的「退出」、
    // 错误横幅与 ConnectionBanner 的「重新配对」走的是同一个 onUnpair，
    // 而 AppRoot 层的框是三屏共用的唯一一处。这里只留 onUnpair 的直接调用。
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
    busySessionIds: Set<String> = emptySet(),
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
) {
    val workspace = group.workspace
    val scale = rememberPressScale()
    // 实底 + 1dp 描边替代半透明底色（R017）：半透明色叠在滚动内容上会把
    // 「这是卡片」和「这是背景」混在一起，而描边在深浅两套主题下都稳定。
    val rowShape = RoundedCornerShape(16.dp)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (expanded) 0.5f else 0.22f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, rowShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧圆角方块放方向箭头：34dp 方块比 34dp 圆更像「可展开的容器」，
            // 展开时箭头旋转 90°，用 Motion 的曲线而不是硬切。
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (expanded) 0.22f else 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(Motion.FAST, easing = Motion.Emphasized),
                    label = "workspaceChevron",
                )
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = workspace.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (group.hasRunning) {
                        Spacer(Modifier.width(8.dp))
                        // R017：呼吸圆点换成均衡器（总原则 ②）。这是原本的第五处手写
                        // 呼吸动画，现在全 App 只剩 `GeneratingGlyph` 一个实现。
                        GeneratingGlyph(
                            size = GlyphSize.SM,
                            tint = LocalStatusRunning.current,
                        )
                    }
                }
                Text(
                    text = workspace.path,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 计数改成胶囊而不是一行纯文字（R017）：「3 · 1 归档」比
                // 「3 个会话 · 1 个已归档」短一半，且多个胶囊并列时天然对齐。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatPill("${group.activeCount}", "会话")
                    if (group.archivedCount > 0) StatPill("${group.archivedCount}", "归档")
                    if (group.missingIds.isNotEmpty()) {
                        StatPill("${group.missingIds.size}", "读不到", warn = true)
                    }
                }
            }
        }

                // 展开动画（R017）：早先是 `if (expanded)` 硬切，行高突变很生硬。
                // 容器形变属于 [Motion] 的「慢」档，颜色/位移才用快档。
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(Motion.SLOW, easing = Motion.Emphasized)) +
                        fadeIn(Motion.fade()),
                    exit = shrinkVertically(animationSpec = tween(Motion.SLOW, easing = Motion.Emphasized)) +
                        fadeOut(Motion.fade(Motion.FAST)),
                ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val pinned = group.pinnedRows
                val archived = group.rows.filter { it.isArchived }
                val recent = group.rows.filter { it.isArchived.not() && it.isPinned.not() }

                if (pinned.isNotEmpty()) {
                    SectionLabel("置顶")
                    pinned.forEach {
                        SessionRowItem(
                            row = it,
                            onOpenSession = onOpenSession,
                            now = now,
                            busy = it.summary.sessionId in busySessionIds,
                            onArchive = onArchive,
                            onUnarchive = onUnarchive,
                            onPin = onPin,
                            onUnpin = onUnpin,
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    if (pinned.isNotEmpty()) SectionLabel("会话")
                    recent.forEach {
                        SessionRowItem(
                            row = it,
                            onOpenSession = onOpenSession,
                            now = now,
                            busy = it.summary.sessionId in busySessionIds,
                            onArchive = onArchive,
                            onUnarchive = onUnarchive,
                            onPin = onPin,
                            onUnpin = onUnpin,
                        )
                    }
                }
                if (archived.isNotEmpty()) {
                    SectionLabel("已归档 ${archived.size}")
                    archived.forEach {
                        SessionRowItem(
                            row = it,
                            onOpenSession = onOpenSession,
                            now = now,
                            busy = it.summary.sessionId in busySessionIds,
                            onArchive = onArchive,
                            onUnarchive = onUnarchive,
                            onPin = onPin,
                            onUnpin = onUnpin,
                        )
                    }
                }
                if (group.rows.isEmpty()) {
                    Text(
                        text = "这个工作区还没有会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 46.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** 计数胶囊：大数字 + 小单位，一眼看清数量关系（R017）。 */
@Composable
private fun StatPill(value: String, unit: String, warn: Boolean = false) {
    val bg = if (warn) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    }
    val fg = if (warn) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
        Text(text = unit, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 46.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** 会话列表里的一行：标题 + 时间 + 状态点。长按出管理菜单（归档 / 置顶）。 */
@Composable
private fun SessionRowItem(
    row: SessionGrouping.SessionRow,
    onOpenSession: (SessionSummary) -> Unit,
    now: Long = System.currentTimeMillis(),
    busy: Boolean = false,
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
) {
    val summary = row.summary
    // `rememberSaveable` 而不是 `remember`：旋屏会让 `remember` 复位、菜单弹到一半消失
    // （R012 P3-2）。与全工程其它 menuOpen（`HomeScreen.kt:385`）保持一致。
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val scale = rememberPressScale()
    val pressTint = rememberPressTint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, end = 12.dp, top = 3.dp, bottom = 3.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(bubbleShape)
            .background(pressTint)
            .combinedClickable(
                onClick = { onOpenSession(summary) },
                onLongClick = { menuOpen = true },
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2dp 左侧轨道：会话是工作区的子节点，用小色条替代原来的半透明底色
        // （R017），视觉重量从「又一个卡片」降为「卡片里的一行」。
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 2.dp, height = 28.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(
                    if (row.isPinned) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                ),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = TimeText.relative(summary.updatedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
            }
        }
        if (summary.running) {
            GeneratingGlyph(size = GlyphSize.SM)
        } else if (row.isArchived) {
            Text(
                text = "归档",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
        }
        SessionActionsMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            archived = row.isArchived,
            pinned = row.isPinned,
            busy = busy,
            onArchive = { onArchive(summary.sessionId) },
            onUnarchive = { onUnarchive(summary.sessionId) },
            onPin = { onPin(summary.sessionId) },
            onUnpin = { onUnpin(summary.sessionId) },
        )
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
/**
 * 会话抽屉。
 *
 * 可见性是 `internal` 而不是 `private`：这个组装的 `LazyColumn` key 唯一性是一个
 * **反复逃逸**的真实缺陷（R003：同一会话同时出现在两个工作区的 `sessionIds` 里时
 * 抛 `IllegalArgumentException: Key … was already used`），而它只能靠渲染层测试
 * 才抓得到——502 项纯逻辑单测全部漏掉了它。测试源码集与 main 同模块，
 * `internal` 正好够用，不必把实现细节公开成 `public`。
 */
internal fun SessionDrawer(
    state: HomeUiState,
    onOpenSession: (SessionSummary, Workspace?) -> Unit,
    onRefreshSessions: () -> Unit,
    busySessionIds: Set<String> = emptySet(),
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
    // 由调用方传入（R006 P3-3）：这里自己也调一次 `rememberNow()` 会起第二个定时器，
    // 而 HomeScreen 的那个每 30 秒跳一次就足以让整个抽屉重组。一个时钟够了。
    now: Long,
) {
    // 派生列表现在是 HomeUiState 的构造属性（不再是 get() 派生），在 LazyColumn 的
    // content lambda 里读也一定是当前值。这里取成局部变量只是少写几次 state.。
    val pinned = state.pinned
    val recent = state.recent
    val groups = state.groups
    val archivedIds = state.snapshot.archivedSessionIds
    val pinnedIds = state.snapshot.pinnedSessionIds

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
                        busy = pair.first.sessionId in busySessionIds,
                        archivedIds = archivedIds,
                        pinnedIds = pinnedIds,
                        onArchive = onArchive,
                        onUnarchive = onUnarchive,
                        onPin = onPin,
                        onUnpin = onUnpin,
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
                        busy = summary.sessionId in busySessionIds,
                        archivedIds = archivedIds,
                        pinnedIds = pinnedIds,
                        onArchive = onArchive,
                        onUnarchive = onUnarchive,
                        onPin = onPin,
                        onUnpin = onUnpin,
                    )
                }
            }

            item(key = "hdr-ws") { DrawerHeader("按工作区") }
            groups.forEach { group ->
                // key 里**不能带下标**（R006 P3-2）：工作区顺序由 `order` 帧决定，插入/重排会
                // 让所有下标位移，于是全部 key 变化、LazyColumn 无法复用项，滚动位置直接丢失。
                // workspaceId 已是稳定唯一标识。
                item(key = "ws-${group.workspace.workspaceId}") {
                    Text(
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
                        busy = row.summary.sessionId in busySessionIds,
                        archivedIds = archivedIds,
                        pinnedIds = pinnedIds,
                        onArchive = onArchive,
                        onUnarchive = onUnarchive,
                        onPin = onPin,
                        onUnpin = onUnpin,
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
    busy: Boolean = false,
    archivedIds: Set<String> = emptySet(),
    pinnedIds: Set<String> = emptySet(),
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
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
            // R017：静态绿点换成均衡器。这是最后一处「运行中」指示——原先三处
            // （折叠分组 / 会话行 / 连接横幅）是呼吸动画，会话行这里反而是个静止点，
            // 同一语义三种视觉，用户根本记不住哪个是哪个。
            //
            // 顺带修掉硬编码 `Color(0xFF4ADE80)`：R017 的 `statusRunning` token 让
            // 它在浅色主题下也能保持 ≥3:1 对比度（原绿在白底上会发灰）。
            GeneratingGlyph(size = GlyphSize.SM, tint = LocalStatusRunning.current)
        }
        // 抽屉里没有独立的「已归档」段（归档会话只在工作区展开段出现），所以
        // archived / pinned 从 `state.snapshot` 推导而不是写死——写死会在主区与抽屉
        // 行为分叉，而这条分叉没有测试盯着。
        SessionActionsMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            archived = summary.sessionId in archivedIds,
            pinned = summary.sessionId in pinnedIds,
            busy = busy,
            onArchive = { onArchive(summary.sessionId) },
            onUnarchive = { onUnarchive(summary.sessionId) },
            onPin = { onPin(summary.sessionId) },
            onUnpin = { onUnpin(summary.sessionId) },
        )
    }
}
