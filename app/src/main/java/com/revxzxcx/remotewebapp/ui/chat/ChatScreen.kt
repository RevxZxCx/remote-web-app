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
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ContentBlock
import com.revxzxcx.remotewebapp.core.session.LiveAttempt
import com.revxzxcx.remotewebapp.core.session.visibleAssistantBlocks
import com.revxzxcx.remotewebapp.ui.home.TimeText
import com.revxzxcx.remotewebapp.ui.home.rememberNow

/**
 * 聊天页。
 *
 * M3 只做**纯文本**渲染（markdown / 代码高亮 / 工具卡片折叠 / 改动文件卡片留到 M3.5，
 * 见 `需求文档.md` §9 里程碑）。工具调用与结果以等宽文本折叠展示，保证「能看到做了什么」
 * 这个基本诉求在 M3 就成立。
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
                        ChatItemRow(item, now)
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
private fun ChatItemRow(item: ChatItem, now: Long) {
    when (item) {
        is ChatItem.User -> Bubble(
            alignEnd = true,
            background = MaterialTheme.colorScheme.primaryContainer,
            time = item.time,
            now = now,
        ) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
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
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.forEach { block -> BlockView(block) }
                }
            }
        }

        is ChatItem.ToolCall -> Bubble(
            alignEnd = false,
            background = MaterialTheme.colorScheme.surfaceVariant,
            time = item.time,
            now = now,
        ) {
            Column {
                Text(
                    text = "工具 · ${item.name}",
                    style = MaterialTheme.typography.labelMedium,
                )
                // `arguments` 是 JSON 字符串；M3 直接原样展示，M3.5 会解析出可点文件行。
                if (item.arguments.isNotBlank()) {
                    Text(
                        text = item.arguments.take(600),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ChatItem.ToolResult -> Bubble(
            alignEnd = false,
            background = if (item.isError) Color(0xFF3A1E22) else MaterialTheme.colorScheme.surfaceVariant,
            time = item.time,
            now = now,
        ) {
            Text(
                text = item.text.ifBlank { "（无输出）" }.take(1200),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (item.isError) Color(0xFFFFB4AB) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
private fun BlockView(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> Text(block.text, style = MaterialTheme.typography.bodyMedium)

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

        is ContentBlock.ToolCall -> Column {
            Text("工具 · ${block.name}", style = MaterialTheme.typography.labelMedium)
            Text(
                text = block.arguments.take(600),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ContentBlock.Unknown -> Text(
            text = "[不支持的内容块：${block.type}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 正在流式生成的预览气泡。 */
@Composable
private fun LiveRow(live: LiveAttempt) {
    Bubble(
        alignEnd = false,
        background = MaterialTheme.colorScheme.surfaceVariant,
        time = 0L,
        now = 0L,
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
                Text(live.text, style = MaterialTheme.typography.bodyMedium)
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
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
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
