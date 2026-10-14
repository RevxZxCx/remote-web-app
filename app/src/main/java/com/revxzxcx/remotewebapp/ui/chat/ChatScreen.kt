package com.revxzxcx.remotewebapp.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import com.revxzxcx.remotewebapp.core.protocol.isHealthy
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.ImageMeasure
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
import com.revxzxcx.remotewebapp.ui.common.ConnectionBanner
import com.revxzxcx.remotewebapp.ui.common.decodeScaled
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.home.TimeText
import com.revxzxcx.remotewebapp.ui.home.rememberNow
import com.revxzxcx.remotewebapp.ui.markdown.MdBlocks
import com.revxzxcx.remotewebapp.ui.markdown.MdStream

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

    // 模型选择器的显示开关。目录本身由 ViewModel 拉（[ChatUiState.catalog]），
    // 这里只负责「弹不弹」——目录没到时对话框显示为空而不是崩。
    var showModelPicker by rememberSaveable { mutableStateOf(false) }

    // 附件提示由 ViewModel 承载（R007 P2-3）：上传整条链跑在 viewModelScope 里，
    // 旋转屏幕既不取消上传也不丢提示。早先是这里的 `remember`，旋转就被重置。
    val attachNotice by viewModel.attachNotice.collectAsState()
    val pending by viewModel.pending.collectAsState()

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
        state.items.size +
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
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (state.generating) {
                        TextButton(onClick = viewModel::cancel) { Text("中止") }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // 排队队列（用户 m21177 第 4 条反馈）：位置在模型条与输入区之间，
                // 即「发送框上方」。每条一个气泡，带 编辑 / 删除 / 插队 三个操作。
                QueuedStrip(
                    messages = state.queued,
                    generating = state.generating,
                    now = now,
                    onEdit = viewModel::editQueued,
                    onRemove = viewModel::removeQueued,
                    onSteer = viewModel::steerQueued,
                )
                ModelBar(
                    model = state.model,
                    loading = state.catalogLoading,
                    error = state.modelError,
                    onOpen = {
                        viewModel.openModelPicker()
                        showModelPicker = true
                    },
                )
                Composer(
                    draft = draft,
                    generating = state.generating,
                    sending = state.sending,
                    pending = pending,
                    attachNotice = attachNotice,
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
                    onRepair = onUnpair,
                    onRetry = viewModel::retry,
                )
            }

            if (state.fromCache) {
                CacheNotice("正在同步：当前显示的是上次缓存的会话内容。")
            }

            // 三类错误各自带自己的重试动作（R006 P3-6）：共用一个字段时，「发送失败」的
            // 错误条上那个「重试」也去重启整条 follow 流——比失败本身重得多，还不会重新
            // 发送那条消息。用户点了按钮发现什么都没发生。
            state.error?.let { error ->
                Surface(color = Color(0xFF3A1E22), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::retry) { Text("重试") }
                    }
                }
            }

            state.sendError?.let { error ->
                Surface(color = Color(0xFF3A1E22), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::retrySend) { Text("重试") }
                    }
                }
            }

            state.pageError?.let { error ->
                Surface(color = Color(0xFF3A1E22), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::retryLoadMore) { Text("重试") }
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
                        items(state.items, key = { it.seq }) { item ->
                            ChatItemRow(
                                item = item,
                                now = now,
                                changes = state.changes[item.seq],
                                changesLoading = item.seq in state.changesLoading,
                                onOpenFile = onOpenFile,
                                onRetryChanges = viewModel::retryChanges,
                                attachments = viewModel.attachments,
                                sessionId = viewModel.sessionId,
                            )
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
                            state.items.forEach { add(it.seq) }
                            if (state.live != null) add(CHAT_LIVE_KEY)
                        }
                    },
                    userItemIndices = {
                        val base = if (state.loadingMore) 1 else 0
                        state.items.mapIndexedNotNull { index, item ->
                            if (item is ChatItem.User) base + index else null
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
}

/**
 * 输入区上方的模型条（需求 FR-5.5 模型切换的入口）。
 *
 * 为什么放在这里而不是顶栏：模型名 `provider/model · effort` 有 20–40 个字符，
 * 顶栏要和标题、`cwd`、中止按钮抢空间；输入区上方那一行本来就是「与发送相关的上下文」，
 * 用户改模型的时机就在打字前。
 *
 * 拉取失败时这里同时也是错误展示位（[error]）：目录拉不到不该弹对话框打断用户，
 * 但也不能让他以为「这个 App 没有模型切换功能」。
 */
@Composable
private fun ModelBar(
    model: ModelRef?,
    loading: Boolean,
    error: String?,
    onOpen: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpen, enabled = !loading) {
                Text(
                    text = when {
                        loading -> "正在读取模型列表…"
                        model != null -> "模型 · ${model.label}"
                        else -> "模型 · 桌面端默认"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB4AB),
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    maxLines = 1,
                )
            }
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
 */
@Composable
internal fun Composer(
    draft: String,
    generating: Boolean,
    sending: Boolean,
    pending: List<PromptAttachment>,
    attachNotice: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onAttachImage: (ByteArray, String, String?, ImageMeasure) -> String?,
    onAttachFile: (ByteArray, String?) -> Unit,
    onAttachNotice: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAttachSheet by remember { mutableStateOf(false) }

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

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (pending.isNotEmpty()) {
                PendingAttachmentsRow(pending = pending, onRemove = onRemoveAttachment)
            }
            attachNotice?.let { notice ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB4AB),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    TextButton(onClick = { onAttachNotice(null) }) { Text("知道了") }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextButton(
                    onClick = { showAttachSheet = true },
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { Text("＋") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .heightIn(min = 48.dp, max = 160.dp),
                    placeholder = {
                        Text(if (generating) "生成中…（发送会排到当前轮之后）" else "发消息…")
                    },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Button(
                    onClick = onSend,
                    enabled = (draft.isNotBlank() || pending.isNotEmpty()) && !sending,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                ) { Text(if (sending) "发送中" else "发送") }
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
    val bytes = runCatching {
        open()?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null
    if (bytes.isEmpty()) return@withContext null
    Triple(bytes, mediaType ?: "application/octet-stream", displayName)
}

/**
 * 从 content Uri 取出 `(输入流工厂, MIME, 展示名)`。
 *
 * 单独一层而不是塞进 [loadUriBytes]：查询 `OpenableColumns` 与 `getType` 都是会抛的
 * 系统调用，包在 `runCatching` 里各管各的失败，读字节那段则完全不被它们影响。
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
@Composable
private fun ChatItemRow(
    item: ChatItem,
    now: Long,
    changes: ChangesEntry?,
    changesLoading: Boolean,
    onOpenFile: (FileTarget) -> Unit,
    onRetryChanges: (Long) -> Unit,
    attachments: SessionAttachmentRepository,
    sessionId: String,
) {
    when (item) {
        is ChatItem.User -> Bubble(
            alignEnd = true,
            background = MaterialTheme.colorScheme.primaryContainer,
            time = item.time,
            now = now,
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
            Bubble(
                alignEnd = false,
                background = MaterialTheme.colorScheme.surfaceVariant,
                time = item.time,
                now = now,
                footer = if (item.interrupted) "（已中止）" else null,
                // 助手正文常有代码块与表格，320dp 太窄。
                wide = true,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.forEach { block -> BlockView(block, onOpenFile, attachments, sessionId) }
                }
            }
        }

        is ChatItem.ToolCall -> Bubble(
            alignEnd = false,
            background = MaterialTheme.colorScheme.surfaceVariant,
            time = item.time,
            now = now,
        ) {
            ToolCallCard(item, onOpenFile = { path -> onOpenFile(FileTarget(path)) })
        }

        is ChatItem.ToolResult -> Bubble(
            alignEnd = false,
            background = if (item.isError) Color(0xFF3A1E22) else MaterialTheme.colorScheme.surfaceVariant,
            time = item.time,
            now = now,
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

        is ChatItem.Notice -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = item.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = if (expanded) {
                        "▾ 思考过程 · ${text.length} 字"
                    } else {
                        "▸ 思考过程 · ${text.length} 字"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
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
    Bubble(
        alignEnd = false,
        background = MaterialTheme.colorScheme.surfaceVariant,
        time = 0L,
        now = 0L,
        wide = true,
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
                Text(
                    text = "工具 ${live.toolNames[id] ?: id.take(8)} · $args",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (live.text.isEmpty() && live.reasoning.isEmpty() && live.toolArgs.isEmpty()) {
                Text(
                    text = "正在生成…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 排队队列整条（用户 m21177 第 4 条反馈的本体）。
 *
 * 位置在**输入区上方**而不是消息列表里：反馈原话「如果在对话中输入发送，发送框上方会
 * 多一个气泡…显示实际的输入，表示排队中」。除了位置，还把 R009「刻意不做撤回按钮」
 * 的决定推翻——那时以为远程客户端拿不到 itemId，实际它在 `inbox` 投影与
 * `agent/inbox/spliced` 事件里（见 [QueuedMessage] 的 KDoc），所以编辑/删除/插队
 * 三个操作都能真的打到服务端。
 *
 * 多条时用 `LazyColumn` 限高（最多约三条高）：三条以上仍然要能看见最后一条，
 * 否则用户不知道刚发出去的那条在哪。
 *
 * `internal` 同 `Composer`/`SessionDrawer`：这是用户反馈的修复本体，必须有渲染测试
 * 驱动它，确认三个按钮真的存在且绑对了回调。
 */
@Composable
internal fun QueuedStrip(
    messages: List<QueuedMessage>,
    generating: Boolean,
    now: Long,
    onEdit: (QueuedMessage, String) -> Unit,
    onRemove: (QueuedMessage) -> Unit,
    onSteer: (QueuedMessage) -> Unit,
) {
    if (messages.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = if (messages.size == 1) "排队中 1 条" else "排队中 ${messages.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 168.dp)) {
                items(messages, key = { "queued-${it.id}" }) { message ->
                    QueuedRow(
                        echo = message,
                        now = now,
                        generating = generating,
                        onEdit = { text -> onEdit(message, text) },
                        onRemove = { onRemove(message) },
                        onSteer = { onSteer(message) },
                    )
                }
            }
        }
    }
}

/**
 * 一条排队中的消息。
 *
 * 三个操作按钮的可见性规则（不是洁癖，是契约）：
 *  - `confirmed == false`：本地回声还没被 `agent/inbox/spliced` 确认，拿不到 itemId，
 *    此时三个操作都发不出去（服务端只认 `itemId`），所以只显示、不给按钮。
 *  - `插队`：只在 `generating` 时给。不生成时这条本来就排在空队列头部，下一个轮次
 *    就是它，插队没有意义；服务端此时也会回 `session/steer-unavailable`。
 *
 * 编辑走对话框而不是行内输入框：气泡只有一行高，行内输入会把它撑成三行，
 * 三条以上时把整条队列挤出屏幕（用户 m21177 的原话是「多一个气泡」，不是多一个表单）。
 */
@Composable
internal fun QueuedRow(
    echo: QueuedMessage,
    now: Long,
    generating: Boolean,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
    onSteer: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val actionable = echo.confirmed
    Bubble(
        alignEnd = true,
        background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        time = echo.enqueuedAt,
        now = now,
        footer = buildString {
            append(if (echo.isAwaitingTurn) "排队中 · 等当前轮结束后发出" else "已插队 · 在当前轮执行")
            if (echo.attachmentCount > 0) append(" · 含 ${echo.attachmentCount} 个附件")
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                echo.text.isNotBlank() -> MdBlocks(
                    blocks = rememberMarkdown(echo.text),
                    onLinkClick = {},
                    onFilePathClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )

                echo.attachmentCount > 0 -> Text(
                    text = "（${echo.attachmentCount} 个附件）",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (actionable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = true }) { Text("编辑") }
                    // `steer` 只在生成中给（见 KDoc）。
                    if (generating) TextButton(onClick = onSteer) { Text("插队") }
                    TextButton(onClick = onRemove) { Text("删除") }
                }
            } else {
                // 还没拿到服务端 id：告诉用户为什么没有按钮，而不是让人以为坏了。
                Text(
                    text = "等待桌面端确认…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (editing) {
        var draft by rememberSaveable { mutableStateOf(echo.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑排队消息") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("消息内容") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editing = false
                        onEdit(draft)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}

/** 统一的气泡外壳：靠左或靠右、限宽、带时间脚注。 */
@Composable
private fun Bubble(
    alignEnd: Boolean,
    background: Color,
    time: Long,
    now: Long,
    footer: String? = null,
    /** 助手气泡里有代码块/表格时需要更宽，否则 320dp 会把它们挤成一条条。 */
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(12.dp),
            modifier = if (wide) {
                Modifier.fillMaxWidth(0.95f)
            } else {
                Modifier.widthIn(max = 320.dp)
            },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                content()
                val caption = buildString {
                    if (time > 0L) append(TimeText.relative(time, now))
                    if (footer != null) {
                        if (isNotEmpty()) append(' ')
                        append(footer)
                    }
                }
                if (caption.isNotBlank()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
