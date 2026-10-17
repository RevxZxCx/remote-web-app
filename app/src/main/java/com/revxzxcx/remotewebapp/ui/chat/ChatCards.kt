package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.Motion
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    // R017 / D47：工具卡是「实底 + 1dp 描边」的卡。调用处
    // （`ChatItemRow` 的 `ChatItem.ToolCall` 分支）的注释一直这么写，但实现只是
    // 一个裸 `Column`——注释与代码不符（D45 那类问题），且与「助手气泡无底色」
    // 的形态没有区分度。现在补上。
    //
    // 折叠态只占一行高，所以在分组展开区里几十张卡叠起来也不会变成一面色块；
    // 描边用 `outlineVariant` 而不是 `outline`：后者在浅色主题下约 1.2:1 近乎不可见。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = groupShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
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

    // 同 [ToolCallCard]：R017 / D47 补上「实底 + 1dp 描边」。
    // 失败的描边跟着走 error 色——一条失败在几十张卡里必须一眼可见。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = groupShape,
        border = BorderStroke(
            1.dp,
            if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
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

    // R028 ②（用户 m41448 批准的效果图 s2）：「卡片＝实底 surface + 1dp outlineVariant 描边」。
    // 原来这里是 `surfaceVariant.copy(alpha = 0.5f)` 的**半透明底色**——与首页那套实底描边
    // 卡片不是一个语言，而且半透明底压在消息列表上时边界发糊（同一件事在 R016/R018 已经
    // 在 GroupRow / 工具卡上修过，这张卡当时漏了）。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        summary != null && summary.total == 0 -> "第 ${turn + 1} 轮：没有改动文件"
                        summary != null -> "第 ${turn + 1} 轮：改动 ${summary.total} 个文件"
                        entry is ChangesEntry.Failed -> "本轮改动读取失败"
                        entry is ChangesEntry.Unavailable -> "本轮改动的记录已不可用"
                        // entry == null 且 loading == false：既没有摘要、也没有在飞行的请求。
                        // 快照裁剪后（重连、或卡片离开了窗口）会短暂落到这里，若还说「正在
                        // 读取…」就是永不结束的加载（R005 P3 补遗）——如实说「还没取到」并给重试。
                        loading -> "正在读取本轮改动…"
                        else -> "本轮改动尚未读取"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (summary != null && summary.total > 0) {
                    Text(
                        text = "+${summary.added} -${summary.deleted}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
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

                // entry == null 且已经没有在飞行的请求：不能就这么停在「正在读取…」，
                // 给一个明确的重试入口（R005 P3 补遗）。
                null -> if (!loading) {
                    TextButton(onClick = onRetry) {
                        Text("读取改动", style = MaterialTheme.typography.labelSmall)
                    }
                }

                else -> Unit
            }
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
    // chevron 用图标旋转而不是 `▸`/`▾` 字符（R016/R017）：两个字形宽度不同，
    // 切换时会把右侧摘要顶跳；图标恒定居中，只转 90°。
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = Motion.FAST, easing = Motion.Emphasized),
        label = "card-chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).rotate(chevron),
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

// ---------------------------------------------------------------------------
// 折叠分组内的「行式」工具条目（用户 m44550 第二张图）
//
// 与上面的 [ToolCallCard] / [ToolResultCard] 是**两种形态，各有其位**：
//  - 分组内（[com.revxzxcx.remotewebapp.ui.chat.GroupRow] 的成员）：一行一条，
//    左起「工具 · 名称」等宽 chip → 参数摘要 → 状态 tag。几十条叠起来仍然是一张
//    卡片的内部结构，不是几十张卡片。
//  - 分组外（无折叠的零散工具事件）：仍用卡片形态，因为它单独出现时需要自己的边框。
//
// 形态取自 `docs/R028-效果图.html` 的 `.grow` / `.tool` / `.arg` / `.tag` 一族：
// `.grow{border-top:1px solid outlineVariant;padding:9px 12px;gap:9px}`、
// `.tool{mono 11.5px;onSurface;surfaceVariant 底;radius 4px;padding 1px 6px}`、
// `.arg{mono 11px;onSurfaceVariant;flex:1;ellipsis}`、
// `.tag{height 20px;pill;10.5px;padding 0 8px}`。
// ---------------------------------------------------------------------------

/**
 * 分组内的一条工具调用行。
 *
 * 点整行进文件查看页（有路径时）或展开原始参数。原先分组内每条工具调用都是一张
 * 独立卡片，展开一个 `×52` 的分组会看到 52 张带描边的卡——用户看到的正是「样式还是
 * 和之前一样」。
 *
 * @param showTopDivider 组内第一条不画上分隔线（否则它与分组标题行之间会多一道横线）。
 */
@Composable
internal fun ToolCallRow(
    item: ChatItem.ToolCall,
    path: String?,
    showTopDivider: Boolean,
    onToggle: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    // 摘要要 `remember`（R028b 审查 P2-5）：流式期分组每秒重组数十次，`firstArgumentHint`
    // 每次都要解析一遍 JSON + 编译正则。`path` 由调用方 remember 后传进来（P2-6）——
    // 早先这里和 `GroupMemberRow` 各解析一次，同一条 arguments 每帧跑两遍。
    val argument = remember(item.seq, item.arguments) { firstArgumentHint(item.name, item.arguments) }
    // tag 是**状态**（原型 `.grow` 里是「已编辑 / 完成 / 已读取」），不是操作提示。
    // 早先无路径时写「详情 / 收起」，那是把「能不能展开」当成了状态——展开由整行点击
    // 承担，tag 再写一遍既重复又占宽度。
    val tag = when {
        path == null -> "已调用"
        ToolFilePaths.isMutating(item.name) -> "已编辑"
        else -> "已读取"
    }
    ToolRow(
        tool = item.name,
        argument = path ?: argument,
        tag = tag,
        tagStyle = if (path != null) ToolTagStyle.EDIT else ToolTagStyle.NEUTRAL,
        showTopDivider = showTopDivider,
        // 整行点击**永远是展开参数**（R028b 审查 P2-3）：早先有路径时整行跳文件页，于是
        // `write` / `edit` 这类调用用户再也看不到模型到底传了什么（content / old_string），
        // 而那正是审查工具调用时最需要的信息。文件跳转改由右侧 tag 承担——它本来就是
        // 第二个点击目标，且「已编辑 / 已读取」这个文案本身就在暗示可以点。
        onClick = onToggle,
        onTagClick = if (path != null) {
            { onOpenFile(path) }
        } else {
            null
        },
    )
}

/** 分组内的一条工具结果行。失败时 tag 走 error 色。 */
@Composable
internal fun ToolResultRow(
    item: ChatItem.ToolResult,
    showTopDivider: Boolean,
    onToggle: () -> Unit,
) {
    val summary = remember(item.seq, item.text) { summarize(item.text) }
    ToolRow(
        tool = if (item.isError) "工具出错" else "工具结果",
        argument = summary,
        // 🔴 tag 是**状态**而不是操作提示。原型 `docs/R028-效果图.html` 的 `.grow` 里
        // 三条分别是「已编辑」「完成」「已读取」——全是状态；早先这里写「详情 / 收起」，
        // 那是操作提示，且被涂成绿色（OK 配色）后语义彻底错位：绿色 = 成功，
        // 而「详情」跟成功毫无关系。用户 m44550 第 2 条「样式还是和之前一样」，
        // 这是其中一处。
        //
        // 展开能力由整行点击承担（`onClick = onToggle`），不需要 tag 再写一遍。
        tag = if (item.isError) "失败" else "完成",
        tagStyle = if (item.isError) ToolTagStyle.BAD else ToolTagStyle.OK,
        showTopDivider = showTopDivider,
        onClick = onToggle,
    )
}

/** 行内 tag 的配色（对应效果图 `.tag.edit` / `.tag.ok` / `.tag.bad` / 中性）。 */
internal enum class ToolTagStyle { EDIT, OK, BAD, NEUTRAL }

/**
 * 行式工具条目的骨架：`[工具名 chip] [参数摘要] ......... [tag]`。
 *
 * 整行可点（点击热区包含左侧分隔线的整条宽度，`clickable` 在 `padding` 之前）。
 *
 * @param onTagClick 非 null 时 tag 自己也是一个点击目标（用于「点 tag 进文件页」）。
 *   行点击与 tag 点击是两个不同的动作，所以 tag 单独包一层 `clickable` 而不是让
 *   行点击去猜——`clickable` 在子项上会先消费事件，不会冒泡到行。
 */
@Composable
private fun ToolRow(
    tool: String,
    argument: String,
    tag: String,
    tagStyle: ToolTagStyle,
    showTopDivider: Boolean,
    onClick: () -> Unit,
    onTagClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTopDivider) {
            // 分隔线用 `outlineVariant` 而不是 `onSurfaceVariant.copy(alpha=…)`：
            // 后者叠加在不同底色上会得到不同灰度（R016 起全 App 统一用实色令牌）。
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ToolChip(tool)
            Text(
                text = argument.ifBlank { "（无参数）" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onTagClick != null) {
                Box(modifier = Modifier.clickable(onClick = onTagClick)) {
                    ToolTag(tag, tagStyle)
                }
            } else {
                ToolTag(tag, tagStyle)
            }
        }
    }
}

/**
 * 等宽小 chip，例如 `read` / `pwsh`。
 *
 * `widthIn(max = 120.dp)` + `Ellipsis` 是必需的（R028b 审查 P2-7）：非 weight 子项先量，
 * 一个 `mcp__xxx__long_tool_name` 会吃满整行，把中间那块参数摘要（原型的主体）
 * 压成 0 宽再省略。
 */
@Composable
private fun ToolChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 120.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 行尾状态 tag（`已编辑` / `已读取` / `详情` / `失败`）。 */
@Composable
private fun ToolTag(text: String, style: ToolTagStyle) {
    val fg: Color
    val bg: Color
    val border: Color
    when (style) {
        ToolTagStyle.EDIT -> {
            fg = MaterialTheme.colorScheme.onSecondaryContainer
            bg = MaterialTheme.colorScheme.secondaryContainer
            border = MaterialTheme.colorScheme.outlineVariant
        }
        ToolTagStyle.OK -> {
            fg = LocalStatusRunning.current
            bg = LocalStatusRunning.current.copy(alpha = 0.14f)
            border = LocalStatusRunning.current.copy(alpha = 0.35f)
        }
        ToolTagStyle.BAD -> {
            fg = MaterialTheme.colorScheme.error
            bg = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            border = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        }
        ToolTagStyle.NEUTRAL -> {
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            bg = Color.Transparent
            border = MaterialTheme.colorScheme.outlineVariant
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 20.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/**
 * 没有文件路径时的参数摘要：取 `arguments`（JSON 字符串）里第一个标量值。
 *
 * 为什么不用正则捞 `"command"`：实测同一个工具（`write`）的 `arguments` **键序都不
 * 固定**（一次 `file_path` 在前、一次 `content` 在前），且值里可能含转义引号。
 * 解析失败时退回整串前 [TOOL_HINT_CHARS] 字符——总比什么都不显示好。
 */
internal fun firstArgumentHint(toolName: String, arguments: String): String {
    if (arguments.isBlank()) return ""
    val parsed = runCatching {
        val el = Json.parseToJsonElement(arguments)
        val obj = el as? JsonObject ?: return@runCatching null
        // 优先级：命令 > 路径 > 查询词 > 第一个标量。
        val preferred = listOf("command", "cmd", "path", "file_path", "query", "pattern", "url", "jobId")
        preferred.firstNotNullOfOrNull { key -> obj[key]?.scalarOrNull() }
            ?: obj.values.firstNotNullOfOrNull { it.scalarOrNull() }
    }.getOrNull()
    // `{}` / 全是 null 的对象：没有可显示的参数。此时**不要**退回原始 JSON 字符串——
    // 那会把 `{}` 或 `{"file_path":null}` 原样显示成摘要，比留空更让人困惑。
    // 只有「解析彻底失败」（不是 JSON）才退回原串，因为那说明服务端给的不是 JSON。
    val text = parsed
        ?: arguments.takeIf { runCatching { Json.parseToJsonElement(it) }.isFailure }
        ?: ""
    return text.replace(WHITESPACE_RUN, " ").trim().take(TOOL_HINT_CHARS)
}

/**
 * 连续空白（含换行/制表）——提成顶层常量。
 *
 * `Regex` 构造有编译成本，早先写在 `firstArgumentHint` 函数体内，流式期每次重组
 * 都重新编译一次（R028b 审查 P2-5）。
 */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * [JsonElement] 直接是标量时取它的文本（对象/数组/null 返回 null）。
 *
 * 🔴 `JsonNull` 也是 `JsonPrimitive` 的子类，`isString` 为 false、`content` 是字面量
 * `"null"`——不显式排除的话 `{"file_path":null}` 会显示成「参数是字符串 null」。
 * 这与 R007 在 `ToolFilePaths` 里踩过的是同一个坑（当时 `{"file_path":null}` 被提取成
 * 路径 `"null"`），那里用 `takeIf { it.isString }` 修掉，这里必须同时排掉 `JsonNull`。
 */
private fun JsonElement.scalarOrNull(): String? {
    if (this is JsonNull) return null
    if (this !is JsonPrimitive) return null
    // 字符串与数字/布尔都照原样显示（例如 `{"timeout":30}`、`{"ok":true}`）。
    return content.takeIf { it.isNotBlank() }
}

/** 行内参数摘要最多显示这么多字符。 */
private const val TOOL_HINT_CHARS = 80

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
