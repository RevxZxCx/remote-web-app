package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

/**
 * 判定「用户是否贴在底部」时允许的余量（单位：行）。
 *
 * 新内容到达的那一刻滚动还没发生，末行可能刚被顶出视口半个身子，此时若判「不在底部」，
 * 这一轮跟随就被跳过、而且要等下一次内容变化才补上——流式生成时正好每来一个 token
 * 都会补，看起来就是「滚一下停一下」的抖动。允许差一两行让跟随连续。
 */
private const val BOTTOM_SLACK = 2

/**
 * 聊天列表的「贴底跟随」。
 *
 * 从 `ChatScreen` 里抽出来，为的是能被 Robolectric 渲染测试直接驱动：
 * 这段逻辑的全部缺陷（跟随失效、跳不过去、被 latch 住）都只在**真实渲染**时才会现形，
 * 纯逻辑单测一条也抓不到——本工程已经为此付过七次代价。
 *
 * 行为契约（三条，缺一不可）：
 *  1. **内容到位时主动滚到底一次，且不看是否贴底。** 打开长会话时列表从索引 0 开始，
 *     任何「已经在底部」的判定都会是 false；而 `liveLength` 在没有流式预览时只发射一次，
 *     于是「只在贴底时跟随」这条永远不成立 → 列表永远停在顶部、此后再也不跟随。
 *     R010m 在 MuMu 上用埋点实测到这条：`queued size=1 ids=[3] lastIndex=164
 *     atBottom=false canForward=true`——排队回声确实在状态里，只是躺在视口下方，
 *     uiautomator 只报可见节点，所以被误判成「回声消失了」。
 *  2. 之后只在用户贴在底部时跟随（用户上翻历史时不被拽回去）。
 *  3. `liveLength` 与 `lastIndex` 必须是 **lambda** 而不是值：`snapshotFlow` 只观察
 *     Compose 快照系统里的对象，`MutableStateFlow` 不在其中
 *     （`SnapshotFlowObservationTest` 把这条语义钉成了契约）。`lastIndex` 每个流式 token
 *     都在变，作为 `LaunchedEffect` 的 key 会让 effect 每 token 取消重启一次，
 *     `snapshotFlow` 永远等不到第二个值。
 *  4. **lambda 里必须读活的 Compose 状态，不能捕获组合时算好的值。**
 *     `LaunchedEffect(listState)` 的 key 不变 → effect 不重启 → 协程持有的是**第一次
 *     组合**创建的那个 lambda。调用方若写成 `lastIndex = { lastIndex }`（捕获一个
 *     `val Int`），下标就被冻在首次组合那一刻，流式过程中每次 collect 都按旧下标滚，
 *     「生成时不自动往下滚」；写成 `lastIndex = { state.items.size - 1 }`（读委托）才对。
 *     `ChatScrollFollowTest.内容变化后跟随到新的末尾` 钉住这一条。
 *
 * **「贴底」的判定为什么不用 `canScrollForward`（用户 m21177 第 2.3 条反馈的根因）**：
 * 流式生成时 live 行持续长高，它的底边被推到视口下方 → `canScrollForward` 恒真 →
 * 「贴底」被翻成 false → 此后每个 token 都被 `if (贴底)` 挡掉，而**没有任何东西
 * 把它滚回底部**，于是永久 latch。改成读 `layoutInfo` 判「最后一个可见项的下标是否
 * 已经接近列表末尾」，并**把部分可见的末行算作贴底**——那正是流式场景下的正确形态。
 *
 * ⚠️ 这个判定**不能**用 `derivedStateOf`：`derivedStateOf` 只在被**组合**读取时才重算，
 * 而这里是在协程里读（R011 首跑 3 项渲染测试失败即此因，报「先确认已经在底部」）。
 * 协程里直接读 `listState.layoutInfo`（它是 `mutableStateOf`，读到的是当前值）。
 *
 * **真机证据（OnePlus PKG110，`adb logcat -s DSH-SCROLL`）**：流式过程中每个新 item 都收到
 * 一次 `collect … near=true …` 并跟着滚一次，末行实测高 2844px / 视口 1044px。
 * 也就是说「2.3 没有自动滚动」在 R011 两个根因修完后**已经不成立**，之前设备上看不出来
 * 是测量问题（锚点选成了模型条，而模型条在排队条下面，比真实视口底边低一个排队条）。
 *
 * @param listState 调用方的列表状态（不负责创建，调用方可能还要用它做别的）。
 * @param lastIndex 列表最后一行的下标；负数表示还没有任何内容。**是 lambda 不是值**
 *   （见 KDoc 第 3 条）。
 * @param liveLength 流式预览当前文本长度，读它必须走快照系统。
 */
@Composable
internal fun ChatScrollFollow(
    listState: LazyListState,
    lastIndex: () -> Int,
    liveLength: () -> Int,
) {
    // 内容到位时是否已经主动滚到底过一次。**必须是普通 `remember` 而不是
    // `rememberSaveable`**：`listState` 旋转后会换成新实例（位置归零），这个标记要跟着重置，
    // 否则旋转后永远不再跳一次，用户看到的是停在小半路中的列表。
    var jumpedToNewest by remember(listState) { mutableStateOf(false) }

    // key 里**不放 lastIndex**（见 KDoc 第 3 条）：行数与流式长度都从 snapshotFlow 里读。
    LaunchedEffect(listState) {
        // 先等内容到位。**不能**在这里用 `if (lastIndex() < 0) return`：
        // 会话页首帧时 items 还是空的（快照还在路上），提前 return 之后没有任何东西
        // 会重启这个 effect（key 只有 listState），首帧跳底就永久丢了。
        snapshotFlow { lastIndex() }.first { it >= 0 }

        // 内容到位：无条件跳到底（见 KDoc 第 1 条）。
        if (!jumpedToNewest) {
            jumpedToNewest = true
            listState.scrollToBottom(lastIndex())
        }
        snapshotFlow { lastIndex() to liveLength() }
            .collect { _ ->
                // 先等一帧让列表重新测量，再判贴底、再滚（见 [scrollToBottom] 的 KDoc）。
                withFrameNanos { }
                if (listState.isNearBottom()) listState.scrollToBottom(lastIndex())
            }
    }
}

/** 见 [ChatScrollFollow] 的 KDoc：末行部分可见也算贴底，另留 [BOTTOM_SLACK] 行余量。 */
private fun LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= info.totalItemsCount - BOTTOM_SLACK
}
