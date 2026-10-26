package com.revxzxcx.remotewebapp.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.Motion

/**
 * 首页两条「可以滑」的提示是否已经展示过。
 *
 * ## 为什么需要落盘
 *
 * 半屏分区规则**本身是看不见的**：用户面对首页，无法从界面上知道「左半边右滑会出会话、
 * 右半边左滑会出洞察」。提示一旦看过就没用了，不该每次冷启动都再出现一次——那是纯噪声，
 * 而且会长期占住底角。
 *
 * 存 SharedPreferences 而不是内存：只在进程内记的话，每次冷启动都会重新教育用户一遍，
 * 只在「同一次运行里不重复出现」是不够的。
 *
 * 为什么不用 DataStore：只有两个 boolean，与 [com.revxzxcx.remotewebapp.core.prefs.ThemePreferences]
 * 同一判断——不值得为它引一个依赖。
 */
internal class SwipeHintPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 这一侧的提示是否已经展示过（展示过就不再出现）。 */
    fun seen(side: SwipeSide): Boolean = prefs.getBoolean(keyOf(side), false)

    /** 记下这一侧已经展示过。同步写内存、异步落盘，不阻塞 UI。 */
    fun markSeen(side: SwipeSide) {
        if (seen(side)) return
        prefs.edit { putBoolean(keyOf(side), true) }
    }

    private fun keyOf(side: SwipeSide): String = when (side) {
        SwipeSide.LEFT -> KEY_LEFT
        SwipeSide.RIGHT -> KEY_RIGHT
    }

    private companion object {
        const val FILE_NAME = "dsh-swipe-hint"
        const val KEY_LEFT = "left_seen"
        const val KEY_RIGHT = "right_seen"
    }
}

/**
 * 两条可滑动标识（R051 原型第 4 节）。
 *
 * ## 为什么分成两条、各贴自己那一侧的底角
 *
 * 提示要**紧挨着它教的那半边**。把两条并排放中间、或只放一条写「左右可滑」，用户还得
 * 自己把文字和方向对上，而这条提示存在的唯一理由就是省掉那一步。
 *
 * ## 为什么颜色不同
 *
 * 会话抽屉用主色（蓝），洞察用 [LocalStatusRunning] 的绿——R051 原型第 4 节两条胶囊
 * 就是 `--primary` 与 `--running`。这里**不能**用 `colorScheme.tertiary`：本工程的
 * tertiary 落在 `#F2C14E` 琥珀，与原型写的 `#4ADE80` 是两个概念（`Theme.kt` 里专门
 * 记过这个令牌容易被误用的坑，R044 就踩过一次）。
 *
 * ## 对读屏隐形
 *
 * 两条都是「教你用手势」的纯视觉引导，对读屏用户既读不出也做不了（他们用的是
 * 顶栏的 ☰ 与 ◔ 两个按钮，那两个才是等价入口）。用 [clearAndSetSemantics] 把整条
 * 提示从语义树上摘掉，避免多出两个无法操作的焦点。
 *
 * @param showLeft 左侧提示是否可见。
 * @param showRight 右侧提示是否可见。
 */
@Composable
internal fun SwipeHints(
    showLeft: Boolean,
    showRight: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SwipeHint(
            text = "右滑看会话",
            arrow = "→",
            color = MaterialTheme.colorScheme.primary,
            visible = showLeft,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 14.dp),
        )
        SwipeHint(
            text = "左滑看洞察",
            arrow = "←",
            color = LocalStatusRunning.current,
            visible = showRight,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 14.dp),
        )
    }
}

/** 单条提示胶囊。淡入淡出用 [Motion.MEDIUM]，与抽屉本身的补间同档。 */
@Composable
private fun SwipeHint(
    text: String,
    arrow: String,
    color: Color,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.fade(Motion.MEDIUM)),
        exit = fadeOut(Motion.fade(Motion.FAST)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = arrow,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
