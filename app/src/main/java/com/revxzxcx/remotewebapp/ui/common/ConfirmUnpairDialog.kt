package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 「解除配对」的二次确认框。
 *
 * 抽成共享组件（R007 P2-5）：M5-1 只给工作区页顶栏的「退出」加了确认，设置页
 * 「危险操作」区的同一个动作却是直接 `container.unpair()`——同一个破坏性操作两处
 * 保护强度不一致，等于把 M5-1 的防护绕过。
 *
 * 代价高是因为：**配对只能由桌面端发起**（`POST /api/pair/issue` 是 loopback-only），
 * 手机端误触一次就得跑回电脑重新扫码。M4 验收时亲身误触过（「诊断」与「退出」
 * 顶栏坐标只差 131 px）。
 *
 * ## 调用方必须用 `rememberSaveable` 承载可见性（R007d P3-9）
 *
 * 确认框自己不管可见性状态（`onDismiss` 由调用方持有）。若调用方用普通 `remember`，
 * 旋转屏幕/进程重建后确认框会**静默消失**——破坏性动作不会被执行所以没有数据风险，
 * 但二次确认被静默绕过。两个调用方（HomeScreen / SettingsScreen）都已用 `rememberSaveable`。
 *
 * 确认按钮用 error 色（R007d P3-10）：设置页的入口按钮是 error 色，对话框里最破坏性的
 * 那个按钮却是默认色，视觉权重不一致。
 */
@Composable
fun ConfirmUnpairDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出配对？") },
        text = {
            Text(
                "会清掉本机保存的设备凭证，之后需要回到桌面端重新发起配对" +
                    "（用链接或二维码）才能再次连接。\n\n" +
                    "缓存的会话与消息会保留，重新配对后仍可查看。",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("退出配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
