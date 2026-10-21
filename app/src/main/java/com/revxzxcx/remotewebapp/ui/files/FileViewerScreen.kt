package com.revxzxcx.remotewebapp.ui.files

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.changes.ChangesDiff
import com.revxzxcx.remotewebapp.core.changes.ChangesLimitations
import com.revxzxcx.remotewebapp.core.changes.DiffHunk
import com.revxzxcx.remotewebapp.core.files.FileKind
import com.revxzxcx.remotewebapp.ui.chat.ADDED_COLOR
import com.revxzxcx.remotewebapp.ui.chat.DELETED_COLOR
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.common.decodeScaled
import com.revxzxcx.remotewebapp.ui.markdown.MdBlocks
import com.revxzxcx.remotewebapp.ui.markdown.MdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件查看页（需求 FR-5.5 ~ FR-5.10）。
 *
 * 两个标签页，进入时的默认页由「从哪里点进来」决定：
 *  - 从**改动文件卡片**进来 → 默认看本轮 diff（还能切到当前全文）；
 *  - 从**工具卡片**或正文里的路径进来 → 只有当前全文。
 *
 * 只能看，不能改：`dsh-api-workspace-files` 不提供任何写接口（设计决定 D15）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    viewModel: FileViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // 离开文件页就把整块字节还回去（R005 P2 补遗）：VM 挂在整个 Activity 的
    // ViewModelStore 上，`openFile = null` 只切换组合、不会 clear，图片字节可达 32 MiB。
    DisposableEffect(viewModel) {
        onDispose { viewModel.releaseMedia() }
    }

    // 滚到底部自动取下一页（FR-5.7）。只有分页文本形态才需要。
    //
    // 行数与 loadingMore 必须放进 **LaunchedEffect 的 key**，不能只放进 `snapshotFlow`
    // 的 lambda（R005 P2 补遗）：`state` 是 `collectAsState()` 出来的普通值，不是 `State`，
    // 而 `viewModel.state.value` 也不是 Compose 快照状态 —— 在 lambda 里读它们**不产生
    // 依赖**，flow 不会因此重新发射。而 `canScrollForward` 只在**变化时**发射，页短于
    // 视口时它恒为 false，追加一页也不翻转 → 自动翻页停摆。把行数作 key 让 effect 在
    // 每次追加后重启，重新求值一次 `canScrollForward`。
    LaunchedEffect(listState, state.eof, state.tab, state.lines.size, state.loadingMore) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                if (!canScrollForward && !state.loadingMore && !state.eof && state.tab == FileTab.CONTENT) {
                    viewModel.loadMore()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.target.display,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = state.absolutePath ?: state.target.path
                        if (subtitle.isNotBlank() && subtitle != state.target.display) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    // R017：与其它屏幕统一成图标钮。原来这里是 TextButton("返回")，
                    // 在「整体美观协调」的目标下是全 App 唯一一个文字返回钮。
                    AppIconButton(onClick = onBack, contentDescription = "返回") {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // 「在电脑上打开」保留为文字按钮：它是这一屏唯一的功能动作，
                    // 且带外部副作用（唤起桌面端），R017 的底线要求「破坏性/外部副作用
                    // 动作保留文字标签」——这条不在「收拢成图标」的范围内。
                    if (state.canShowDiff) {
                        TextButton(
                            onClick = viewModel::openOnDesktop,
                            enabled = !state.openingOnDesktop,
                        ) { Text(if (state.openingOnDesktop) "打开中…" else "在电脑上打开") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    action = { TextButton(onClick = viewModel::consumeMessage) { Text("好") } },
                ) { Text(message) }
            }

            // HTML 的「渲染 / 源码」两个页签（R040）。与 diff 页签不会同时出现：
            // diff 来自改动卡片、HTML 渲染来自当前全文，两条入口互斥。
            if (state.kind == FileKind.HTML && !state.canShowDiff) {
                TabRow(selectedTabIndex = if (state.tab == FileTab.RENDER) 0 else 1) {
                    Tab(
                        selected = state.tab == FileTab.RENDER,
                        onClick = { viewModel.selectTab(FileTab.RENDER) },
                        text = { Text("渲染") },
                    )
                    Tab(
                        selected = state.tab == FileTab.CONTENT,
                        onClick = { viewModel.selectTab(FileTab.CONTENT) },
                        text = { Text("源码") },
                    )
                }
            }

            if (state.canShowDiff) {
                TabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(
                        selected = state.tab == FileTab.DIFF,
                        onClick = { viewModel.selectTab(FileTab.DIFF) },
                        text = { Text("本轮改动") },
                    )
                    Tab(
                        selected = state.tab == FileTab.CONTENT,
                        onClick = { viewModel.selectTab(FileTab.CONTENT) },
                        text = { Text("当前全文") },
                    )
                }
            }

            if (state.tab == FileTab.DIFF) {
                DiffPane(state, onRetry = viewModel::retry)
                return@Column
            }

            // HTML 渲染页：WebView 自己管加载态与错误态（[HtmlPane] 内部分支），
            // 不跟下面的「分页文本」逻辑混在一起——那是源码页的事。
            if (state.tab == FileTab.RENDER) {
                HtmlPane(
                    html = state.html,
                    path = state.target.path,
                    loading = state.htmlLoading,
                    error = state.htmlError,
                    notice = state.htmlNotice,
                    readAsset = viewModel::readHtmlAsset,
                    onRetry = viewModel::retry,
                )
                return@Column
            }

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                // 全屏错误只在「一行都还没读到」时用（R005 P2 补遗）：追加下一页失败时
                // 把已经渲染好的几千行换成一块错误页，等于让一次网络抖动抹掉全部阅读
                // 进度，而「重试」还会从第 1 页重来。那种情况交给 [FileFooter]。
                state.error != null && state.lines.isEmpty() -> ErrorPane(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::retry,
                )

                // PDF 按 **kind** 判定，不能靠 `media != null`（R005 P1）：PDF 走
                // `loadPdfStat()` 只取 stat、不下载字节，`media` 恒为 null，于是早前落到
                // `lines.isEmpty()` 分支显示成「（空文件）」 —— 明明是「有内容但 App 不能
                // 预览」，却说成文件是空的，MediaPane 里的 PDF 文案也成了死代码。
                state.kind == FileKind.PDF -> MediaPane(state)

                state.media != null -> MediaPane(state)

                state.lines.isEmpty() -> CenterNote("（空文件）")

                // Markdown 文件默认走预览（FR-5.6）：看文档时排版比原文有用。
                state.kind == FileKind.MARKDOWN -> MarkdownPane(state, listState, viewModel)

                else -> TextPane(state, listState, viewModel)
            }
        }
    }
}

/** diff 页。取不到记录时要说清原因，不能显示成「没有改动」。 */
@Composable
private fun DiffPane(state: FileViewerState, onRetry: () -> Unit) {
    val diff = state.diff
    when {
        state.diffLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        // 404 是**终态**：记录只存在桌面端 Host 的内存里，重启后就没了，再点多少次
        // 也是 404。所以这里用**不带重试按钮**的说明页（R005 P3 补遗），避免把用户
        // 引向一个永远失败的动作。
        state.diffMissing -> CenterNote(
            "这一轮的改动记录已经不可用了。\n\n${ChangesLimitations.HOST_RESTART}",
        )

        // 失败与 404 必须分开：把一次网络抖动说成「桌面端重启后记录丢了」是错误归因，
        // 而且会让用户以为没有恢复余地（R005 P2）。
        state.diffError != null -> ErrorPane(
            message = "读取差异失败：${state.diffError}",
            onRetry = onRetry,
        )

        diff == null -> ErrorPane("没有改动数据。", onRetry = onRetry)

        else -> DiffBody(diff)
    }
}

@Composable
private fun DiffBody(diff: ChangesDiff) {
    when (diff) {
        is ChangesDiff.Binary -> CenterNote("这是一个二进制文件，不提供文本 diff。")
        is ChangesDiff.Oversized -> CenterNote(ChangesLimitations.UI_MAX_FILE_NOTE)

        is ChangesDiff.Text -> {
            // hunk 之间横向滚动要联动（同一个 ScrollState 供所有行读取），否则每个 hunk
            // 各自滚动会让左侧缩进对不齐。
            val hScroll = rememberScrollState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "diff-head") {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = diff.display,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = when {
                                    !diff.beforeExists && diff.afterExists -> "新增文件"
                                    diff.beforeExists && !diff.afterExists -> "删除文件"
                                    else -> "修改"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (diff.coarse) {
                            Text(
                                text = "改动过大，已降级为整文件对比。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // 每个 hunk 一个 key：hunk 数量变化时列表锚点不会错位（M2 的教训——
                // LazyColumn 的 key 必须唯一且稳定）。
                itemsIndexed(diff.hunks, key = { index, _ -> "hunk-$index" }) { index, hunk ->
                    HunkView(index + 1, hunk, diff.hunks.size, hScroll)
                }
            }
        }
    }
}

/** 一个 hunk：头部 `@@ … @@` + 逐行着色。 */
@Composable
private fun HunkView(number: Int, hunk: DiffHunk, total: Int, hScroll: ScrollState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = "$number/$total  ${hunk.header}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // 不折行 + 横向滚动：diff 的缩进结构比换行更重要（与代码块同一取舍）。
        Row(modifier = Modifier.horizontalScroll(hScroll)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                hunk.lines.forEach { line -> DiffLine(line) }
            }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val marker = line.firstOrNull() ?: ' '
    val known = marker == '+' || marker == '-' || marker == ' '
    val body = if (known) line.substring(1) else line
    val background = when (marker) {
        '+' -> ADDED_BG
        '-' -> DELETED_BG
        else -> Color.Transparent
    }
    val accent = when (marker) {
        '+' -> ADDED_COLOR
        '-' -> DELETED_COLOR
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .background(background)
            .padding(vertical = 1.dp),
    ) {
        Text(
            text = if (known) marker.toString() else "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = accent,
            modifier = Modifier.width(10.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )
    }
}

/**
 * 纯文本 / 代码：带行号、等宽、横向滚动。
 *
 * 所有行共享一个 [ScrollState]，所以横向拖动任意一行，整页一起动——否则每行各自
 * 滚动会让长行的缩进互相错开，代码反而读不了。
 */
@Composable
private fun TextPane(
    state: FileViewerState,
    listState: LazyListState,
    viewModel: FileViewerViewModel,
) {
    val hScroll = rememberScrollState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(state.lines) { index, line ->
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                Text(
                    text = (state.startLine + index).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.width(48.dp).padding(end = 8.dp),
                )
                Text(
                    // 空行也要占一行高，否则看起来像丢了一行。
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        item(key = "file-footer") { FileFooter(state, viewModel) }
    }
}

/** Markdown 文件的预览渲染（复用聊天页同一套渲染层）。 */
@Composable
private fun MarkdownPane(
    state: FileViewerState,
    listState: LazyListState,
    viewModel: FileViewerViewModel,
) {
    // 分页读到的行拼回文本再解析。只解析一次（行集合变化才重算）。
    val text = remember(state.lines) { state.lines.joinToString("\n") }
    val blocks = remember(text) { MdParser.parse(text) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
    ) {
        item(key = "md-body") {
            MdBlocks(
                blocks = blocks,
                onLinkClick = {},
                onFilePathClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "file-footer") { FileFooter(state, viewModel) }
    }
}

/** 图片直接解码显示；PDF 只给出处（App 不自带 PDF 渲染器）。 */
@Composable
private fun MediaPane(state: FileViewerState) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (state.kind == FileKind.PDF) {
            CenterNote(
                "PDF 暂不支持在 App 内预览（${state.bytes ?: 0} 字节）。\n" +
                    "可以在聊天页让电脑端打开它。",
            )
            return@Column
        }
        val bytes = state.media ?: return@Column
        // 解码必须在组合线程之外（R005 P2）：整块字节最大能到 32 MiB，主线程同步
        // `decodeByteArray` 会卡帧甚至 ANR，而 `runCatching` 还会把 OOM 吞成「解码失败」。
        // 同时按目标宽度**下采样**：直接全尺寸解码一张 4000×3000 的照片就是 48 MB 位图。
        //
        // 用三态而不是「null 既表示解码中、又表示失败」（R005 P2 补遗）：畸形图片与
        // OOM 都会被 `runCatching` 折成 null，界面就永远停在「正在解码图片…」，没有
        // 失败态、没有重试，用户看到的是一个永不结束的加载。
        val decoded by produceState<ImageDecode>(ImageDecode.Decoding, bytes) {
            value = withContext(Dispatchers.Default) {
                runCatching { decodeScaled(bytes) }
                    .fold(
                        onSuccess = { bitmap ->
                            if (bitmap != null) ImageDecode.Ready(bitmap)
                            else ImageDecode.Failed("这个文件不像图片（找不到图片尺寸头）")
                        },
                        onFailure = { cause ->
                            ImageDecode.Failed(cause::class.java.simpleName)
                        },
                    )
            }
        }
        when (val result = decoded) {
            ImageDecode.Decoding -> CenterNote("正在解码图片…（${bytes.size} 字节）")
            is ImageDecode.Failed -> CenterNote("图片解码失败：${result.reason}")
            is ImageDecode.Ready -> Image(
                bitmap = result.bitmap.asImageBitmap(),
                contentDescription = state.target.display,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** 图片解码的三态：解码中 / 成功 / 失败（失败要如实说，不能一直转圈）。 */
private sealed interface ImageDecode {
    data object Decoding : ImageDecode
    data class Ready(val bitmap: Bitmap) : ImageDecode
    data class Failed(val reason: String) : ImageDecode
}

/** 列表底部：加载更多的转圈、「已到文件末尾」、或**追加失败**的续读入口。 */
@Composable
private fun FileFooter(state: FileViewerState, viewModel: FileViewerViewModel) {
    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            state.loadingMore -> CircularProgressIndicator()
            // 已读到内容、但追加下一页失败：错误留在这里，已读的行**保持原样**，
            // 「重试」从当前进度续读而不是回到第 1 页（R005 P2 补遗）。
            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "加载下一页失败：${state.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = viewModel::loadMore) { Text("重试") }
            }

            state.eof -> Text(
                text = "已到文件末尾（共 ${state.startLine + state.lines.size - 1} 行）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> TextButton(onClick = viewModel::loadMore) { Text("加载更多") }
        }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 增/删行的底色：低饱和，避免整屏刺眼。 */
private val ADDED_BG = Color(0x1A4CAF50)
private val DELETED_BG = Color(0x1AE57373)
