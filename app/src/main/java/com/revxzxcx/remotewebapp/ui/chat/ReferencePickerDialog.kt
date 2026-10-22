package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 文件引用选择器（R043，用户 m26743 第 2 条里「能进行各种的添加」）。
 *
 * 引用与附件是两件事：附件会被上传成图片/文件块，引用只是正文里的 `@path`——
 * 桌面端就是这么做的（在输入框打 `@` 打开 `fileReferences` 源，选中后变成一段
 * 高亮的路径文本）。所以这里选中的东西**不进** `pending`，只拼进消息文本。
 *
 * 为什么用对话框而不是底部面板：它从「+」面板里再进来一层，而面板已经占了下半屏；
 * 再叠一个面板会出现「面板里的面板」，返回手势的语义也会打架。对话框高度受限、
 * 有明确的取消，正好适合这种「查一下、挑几个」的短任务。
 *
 * 搜索是**输入即搜**（ViewModel 侧每次取消上一次在途请求）：文件树可能有几千个，
 * 一次拉全清单再本地过滤会让打开弹窗卡一下。
 */
@Composable
internal fun ReferencePickerDialog(
    results: List<FileReference>,
    loading: Boolean,
    error: String?,
    selected: List<FileReference>,
    onSearch: (String) -> Unit,
    onAdd: (FileReference) -> Unit,
    onRemove: (FileReference) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    // 首次打开就搜一次空串：服务端对空 query 也会回一批（实测 20 条），
    // 让用户一进来就有东西可选，而不是面对一个必须打字才有内容的空列表。
    LaunchedEffect(Unit) { onSearch("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("引用文件", modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    Text(
                        text = "已选 ${selected.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                                onSearch(it)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    when {
                        loading && results.isEmpty() -> CenterHint("正在读取工作区文件…")
                        error != null && results.isEmpty() -> CenterHint("读不到文件列表：$error")
                        results.isEmpty() -> CenterHint(
                            if (query.isBlank()) "这个工作区没有可引用的文件。" else "没有匹配「$query」的文件。",
                        )
                        else -> LazyColumn {
                            items(results, key = { it.path }) { reference ->
                                val picked = selected.any { it.path == reference.path }
                                ReferenceRow(
                                    reference = reference,
                                    picked = picked,
                                    onClick = {
                                        if (picked) onRemove(reference) else onAdd(reference)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun ReferenceRow(reference: FileReference, picked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (reference.isDirectory) Icons.Default.List else Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (picked) 0.9f else 0.45f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reference.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (picked) FontWeight.Medium else FontWeight.Normal,
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
        if (picked) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}
