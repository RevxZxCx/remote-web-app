package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 折叠分组展开后的自动滚动（用户 m45428 第 3 条）。
 *
 * 用户原话：「对于折叠操作的部分，展开后应该要显示最后一行的操作，现在显示的是前面几行的
 * 内容。并且，展开后，如果用户停在展开后的最后一个操作，有新的操作被折叠进来了，也要能
 * 优雅的滚动，目前的情况是不会自动滚动。需要注意的是，如果用户上划了想查看之前的操作，
 * 也不要给用户拉下来，这是交互性的基本要求。」
 *
 * 拆成三条契约：
 *
 * 1. **展开时无条件跳到最后一项。** 用户点开一个「执行了命令 ×52」的分组，想看的是
 *    **刚刚发生了什么**，而不是第 1 条。旧实现不设 `LazyListState`，列表停在顶部，
 *    用户看到的正是最早那几条——这是用户报的「显示的是前面几行的内容」。
 *
 * 2. **之后只在用户贴底时跟随新成员。** 生成过程中新操作不断追加到末尾；如果用户
 *    正看着末尾，就跟着滚，让「现在在跑什么」始终可见。
 *
 * 3. **用户上划之后不再抢滚动。** 用户主动往上翻是想看之前做了什么，此时把列表拽回
 *    底部是最讨人厌的一种「智能」。判据用「是否贴底」而不是「是否有过一次上划」：
 *    用户上划后自然会脱离底部，等他自己滑回底部时跟随应该恢复——这才是符合直觉的。
 *
 * ## 为什么不用 `ChatScrollFollow`
 * 那个组件是给**主聊天列表**用的，它把「首帧无条件跳底」与「贴底才跟随」绑在一个
 * `jumpedToNewest` 标记上，且依赖 `liveLength`（流式文本长度）作为变化源。分组内层列表
 * 的变化源是**成员数量**，语义不同，硬复用会把两套条件混在一起（本工程在
 * `ChatScrollFollow` 上已为「条件混用」付过两次代价）。这里只保留它的第 1、2 条契约。
 *
 * @param listState 分组内层 `LazyColumn` 的状态。
 * @param expanded 分组是否展开。从 false → true 的那一刻触发「跳到最后一项」。
 * @param memberCount 当前成员数量。它变化时决定是否跟随。
 */
@Composable
internal fun GroupAutoScroll(
    listState: LazyListState,
    expanded: Boolean,
    memberCount: Int,
) {
    // 「这次展开是否已经跳到底过」。必须是普通 `remember`（不是 saveable）：
    // 收起再展开应该重新跳一次，用户点开就是想看最新的。
    var jumpedForThisExpand by remember { mutableStateOf(false) }

    // 契约 1：展开的那一帧无条件跳到最后一项。
    LaunchedEffect(expanded, memberCount) {
        if (!expanded) {
            // 收起时清标记：下次展开要重新跳。
            jumpedForThisExpand = false
            return@LaunchedEffect
        }
        if (memberCount == 0) return@LaunchedEffect
        if (!jumpedForThisExpand) {
            jumpedForThisExpand = true
            // 直接 `scrollToItem` 而不是 `animateScrollToItem`：用户刚点开，
            // 看到的应该立刻是末尾，而不是一段「从第 1 条飞到最后一条」的滚动动画
            // （52 项的动画会持续一秒多，反而像卡住了）。
            listState.scrollToItem(memberCount - 1)
        }
    }

    // 契约 2 + 3：成员增加时，只在贴底的情况下跟随。
    LaunchedEffect(listState, expanded) {
        if (!expanded) return@LaunchedEffect
        // 🔴 必须记住「变化前」的项数，用**旧**的项数判贴底（我首跑就是这里错的）。
        //
        // 新成员到达的那一刻 `totalItemsCount` 已经是新的（23），而滚动位置仍停在
        // 旧末尾（19）。若拿新数量算 `atBottom = 19 >= 23-1-2 = 20` → false，
        // 于是「本来贴底的用户」被判成「上划了」，跟随永远不触发。
        //
        // 正确的问法是「**在成员增加之前**用户是否贴底」，所以拿 `previousCount` 比。
        var previousCount = listState.layoutInfo.totalItemsCount
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .collect { count ->
                if (count == 0) {
                    previousCount = count
                    return@collect
                }
                // 贴底判定与 `ChatScrollFollow` 同口径：最后一个可见项的下标已经接近
                // **旧**末尾（允许 [GROUP_BOTTOM_SLACK] 项误差，因为新内容到达时末项
                // 可能刚被顶出视口一点点）。**不能用 `canScrollForward`**：新成员刚追加、
                // 还没滚动的那一刻它必然为 true，会把「本来贴底」误判成「用户上划了」。
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atBottomBefore = lastVisible >= previousCount - 1 - GROUP_BOTTOM_SLACK
                if (atBottomBefore && count > previousCount) {
                    listState.animateScrollToItem(count - 1)
                }
                previousCount = count
            }
    }
}

/**
 * 「贴底」判定允许的项数余量。
 *
 * 新成员到达的那一刻滚动还没发生，末项可能刚被顶出视口一两行；此时若判「不在底部」，
 * 这一轮跟随就被跳过、而且要等下一次成员变化才补上——生成过程中正好每次都会补，
 * 看起来就是「滚一下停一下」的抖动。与 `ChatScrollFollow` 的 `BOTTOM_SLACK` 同源。
 */
private const val GROUP_BOTTOM_SLACK = 2
