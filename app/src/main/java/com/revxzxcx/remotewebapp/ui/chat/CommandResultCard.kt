package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning

/**
 * 一条斜杠命令的执行记录（R045）。
 *
 * ## 为什么它必须存在
 *
 * R043 把「发送」统一走了 `session/prompt`，于是 `/export`、`/review`、`/goal` 这些
 * 本来该由命令服务执行的输入，被当成普通文本投进了模型上下文。服务端的命令解析器
 * 其实也会执行它（会话日志里能查到 `command/run` → `command/done {kind:"success"}`），
 * 但 App 完全不认这两个事件，界面上**一个字都不显示**——用户点了命令菜单、选中
 * `/export`，看到的只是消息发出去、模型困惑地回一句「这条命令没有被展开」。
 *
 * ## 视觉
 *
 * 与 [TurnResultCard] 同一套骨架（圆角卡 + 左侧图标 + 标题 + 右上角状态），但语气不同：
 * 命令结果是**工具性**的，用中性色而不是错误红。执行成功给绿勾，失败给琥珀色警告，
 * 结果文本用等宽字体。
 */
@Composable
internal fun CommandResultCard(
    item: ChatItem.CommandResult,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        item.running -> scheme.onSurfaceVariant
        item.succeeded -> LocalStatusRunning.current
        else -> scheme.error
    }
    val icon = when {
        item.running -> Icons.Default.Refresh
        item.succeeded -> Icons.Default.Check
        else -> Icons.Default.Warning
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = scheme.surface,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    // done 缺 line 时（只看到结算、没看到 run）退化成命令 id 的前缀，
                    // 至少让用户知道「有条命令跑完了」，而不是一张没有主语的卡。
                    text = commandTitle(item),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        item.running -> "执行中"
                        item.succeeded -> "已完成"
                        else -> "失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.08f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            val body = item.text
            if (body.isNullOrBlank()) {
                if (item.running) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        text = "命令在桌面端执行，结果回来后显示在这里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 28.dp, top = 4.dp),
                    )
                }
            } else {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 8.dp),
                    color = scheme.outlineVariant.copy(alpha = 0.6f),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalContentColor.current,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
        }
    }
}

/**
 * 卡片标题：`/export` 这种命令名。
 *
 * `command/done` 事件不带命令名，正常情况下 reducer 已经把它和 `command/run` 合并、
 * 名字抄过来了；只有「窗口从 done 开始」这种历史回放场景才会拿不到名字。
 */
internal fun commandTitle(item: ChatItem.CommandResult): String {
    if (item.line.isNotBlank()) return item.line
    return "命令 ${item.commandId.takeLast(6)}"
}
