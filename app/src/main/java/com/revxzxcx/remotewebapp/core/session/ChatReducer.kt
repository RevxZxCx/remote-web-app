package com.revxzxcx.remotewebapp.core.session

/**
 * 聊天页的状态。
 *
 * [items] 是**已持久化**的条目，按 `seq` 升序；[live] 是正在生成、尚未落盘的预览。
 * 两者分开存是刻意的：落盘条目是权威的、可翻页的；预览是易失的，收到同 `(turn, step)`
 * 的 `assistant/message` 后整块丢弃。这样就不会出现「同一句话显示两遍」。
 */
data class ChatUiState(
    /** 按 seq 升序的持久化条目。 */
    val items: List<ChatItem> = emptyList(),
    /** 正在生成的预览；为 null 表示当前没有活跃生成。 */
    val live: LiveAttempt? = null,
    /** 是否正在拉第一帧快照。 */
    val loading: Boolean = true,
    /** 是否正在加载更早的历史。 */
    val loadingMore: Boolean = false,
    /** 还有更早的历史可以加载（`snapshot.hasMore` / `page.hasMore`）。 */
    val hasMore: Boolean = false,
    /** 日志尾部 seq；翻页的 `throughSeq`。 */
    val cursor: Long = -1L,
    /** 会话标题（来自 `projections.values.title`）。 */
    val title: String? = null,
    /** 工作目录（来自 `header.cwd`）。 */
    val cwd: String? = null,
    /**
     * 该会话是否正在生成。
     *
     * 权威来源是轮次边界事件（`turn/start` 置真、`turn/end` 置假），不是「有没有预览」。
     * 一个轮次里有多个步骤，每步都会落盘一条 `assistant/message`（实测 turn 27 有 45 步），
     * 所以**不能**因为落盘就置假——那会让中止按钮在第 1 步之后就消失。
     */
    val generating: Boolean = false,
    /** 发送/中止等操作的错误提示。 */
    val error: String? = null,
    /** 是否已成功收到过第一帧（用于区分「空会话」与「还没加载」）。 */
    val ready: Boolean = false,
    /**
     * `assistant-stream` 的 `end(committed)` 报告的落盘 seq。
     *
     * 该 seq 的持久化事件到达时预览必须丢弃。之所以要记住它而不是「等事件自己来清」：
     * 落盘事件可能因为畸形而解析不出卡片，此时只有这个 seq 能兜底，否则预览会永久残留。
     */
    val committedSeq: Long = -1L,
)

/**
 * 把 [ChatFrame] 折叠进 [ChatUiState] 的纯函数。
 *
 * 为什么是纯函数而不是写在 ViewModel 里：聊天状态机是本次改动里最容易出错的部分
 * （乱序、重复、重连补齐），必须能脱离网络与 Compose 单独测。
 *
 * 四条不变量：
 * 1. [ChatUiState.items] 永远按 `seq` 严格升序且不重复（用 `seq` 去重）。
 * 2. 同 `(turn, step)` 的助手消息到达时，对应的 [ChatUiState.live] 预览**必须**被丢弃。
 * 3. 快照（`snapshot` 帧）**替换**而不是追加条目——它是一段窗口，不是增量。
 * 4. [ChatUiState.generating] 只由 `turn/start` / `turn/end`（以及快照的活跃尝试）决定。
 */
object ChatReducer {

    /** 折叠一帧。 */
    fun reduce(state: ChatUiState, frame: ChatFrame): ChatUiState = when (frame) {
        is ChatFrame.Snapshot -> onSnapshot(state, frame)
        is ChatFrame.Event -> onEvent(state, frame)
        is ChatFrame.Live -> onLive(state, frame.frame)
    }

    /**
     * 开场快照。
     *
     * 条目**替换**（快照是一段窗口）；`hasMore`/`cursor` 用快照的值。
     * 重连时会再次收到快照，此时用新窗口覆盖旧窗口是正确的——服务端保证窗口是
     * 从同一游标往前推的连续区间（`history.js:395-426` `paginate`）。
     */
    private fun onSnapshot(state: ChatUiState, frame: ChatFrame.Snapshot): ChatUiState {
        val next = state.copy(
            // `distinctBy` 不只是洁癖：`ChatScreen` 用 `key = { it.seq }` 渲染
            // `LazyColumn`，重复 key 会直接抛 `IllegalArgumentException`。服务端窗口
            // 理论上不会重复，但这一行是零成本的保险（R003 P1-1 同类问题）。
            items = frame.items.distinctBy { it.seq }.sortedBy { it.seq },
            loading = false,
            ready = true,
            hasMore = frame.hasMore,
            cursor = frame.cursor,
            title = frame.title ?: state.title,
            cwd = frame.cwd ?: state.cwd,
            error = null,
            // 快照是权威窗口，之前挂起的 committed 预览在这里一并作废。
            committedSeq = -1L,
        )
        if (frame.baseline.isNotEmpty()) {
            // 重连基线：把断开期间已生成但未落盘的增量回放成一个预览气泡。
            //
            // `turn`/`step`/`nextIndex` 从 `assistantStream.activeAttempt` 上读，而不是猜。
            // 猜错会让预览与落盘事件的 `(turn, step)` 对不上，于是预览永远清不掉，
            // 表现为同一条消息渲染两遍（R004 P2-2）。
            val seed = LiveAttempt(
                attemptId = frame.baselineAttemptId ?: BASELINE_ATTEMPT_ID,
                turn = frame.baselineTurn,
                step = frame.baselineStep,
                nextIndex = frame.baselineNextIndex,
            )
            val merged = frame.baseline.fold(seed) { acc, delta -> applyDelta(acc, delta, -1L) }
            return next.copy(live = merged.takeIf { it.hasContent() }, generating = true)
        }
        // 没有活跃尝试：这一轮没在生成。快照是权威的——prompt 之后到首帧之间
        // 由 ViewModel 乐观置真，收到快照后以快照为准（R004 P2-1）。
        return next.copy(live = null, generating = frame.tailTurnOpen)
    }

    /**
     * 一条持久化事件。
     *
     * 即使 [ChatFrame.Event.item] 为 `null`（噪声事件）也必须走这里：`turn/start` 与
     * `turn/end` 本身不产生卡片，但它们是 [ChatUiState.generating] 的唯一权威来源。
     */
    private fun onEvent(state: ChatUiState, frame: ChatFrame.Event): ChatUiState {
        val item = frame.item
        val seq = frame.seq
        val type = frame.eventType

        // 1) 轮次生命周期。只有这两个事件能翻转 generating——多步轮次里每一步都会落盘
        //    一条 assistant/message，若拿它置假，中止按钮会在第 1 步之后消失（R004 P2-1/P2-6）。
        val generating = when (type) {
            "turn/start" -> true
            "turn/end" -> false
            else -> state.generating
        }

        // 2) 预览作废的四种情形：轮次结束、本步助手消息落盘（**按帧上的 (turn, step)
        //    判定，不看 item 是否解析出卡片**——content 为空或只含被滤掉的 tool-call
        //    块时 item 为 null，只看 item 会让预览永久残留）、committed 的 seq 到了。
        //    注意「清预览」不等于「停止生成」——多步轮次里清完预览还要继续下一步。
        val live = state.live
        val clearLive = live != null && (
            type == "turn/end" ||
                (seq >= 0L && seq == state.committedSeq) ||
                (type == "assistant/message" && frame.turn == live.turn && frame.step == live.step)
            )
        val committedSeq = when {
            clearLive -> -1L
            seq >= 0L && state.committedSeq in 0L until seq -> -1L
            else -> state.committedSeq
        }

        if (item == null || item.isInvisible()) {
            // 纯噪声事件，或「只承载工具调用」的助手消息：不动条目，只同步状态标志。
            //
            // 后者必须仍然走这里——它是「本步已落盘」的信号，预览要靠它清掉
            // （见上面 `clearLive` 的第三条）。只是它自己不该占一行：
            // 那次工具调用由独立的 `tool/call` 事件渲染。
            if (type != "turn/start" && type != "turn/end" && !clearLive) return state
            return state.copy(
                generating = generating,
                live = if (clearLive) null else live,
                committedSeq = committedSeq,
                cursor = if (seq >= 0L) maxOf(state.cursor, seq) else state.cursor,
                // 收到过真实事件就不再是「正在加载」——即使这条不占行
                // （整个会话只有工具调用时，否则会永远停在转圈）。
                ready = state.ready || item != null,
                loading = if (item != null) false else state.loading,
            )
        }

        val items = (state.items + item)
            .distinctBy { it.seq }
            .sortedBy { it.seq }

        return state.copy(
            items = items,
            live = if (clearLive) null else live,
            committedSeq = committedSeq,
            ready = true,
            loading = false,
            cursor = if (seq >= 0L) maxOf(state.cursor, seq) else state.cursor,
            generating = generating,
            // 真的渲染出内容了才清错误条；否则会把重连提示一并抹掉。
            error = null,
        )
    }

    /** 一条瞬时流式帧。 */
    private fun onLive(state: ChatUiState, frame: ChatFrame.LiveFrame): ChatUiState = when (frame) {
        is ChatFrame.LiveFrame.Start -> state.copy(
            live = LiveAttempt(attemptId = frame.attemptId, turn = frame.turn, step = frame.step),
            generating = true,
            committedSeq = -1L,
        )

        is ChatFrame.LiveFrame.Chunk -> {
            val current = state.live
            // 认不出的 attemptId：忽略。可能是控制器在 Host 看到该 attempt 之后才挂上，
            // 此时没有 start 帧可重建（官方 `ClientAssistantStream.acceptFrame` 同样忽略）。
            if (current == null || current.attemptId != frame.attemptId) {
                state
            } else {
                state.copy(live = applyDelta(current, frame.delta, frame.index))
            }
        }

        is ChatFrame.LiveFrame.End -> {
            val current = state.live
            if (current == null || current.attemptId != frame.attemptId) {
                state
            } else if (frame.committedSeq == null) {
                // `abandoned`：这次尝试被丢弃，预览内容不该留下。
                state.copy(live = null, generating = false)
            } else {
                // `committed`：对应的持久化事件随后到达，届时由 [onEvent] 清预览。
                // 这里**不**清，否则会在落盘事件与预览之间闪一下空白；只记下 seq 兜底。
                state.copy(committedSeq = frame.committedSeq)
            }
        }
    }

    /**
     * 把一个 delta 折进 [LiveAttempt]。
     *
     * @param index 帧上的 `index`；基线回放传 -1（无校验、不推进）。
     */
    private fun applyDelta(
        attempt: LiveAttempt,
        delta: AssistantStreamExpander.Delta?,
        index: Long,
    ): LiveAttempt {
        if (delta == null) return attempt
        return when (delta) {
            is AssistantStreamExpander.Delta.Text -> attempt.copy(
                text = attempt.text + delta.text,
                nextIndex = advance(attempt, index),
            )

            is AssistantStreamExpander.Delta.Reasoning -> attempt.copy(
                reasoning = attempt.reasoning + delta.text,
                nextIndex = advance(attempt, index),
            )

            is AssistantStreamExpander.Delta.ToolCall -> attempt.copy(
                toolArgs = attempt.toolArgs + (delta.id to (attempt.toolArgs[delta.id].orEmpty() + delta.argumentsDelta)),
                toolNames = delta.name?.let { attempt.toolNames + (delta.id to it) } ?: attempt.toolNames,
                nextIndex = advance(attempt, index),
            )

            is AssistantStreamExpander.Delta.Raw -> attempt.copy(nextIndex = advance(attempt, index))
        }
    }

    /**
     * 推进 `nextIndex`。
     *
     * 只在 `index` 恰好等于当前期望值时推进——与官方 `acceptFrame` 一致：
     * `frame.index !== attempt.nextIndex` 时官方要求 rebaseline（重连对齐，M4 再做），
     * M3 的选择是**不推进也不丢内容**：宁可多拼一段，也不静默丢字。
     *
     * `index < 0`（基线回放）**不推进**：基线没有逐块 index，推进只会让计数虚高，
     * 后面真实 chunk 的 index 就永远对不上期望值。
     */
    private fun advance(attempt: LiveAttempt, index: Long): Int =
        if (index >= 0L && index.toInt() == attempt.nextIndex) attempt.nextIndex + 1 else attempt.nextIndex

    /** 预览里是否有任何可见内容。 */
    private fun LiveAttempt.hasContent(): Boolean =
        text.isNotEmpty() || reasoning.isNotEmpty() || toolArgs.isNotEmpty()

    /**
     * 这条条目在列表里是否会占一行。
     *
     * 「只承载工具调用」的助手消息（[visibleAssistantBlocks] 过滤后为空且未被中止）
     * 不占行：那次调用由独立的 `tool/call` 事件渲染。但它**仍然必须**被 [onEvent]
     * 处理，因为它是「本步已落盘」的信号，预览要靠它清掉。
     */
    private fun ChatItem.isInvisible(): Boolean =
        this is ChatItem.Assistant && visibleAssistantBlocks(blocks, interrupted).isEmpty()

    /** 基线回放出来的预览用的伪 attemptId（不会与真实 attempt 相等）。 */
    private const val BASELINE_ATTEMPT_ID = "__baseline__"
}
