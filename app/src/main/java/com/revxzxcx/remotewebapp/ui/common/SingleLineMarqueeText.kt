package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 单行文本：放得下就安静地放着，放不下就**慢慢横向滚动**把剩下的露出来。
 *
 * 用户 m17042 第 6 条：「标题/路径过长换行：应单行截断 + 可滚动显示，注意元素位置协调」。
 *
 * 为什么不是单纯的 `maxLines = 1` + `Ellipsis`：标题和 cwd 里真正有信息量的往往在**尾部**
 * （「App公网配对 Connection Reset 问题修复」的「修复」、`F:\bot\remote-web-app\app\src\…`
 * 的末级目录）。省略号把尾部整段吃掉，用户没有任何办法看到它——只能回桌面端查。
 * 截断只负责兜住「永远是高一行」这条布局约束，滚动负责把内容还给用户。
 *
 * 三个必须一起成立的条件：
 * ① `maxLines = 1` + `softWrap = false`：不换行。换行会让顶栏从两行变三行、
 *    下面的消息区整体下移——这正是用户抱怨的那件事。
 * ② `overflow = Clip`（**不能是 Ellipsis**）：省略号会和跑马灯互相抵消。`basicMarquee`
 *    自己会测量文本是否真的超出容器，没超出就完全不画动画，所以「短标题也乱滚」不会发生，
 *    调用方不需要先量一次。
 * ③ 尊重系统的「关闭动画」（R017 铁律 4）：`ANIMATOR_DURATION_SCALE = 0` 时退化成静止的
 *    截断文本。滚动是装饰而不是信息本身——关掉动画时一个字都不该少。
 *
 * 无障碍：`Text` 的语义仍是**完整字符串**，读屏用户听到全名而不是被截断的片段。
 *
 * @param modifier 通常要带 `fillMaxWidth()` 或权重，否则 Measurable 按内容宽度量，
 *   「是否溢出」永远为假、滚动也就永远不触发。
 */
@Composable
internal fun SingleLineMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val animations = rememberAnimationsEnabled()
    val scroller = if (animations) {
        modifier.basicMarquee(
            // 停顿时间：让人读完标题开头再走。太快像故障，太慢滚完一轮要等很久。
            initialDelayMillis = MARQUEE_INITIAL_DELAY_MS,
            repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
            // 每秒 1dp：系统跑马灯的常见手感；用 dp 而非 px，不同密度下速度一致。
            velocity = 1.dp,
        )
    } else {
        modifier
    }
    Text(
        text = text,
        modifier = scroller,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

private const val MARQUEE_INITIAL_DELAY_MS = 1_200
private const val MARQUEE_REPEAT_DELAY_MS = 1_600
