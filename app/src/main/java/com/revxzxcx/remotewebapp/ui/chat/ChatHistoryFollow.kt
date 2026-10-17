package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 滚到列表顶部时自动加载更早历史（用户 m22856 反馈「如果需要往上翻查看历史的对话，
 * 也应做自动加载更早的历史对话」）。
 *
 * 抽成独立 Composable 的理由同 [ChatScrollFollow]：这是用户反馈的修复本体，而它的缺陷
 * 只在真实布局后现形（`firstVisibleItemIndex`、`visibleItemsInfo` 都是布局量），
 * 纯 JVM 单测一条也抓不到。
 *
 * ⚠️ **必须加「列表真的溢出一屏」这个前提**（R012 P2-C）：`snapshotFlow` 必发初始值，
 * 而 [ChatScrollFollow] 会把列表定位到底部。短会话（全部消息在一屏内）时
 * `firstVisibleItemIndex` 恒为 0，用户什么都没做就会触发 `onLoadMore()`，顶部凭空出现
 * 转圈并静默 prepend 旧消息——与「往上翻才加载」的验收口径相反。
 *
 * `hasMore` 不收成 Composable 状态参数而是 `() -> Boolean`：放进 [LaunchedEffect] 的 key
 * 会让它每次翻转都整体重启协程、再发一次初始值，等于把同一个缺陷换个时机复现。
 * lambda 里每次调用都取当前值，与 [ChatScrollFollow] 的 `liveLength` 是同一条契约。
 *
 * ## 🔴 为什么需要两个判据（用户 m45428 第 1 条）
 *
 * 原实现只观察 `snapshotFlow { listState.firstVisibleItemIndex }`。`snapshotFlow` **只在
 * 值变化时发射**，于是有一条它永远覆盖不到的路径：用户**已经停在顶部**，还想继续往下拉
 * 以加载更早的历史——索引一直是 0，流不会再有第二次发射，`onLoadMore()` 永远不触发。
 *
 * 用户的原始描述与这条完全对得上：「如果当前已经是第一条消息了，往下拉并不会进行加载，
 * 而是要先往上拉，再迅速下拉，似乎是需要有一定速度才能触发加载」。快速上拉再下拉之所以
 * 「有时能成」，是因为惯性让 `firstVisibleItemIndex` 短暂变成 1 又回 0，制造了一次变化
 * ——纯属副作用，不是设计。
 *
 * ⚠️ **不能用 `firstVisibleItemScrollOffset >= 阈值` 当判据**（我试过，不成立）：
 * `LazyColumn` 到达顶部后继续下拉时，位移被 **overscroll 效果**吃掉，
 * `firstVisibleItemScrollOffset` 仍然是 0。真机上那条路永远不成立。
 *
 * 所以这里用两个互补的判据：
 *  1. **到达顶部**——索引变为 0（覆盖 m22856 的「往上翻到顶」）；
 *  2. **在顶部再次发起滚动**——`isScrollInProgress` 由 false 变 true 且此刻索引为 0
 *     （覆盖 m45428 的「已经在顶部还想继续拉」）。
 *
 * 判据 2 也顺带做了「在顶部往下滑时预取」——用户停在顶部继续滑，说明他还在往前看，
 * 提前把上一页取回来比等到底部转圈更好。
 *
 * 触发后**不去重**：`ChatViewModel.loadMore()` 首行判 `pageJob?.isActive`，重复调用安全。
 * 在这一层再维护一份「已触发」标记反而会引入新的 latch 风险（本工程在
 * `ChatScrollFollow` 上为此付过两次代价）。
 */
@Composable
internal fun ChatHistoryFollow(
    listState: LazyListState,
    hasMore: () -> Boolean,
    onLoadMore: () -> Unit,
) {
    // 判据 1：到达顶部。
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index == 0) tryLoad(listState, hasMore, onLoadMore)
            }
    }

    // 判据 2：已经停在顶部，又发起了一次滚动（下拉/上滑都算——都是「用户还在这儿翻」）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && listState.firstVisibleItemIndex == 0) {
                    tryLoad(listState, hasMore, onLoadMore)
                }
            }
    }
}

/**
 * 满足前提时触发一次加载。
 *
 * 前提「列表真的溢出一屏」必须同时满足——否则短会话一进去就会翻（R012 P2-C）。
 */
private fun tryLoad(
    listState: LazyListState,
    hasMore: () -> Boolean,
    onLoadMore: () -> Unit,
) {
    val layout = listState.layoutInfo
    val overflows = layout.totalItemsCount > layout.visibleItemsInfo.size
    if (overflows && hasMore()) onLoadMore()
}
