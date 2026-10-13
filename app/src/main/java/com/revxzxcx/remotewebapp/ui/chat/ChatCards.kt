package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.changes.ChangedFile
import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import com.revxzxcx.remotewebapp.core.changes.ChangesLimitations
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.ToolFilePaths

/**
 * 工具调用卡片（需求 FR-5.3）。
 *
 * 三个要点：
 *  1. **默认折叠**，只显示 `工具 · write  path` 这样一行摘要——一次多步轮次里工具调用
 *     动辄几十条，全部展开会把正文挤没；
 *  2. 参数里解析出的**文件路径是一行可点的按钮**，点开进文件查看页。路径从
 *     `arguments`（JSON 字符串）里用 [ToolFilePaths] 解析，**不能用正则**捞：
 *     实测同一个 `write` 的 `arguments` 键序都不固定（一次 `file_path` 在前、
 *     一次 `content` 在前）。
 *  3. 展开后显示原始 `arguments`，但**截断**——`write` 的 content 可能是几万字符。
 */
@Composable
internal fun ToolCallCard(
    item: ChatItem.ToolCall,
    onOpenFile: (String) -> Unit,
) {
    // 展开状态要跟着列表滚动存活（LazyColumn 会回收离屏条目），但不必跨进程保存。
    // 用 rememberSaveable 让旋转屏幕后仍然是用户离开时的样子。
    var expanded by rememberSaveable(item.seq) { mutableStateOf(false) }
    val path = remember(item.name, item.arguments) { ToolFilePaths.extract(item.name, item.arguments) }

    Column(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            label = "工具 · ${item.name}",
            detail = path,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (path != null) {
            FileRowMarker(
                path = path,
                label = if (ToolFilePaths.isMutating(item.name)) "已编辑" else "已读取",
                onClick = { onOpenFile(path) },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                if (item.arguments.isNotBlank()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    )
                    Text(
                        text = item.arguments.take(MAX_ARGUMENTS_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (item.arguments.length > MAX_ARGUMENTS_CHARS) {
                        Text(
                            text = "（参数过长，已截断；完整内容请看文件查看页）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(
                        text = "（无参数）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 工具结果卡片。默认折叠成一行（成功/失败 + 行数），展开看输出。
 *
 * 失败用红底，成功保持中性——一条失败的工具调用在几十行日志里必须一眼可见。
 */
@Composable
internal fun ToolResultCard(item: ChatItem.ToolResult) {
    var expanded by rememberSaveable(item.seq) { mutableStateOf(false) }
    val summary = remember(item.text) { summarize(item.text) }

    Column(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            label = if (item.isError) "工具出错" else "工具结果",
            detail = summary,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            accent = if (item.isError) MaterialTheme.colorScheme.error else null,
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )
                Text(
                    text = item.text.ifBlank { "（无输出）" }.take(MAX_RESULT_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (item.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (item.text.length > MAX_RESULT_CHARS) {
                    Text(
                        text = "（输出过长，已截断）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「这一轮改了 N 个文件」卡片（需求 FR-5.4）。
 *
 * 四种状态必须区分清楚，不能都显示成「没有改动」：
 *  - [entry] == null 且 [loading] → 正在拉；
 *  - [ChangesEntry.Unavailable] → **记录真没了**（Host 重启后该轮摘要不在内存，404 终态）；
 *  - [ChangesEntry.Failed] → **读取失败**（超时/断网/解析不了），可重试，**不说成「没了」**；
 *  - `total == 0` → 这一轮确实没改文件；有文件 → 列出来，每行可点开 diff。
 *
 * [onOpenFile] 回传的是 **[ChangedFile.path]**（不是 `display`）加它在 [ChangesSummary.files]
 * 里的**下标**：`display` 是桌面端裁短过的展示路径，拿它去请求 `workspaceFiles` 会
 * `not-found`，拿它去 `indexOfFirst { it.path == … }` 又永远匹配不到，于是「本轮改动」
 * 标签页与「在电脑上打开」入口会一起消失（R005 P1-1）。
 *
 * `total` 是截断前的完整数，[ChangesSummary.files] 最多 `maxFiles` 条，所以
 * 文件多的时候要提示「只显示前 N 个」。
 */
@Composable
internal fun ChangesCard(
    turn: Long,
    loading: Boolean,
    entry: ChangesEntry?,
    onOpenFile: (path: String, display: String, index: Int) -> Unit,
    onRetry: () -> Unit,
) {
    val summary = (entry as? ChangesEntry.Loaded)?.summary
    var expanded by rememberSaveable(turn) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    summary != null && summary.total == 0 -> "第 ${turn + 1} 轮：没有改动文件"
                    summary != null -> "第 ${turn + 1} 轮：改动 ${summary.total} 个文件"
                    loading || entry == null -> "正在读取本轮改动…"
                    entry is ChangesEntry.Failed -> "本轮改动读取失败"
                    else -> "本轮改动的记录已不可用"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (summary != null && summary.total > 0) {
                Text(
                    text = "+${summary.added} -${summary.deleted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (summary != null && summary.total > 0) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 只渲染前 MAX_LISTED_FILES 条：单个 LazyColumn item 里塞 500 行
                    // 会让展开时整块一起测量、且无法被列表回收（R005 P3-3）。
                    summary.files.take(MAX_LISTED_FILES).forEachIndexed { index, file ->
                        ChangedFileRow(file) { onOpenFile(file.path, file.display, index) }
                    }
                    val hidden = summary.total - minOf(summary.files.size, MAX_LISTED_FILES)
                    if (hidden > 0) {
                        Text(
                            text = "还有 $hidden 个文件未列出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // 取不到摘要时必须说清原因，否则用户会以为「这轮真没改文件」。两种失败
        // 的归因完全不同：404 是记录真的随 Host 进程没了，其它是网络/解析问题。
        when (entry) {
            is ChangesEntry.Unavailable -> Text(
                text = ChangesLimitations.HOST_RESTART,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            is ChangesEntry.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text("重试", style = MaterialTheme.typography.labelSmall)
                }
            }

            else -> Unit
        }
    }
}

/** 摘要里的一行文件。 */
@Composable
private fun ChangedFileRow(file: ChangedFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.display,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.binary) {
            Text(
                text = "二进制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        } else if (file.oversized) {
            Text(
                text = "过大",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = "+${file.added}",
            style = MaterialTheme.typography.labelSmall,
            color = ADDED_COLOR,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            text = "-${file.deleted}",
            style = MaterialTheme.typography.labelSmall,
            color = DELETED_COLOR,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 卡片标题行：左边名字、右边一句摘要、最右是展开/收起。整行可点。 */
@Composable
private fun CardHeader(
    label: String,
    detail: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
        } else {
            Row(modifier = Modifier.weight(1f)) {}
        }
    }
}

/** 一行「已编辑/已读取 <路径>」，点它进文件查看页。 */
@Composable
private fun FileRowMarker(path: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `clickable` 在 `padding` **之前**：反过来的话左边 16dp 缩进不在点击热区里，
            // 手指落在缩进上就没反应（与 [ChangedFileRow] 的顺序保持一致）。
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
    }
}

/** 把工具输出压成一句摘要：优先取首行，空则报行数。 */
private fun summarize(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val lines = text.count { it == '\n' } + if (text.isBlank()) 0 else 1
    return when {
        firstLine.isEmpty() -> "（无输出）"
        lines <= 1 -> firstLine.take(80)
        else -> "${firstLine.take(60)} · $lines 行"
    }
}

/** 展开后参数最多显示这么多字符。 */
private const val MAX_ARGUMENTS_CHARS = 2_000

/** 展开后工具输出最多显示这么多字符。 */
private const val MAX_RESULT_CHARS = 8_000

/**
 * 改动卡片展开后最多直接渲染多少个文件行。
 *
 * `maxFiles` 的服务端上限是 500，全部塞进聊天列表的**同一个 item** 里会让展开瞬间
 * 一次性测量 500 行（且这些行不受 LazyColumn 回收），明显掉帧（R005 P3-3）。
 */
private const val MAX_LISTED_FILES = 50

/** 与桌面端的 diff 配色一致：绿色增、红色删。 */
internal val ADDED_COLOR = Color(0xFF4CAF50)
internal val DELETED_COLOR = Color(0xFFE57373)
