package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 聊天列表里「正在加载更早历史」那一行的 key。
 *
 * 与 `ChatScreen` 的 LazyColumn 共用这一个常量，是为了让高度模型（[ChatScrollbar]）与
 * 真实列表的行序不可能对不上：两处各写一份字符串时，改了一处忘另一处不会有任何编译错误，
 * 只会让滚动条的标记整体偏移几行——而这种偏移在视觉上极难察觉。
 */
internal const val CHAT_LOADING_MORE_KEY = "loading-more"

/** 同上，流式预览那一行的 key。 */
internal const val CHAT_LIVE_KEY = "live"

/** 滑块占轨道高度的最小比例，保证几千条消息的长会话里滑块仍然看得见、点得着。 */
private const val MIN_THUMB_FRACTION = 0.06f

/**
 * 会话长度系数的上限。
 *
 * 系数是「cursor / 已加载窗口的 seq 跨度」估出来的，而消息在 seq 上的密度并不均匀
 * （一个轮次里工具调用能占几百条 seq）。刚打开老会话时窗口只有 50 条消息、跨度可能很小，
 * 系数会被放大到几百。没有上限时，一个偶发的窄窗口会让滑块缩到看不见，
 * 而用户此刻恰好正在滚动的就是那 50 条——信息量全丢。
 */
private const val MAX_SESSION_SCALE = 64f

/**
 * 滚动条的**模型几何**：已加载窗口的前缀和 + 由会话长度系数放大出来的「整个会话」。
 *
 * @param prefixes 已加载窗口的前缀和（`prefixHeights` 的输出）。
 * @param head 未加载（更早）部分估高。已加载的一律是较新尾部，所以它在模型顶边。
 * @param total 模型总高 = 已加载总高 × 系数。
 */
private data class Geometry(
    val prefixes: FloatArray,
    val head: Float,
    val total: Float,
) {
    // data class 持 FloatArray 时必须手写 equals/hashCode，否则每次比较都是引用比较，
    // 而 ScrollbarModel 的相等性护栏会把它当成「变了」→ 回调每帧空转。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Geometry) return false
        return head == other.head && total == other.total && prefixes.contentEquals(other.prefixes)
    }

    override fun hashCode(): Int {
        var result = prefixes.contentHashCode()
        result = 31 * result + head.hashCode()
        result = 31 * result + total.hashCode()
        return result
    }
}

/**
 * 滚动条的**可观测模型**。
 *
 * 存在的唯一理由是测试：这条滚动条是 Canvas 画出来的，语义树里既没有「滑块在 82%」
 * 也没有「有 3 个标记」，Robolectric 断言只能靠这个结构体。生产环境用默认的 no-op 回调。
 *
 * @param totalHeight 模型里全部行的估高总和（px）。为 0 表示还没有量到任何一行。
 * @param thumbTopFraction 滑块顶边在轨道中的位置，0..1。
 * @param thumbHeightFraction 滑块高度占轨道的比例（= 视口 / 总高）。
 * @param markerFractions 每个「我发送的消息」标记的中心在轨道中的位置，0..1。
 */
internal data class ScrollbarModel(
    val totalHeight: Float,
    val thumbTopFraction: Float,
    val thumbHeightFraction: Float,
    val markerFractions: List<Float>,
)

/**
 * 聊天页的**位置滚动条**：右侧一条竖轨，虚线轨道 + 一个滑块，
 * 并在**每一条「我发送的消息」**的位置压一根主色短标记。
 *
 * 需求来自用户 m24150：「聊天页面中，对于我们输入对话点的标记没有，有时候我们想找回我们
 * 输入某一个对话点的位置，很麻烦，如果能做一个类似滚动条的标记输入对话点的东西，会很好用」。
 *
 * ## 为什么不用 LazyColumn 自带的 `Scrollbar`
 *
 * Compose Foundation 1.6 起有 `Modifier.verticalScrollbar` / `LazyListState.rememberScrollbarAdapter`，
 * 但它们只画滑块：**没有任何 API 能在指定 item 的位置钉一个标记**。而「找我发过的话」
 * 恰好只需要标记、滑块只是顺带。所以这里自己画。
 *
 * ## 高度模型为什么是估的
 *
 * 滚动条要把「第 N 行」映射到「轨道的某个位置」，严格做法需要全部行的已测量高度。
 * LazyColumn 只持有可见项的高度，不可见的早就回收了。所以：
 *
 *  1. 只记录**当前可见**项的真实高度（`snapshotFlow` + `mutableStateMapOf`）；
 *  2. 未量到的行用**已量行的平均值**补；
 *  3. 前缀和 `p[i]` = 前 i 行的估高总和，行 N 的中心 = `(p[N] + p[N+1]) / 2`。
 *
 * 平均高度对聊天页是合理的：消息气泡高度方差大，但**累积**后方差被平均掉，标记的位置
 * 足够接近；用户点标记时即使偏了一行，也只是跳到相邻的一条消息。**宁可近似也要有标记**，
 * 这是本工程 D37「服务端没有的能力不出现对应字样」之外的另一类取舍：不追求精确到行。
 *
 * ## 交互
 *
 * 点/拖动轨道时，先看落点是否落在某个用户消息标记的 **14dp 命中半径**内：
 * 命中就滚到那条消息，否则按「落点占轨道的比例」二分出目标行。
 * 拖动用第二个 `pointerInput` 修饰符承载（两个修饰符各跑一条协程，互不阻塞）。
 *
 * ⚠️ 所有 lambda 都必须在**调用时**读活的 Compose 状态（`ChatScrollFollow` KDoc 第 4 条
 * 记过同类事故）：`pointerInput` 块只在修饰符安装时跑一次，里面捕获的组合期值会被冻住。
 *
 * ## 为什么滑块大小要乘一个「会话长度系数」
 *
 * `session/follow` 的首屏窗口只有 50 条消息（`history.js:59` `DEFAULT_MAX_MESSAGES = 50`），
 * 而一个老会话可能有几千条。**不乘系数时，每个会话刚打开都恰好量到 50 行**，
 * 于是「视口 / 已加载总高」在所有会话里是同一个数——滑块大小完全不随会话长度变化
 * （用户 m24431 实测反馈：「滑块不会根据会话长度变化，大小不变」）。
 *
 * 系数由调用方从 **seq 跨度**估：`cursor` 是服务端权威的日志尾部 seq，已加载窗口覆盖
 * `[items.first().seq, cursor]`，两者之比就是「已加载部分占整个会话的比例」。
 * 已加载部分一律是**较新的尾部**，所以未加载的头部估高为 `已加载总高 × (系数 - 1)`，
 * 全部几何量都从「距轨道顶边」改成「距模型顶边」。
 *
 * @param listState 调用方的列表状态。
 * @param itemKeys 当前列表的全部 key，**顺序必须与 LazyColumn 的行序逐行一致**
 *   （loading-more → items → live），否则标记会整体偏移。
 * @param userItemIndices 「我发送的消息」在 [itemKeys] 里的下标集合。
 * @param sessionLengthScale 已加载窗口占整个会话的比例的倒数（>= 1）。1 表示已全部加载。
 *   默认 1，测试与「不需要这个修正」的调用方都不传。
 * @param onScrollToItem 滚到指定下标（调用方通常直接给 `listState::scrollToItem`）。
 * @param onModelChange 模型变化回调，**只给测试用**；生产环境不要传。
 */
@Composable
internal fun ChatScrollbar(
    listState: LazyListState,
    itemKeys: () -> List<Any>,
    userItemIndices: () -> List<Int>,
    onScrollToItem: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
    sessionLengthScale: () -> Float = { 1f },
    onModelChange: (ScrollbarModel) -> Unit = {},
) {
    // 已量到的行高。只由可见项写入，永不清理——用户滚过一行就多知道一行。
    val heights = remember { mutableStateMapOf<Any, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key to it.size } }
            .collect { pairs -> for ((key, size) in pairs) heights[key] = size }
    }

    // 前缀和。`remember(listState)` 而不是 `remember(itemKeys())`：key 每次重组都是新 List，
    // 会让 State 每次换实例，而 pointerInput 捕获的是旧实例 → 拿到旧 keys 算出的前缀和。
    // 把 itemKeys() 放进 derivedStateOf 内部读，State 的标识就稳定了，读到的也永远是当前值。
    val prefixes = remember(listState) {
        derivedStateOf { prefixHeights(itemKeys(), heights) }
    }

    // 会话长度系数：已加载窗口占整个会话的比例。
    //
    // 在组合期读一次、再把值当 remember 的键，不把 `sessionLengthScale()` 塞进
    // derivedStateOf 里：derivedStateOf 的计算也可能在 pointerInput / SideEffect 这些
    // 非观察作用域里被调用，那种调用不登记依赖，系数变了模型不重算（实测踩过）。
    // `geometry` 内部仍是 derivedStateOf——行高是滚动中逐帧量出来的，必须活读。
    val sessionScale = sessionLengthScale().coerceIn(1f, MAX_SESSION_SCALE)

    // 模型几何：把「已加载窗口的前缀和」放大成「整个会话的模型」。
    val geometry by remember(prefixes, listState, sessionScale) {
        derivedStateOf {
            val raw = prefixes.value
            val rawTotal = raw.last()
            Geometry(
                prefixes = raw,
                head = rawTotal * (sessionScale - 1f),
                total = rawTotal * sessionScale,
            )
        }
    }

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val markerColor = MaterialTheme.colorScheme.primary

    val density = LocalDensity.current
    val dashHeightPx = with(density) { 3.dp.toPx() }
    val dashGapPx = with(density) { 7.dp.toPx() }
    val trackWidthPx = with(density) { 4.dp.toPx() }
    val markerWidthPx = with(density) { 8.dp.toPx() }
    val markerHeightPx = with(density) { 4.dp.toPx() }
    val minThumbHeightPx = with(density) { 26.dp.toPx() }
    val cornerRadiusPx = with(density) { 2.dp.toPx() }
    val hitRadiusPx = with(density) { 14.dp.toPx() }

    // 只在列表**溢出一屏**时显示：内容一屏放得下时滑块就是整条轨道，纯噪声。
    // `layoutInfo` 带 @FrequentlyChangingValue，必须包 derivedStateOf——直接读会让这个
    // Composable 在每次滚动帧都重组（lint 的 FrequentlyChangingValue 警告就是盯这条的）。
    val overflow by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > info.visibleItemsInfo.size
        }
    }
    val prefixesNow = geometry.prefixes
    val totalHeight = geometry.total

    // 滑块位置：视口顶边在模型里的位置 / 总高。末行顶边 = p[index] - item.offset
    // （item.offset 是相对视口顶边的，所以减回去才是模型坐标），再加未加载头部的估高。
    val thumbTop by remember(prefixes, listState, geometry) {
        derivedStateOf {
            val info = listState.layoutInfo
            val geom = geometry
            val total = geom.total
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (total <= 0f || viewport <= 0f) {
                0f
            } else {
                val first = info.visibleItemsInfo.firstOrNull()
                val index = first?.index ?: 0
                val offset = first?.offset ?: 0
                val raw = (geom.head + geom.prefixes.getOrElse(index) { 0f } - offset) / total
                // 先按比例求位置，再让滑块整体待在轨道里：长会话里 thumbHeight 被钳到
                // MIN_THUMB_FRACTION，此时 0.975 的顶边会让滑块底边超出轨道。
                val height = (viewport / total).coerceIn(MIN_THUMB_FRACTION, 1f)
                raw.coerceIn(0f, 1f - height)
            }
        }
    }
    // 滑块高度。`MIN_THUMB_FRACTION` 是**下限**：长会话里视口占总高的比例可能小于 6%，
    // 不钳的话滑块会细到点不着。所以钳完上界还要反过来钳位置——否则贴底时
    // `thumbTop + thumbHeight > 1`，滑块下半截被画到轨道外面去。
    val thumbHeight by remember(prefixes, listState, geometry) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = geometry.total
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (total <= 0f) 1f else (viewport / total).coerceIn(MIN_THUMB_FRACTION, 1f)
        }
    }

    // 标记位置：行的模型中点 / 模型总高。已加载的是较新的尾部，所以要加上未加载头部的估高，
    // 否则几千条消息的老会话会把所有标记挤在轨道最下面一小段里。
    val markerFractions = if (totalHeight > 0f) {
        userItemIndices().mapNotNull { index ->
            if (index in 0 until prefixesNow.size - 1) {
                (geometry.head + (prefixesNow[index] + prefixesNow[index + 1]) * 0.5f) / totalHeight
            } else {
                null
            }
        }
    } else {
        emptyList()
    }

    // 模型回调带相等性护栏：SideEffect 每次重组都跑，而流式过程中每个 token 都会重组一次。
    // 放在 SideEffect 里而不是组合期直接写：组合期写快照状态要靠「下一次重组时条件不再成立」
    // 才能收敛，而 Compose 对这类写法的态度一直是「能用但不保证」。
    var lastModel by remember { mutableStateOf<ScrollbarModel?>(null) }
    val model = ScrollbarModel(totalHeight, thumbTop, thumbHeight, markerFractions)
    SideEffect {
        if (model != lastModel) {
            lastModel = model
            onModelChange(model)
        }
    }

    if (!overflow || totalHeight <= 0f) return

    val scope = rememberCoroutineScope()
    val description = "聊天位置滚动条，${markerFractions.size} 个我发送的位置标记"

    /**
     * 把轨道上的纵向落点换算成列表下标。返回 -1 表示「没有可滚的目标」。
     *
     * 全部输入都在**调用时**从活的 Compose 状态读：`prefixes.value` 与 `userItemIndices()`
     * 都不能在修饰符安装时求值（见类 KDoc 的 ⚠️）。
     */
    fun resolve(trackHeight: Float, y: Float): Int {
        val p = geometry.prefixes
        val head = geometry.head
        val total = geometry.total
        if (trackHeight <= 0f || total <= 0f) return -1
        val clampedY = y.coerceIn(0f, trackHeight)
        val markers = userItemIndices()
        // 先看标记：14dp 命中半径内一律吸附到标记，宁可误吸附也不要让用户点不中一根 4dp 的短线。
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        for (index in markers) {
            if (index !in 0 until p.size - 1) continue
            val centerPx = (head + (p[index] + p[index + 1]) * 0.5f) / total * trackHeight
            val distance = abs(centerPx - clampedY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex >= 0 && bestDistance <= hitRadiusPx) return bestIndex
        // 否则按比例二分。p 是单调递增的前缀和，二分的是「第一个 p[i] 超过目标」的前一行。
        // 目标是模型坐标，要先减掉未加载头部才是已加载窗口里的偏移。
        val target = clampedY / trackHeight * total - head
        if (target <= 0f) return 0
        var lo = 0
        var hi = p.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (p[mid] <= target) lo = mid else hi = mid
        }
        return lo
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                fun trackHeight(): Float = size.height.toFloat()
                detectTapGestures { offset ->
                    val index = resolve(trackHeight(), offset.y)
                    if (index >= 0) scope.launch { onScrollToItem(index) }
                }
            }
            .pointerInput(Unit) {
                fun trackHeight(): Float = size.height.toFloat()
                detectVerticalDragGestures { change, _ ->
                    val index = resolve(trackHeight(), change.position.y)
                    if (index >= 0) scope.launch { onScrollToItem(index) }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val height = size.height
            val centerX = size.width / 2f
            if (height <= 0f || totalHeight <= 0f) return@Canvas

            // 虚线轨道：一段 3dp、间隔 7dp。用 Canvas 而不是几十个小 Box——那是几十个节点，
            // 而这个装饰在测试里只需要一条 contentDescription。
            var y = 0f
            while (y < height) {
                drawRect(
                    color = trackColor,
                    topLeft = Offset(centerX - trackWidthPx / 2f, y),
                    size = Size(trackWidthPx, min(dashHeightPx, height - y)),
                )
                y += dashHeightPx + dashGapPx
            }

            // 「我发送的消息」标记：比滑块宽、比滑块短，压在主色上。
            for (fraction in markerFractions) {
                val centerY = (fraction * height).coerceIn(0f, height)
                drawRect(
                    color = markerColor,
                    topLeft = Offset(centerX - markerWidthPx / 2f, centerY - markerHeightPx / 2f),
                    size = Size(markerWidthPx, markerHeightPx),
                )
            }

            // 滑块。高度按「视口 / 总高」给下限保护，长会话里仍然看得见。
            val thumbHeightPx = max(thumbHeight * height, minThumbHeightPx)
            val thumbTopPx = (thumbTop * height).coerceIn(0f, max(0f, height - thumbHeightPx))
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(centerX - trackWidthPx / 2f, thumbTopPx),
                size = Size(trackWidthPx, thumbHeightPx),
                cornerRadius = CornerRadius(cornerRadiusPx),
            )
        }
    }
}

/**
 * 「滚到底部」浮动按钮。
 *
 * 位置在滚动条**下方**（用户 m24150 原话：「一个可以导航到底部的按钮」配「标记输入对话点的
 * 滚动条」，两者是一起提的），只在**列表没贴在底部**时出现——贴底时常驻一个「到底部」
 * 按钮是纯遮挡。出现/消失用 `AnimatedVisibility`，因为内容增长时它每秒可能切好几次状态，
 * 硬切会让用户的眼睛被右下角的闪动抓住。
 *
 * @param visible 是否显示。
 * @param onClick 点击后滚到底部。
 */
@Composable
internal fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.85f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "滚动到底部" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 由 [itemKeys] 与已量到的行高算出前缀和数组。
 *
 * 纯函数，单独放出来是为了能被 JVM 单测直接钉住（不需要 Compose、不需要 Android）：
 * 「未量到的行用平均值补」这条近似规则一旦写错，症状是标记整体偏移，而这种偏移在
 * 设备上极难归因——本工程已经为「只有渲染层才暴露的缺陷」付过九次代价。
 *
 * @return 长度 `keys.size + 1` 的数组，`out[i]` = 前 i 行估高总和，`out.last()` = 全部。
 */
internal fun prefixHeights(keys: List<Any>, heights: Map<Any, Int>): FloatArray {
    val out = FloatArray(keys.size + 1)
    if (keys.isEmpty()) return out
    var knownSum = 0f
    var knownCount = 0
    for (key in keys) {
        val height = heights[key]
        if (height != null) {
            knownSum += height.toFloat()
            knownCount++
        }
    }
    val average = if (knownCount > 0) knownSum / knownCount else 0f
    for (i in keys.indices) {
        out[i + 1] = out[i] + (heights[keys[i]]?.toFloat() ?: average)
    }
    return out
}
