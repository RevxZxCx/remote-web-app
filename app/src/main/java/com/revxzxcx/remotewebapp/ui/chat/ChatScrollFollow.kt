package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 聊天列表的「贴底跟随」。
 *
 * 从 [androidx.compose.ui.chat.ChatScreen] 里抽出来，为的是能被 Robolectric 渲染测试直接驱动：
 * 这段逻辑的全部缺陷（跟随失效、跳不过去、被 latch 住）都只在**真实渲染**时才会现形，
 * 纯逻辑单测一条也抓不到——本工程已经为此付过七次代价。
 *
 * 行为契约（三条，缺一不可）：
 *  1. **首帧内容到位时主动滚到底一次，且不看 [atBottom]。** 打开长会话时列表从索引 0 开始，
 *     `canScrollForward` 恒真 → [atBottom] 立刻被置 false；而 `liveLength` 在没有流式预览时
 *     只发射一次 0，于是 `if (atBottom)` 永不成立 → 列表永远停在顶部、此后再也不跟随。
 *     R010m 在 MuMu 上用埋点实测到这条：`queued size=1 ids=[3] lastIndex=164
 *     atBottom=false canForward=true`——排队回声确实在状态里，只是躺在视口下方，
 *     uiautomator 只报可见节点，所以被误判成「回声消失了」。同一形态也是用户 T7 反馈
 *     「生成时文本到底部不自动滚到最新行」的残余根因。
 *  2. 之后只在用户贴在底部时跟随（用户上翻历史时不被拽回去）。
 *  3. `liveLength` 必须是 **lambda** 而不是值：`snapshotFlow` 只观察 Compose 快照系统里的
 *     对象，`MutableStateFlow` 不在其中（`SnapshotFlowObservationTest` 把这条语义钉成了契约）。
 *
 * @param listState 调用方的列表状态（不负责创建，调用方可能还要用它做别的）。
 * @param lastIndex 列表最后一行的下标；负数表示还没有任何内容。
 * @param liveLength 流式预览当前文本长度，读它必须走快照系统。
 */
@Composable
internal fun ChatScrollFollow(
    listState: LazyListState,
    lastIndex: Int,
    liveLength: () -> Int,
) {
    // 用户是否贴在底部。用 snapshotFlow 观察而不是每次重组都读，避免无谓重组。
    //
    // 必须是 `rememberSaveable`（R007 P2-4）：`listState` 本身是普通 `remember`，旋转后会
    // 得到新实例（滚动位置归零、从头开始）；若 `atBottom` 也是普通 `remember`，旋转后它重置成
    // true，于是用户正在上翻历史时一转屏就被拉回底部。保存 `atBottom` 至少保证「不强行跟随」。
    var atBottom by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                // 不能继续向下滚 = 已经在底部。
                atBottom = !canScrollForward
            }
    }

    // 首帧内容到位时是否已经主动滚到底过一次。**必须是普通 `remember` 而不是
    // `rememberSaveable`**：`listState` 旋转后会换成新实例（位置归零），这个标记要跟着重置，
    // 否则旋转后永远不再跳一次，用户看到的是停在小半路中的列表。
    var jumpedToNewest by remember { mutableStateOf(false) }

    LaunchedEffect(listState, lastIndex) {
        if (lastIndex < 0) return@LaunchedEffect
        // 首帧内容到位：无条件跳到底（见 KDoc 第 1 条）。
        // 首帧内容到位：无条件跳到底（见 KDoc 第 1 条）。
        if (!jumpedToNewest) {
            jumpedToNewest = true
            listState.scrollToBottom(lastIndex)
        }
        snapshotFlow { liveLength() }
            .collect { if (atBottom) listState.scrollToBottom(lastIndex) }
    }
}
