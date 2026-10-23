package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.session.FileReference
import com.revxzxcx.remotewebapp.ui.chat.ChevronUpIcon
import com.revxzxcx.remotewebapp.ui.theme.chipShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 文件引用选择器（R043 第 2 条，R044 返工）。
 *
 * 引用与附件是两件事：附件会被上传成图片/文件块，引用只是正文里的 `@path`——桌面端就是
 * 这么做的（输入框打 `@` 打开 `fileReferences` 源，选中后变成一段高亮的路径文本）。所以
 * 这里选中的东西**不进** `pending`，只拼进消息文本。
 *
 * ## 形态：输入框正上方的展开面板（R046 ③，用户 m31204）
 *
 * 用户原话：「引用文件的基本选择流程和功能是正常的，但是界面和原型图有出入，原型是显示在
 * 输入框的上方，像是展开的一个框，而实际选择文件的表现是一个新的窗口，这种情况虽然能用
 * 但不优雅，你需要看原型的具体界面，还原成原型的设计。」
 *
 * 原型（`docs/R043-效果图.html` 第 5 节）里它是 `.dock` 内的一个**兄弟块**，不是独立窗口：
 * `background:var(--surface); border:1px solid var(--outline); border-radius:14px;
 * margin-bottom:8px; box-shadow:0 -10px 34px rgba(0,0,0,.5)` —— 向上投射的阴影就是
 * 「从输入框长出来」的全部暗示。所以这里不再是 `Dialog`，而是一个普通 Composable，
 * 由 [Composer] 在输入框**正上方**渲染；开关仍然由调用方持有（`showReferencePicker`）。
 *
 * 顶部一行标题 + 已选计数，第二行搜索框，第三行面包屑（仅在有层级时出现），下面是列表。
 *
 * ## 为什么不是 AlertDialog / Dialog
 *
 * 第一版用 `AlertDialog`，标题 + 列表挤在 material3 的内边距里；第二版换成自绘 `Dialog`，
 * 能放面包屑了，但它把整个屏幕遮暗、还要靠「完成」按钮退出——选择引用是在**写消息的过程
 * 中**发生的事，弹出独立窗口等于打断输入上下文（用户说的「不优雅」）。
 *
 * ## 目录与文件是两种点击
 *
 * 用户 m27926 第 2 条报的「点文件夹会直接选中，无法选择文件夹里的文件」。根因在
 * [ReferencePathTrail.rowAction]：目录行的动作是**进入**，不是选中——`@a/b/` 这种目录引用
 * 对模型没有意义，用户要的是里面的文件。目录行右侧另留一个「引用目录」小入口，
 * 给确实想引用整个目录的场景（与桌面端 mention 允许尾斜杠一致）。
 *
 * ## 搜索是输入即搜
 *
 * 文件树可能有几千个，一次拉全清单再本地过滤会让弹窗卡一下；ViewModel 侧每次取消上一次
 * 在途请求。搜索框有内容时**隐藏面包屑**——此时列表是全局匹配结果，不在某一个目录里，
 * 显示面包屑会骗人。
 */
@Composable
internal fun ReferencePickerPanel(
    results: List<FileReference>,
    loading: Boolean,
    error: String?,
    selected: List<FileReference>,
    onSearch: (String) -> Unit,
    onAdd: (FileReference) -> Unit,
    onRemove: (FileReference) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 审查 P2：这两个状态原来分别是 `rememberSaveable`，而面板由 `AnimatedVisibility` 挂载
    // ——收起只是移出组合树，重开时 `SaveableStateRegistry` 会把值原样恢复。于是「在 `docs/`
    // 里搜过 `config` 再收起、再点 ＋→引用」会回到那次的搜索态，而 `onPickReference` 只发了
    // `searchReferences("")`（根目录），列表与 `query` 对不上、甚至整屏「没有可引用的文件」。
    //
    // 面板是一次性的选择器（收起即放弃），不需要跨重建保留，因此用普通 `remember`：
    // 挂载即空白，与 `onPickReference` 发出去的那次根目录搜索一致。
    var query by remember { mutableStateOf("") }
    // 当前目录（带尾斜杠；"" = 工作区根）。它与搜索词互斥：
    // 打字 → 全局搜索；清空 → 回到 browsing 的那个目录。
    var directory by remember { mutableStateOf("") }

    // 首次组合就对**当前目录**搜一次：服务端对空 query 也会回一批（根目录直接子项），
    // 让用户一进来就有东西可选。
    //
    // key 必须是 `directory` 而不是 `Unit`：两者现在都是普通 `remember`（面板收起即
    // 丢弃，见上面的说明），但「同一次挂载内 directory 变化」仍然要走这条 effect。
    // 用 `Unit` 的话，切目录不会重新搜，View 里会留着上一个目录的列表。
    val browsing = query.isBlank()

    // 搜索的唯一触发点。
    //
    // key 同时带上 `browsing`：只带 `directory` 时，「清空搜索」这条路径会漏搜——
    // 清空按钮要把当前目录重新列一遍，而它传的 `directory` 与当前值**相同**，
    // key 不变、effect 不重跑，列表就停在上一次全局搜索的匹配项上，面包屑却已经
    // 重新出现，用户会把跨目录的搜索结果当成当前目录内容。键盘退格删空更糟：那条
    // 路径发的是空 query，回来的是**根目录**列表（R045 三轮审查 P2）。
    //
    // 两个 key 都不能少：`directory` 负责「切目录 / 面包屑回跳」，`browsing` 负责
    // 「清空搜索回到目录浏览」——清空时 directory 值不变，只靠它 key 不变、effect 不重跑。
    LaunchedEffect(directory, browsing) { if (browsing) onSearch(directory) }

    fun browseTo(next: String) {
        query = ""
        // 不要在这里调 `onSearch(next)`：上面的 effect 监听 `directory` 与 `browsing`，
        // 两处都调会为一次导航发两次请求，后到的覆盖先到的（R045 二轮审查 P2）。
        directory = next
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        ),
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 标题行 ────────────────────────────────────────────────
            // R046 ③ 对齐原型：一行小字「@ 引用工作区里的路径」，等宽 + 主色的 `@`。
            // 原型没有「完成」按钮——面板就长在输入框上方，点输入框或点外面即可继续写，
            // 多一个按钮反而要用户先做一次决定。收起入口留在右上角的 ⌄（见下）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 11.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (selected.isEmpty()) {
                        "引用工作区里的路径"
                    } else {
                        "引用工作区里的路径 · 已选 ${selected.size} 项"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = ChevronUpIcon,
                    contentDescription = "收起引用面板",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDismiss)
                        .padding(2.dp),
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // ── 搜索框 ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .heightIn(min = 38.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        pillShape,
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜文件名或路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            // 空串交给上面的 effect（它按 `browsing` 回到当前目录）；
                            // 这里再发一次 `onSearch("")` 拿到的是根目录列表。
                            if (it.isNotBlank()) onSearch(it)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "清空搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { query = "" },
                    )
                }
            }

            // ── 面包屑（只在浏览某一层时出现）───────────────────────────
            if (browsing && directory.isNotEmpty()) {
                BreadcrumbRow(directory = directory, onJump = { browseTo(it) })
            }

            // ── 列表 ────────────────────────────────────────────────
            // 原型里这个面板是「从输入框长出来的 4~6 行」，不是盖住半屏的列表：
            // 上限压到 240dp（约 5 行），超出靠滚动。搜索态多给一点（结果可能很多）。
            var listHeight = 240.dp
            if (query.isNotBlank()) listHeight = 300.dp
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                when {
                    loading && results.isEmpty() -> CenterHint("正在读取工作区文件…")
                    error != null && results.isEmpty() -> CenterHint("读不到文件列表：$error")
                    results.isEmpty() -> CenterHint(
                        if (query.isBlank()) {
                            if (directory.isEmpty()) {
                                "这个工作区没有可引用的文件。"
                            } else {
                                "这个文件夹是空的。"
                            }
                        } else {
                            "没有匹配「$query」的文件。"
                        },
                    )
                    else -> LazyColumn {
                        items(results, key = { it.path }) { reference ->
                            val picked = selected.any { it.path == reference.path }
                            ReferenceRow(
                                reference = reference,
                                picked = picked,
                                action = ReferencePathTrail.rowAction(
                                    ReferencePathTrail.FileReferenceView(
                                        path = reference.path,
                                        isDirectory = reference.isDirectory,
                                        selected = picked,
                                    ),
                                ),
                                onClick = {
                                    if (reference.isDirectory) {
                                        browseTo(ReferencePathTrail.childQuery(reference.path))
                                    } else if (picked) {
                                        onRemove(reference)
                                    } else {
                                        onAdd(reference)
                                    }
                                },
                                onPickDirectory = { onAdd(reference) },
                            )
                        }
                    }
                }
            }

            // ── 已选胶囊 ──────────────────────────────────────────────
            if (selected.isNotEmpty()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                SelectedRow(selected = selected, onRemove = onRemove)
            }
        }
    }
}

/**
 * 面包屑：`工作区 / docs / dev`，中间段可点。
 *
 * 点「工作区」回到根（`query = ""`），点中间某段回到那一层。末段是当前层，不加粗——
 * 它已经是「你正在看的」，加粗反而和列表里的目录名撞视觉。
 */
@Composable
private fun BreadcrumbRow(directory: String, onJump: (String) -> Unit) {
    val crumbs = ReferencePathTrail.crumbs(directory)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CrumbChip(label = "工作区", onClick = { onJump("") })
        crumbs.forEachIndexed { index, crumb ->
            Text(
                text = "/",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
            if (index == crumbs.lastIndex) {
                Text(
                    text = crumb.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                CrumbChip(label = crumb.label, onClick = { onJump(crumb.query) })
            }
        }
    }
}

@Composable
private fun CrumbChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

@Composable
private fun ReferenceRow(
    reference: FileReference,
    picked: Boolean,
    action: ReferencePathTrail.RowAction,
    onClick: () -> Unit,
    onPickDirectory: () -> Unit,
) {
    val directory = reference.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (directory) FolderIcon else FileIcon,
            contentDescription = null,
            tint = if (directory) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (directory) {
                    reference.path.substringAfterLast('/').ifEmpty { reference.path } + "/"
                } else {
                    reference.path.substringAfterLast('/')
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (picked) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 同名文件在不同目录很常见（`index.html`），只显示 basename 会选错。
            val parent = reference.path.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty()) {
                Text(
                    text = parent,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (action) {
            ReferencePathTrail.RowAction.Descend -> {
                // 目录行：右侧给「引用整个目录」的独立入口，主点击区仍然是进入。
                Text(
                    text = "引用目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clickable(onClick = onPickDirectory)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Icon(
                    imageVector = ChevronRightIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
            }
            ReferencePathTrail.RowAction.ToggleSelect -> Unit
            ReferencePathTrail.RowAction.RemoveSelect -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectedRow(selected: List<FileReference>, onRemove: (FileReference) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
        selected.forEach { reference ->
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), chipShape)
                    .padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reference.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "移除引用 ${reference.path}",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onRemove(reference) },
                )
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}
