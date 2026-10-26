package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.SessionStatus
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusAttention
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusError
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning

/**
 * 会话列表行的状态标记（R049，用户 m32994）。
 *
 * ## 为什么不是「一个点」
 *
 * 用户的原话是「对话完成后图标应该从加载切换为绿色点」「报错或者需要继续时也该有颜色」。
 * 四态里 [SessionStatus.Ongoing] 必须**保留**原来的均衡器动画——它是列表里唯一告诉用户
 * 「这个会话此刻在动」的信号，换成静态点等于把运行态降级成完成态。所以这个组件的形状是：
 *
 * | 状态 | 视觉 | 读屏 |
 * |---|---|---|
 * | [SessionStatus.Ongoing] | `GeneratingGlyph`（三柱动画，关动画时退化为静态三柱） | 「正在生成」 |
 * | [SessionStatus.Failed] | 红点 | 「上一轮失败，点进去看」+ 服务端给的原因（有的话） |
 * | [SessionStatus.Attention] | 琥珀点 | 「等待处理」 |
 * | [SessionStatus.Done] | 绿点 | 「刚跑完，还没看」 |
 * | [SessionStatus.Idle] | 不渲染 | —— |
 *
 * ## 后三态都是「一次性提醒」，不是常驻状态
 *
 * 用户 m33325 纠正过：「这个点不是常驻的，而是一个完成的标记，提醒用户查看对话的」。
 * 所以绿点只代表「跑完了但你还没来看」，红点只代表「上一轮挂了但你还没来看」——
 * 进来看了、或它又跑起来，都会消失（判据在 `core/session/CompletionLedger`）。
 * 组件的读屏文案也按这个语义写：「刚跑完，还没看」而不是「已完成」——后者听起来
 * 像状态，会在点消失后让用户以为「它又没完成了」。
 *
 * 只有 [SessionStatus.Ongoing] 是真正的状态。
 *
 * ## 为什么状态点比动画小
 *
 * 动画是 16dp 的方框，静态点取 8dp 圆：同一个位置，静下来时视觉重量应当比在跑时轻，
 * 否则一屏静止的红/绿点会盖过标题。8dp 也是桌面端 `StateDot` 的常规尺寸量级。
 *
 * ## 无障碍
 *
 * 每个状态都有自己的 [contentDescription]。列表行本身不朗读状态——它把
 * `stateDescription` 留给「已置顶/已归档」那类标记（见 `HomeScreen` 的 `DrawerSessionRow`），
 * 而这里的点是**独立节点**，读屏会单独播报。两者不冲突：状态点是「现在怎么样」，
 * 行上的 stateDescription 是「这一行有什么标记」。
 */
@Composable
fun SessionStatusDot(
    status: SessionStatus,
    modifier: Modifier = Modifier,
    size: Dp = StatusDotSize,
    failureReason: String = "",
) {
    when (status) {
        // 进行中沿用列表里既有的三柱动画（R017 定的统一「生成中」符号），不画点。
        SessionStatus.Ongoing -> GeneratingGlyph(
            modifier = modifier,
            size = GlyphSize.SM,
            tint = LocalStatusRunning.current,
            description = "正在生成",
        )
        SessionStatus.Failed -> Dot(
            modifier = modifier,
            size = size,
            color = LocalStatusError.current,
            // 服务端给了原因就读出来，没给就用通用文案——红点该亮就得亮，
            // 文案只是附加信息（`RemoteEvents.SessionNotification.Error.message`）。
            description = if (failureReason.isBlank()) {
                "上一轮失败，点进去看"
            } else {
                "上一轮失败，点进去看：$failureReason"
            },
        )
        SessionStatus.Attention -> Dot(
            modifier = modifier,
            size = size,
            color = LocalStatusAttention.current,
            description = "等待处理",
        )
        SessionStatus.Done -> Dot(
            modifier = modifier,
            size = size,
            color = LocalStatusRunning.current,
            description = "刚跑完，还没看",
        )
        // 空白会话（R038 已把它整个从列表里滤掉）不画任何标记。
        SessionStatus.Idle -> Unit
    }
}

/** 静态状态点的边长。见 [SessionStatusDot] 里「为什么比动画小」的说明。 */
private val StatusDotSize = 8.dp

@Composable
private fun Dot(
    modifier: Modifier,
    size: Dp,
    color: androidx.compose.ui.graphics.Color,
    description: String,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}
