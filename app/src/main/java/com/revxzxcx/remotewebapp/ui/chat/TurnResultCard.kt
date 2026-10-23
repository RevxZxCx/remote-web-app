package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.AbortCause
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.TurnOutcome
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning

/** 错误原文默认折行数；超过就跟一个「展开全文」。 */
private const val COLLAPSED_ERROR_LINES = 3

/**
 * 轮次结果卡（R043，用户 m26743 第 1 条）。
 *
 * 用户原话：「当会话意外中断的时候，比如输出被截断、模型报错、请求失败等情况下，
 * app 端是看不到具体的内容的，这里需要有一个实际的显示。」
 *
 * 三种视觉对应五类结局：
 *  - **失败**（红）：[TurnOutcome.Failed] —— 模型/网关报错，带 `code` 与原文；
 *  - **截断**（琥珀）：[TurnOutcome.Truncated] —— 输出到上限被切断，内容不完整但可续写；
 *  - **中性**（描边色）：[TurnOutcome.Aborted] / [TurnOutcome.Blocked] / [TurnOutcome.Interrupted] / [TurnOutcome.Unknown]。
 *
 * `completed` 不会走到这里（解析层就不产出卡片），所以本卡片出现的每一帧都是
 * 「这一轮出了点事」——但仍不能一律刷红：用户自己按的停止键不该像故障。
 *
 * @param onRetry 重试本轮；`null` 表示当前结局重试没有意义（例如配额用尽、凭证缺失），
 *   此时**不显示**按钮——点了还是失败按钮比没有按钮更糟。
 * @param onContinue 继续写（仅截断时可用）。
 */
@Composable
internal fun TurnResultCard(
    item: ChatItem.TurnResult,
    onRetry: (() -> Unit)?,
    onContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 这一轮原始消息带附件、而「重试本轮」不带回去时置真。
     *
     * 重试只重发正文（附件是一次性 content 块，重放等于伪造一次新上传），所以带图
     * 失败的那一轮重试注定复现不出原问题。与其装作能一模一样重来，不如在按钮旁
     * 直接说清楚（R043 审查 P3-1）。
     */
    retryDropsAttachments: Boolean = false,
) {
    val outcome = item.outcome
    val tone = toneOf(outcome)
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(item.seq) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tone.accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 头：图标 + 标题 + 结局标签 ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(tone.accent.copy(alpha = 0.16f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tone.icon,
                        contentDescription = null,
                        tint = tone.accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = titleOf(outcome),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                tagOf(outcome)?.let { tag ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = tone.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .background(tone.accent.copy(alpha = 0.08f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            // ── 体：轮次号 + 错误原文 ──────────────────────────────────────
            val errorText = errorTextOf(outcome)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 10.dp, bottom = if (outcome is TurnOutcome.Aborted && errorText == null) 9.dp else 0.dp),
            ) {
                Text(
                    // turn 缺失时解析器回落成 0，但「第 1 轮」是假信息——只有真的
                    // 拿到了轮次才显示，否则整行省掉（R043 审查 P2-2 的连带）。
                    text = if (item.turnKnown) "第 ${item.turn + 1} 轮" else "本轮",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (errorText != null) {
                    Spacer(Modifier.size(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(errorBlockBg(), RoundedCornerShape(10.dp))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            // 原文常带引号、花括号、request_id：等宽 + 独立底色 = 「这是机器输出」。
                            color = errorBlockFg(),
                            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_ERROR_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!expanded && errorText.length > 90) {
                        TextButton(
                            onClick = { expanded = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 0.dp,
                                vertical = 0.dp,
                            ),
                        ) {
                            Text(
                                text = "展开全文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (errorText != null) Spacer(Modifier.size(9.dp))

            // ── 动作：重试 / 继续写 / 复制 ──────────────────────────────────
            val showActions = onRetry != null || onContinue != null || errorText != null
            if (showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, end = 10.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        // 带附件的那一轮重试发不出附件——这一句必须和「重试本轮」同时出现，
                        // 否则用户以为能原样重来（R043 审查 P3-1）。
                        text = if (retryDropsAttachments && onRetry != null) {
                            "${hintOf(outcome)}（重试不含附件）"
                        } else {
                            hintOf(outcome)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f),
                    )
                    if (errorText != null) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(errorText)) }) {
                            Text("复制", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    onContinue?.let { run ->
                        PrimaryPill(text = "继续写", onClick = run, tone = tone.accent)
                    }
                    onRetry?.let { run ->
                        PrimaryPill(text = "重试本轮", onClick = run, tone = tone.accent)
                    }
                }
            }
        }
    }
}

/** 视觉基调：把六种 `kind` 压到三种颜色 + 一个图标。 */
private data class TurnTone(val accent: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun toneOf(outcome: TurnOutcome): TurnTone = when (outcome) {
    is TurnOutcome.Failed, is TurnOutcome.Unknown -> TurnTone(
        accent = MaterialTheme.colorScheme.error,
        icon = Icons.Default.Warning,
    )
    is TurnOutcome.Truncated -> TurnTone(
        accent = MaterialTheme.colorScheme.tertiary,
        icon = Icons.Default.Info,
    )
    // 中止 / 拦截 / 断流一律中性：都不是故障，用户不需要「警报」。
    is TurnOutcome.Aborted, is TurnOutcome.Blocked, is TurnOutcome.Interrupted -> TurnTone(
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = Icons.Default.Refresh,
    )
    is TurnOutcome.Completed -> TurnTone(
        accent = LocalStatusRunning.current,
        icon = Icons.Default.Check,
    )
}

/**
 * 卡片标题。
 *
 * 文案必须按 `kind` 分开——「你点了停止」和「进程重启把它掐了」对用户是完全不同的两件事，
 * 合并成一句「本轮已中止」会让人分不清是自己还是系统干的。
 */
internal fun titleOf(outcome: TurnOutcome): String = when (outcome) {
    is TurnOutcome.Failed -> when (outcome.code) {
        "QUOTA_EXCEEDED" -> "本轮失败 · 额度已用完"
        "MISSING_CREDENTIAL" -> "本轮失败 · 未登录"
        "RATE_LIMIT" -> "本轮失败 · 模型限流"
        "HTTP_413", "CLIENT_ERROR" -> "本轮失败 · 请求过大"
        "REASONING_LOOP" -> "本轮失败 · 思考陷入循环"
        "AUTH" -> "本轮失败 · 鉴权被拒"
        "NO_ADAPTER" -> "本轮失败 · 没有可用通道"
        "TRANSPORT", "SERVER" -> "本轮失败 · 上游错误"
        else -> "本轮失败"
    }
    is TurnOutcome.Truncated -> "输出被截断"
    is TurnOutcome.Aborted -> when (outcome.by) {
        AbortCause.USER -> "本轮已中止"
        AbortCause.PARENT -> "被父会话中止"
        AbortCause.DISPOSED -> "连接中断，本轮中止"
        AbortCause.UNKNOWN -> "本轮已中止"
    }
    is TurnOutcome.Blocked -> "本轮被拦下"
    is TurnOutcome.Interrupted -> "生成被中断"
    is TurnOutcome.Unknown -> "本轮结束异常"
    is TurnOutcome.Completed -> "本轮完成"
}

/** 右上角结局标签：优先用服务端的 `code`，没有就退回 `kind` 的短写。 */
internal fun tagOf(outcome: TurnOutcome): String? = when (outcome) {
    is TurnOutcome.Failed -> outcome.code ?: "ERROR"
    // 截断 / 拦下 / 中断 的标题已经把话说完了（「输出被截断」「本轮被拦下」
    // 「生成被中断」），再挂一个 MAX-TOKENS / BLOCKED / INTERRUPTED 只是
    // 把同一件事说两遍，还占掉右上角唯一的位置。tag 留给「标题里没有的那半句」：
    // 失败的 wire code、中止的发起方、未知结局的 kind。
    is TurnOutcome.Truncated -> null
    is TurnOutcome.Blocked -> null
    is TurnOutcome.Interrupted -> null
    is TurnOutcome.Aborted -> when (outcome.by) {
        AbortCause.USER -> "STOPPED"
        AbortCause.PARENT -> "PARENT"
        AbortCause.DISPOSED -> "DISPOSED"
        AbortCause.UNKNOWN -> null
    }
    is TurnOutcome.Unknown -> outcome.kind.uppercase()
    is TurnOutcome.Completed -> null
}

/** 等宽深底里那段原文；没有可展示原文时返回 `null`。 */
internal fun errorTextOf(outcome: TurnOutcome): String? = when (outcome) {
    is TurnOutcome.Failed -> outcome.message.ifBlank {
        // 服务端偶尔只给 code 不给 message（实测 INVALID_REQUEST 有这种）。
        // 这时至少把 code 与 status 摆出来，比空白强。
        listOfNotNull(outcome.code, outcome.status?.let { "HTTP $it" }).joinToString(" · ")
            .ifBlank { null }
    }
    else -> null
}

/** 动作行左侧那句灰色说明。 */
internal fun hintOf(outcome: TurnOutcome): String = when (outcome) {
    is TurnOutcome.Failed -> when (outcome.code) {
        "MISSING_CREDENTIAL" -> "需要先在桌面端登录模型账号"
        "QUOTA_EXCEEDED", "AUTH", "NO_ADAPTER" -> "重试解决不了，需要在桌面端处理"
        "HTTP_413", "CLIENT_ERROR" -> "去掉大附件后重试"
        "REASONING_LOOP" -> "额度没用完，可以直接继续"
        else -> "直接重发本轮，不用再手打一遍"
    }
    is TurnOutcome.Truncated -> "额度用满，点「继续写」接着输出"
    is TurnOutcome.Aborted -> when (outcome.by) {
        AbortCause.USER -> "已保留前面产出"
        else -> "内容可能不完整"
    }
    is TurnOutcome.Blocked -> "需要去桌面端批准后继续"
    is TurnOutcome.Interrupted -> "内容可能只有半截"
    // 未知 kind 也要给一句话。空串会留下一个只剩「复制」按钮的空行，
    // 用户看不出这是「不认识」还是「没话说」。
    is TurnOutcome.Unknown -> "这一轮的结束原因 App 还不认识"
    is TurnOutcome.Completed -> ""
}

/**
 * 落在 [tone] 底色上的前景色。
 *
 * 不能按亮度二分：浅色主题的 `tertiary` 是 `#F2C14E`（亮度 0.576 > 0.5），二分法会
 * 把「继续写」胶囊的字取成 `background`，实测对比度只有 1.56:1——比原来写死白色
 * 的 1.68:1 还差（R045 审查 P2）。改成在两个候选里按实际对比度择优，谁高用谁。
 */
@Composable
private fun onToneOf(tone: Color): Color {
    // 候选集必须包含主题为**这块底色**定义的前景色，否则择优也选不出对的：
    // `tertiary = #F2C14E` 上白色只有 1.68:1、`background` 只有 1.56:1，两者都不过 AA，
    // 而主题本来就为它备了 `onTertiary = #3A2A00`（约 7:1）。同理失败态用 `onError`
    // （R045 三轮审查 P2）。
    val candidates = listOf(
        MaterialTheme.colorScheme.onTertiary,
        MaterialTheme.colorScheme.onError,
        Color.White,
        MaterialTheme.colorScheme.background,
    )
    return candidates.maxByOrNull { contrastRatio(it, tone) } ?: Color.White
}

/**
 * WCAG 相对对比度：(L1 + 0.05) / (L2 + 0.05)，L 取较亮者。
 *
 * 用与 [luminance] 同一套相对亮度公式，避免再引一个色彩库依赖。
 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * 错误原文块的底色。
 *
 * 早先写死 `Color(0xFF0A121D)`：深色主题下没问题，但浅色主题里卡片是白底，
 * 中间糊一块近乎纯黑的方块，字色又被写死成 `#C8D8EC` 的浅蓝——观感像另开了一个
 * 暗色页面（用户 m27926 第 1 条配图）。
 *
 * 取 `surfaceVariant` 而不是 `surfaceContainerLowest` / `surfaceContainerHighest`：
 * 后两个令牌本工程 [Theme.kt] 从未定义，会落到 Material3 内置基线（深色 `#0F0D13`、
 * 浅色 `#E6E0E9`），是中性**紫灰**而不是本 App 的蓝灰 ramp——色相只是从「蓝黑」换成
 * 「紫黑」，问题没解决。这与 [Theme.kt] 里为 tertiary 记录过的同类事故同源
 * （R044 四轮审查 P3）。`surfaceVariant` 两套主题都显式定义，且比 surface 各偏一档：
 * 深色 `#1A2942` 相对 `#111C2B` 抬高一档（内嵌面板），浅色 `#E9EFF7` 相对 `#FFFFFF`
 * 压一档（灰底块），都是「与卡片同族但可区分」的效果。
 */
@Composable
private fun errorBlockBg(): Color = MaterialTheme.colorScheme.surfaceVariant

/**
 * 错误原文块的字色。
 *
 * 深色主题下是浅蓝灰（机器输出的观感），浅色主题下用 `onSurface` 保证 4.5:1。
 */
@Composable
private fun errorBlockFg(): Color = MaterialTheme.colorScheme.onSurface

/** 主色胶囊按钮（与 Composer 的发送键同一套形状语言）。 */
@Composable
private fun PrimaryPill(text: String, onClick: () -> Unit, tone: Color) {
    Surface(
        color = tone,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                // 之前写死 `Color.White`。tone 不一定是深色——浅色主题下 tertiary 是
                // #C08A12 那种琥珀、onSurfaceVariant 是 #45586D，白字压上去对比度不够。
                // 按底色亮度反推，深底白字、浅底深字。
                color = onToneOf(tone),
            )
        }
    }
}

/**
 * 这个结局能不能重试。
 *
 * 判据是**重试是否可能成功**，不是「有没有出错」：
 *  - 配额用尽 / 凭证缺失 / 鉴权失败 / 没有适配 provider → 重试一定还是同一个错，
 *    给按钮等于骗用户点一次；
 *  - 请求过大 → 得先改内容，重发原样还是 413；
 *  - 其余（限流、上游 5xx、传输中断、未知）→ 重试有意义。
 *
 * 抽成内部函数是为了能纯 JVM 单测——按钮出现/消失这种事，UI 测试只能看到结果，
 * 看不到判据。
 */
internal fun canRetry(outcome: TurnOutcome): Boolean = when (outcome) {
    is TurnOutcome.Failed -> outcome.code !in NON_RETRYABLE_CODES
    is TurnOutcome.Interrupted -> true
    is TurnOutcome.Unknown -> true
    else -> false
}

/** 重试注定失败的错误码（见 [canRetry]）。 */
internal val NON_RETRYABLE_CODES = setOf(
    "QUOTA_EXCEEDED",
    "MISSING_CREDENTIAL",
    "AUTH",
    "NO_ADAPTER",
    "HTTP_413",
    "CLIENT_ERROR",
)

/** 这个结局能不能「继续写」：只有截断可以，其余继续只会再撞同一堵墙。 */
internal fun canContinue(outcome: TurnOutcome): Boolean = outcome is TurnOutcome.Truncated

/** 分隔用：轮次结果卡上方是否要垫一条细线（与 Notice 分隔行一致的视觉节奏）。 */
@Composable
internal fun TurnResultDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
