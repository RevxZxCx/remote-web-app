package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.session.SessionGrouping
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.common.AppMark
import com.revxzxcx.remotewebapp.ui.common.CacheNotice
import com.revxzxcx.remotewebapp.ui.common.ConnectionIndicator
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

    // 抽屉的开合由 `drawerState` 自己持有，不再镜像到 ViewModel —— 早先那个 `drawerOpen`
    // 字段没有任何回写点，恒为 false，只会留下错误的心智模型（R003 P3-2）。
    //
    // 系统返回键（R028 ②）：**必须**把 `drawerState` 传给 `ModalDrawerSheet`。material3 1.4.0
    // 只在接收 `DrawerState` 的那个重载里注册预测性返回回调；不带参数的旧重载会给内部
    // 参数传 null 并被短路，于是抽屉打开时**零回调注册**，返回落到系统默认 = finish Activity。
    // 行号与结论都经反编译核实（详见 docs/dev 的 R028 记录），不要改回无参重载。

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
            HomeDrawerSheet(drawerState = drawerState) {
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
                                    state.fromCache -> {
                                        // R028 ①：副标题不再重复胶囊里的「已连接」，
                                        // 改成两个只有这里有的事实（工作区数、会话数）。
                                        val workspaces = state.snapshot?.items?.size ?: 0
                                        "缓存内容 · 工作区 $workspaces · 会话 ${state.sessions.visible.size}"
                                    }

                                    else -> {
                                        val workspaces = state.snapshot?.items?.size ?: 0
                                        "工作区 $workspaces · 会话 ${state.sessions.visible.size}"
                                    }
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
                            // R028 ①：连接状态收进顶栏，放在「更多」**左边**。
                            // 原来顶栏下面永远压着一条整宽状态条（ConnectionBanner），
                            // 不管健不健康都占一整行；现在三级信息密度：
                            // 圆点（不打扰）→ 胶囊（想知道）→ 面板（要排查）。
                            //
                            // 两个 `collectAsState` 刻意写在 `actions` 这个 lambda **里面**
                            // （R028 审查 P2-3）：`connectionDetails` 跟着每一帧收发更新，
                            // 若在 `HomeScreen` 函数体顶层读取，Compose 会把失效范围记在
                            // 整个 HomeScreen 的重组作用域上——每一帧都重建顶栏与列表，
                            // 恰好抵消了把 `carrier` 与 `connectionDetails` 拆开省下的开销。
                            // 放进 lambda 后高频失效只波及顶栏右侧这一格。
                            val carrier by viewModel.carrier.collectAsState()
                            val connectionDetails by viewModel.connectionDetails.collectAsState()
                            ConnectionIndicator(
                                carrier = carrier,
                                details = connectionDetails,
                                // 「重新配对」= 退出配对 + 重新配对，是破坏性操作（会擦凭证）。
                                // AppRoot 层的 onUnpair 只置位 pendingUnpair，确认框由
                                // AppRoot 统一渲染（R018 P1-1）——面板里不能另开一条不确认的路。
                                onRepairRequest = onUnpair,
                                onRetry = viewModel::retry,
                                onOpenDebug = onOpenDebug,
                            )
                            // R017 ①：actions **只准一个**图标按钮。刷新曾是一个独立的
                            // Refresh 图标钮，与 MoreVert 并排——那样顶栏右端有两个图标，
                            // 既违反规范也让「刷新」离「退出配对」只差几十像素（误触风险）。
                            // 现在刷新收进溢出菜单，放在第一项（最高频）。
                            // （连接指示器不是「图标按钮」，它自带状态语义，R028 明确要求
                            // 它常驻顶栏。）
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

                    // R028 ①：整宽状态条已收进顶栏，只剩缓存提示（它与连接状态可以同时为真，
                    // 合成一条会丢掉其中一半信息，所以保留独立一条）。
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
                            archivedInWorkspace = group.archivedCount,
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
                            archivedInWorkspace = group.archivedCount,
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
                            archivedInWorkspace = group.archivedCount,
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
    /** 同 [DrawerSessionRow]：只给「移入归档」确认框的上下文卡用。 */
    archivedInWorkspace: Int? = null,
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
) {
    val summary = row.summary
    // `rememberSaveable` 而不是 `remember`：旋屏会让 `remember` 复位、菜单弹到一半消失
    // （R012 P3-2）。与全工程其它 menuOpen（`HomeScreen.kt:385`）保持一致。
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    // ⚠️ 三个 hook 必须共用同一个 `interactionSource`：`rememberPressScale()` /
    // `rememberPressTint()` 在不传参时各自 `remember { MutableInteractionSource() }`，
    // 而 `combinedClickable` 不传参时 Compose 内部再新建一个——三个源互不相干，
    // `scale` 恒 1f、`pressTint` 恒透明，按下去毫无反馈（R018 P2-2，R035 补修主区）。
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource = interactionSource)
    val pressTint = rememberPressTint(interactionSource = interactionSource)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, end = 12.dp, top = 3.dp, bottom = 3.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(bubbleShape)
            .background(pressTint)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
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
            // 主区的长按菜单与抽屉走同一个组件，上下文头也要一致（R033 ②）。
            title = summary.displayName,
            subtitle = TimeText.relative(summary.updatedAt, now),
            archivedInWorkspace = archivedInWorkspace,
        )
    }
}

/**
 * 抽屉侧板。**存在的唯一理由是让 R028 ② 的返回键修复可以被单测钉住。**
 *
 * 为什么必须抽出来：material3 1.4.0 只在不带/带 `DrawerState` 的**两个** `ModalDrawerSheet`
 * 重载里注册预测性返回回调——不带参数的那个给内部 `drawerPredictiveBackState` 传
 * `aconst_null`，随后在 `DrawerSheet-cm3T3N0` 里被 `ifnull` 短路，于是抽屉打开时
 * **平台侧零回调注册**，系统返回落到默认行为 = `finish()` Activity（进程存活）。
 *
 * 也就是说，这一整条链上「抽屉返回键是否可用」**只由一个实参决定**：`drawerState`
 * 传没传。这个错误用眼过一遍代码是发现不了的（`drawerState` 就在同一个函数里、
 * 到处都在用），设备上复现也只是「按返回退出应用」——和「回首页」看起来一样像
 * 正常导航。所以把它抽成一个签名上就要求 `drawerState` 的 `internal` 组合函数，
 * 让 `HomeDrawerSheetTest` 能在 Robolectric 下断言「抽屉打开时返回关闭抽屉而不是
 * finish Activity」。删掉这个参数会让那条测试失败，而不是等到用户来报。
 */
@Composable
internal fun HomeDrawerSheet(
    drawerState: DrawerState,
    content: @Composable () -> Unit,
) {
    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier
            .fillMaxHeight()
            .width(DrawerPanelWidth)
            .shadow(elevation = 12.dp, shape = DrawerPanelShape, clip = false)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = DrawerPanelShape,
            ),
        drawerShape = DrawerPanelShape,
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 0.dp,
    ) { content() }
}

/**
 * 抽屉浮起面板的宽度（R033 ①）。
 *
 * 默认 `ModalDrawerSheet` 铺满 `NavigationDrawerTokens.ContainerWidth = 360dp`，在本机
 * （360dp 宽）等于**整屏**：没有右边界、没有圆角，用户看到的是一块和页面糊在一起的面，
 * 而不是「浮在页面上的一层」。318dp 留出 42dp 让底下的页面与遮罩透出来，层级才成立。
 *
 * ⚠️ 318 是设计值不是「自适应」：`ModalDrawerSheet` 内部会再套一层
 * `sizeIn(min 240dp, max 360dp)`，所以比 240 小的值会被 system 抬回 240，
 * 比 360 大的值会被压回 360。真正要改宽度只能改这里。
 */
private val DrawerPanelWidth = 318.dp

/** 抽屉面板圆角：只圆右侧两角，左侧贴边（它是从左边滑出来的）。 */
private val DrawerPanelShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

/** 抽屉里每一行会话的圆角（R033 ①）：10dp，和卡片的其他圆角同一档。 */
private val DrawerRowShape = RoundedCornerShape(10.dp)

/**
 * 左侧会话抽屉：置顶 / 最近 / 按工作区分组，外加一个「已归档」聚合视图。
 *
 * 为什么要有抽屉而不是只靠工作区展开：实测有 5 个工作区、47 条会话，
 * 用户「最近在哪个会话里说过话」的记忆通常强于「它在哪个工作区」，
 * 所以「最近」这一段是主入口（原型 `#home` 的会话抽屉）。
 *
 * 可见性是 `internal` 而不是 `private`：这个组装的 `LazyColumn` key 唯一性是一个
 * **反复逃逸**的真实缺陷（R003：同一会话同时出现在两个工作区的 `sessionIds` 里时
 * 抛 `IllegalArgumentException: Key … was already used`），而它只能靠渲染层测试
 * 才抓得到——502 项纯逻辑单测全部漏掉了它。测试源码集与 main 同模块，
 * `internal` 正好够用，不必把实现细节公开成 `public`。
 */
@Composable
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

    // 归档聚合视图（R033 ①）：抽屉底部「已归档 N」点进去看全局归档会话。
    // 用 rememberSaveable 是为了转屏/进程重建后仍停在用户打开的那一屏。
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    // ⚠️ `distinctBy` 不能省：`session/list` 的解析只做 `mapNotNull`，原样保留重复条目
    // （`SessionListParser.summaries` / `SessionListResult.items` 都不去重；工程里
    // `SessionGroupingTest` 的「recentSessions 对 sessionList 里的重复条目只保留一条」
    // 说明这个数据形态是真实存在的）。不去的后果不是「多一行」，而是
    // `items(key = "archived-${sessionId}")` 撞 key 抛
    // `IllegalArgumentException: Key "archived-X" was already used`——LazyColumn 直接崩，
    // 整个抽屉打不开。同一份数据在 `recentSessions`（`distinctBy`）、`group()`
    // （`sessionIds.distinct()`）、`pinnedSessions`（`associateBy`）三处都防过，
    // 漏掉的只有 R033 新增的这条链。
    // `filterNot { it.blank }`（R038）：归档聚合视图与其它三段同一口径，空会话不列。
    val archivedSessions = remember(archivedIds, state.sessions.items) {
        state.sessions.items
            .filter { it.sessionId in archivedIds }
            .filterNot { it.blank }
            .distinctBy { it.sessionId }
            .sortedByDescending { it.updatedAt }
    }

    // 每条会话所属工作区里已归档多少条（给「移入归档」确认框的上下文卡用）。
    // 从 `groups` 直接读：那是唯一已经算好的地方，再自己 filter 一遍容易两处口径分叉。
    // 同一会话可能出现在两个工作区，取先出现的那个——确认框只是要说个量级，
    // 精确到「哪个工作区」这件事行副标题已经交代了。
    val archivedCountBySession = remember(groups) {
        buildMap {
            groups.forEach { group ->
                group.rows.forEach { row -> putIfAbsent(row.sessionId, group.archivedCount) }
            }
        }
    }

    // 搜索（R036）：输入框在抽屉头部，过滤在 [searchSessions] 里做——纯本地、零延迟。
    // query 用 rememberSaveable：转屏后不该把已经敲进去的词清掉。
    var query by rememberSaveable { mutableStateOf("") }
    // key 里带上 snapshot 与 items：workspace/follow 与 session/list 是两条独立通道，
    // 任一先到都要让结果跟着更新（否则刚到的会话在搜索里搜不到）。
    val searchHits = remember(query, state.snapshot, state.sessions.items) {
        searchSessions(
            snapshot = state.snapshot,
            sessions = state.sessions,
            query = query,
        )
    }
    // 搜索一开就退出归档视图：两个视图共用同一个 LazyColumn 与 listState，
    // 「在归档视图里搜索」会让「返回会话列表」与搜索结果同屏出现，语义含糊。
    val searching = query.isNotBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHead(
            state = state,
            workspaceCount = groups.size,
            // 与抽屉里实际能看到的行同源：`items` 含子代理会话（`topLevel` 才过滤），
            // 也不去重，直接计数会出现「胶囊说 51、列表里最多 47 行」的对不上账。
            // 抽屉的三段（最近 / 置顶 / 按工作区）都以 `snapshot` 为骨架，所以骨架为空
            // 时这里也必须是 0——否则「刚装好、会话列表已到、工作区快照未到」的窗口里
            // 会显示「128 会话」而下面一行都没有、也没有任何入口。
            sessionCount = if (state.snapshot.isEmpty) {
                0
            } else {
                state.sessions.topLevel.distinctBy { it.sessionId }.size
            },
            pinnedCount = pinnedIds.size,
            // ⚠️ 必须用 `archivedSessions.size` 而不是 `archivedIds.size`：胶囊与底部入口
            // 是同一个「已归档」概念的两个出口，若一个读快照 id 集合、一个读会话列表过滤结果，
            // 在 `session/list` 与 `workspace/follow` 两条通道先后到达的窗口里会互相矛盾
            // （头部「归档 2」而底部根本没有入口，聚合视图也不可达）。
            archivedCount = archivedSessions.size,
            onRefreshSessions = onRefreshSessions,
            query = query,
            onQueryChange = { query = it },
        )
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

        // 切换「常规三段 ↔ 归档聚合视图」时必须回到顶部（R034 P1-1）。
        //
        // 同一个 `listState` 在两个分支间复用，而「已归档 N」入口**固定在常规列表末尾**
        // ——用户点它时 `firstVisibleItemIndex` 已经在几十条之后；切到归档视图后 item
        // 总数骤减到个位数，LazyColumn 只能把 index 钳到末项，于是放在顶部的
        // 「返回会话列表」落在视口之外。而 `archivedOpen = true` 时底部的「已归档 N」
        // 入口又不再渲染，屏幕上没有任何回去的提示 —— 这正是上面注释承诺要避免的死胡同。
        //
        // 两个方向都回顶：返回时同样会经历「item 数量骤增 + index 钳位」，把用户丢在
        // 一个他上次没看过的位置比回顶更难解释。
        LaunchedEffect(archivedOpen) { listState.requestScrollToItem(0) }

        // 搜索时同样回顶：结果是全新的集合，沿用上一次的 index 会让用户停在一个
        // 与本次输入无关的位置上（而且结果往往只有几条，index 会被钳到末项）。
        LaunchedEffect(searching) { listState.requestScrollToItem(0) }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (searching) {
                // 搜索结果（R036）：跨工作区的平铺列表，命中项直接可点开。
                //
                // 搜索结果里**不**排除已归档会话：用户搜的是「我记得有这么个会话」，
                // 它现在被归档了恰恰是更要紧的信息，把它藏起来等于搜不到。
                // 归档态由行内的状态条 + 读屏的 stateDescription 表达。
                item(key = "hdr-search") {
                    DrawerSectionHeader(
                        text = if (searchHits.isEmpty()) "没有匹配" else "搜索结果",
                        count = searchHits.size,
                    )
                }
                if (searchHits.isEmpty()) {
                    item(key = "search-empty") {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "没有会话名、工作区或模型匹配「$query」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            )
                            // 说清楚能搜什么：全文搜索在这个部署里是关着的（服务端
                            // `openAt: never`），不说的话用户会以为「搜不到消息正文」
                            // 是 App 的 bug。
                            Text(
                                text = "这里只匹配会话标题、工作区名与模型名，不搜消息正文。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
                items(searchHits, key = { "hit-${it.summary.sessionId}" }) { hit ->
                    DrawerSessionRow(
                        summary = hit.summary,
                        subtitle = hit.workspace?.displayName,
                        onClick = { onOpenSession(hit.summary, hit.workspace) },
                        now = now,
                        busy = hit.summary.sessionId in busySessionIds,
                        archivedInWorkspace = archivedCountBySession[hit.summary.sessionId],
                        archivedIds = archivedIds,
                        pinnedIds = pinnedIds,
                        onArchive = onArchive,
                        onUnarchive = onUnarchive,
                        onPin = onPin,
                        onUnpin = onUnpin,
                    )
                }
            } else if (archivedOpen) {
                // 归档聚合视图（R033 ①）：取代上面的常规三段，只显示归档会话。
                // 归档会话在主区是「工作区展开段」里的一个子段，抽屉里此前完全没有入口——
                // 用户归档完就再也找不到它，只能去桌面端取消归档。
                //
                // ⚠️ 返回入口必须放在**顶部**：归档会话可能几十条，把「返回」放在列表末尾
                // 等于要求用户先滚到底才出得去；而 `archivedOpen` 是 `rememberSaveable`，
                // 关掉抽屉再打开仍停在这一屏，没有返回入口就是一个死胡同。
                item(key = "archived-back") {
                    TextButton(
                        onClick = { archivedOpen = false },
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    ) {
                        Text("返回会话列表")
                    }
                }
                item(key = "hdr-archived") {
                    DrawerSectionHeader(text = "已归档", count = archivedSessions.size)
                }
                if (archivedSessions.isEmpty()) {
                    item(key = "archived-empty") {
                        Text(
                            text = "还没有归档的会话。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                items(archivedSessions, key = { "archived-${it.sessionId}" }) { summary ->
                    DrawerSessionRow(
                        summary = summary,
                        subtitle = state.snapshot.workspaceOf(summary.sessionId)?.displayName,
                        onClick = {
                            onOpenSession(summary, state.snapshot.workspaceOf(summary.sessionId))
                        },
                        now = now,
                        busy = summary.sessionId in busySessionIds,
                        archivedInWorkspace = archivedCountBySession[summary.sessionId],
                        archivedIds = archivedIds,
                        pinnedIds = pinnedIds,
                        onArchive = onArchive,
                        onUnarchive = onUnarchive,
                        onPin = onPin,
                        onUnpin = onUnpin,
                    )
                }
            } else {
                if (pinned.isNotEmpty()) {
                    item(key = "hdr-pinned") { DrawerSectionHeader(text = "置顶", count = pinned.size) }
                    items(pinned, key = { "pin-${it.first.sessionId}" }) { pair ->
                        DrawerSessionRow(
                            summary = pair.first,
                            subtitle = pair.second?.displayName,
                            onClick = { onOpenSession(pair.first, pair.second) },
                            now = now,
                            busy = pair.first.sessionId in busySessionIds,
                            archivedInWorkspace = archivedCountBySession[pair.first.sessionId],
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
                    item(key = "hdr-recent") { DrawerSectionHeader(text = "最近", count = recent.size) }
                    items(recent, key = { "recent-${it.sessionId}" }) { summary ->
                        DrawerSessionRow(
                            summary = summary,
                            subtitle = state.snapshot.workspaceOf(summary.sessionId)?.displayName,
                            onClick = {
                                onOpenSession(summary, state.snapshot.workspaceOf(summary.sessionId))
                            },
                            now = now,
                            busy = summary.sessionId in busySessionIds,
                            archivedInWorkspace = archivedCountBySession[summary.sessionId],
                            archivedIds = archivedIds,
                            pinnedIds = pinnedIds,
                            onArchive = onArchive,
                            onUnarchive = onUnarchive,
                            onPin = onPin,
                            onUnpin = onUnpin,
                        )
                    }
                }

                item(key = "hdr-ws") { DrawerSectionHeader(text = "按工作区", count = groups.size) }
                groups.forEach { group ->
                    // key 里**不能带下标**（R006 P3-2）：工作区顺序由 `order` 帧决定，插入/重排会
                    // 让所有下标位移，于是全部 key 变化、LazyColumn 无法复用项，滚动位置直接丢失。
                    // workspaceId 已是稳定唯一标识。
                    item(key = "ws-${group.workspace.workspaceId}") {
                        DrawerWorkspaceHeader(
                            name = group.workspace.displayName,
                            activeCount = group.activeCount,
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
                            archivedInWorkspace = group.archivedCount,
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

                // 归档入口固定在列表末尾（R033 ①）。只有真的有归档会话时才出现——
                // 0 条的入口点进去是空列表，纯粹是噪音。
                if (archivedSessions.isNotEmpty()) {
                    item(key = "archived-entry") {
                        Box(Modifier.padding(top = 10.dp, bottom = 10.dp)) {
                            DrawerArchivedEntry(
                                count = archivedSessions.size,
                                onToggle = { archivedOpen = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抽屉头部（R033 ①）。
 *
 * 改造前这里是一个 `Surface` 铺满宽度、里面两行纯文字：「会话」+「共 N 条 · 顶层 M ·
 * 已归档 K」。三个问题：
 *  1. 三个数字塞在一行 11sp 的灰字里，扫视时读不出重点；
 *  2. 没有任何「这是哪个应用」的标识，抽屉浮起来之后和主区不像同一个产品；
 *  3. 唯一的动作是长按行——没有刷新入口（主区要下拉，抽屉里没手势）。
 *
 * 现在：30dp 应用图标 + 标题 + 一行状态副标题 + 刷新按钮；下面三枚统计胶囊把会话 /
 * 置顶 / 归档拆成可扫视的三块；再下面一行 34dp 的搜索框。
 *
 * 搜索是**本地过滤**，不调 `session/search`：那个端点的 descriptor 确实存在
 * （`request {query}` → `{items:[{sessionId,snippet}],hasMore}`），但这个部署的会话全文
 * 索引 `openAt` 是 `never`，实测每次调用都返回
 * `gateway/internal: session search failed: SessionQueryError: session search is disabled`。
 * 桌面端 Web 侧边栏在同样配置下也只是匹配标题与工作区名，所以本地过滤与桌面端行为一致，
 * 而且离线（缓存数据）可用。[searchSessions] 里有完整取证。
 *
 * ⚠️ 没有「新建会话」按钮：`DshEndpoints.SESSION_CREATE` 只有常量、没有实现，wire
 * 形状从未实测（决策 D88）。
 * 图标由 [com.revxzxcx.remotewebapp.ui.common.AppMark] 自绘（理由见它的 KDoc）。
 */
@Composable
private fun DrawerHead(
    state: HomeUiState,
    workspaceCount: Int,
    sessionCount: Int,
    pinnedCount: Int,
    archivedCount: Int,
    onRefreshSessions: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    // 副标题是**状态陈述**，不能一律写「已连接」（R033 审查 P2-1）：
                    // 解绑（`needsRepair`）与缓存（`fromCache`）时那句「已连接」是假的，
                    // 而抽屉里没有别的地方能纠正它——`ConnectionBanner` 只挂在主区。
                    text = when {
                        state.sessionsLoading -> "正在读取…"
                        state.needsRepair -> "设备已解绑 · 需要重新配对"
                        state.sessionsError != null -> "读取失败 · 可点右侧重试"
                        state.fromCache -> "$workspaceCount 个工作区 · 缓存数据"
                        else -> "$workspaceCount 个工作区 · 已连接"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            AppIconButton(onClick = onRefreshSessions, contentDescription = "刷新会话") {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DrawerStatPill(value = sessionCount, unit = "会话")
            DrawerStatPill(value = pinnedCount, unit = "置顶", accent = MaterialTheme.colorScheme.primary)
            DrawerStatPill(value = archivedCount, unit = "归档", accent = MaterialTheme.colorScheme.tertiary)
        }

        DrawerSearchField(query = query, onQueryChange = onQueryChange)
    }
}

/**
 * 抽屉里的搜索框（R036）。
 *
 * **只搜本地已有的会话摘要**：匹配会话标题、工作区名、模型名，不碰消息正文。
 * 原因见 [searchSessions] 的 KDoc —— 这个部署的会话全文索引是关着的
 * （`openAt: never`），`session/search` 必然报 `session search is disabled`；
 * 桌面端 Web 侧边栏的搜索同样只匹配标题与工作区名。
 *
 * 用一个真的 `BasicTextField` 而不是「长得像输入框的占位行」：R033 时它是
 * `.semantics { disabled() }` 的假框，标签写「暂未开放」——用户点它没反应，
 * 只会归因成 App 卡了。现在能输入、能清空、有焦点态。
 *
 * 右侧的清除按钮只在有输入时出现：空框上放一个 × 是纯噪音。
 */
@Composable
private fun DrawerSearchField(query: String, onQueryChange: (String) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        query.isNotEmpty() -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, borderColor, pillShape)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        if (query.isNotEmpty()) {
            AppIconButton(onClick = { onQueryChange("") }, contentDescription = "清除搜索") {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 抽屉统计胶囊。与主区的 [StatPill] 同形（`pillShape` + 半透明 `surfaceVariant`），
 * 但数字用等宽字重强调、单位后置，并且可以带一个强调色（置顶蓝 / 归档黄）——
 * 主区那三枚是「工作区属性」，这里是「全局计数」，两者不该长得一模一样。
 */
@Composable
private fun DrawerStatPill(value: Int, unit: String, accent: Color? = null) {
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = accent?.copy(alpha = 0.3f) ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = pillShape,
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = (accent ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f),
        )
    }
}

/**
 * 抽屉分组标题（R033 ①）。
 *
 * 改造前只有一行 11sp 灰字（"置顶" / "最近" / "按工作区"），三段的视觉权重完全一样，
 * 而且没有条数——用户看不出「最近」里到底有 3 条还是 30 条。现在：字距拉开的小标题
 * + 计数 + 右侧一条渐隐的细线；细线用 `Box` 的横向渐变画，把标题和内容分开，
 * 但不引入第二条实线（实线会和上下的 `HorizontalDivider` 打架）。
 */
@Composable
private fun DrawerSectionHeader(text: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            // 对比度：11sp 小字在浅色主题（background #F4F7FB / onBackground #182430）下
            // 要满足 AA 4.5:1。实测 0.45 折算后只有 ≈2.7:1、计数 0.3 只有 ≈1.8:1，
            // 强光下基本看不见。0.7 / 0.55 是同一观感下能达标的下限。
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/** 抽屉里的工作区小标题（R033 ①）：工作区名 + 活跃会话数。 */
@Composable
private fun DrawerWorkspaceHeader(name: String, activeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = activeCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
        )
    }
}

/**
 * 抽屉里的一行会话（R033 ①）。
 *
 * 改造前：一个 `Row` 加 `padding(horizontal = 16.dp, vertical = 9.dp)`，两行文字上下堆叠，
 * 行与行之间没有任何形状或分隔，整段看起来像一块连续的文本块；右侧只有运行中的均衡器，
 * 长按是唯一能唤出菜单的方式（没有任何视觉提示说明可长按）。
 *
 * 现在：
 *  - 10dp 圆角行 + 左右 8dp 外边距，行与行之间有呼吸缝，扫视时每行是独立目标；
 *  - 左侧 3dp 状态条：置顶蓝 / 归档黄 / 普通灰（原来只有置顶有 2dp 条，且在主区）；
 *  - 右侧常驻 `⋮` 按钮（`Icon` 16dp 字形，触达沿用 `IconButton` 默认 48dp），长按仍然保留
 *    ——两条路径都能出菜单；
 *  - 归档行标题降到 45% 不透明度，从视觉上「退到后面去」。
 *
 * ⚠️ `rememberPressScale` / `rememberPressTint` 必须和 `clickable` 共用同一个
 * `interactionSource`，否则缩放恒为 1f、着色恒为透明（R018 P2-2 踩过）。
 */
@Composable
private fun DrawerSessionRow(
    summary: SessionSummary,
    subtitle: String?,
    onClick: () -> Unit,
    now: Long = System.currentTimeMillis(),
    busy: Boolean = false,
    /**
     * 这条会话所属工作区里有多少条已归档。
     *
     * 只给「移入归档」的二次确认框用：它要在上下文卡里写「工作区 · 时间 · 已归档 N 条」，
     * 让用户在按下确认前就知道这一行的归档不是孤立事件（旁边已经躺了几条）。
     * 拿不到归属（工作区快照还没到）时传 null，卡片会自动少这一段而不显示「已归档 0 条」。
     */
    archivedInWorkspace: Int? = null,
    archivedIds: Set<String> = emptySet(),
    pinnedIds: Set<String> = emptySet(),
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onPin: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource = interactionSource)
    val pressTint = rememberPressTint(interactionSource = interactionSource)
    val archived = summary.sessionId in archivedIds
    val pinned = summary.sessionId in pinnedIds
    val barColor = when {
        pinned -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        archived -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(DrawerRowShape)
            .background(pressTint)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
                onLongClick = { menuOpen = true },
            )
            // 置顶 / 归档原本只由「3dp 竖条颜色 + 标题透明度」表达：读屏用户听到的是
            // 一模一样的「标题 + 时间 + 会话操作」，色弱用户在 3dp 窄条上分不清
            // #5AA2FF 与 #F2C14E。`stateDescription` 把状态变成可朗读的文字。
            .semantics {
                if (pinned) stateDescription = "已置顶" else if (archived) stateDescription = "已归档"
            }
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧状态条：3dp 宽、贴着行高的 22dp，圆角 2dp。
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
                // 纯装饰：状态已经由上面的 `stateDescription` 表达，这里再挂语义会被
                // 读屏重复念一遍。
                .clearAndSetSemantics {},
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = summary.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (pinned) FontWeight.Medium else FontWeight.Normal,
                // 归档行标题 0.5 → 0.62：11~13sp 正文的 AA 下限，同时仍能看出「退后」。
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (archived) 0.62f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    subtitle,
                    TimeText.relative(summary.updatedAt, now).ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                // 副标题 0.45 → 0.65（同上，11sp 小字的 AA 下限）。
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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
            Box(Modifier.padding(start = 6.dp)) {
                GeneratingGlyph(size = GlyphSize.SM, tint = LocalStatusRunning.current)
            }
        }
        // 常驻的 ⋮：抽屉里此前只能长按唤出菜单，用户根本发现不了。
        //
        // contentDescription 必须带上会话名：这枚按钮逐行重复，写成常量会让读屏用户听到
        // 一串无法区分的「会话操作」，也会让后续 `onNodeWithContentDescription("会话操作")`
        // 直接 `Expected exactly '1' node but found 'N'`。
        AppIconButton(
            onClick = { menuOpen = true },
            contentDescription = "会话操作：${summary.displayName}",
            enabled = true,
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (archived) 0.3f else 0.5f),
            )
        }
        // archived / pinned 一律从 `state.snapshot` 的两个全局 id 集合推导，不由调用点
        // 写死——写死会让「最近段」「工作区段」「已归档聚合视图」三处的菜单行为分叉，
        // 而这条分叉没有测试盯着。
        SessionActionsMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            archived = archived,
            pinned = pinned,
            busy = busy,
            onArchive = { onArchive(summary.sessionId) },
            onUnarchive = { onUnarchive(summary.sessionId) },
            onPin = { onPin(summary.sessionId) },
            onUnpin = { onUnpin(summary.sessionId) },
            title = summary.displayName,
            subtitle = listOfNotNull(
                subtitle,
                TimeText.relative(summary.updatedAt, now).ifBlank { null },
            ).joinToString(" · "),
            archivedInWorkspace = archivedInWorkspace,
        )
    }
}
