package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.theme.Motion

/**
 * 折叠分组内的思考过程——与工具条目**同一种行式形态**（用户 m45428 第 2 条）。
 *
 * 用户原话：「刚刚修复的操作界面的美化生效了，在折叠界面中的工具调用以及读写操作等，
 * 都应用了新的样式，但是，里面的思考过程的样式还是和之前一样，显得有些突兀。
 * 我希望，被折叠进去的思考过程，应该采用和其他工具一样的样式。需要注意，没有被折叠的
 * 思考过程保持原有的样式即可。」
 *
 * ## 为什么单独一个组件而不是给 [ReasoningBlock] 加参数
 * 两者的**形态本身就不同**，不是同一件事的两种配色：
 *  - 未折叠（分组外）的思考：独立卡片（实底 + 1dp 描边 + chevron），因为它单独出现时
 *    需要自己的边界；
 *  - 折叠进分组后的思考：一行 `[思考过程 chip] [首行预览] …… [N 字]`，与 `bash` / `read`
 *    那些行并排——它只是这一组操作里的**一步**，再套一层卡片会让分组内部看起来是
 *    「一堆卡片里混着一张卡片」，正是用户说的「突兀」。
 *
 * 这与 D78（分组内工具条目用行式、分组外用卡片）是同一条决策的延伸：**分组内的成员
 * 一律是行，分组外的独立条目才是卡**。
 *
 * @param text 思考正文（可能上万字，折叠态只显示首行预览）。
 * @param showTopDivider 组内第一条不画上分隔线。
 */
@Composable
internal fun ReasoningRow(
    text: String,
    showTopDivider: Boolean,
) {
    if (text.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ToolRow(
            tool = "思考过程",
            // 折叠态显示**首行**而不是「N 字」：首行是「模型在想什么」的最短有效摘要，
            // 字数只是个数字。这一点与 `ReasoningBlock` 的折叠态不同（那里标题必须带
            // 字数，因为它独自占据一整张卡片，没有别的地方能告诉用户内容有多少）。
            argument = reasoningPreview(text),
            tag = "${text.length} 字",
            tagStyle = ToolTagStyle.NEUTRAL,
            showTopDivider = showTopDivider,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    // 思考动辄上万字。不封顶的话整块文字被 Measurer 一次量完，
                    // 低端机上直接 OOM / ANR（同 `ReasoningBlock` 的理由）。
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * 思考正文的首行预览。
 *
 * 与 `ChatFold.kt` 的 `reasoningSummary` 不同：那个是给「已沉淀的思考」取**最后一个
 * 完整段落**的首行（桌面端 `latestCompletedParagraphFirstLine` 的行为，用于已完成轮次）；
 * 这里在分组内逐条展示，取**第一个**非空行更符合「这一步在干什么」的阅读顺序。
 *
 * 归一化连续空白并截到 [REASONING_PREVIEW_CHARS]：思考正文常带大段换行缩进，
 * 原样塞进单行会把行高撑开。
 */
internal fun reasoningPreview(text: String): String {
    val first = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val normalized = first.replace(REASONING_WHITESPACE, " ").trim()
    return if (normalized.length <= REASONING_PREVIEW_CHARS) {
        normalized
    } else {
        normalized.substring(0, REASONING_PREVIEW_CHARS).trimEnd() + "…"
    }
}

/** 行内思考预览最多显示这么多字符。 */
private const val REASONING_PREVIEW_CHARS = 60

/** 连续空白（含换行/制表）——提成顶层常量，避免每次调用重新编译正则。 */
private val REASONING_WHITESPACE = Regex("\\s+")
