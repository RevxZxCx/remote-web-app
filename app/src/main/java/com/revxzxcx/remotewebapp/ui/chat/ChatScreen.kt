package com.revxzxcx.remotewebapp.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import com.revxzxcx.remotewebapp.core.protocol.isHealthy
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.AskQuestion
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.ui.theme.Motion
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape
import com.revxzxcx.remotewebapp.ui.theme.bubbleTailShape
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape
import com.revxzxcx.remotewebapp.core.session.ImageMeasure
import com.revxzxcx.remotewebapp.core.session.InteractiveComposerRequest
import com.revxzxcx.remotewebapp.core.session.LiveAttempt
import com.revxzxcx.remotewebapp.core.session.ModelCatalog
import com.revxzxcx.remotewebapp.core.session.ModelRef
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.core.session.ToolFilePaths
import com.revxzxcx.remotewebapp.core.session.fromBase64
import com.revxzxcx.remotewebapp.core.session.message
import com.revxzxcx.remotewebapp.core.session.visibleAssistantBlocks
import com.revxzxcx.remotewebapp.ui.common.CacheNotice
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.common.ConnectionBanner
import com.revxzxcx.remotewebapp.ui.common.GeneratingGlyph
import com.revxzxcx.remotewebapp.ImeInsets
import com.revxzxcx.remotewebapp.ui.common.GlyphSize
import com.revxzxcx.remotewebapp.ui.common.IconSizes
import com.revxzxcx.remotewebapp.ui.common.decodeScaled
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.home.TimeText
import com.revxzxcx.remotewebapp.ui.home.rememberNow
import com.revxzxcx.remotewebapp.ui.markdown.MdBlocks
import com.revxzxcx.remotewebapp.ui.markdown.MdStream
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.TextStyle
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning

/**
 * 聊天页。
 *
 * M3.5 起正文按 Markdown 渲染（[MdBlocks]，自绘，决策 D12），代码块用轻量高亮库
 * （决策 D13）；工具调用与工具结果折叠成卡片，改动文件卡片可点开文件查看页。
 *
 * 自动滚动策略：只有用户已经贴在底部时才跟随新内容，否则不打断他向上翻阅历史
 * （与桌面端一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenFile: (FileTarget) -> Unit,
    onUnpair: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val carrier by viewModel.carrier.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val listState = rememberLazyListState()
    val now = rememberNow()

    // 用户在滑动对话列表时收起输入框（用户 m45428 第 5 条）。计数器每滑一次 +1，
    // 由 [Composer] 的 `collapseSignal` 消费。
    //
    // 🔴 判据必须是**真实的用户拖动**，不能用 `listState.isScrollInProgress`
    // （R029 审查 P2-2，第一版就是这么写的，是错的）。那个量不只由手指驱动：
    // [ChatScrollFollow] 在贴底时会**随每个流式 token** 调 `scrollToBottom(...)`，
    // 而它内部是 `scrollToItem` + `scroll(MutatePriority.Default)`，两者都会把
    // `isScrollInProgress` 置真。于是「模型正在输出 + 列表贴底」这个生成中的默认状态里，
    // 收缩信号会随 token 不断自增，`Composer` 的 `LaunchedEffect(collapseSignal)`
    // 反复重启并调窗口级 `focusManager.clearFocus()`——用户想「生成中先排队打一句话」，
    // 键盘会被下一个 token 顶掉。
    //
    // 也不能用 `scrollOffset`：它在每次布局变化（新消息到达）时也会变，同样是误判。
    //
    // `interactionSource` 的 `DragInteraction` 只由**指针输入**产生，程序化滚动不发它，
    // 这才是「用户手指在拖」的定义。`isScrollInProgress` 与它的差别正好是「谁发起的滚动」。
    var scrollCollapseSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { scrollCollapseSignal++ }
    }

    // ── R031：目标 / 任务 / 排队 三块的界面状态 ──────────────────────────────
    //
    // 全部是 `remember`（**不是** saveable）：用户 m45632 明确要求「状态不做记忆，
    // 重新打开对话依旧为展开状态」。用 saveable 会在进程重建后恢复，与要求相反。
    //
    // 目标横幅默认展开 → `goalCollapsed = false`。
    var goalCollapsed by remember { mutableStateOf(false) }
    var goalEditing by remember { mutableStateOf(false) }
    var showDeleteGoal by remember { mutableStateOf(false) }

    // 任务胶囊与排队列表**互斥展开**（用户 m45632）：同一时刻最多一个展开，
    // 另一个自动收起。理由是两个都展开时总高可达 548dp，必然把消息区压没。
    // 用一个枚举而不是两个布尔，从类型上就不可能同时为真。
    var expandedDock by remember { mutableStateOf(DockTarget.None) }

    // 输入法是否可见（用户 m45632）：键盘占掉约 40% 高度，此时目标横幅、任务胶囊、
    // 排队条**全部让位**，输入框直接贴键盘；键盘收起后原样恢复。
    //
    // 🔴 判据必须用 [ImeInsets]（框架层桥进来的原生 ime 高度），**不能用
    // `WindowInsets.ime.getBottom()`**：`enableEdgeToEdge()` 之后那个值在本工程的
    // 真机上恒为 0（见 [ImeInsets] 的 KDoc，AppRoot 早就为同一件事改过一次），于是
    // 「键盘弹出时让位」整条契约会静默失效。R031 设备验收在 MuMu 上就是这样抓到的：
    // `dumpsys input_method` 报 `mInputShown=true`，横幅与胶囊却照旧显示。
    //
    // 判据仍然不是焦点：焦点在但键盘没弹（外接键盘）时不该隐藏。
    val imeBottomPx by ImeInsets.bottomPx.collectAsState()
    val imeVisible = imeBottomPx > 0

    // 排队区是否正在行内编辑。由 [QueuedStrip] 上报（编辑态在它内部，那里才知道点的是哪条）。
    //
    // 它是「输入法让位」的**唯一例外**（用户 m45632）：用户点开排队消息的行内输入框，
    // 键盘必然升起；此时把排队条整块移出组合树，输入框连同焦点一起消失、键盘立刻收回，
    // 编辑根本进行不下去（R031 独立审查 P1）。目标横幅的行内编辑同理，见下面 `goalEditing`。
    var queueEditing by remember { mutableStateOf(false) }

    // 键盘升起时任务面板 / 排队列表一律**回到收起态**（用户 m45632；R031 独立审查 P3）。
    //
    // 不重置的话，键盘只是把它们移出组合树，收起键盘后 `expandedDock` 还是原来的值，
    // 面板会「记得」自己展开着又弹回来——用户看到的是「我没点它却展开了」。
    // 目标横幅不在此列：它保留用户自己的收起/展开选择（设计稿第 1326 行）。
    LaunchedEffect(imeVisible) {
        if (imeVisible) expandedDock = DockTarget.None
    }

    // 模型选择器的显示开关。目录本身由 ViewModel 拉（[ChatUiState.catalog]），
    // 这里只负责「弹不弹」——目录没到时对话框显示为空而不是崩。
    var showModelPicker by rememberSaveable { mutableStateOf(false) }

    // 审批权限面板（用户 m28009）。请求本身由应用级的 `$events` 订阅推送，
    // ViewModel 只按 sessionId 取子集；这里是「弹不弹」。
    var showApprovals by rememberSaveable { mutableStateOf(false) }
    val approvals by viewModel.approvals.collectAsState()
    val approvalError by viewModel.approvalError.collectAsState()
    val interactive by viewModel.interactive.collectAsState()
    val questionError by viewModel.questionError.collectAsState()

    // 附件提示由 ViewModel 承载（R007 P2-3）：上传整条链跑在 viewModelScope 里，
    // 旋转屏幕既不取消上传也不丢提示。早先是这里的 `remember`，旋转就被重置。
    val attachNotice by viewModel.attachNotice.collectAsState()
    val pending by viewModel.pending.collectAsState()

    // 桌面端「标准模式」的折叠分组（用户 m24940）。一整段过程（工具调用 + 工具结果 +
    // 只含思考的助手消息）折成一行摘要，用户消息与含正文的助手消息仍然单独成行。
    // 行模型是 scrollbar / 滚动跟随 / 历史自动加载三处共用的下标基准，所以必须由
    // [foldChatItems] 单一真相源产出，不能在各自的位置上重算。
    //
    // ⚠️ 必须是 State（`by derivedStateOf`），不能是普通 `remember` val。下面三个 lambda
    // （[ChatScrollFollow] 的 lastIndex / [ChatScrollbar] 的 itemKeys / userItemIndices）
    // 都被 `LaunchedEffect(listState)` 与 `pointerInput(Unit)` 这类**永不重启**的 effect
    // 持有，协程里留的是首次组合创建的那个 lambda。普通 val 会被**按值**捕获 → `rows`
    // 冻在首帧（开会话时 items 通常为空 → 0）→ `scrollToBottom(0)` 把列表跳到顶部、
    // 流式永久不跟随（m21177-2.3 复发）；scrollbar 的 prefixes 与 resolve() 同样拿到
    // 首帧 rows → 标记逐个消失、滑块与点击定位整体偏移。
    // 这正是 ChatScrollFollow.kt 与 ChatScrollbar.kt 两份 KDoc 明文警告过的「冻住」，
    // R015 自己又踩了一次（审查报告 P1-1/P1-2）。
    val rows by remember { derivedStateOf { foldChatItems(state.items) } }

    // 「贴底跟随」的完整逻辑（含 R010m 修掉的首帧 latch、T7 的 scrollToBottom）在
    // [ChatScrollFollow]，抽成独立 Composable 是为了能被 Robolectric 渲染测试直接驱动——
    // 这段逻辑的缺陷只在真实渲染时现形，纯逻辑单测一条也抓不到。
    // ⚠️ 两个 lambda 都必须在**调用时**读 `state`，绝不能捕获组合时算好的 `lastIndex`。
    // `LaunchedEffect(listState)` 的 key 在整个会话里不变 → effect 不重启 → 协程里持有的
    // 是**第一次组合**创建的那个 lambda。捕获 `val lastIndex`（一个 Int）等于把下标冻在
    // 首次组合那一刻：流式过程中 collect 每次触发都拿着旧下标去滚，视觉上就是
    // 「生成时不自动往下滚」（用户 m21177 第 2.3 条反馈的根因）。
    // 读 `state` 则相反——`by collectAsState()` 的委托在 lambda 里每次调用都取当前值。
    // 抽成局部 val 而不是内联两遍：[ChatScrollFollow] 与「滚到底部」浮动按钮
    // （用户 m24150）必须按同一个下标滚，两处各写一遍算式必然漂移。
    val lastIndex: () -> Int = {
        rows.size +
            (if (state.loadingMore) 1 else 0) +
            (if (state.live != null) 1 else 0) - 1
    }
    ChatScrollFollow(
        listState = listState,
        lastIndex = lastIndex,
        liveLength = { state.live?.text?.length ?: 0 },
    )

    // 「滚到底部」浮动按钮的显隐（用户 m24150）。判据与 [ChatScrollFollow] 的贴底判定
    // 共用 [isNearBottom]，否则会出现「明明贴在底部却冒出一个到底部按钮」。
    // `derivedStateOf` 在这里是对的（与 ChatScrollFollow 里不能用它相反）：这里是组合期读，
    // 而且滚动过程中每个 token 都重组一次这个 Composable 并不亏——它本来就要重画。
    val showScrollToBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            !state.loading &&
                info.totalItemsCount > info.visibleItemsInfo.size &&
                !listState.isNearBottom()
        }
    }
    val scope = rememberCoroutineScope()

    // 滚到顶时自动加载更早历史。逻辑在 [ChatHistoryFollow]（抽出来是为了能被渲染测试
    // 直接驱动：`firstVisibleItemIndex` 与「列表是否溢出一屏」都是只有真实布局后才有的量）。
    ChatHistoryFollow(
        listState = listState,
        hasMore = { viewModel.state.value.hasMore },
        onLoadMore = { viewModel.loadMore() },
    )

    // 删除目标的二次确认（用户 m45632 第 1 条明确要求）。只在用户点了删除按钮后出现。
    if (showDeleteGoal) {
        DeleteGoalDialog(
            onConfirm = {
                showDeleteGoal = false
                state.goal?.let { viewModel.clearGoal(it) }
            },
            onDismiss = { showDeleteGoal = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title?.takeIf { it.isNotBlank() } ?: title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.cwd?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                // R017：「返回」与「中止」都从文字按钮改成图标按钮。顶栏原本在生成时
                // 会多出一个文字「中止」，把标题挤成半行；现在两个位置都是固定图标，
                // 标题宽度不再随生成状态跳动。
                navigationIcon = {
                    AppIconButton(onClick = onBack, contentDescription = "返回") {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // ── R031：目标横幅收起后，顶栏出现一枚状态色圆点作为回展开入口
                    // （用户 m45632）。键盘可见时不显示，避免与顶部冲突。
                    val goal = state.goal
                    if (goal != null && goalCollapsed && dockVisible(imeVisible, editing = goalEditing)) {
                        GoalChip(goal = goal, onClick = { goalCollapsed = false })
                    }
                    if (state.generating) {
                        AppIconButton(
                            onClick = viewModel::cancel,
                            contentDescription = "中止生成",
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // 排队队列（用户 m21177 第 4 条反馈）：位置在模型条与输入区之间，
                // 即「发送框上方」。每条一个气泡，带 编辑 / 删除 / 插队 三个操作。
                //
                // 有提问/审批时整块让位（用户 m29539）：输入区都变成回答窗口了，再显示
                // 「待发送」会让人以为还能继续塞消息。已有排队消息由服务端保管，回答完
                // 请求后这里自然回来。
                // ── R031 的底部堆叠顺序（自下而上）：
                //    输入框 → 排队条 → 任务胶囊 → 消息区
                // 排队条贴输入框（用户 m21177 定的「发送框上方」），任务胶囊描述的是
                // 更外层的宏观进度，所以让位到排队条之上。
                //
                // 输入法可见时三者全部隐藏（用户 m45632）：键盘已经占掉 40% 高度，
                // 再挂这两块会和顶部目标打架、也把消息区挤没。
                // 排队区正在行内编辑时也不能隐藏（同上）：此时键盘必然升起，
                // 把排队条移出组合树会让那个输入框连同焦点一起消失。
                if (interactive == null && dockVisible(imeVisible, editing = queueEditing)) {
                    // 任务胶囊 + 展开的任务列表（在排队条**之上**）
                    if (state.todos.visible) {
                        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                            TaskPill(
                                todos = state.todos,
                                expanded = expandedDock == DockTarget.Tasks,
                                onToggle = {
                                    expandedDock = if (expandedDock == DockTarget.Tasks) {
                                        DockTarget.None
                                    } else {
                                        // 互斥：展开任务会收起排队列表（枚举只有一个值，
                                        // 赋值即互斥，不需要显式去关另一个）。
                                        DockTarget.Tasks
                                    }
                                },
                            )
                            AnimatedVisibility(
                                visible = expandedDock == DockTarget.Tasks,
                                enter = fadeIn(tween(Motion.MEDIUM)) +
                                    expandVertically(tween(Motion.MEDIUM)),
                                exit = fadeOut(tween(Motion.FAST)) +
                                    shrinkVertically(tween(Motion.FAST)),
                            ) {
                                Box(Modifier.padding(top = 6.dp)) {
                                    TaskPanel(todos = state.todos)
                                }
                            }
                        }
                    }
                    QueuedStrip(
                        messages = state.queued,
                        generating = state.generating,
                        now = now,
                        expanded = expandedDock == DockTarget.Queue,
                        onToggleExpanded = {
                            expandedDock = if (expandedDock == DockTarget.Queue) {
                                DockTarget.None
                            } else {
                                DockTarget.Queue
                            }
                        },
                        onEdit = viewModel::editQueued,
                        onRemove = viewModel::removeQueued,
                        onSteer = viewModel::steerQueued,
                        onEditingChanged = { queueEditing = it },
                    )
                }
                // 有待回答的请求时，**输入框本身变成审批/提问窗口**（用户 m29539，
                // 与 DSH 桌面端一致：卡片暂时占用编辑器位置）。所以：
                //   · 这里没有横幅，也没有「上面挂着卡片、下面还能打字」的状态——
                //     那个状态下用户会把新消息发出去，而服务端还在等他回答；
                //   · 排队条同样让位：有提问/审批时不能再往队列里塞消息。
                // Composer 内部按 [interactive] 分派，三种形态共用同一个 Surface。
                Composer(
                    draft = draft,
                    generating = state.generating,
                    collapseSignal = scrollCollapseSignal,
                    sending = state.sending,
                    pending = pending,
                    attachNotice = attachNotice,
                    interactive = interactive,
                    // 两条错误通道合并展示，但必须带来源前缀——否则同时失败时用户
                    // 分不清是「提问没送出去」还是「审批没送出去」，重试的点也不同。
                    interactiveError = when {
                        questionError != null && approvalError != null ->
                            "提问失败：$questionError；审批失败：$approvalError"
                        questionError != null -> "提问失败：$questionError"
                        approvalError != null -> "审批失败：$approvalError"
                        else -> null
                    },
                    onAnswerApproval = { eventId, allow ->
                        viewModel.answerApproval(eventId, allow)
                    },
                    onToggleQuestionOption = { callId, question, label ->
                        viewModel.toggleQuestionOption(callId, question, label)
                    },
                    onQuestionCustom = { callId, question, value ->
                        viewModel.setQuestionCustom(callId, question, value)
                    },
                    onSubmitQuestions = viewModel::submitQuestionGroup,
                    onSkipQuestions = viewModel::skipQuestionGroup,
                    permissionChip = {
                        PermissionChip(
                            permission = state.permission,
                            pendingCount = approvals.size,
                            onClick = { showApprovals = true },
                        )
                    },
                    modelChip = {
                        ModelBar(
                            model = state.model,
                            loading = state.catalogLoading,
                            error = state.modelError,
                            onOpen = {
                                viewModel.openModelPicker()
                                showModelPicker = true
                            },
                        )
                    },
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onAttachImage = { bytes, mediaType, name, measure ->
                        // 返回值仍保留给调用方判断「加没加上」，提示已由 ViewModel 落到
                        // attachNotice 上（R007 P2-3）。
                        viewModel.attachImage(bytes, mediaType, name, measure)?.message()
                    },
                    onAttachFile = { bytes, name ->
                        // 上传在 viewModelScope 里跑（R007 P2-3）：旋转屏幕不取消。
                        // 结果经 attachNotice 回来。
                        viewModel.attachFileInBackground(bytes, name)
                    },
                    onAttachNotice = { viewModel.setAttachNotice(it) },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 全局状态条（FR-4.4）。与工作区页读的是同一个载波状态，所以两处永远一致。
            // 只在非健康时显示：正常聊天时它占的那一行高度没有价值，异常时才是关键信息。
            if (!carrier.isHealthy) {
                ConnectionBanner(
                    carrier = carrier,
                    // 「重新配对」= 退出配对 + 重新配对，是破坏性操作（会擦凭证）。
                    // 确认框由 AppRoot 统一渲染（R018 P1-1）。
                    onRepairRequest = onUnpair,
                    onRetry = viewModel::retry,
                )
            }

            if (state.fromCache) {
                CacheNotice("正在同步：当前显示的是上次缓存的会话内容。")
            }

            // 三类错误各自带自己的重试动作（R006 P3-6）：共用一个字段时，「发送失败」的
            // 错误条上那个「重试」也去重启整条 follow 流——比失败本身重得多，还不会重新
            // 发送那条消息。用户点了按钮发现什么都没发生。
            //
            // 三条横幅同形（R016 抽成 [ChatErrorBanner]：早先是三段逐字复制的代码，
            // 改一处漏两处只是时间问题），配色跟随主题的 errorContainer 角色，
            // 进出带竖向展开动画——没有动画时横幅是硬切出来的，列表内容被猛地顶一下。
            ChatErrorBanner(text = state.error, actionLabel = "重试", onAction = viewModel::retry)
            ChatErrorBanner(
                text = state.sendError,
                actionLabel = "重试",
                onAction = viewModel::retrySend,
            )
            // 目标操作的失败**不能**复用上面那条（R031 独立审查 P2）：那条的「重试」绑的是
            // `retrySend()`，它只清错误再调 `send()`——草稿为空时点了什么都不会发生，
            // 草稿非空时会把一条无关消息发出去。这里只提示，关掉就完事（重点按钮即重试）。
            ChatErrorBanner(
                text = state.goalError,
                actionLabel = "知道了",
                onAction = viewModel::dismissGoalError,
            )
            ChatErrorBanner(
                text = state.pageError,
                actionLabel = "重试",
                onAction = viewModel::retryLoadMore,
            )

            // ── R031 目标横幅（用户 m45580 第 2 条）：会话级元信息，挂在消息区上方。
            //
            // 键盘可见时隐藏（用户 m45632）：与底部两块一起让位，否则顶部横幅 + 键盘
            // 会把消息区压到只剩一两行。
            // 行内编辑时**不能**隐藏（否则输入框随横幅一起销毁）：这是「输入法让位」的
            // 唯一例外（用户 m45632 明确要求「正在行内编辑的那一行不隐藏」）。
            if (dockVisible(imeVisible, editing = goalEditing)) {
                state.goal?.let { goal ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        GoalBanner(
                            goal = goal,
                            collapsed = goalCollapsed,
                            editing = goalEditing,
                            onToggleCollapsed = { goalCollapsed = true },
                            onPauseResume = {
                                // 目标与任务独立（见 Goal 的 KDoc）：这里只发 pause/resume，
                                // 不动 todos、也不中止当前轮。
                                viewModel.setGoalPhase(
                                    pause = goal.canPause,
                                    goal = goal,
                                )
                            },
                            onRequestEdit = { goalEditing = true },
                            onEdit = { objective ->
                                viewModel.editGoal(goal, objective)
                                goalEditing = false
                            },
                            onCancelEdit = { goalEditing = false },
                            onDelete = { showDeleteGoal = true },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.items.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    // `queued.isEmpty()` 必须一起判：全新会话里第一句话发出去时 items 还是空的、
                    // live 也还没到（服务端要先出队才开轮），此刻若走进这个空态分支，
                    // 刚发出去的那条回声就被「这个会话还没有消息」盖掉了——正是 m20380 的缺口。
                    state.ready && state.items.isEmpty() && state.live == null &&
                        state.queued.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "这个会话还没有消息。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.loadingMore) {
                            item(key = CHAT_LOADING_MORE_KEY) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                            // 新消息淡入（R016）。用 LazyItemScope.animateItem 而不是自己套
                            // AnimatedVisibility：后者是**每个 item 各自**的进出动画，会在
                            // loadMore 往前插历史时让整页老消息一起重播一遍淡入——用户看到
                            // 的是「整页闪一下」。animateItem 只对新出现的 key 播放一次，
                            // 已存在的 key 换位置时走 placement 动画，两者分工正好是我们要的。
                            val enter = Modifier.animateItem(
                                fadeInSpec = tween(Motion.MEDIUM, easing = Motion.Emphasized),
                                placementSpec = tween(Motion.MEDIUM, easing = Motion.Emphasized),
                                fadeOutSpec = tween(Motion.FAST, easing = Motion.Emphasized),
                            )
                            when (row) {
                                is ChatRow.Single -> ChatItemRow(
                                    item = row.item,
                                    now = now,
                                    changes = state.changes[row.item.seq],
                                    changesLoading = row.item.seq in state.changesLoading,
                                    onOpenFile = onOpenFile,
                                    onRetryChanges = viewModel::retryChanges,
                                    attachments = viewModel.attachments,
                                    sessionId = viewModel.sessionId,
                                    modifier = enter,
                                )

                                is ChatRow.Group -> GroupRow(
                                    members = row.members,
                                    running = index == rows.lastIndex && state.generating,
                                    now = now,
                                    changes = state.changes,
                                    changesLoading = state.changesLoading,
                                    onOpenFile = onOpenFile,
                                    onRetryChanges = viewModel::retryChanges,
                                    attachments = viewModel.attachments,
                                    sessionId = viewModel.sessionId,
                                    modifier = enter,
                                )
                            }
                        }
                        state.live?.let { live ->
                            item(key = CHAT_LIVE_KEY) { LiveRow(live) }
                        }
                        // 排队气泡**不在这里**：用户 m21177 第 4 条反馈明确要求「发送框上方多一个
                        // 气泡」，而消息列表会随内容增长把气泡推到看不见的地方（R010m 就是活例：
                        // 列表停在半路时，排在列表尾的回声在视口外，看起来像「回声消失了」）。
                        // 现在由 bottomBar 里的 [QueuedStrip] 渲染。
                    }
                }

                // 位置滚动条（用户 m24150）。行 key 的顺序必须与上面 LazyColumn 逐行一致，
                // 否则标记会整体偏移几行——所以 loading-more / live 两个 key 也走这里的常量。
                ChatScrollbar(
                    listState = listState,
                    itemKeys = {
                        buildList {
                            if (state.loadingMore) add(CHAT_LOADING_MORE_KEY)
                            rows.forEach { add(it.key) }
                            if (state.live != null) add(CHAT_LIVE_KEY)
                        }
                    },
                    userItemIndices = {
                        val base = if (state.loadingMore) 1 else 0
                        rows.mapIndexedNotNull { index, row ->
                            if (row is ChatRow.Single && row.item is ChatItem.User) {
                                base + index
                            } else {
                                null
                            }
                        }
                    },
                    // 会话长度系数。不乘它时每个会话刚打开都恰好量到 50 行
                    // （DEFAULT_MAX_MESSAGES），滑块大小在所有会话里是同一个数。
                    // cursor 是服务端权威的日志尾部 seq，已加载窗口覆盖
                    // [items.first().seq, cursor]，两者之比就是已加载部分占整个会话的比例。
                    sessionLengthScale = {
                        val items = state.items
                        val cursor = state.cursor
                        if (items.isEmpty() || cursor < 0L || !state.hasMore) {
                            1f
                        } else {
                            val oldest = items.first().seq
                            val span = (cursor - oldest + 1L).coerceAtLeast(1L)
                            ((cursor + 1L).toDouble() / span.toDouble()).toFloat()
                        }
                    },
                    onScrollToItem = { index -> listState.scrollToItem(index) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(18.dp)
                        .padding(vertical = 6.dp),
                )

                // 「滚到底部」浮动按钮：只在列表没贴在底部时出现，位置在滚动条下方
                // （用户 m24150 把两者作为一个需求一起提的）。
                ScrollToBottomFab(
                    visible = showScrollToBottom,
                    onClick = { scope.launch { listState.scrollToBottom(lastIndex()) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }

    // 模型选择器。放在 Scaffold 外面：AlertDialog 需要覆盖在整个页面之上，
    // 放 bottomBar/topBar 里会被裁掉（Compose 的对话框是独立窗口）。
    state.catalog?.let { catalog ->
        if (showModelPicker) {
            ModelPickerDialog(
                catalog = catalog,
                current = state.model,
                onSelect = { provider, model, effort ->
                    viewModel.selectModel(provider, model, effort)
                    showModelPicker = false
                },
                onDismiss = { showModelPicker = false },
            )
        }
    }

    // 审批权限面板（用户 m28009）。与模型选择器同一个位置：都是「从输入区配置行点进来
    // 的详情」，同一个窗口层级用户不需要学两次。
    //
    // R021（用户 m29539）起待处理的审批请求不再列在这里——它们已经占住输入框了。
    // 这个面板只剩「当前策略是什么 + 为什么手机端改不了」，因为用户点进来第一件事
    // 就是想改策略，不说明白他就会到处找开关（D53：不做假开关）。
    if (showApprovals) {
        PermissionSheet(
            permission = state.permission,
            pendingCount = approvals.size,
            error = approvalError,
            onDismissRequest = { showApprovals = false },
        )
    }
}

/**
 * 输入区里的模型胶囊（需求 FR-5.5 模型切换的入口）。
 *
 * R019（用户 m27990）起它不再单独占一行，而是收进 [Composer] 的展开态配置行，与
 * [PermissionChip] 并排。所以外层从「整行 surfaceVariant 容器」改成裸 Row：
 * 容器在配置行里会把「＋ ⚠️ 模型」整行糊成一块，而且 `fillMaxWidth` 会把右侧空间吃光。
 *
 * 拉取失败时这里同时也是错误展示位（[error]）：目录拉不到不该弹对话框打断用户，
 * 但也不能让他以为「这个 App 没有模型切换功能」。
 */
@Composable
internal fun ModelBar(
    model: ModelRef?,
    loading: Boolean,
    error: String?,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // R017：从 TextButton 改成胶囊徽标。TextButton 自带 48dp 最小触控高度和
        // 内边距，在这条 ~30dp 的窄行里会把输入区整体撑高；胶囊显式给定 32dp，
        // 并且「实底 + 1dp 描边」的形态与工作区统计徽标、排队条操作钮同源。
        //
        // leading 6dp 圆点用 primary：这是当前唯一在用户打字前需要被看到的
        // 「发送上下文」，但它是静态的（不参与 GeneratingGlyph 的运行中语义）。
        Surface(
            onClick = onOpen,
            enabled = !loading,
            shape = pillShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.height(32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (model != null) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = when {
                        loading -> "正在读取模型列表…"
                        model != null -> "${model.provider}/${model.model}"
                        else -> "桌面端默认模型"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model?.reasoningEffort != null) {
                    Spacer(Modifier.width(6.dp))
                    // 推理档做成更小的一枚内嵌徽标而不是拼进同一串文字：
                    // `provider/model` 与 `high` 是两个不同维度的信息，混在一行
                    // 等宽字体里用户得自己断开读。
                    Surface(
                        shape = pillShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = model.reasoningEffort,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 模型选择对话框。
 *
 * 拆成独立的 `internal` Composable 而不是内联在 [ChatScreen] 里，是为了能用 Robolectric
 * 渲染测试直接驱动它（M5-5）：目录解析（[ModelCatalog]）与「点一行该发出什么参数」
 * （含推理档的默认值规则）是两个容易错且值得钉死的点。
 *
 * 交互是**点完即生效**：点模型行立刻回调 [onSelect] 并关闭。没有「确定」按钮——
 * 多一步确认对「只是想看看有哪些模型」的用户是负担，而选错的代价只是再点一次。
 */
@Composable
internal fun ModelPickerDialog(
    catalog: ModelCatalog,
    current: ModelRef?,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (catalog.groups.isEmpty()) {
                    Text(
                        text = "桌面端没有返回可选的模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                catalog.groups.forEach { group ->
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    group.models.forEach { model ->
                        val selected = current?.provider == group.id && current.model == model.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(
                                        group.id,
                                        model.id,
                                        effortOf(model, current, selected),
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onSelect(
                                        group.id,
                                        model.id,
                                        effortOf(model, current, selected),
                                    )
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = model.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                model.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                        // 推理档只在「选中的那个模型」下面展开：每个模型都列一排档位会让
                        // 对话框变成一堵墙，而档位只有在已选该模型时才有意义。
                        if (selected && model.efforts.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 48.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val active = current?.reasoningEffort
                                    ?: model.defaultEffort
                                model.efforts.forEach { effort ->
                                    TextButton(
                                        onClick = { onSelect(group.id, model.id, effort.id) },
                                    ) {
                                        Text(
                                            text = effort.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (effort.id == active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 拉不到模型的 provider 也告诉用户：静默少几个选项会让人以为目录不全。
                if (catalog.failures.isNotEmpty()) {
                    Text(
                        text = "部分模型源不可用：" +
                            catalog.failures.joinToString("、") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 点一个模型时该带哪个推理档。
 *
 * 规则（按优先级）：
 * 1. 已经选中同一个模型且带档 → 沿用当前档（用户就是来换档的）；
 * 2. 服务端给了 `reasoning.defaultEffort` → 用它（最不容易出意外的选择）；
 * 3. 否则 null —— **不发送 `reasoningEffort` 键**。模型没有档位时传任何字符串都会让
 *    服务端回 `gateway/arguments-invalid: unexpected "reasoningEffort"`。
 */
private fun effortOf(model: com.revxzxcx.remotewebapp.core.session.CatalogModel, current: ModelRef?, selected: Boolean): String? =
    if (selected) current?.reasoningEffort ?: model.defaultEffort else model.defaultEffort

/**
 * 底部输入区。
 *
 * 附件入口是「＋」按钮 + 二选一对话框，而不是直接把两个选择器摊开：相册选择器和
 * 文件选择器各占一行会让本来就只有两行的输入区再翻一倍，而绝大多数消息不带附件。
 *
 * 相册走 `ActivityResultContracts.PickVisualMedia`（系统照片选择器，**不需要相机/存储权限）；
 * 文件走 `OpenDocument`（SAF，同样不需要权限）。两条路都不申请运行时权限——
 * 本项目 M5-7 已经验证过相机权限链路，附件这里没必要再引入第二个权限面。
 *
 * ## 三种形态（用户 m29539）
 * [interactive] 非空时这个 Composable **整体变成审批/提问窗口**（[InteractiveWindow]），
 * 文本输入、附件、模型与权限配置全部让位——与 DSH 桌面端「卡片暂时占用编辑器位置」
 * 一致。默认值是 null，所以既有调用方（渲染测试）不受影响。
 */
@Composable
internal fun Composer(
    draft: String,
    generating: Boolean,
    /**
     * 每变一次就让输入框**收起并清焦点**（用户 m45428 第 5 条：「当用户上下滑动对话的时候
     * 应该要折叠输入框」）。
     *
     * 为什么传一个计数而不是布尔：收起是一个**一次性动作**，不是一种状态。布尔会在
     * 「true → false」时又触发一次别的逻辑，而且两次滑动之间布尔值不变、不会重新触发。
     * 计数器每滑一次 +1，`LaunchedEffect` 每次都能收到新的 key。
     */
    collapseSignal: Int = 0,
    sending: Boolean,
    pending: List<PromptAttachment>,
    attachNotice: String?,
    /** 有待回答的请求时占用整个输入区；null = 正常聊天输入态。 */
    interactive: InteractiveComposerRequest? = null,
    /** 回答失败的原因（提问与审批共用一条：都是「这一步没送出去」）。 */
    interactiveError: String? = null,
    onAnswerApproval: (String, Boolean) -> Unit = { _, _ -> },
    onToggleQuestionOption: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
    onQuestionCustom: (String, AskQuestion, String) -> Unit = { _, _, _ -> },
    onSubmitQuestions: (String) -> Unit = {},
    onSkipQuestions: (String) -> Unit = {},
    /** 模型配置入口（展开态第二行）。由调用方组装，Composer 不关心它长什么样。 */
    modelChip: @Composable () -> Unit,
    /** 审批权限入口（展开态第二行）。同上。 */
    permissionChip: @Composable () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onAttachImage: (ByteArray, String, String?, ImageMeasure) -> String?,
    onAttachFile: (ByteArray, String?) -> Unit,
    onAttachNotice: (String?) -> Unit,
) {
    if (interactive != null) {
        InteractiveWindow(
            request = interactive,
            error = interactiveError,
            onAnswerApproval = onAnswerApproval,
            onToggleOption = onToggleQuestionOption,
            onCustomAnswer = onQuestionCustom,
            onSubmit = onSubmitQuestions,
            onSkip = onSkipQuestions,
        )
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAttachSheet by rememberSaveable { mutableStateOf(false) }

    // 展开/收起：**只看焦点与内容，不看键盘**。键盘高度在部分设备上恒 0（MuMu + 搜狗实测），
    // 拿 `WindowInsets.ime` 当判据会让「点了输入框却没展开」。焦点是用户手指直接造成的，
    // 比任何间接信号都可靠。
    val fieldInteraction = remember { MutableInteractionSource() }
    val focused by fieldInteraction.collectIsFocusedAsState()

    // 用户滑动对话时收起输入框（用户 m45428 第 5 条：「输入框被点击的时候，会自动展开，
    // 但是当用户不输入时，却不会自动折叠。我认为当用户上下滑动对话的时候应该要折叠输入框」）。
    //
    // 做法：`collapseSignal` 每变一次就把焦点清掉（`fieldInteraction` 释放焦点 →
    // `collectIsFocusedAsState()` 变 false）。**不能直接把 `expanded` 设成 false**：
    // `expanded` 是派生值，清不掉；真正让它为假的只有「没有焦点 + 没有内容」。
    // 而「没有内容」不该由滑动决定——用户打了半句话去翻历史，回来草稿必须还在。
    // 所以滑动只负责**收键盘、去焦点**，内容仍然决定展开态：有草稿时框仍然展开，
    // 但键盘收了、视线让出来了。这是「不丢草稿」与「不挡屏幕」之间的正确取舍。
    val focusManager = LocalFocusManager.current
    LaunchedEffect(collapseSignal) {
        if (collapseSignal > 0) focusManager.clearFocus()
    }

    val expanded = focused || draft.isNotEmpty() || pending.isNotEmpty() || attachNotice != null

    // 选图：系统照片选择器返回的 Uri 只在这个回调里有效，读字节必须在这里做。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val queried = queryUri(context, uri)
        scope.launch {
            val loaded = loadUriBytes(queried.first, queried.second, queried.third)
            if (loaded == null) {
                onAttachNotice("这张图片读不出来（可能已被移动或删除）。")
                return@launch
            }
            val (bytes, mediaType, name) = loaded
            onAttachNotice(onAttachImage(bytes, mediaType, name, measureImage(bytes)))
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val queried = queryUri(context, uri)
        scope.launch {
            val loaded = loadUriBytes(queried.first, queried.second, queried.third)
            if (loaded == null) {
                onAttachNotice("这个文件读不出来（可能已被移动或删除）。")
                return@launch
            }
            val (bytes, _, name) = loaded
            // 「正在上传…」与最终结果都由 ViewModel 侧的 attachNotice 承载：
            // 上传跑在 viewModelScope 里，旋转屏幕既不取消它也不丢提示（R007 P2-3）。
            onAttachFile(bytes, name)
        }
    }

    // R028 ④（用户 m41448）：「未输入时输入框会收束成一行圆角方框，**悬浮于界面之上**，
    // 添加文件按钮和发送按钮放在输入框内，三者合为一体，并且输入区域也不用明显的分割线，
    // 主要是需要体现整体感和设计感。当用户点击的时候，输入框向上展开，而各种操作按钮
    // 排布在输入框的最下面一排，输入区域也不用给出分隔线，但是需要随着用户的输入向上展开」。
    //
    // 三条与旧实现的差别：
    // ① **悬浮**：旧的 Surface 是 `fillMaxWidth` 贴边通栏、上下与屏幕齐平，看起来是「底栏」；
    //    现在是四周留白 + 投影的浮动方块，与消息列表之间靠投影分层而不是靠一条 1dp 描边分界。
    // ② **无可见分隔线**：输入控件不再是 `OutlinedTextField`（自带一圈描边，那是「明显的
    //    分割线」）。改用 `TextField` + 全透明容器色/指示线色——保留 TextField 的全部行为
    //    （光标、选区、IME、`collectIsFocusedAsState`），只是不画框。分隔线交给留白与投影。
    // ③ **展开态按钮落在同一框内的最下面一排**：旧实现把权限 + 模型放在输入行**下面另起
    //    一行**，＋ 与发送则在输入行里，于是展开后是「输入行 + 配置行」两行按钮；
    //    现在展开态是「输入区在最上、操作按钮（权限图标 + 模型胶囊 + ＋ + 发送）在最下」。
    //
    // 两态共用一个 Surface，只让 `shape`/高度变化——容器不消失重建，输入框焦点与草稿
    // 因此不会因为状态切换而丢（这是「收起」最容易踩的坑）。
    //
    // 动画走**圆角半径**而不是「两个 Shape 之间插值」：Compose 没有 animateShapeAsState，
    // 而 `CircleShape`（收起）与 `RoundedCornerShape(20dp)`（展开）之间也没有可插值的
    // 中间形状。半径 26dp 在 ~52dp 高的单行上就是视觉上的全圆角，20dp 是圆角矩形，
    // 两者同族，动画连续。
    val cornerRadius by animateDpAsState(
        targetValue = if (expanded) 20.dp else 26.dp,
        animationSpec = tween(Motion.FAST, easing = Motion.Emphasized),
        label = "composerCorner",
    )
    // 四周留白让输入框「浮起来」；收起态给多一点横向留白，展开态给满，
    // 这样形态变化时看的是框在长大，而不是框在平移。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (expanded) 8.dp else 12.dp,
                end = if (expanded) 8.dp else 12.dp,
                top = 4.dp,
                bottom = 8.dp,
            ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(cornerRadius),
            // 描边保留但降权：投影负责分层，描边只负责在浅色主题下把边缘交代清楚。
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 只在展开态出现：附件行 ----
                AnimatedVisibility(
                    visible = expanded && pending.isNotEmpty(),
                    enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
                ) {
                    PendingAttachmentsRow(pending = pending, onRemove = onRemoveAttachment)
                }
                // ---- 只在展开态出现：附件提示 ----
                // 附件提示走 AnimatedVisibility 而不是 `let`：上传成功/失败/「正在上传…」
                // 三态切换是硬切时会闪一下，淡入淡出让它看起来是连续的状态变化。
                AnimatedVisibility(
                    visible = expanded && attachNotice != null,
                    enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = attachNotice.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            // 早先是硬编码 `Color(0xFFFFB4AB)`，浅色主题下几乎不可见。
                            // error 角色两套主题都调好了对比度（R016）。
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        TextButton(onClick = { onAttachNotice(null) }) { Text("知道了") }
                    }
                }

                // ---- 输入控件（两态都有，展开态在最上）----
                // 收起态：＋ 在左、发送在右，三者同行且都在框内（用户要的「三者合为一体」）。
                // 展开态：输入框独占上面一段，按钮下移到底排。
                //
                // 🔴 `Row` 本身**无条件**存在，`ComposerField` 因此永远在同一个调用点：
                // 早先写成 `if (!expanded) { Row{＋,字段,发送} } else { 字段 }`，点击聚焦 →
                // expanded 变真 → 组合走到另一个分支 → Compose 把它当成**另一个节点**重建 →
                // 新节点没有焦点 → `collectIsFocusedAsState` 立刻变假 → expanded 又变假 →
                // 回到原分支 —— 表现为「点输入框毫无反应、永远展不开」（设备 uiautomator 实测：
                // 连点四次、输入文字都不进 draft）。现在只有 ＋ 与 发送 两根**兄弟**在条件里，
                // 输入框的位置不变，焦点、光标、选区与 IME 全部保住。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (expanded) 14.dp else 0.dp,
                            end = if (expanded) 14.dp else 0.dp,
                            top = if (expanded) 10.dp else 0.dp,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 🔴 收起态的 ＋ 与发送必须**等下面那排的退出动画播完**再出现
                        // （R028 审查 P3-15）。早先是裸 `if (!expanded)`：收起那一刻底排还在播
                        // 140ms 的 `shrinkVertically + fadeOut`，而这两根已经提前画出来，
                        // 于是一帧里同时存在**两个 ＋ 和两个发送键**，看起来像重影。
                        // `enter` 的 `delayMillis = Motion.FAST` 正好等于底排的 exit 时长，
                        // `exit` 用 0ms 保证展开方向不会拖着旧按钮（那两个由底排负责淡入）。
                        // 首次组合为收起态时 AnimatedVisibility 不播入场动画，按钮立即在场。
                        AnimatedVisibility(
                            visible = !expanded,
                            enter = fadeIn(tween(Motion.FAST, delayMillis = Motion.FAST)),
                            exit = fadeOut(tween(0)),
                        ) {
                            AttachButton(onClick = { showAttachSheet = true })
                        }
                        ComposerField(
                            draft = draft,
                            generating = generating,
                            expanded = expanded,
                            fieldInteraction = fieldInteraction,
                            onDraftChange = onDraftChange,
                            onSend = onSend,
                            modifier = if (expanded) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.weight(1f).padding(horizontal = 6.dp)
                            },
                        )
                        AnimatedVisibility(
                            visible = !expanded,
                            enter = fadeIn(tween(Motion.FAST, delayMillis = Motion.FAST)),
                            exit = fadeOut(tween(0)),
                        ) {
                            ComposerSendButton(
                                draft = draft,
                                sending = sending,
                                pending = pending,
                                onSend = onSend,
                                onAttachNotice = onAttachNotice,
                            )
                        }
                    }
                }

                // ---- 底排（仅展开态）：权限图标 + 模型胶囊 + ＋ + 发送 ----
                // 用户 m42277 定案：权限只留图标（它本来就只读，多写几个字只是占宽度），
                // 于是原来必须拆成两行的四件东西能回到同一排——这正是 m41448「各种操作
                // 按钮排布在输入框的最下面一排」的字面要求。
                // 模型胶囊保留「全名 + 档位」，**允许在 360dp 上省略号截断**（同一轮定案）：
                // 完整 id 在点开的模型选择器里本来就能看到，为此牺牲排列整齐不值得。
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 用户 m44550：「将加号按钮移动到最左边，这样显得整体更为协调一些」。
                        // 于是底排顺序从「权限 · 模型 · …… · ＋ · 发送」改为
                        // 「＋ · 权限 · 模型 · …… · 发送」——＋ 与发送分居两端，中间是
                        // 两个只读徽标，视觉上两侧对称。
                        AttachButton(onClick = { showAttachSheet = true })
                        Spacer(Modifier.width(6.dp))
                        permissionChip()
                        Spacer(Modifier.width(6.dp))
                        // 模型胶囊用**唯一**一个弹性槽吃掉剩余宽度：它自己按自然宽度画，
                        // 太宽时被这个槽的上限约束住走省略号。
                        //
                        // 早先写成 `Box(weight(1f, fill = false)) { chip }` + `Spacer(weight(1f))`，
                        // 两个 1f 兄弟把剩余宽度对半分，而 spacer 一个像素都用不掉——实测 360dp 上
                        // 胶囊只拿到约一半空间，`buddy/deepseek-v4.1-flash` 被截成「buddy/de…」，
                        // 比「允许截断」的口径难看得多。现在发送键由这个槽顶到最右，胶囊拿到
                        // 全部剩余宽度（效果图里 `.chip{flex:0 1 auto}` + `.spacer{flex:1 1 auto}`
                        // 的等价写法）。
                        Box(modifier = Modifier.weight(1f)) { modelChip() }
                        Spacer(Modifier.width(6.dp))
                        ComposerSendButton(
                            draft = draft,
                            sending = sending,
                            pending = pending,
                            onSend = onSend,
                            onAttachNotice = onAttachNotice,
                        )
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        AlertDialog(
            onDismissRequest = { showAttachSheet = false },
            title = { Text("添加附件") },
            text = {
                Column {
                    Text(
                        text = "图片按桌面端的上限预检后直接发送；其它文件先上传到桌面端，再随消息一起提交。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAttachSheet = false
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) { Text("选图片") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAttachSheet = false
                    pickFile.launch(arrayOf("*/*"))
                }) { Text("选文件") }
            },
        )
    }
}

/**
 * R028 ④：输入区被拆成三个可复用件（＋ / 输入控件 / 发送），因为两态里它们的**排布不同**
 * 而**外观相同**：收起态是「＋ | 输入 | 发送」一行，展开态是「输入在上、底排里
 * 权限图标 + 模型 + ＋ + 发送」。拆成函数才不会出现「收起态改了、展开态忘了改」。
 */
@Composable
private fun AttachButton(onClick: () -> Unit) {
    // 「＋」做成小圆钮而不是 TextButton：TextButton 自带 48dp 最小触控区与内边距，
    // 在两行高的输入区里会把整行撑高，而且字重和「发送」不一致。
    // 图标用 `Icons.Default.Add` 而不是字符「＋」（R017）：全角加号的字形宽度随字体变，
    // 居中永远差一两像素，图标则恒等。
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加附件",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSizes.composer),
        )
    }
}

/**
 * R028 ④：输入控件用 `TextField` + **全透明容器色/指示线色**，而不是 `OutlinedTextField`。
 *
 * 用户原话要求「输入区域也不用明显的分割线，主要是需要体现整体感和设计感」，
 * 而 `OutlinedTextField` 自带一圈描边——那正是「明显的分割线」。用 `TextField` 保留全部
 * 行为（光标、选区、IME、多行、`interactionSource` 的焦点上报），只是把四色设成透明：
 * 容器色 / 聚焦指示线 / 未聚焦指示线 / 禁用容器色。**不要**用 `BasicTextField`：
 * 那要自己实现占位符、文本样式与 `ImeAction`，settle 出来的东西和 TextField 一样但更容易错。
 *
 * 高度：`heightIn(min = 40.dp, max = 160.dp)` 保留——展开态正文最多 6 行后开始内部滚动，
 * 展开高度因此随输入增长（用户要的「随着用户的输入向上展开」）。
 */
@Composable
private fun ComposerField(
    draft: String,
    generating: Boolean,
    expanded: Boolean,
    fieldInteraction: MutableInteractionSource,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier.heightIn(min = 40.dp, max = 160.dp),
        placeholder = {
            Text(
                // 收起态文案贴近用户 m27990 参考图的「描述你的任务，或…」；
                // 展开态改用能解释「现在发会怎样」的文案（生成中 = 排队）。
                if (expanded && generating) {
                    "生成中…（发送会排到当前轮之后）"
                } else {
                    "描述你的任务，或先聊几句…"
                },
            )
        },
        maxLines = 6,
        shape = RoundedCornerShape(12.dp),
        interactionSource = fieldInteraction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        // 四色透明：不画容器、不画指示线。分隔交给留白与投影（R028 ④）。
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * R028 ④：发送键（圆形 + ↑）。与 `＋` 同尺寸、同形状，因此在底排里并排时天然对齐。
 *
 * 用 `KeyboardArrowUp` 而不是「↑」字符——字符的字形宽度随字体变，圆形里永远偏一两像素
 * （R017 同理由）。禁用态不隐藏按钮而是降透明度：参考图里它一直在。点了没反应会让人
 * 以为 App 卡了，所以 `canSend == false` 时点击给一句提示。
 *
 * `enabled = canSend` 必须真的传给 clickable：早先是硬编码 `enabled = true`，于是
 * `assertIsNotEnabled` 这类判据（以及读屏用户）拿到的是「可点」。contentDescription
 * 恒为「发送按钮」——它是按钮的身份，不随状态变；「现在发会排队」由占位符承担。
 */
@Composable
private fun ComposerSendButton(
    draft: String,
    sending: Boolean,
    pending: List<PromptAttachment>,
    onSend: () -> Unit,
    onAttachNotice: (String?) -> Unit,
) {
    val canSend = (draft.isNotBlank() || pending.isNotEmpty()) && !sending
    val sendTint by animateColorAsState(
        targetValue = if (canSend) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
        },
        // 必须显式给出类型实参：`tween(Motion.FAST)` 没有别的约束，Kotlin 会把 T 推成 Int，
        // 于是 `AnimationSpec<Color>` 一个候选都不匹配。
        animationSpec = tween<Color>(Motion.FAST),
        label = "sendTint",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(sendTint)
            .semantics { contentDescription = "发送按钮" }
            .clickable(enabled = canSend) {
                if (canSend) {
                    onSend()
                } else {
                    // 空草稿 / 上传中。给一句当场可懂的理由，而不是静默吞掉。
                    onAttachNotice(
                        when {
                            sending -> "正在发送上一条，请稍等…"
                            else -> "先输入文字或加个附件。"
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSizes.composer),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.surface,
            )
        } else {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = if (canSend) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(IconSizes.composer),
            )
        }
    }
}

/** 待发送附件的一行小卡片。 */
@Composable
private fun PendingAttachmentsRow(
    pending: List<PromptAttachment>,
    onRemove: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(pending, key = { index, item -> "$index-${item.receiptId ?: item.name ?: ""}" }) { index, item ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 图片给真缩略图，文件给一个图标占位——T5 设备验收：早先两种都是
                    // 一行文字，用户完全看不出自己挂上去的是什么。
                    PendingAttachmentThumb(item)
                    Text(
                        text = when (item.kind) {
                            PromptAttachment.Kind.IMAGE -> "图片 · ${item.name ?: "未命名"}"
                            PromptAttachment.Kind.FILE -> "文件 · ${item.name ?: "未命名"}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(
                        onClick = { onRemove(index) },
                        modifier = Modifier.padding(start = 2.dp),
                    ) { Text("×") }
                }
            }
        }
    }
}

/**
 * 待发送附件的缩略图。
 *
 * 图片从 base64 现场解一张小图（[decodeScaled] 只按宽度下采样，缩略图给 96px 足够，
 * 内存代价可以忽略）；解不出来时退成一个灰色方块而不是留白。文件用 `📎` 文字图标，
 * 因为文件附件在发送前**只有回执**，本地没有可渲染的内容（D15：服务端没有读任意
 * 本地文件的接口，`fileUploads/upload` 之后内容只在桌面端）。
 */
@Composable
private fun PendingAttachmentThumb(item: PromptAttachment) {
    val isImage = item.kind == PromptAttachment.Kind.IMAGE
    val bitmap = produceState<ImageBitmap?>(
        initialValue = null,
        isImage,
        item.data,
    ) {
        value = if (!isImage || item.data.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                // 缩略图宽度 96px：屏幕上它只有 36dp 宽，2x 密度下 72px 已够，96 留余量。
                decodeScaled(item.data.fromBase64(), maxWidth = 96)?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap.value
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = "附件预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = if (isImage) "图" else "📎",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 滚到列表末尾，并让最后一行的**底部**贴住视口底部。
 *
 * 为什么不能只用 `scrollToItem(index)`：它把该行的**顶边**对齐到视口顶边。聊天页最后一行
 * 是流式生成的助手气泡，高度随 token 不断增长，一旦超过一屏，`scrollToItem` 就把它的
 * 开头钉在屏幕顶部——用户看到的是「生成内容停在自己开头、最新的字在屏幕外」，也就是
 * T7 里说的「回弹到生成的开头」。
 *
 * 这里改成两步：先 `scrollToItem` 把行带进来，再按「最后一个可见项超出视口底边多少」补滚一次。
 * 收敛条件是 overflow 本身而不是「还能不能向前滚」——后者需要循环若干屏才收敛，而末行可能
 * 高过多屏（真机实测出现过 2844px 的助手气泡，视口只有 1044px），按屏补滚要么漏、要么白滚。
 *
 * 用 [androidx.compose.foundation.MutatePriority.Default] 而不是用户手势的优先级：跟随滚动
 * 不该抢走用户正在做的手势，但也不能被挂起的手势无限期挡住。
 *
 * ## 开头必须等一帧（用户 m21177 第 2.3 条反馈的第二个根因）
 *
 * `snapshotFlow` 在**快照应用后立刻**发射，那一刻列表往往还没重新测量：新行不在
 * `layoutInfo` 里、旧行还是旧高度。此时 `scrollToItem(index)` 会钳到**旧布局**的最大滚动处
 * （等于没滚），而 `canScrollForward` 在旧布局里又是 false → 补滚循环直接退出 → 之后
 * 没有任何东西再触发一次，列表永远停在「差一截」的位置。
 * `ChatScrollFollowTest` 的埋点实测到这条：`collect pair=(2,0) near=true lastIndex=2
 * total=2`——新行已经在状态里，布局却还只有 2 行。
 * `withFrameNanos { }` 让出到下一帧，重组与测量都跑完再读 `layoutInfo`。
 *
 * `internal` 而不是 `private`：这是 T7 设备验收实测缺陷的回归守卫，必须能被渲染测试驱动。
 */
internal suspend fun LazyListState.scrollToBottom(index: Int) {
    if (index < 0) return
    // 没有 MonotonicFrameClock 的上下文（例如单测里的 runBlocking）说明本来就没有帧在跑，
    // 没什么可等的；生产路径上 LaunchedEffect 的协程一定带帧时钟。
    if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { }
    scrollToItem(index)
    scroll(MutatePriority.Default) {
        val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@scroll
        val overflow = (last.offset + last.size) - layoutInfo.viewportEndOffset
        if (overflow > 0) scrollBy(overflow.toFloat())
    }
}

/**
 * 把一次选择器选择读成 `(字节, MIME, 展示名)`。
 *
 * 三个输入都是**已经取到的事实**（`open` 是懒的，读失败由它返回 null 表达），所以整个
 * 函数不碰 Android 的 ContentResolver，能被 JVM 单测直接驱动——选择器回调发生在主线程，
 * 这里抛异常会直接崩进程，而它恰恰是最容易出异常的一段 IO。
 *
 * 读失败（流被占用、文件已删、跨提供方不可读）返回 null，由调用方弹提示。
 */
internal suspend fun loadUriBytes(
    open: () -> java.io.InputStream?,
    mediaType: String?,
    displayName: String?,
): Triple<ByteArray, String, String?>? = withContext(Dispatchers.IO) {
    // 取消必须原样上抛：`runCatching` 会连 `CancellationException` 一起吞掉，而它发生在
    // `withContext(Dispatchers.IO)` 里——用户旋转屏幕或返回时 `withContext` 取消这个块，
    // 吞掉之后调用方看到的是「读不出来」而不是静默取消（R015 审查 P3-2）。
    val bytes = try {
        open()?.use { it.readBytes() }
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        null
    } ?: return@withContext null
    if (bytes.isEmpty()) return@withContext null
    Triple(bytes, mediaType ?: "application/octet-stream", displayName)
}

/**
 * 从 content Uri 取出 `(输入流工厂, MIME, 展示名)`。
 *
 * 单独一层而不是塞进 [loadUriBytes]：查询 `OpenableColumns` 与 `getType` 都是会抛的
 * 系统调用，各自 `runCatching` 各管各的失败，读字节那段则完全不被它们影响。
 * 这里的 `runCatching` 同样吞取消，但两处都在**主线程同步段**里、块内没有挂起函数，
 * 所以不存在「取消被吞」的窗口（与 [loadUriBytes] 的 `withContext(IO)` 不同）。
 */
private fun queryUri(context: android.content.Context, uri: android.net.Uri): Triple<() -> java.io.InputStream?, String?, String?> {
    val resolver = context.contentResolver
    val open = { runCatching { resolver.openInputStream(uri) }.getOrNull() }
    val mediaType = runCatching { resolver.getType(uri) }.getOrNull()
    val name = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return Triple(open, mediaType, name)
}

/** 一条持久化条目。 */
/**
 * 折叠分组的一行（用户 m24940，桌面端「标准模式」的 `ProcessGroup`）。
 *
 * 收起时只显示 [summarizeGroup] 算出的一行摘要（「已读取文件并写入文件」这类），
 * 点一下才把组内成员按原来的样子逐条铺开。**默认收起**——这一条就是需求本体：
 * 用户原话「自动折叠各种操作比如思考、命令、读文件，折叠成『已思考、执行命令、
 * 读取文件等』」。
 *
 * 展开态用 `rememberSaveable` 而不带输入键：状态绑定到**组合槽位**，而外层
 * `items(key = …)` 给每条消息稳定槽位，所以流式过程中组内成员增加也不会把展开态
 * 重置掉（[ReasoningBlock] 踩过同一个坑，见那里的 KDoc）。
 *
 * @param running 这一组是否属于正在进行的那一轮。只有进行中的分组才显示实时细节
 *   （桌面端 `POLICIES.standard.liveProcessDetail`）。
 */
@Composable
internal fun GroupRow(
    members: List<ChatItem>,
    running: Boolean,
    now: Long,
    changes: Map<Long, ChangesEntry>,
    changesLoading: Set<Long>,
    onOpenFile: (FileTarget) -> Unit,
    onRetryChanges: (Long) -> Unit,
    attachments: SessionAttachmentRepository,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = remember(members, running) { summarizeGroup(members, running) }
    // 展开区左侧那道 1dp 层级导轨的颜色，在组合作用域里取出来供 `drawBehind` 用
    // （DrawScope 不是 @Composable，拿不到 MaterialTheme）。
    val railLineColor = MaterialTheme.colorScheme.outlineVariant
    // R017 / D47：折叠卡改「实底 + 1dp 描边」。原来是
    // `surfaceVariant.copy(alpha = 0.45f)`——半透明叠加在消息列表的 surface 上，
    // 深色浅色两套主题各自糊成不同灰度，且与「助手气泡无底色」的形态没有区分度。
    // 实底 + outlineVariant 描边在两套主题下都是同一个「卡片」语义。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = groupShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // chevron 旋转而不是换字符（R016）：`▸`/`▾` 两个字形宽度不同，
                // 切换时标题会被推着左右跳一下；旋转是纯 transform，位置不动。
                // R017 把字形换成 `KeyboardArrowRight` 图标：字形的基线位置随字体
                // 变，图标恒定居中，且能与工作区行的箭头用同一个视觉语言。
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(Motion.FAST, easing = Motion.Emphasized),
                    label = "groupChevron",
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                )
                Text(
                    text = summary.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                // 徽标用参数 `running`（「这一组就是正在生成的那一组」）而不是细节行是否为空：
                // 纯思考的分组即使暂时没有可显示的细节，用户也该知道它还在跑。
                // 早期版本另有一个 `FoldSummary.running = running && detail.isNotEmpty()`，
                // 但没有任何消费方，是死字段（R015 审查 P3-1），已删。
                if (running) {
                    // R017：原来这里是「呼吸圆点 + 进行中」两件手写动画。全 App 的
                    // 「正在运行」改由 `GeneratingGlyph` 一个组件表达（R017 总原则 ②），
                    // 所以这里换成三柱均衡器。SM 尺寸 16dp，与 6dp 圆点同数量级但
                    // 视觉权重更高——折叠卡通常一次有好几条，用户扫一眼就知道哪条在跑。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GeneratingGlyph(size = GlyphSize.SM, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "进行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                // R017：收起态右侧的 `×N`。原来折叠卡只告诉用户「这有一组工具」，
                // 展开才知道里面有几条；一条执行了 12 个命令的分组和一条只有 1 个的
                // 分组看起来完全一样。等宽数字保证 1/10/100 位时宽度不跳。
                if (summary.count > 1) {
                    Text(
                        text = "×${summary.count}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            // 进行中的那一行细节（`liveToolDetail`）。只在真有内容时才占一行高度，
            // 否则纯思考的分组会在摘要下面多出一条空白。
            //
            // 细节行走 AnimatedVisibility 而不是 `if`：流式过程中 detail 每个 token
            // 都在变，用 `if` 会让它在「有内容」和「没内容」之间反复出现/消失，
            // 每次都是硬切。淡入淡出让这种高频变化看起来是连续的。
            AnimatedVisibility(
                visible = summary.detail.isNotEmpty(),
                enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
            ) {
                Text(
                    text = summary.detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
            ) {
                // 展开内容必须是**内层 lazy + 限高**，不能 `members.forEach` 一次性全渲染。
                // `MESSAGE_CACHE_LIMIT = 600` 之下一个轮次的分组可含数百个成员，在单个
                // LazyColumn item 里同时建数百个气泡会让测量/布局卡顿，而且巨型行高还会
                // 扭曲 [ChatScrollbar] 「未量行用平均值补」的高度估算模型（R015 审查 P2-2）。
                // 限高让这一行始终有界，内层 lazy 保证同时只有可见成员被组合。
                //
                // R017：左边那道 1dp 竖线是「层级导轨」。展开区里的每个成员自己已经是
                // 一张卡/一条带导轨的气泡，外面再套一条线是为了回答「这些是从哪儿展开
                // 来的」——没有它，展开区看起来像平白多出来的一堆卡片。
                //
                // 🔴 画法：`drawBehind` 而不是「一个 width(1.dp).fillMaxHeight() 的 Box」
                // （R028b 审查 P1-1 的正解）。
                //
                // 为什么不能用 Box + fillMaxHeight：这个 Row 住在聊天 LazyColumn 的 item
                // 里，高度约束是 `Infinity`，`fillMaxHeight()` 在无界高度下取 `minHeight`
                // = **0**，导轨一个像素都画不出来（同文件 `Bubble` 犯过同一个错）。
                //
                // 为什么也不能用 `height(IntrinsicSize.Min)`：那会向 Row 的子项请求**固有
                // 高度**，而子项里有一个 `LazyColumn`——`SubcomposeLayout` 家族一律不支持
                // 固有测量，直接抛 `IllegalStateException: Asking for intrinsic measurements
                // of SubcomposeLayout layouts is not supported`。（这一条是变异验证抓到的：
                // 加 IntrinsicSize 后 GroupRowRenderTest 三条全崩。）
                //
                // `drawBehind` 在**布局完成之后**才画，此时 `size.height` 已经是实测高度，
                // 既不需要固有测量、也不需要 fillMaxHeight——零额外测量开销。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            // 线宽 1dp，上下各留 4dp（与原 padding 口径一致）。
                            val inset = 4.dp.toPx()
                            drawRect(
                                color = railLineColor,
                                topLeft = Offset(0f, inset),
                                size = Size(1.dp.toPx(), (size.height - inset * 2).coerceAtLeast(0f)),
                            )
                        },
                ) {
                    // 内层列表状态：展开后要能滚到最后一个操作，新操作进来时要跟随，
                    // 用户上划后不抢滚动（用户 m45428 第 3 条）。逻辑在 [GroupAutoScroll]。
                    val innerState = rememberLazyListState()
                    GroupAutoScroll(
                        listState = innerState,
                        expanded = expanded,
                        memberCount = members.size,
                    )
                    LazyColumn(
                        state = innerState,
                        modifier = Modifier
                            .padding(start = 10.dp, top = 10.dp)
                            .heightIn(max = 360.dp)
                            .fillMaxWidth(),
                        // R028b（用户 m44550 第二张图）：成员改为**行式**后必须去掉
                        // 条目间距。行与行之间由各自的 1dp 上分隔线连接，若还留 8dp 空隙，
                        // 分隔线会浮在两段空白中间（看起来像断开的短横线）。
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        itemsIndexed(members, key = { _, member -> member.seq }) { index, item ->
                            GroupMemberRow(
                                item = item,
                                showTopDivider = index > 0,
                                now = now,
                                changes = changes[item.seq],
                                changesLoading = item.seq in changesLoading,
                                onOpenFile = onOpenFile,
                                onRetryChanges = onRetryChanges,
                                attachments = attachments,
                                sessionId = sessionId,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 折叠分组内的一条成员。
 *
 * 与 [ChatItemRow] 的区别只有一处、但是 R028b 的全部内容：**工具调用/工具结果在
 * 分组内走行式（[ToolCallRow] / [ToolResultRow]），而不是各自一张带描边的卡**。
 *
 * 为什么必须分成两个函数而不是给 [ChatItemRow] 加一个 `inGroup` 布尔：卡片形态在
 * 分组外仍然是对的（一条零散的工具事件单独出现时需要自己的边框），而分组内几十条
 * 卡片会叠成一面带横线的色块——用户 m44550 第二张图看到的正是这个（「操作和工具调用
 * 的样式还是和之前一样」）。两者的**点击语义也不同**：卡片点标题展开，行式点整行进
 * 文件页。用布尔开关会把两套语义混进一个函数。
 */
@Composable
private fun GroupMemberRow(
    item: ChatItem,
    showTopDivider: Boolean,
    now: Long,
    changes: ChangesEntry?,
    changesLoading: Boolean,
    onOpenFile: (FileTarget) -> Unit,
    onRetryChanges: (Long) -> Unit,
    attachments: SessionAttachmentRepository,
    sessionId: String,
) {
    when (item) {
        is ChatItem.ToolCall -> {
            var expanded by rememberSaveable(item.seq) { mutableStateOf(false) }
            // 路径只解析一次，同时喂给行组件与「展开体可见性」（R028b 审查 P2-6）：
            // 早先这里与 `ToolCallRow` 各解析一遍，同一条 arguments 每帧跑两遍 JSON。
            val path = remember(item.seq, item.arguments) {
                ToolFilePaths.extract(item.name, item.arguments)
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                ToolCallRow(
                    item = item,
                    path = path,
                    showTopDivider = showTopDivider,
                    onToggle = { expanded = !expanded },
                    onOpenFile = { target -> onOpenFile(FileTarget(target)) },
                )
                // 展开体是**原始参数**，与有没有路径无关（审查 P2-3 的配套修复）：
                // 早先 `expanded && path == null` 让 `write` / `edit` 这类带路径的调用
                // 永远看不到 content / old_string，而那正是审查工具调用时最想看的东西。
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = item.arguments.ifBlank { "（无参数）" }.take(MAX_INLINE_ARGUMENTS_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    )
                }
            }
        }

        is ChatItem.ToolResult -> {
            var expanded by rememberSaveable(item.seq) { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                ToolResultRow(
                    item = item,
                    showTopDivider = showTopDivider,
                    onToggle = { expanded = !expanded },
                )
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = item.text.ifBlank { "（无输出）" }.take(MAX_INLINE_RESULT_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    )
                }
            }
        }

        // 折叠进分组的思考成员用行式，与工具条目同一种形态（用户 m45428 第 2 条）。
        //
        // 判据是「这一条**只剩**思考、没有正文」：
        //  - 只剩思考 → 行式。它只是这一组操作里的一步，再套一张卡会显得突兀，
        //    用户原话就是「思考过程的样式还是和之前一样，显得有些突兀」。
        //  - 思考 + 正文（模型先说再写，或边想边说）→ 保持 [ChatItemRow] 的卡片形态。
        //    那种情况下思考是「这条回复的一部分」，拆成两行会把一条回复劈开。
        //
        // 🔴 判据必须建立在 `visibleAssistantBlocks` 的结果上，**不能拿 `item.blocks` 原样判**。
        // 实测上个会话（f3c843ab）12221 条 `assistant/message` 的块形状分布：
        //   `[reasoning, tool-call]` 5738 条（绝对主流）、`[tool-call]` 5130、
        //   `[reasoning, text, tool-call]` 809。
        // 也就是说「先想、再调工具」是模型的常态：这类消息的工具块会被 `visibleAssistantBlocks`
        // 滤掉，于是它被折进分组（`hasReply()` 那边判得对），可整条消息的原始 `blocks` 里
        // **带着**那个 tool-call，`all { it is Reasoning }` 因此为假 → 整条落到卡片形态、
        // 思考被渲染成 [ReasoningBlock] 大卡。首版正是这么写的，设备验收一眼可见：
        // 分组里其余成员都是行，唯独思考是一张卡。
        is ChatItem.Assistant -> {
            val visible = visibleAssistantBlocks(item.blocks, item.interrupted)
            val reasoningOnly = visible.isNotEmpty() && visible.all { it is ContentBlock.Reasoning }
            if (reasoningOnly) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    visible.forEachIndexed { index, block ->
                        ReasoningRow(
                            text = (block as ContentBlock.Reasoning).text,
                            // 组内第一条不画上分隔线；同一成员里若有多个思考块，
                            // 后面几条自己也要有分隔线，否则会和上一条粘成一段。
                            showTopDivider = showTopDivider || index > 0,
                        )
                    }
                }
            } else {
                ChatItemRow(
                    item = item,
                    now = now,
                    changes = changes,
                    changesLoading = changesLoading,
                    onOpenFile = onOpenFile,
                    onRetryChanges = onRetryChanges,
                    attachments = attachments,
                    sessionId = sessionId,
                    railColor = null,
                )
            }
        }

        // 其余成员（改动卡、提示行……）保持原有形态，但**不画导轨**：
        // 分组展开区外面已经有一道 1dp 层级导轨（审查 P2-9）。
        else -> ChatItemRow(
            item = item,
            now = now,
            changes = changes,
            changesLoading = changesLoading,
            onOpenFile = onOpenFile,
            onRetryChanges = onRetryChanges,
            attachments = attachments,
            sessionId = sessionId,
            railColor = null,
        )
    }
}

/** 分组内展开的原始参数最多显示这么多字符（比卡片形态的 2000 短：行内空间更小）。 */
private const val MAX_INLINE_ARGUMENTS_CHARS = 1_200

/** 分组内展开的工具输出最多显示这么多字符。 */
private const val MAX_INLINE_RESULT_CHARS = 4_000

/**
 * 一条消息的完整渲染（用户气泡 / 助手气泡 / 各类卡片）。
 *
 * `internal` 而不是 `private`：`MessageSelectionTest` 要走「消息 → 是否可长按选择」这条
 * 真实路径，才能守住「正文可选、工具卡不可选」这个分流（R030）。
 * 只测 `Bubble` 本体不行——它拿不到 `ChatItemRow` 给每条消息传的 `selectable`。
 */
@Composable
internal fun ChatItemRow(
    item: ChatItem,
    now: Long,
    changes: ChangesEntry?,
    changesLoading: Boolean,
    onOpenFile: (FileTarget) -> Unit,
    onRetryChanges: (Long) -> Unit,
    attachments: SessionAttachmentRepository,
    sessionId: String,
    modifier: Modifier = Modifier,
    /**
     * 助手正文左侧导轨的颜色；`null` = 不画。
     *
     * 分组内的成员必须传 `null`（R028b 审查 P2-9）：`GroupRow` 的展开区外面已经有
     * 一道 1dp 的层级导轨，组内每条正文再画一条 2dp 导轨，两者相距仅 10dp，
     * 看起来是「一条细线 + 每条内容各一条粗线」——与用户 m44550 第 1 条要的
     * 「一条导轨」正好相反。默认值保持 `outline`，分组外仍然要导轨。
     */
    railColor: Color? = MaterialTheme.colorScheme.outline,
) {
    when (item) {
        is ChatItem.User -> Bubble(
            alignEnd = true,
            background = MaterialTheme.colorScheme.primaryContainer,
            time = item.time,
            now = now,
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 图片/文件块必须单独渲染：早先这里只画 `item.text`，于是「只发图不发字」
                // 的消息在对话里是一个**空白气泡**——用户 m21177 第 5c 条反馈原话
                // 「对话气泡中没有做预览，这是不优雅的」。
                //
                // 图片走 BlockView（按 attachmentId 现取像素），文件给一行说明——
                // 文件附件在会话日志里同样只剩一个 id，客户端没有可渲染的内容。
                item.blocks.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> Unit // 文字统一走下面的 MdBlocks
                        // （空分支是刻意的：这个 when 只处理非文字块，文字走 MdStream）
                        else -> BlockView(block, onOpenFile, attachments, sessionId)
                    }
                }
                if (item.text.isNotBlank()) {
                    // 用户消息里通常有粘贴的路径/命令，用 Markdown 渲染能保留行内代码与折行。
                    MdBlocks(
                        blocks = rememberMarkdown(item.text),
                        onLinkClick = {},
                        onFilePathClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is ChatItem.Assistant -> {
            // 工具调用块要滤掉（原因见 visibleAssistantBlocks 的 KDoc）：同一次调用既有
            // 独立的 `tool/call` 事件，又有 assistant/message 里的 `tool-call` 块，
            // 实测 182:182 一一对应，不滤就渲染两遍。
            val visible = visibleAssistantBlocks(item.blocks, item.interrupted)
            if (visible.isEmpty()) return
            // R016：助手正文不设背景。内容自带层次（Markdown 标题 / 代码块 / 分隔线），
            // 再套色块只会把层次糊掉。用户气泡仍然有底色，那才是「谁说的」的分界。
            Bubble(
                alignEnd = false,
                background = null,
                time = item.time,
                now = now,
                footer = if (item.interrupted) "（已中止）" else null,
                // 助手正文常有代码块与表格，320dp 太窄。
                wide = true,
                modifier = modifier,
                railColor = railColor,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.forEach { block -> BlockView(block, onOpenFile, attachments, sessionId) }
                }
            }
        }

        is ChatItem.ToolCall -> Bubble(
            alignEnd = false,
            background = null,
            time = item.time,
            now = now,
            modifier = modifier,
            // 工具卡自己就是「实底 + 1dp 描边」的卡（R017），再画一条导轨会和卡的
            // 左描边叠成 3dp，且让「卡」读起来像「行」。导轨只给纯内容气泡。
            railColor = null,
            // 卡里有展开按钮与文件跳转，包进选择容器会让长按抢走点击（R030）。
            selectable = false,
        ) {
            ToolCallCard(item, onOpenFile = { path -> onOpenFile(FileTarget(path)) })
        }

        is ChatItem.ToolResult -> Bubble(
            alignEnd = false,
            background = null,
            time = item.time,
            now = now,
            modifier = modifier,
            railColor = null,
            selectable = false,
        ) {
            ToolResultCard(item)
        }

        is ChatItem.Changes -> ChangesCard(
            turn = item.turn,
            loading = changesLoading,
            entry = changes,
            // 一行改动文件要同时带出三样东西：**真实路径**（`display` 是桌面端裁短过的
            // 展示串，拿去请求文件会 not-found）、`display`（标题栏照原样显示）、
            // 以及它在 summary.files 里的**下标**（文件查看页靠它取本轮 diff）。
            // 早前版本只回传 `display` 再 `indexOfFirst { it.path == display }` 反查，
            // `display != path` 时恒为 -1，diff 标签页与「在电脑上打开」会一起消失（R005 P1-1）。
            onOpenFile = { path, display, index ->
                onOpenFile(
                    FileTarget(
                        path = path,
                        display = display,
                        seq = item.seq,
                        index = index,
                    ),
                )
            },
            onRetry = { onRetryChanges(item.seq) },
        )

        // R028 ②（用户 m41448 批准的效果图 s2 第 ④ 条）：「轮次分隔改成细线 + 居中标签」。
        //
        // 这条分隔线**没有**自己的背景色——它画在消息列表的 surface 上，所以取
        // `outlineVariant` 而不是默认的 `HorizontalDivider` 颜色（默认值在深色主题下比
        // outlineVariant 更亮，会让每轮之间出现一圈比卡片描边还抢眼的横线）。
        // 间距从 8dp 收到 10dp 是照效果图 `.round{gap:10px}`：两条线越靠近标签，
        // 「这一段属于同一轮」的读法越成立。
        is ChatItem.Notice -> Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = item.text,
                style = MaterialTheme.typography.labelSmall,
                // 注意：这里**不**做等宽。效果图 s2 的 `.round .tx` 是普通字重普通字体，
                // 「第 10 轮结束」这种中文标签走等宽反而更松散。
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** 渲染一个内容块。 */
@Composable
private fun BlockView(
    block: ContentBlock,
    onOpenFile: (FileTarget) -> Unit,
    attachments: SessionAttachmentRepository,
    sessionId: String,
) {
    when (block) {
        is ContentBlock.Text -> MdBlocks(
            blocks = rememberMarkdown(block.text),
            onLinkClick = {},
            onFilePathClick = { path -> onOpenFile(FileTarget(path)) },
            modifier = Modifier.fillMaxWidth(),
        )

        is ContentBlock.Reasoning -> ReasoningBlock(block.text)

        // 图片要按 attachmentId 现取像素（`session/attachment`），不是占位文字。
        // `offloaded: true` 是常态：服务端把像素卸载进附件库，内容块只留 id。
        is ContentBlock.Image -> AttachmentImage(
            sessionId = sessionId,
            attachmentId = block.attachmentId,
            label = block.name ?: block.mediaType.takeIf { it.isNotBlank() },
            repository = attachments,
        )

        is ContentBlock.File -> Text(
            text = "[文件 ${block.name}]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 正常路径下这里到不了（visibleAssistantBlocks 已过滤掉），只有被中止的消息
        // 会保留 tool-call 块。用同一张卡片渲染，保持观感一致。
        is ContentBlock.ToolCall -> Column {
            Text("工具 · ${block.name}", style = MaterialTheme.typography.labelMedium)
            val path = ToolFilePaths.extract(block.name, block.arguments)
            if (path != null) {
                TextButton(onClick = { onOpenFile(FileTarget(path)) }) {
                    Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            } else {
                Text(
                    text = block.arguments.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ContentBlock.Unknown -> Text(
            text = "[不支持的内容块：${block.type}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 思考过程块（用户 m22856 反馈「对话的气泡中总是掺杂一些思考过程的文字…这些我都不太想
 * 看到出现在对话中」）。
 *
 * **默认折叠**，只留一行「思考过程 · N 字」；点一下才展开。理由：reasoning 是模型的
 * 内部推理，不是给用户读的正文，但它偶尔有排查价值（「模型为什么这么做」），所以不删、
 * 只收起。桌面端 Web UI 同样把 reasoning 收在可折叠区域里。
 *
 * **为什么展开状态不挂文本前缀**：第一版写成 `rememberSaveable(text.take(64))`，想让
 * 同一段推理在流式增量之间保持展开。但 `take(64)` 对**短于 64 字的推理等于整段文本**，
 * 每个 token 都会换键 → 用户展开后立刻被折回去（`ReasoningBlockRenderTest`
 * 「流式增长时折叠状态保持」钉住这条）。改成不带输入键：状态绑定到**组合槽位**，
 * 而列表用 `items(key = …)` 给每条消息稳定槽位，既不会在流式中丢状态，也不会串到别的消息。
 *
 * ⚠️ 这里只能承诺「同一条 `LiveAttempt` 更新期间（item 未被回收）保持展开态」。
 * item 滚出可视区后 provider 被注销，`rememberSaveable` 的 `expanded` 随之丢失，
 * 长思考滚走再回来会回到折叠。行为可接受（默认折叠本来就是既定期望），但**不要**
 * 把注释写成「回收时按槽位保留」——那不成立（R012 P3-1）。
 *
 * `internal` 同 [Composer]/[QueuedStrip]：这是用户反馈的修复本体，必须有渲染测试
 * 驱动它，确认「默认不显示正文」与「点一下才显示」两条契约。
 */
@Composable
internal fun ReasoningBlock(text: String) {
    if (text.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 同 [GroupRow]：R017 / D47 改实底 + 1dp 描边（原来是 surfaceVariant@0.45f）。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = groupShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                // chevron 旋转而不是换字符，同 [GroupRow]：`▸`/`▾` 字形宽度不同，
                // 切换时后面的标题会被推着跳一下。R017 同样换成图标。
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(Motion.FAST, easing = Motion.Emphasized),
                    label = "reasoningChevron",
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                )
                Text(
                    text = "思考过程 · ${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        // 思考过程动辄上万字（同一个 ChatItem 的 reasoning 会一直累积）。
                        // 不封顶的话整块文字进内存后被 Measurer 一次量完，
                        // 低端机上直接 OOM / ANR。5000 字足够看清模型在想什么。
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 聊天页的错误横幅。三类错误（连接 / 发送 / 翻页）共用这一个形态，区别只在
 * [actionLabel] 与 [onAction]——它们的重试动作必须分开，见调用处注释。
 *
 * 用 `AnimatedVisibility` 而不是 `if (text != null)`：横幅出现在列表**上方**，
 * 硬切出现会把列表内容猛地顶下去一截；竖向展开让「多了一行」这件事有过程。
 * [text] 为 null 时整条不占位（`AnimatedVisibility` 的 visible=false 不留高度）。
 */
@Composable
private fun ChatErrorBanner(
    text: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * 把一段 Markdown 文本解析成块，并按文本记忆。
 *
 * 用 [MdStream] 而不是每次直接 `MdParser.parse`：流式生成时文本每几十毫秒就变一次，
 * 整段重解析是 O(n²)（桌面端同样只在尾部重解析）。`remember` 的键是**文本本身**，
 * 所以块被复用、列表滚动回来时不会重新解析。
 */
@Composable
private fun rememberMarkdown(text: String): List<com.revxzxcx.remotewebapp.ui.markdown.MdBlock> {
    val stream = remember { MdStream() }
    return remember(text) { stream.update(text) }
}

/** 正在流式生成的预览气泡。 */
@Composable
private fun LiveRow(live: LiveAttempt) {
    // background = null：流式预览与已落盘的助手消息**必须同构**。早先这里给了
    // `surfaceVariant`，用户看到的画面是「正在生成的那条带底色、生成完底色消失」，
    // 等于每一次回复结束都闪一下。桌面端同样把两者渲染成同一种形态，「正在生成」
    // 由 composer 的「生成中…」占位与顶栏「中止」表达，不靠气泡底色。
    //
    // R017：两者改用**导轨颜色**区分——已落盘用 outline（沉），流式用 primary@35%
    // （浮），再加 token 脉冲。底色同构这条铁律不变：变的只是那条 2dp 的线。
    Bubble(
        alignEnd = false,
        background = null,
        time = 0L,
        now = 0L,
        wide = true,
        railColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        pulseKey = live.text.length,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live.reasoning.isNotEmpty()) {
                // 与已落盘的助手消息同一套形态：思考过程默认收起（用户 m22856）。
                ReasoningBlock(live.reasoning)
            }
            if (live.text.isNotEmpty()) {
                // 流式正文同样走 Markdown，且用同一个增量解析器：未闭合的围栏会被
                // 当成「还在输出的代码块」正常显示，不会退化成一段乱码。
                MdBlocks(
                    blocks = rememberMarkdown(live.text),
                    onLinkClick = {},
                    onFilePathClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            live.toolArgs.forEach { (id, args) ->
                // 与折叠分组用同一个函数（桌面端 `liveToolDetail`）：进行中的工具只显示
                // 一行「文件名 / 命令 / 搜索词」，而不是把半截 JSON 参数原样倒出来。
                // 流式过程中 `args` 常常是未闭合的 JSON，倒出来用户根本读不了。
                Text(
                    text = liveToolDetail(live.toolNames[id] ?: id.take(8), args),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 与左侧导轨对齐：Bubble 的无底色分支在内容前插了 2dp 导轨 + 8dp
                    // 间距，这里补 2dp 让等宽字与导轨左缘对齐（R017）。
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            if (live.text.isEmpty() && live.reasoning.isEmpty() && live.toolArgs.isEmpty()) {
                // R017：流式占位从纯文字「正在生成…」换成均衡器 + 文字。用户 m26446
                // 明确要求「流式对话可以用动态图标以及动效表示实际的生成交互」——一个
                // 会动的三柱图标比静止的省略号更能表达「正在吐字」，且与折叠分组、
                // 连接横幅共用同一个组件，全 App 只有一种「运行中」的视觉语言。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GeneratingGlyph(
                        size = GlyphSize.SM,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "正在生成…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * 排队区（R031 重构，用户 m45580 第 3 条）。
 *
 * ## 三种形态
 *  - **0 条** → 整块不出现。
 *  - **1 条** → 显示这一条的内容（最多 2 行）+ 右侧三个**图标**按钮（插队 / 编辑 / 删除）。
 *  - **≥2 条** → 收起成**固定 44dp 一行**，只显示「排队中 · N 条」与展开箭头，
 *    **不显示任何消息内容**（用户原话：「超过一条信息则不展示实际信息内容」）。
 *    点开后才逐条展开，每条同样带三个图标按钮，列表限高滚动。
 *
 * ## 为什么不再用原来的气泡形态
 * 旧实现每条都是一整个 `Bubble`（带时间、footer 文案、三个文字按钮）。三条就把
 * composer 上方吃掉 200dp 以上，而且文字按钮每个 48dp 触控区、三个并排 144dp，
 * 比正文还宽。改成「单条 68dp / 多条 44dp」后，底部堆叠给消息区留出了空间。
 *
 * ## 与任务胶囊互斥展开
 * [expanded] 由调用方用一个枚举控制，展开排队时会自动收起任务列表——两个都展开
 * 总高可达 548dp，必然把消息区压没（见 `DockTarget` 的 KDoc）。
 *
 * @param expanded 排队列表是否展开。**仅多条时有效**（单条不需要展开）。
 * @param onToggleExpanded 点那一行的展开/收起。
 */
@Composable
internal fun QueuedStrip(
    messages: List<QueuedMessage>,
    generating: Boolean,
    now: Long,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEdit: (QueuedMessage, String) -> Unit,
    onRemove: (QueuedMessage) -> Unit,
    onSteer: (QueuedMessage) -> Unit,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    // 整条带竖向展开动画：排队条在 composer **上方**，硬切会让输入区被猛地顶上去一截。
    AnimatedVisibility(
        visible = messages.isNotEmpty(),
        enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp)) {
            if (messages.size == 1) {
                // 单条：直接显示内容，不需要展开。
                QueuedRow(
                    echo = messages.first(),
                    generating = generating,
                    onEdit = { text -> onEdit(messages.first(), text) },
                    onRemove = { onRemove(messages.first()) },
                    onSteer = { onSteer(messages.first()) },
                    onEditingChanged = onEditingChanged,
                )
            } else {
                QueuedHeaderRow(
                    count = messages.size,
                    expanded = expanded,
                    onToggle = onToggleExpanded,
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .heightIn(max = QUEUE_LIST_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(messages, key = { "queued-${it.id}" }) { message ->
                            QueuedRow(
                                echo = message,
                                generating = generating,
                                onEdit = { text -> onEdit(message, text) },
                                onRemove = { onRemove(message) },
                                onSteer = { onSteer(message) },
                                onEditingChanged = onEditingChanged,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 排队列表展开后的限高（约 3.5 条）。 */
internal val QUEUE_LIST_MAX_HEIGHT = 210.dp

/**
 * 多条排队的收起行（固定 44dp，**不显示任何内容**）。
 *
 * 左侧三张层叠小片是「有多条」的视觉隐喻，中间是「排队中 · N 条」，右侧展开箭头。
 * 整行可点。这是用户 m45580 明确要求的形态。
 */
@Composable
private fun QueuedHeaderRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(QueueHeaderTestTag)
            .height(44.dp)
            .clip(groupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, groupShape)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 层叠小片（三个偏移的圆角方块）。
        Box(Modifier.size(16.dp)) {
            for (i in 0..2) {
                Box(
                    modifier = Modifier
                        .padding(start = (i * 2).dp, top = (i * 2).dp)
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f + i * 0.28f)),
                )
            }
        }
        Text(
            text = "排队中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "$count 条",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) ChevronUpIcon else ChevronDownIcon,
            contentDescription = if (expanded) "收起排队列表" else "展开排队列表",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 排队收起行的测试标签。 */
internal const val QueueHeaderTestTag = "chat-queue-header"

/**
 * 一条排队中的消息（R031 形态：紧凑卡片 + 右侧三图标）。
 *
 * ## 三个操作按钮的可见性规则（沿用既有契约）
 *  - `confirmed == false`：本地回声还没被 `agent/inbox/spliced` 确认，拿不到 `itemId`，
 *    三个操作发不出去（服务端只认 `itemId`），所以**只显示、不给按钮**。
 *  - `插队`：只在 `generating` 时给。不生成时这条本来就排在队头，插队没有意义。
 *
 * ## 行内编辑（用户 m45632 第 5 条）
 * 编辑时把内容行换成一个**单行**输入框（可左右滑动），右侧三个按钮换成 `✓` / `×`。
 * 不再用对话框——用户明确要求「排队信息的编辑在行内，把内容限制在一行里面，
 * 用户可以左右滑动，在编辑情况下三个按钮不会显示，而是显示两个按钮」。
 *
 * ## 附件不参与编辑
 * `attachmentCount > 0` 时只显示「含 N 个附件」一小行提示（用户 m45632 第 2 条：
 * 附件数不显示 → 这里按「不显示具体数量，只在有附件时说明一句」处理；实际按用户
 * 最终决定「附件数不显示」执行，见下）。
 */
@Composable
internal fun QueuedRow(
    echo: QueuedMessage,
    generating: Boolean,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
    onSteer: () -> Unit,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val actionable = echo.confirmed

    // 把「本行是否在编辑」上报给调用方：键盘升起时排队条是否可以让位就靠它
    // （R031 独立审查 P1）。用 `DisposableEffect` 而不是 `LaunchedEffect(editing)`：
    // 本行被移出组合树（例如删掉这条排队消息）时要顺手复位，否则会永久卡在
    // `queueEditing = true`，之后键盘弹出再也不隐藏排队条。
    DisposableEffect(editing) {
        onEditingChanged(editing)
        onDispose { if (editing) onEditingChanged(false) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, groupShape)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
            )
            Text(
                text = if (echo.isAwaitingTurn) "等当前轮结束后发出" else "已插队 · 在当前轮执行",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (actionable && !editing) {
                // 三个图标按钮，每个 28dp 触控圆（比文字按钮的三个 48dp 省 96dp 宽）。
                QueueIconAction(
                    icon = SteerIcon,
                    description = "插队",
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = generating,
                    onClick = onSteer,
                )
                QueueIconAction(
                    icon = Icons.Default.Edit,
                    description = "编辑排队消息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { editing = true },
                )
                QueueIconAction(
                    icon = Icons.Default.Delete,
                    description = "删除排队消息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRemove,
                )
            }
        }

        if (editing) {
            QueueInlineEditor(
                initial = echo.text,
                onSave = { text ->
                    editing = false
                    onEdit(text)
                },
                onCancel = { editing = false },
            )
        } else {
            if (echo.text.isNotBlank()) {
                // 最多 2 行，超出省略：排队条不能因为一条长消息就撑满半屏。
                Text(
                    text = echo.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // 用户 m45632 第 2 条：附件数不显示。只说明「有附件，不可编辑」。
                Text(
                    text = "（含附件，不支持编辑）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!actionable) {
                // 还没拿到服务端 id：告诉用户为什么没有按钮，而不是让人以为坏了。
                Text(
                    text = "等待桌面端确认…",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 排队消息的行内编辑器：**单行**输入（可左右滑动）+ `✓` / `×`。
 *
 * 单行是用户 m45632 的明确要求（「把内容限制在一行里面，用户可以左右滑动」）。
 * 换行会把排队条从 68dp 撑成三行，多条时把整条队列挤出屏幕。
 */
@Composable
private fun QueueInlineEditor(
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp)
                .horizontalScroll(scroll)
                .testTag(QueueEditFieldTestTag),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
        )
        QueueIconAction(
            icon = Icons.Default.Check,
            description = "保存排队消息",
            tint = LocalStatusRunning.current,
            onClick = { if (text.isNotBlank()) onSave(text.trim()) },
        )
        QueueIconAction(
            icon = Icons.Default.Clear,
            description = "取消编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onCancel,
        )
    }
}

/** 排队行内编辑输入框的测试标签。 */
internal const val QueueEditFieldTestTag = "chat-queue-edit-field"

/** 排队区一个 28dp 的图标动作按钮。 */
@Composable
private fun QueueIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 统一的消息外壳：靠左或靠右、限宽、带时间脚注。
 *
 * ## 助手侧不设背景（R016，用户 m25865「回复的气泡不设置背景更好一些」）
 *
 * [background] 传 null 时走「无底色」形态：没有 Surface、没有圆角、没有内边距，
 * 内容直接铺在列表背景上。理由是助手消息的内容本身已经有层次（Markdown 标题、
 * 代码块、折叠分组、分隔线），再套一层色块只会把这些层次糊成一块。桌面端标准
 * 模式的助手消息同样不带底色。
 *
 * 用户气泡**保留**底色——没有底色就无法一眼分开「我说的」和「它说的」，那是对话
 * 界面最基本的可读性。底色用 [bubbleTailShape] 的非对称圆角（右下角收窄到 6dp）
 * 暗示气泡从哪一侧冒出，比画一个小三角干净。
 *
 * ## 为什么背景是 `Color?` 而不是 `Boolean`
 *
 * 早先是 `background: Color` + `alignEnd`，调用方为了「不要背景」只能传
 * `Color.Transparent`——那仍然会建一个 Surface、仍然有内边距，视觉上没差别但
 * 布局上多了一层。可空让「没有背景」成为一个真实状态而不是一个透明颜色。
 */
@Composable
internal fun Bubble(
    alignEnd: Boolean,
    background: Color?,
    time: Long,
    now: Long,
    // `modifier` 必须是第一个可选参数（lint `ModifierParameter`）：调用方全是命名参数，
    // 调换顺序不影响任何调用点，只是让这条告警回到基线。
    modifier: Modifier = Modifier,
    footer: String? = null,
    /** 助手气泡里有代码块/表格时需要更宽，否则 320dp 会把它们挤成一条条。 */
    wide: Boolean = false,
    /**
     * 无底色形态下左侧导轨的颜色（R017）。
     *
     * 传 null = 不画导轨；默认 `outline`。已落盘的助手消息用 outline（沉下去，
     * 不抢注意力），流式预览用 `primary@35%`（浮起来，让用户知道这一条正在长）。
     *
     * @param pulseKey 变值时让导轨弹一下宽（2dp → 3dp → 2dp），用于流式 token 脉冲。
     *     只在 [railColor] 非 null 时有意义。
     */
    railColor: Color? = MaterialTheme.colorScheme.outline,
    pulseKey: Any? = null,
    /**
     * 消息内容是否可长按选择。
     *
     * 默认 true：正文用 [SelectionContainer] 包住，长按即出系统的选择手柄与「复制」菜单
     * （用户 m01082 修正：「用户可以长按复制界面中的内容」）。
     *
     * 工具调用 / 工具结果卡传 false——它们的展开体动辄几千字的原始参数，包进选择容器会让
     * 整张卡吞掉点击事件，而那里本来也没有需要逐句复制的正文。
     *
     * 注意 `ChatItem.Notice` 与 `ChatItem.Changes` **不经过本组件**（前者是分隔行、后者是
     * `ChangesCard`），它们与 `selectable` 无关，不要以为这里漏传了参数。
     */
    selectable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val caption = buildString {
        if (time > 0L) append(TimeText.relative(time, now))
        if (footer != null) {
            if (isNotEmpty()) append(' ')
            append(footer)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        if (background == null) {
            // R017：无底色形态加一道 2dp 左侧导轨。助手消息去掉底色之后（R016），
            // 靠左的内容与列表背景之间只剩 2dp 间距，在一屏几十条里「这一条从哪儿
            // 开始」需要用户自己扫。导轨是 2dp 圆角实线，比再套回一层半透明底轻得
            // 多，也保留了 R016 的层次。
            //
            // 为什么不是每行都画：用户气泡有底色 + 非对称圆角，已经自带边界；
            // 只有助手侧需要这条线。所以导轨挂在 `background == null` 分支里。
            //
            // token 脉冲（[pulseKey]）：导轨宽度 2dp → 3dp → 2dp。走 `Animatable`
            // 而不是 `animateDpAsState`，因为要在每次脉冲时先 snapTo 再 animateTo，
            // 而 animateDpAsState 只有「目标值变了才动」一种触发方式——连续两个
            // token 间隔短于动画时长时它会把第二次变化直接吞掉。
            val railPulse = remember { Animatable(2f) }
            LaunchedEffect(pulseKey) {
                if (pulseKey != null && railColor != null) {
                    railPulse.snapTo(3f)
                    railPulse.animateTo(2f, tween(300, easing = Motion.Emphasized))
                }
            }
            // 导轨铺满整条消息（用户 m44550 第一张图：旧实现只画出短一截）。
            //
            // 🔴 用 `drawBehind` 画，**不要**用「一个 Box + fillMaxHeight + IntrinsicSize」
            // 那一套。两次翻车的记录都在这里，免得下一个人再走一遍：
            //
            // ① `Box(Modifier.width(2.dp).fillMaxHeight())` 单独用 → 导轨高度为 0。
            //    这一行住在 `LazyColumn` 的 item 里，高度约束是 `Constraints.Infinity`，
            //    而 `fillMaxHeight()` 的语义是「有上界取 maxHeight，否则取 minHeight」= 0。
            //
            // ② 再加 `height(IntrinsicSize.Min)` 把高度改成固有高度 → 导轨能显示了，
            //    但**内容会被截断**（用户 m45428 第 7 条：「比较长的有导轨的文字输出，
            //    有被截断的情况…后面的都看不到了」）。原因：`IntrinsicSize.Min` 把行高
            //    固定成「子项固有高度」，而 Markdown 正文/代码块的固有高度只是**估算**，
            //    实际排版更高时超出部分直接被裁掉——内容越长越明显。
            //
            // `drawBehind` 在**布局完成之后**才画，`size.height` 是实测高度：
            // 导轨永远等于真实内容高度，且不参与测量、不影响内容排布。
            //
            // 代价：导轨不再是一个独立节点，所以 `BubbleRailRenderTest` 改用
            // 「扫描像素」而不是「查 testTag 节点」来验证——见该测试的 KDoc。
            //
            // token 脉冲（`railPulse`）仍然有效：宽度在 `drawBehind` 里读 `railPulse.value`，
            // 每帧重画，与「用一个 Box 的 width」等价。
            Row(
                modifier = Modifier
                    .then(if (wide) Modifier.fillMaxWidth() else Modifier.widthIn(max = 340.dp))
                    .testTag(ChatRailTestTag)
                    .drawBehind {
                        if (railColor != null) {
                            val inset = 3.dp.toPx()
                            drawRoundRect(
                                color = railColor,
                                topLeft = Offset(0f, inset),
                                size = Size(
                                    width = railPulse.value.dp.toPx(),
                                    height = (size.height - inset * 2).coerceAtLeast(0f),
                                ),
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        // start = 2dp（导轨宽）+ 8dp（间距）：导轨画在 Row 的 x=0 处，
                        // 内容必须让开它。
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectable) {
                        SelectionContainer(modifier = Modifier.testTag(MessageSelectableTestTag)) {
                            content()
                        }
                    } else {
                        content()
                    }
                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        } else {
            Surface(
                color = background,
                shape = if (alignEnd) bubbleTailShape else bubbleShape,
                modifier = if (wide) {
                    Modifier.fillMaxWidth(0.95f)
                } else {
                    Modifier.widthIn(max = 300.dp)
                },
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectable) {
                        SelectionContainer(modifier = Modifier.testTag(MessageSelectableTestTag)) {
                            content()
                        }
                    } else {
                        content()
                    }
                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}


/**
 * 导轨容器（`Bubble` 里那个带 `drawBehind` 的 `Row`）的测试标签。
 *
 * 导轨本身是 `drawBehind` 画的、不是语义节点，查不到；但**容器的高度**可以量——
 * 「容器高度是否容得下最后一行内容」正是 `height(IntrinsicSize.Min)` 截断与否的判据。
 */
/**
 * 底部「可展开区」的目标（R031）。
 *
 * 为什么用一个枚举而不是两个布尔：任务列表与排队列表**互斥展开**（用户 m45632）。
 * 两个布尔能表示 (true, true) 这种非法状态，而枚举只有一个值——从类型上就不可能
 * 同时展开两个。
 *
 * 互斥的原因不是洁癖：两者都展开时总高 = 任务面板 196 + 排队列表 210 + 胶囊 34 +
 * 排队行 44 + 输入框 ≈ 548dp，而 800dp 机型扣掉顶栏与目标横幅后只剩约 480dp，
 * **必然把消息区压没甚至把输入框顶出屏幕**。
 */
internal enum class DockTarget { None, Tasks, Queue }

/**
 * 「输入法让位」的判定：键盘升起时目标横幅 / 任务胶囊 / 排队条一律隐藏，
 * **唯一例外**是正在行内编辑的那一块（把它的输入框移出组合树会连焦点一起没掉）。
 *
 * 抽成独立函数是为了让 [ImeDockVisibilityTest] 能守住这条契约。R031 设备验收时
 * 在 MuMu 上验不动这一点：模拟器挂着硬件键盘（`dumpsys input` 里有 `Xiaomi Keyboard`），
 * 输入法窗口被摆到 `mSurfacePosition=Point(0,2400)` 即屏幕外，`mInputShown` 为真
 * 但 ime inset 恒为 0——「键盘占掉高度」这个前提在模拟器上根本不成立。
 */
internal fun dockVisible(imeVisible: Boolean, editing: Boolean): Boolean = !imeVisible || editing


internal const val ChatRailTestTag = "chat-bubble-rail"

/**
 * 「正文可长按选择」那一层 [SelectionContainer] 的测试标签。
 *
 * `SelectionContainer` 会把 modifier 应用到自己那个布局节点上，所以 tag 可查（实测
 * 配置为 `TestTag` + `Focused` + `RequestFocus`）。**但子树里没有任何节点带
 * `SemanticsActions.CopyText`**——想靠「有没有 CopyText」判断可选性是行不通的。
 *
 * 「这一条消息是否真的可选」的判据因此是 `RequestFocus`：普通容器没有这个动作，
 * 换成 `Box` 后断言即变红（已做变异验证）。
 */
internal const val MessageSelectableTestTag = "chat-message-selectable"
