package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 * [onRepairRequest] 只在配对失效（[CarrierState.Unpaired]，终态）时出现：那是不可能靠重试恢复的
 * 情况，必须给用户一条明确的出路，而不是让他看着「重连中…」等下去（R002 P1-1）。
 *
 * ## `onRepairRequest` 与 `onRetry` 不是一回事（R018 P1-1）
 *
 * - `onRetry` = 重连，同一个凭证再试一次，无破坏性；
 * - `onRepairRequest` = **退出配对并重新配对**，会擦掉本机凭证，是破坏性操作。
 *
 * 早前它叫 `onRepair`，两个屏幕都传了不带二次确认的 `onUnpair` 进来——用户看到
 * 「重新配对」四个字会以为是重连，点一下凭证就没了。现在名字里带 `Request`，
 * 且确认框由 AppRoot 统一渲染，调用方传进来的 `onRepairRequest` 已经是确认后的动作。
 *
 * ## 时钟自持（R007d P3-7）
 *
 * 只有 [CarrierState.Reconnecting] 的文案（「Ns 后重试」）需要 [nowMillis]。早前由调用方
 * 把 `now` 传进来，于是一个 30 秒一跳的时钟被提到了两个屏幕的顶层 body——横幅自己在
 * Connected/Idle/Unpaired 下根本不用 now，也照样重组、白算一次 `describe()`。
 * 现在改成 [ConnectionBanner] 内部按需起时钟：非重连态不起、不重组。
 */
@Composable
fun ConnectionBanner(
    carrier: CarrierState,
    modifier: Modifier = Modifier,
    onRepairRequest: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val needsClock = carrier is CarrierState.Reconnecting
    val nowMillis = if (needsClock) rememberBannerClock() else 0L
    ConnectionBanner(carrier, nowMillis, modifier, onRepairRequest, onRetry)
}

/**
 * 秒级时钟（R018 P2-3）。
 *
 * 与 `ui/home/TimeText.kt` 的 `rememberNow()` 是**两个不同粒度**的工具，不能混用：
 * - `rememberNow()` 30 秒一跳，为「X 分钟前」设计；
 * - 本函数 1 秒一跳，为「Ns 前 / Ns 后重试」设计。
 *
 * 诊断页原来复用 `rememberNow()`，于是「已连上（12s 前）」最多滞后 30 秒才更新，
 * 中间 29 秒显示的是过期数字——而调试页的定位是「每个数字都对应一个真实排查过的
 * 问题」，数值本身不可信就等于这个页面对用户说谎。横幅的「Ns 后重试」倒计时同理。
 */
@Composable
fun rememberSecondClock(): Long {
    val now = produceState(System.currentTimeMillis(), true) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return now.value
}

/** 只在需要倒计时时跑的时钟：1 秒一跳（比重启退避的粒度细，倒计时才不会跳变）。 */
@Composable
private fun rememberBannerClock(): Long = rememberSecondClock()

/** 带显式时钟的版本：给渲染测试用（测试里不能让真实时钟参与断言）。 */
@Composable
fun ConnectionBanner(
    carrier: CarrierState,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onRepairRequest: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val healthy = carrier.isHealthy
    val tone = toneOf(carrier)
    Surface(color = tone.background, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // R017：状态点的呼吸动画换成 `GeneratingGlyph(SM)`。原来这里是第四处
            // 手写的 `rememberInfiniteTransition` 呼吸点（另三处：折叠分组、会话行
            // 运行指示、流式占位）。R017 总原则 ② 要求全 App「正在运行」只由一个
            // 组件表达，于是全部收敛到均衡器上。横幅行高只有 ~28dp，用 7dp 圆点换
            // 16dp 均衡器不会挤到文案。
            if (carrier is CarrierState.Reconnecting) {
                GeneratingGlyph(size = GlyphSize.SM, tint = tone.foreground)
                Spacer(Modifier.size(8.dp))
            } else {
                Surface(
                    color = tone.dot,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(7.dp),
                ) {}
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = carrier.describe(nowMillis),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = tone.foreground,
                modifier = Modifier.weight(1f),
            )
            // 只在需要用户介入时才给按钮：健康的连接不需要「重试」。
            if (!healthy) {
                when (carrier) {
                    is CarrierState.Unpaired -> if (onRepairRequest != null) {
                        TextButton(
                            onClick = onRepairRequest,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            Text("重新配对", color = tone.foreground, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    CarrierState.Idle, CarrierState.Connecting -> Unit
                    is CarrierState.Connected, is CarrierState.Reconnecting ->
                        if (onRetry != null) {
                            TextButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            ) {
                                Text("重试", color = tone.foreground, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                }
            }
        }
    }
}

/**
 * 状态条配色的三档：健康（绿）/ 正在恢复（黄）/ 需要用户介入（红）。
 *
 * **必须跟随主题**：早先是三组硬编码深色值，浅色主题下会变成「白底上压深红底 + 亮红字」，
 * 与整个界面的底色完全不协调（R016 修）。现在从 `colorScheme` 的 container/onContainer
 * 角色派生，两套主题各自调过对比度。
 */
private data class CarrierTone(val background: Color, val foreground: Color, val dot: Color)

@Composable
private fun toneOf(carrier: CarrierState): CarrierTone {
    val scheme = MaterialTheme.colorScheme
    val container = scheme.surfaceVariant
    val onContainer = scheme.onSurfaceVariant
    return when (carrier) {
        is CarrierState.Connected -> CarrierTone(container, onContainer, scheme.primary)
        is CarrierState.Unpaired -> CarrierTone(container, scheme.error, scheme.error)
        CarrierState.Idle, CarrierState.Connecting, is CarrierState.Reconnecting ->
            CarrierTone(container, onContainer, scheme.tertiary)
    }
}

/**
 * 缓存提示条（需求 FR-4.3）。
 *
 * 单独一条而不是并进 [ConnectionBanner]：两者可以同时为真（正在重连、同时显示的是缓存），
 * 合成一条会丢掉其中一半的信息。
 *
 * 配色同样跟随主题（早先是硬编码深蓝底 + 亮蓝字，浅色主题下刺眼）——R016。
 */
@Composable
fun CacheNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 毫秒 → `HH:mm:ss`（**本地时区**）。
 *
 * 调试页（[com.revxzxcx.remotewebapp.ui.debug.DebugScreen]）的建连时间/配对时间在用。
 * 放在这里而不是 `TimeText`：那是 `internal` 且只服务相对时间，这里是绝对时刻，
 * 两个屏幕的可见性需求不同。
 *
 * 不要写成 `(millis / 1000) % 86_400`：那是 UTC 时刻的时分秒，东八区下会比真实时间早 8
 * 小时，而连接时间、配对时间正是用户拿来对表的基准（R006 P2-2）。
 */
fun formatWallClock(millis: Long): String {
    if (millis <= 0L) return "—"
    val local = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}
