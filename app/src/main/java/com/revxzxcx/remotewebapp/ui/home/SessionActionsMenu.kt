package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 会话行的长按菜单：归档 / 取消归档 + 置顶 / 取消置顶。
 *
 * **为什么没有「删除」**：服务端没有删除会话的 API。已把 SDK dump（`F:\bot\_sdk`）
 * 全量 grep 过 `deleteSession` / `session\/delete` / `removeSession`，零命中；
 * session-controller 的 21 个 invocation 里与「移除」相关的只有 archive/unarchive。
 * 桌面端 Web UI 提供的同样是「归档」。所以这里也只做归档，文案不说「删除」——
 * 承诺一个做不到的操作比少一个功能更糟（需求文档 Q14 同类的诚实原则）。
 *
 * 菜单用 `DropdownMenu` 而不是 `ModalBottomSheet`：行在 `LazyColumn` 里，
 * 底部弹窗会盖住整列、丢失「我在操作哪一行」的上下文；下拉菜单天然锚定在被按的那一行上。
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
) {
    Box(modifier) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(if (archived) "取消归档" else "归档") },
                enabled = !busy,
                onClick = {
                    onDismiss()
                    if (archived) onUnarchive() else onArchive()
                },
            )
            DropdownMenuItem(
                text = { Text(if (pinned) "取消置顶" else "置顶") },
                enabled = !busy,
                onClick = {
                    onDismiss()
                    if (pinned) onUnpin() else onPin()
                },
            )
        }
    }
}
