package com.revxzxcx.remotewebapp.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 复制按钮的图标尺寸（用户 m45428 第 6 条）。
 *
 * 16dp 与代码块标题行里 `labelSmall` 的文字高度同量级——按钮不能比它所在的那一行
 * 更抢眼，否则每个代码块上方都像多了一个主按钮。
 */
private val CopyIconSize = 16.dp

/** 复制成功后图标变成对勾的时长。 */
private const val COPY_FEEDBACK_MS = 1_500L

/**
 * 一个只显示图标（不显示文字）的复制按钮（用户 m45428 第 6 条）。
 *
 * 用户原话：「对话界面应该要可以复制，并且，一些代码块也应该要有独立的复制按钮，
 * 你添加按钮的时候注意与整体布局的协调，添加的按钮应该要用复制的图标展示而不是文字按钮」。
 *
 * 三条设计约束：
 *  1. **只用图标**，不带文字——文字按钮会占掉标题行一半宽度，在 360dp 屏上把语言标签挤没；
 *  2. 反馈靠**图标替换**（复制 → 对勾）而不是弹 Toast：Toast 会盖住代码块底部，
 *     而且用户往往在连续复制多个块，Toast 排队会互相打断；
 *  3. 点击热区 40dp（图标 16dp + 四周留白），满足「小图标也要能点中」。
 *
 * `contentDescription` 是必填的：M5 的设备验收与渲染测试都靠它定位这个控件，
 * 而且读屏用户需要知道这是一个「复制代码」的按钮。
 */
@Composable
internal fun CopyIconButton(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    // 复制成功后 1.5 秒把图标换回复制。用 `LaunchedEffect(copied)` 而不是在点击回调里
    // `delay`：后者在快速连点时会留下多个未取消的协程，图标可能提前换回。
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { this.contentDescription = contentDescription }
            .clickable {
                copyToClipboard(context, text)
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else CopyIcon,
            contentDescription = null,
            // 复制成功用 `primary` 而不是绿：这一屏里绿色已经是「运行中/完成」的语义
            // （`LocalStatusRunning`），再借给「复制成功」会让两个不相关的状态撞色。
            tint = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(CopyIconSize),
        )
    }
}

/**
 * 把文本写进系统剪贴板。
 *
 * 抽成顶层函数是为了能被 JVM 单测直接调用（不经过组合）。`ClipboardManager` 在
 * Robolectric 下有 shadow 实现，可以断言「确实写进去了」。
 *
 * 空文本**不写**：Android 13+ 会在剪贴板浮层里显示预览，空内容会闪一个空白的
 * 「已复制」气泡，看起来像出错了。
 */
internal fun copyToClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("DSH", text))
}


/**
 * 自绘的「复制」图标（两个重叠的圆角矩形）。
 *
 * ## 为什么不直接用 `Icons.Default.ContentCopy`
 * **它不存在。** 本工程只依赖 `androidx.compose.material:material-icons-core` 1.7.8，
 * 而那个 AAR 里只有 **49 枚**图标（已用 `jar tf` 逐一列出核实）：
 *
 * ```
 * AccountBox AccountCircle Add AddCircle ArrowBack ArrowDropDown ArrowForward Build
 * Call Check CheckCircle Clear Close Create DateRange Delete Done Edit Email ExitToApp
 * Face Favorite FavoriteBorder Home Info KeyboardArrowDown KeyboardArrowLeft
 * KeyboardArrowRight KeyboardArrowUp List LocationOn Lock MailOutline Menu MoreVert
 * Notifications Person Phone Place PlayArrow Refresh Search Send Settings Share
 * ShoppingCart Star ThumbUp Warning
 * ```
 *
 * `ContentCopy` / `FileCopy` / `CopyAll` 全都不在里面（`material-icons-extended` 才有，
 * 但那是个十几 MB 的依赖，为一个图标引入不值得——决策 D74 已就同一问题做过同样的选择）。
 *
 * 所以按 Material `content_copy` 的原始路径手工构造：两个 3dp 圆角矩形，右下那个在前、
 * 左上那个只画 L 形的两条边（与官方字形一致，不是简单的两个方框叠起来）。
 *
 * `viewportSize` 用 24×24 是 Material 图标的标准坐标系，这样它在任何 `Modifier.size()`
 * 下缩放都与系统图标视觉重量一致。
 */
private val CopyIcon: ImageVector = ImageVector.Builder(
    name = "ContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // 前景那个矩形：右下，完整闭合。
    path(
        fill = SolidColor(Color.Black),
    ) {
        moveTo(16f, 1f)
        lineTo(4f, 1f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        verticalLineTo(17f)
        horizontalLineTo(4f)
        verticalLineTo(3f)
        horizontalLineTo(16f)
        verticalLineTo(1f)
        close()
    }
    // 背景那个矩形：左上，只画两条边（官方字形就是这样，不是两个完整方框）。
    path(
        fill = SolidColor(Color.Black),
    ) {
        moveTo(19f, 5f)
        lineTo(8f, 5f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        verticalLineTo(21f)
        curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
        horizontalLineTo(19f)
        curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
        verticalLineTo(7f)
        curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
        close()
        moveTo(19f, 21f)
        lineTo(8f, 21f)
        verticalLineTo(7f)
        horizontalLineTo(19f)
        verticalLineTo(21f)
        close()
    }
}.build()
