package com.revxzxcx.remotewebapp.ui.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.events.ApprovalPolicy
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.events.SessionPermission
import com.revxzxcx.remotewebapp.ui.common.IconSizes
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 输入框展开态里的「审批权限」入口（用户 m27990 参考图中的 ⚠️ 位置）。
 *
 * ## 为什么用 ⚠️ 而不是齿轮
 * 参考图里这个位置是警告三角，语义就是「这里有个需要你拍板的事」。本 App 把它固定为
 * **审批权限**入口：平时显示当前策略，有待审批请求时变成强调色并带计数。
 *
 * ## 为什么点它是弹面板而不是开关
 * 服务端没有暴露「设置当前会话权限」的远程方法（`session` 命名空间 24 个方法里没有
 * setApprovalPolicy / setPermission，见 [SessionPermission] KDoc）。所以这里**不能**做成
 * 开关——做成开关就会有用户点了没反应，比没有开关更糟。面板里能看策略、能批请求，
 * 这两件事都是真实可做的。
 */
@Composable
internal fun PermissionChip(
    permission: SessionPermission,
    pendingCount: Int,
    onClick: () -> Unit,
) {
    val urgent = pendingCount > 0
    val tint = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val edge = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    // 用 Box + clickable 而不是 `Surface(onClick=…)`：这里必须把 contentDescription 与
    // 点击语义挂到**同一个**节点上（`Modifier.semantics{}` 紧跟 `.clickable{}`）。
    // `Surface(onClick)` 的 modifier 参数与它内部 clickable 的先后顺序不受我们控制，
    // 渲染测试里 `onNodeWithContentDescription("审批权限")` 会找不到合并后的节点。
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, edge, pillShape)
            .semantics { contentDescription = "审批权限" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (urgent) "待批 $pendingCount" else permission.shortLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 徽标上的短文案（[SessionPermission.summary] 太长，徽标里放不下）。
 *
 * `approvalPolicy` 缺席时**不要**直接显示「权限未知」——`permission/preset` 与
 * `sandbox/mode` 两条路径只会带模式不带策略，那时回落显示模式标签（与
 * [SessionPermission.summary] 的取法一致）。真的一无所知才说未知。
 */
private fun SessionPermission.shortLabel(): String = when {
    !known -> "权限未知"
    approvalPolicy == ApprovalPolicy.NEVER -> "不问直接放行"
    approvalPolicy == ApprovalPolicy.ASK -> "每次要批准"
    sandboxMode != null -> sandboxMode.label
    preset != null -> preset.label
    else -> "权限未知"
}

/**
 * 有待审批请求时，出现在输入框**上方**的卡片。
 *
 * 位置是刻意的：请求里通常包含「桌面端正在等一个命令要不要跑」，用户大概率正盯着输入框
 * 准备说话。放在消息列表里会被长历史埋掉，放在顶栏则离手指太远。
 *
 * 卡片用 error 描边而不是 error 实底：实底在整个输入区旁边太抢眼，而且这里是**可滚动
 * 消息区与固定输入区之间的过渡带**，重色会让人以为输入区坏了。
 */
@Composable
internal fun ApprovalBanner(
    approvals: List<ApprovalRequest>,
    onAllow: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    if (approvals.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "桌面端在等你授权（${approvals.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            approvals.forEach { request ->
                ApprovalRow(request = request, onAllow = onAllow, onReject = onReject)
            }
        }
    }
}

/** 一条审批请求。 */
@Composable
private fun ApprovalRow(
    request: ApprovalRequest,
    onAllow: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        // 工具名用等宽字体：它是标识符不是自然语言，等宽让 `pwsh` / `str_replace_editor`
        // 这类名字在列表里对齐（R017「等宽只用于标识符」的边界内）。
        Text(
            text = request.toolName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = request.reasonText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onAllow(request.eventId) }) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.composer),
                )
                Text("允许一次", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = { onReject(request.eventId) }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.composer),
                )
                Text("拒绝", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * 「审批权限」详情面板。
 *
 * 上半是**只读**的当前策略（改不了，原因见 [SessionPermission] KDoc）；下半是待批请求
 * 清单（与 [ApprovalBanner] 同一份数据，这里是「全部」视角）。把「不能改」写在面板里
 * 而不是藏在帮助里：用户点进来第一件事就是想改，不说明白他就会到处找开关。
 */
@Composable
internal fun ApprovalSheet(
    approvals: List<ApprovalRequest>,
    permission: SessionPermission,
    error: String?,
    onAllow: (String) -> Unit,
    onReject: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("会话权限") },
        text = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = groupShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = permission.summary(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "策略只能在桌面端改，手机端只读。" +
                                "改完之后这里会自动跟上（会话日志里会记一条事件）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (approvals.isEmpty()) {
                    Text(
                        text = "现在没有等待授权的请求。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    approvals.forEach { request ->
                        ApprovalRow(request = request, onAllow = onAllow, onReject = onReject)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("知道了") }
        },
    )
}
