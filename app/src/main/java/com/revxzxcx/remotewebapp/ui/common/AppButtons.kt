package com.revxzxcx.remotewebapp.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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

/**
 * 溢出菜单本体。
 *
 * 与直接写 `DropdownMenuItem` 的差别：① 每条都要图标（R017「全 App 图标化」）；
 * ② 分隔线是一等公民（破坏性动作必须和常用项隔开）；③ 破坏性项走 error 色。
 */
@Composable
fun AppOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { content() }
}

/** 溢出菜单的一条。 */
@Composable
fun OverflowItem(
    text: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = color) },
        leadingIcon = leadingIcon?.let { icon ->
            {
                val tint = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                CompositionLocalProvider(LocalContentColor provides tint) { icon() }
            }
        },
        onClick = onClick,
    )
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
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
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
