package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyListState

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
 */
@Composable
internal fun ChatHistoryFollow(
    listState: LazyListState,
    hasMore: () -> Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val layout = listState.layoutInfo
                val overflows = layout.totalItemsCount > layout.visibleItemsInfo.size
                if (index == 0 && overflows && hasMore()) onLoadMore()
            }
    }
}
