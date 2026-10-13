package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.describe
import com.revxzxcx.remotewebapp.core.protocol.isHealthy

/**
 * 全局连接状态条（需求 FR-4.4）。
 *
 * 为什么读 [CarrierState] 而不是各页面自己拼文案：载波只有一条（mux 的定义），因此
 * 「连没连上」是全局事实。工作区页与聊天页各自跑着自己的重连退避，若各自记状态，就会
 * 出现「聊天说已连接、工作区说重连中」这种自相矛盾的界面。让唯一的载波持有状态、
 * 由发起重试的一方汇报回来，两个页面就永远不会打架。
 *
 * [onRepair] 只在配对失效（[CarrierState.Unpaired]，终态）时出现：那是不可能靠重试恢复的
 * 情况，必须给用户一条明确的出路，而不是让他看着「重连中…」等下去（R002 P1-1）。
 */
@Composable
fun ConnectionBanner(
    carrier: CarrierState,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onRepair: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val healthy = carrier.isHealthy
    val tone = toneOf(carrier)
    Surface(color = tone.background, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = tone.dot,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(7.dp),
            ) {}
            Spacer(Modifier.size(8.dp))
            Text(
                text = carrier.describe(nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = tone.foreground,
                modifier = Modifier.weight(1f),
            )
            // 只在需要用户介入时才给按钮：健康的连接不需要「重试」。
            if (!healthy) {
                when (carrier) {
                    is CarrierState.Unpaired -> if (onRepair != null) {
                        TextButton(onClick = onRepair) { Text("重新配对", color = tone.foreground) }
                    }

                    CarrierState.Idle, CarrierState.Connecting -> Unit
                    is CarrierState.Connected, is CarrierState.Reconnecting ->
                        if (onRetry != null) {
                            TextButton(onClick = onRetry) { Text("重试", color = tone.foreground) }
                        }
                }
            }
        }
    }
}

/** 状态条配色的三档：健康（绿）/ 正在恢复（黄）/ 需要用户介入（红）。 */
private data class CarrierTone(val background: Color, val foreground: Color, val dot: Color)

private fun toneOf(carrier: CarrierState): CarrierTone = when (carrier) {
    is CarrierState.Connected -> CarrierTone(Color(0xFF12301F), Color(0xFF9BE7B8), Color(0xFF4ADE80))
    is CarrierState.Unpaired -> CarrierTone(Color(0xFF3A1E22), Color(0xFFFFB4AB), Color(0xFFEF5350))
    CarrierState.Idle, CarrierState.Connecting, is CarrierState.Reconnecting ->
        CarrierTone(Color(0xFF33280F), Color(0xFFF3D08A), Color(0xFFFBBF24))
}

/**
 * 缓存提示条（需求 FR-4.3）。
 *
 * 单独一条而不是并进 [ConnectionBanner]：两者可以同时为真（正在重连、同时显示的是缓存），
 * 合成一条会丢掉其中一半的信息。
 */
@Composable
fun CacheNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF1B2A38),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9EC5E8),
            )
        }
    }
}

/**
 * 调试页与状态条共用的时间格式化：毫秒 → `HH:mm:ss`（**本地时区**）。
 *
 * 不要写成 `(millis / 1000) % 86_400`：那是 UTC 时刻的时分秒，东八区下会比真实时间早 8
 * 小时，而连接时间、配对时间正是用户拿来对表的基准（R006 P2-2）。
 */
fun formatWallClock(millis: Long): String {
    if (millis <= 0L) return "—"
    val local = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}
