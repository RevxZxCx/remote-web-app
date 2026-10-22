package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.FileReference
import com.revxzxcx.remotewebapp.core.session.SlashCommand
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape
import com.revxzxcx.remotewebapp.ui.theme.chipShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 输入区「+」面板（R043，用户 m26743 第 2 条）。
 *
 * 用户原话：「给输入框的加号增加桌面端的加号功能……点击加号会展开一个列表，具体的功能
 * 最好和 DSH 的功能基本相似，能进行各种的添加，比如任务、目标。也能进行命令的发送执行，
 * 总体来说不能只能添加文件和图片。」
 *
 * 桌面端的「+」实际上就是 `/` 命令菜单（`aria-label = 添加文件或调用指令`，onClick 走
 * `toggleSource("command", {trigger:"/"})`），**没有**独立的「附件面板」。所以这里做成
 * 三段而不是把桌面端那一个列表照搬过来：
 *  1. **添加**：图片 / 文件 / 引用文件 —— App 独有的入口（桌面端拖拽进窗口，手机上没这个手势）；
 *  2. **快捷**：把常用命令里会改会话设定的两条（设为目标、计划模式）提到手边，
 *     用户口语里的「添加任务、目标」落到这里；
 *  3. **命令**：`commands/list` 的完整列表，先露四条 + 「全部 N 条」进搜索页。
 *
 * 为什么命令段**可能空**而整个面板仍然打开：`commands/list` 是插件注册的聚合，
 * 服务端可能因为插件被禁用而返回空。面板若因此整个不可用，用户连「选文件」都做不了——
 * 那是把两件不相关的事绑死了。所以命令段失败只降级它自己（显示一行说明），
 * 上两段照常。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerAddSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    commands: List<SlashCommand>,
    commandsLoading: Boolean,
    commandsError: String?,
    onRetryCommands: () -> Unit,
    /** 选中命令：把 `/name 参数` 交给调用方塞进输入框。 */
    onCommand: (SlashCommand) -> Unit,
    onSetGoal: () -> Unit,
    onPlanMode: () -> Unit,
    onPickReference: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by rememberSaveable { mutableStateOf(AddPage.Root) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            when (page) {
                AddPage.Root -> AddRoot(
                    commands = commands,
                    commandsLoading = commandsLoading,
                    commandsError = commandsError,
                    onRetryCommands = onRetryCommands,
                    onPickImage = onPickImage,
                    onPickFile = onPickFile,
                    onPickReference = onPickReference,
                    onSetGoal = onSetGoal,
                    onPlanMode = onPlanMode,
                    onCommand = onCommand,
                    onShowAll = { page = AddPage.Commands },
                )
                AddPage.Commands -> CommandListPage(
                    commands = commands,
                    loading = commandsLoading,
                    error = commandsError,
                    onRetry = onRetryCommands,
                    onBack = { page = AddPage.Root },
                    onCommand = onCommand,
                )
            }
        }
    }
}

private enum class AddPage { Root, Commands }

@Composable
private fun AddRoot(
    commands: List<SlashCommand>,
    commandsLoading: Boolean,
    commandsError: String?,
    onRetryCommands: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickReference: () -> Unit,
    onSetGoal: () -> Unit,
    onPlanMode: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(max = 560.dp),
    ) {
        // ── 添加 ──────────────────────────────────────────────────────────
        AddSectionTitle("添加")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddTile(label = "图片", icon = Icons.Default.Add, onClick = onPickImage, modifier = Modifier.weight(1f))
            AddTile(label = "文件", icon = Icons.Default.Info, onClick = onPickFile, modifier = Modifier.weight(1f))
            AddTile(label = "引用", icon = Icons.Default.Search, onClick = onPickReference, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(10.dp))

        // ── 快捷 ──────────────────────────────────────────────────────────
        AddSectionTitle("快捷")
        AddRow(label = "设为目标", detail = "长任务用", onClick = onSetGoal)
        AddRow(label = "计划模式", detail = "先出方案再动手", onClick = onPlanMode)
        Spacer(Modifier.size(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(4.dp))

        // ── 命令 ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddSectionTitle("命令", modifier = Modifier.weight(1f))
            if (commands.isNotEmpty()) {
                Text(
                    text = "全部 ${commands.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onShowAll)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        when {
            commandsLoading && commands.isEmpty() -> HintLine("正在读取命令…")
            commandsError != null && commands.isEmpty() -> Column {
                HintLine("命令清单读不到：$commandsError")
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onRetryCommands)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            commands.isEmpty() -> HintLine("这台桌面端没有可用的斜杠命令。")
            else -> Column {
                commands.take(ROOT_COMMAND_LIMIT).forEach { command ->
                    CommandRow(command = command, onClick = { onCommand(command) })
                }
            }
        }
    }
}

/** 首屏最多露几条命令；其余走「全部 N 条」。 */
private const val ROOT_COMMAND_LIMIT = 4

@Composable
private fun CommandListPage(
    commands: List<SlashCommand>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(commands, query) {
        val q = query.trim().removePrefix("/")
        if (q.isEmpty()) commands
        else commands.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 560.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "全部命令",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "返回",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        SearchField(query = query, onQueryChange = { query = it }, placeholder = "搜命令名或说明")
        Spacer(Modifier.size(8.dp))
        when {
            loading && commands.isEmpty() -> HintLine("正在读取命令…")
            error != null && commands.isEmpty() -> Column {
                HintLine("命令清单读不到：$error")
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            filtered.isEmpty() -> HintLine("没有匹配「$query」的命令。")
            else -> LazyColumn {
                items(filtered, key = { it.name }) { command ->
                    CommandRow(command = command, onClick = { onCommand(command) })
                }
            }
        }
    }
}

/** 一条命令：`/name` + 说明 + 参数提示。 */
@Composable
private fun CommandRow(command: SlashCommand, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = command.trigger,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 吃参数的命令给个提示：选中后输入框里会有半截未完成的内容，
                // 用户得知道「还没完，后面该填什么」。
                command.hint?.let { hint ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (command.description.isNotBlank()) {
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun AddTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface1(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AddRow(label: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** 面板内部的浅底描边块（与抽屉里那套卡片同一语言）。 */
@Composable
private fun Surface1(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), chipShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, chipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 面板内的搜索框（与抽屉搜索同款，但这里是真输入）。 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), pillShape)
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
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

/**
 * 已选引用的胶囊行（用户 m21625/m26743 里说的「添加各种内容」的可视化）。
 *
 * 引用**不会**直接进消息正文，而是拼成 `@path` 追加在草稿末尾——那是桌面端的写法，
 * 也是服务端唯一认识的引用语法（`@` 在输入框里同样打开 `fileReferences` 源）。
 */
@Composable
internal fun ReferenceChips(
    references: List<FileReference>,
    onRemove: (FileReference) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (references.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), bubbleShape)
                    .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (reference.isDirectory) Icons.Default.List else Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = reference.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 16.dp),
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
