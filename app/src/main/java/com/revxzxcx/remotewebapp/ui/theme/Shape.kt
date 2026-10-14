package com.revxzxcx.remotewebapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 应用圆角（R016 界面美化）。
 *
 * 全套只此一份，不在各处写 `RoundedCornerShape(12.dp)`——同一个界面里出现三种
 * 接近但不同的圆角，用户说不出哪里不对，但会觉得「糙」。
 *
 * 取值逻辑：越小越「工具感」（代码块、徽标），越大越「容器感」（卡片、对话框）。
 * 气泡用 [bubbleShape] / [bubbleTailShape] 单独一套，因为它们要非对称圆角。
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 对话气泡：四角统一圆角。 */
internal val bubbleShape = RoundedCornerShape(18.dp)

/**
 * 对话气泡的「尾巴角」：用户气泡右下角、助手气泡左下角收窄到 6dp，
 * 用圆角大小暗示「这条消息从哪边冒出来」，比加一个小三角更干净。
 */
internal val bubbleTailShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 6.dp,
    bottomEnd = 18.dp,
    bottomStart = 18.dp,
)

/** 折叠分组卡片（R015 的标准模式折叠）。 */
internal val groupShape = RoundedCornerShape(14.dp)

/** 输入区与底部工具条上的小控件。 */
internal val pillShape = RoundedCornerShape(999.dp)
