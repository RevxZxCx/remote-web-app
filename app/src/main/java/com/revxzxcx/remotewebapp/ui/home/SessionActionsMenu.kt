package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.chat.ChevronDownIcon
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape

/**
 * 会话行的长按菜单：移入归档 / 取消归档 + 置顶 / 取消置顶。
 *
 * **为什么没有「删除」**：服务端没有删除会话的 API。已把 SDK dump（`F:\bot\_sdk`）
 * 全量 grep 过 `deleteSession` / `session\/delete` / `removeSession`，零命中；
 * session-controller 的 21 个 invocation 里与「移除」相关的只有 archive/unarchive。
 * 桌面端 Web UI 提供的同样是「归档」。所以这里也只做归档，文案不说「删除」——
 * 承诺一个做不到的操作比少一个功能更糟（需求文档 Q14 同类的诚实原则）。
 *
 * 菜单用 `DropdownMenu` 而不是 `ModalBottomSheet`：行在 `LazyColumn` 里，
 * 底部弹窗会盖住整列、丢失「我在操作哪一行」的上下文；下拉菜单天然锚定在被按的那一行上。
 *
 * R033 起的三处改动：
 * 1. **上下文头**（[title] + [subtitle]）：菜单浮在列表上方时，用户仍然要能确认自己在
 *    操作哪一条会话。之前只有两行纯文字，长按后菜单盖住了被按的那一行，等于没有上下文。
 * 2. **每项带图标**：纯文字菜单要逐字读完才知道两个选项是什么。
 * 3. **移入归档走二次确认**：归档会让会话从列表里消失（[ArchiveConfirmDialog]），
 *    而这个动作在 App 里没有「撤销」以外的补救路径——一次误触就少一条可见的会话。
 *    置顶/取消置顶是可逆且无破坏性的，不打扰用户。
 */
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    archived: Boolean,
    pinned: Boolean,
    busy: Boolean,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
) {
    // 二次确认：点「移入归档」时先 `onDismiss()` 收起菜单，再弹 `AlertDialog`。
    // 两个 Popup 重叠时层级由最后请求者决定，先收再弹是唯一不依赖实现细节的顺序。
    //
    // 用 `remember` 而不是 `rememberSaveable`：`menuOpen` 是 saveable（旋屏后菜单要留在
    // 原地），但确认框不该在重建后自己冒出来——那会把「用户按到一半」变成「App 替我
    // 决定要归档」。两者生命周期不一致是刻意的。
    var confirmingArchive by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        if (title != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
            )
        }

        DropdownMenuItem(
            text = { Text(if (archived) "取消归档" else "移入归档") },
            leadingIcon = {
                Icon(
                    imageVector = ArchiveIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            enabled = !busy,
            onClick = {
                if (archived) {
                    onDismiss()
                    onUnarchive()
                } else {
                    onDismiss()
                    confirmingArchive = true
                }
            },
        )
        DropdownMenuItem(
            text = { Text(if (pinned) "取消置顶" else "置顶") },
            leadingIcon = {
                Icon(
                    imageVector = PinIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            enabled = !busy,
            onClick = {
                onDismiss()
                if (pinned) onUnpin() else onPin()
            },
        )
    }

    if (confirmingArchive) {
        ArchiveConfirmDialog(
            title = title,
            subtitle = subtitle,
            onDismiss = { confirmingArchive = false },
            onConfirm = {
                confirmingArchive = false
                onArchive()
            },
        )
    }
}

/**
 * 「移入归档？」的二次确认。
 *
 * 为什么不用 `Snackbar` 的「撤销」：归档要发一次 `workspace/archiveSession`，
 * 而且是**按工作区批量**语义的动作（见 `SessionRepository` 的 KDoc），撤销要再发一次
 * 反向请求；网络失败时「撤销」本身也会失败，反而制造第二个不确定状态。
 * 事前确认一次，比事后承诺一个可能失败的撤销更诚实。
 */
@Composable
internal fun ArchiveConfirmDialog(
    title: String?,
    subtitle: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移入归档？") },
        text = {
            Column {
                if (title != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = bubbleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = "它会从会话列表里移走，可以从「已归档」里找回。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // 按钮文案**不能**与菜单项同名（都是「移入归档」）：`DropdownMenu` 关闭时
            // 会短暂与对话框共存，两处同名节点既让用户分不清、也让
            // `onNodeWithText` 直接命中两个节点（R033 首跑踩到）。用「确认移入」把
            // 动作和意图分开。
            TextButton(onClick = onConfirm) { Text("确认移入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 抽屉底部的「已归档 N」入口行。
 *
 * 为什么需要它：归档会话在主区 `WorkspaceRow` 里仍有一段，但抽屉（跨工作区的全局视图）
 * 完全看不到它们——归档在 App 里等于「消失」。这一行给出唯一的回收入口。
 */
@Composable
internal fun DrawerArchivedEntry(
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用 `Surface(onClick)` 而不是 `Modifier.clickable`：前者会挂 Material 的
    // `minimumInteractiveComponentSize`（48dp 保底）并带 `Role.Button`，后者两样都没有。
    // 抽屉里这是唯一的归档入口，还长在列表末尾，触达面积不该比旁边的会话行更小。
    // 顺带修掉涟漪层级：`clickable` 若排在 `clip` 之后、`Surface` 的 background 之前，
    // 涟漪会画在底色下层（几乎看不见）。
    Surface(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = bubbleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ArchiveIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已归档 $count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = ChevronDownIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
