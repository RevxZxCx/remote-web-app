package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶栏 / 行内图标按钮（R017）。
 *
 * 与裸 [IconButton] 的差别只有一个：`contentDescription` 是**必填**的。
 * 本工程所有图标都是纯矢量、没有文案，少了它 TalkBack 只会念「按钮」，
 * 而 M5 的真机验收全程靠 `uiautomator` 的 `content-desc` 定位控件——
 * 漏一个描述等于同时丢掉无障碍与自动化验收两条线索。
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) { content() }
}

/**
 * 溢出菜单的锚点：把锚点控件与 [AppOverflowMenu] 摆进同一个 [Box]。
 *
 * 为什么不把 `DropdownMenu` 写在调用处：每个屏幕都要重复一遍
 * 「remember 展开状态 → Box 包 IconButton → DropdownMenu 挂在 Box 里」，
 * 漏掉 `onDismissRequest` 的后果是菜单点外面不关。收拢成一个锚点之后，
 * 「点外面收起」这件事只在一处实现。
 *
 * **锚点自身必须是可点的**（通常是 [AppIconButton]）：这里不再套一层
 * clickable——早期版本在外层 40dp Box 上挂过 clickable，而内层 IconButton
 * 有 48dp，完整覆盖并消费触摸，外层的 ripple 永远不可见（R018 P3-3）。
 */
@Composable
fun OverflowMenuAnchor(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    anchor: @Composable () -> Unit,
    menu: @Composable () -> Unit,
) {
    Box {
        anchor()
        AppOverflowMenu(expanded = expanded, onDismiss = { onExpandedChange(false) }) {
            menu()
        }
    }
}

/** 菜单容器圆角：原型 `docs/R054-多主机效果图.html:145` 的 `.menu{border-radius:var(--r-md)}` = 14px。 */
internal val OverflowMenuCornerRadius = 14.dp

internal val OverflowMenuShape = RoundedCornerShape(OverflowMenuCornerRadius)

/** 菜单项圆角：原型 `:148` 的 `.mi{border-radius:var(--r-sm)}` = 10px。 */
internal val OverflowMenuRowCornerRadius = 10.dp

internal val OverflowMenuRowShape = RoundedCornerShape(OverflowMenuRowCornerRadius)

/**
 * 菜单最小宽度：照原型 `:146` 的 `.menu{width:212px}`。
 *
 * 为什么按原型的定宽而不是「按内容自适应」：菜单里同时有「刷新列表」（4 字）和
 * 「连接与缓存诊断」（7 字），自适应会让每次弹出的宽度都不一样——
 * 同一个按钮点两次得到两个尺寸，是比窄一点更明显的毛病。
 * 原型也是一次画死 212px。
 */
private val OverflowMenuMinWidth = 212.dp

/** 菜单最大宽度：极端字号/长文案时换行而不是把菜单撑到整个屏幕宽。 */
private val OverflowMenuMaxWidth = 268.dp

/**
 * 溢出菜单本体。
 *
 * 与直接写 `DropdownMenuItem` 的差别：① 每条都要图标（R017「全 App 图标化」）；
 * ② 分隔线是一等公民（破坏性动作必须和常用项隔开）；③ 破坏性项走 error 色。
 *
 * **样式必须在这里给全**（B003）：`DropdownMenu` 的默认值走 M3 的 `MenuDefaults`，
 * 是 4dp 圆角 + 主题色容器 + 无描边，放到本工程的深色卡片体系里就是「一块直角浮层」。
 * 原型 `docs/R054-多主机效果图.html` 明确定义了 `.menu` 的圆角/描边/阴影/内边距，
 * 这里是它唯一的落点——所有屏幕的溢出菜单都经过本函数。
 *
 * 参数与 `SessionActionsMenu` 的 `DropdownMenu` 调用刻意保持一致
 * （`tonalElevation = 0.dp` + 1dp `outline` 描边 + 12dp 阴影），
 * 让「长按会话行」与「顶栏溢出」两处菜单看起来是同一套东西。
 */
@Composable
fun AppOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = OverflowMenuMinWidth, max = OverflowMenuMaxWidth),
        shape = OverflowMenuShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // 原型 `.menu{padding:5px}`：内边距给在 Column 上而不是 DropdownMenu 上，
        // 这样每条的圆角水波纹离容器边缘有一圈留白，和 `.mi` 的 9px 水平内缩配合。
        Column(modifier = Modifier.padding(5.dp)) { content() }
    }
}

/**
 * 溢出菜单的一条。规格照原型 `:148-149`：
 * `.mi{gap:9px; padding:8px 9px; border-radius:10px; font-size:12px}` +
 * `.mi .i{width:16px; font-size:13px}`。
 *
 * **不用 `DropdownMenuItem`**（B003）：它把高度、内边距、水波纹形状都写死在
 * `MenuDefaults` 里，也不接受 `shape` 参数——想让它变成 10dp 圆角的行只能自己画。
 * 这里用 [Row] + `clip` + `clickable` 复刻，与 `SessionActionsMenu.MenuRow` 同一套做法，
 * 两处菜单的行高/圆角/字号因此天然一致。
 *
 * 12sp 而不是 `bodyMedium`（14sp）：菜单是一条条命令，和会话行标题不该同权重；
 * 原型也是 12px。
 */
@Composable
fun OverflowItem(
    text: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp)
            .height(38.dp)
            .clip(OverflowMenuRowShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (leadingIcon != null) {
            val tint = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            // `.mi .i{width:16px; flex:0 0 16px}`：图标列定宽，保证多行文字左边缘对齐。
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides tint) { leadingIcon() }
            }
        }
        Text(text = text, fontSize = 12.sp, color = color)
    }
}

/**
 * 图标的标准尺寸（R017）。
 *
 * 顶栏 22dp、输入区/浮动钮 20dp。原本住在 `AppIconButton.kt`，随那次合并搬到这里。
 * 用常量而不是各处写 `Modifier.size(22.dp)`：R017 之后图标出现在五个屏幕，
 * 尺寸必须全局一致才能让「顶栏那一行」看起来是一套东西。
 */
object IconSizes {
    val bar = 22.dp
    val composer = 20.dp
    val fab = 20.dp
}

/**
 * 溢出菜单里的分隔线：把破坏性动作和常用动作隔开（R017 的误触防护之一）。
 *
 * 名字刻意不叫 `OverflowSeparator`：`AppOverflowMenu.kt` 曾声明过同名 class，
 * 撞成「Conflicting overloads」（R018 P3-2：那个文件连同它的 items 版死代码
 * 已删除，但这个名字不要再用了）。
 *
 * **必须用本函数而不是构造一个 `OverflowSeparator()` 对象**（R018 P2-1）：
 * 那个 class 不是 `@Composable`，在菜单 content lambda 里调用它只是造一个对象
 * 后丢弃，不发出任何 UI 节点——分隔线就 silently 没了，而 M4 验收时「诊断」
 * 与「退出」只差 131px 就误触过。
 */
@Composable
fun OverflowMenuDivider() {
    HorizontalDivider(
        // 原型 `:156` `.msep{height:1px;background:var(--outlineVariant);margin:4px 6px}`。
        // 左右也留 6dp：分隔线如果顶到容器边，5dp 的内边距就被「画穿」了，看起来像卡片裂缝。
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 溢出菜单里配套的图标按钮：图标 + 语义描述。
 *
 * 按压反馈由 [AppIconButton] 内部的 `clickable` 承担（IconButton 自带
 * interactionSource 与 ripple）；不需要整行缩放——它是 48dp 的独立控件，
 * 不是列表行。若日后想要内缩，把同一个 source 传给 `rememberPressScale`
 * 即可（见 PressFeedback.kt 的 KDoc）。
 */
/**
 * 溢出菜单里的**分区标题**（R054）：多主机之后菜单多了「远程」这一组，
 * 需要一个小字标题把「页面动作」与「主机配置」分开。
 *
 * 规格照原型 s4 的 `.mhead`：9sp、字距 0.6sp、onSurfaceVariant、上下留白比其他项窄。
 * 与 [OverflowItem] 的区别是它**不可点**——它只是分组的标签，不是动作。
 */
@Composable
fun OverflowMenuSectionHeader(text: String) {
    Text(
        text = text,
        // 原型 `:157` `.mhead{padding:7px 9px 5px}`：左右 9dp 与 `.mi` 的文字起点对齐，
        // 分组标题才不会比它下面的条目「缩进更深」。
        modifier = Modifier.padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 5.dp),
        fontSize = 9.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun OverflowMenuIconButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    contentDescription: String,
    icon: @Composable () -> Unit,
    menu: @Composable () -> Unit,
) {
    OverflowMenuAnchor(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        anchor = {
            AppIconButton(
                onClick = { onExpandedChange(!expanded) },
                contentDescription = contentDescription,
            ) { icon() }
        },
        menu = menu,
    )
}
