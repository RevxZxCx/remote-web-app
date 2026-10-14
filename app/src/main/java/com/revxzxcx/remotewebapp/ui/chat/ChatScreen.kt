package com.revxzxcx.remotewebapp.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.core.session.ToolFilePaths
import com.revxzxcx.remotewebapp.core.session.message
import com.revxzxcx.remotewebapp.core.session.visibleAssistantBlocks
import com.revxzxcx.remotewebapp.ui.common.CacheNotice
import com.revxzxcx.remotewebapp.ui.common.ConnectionBanner
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

    // 用户是否贴在底部。用 snapshotFlow 观察而不是每次重组都读，避免无谓重组。
    //
    // 必须是 `rememberSaveable`（R007 P2-4）：`listState` 本身经 rememberSaveable 恢复成
    // 新实例，`LaunchedEffect(listState, lastIndex)` 随之重启、snapshotFlow 立即发射当前值；
    // 若 `atBottom` 也是普通 `remember`，旋转后它重置成 true，于是用户正在上翻历史时
    // 一转屏就被拉回底部。
    var atBottom by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                // 不能继续向下滚 = 已经在底部。
                atBottom = !canScrollForward
            }
    }

    // 条目数量或流式文本变化时，若用户在底部就跟随。
    //
    // 滚动目标必须是**最后一行**：列表里除了 [ChatUiState.items] 还可能有
    // 「加载更早」的转圈行和流式预览行，只按 `items.size` 算会少滚一两行，
    // 表现是新消息停在屏幕下沿看不全。
    //
    // key **只含 `lastIndex`**：流式生成时文本每个 token 变一次，把它当 key 会不停取消正在
    // 进行的滚动（`scrollToItem` 是挂起函数），在高频 token 下表现为抖动甚至完全跟不上
    // （R006 P3-4）。文本增长改在同一个 effect 内用 `snapshotFlow` 收集——它天然按快照帧
    // 合并，一秒几十次 chunk 只会滚几次。
    val itemCount = state.items.size
    val lastIndex = itemCount +
        (if (state.loadingMore) 1 else 0) +
        (if (state.live != null) 1 else 0) - 1
    LaunchedEffect(listState, lastIndex) {
        if (lastIndex < 0) return@LaunchedEffect
        snapshotFlow { state.live?.text?.length ?: 0 }
            .collect { if (atBottom) listState.scrollToItem(lastIndex) }
    }

    // 滚到顶时自动加载更早历史。
    LaunchedEffect(listState, state.hasMore) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index == 0 && state.hasMore) viewModel.loadMore()
            }
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

            when {
                state.loading && state.items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.ready && state.items.isEmpty() && state.live == null -> Box(
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
                        item(key = "loading-more") {
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
                        item(key = "live") { LiveRow(live) }
                    }
                }
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
                    enabled = draft.isNotBlank() || pending.isNotEmpty(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                ) { Text("发送") }
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
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (item.kind) {
                            PromptAttachment.Kind.IMAGE -> "图片 · ${item.name ?: "未命名"}"
                            PromptAttachment.Kind.FILE -> "文件 · ${item.name ?: "未命名"}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
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
            // 用户消息里通常有粘贴的路径/命令，用 Markdown 渲染能保留行内代码与折行。
            MdBlocks(
                blocks = rememberMarkdown(item.text),
                onLinkClick = {},
                onFilePathClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
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

        is ContentBlock.Reasoning -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

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
                Text(
                    text = live.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
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
