package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用标志（抽屉头部、配对页 hero 共用）。
 *
 * **为什么是自绘而不是 `painterResource(R.mipmap.ic_launcher)`**：R031 把启动图标换成了
 * `mipmap-anydpi/ic_launcher.xml` 的 `<adaptive-icon>`。Compose 的 `painterResource`
 * 在 Robolectric 下读到 adaptive-icon XML 会抛
 * `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`，
 * 于是任何渲染 `HomeScreen` / `PairScreen` 的测试都会整体失败（R033 首跑实录）。
 * 真正的位图在 `mipmap-*dpi/ic_launcher.png`，但按密度取哪一份由平台决定，写死任一份
 * 都会在别的密度上糊。
 *
 * 所以这里用设计令牌直接画：四角渐变色块 + 一枚列表字形。视觉上与 R031 生成的图标同族
 * （同一套渐变），不受资源系统与 Robolectric 影响。见决策 D104。
 */
@Composable
internal fun AppMark(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    corner: Dp = 9.dp,
    glyphSize: Dp = 16.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            // 渐变的四个端点与 `mipmap-anydpi/ic_launcher_background` 一致：
            // 左上 #8DDCFD、右上 #C4B9FB、左下 #B7B9F6、右下 #D3AEFE。
            // `Brush.linearGradient` 只吃一条轴上的颜色停靠点（无法表达「四角」），
            // 这里取对角线上的三个中间值，观感与四角渐变接近。
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF8DDCFD),
                        Color(0xFFC4B9FB),
                        Color(0xFFD3AEFE),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.List,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = Color(0xFF0B1420).copy(alpha = 0.72f),
        )
    }
}
