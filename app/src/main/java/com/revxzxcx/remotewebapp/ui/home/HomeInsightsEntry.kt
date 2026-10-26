package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.common.AppIconButton

/**
 * 顶栏的「总上下文洞察」入口（R050 定稿的 B 方案）。
 *
 * ## 为什么是顶栏按钮而不是贴边手柄
 *
 * 首页已经有两条常驻导航线：左端 ☰（会话抽屉）和右端溢出菜单。贴边手柄会再压一条
 * 视觉重量进去，而且悬浮在列表上会随滚动与行内容打架。顶栏按钮与 ☰ 左右对称，
 * 一眼就能读出「左边是会话，右边是总量」这层关系。
 *
 * ## 圆点：提醒，不是状态
 *
 * 语义沿用 R049 里用户对会话状态点的纠正——「这个点不是常驻的，而是一个完成的标记，
 * 提醒用户查看」。所以：
 *
 * - 只在**相对上次打开洞察**有了新数据时亮（判据在 [shouldLightInsightsDot]）；
 * - 点开抽屉即灭（由调用方调 `HomeViewModel.markInsightsSeen()`）；
 * - 不显示数字、不显示百分比——顶栏放不下，也没人要在图标上读一个数。
 *
 * 圆点压在图标右上角、略微探出按钮边界：完全压在图标内部会与 ◔ 的楔形混作一团，
 * 完全挪到外面又会被 `TopAppBar` 的宽度裁掉一半。
 *
 * @param hasNew 是否有未查看的新数据（决定圆点亮不亮）。
 * @param onClick 点击回调。调用方要在这里**同时**标记已查看并打开抽屉。
 */
@Composable
internal fun InsightsIconButton(
    hasNew: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 「有新数据」并进按钮自己的描述里，而不是挂在圆点上。
        //
        // 圆点是 5dp 的纯装饰：若在它上面写 contentDescription，读屏会把它当成
        // 一个**独立**的可停留节点（「有新数据」），而按钮本身仍只念「总上下文洞察」，
        // 用户要么听到两个孤立词、要么漏掉状态。所以状态归按钮、圆点对读屏隐形。
        AppIconButton(
            onClick = onClick,
            contentDescription = if (hasNew) "总上下文洞察，有新数据" else "总上下文洞察",
        ) {
            Icon(imageVector = InsightsIcon, contentDescription = null)
        }
        if (hasNew) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = DotInset, y = DotInset)
                    .size(DotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** 圆点直径。5dp 与原型第 1 节标注的「5dp 主色圆点」一致。 */
private val DotSize = 5.dp

/** 圆点从右上角向内缩进的量（负向外移）。 */
private val DotInset = (-1).dp
