package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.LiveAttempt
import com.revxzxcx.remotewebapp.core.session.ToolFilePaths
import com.revxzxcx.remotewebapp.core.session.visibleAssistantBlocks
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
) {
    val state by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val listState = rememberLazyListState()
    val now = rememberNow()

    // 用户是否贴在底部。用 snapshotFlow 观察而不是每次重组都读，避免无谓重组。
    val atBottom = remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                // 不能继续向下滚 = 已经在底部。
                atBottom.value = !canScrollForward
            }
    }

    // 条目数量或流式文本变化时，若用户在底部就跟随。
    //
    // 滚动目标必须是**最后一行**：列表里除了 [ChatUiState.items] 还可能有
    // 「加载更早」的转圈行和流式预览行，只按 `items.size` 算会少滚一两行，
    // 表现是新消息停在屏幕下沿看不全。
    val itemCount = state.items.size
    val liveLength = state.live?.text?.length ?: 0
    val lastIndex = itemCount +
        (if (state.loadingMore) 1 else 0) +
        (if (state.live != null) 1 else 0) - 1
    LaunchedEffect(lastIndex, liveLength) {
        if (atBottom.value && lastIndex >= 0) {
            listState.scrollToItem(lastIndex)
        }
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
            Composer(
                draft = draft,
                generating = state.generating,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                        )
                    }
                    state.live?.let { live ->
                        item(key = "live") { LiveRow(live) }
                    }
                }
            }
        }
    }
}

/** 底部输入区。 */
@Composable
private fun Composer(
    draft: String,
    generating: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                placeholder = {
                    Text(if (generating) "生成中…（发送会排到当前轮之后）" else "发消息…")
                },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            ) { Text("发送") }
        }
    }
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
                    visible.forEach { block -> BlockView(block, onOpenFile) }
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
private fun BlockView(block: ContentBlock, onOpenFile: (FileTarget) -> Unit) {
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

        is ContentBlock.Image -> Text(
            text = "[图片 ${block.name ?: block.attachmentId.take(8)}${if (block.offloaded) " · 已卸载" else ""}]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
