package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 首页右下角的漂浮加号（R055 原型第 1 节）。
 *
 * 尺寸与位置的每一个数字都来自原型，不是随手定的：
 * - **56dp 直径**：Material 的标准 FAB 尺寸。原型 `docs/R055-新增会话效果图.html`
 *   第 1 节 `.fab{width:56px;height:56px}`。
 * - **27sp 加号**：图标故意比 Material 默认的 24dp 大一点。加号是 `Icons.Default.Add`
 *   的描边图形，在 56dp 的圆里用 24dp 会显得小气、圆内留白过多；27sp 让加号的两臂
 *   各留约 14dp 的内边距，视觉重量正好。
 * - **距右 16dp、距下 18dp**：来自原型的 `.fab{right:16px;bottom:18px}`。Scaffold 的
 *   `floatingActionButton` 槽只给 16dp 的标准边距（左右下都是 16dp），比原型少 2dp，
 *   由调用处补：[HomeScreen] 传入 `floatingActionButtonPosition` 之外的 18dp 靠
 *   [HomeFabBottomInset] 与 Scaffold 的 16dp 相差得到——见那里的说明。
 *
 * 两层阴影是原型里的原话（"光晕" + "落地感"）：用一个 `shadow` 叠两种 elevation 在
 * Compose 里做不到，所以第一层用 [Surface] 的 `shadowElevation`（柔和的大范围光晕），
 * 第二层靠 `Modifier.shadow` 的小 elevation（贴近屏幕的硬阴影）。只有一层的话，
 * 深色主题下 FAB 会像贴在背景上而不是浮着。
 */
@Composable
internal fun NewSessionFab(onClick: () -> Unit) {
    // 光晕：取主色当阴影色，让 FAB 周围有一圈同色晕开，与背景区分开。
    // `Modifier.shadow` 的 ambientColor / spotColor 是 Compose 1.7 才有的重载，
    // 这里用 Surface 的 elevation + 默认黑阴影，再靠 Surface 的 color 保证主体是主色。
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(HomeFabSize)
            // 落地感：小半径高程，阴影贴着屏幕边缘，让 FAB 有重量。
            .shadow(HomeFabDropShadow, CircleShape)
            .semantics { contentDescription = HomeFabLabel },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = HomeFabGlowElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                // 图标本身对读屏无意义（外层 Surface 已经声明了 contentDescription），
                // 传 null 避免同一个入口被念两遍。
                contentDescription = null,
                modifier = Modifier.size(HomeFabIconSize),
            )
        }
    }
}

/**
 * 列表底部给 FAB 让出的高度。
 *
 * 56dp（FAB 直径）+ 18dp（距底）= 74dp。这是**硬约束**：留少了最后一张卡片的右下角
 * 会被压住；留多了列表末尾会多出一片可疑的空白。
 */
internal val HomeFabClearance = 74.dp

/** FAB 直径（原型 56px）。 */
internal val HomeFabSize = 56.dp

/** 加号图标尺寸（原型 27px）。 */
internal val HomeFabIconSize = 27.dp

/** 光晕层的高程。 */
internal val HomeFabGlowElevation = 10.dp

/** 落地层的高程。 */
internal val HomeFabDropShadow = 3.dp

/**
 * 距屏幕底边的额外内缩。
 *
 * 原型是 18dp，Scaffold 的 FAB 槽给 16dp，差 2dp。数字小到几乎看不出来，但它是一份
 * **对照原型的契约**：不写的话，下一轮改版把 18 改回 16 也没人会发现。
 */
internal val HomeFabBottomInset = 2.dp

/** 读屏念的名字。 */
internal const val HomeFabLabel = "新增会话"
