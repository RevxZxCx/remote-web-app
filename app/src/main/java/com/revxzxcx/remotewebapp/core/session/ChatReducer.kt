package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.changes.ChangesEntry
import kotlinx.serialization.json.JsonObject

/**
 * 聊天页的状态。
 *
 * [items] 是**已持久化**的条目，按 `seq` 升序；[live] 是正在生成、尚未落盘的预览。
 * 两者分开存是刻意的：落盘条目是权威的、可翻页的；预览是易失的，收到同 `(turn, step)`
 * 的 `assistant/message` 后整块丢弃。这样就不会出现「同一句话显示两遍」。
 *
 * [queued] 是第三种时间轴：已经发给服务端、但服务端还没写进会话日志的消息
 * （`mode:'queue'` 的产物）。它和 [items]/[live] 都不重叠，因此渲染顺序固定为
 * `loadingMore → items → live → queued`，见 `ui/chat/ChatScreen.kt`。
 */
data class ChatUiState(
    /** 按 seq 升序的持久化条目。 */
    val items: List<ChatItem> = emptyList(),
    /** 正在生成的预览；为 null 表示当前没有活跃生成。 */
    val live: LiveAttempt? = null,
    /**
     * 服务端**排队队列**的本地镜像（`mode:'queue'` 的产物）。
     *
     * 权威来源有两个：`session/follow` 快照的 `projections.values.inbox`，以及实时事件
     * `agent/inbox/spliced`（插入 / 编辑 / 删除都由它通报）。消息被服务端取出执行时，
     * `user/message` 事件的 `data.source.rpcId` 就是配对键。
     *
     * 纯本地乐观状态，没有 `seq`，因此**不参与 [items] 的任何运算**（游标、翻页、
     * `distinctBy{seq}`），也绝不写进 Room 缓存。
     *
     * 渲染顺序固定为 `loadingMore → items → live → queued`，见 `ui/chat/ChatScreen.kt`。
     */
    val queued: List<QueuedMessage> = emptyList(),
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
    /**
     * 流（`session/follow`）断开的错误提示。
     *
     * 与 [sendError] / [pageError] 分开是刻意的（R006 P3-6）：早前三类故障共用一个字段，
     * 于是「发送失败」「翻页失败」的错误条上那个「重试」也去调 `retry()`——重启整条 follow 流。
     * 重启流比失败本身重得多（丢掉 live 预览、重新拉整个窗口），而且**不会**重新发送那条
     * 消息、也不会重新翻那一页，用户点了按钮发现什么都没发生。三处各自带自己的重试动作。
     */
    val error: String? = null,
    /** 发送 / 中止失败。重试动作是重新发送，不是重启流。 */
    val sendError: String? = null,
    /**
     * 正在发送（`session/prompt` 在途）。
     *
     * T5 设备验收：没有这个标志时「发送」按钮在 RPC 期间一直是亮的，用户会反复点。
     * 放进 reducer 状态而不是 `remember`：旋转屏幕后不该丢掉「正在发送」这个事实。
     */
    val sending: Boolean = false,
    /** 翻页（加载更早历史）失败。重试动作是重新翻页。 */
    val pageError: String? = null,
    /** 是否已成功收到过第一帧（用于区分「空会话」与「还没加载」）。 */
    val ready: Boolean = false,
    /**
     * `assistant-stream` 的 `end(committed)` 报告的落盘 seq。
     *
     * 该 seq 的持久化事件到达时预览必须丢弃。之所以要记住它而不是「等事件自己来清」：
     * 落盘事件可能因为畸形而解析不出卡片，此时只有这个 seq 能兜底，否则预览会永久残留。
     */
    val committedSeq: Long = -1L,
    /**
     * 「这一轮改了哪些文件」的读取结果，按 [ChatItem.Changes] 的 `seq` 索引（需求 FR-5.4）。
     *
     * 值是 [ChangesEntry] 三态：拿到摘要 / 记录已失效（404，终态）/ 读取失败（可重试）。
     * **key 不存在**才是「还没拉」，与 [ChangesEntry.Unavailable]（拉过，确定没了）不同，
     * 也与 `total == 0`（拉了，这轮确实没改文件）不同。
     *
     * 这个 map 与 [changesLoading] 必须一起被裁剪（见 [onSnapshot]）：只要「有没有结果」
     * 完全由状态自身表达，就不需要另存一份「请求过哪些 seq」的台账——那份台账与这里
     * 的裁剪不同步时，卡片回到窗口后会永远显示「记录已不可用」（R005 P1-1 / P2-2）。
     */
    val changes: Map<Long, ChangesEntry> = emptyMap(),
    /** 正在拉取摘要的 seq 集合（UI 显示转圈）。 */
    val changesLoading: Set<Long> = emptySet(),
    /**
     * 当前条目来自本地缓存、还没有被网络快照确认（需求 FR-4.3）。
     *
     * 与工作区页的 `HomeUiState.fromCache` 同一用途：让聊天页能显示一条「正在同步…」的
     * 细提示，而不是让用户以为看到的就是最新状态。收到任何一帧后由 reducer 置假。
     */
    val fromCache: Boolean = false,
    /**
     * 这个会话下一次轮次要用的模型（`projections.values.modelSelection.next`）。
     *
     * 权威来源是快照投影，但用户刚切完模型时服务端要等下一次快照才更新它，所以
     * `selectModel` 成功后由 ViewModel 直接覆盖（乐观更新）——否则按钮会在几秒内
     * 继续显示旧模型。
     */
    val model: ModelRef? = null,
    /**
     * 切换模型失败。重试动作是重新选择，与流/发送/翻页的失败都无关（R006 P3-6 同理由）。
     */
    val modelError: String? = null,
    /**
     * 可选模型目录；为 null 表示还没拉到（或这个会话拉不到）。
     *
     * 与 [ChatUiState.model] 分开：前者是「能选谁」，后者是「现在选的是谁」。
     * 目录是全局的、几乎不变，所以拉一次就够；但它失败不该影响聊天页的其它功能。
     */
    val catalog: ModelCatalog? = null,
    /** 是否正在拉取可选模型目录。 */
    val catalogLoading: Boolean = false,
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
 * 3. 快照（`snapshot` 帧）**替换**它覆盖到的那段窗口，但**保留**窗口之前的已加载历史
 *    （`seq < 快照最小 seq`）——否则每次重连都会丢掉用户翻出来的旧消息（FR-4.2）。
 * 4. [ChatUiState.generating] 只由 `turn/start` / `turn/end`（以及快照的活跃尝试）决定。
 */
object ChatReducer {

    /**
     * 折叠一帧。
     *
     * `fromCache` 在**任何**一帧到达后都归假：帧只可能来自存活的 `session/follow` 流，
     * 所以「收到帧」本身就等于「网络已通、屏幕上的东西即将/已经被权威数据覆盖」。
     * 放在这里统一处理，而不是在三个 `onXxx` 里各写一遍——漏一处就会让「正在同步…」
     * 的提示永久挂在界面上（这类「标志位没复位」的缺陷本工程已出现过三次：R003 P1-2、
     * R004 P2-1、R005b P3-11）。
     */
    fun reduce(state: ChatUiState, frame: ChatFrame): ChatUiState =
        when (frame) {
            is ChatFrame.Snapshot -> onSnapshot(state, frame)
            is ChatFrame.Event -> onEvent(state, frame)
            is ChatFrame.Live -> onLive(state, frame.frame)
        }.copy(fromCache = false)

    /**
     * 开场快照。
     *
     * **重连补齐（FR-4.2）**：快照是一段窗口而不是全量——`session/follow` 的
     * `maxMessages` 只有几百条，而用户可能已经用 `session/page` 翻出更早的历史。
     * 早前这里直接「用帧内容替换 items」，于是每断一次线、每重连一次，用户翻出来的旧历史
     * 就被静默丢掉（而他仍停留在原来的滚动位置，看到的是消息凭空消失）。
     *
     * 现在的合并规则分两段，判据是**快照自身的最小 seq**：
     *  - `seq < snapshotMinSeq` 的旧条目：快照窗口根本没覆盖它们，**保留**；
     *  - `seq >= snapshotMinSeq` 的：快照是权威，一律以帧内容为准（这不是「新数据覆盖旧数据」
     *    的猜测，而是服务端对同一段日志的重新下发——同 seq 的事件内容必然相同）。
     *
     * 边界：快照为空时 `snapshotMinSeq` 取 [Long.MAX_VALUE]，于是所有已加载的历史都被保留。
     * 这是刻意的——空快照只说明「这个窗口没有消息」，不说明「日志是空的」，把用户已翻出来的
     * 历史清掉才是错的。
     *
     * `cursor` 一律用快照的：它是日志尾部游标，服务端权威。
     */
    private fun onSnapshot(state: ChatUiState, frame: ChatFrame.Snapshot): ChatUiState {
        val window = frame.items.distinctBy { it.seq }.sortedBy { it.seq }
        // 窗口边界取**两者更小**的那个：`windowMinSeq`（窗口信封的最小 seq）与条目最小 seq
        // 取 min，是因为 `turn/start` / `step/end` 这类事件不占行、解析不出条目，却同样属于
        // 「快照已覆盖」的范围。只看条目最小 seq 会把它们当成窗口之外而从旧 items 里保留同
        // seq 的副本，而 `distinctBy` 保留首次出现 → 旧内容胜出（R006 P3-1）。解析器没给
        // （单测手工构造的帧）时退回条目最小 seq；两者都没有说明窗口是空的。
        val snapshotMinSeq = minOf(
            frame.windowMinSeq ?: Long.MAX_VALUE,
            window.firstOrNull()?.seq ?: Long.MAX_VALUE,
        )
        val older = state.items.filter { it.seq < snapshotMinSeq }
        // `distinctBy` 不只是洁癖：`ChatScreen` 用 `key = { it.seq }` 渲染
        // `LazyColumn`，重复 key 会直接抛 `IllegalArgumentException`。服务端窗口
        // 理论上不会重复，但这一行是零成本的保险（R003 P1-1 同类问题）。
        val items = (older + window).distinctBy { it.seq }.sortedBy { it.seq }
        // 快照替换窗口后，窗口外的摘要就该丢掉：否则长时间挂着会随会话越长越吃内存。
        // 仍在窗口内的必须保留——同一轮次的摘要没有必要重新拉一遍（每次都是一次网络往返）。
        val liveSeqs = items.asSequence().filterIsInstance<ChatItem.Changes>().map { it.seq }.toSet()
        // 游标与 hasMore 必须相对**合并后的 items** 判断，不能直取快照窗口值（R006 P2-9）：
        // items 已经是「更早历史 ∪ 快照窗口」，若用户此前已翻到 seq=1，快照窗口最小 seq
        // 可能远大于 1，直取 `frame.hasMore` 就会留下一个点了没用的「加载更早」。
        val oldest = items.firstOrNull()?.seq
        val next = state.copy(
            items = items,
            loading = false,
            ready = true,
            hasMore = frame.hasMore && (oldest == null || oldest > 1L),
            // `cursor` 是日志**尾部**游标，服务端权威；但快照缺该字段时解析器给的是 -1，
            // 直取会把已推进的有效游标覆盖回去（`onEvent` 用的是 `maxOf`，两处不一致）。
            cursor = if (frame.cursor >= 0L) maxOf(state.cursor, frame.cursor) else state.cursor,
            title = frame.title ?: state.title,
            cwd = frame.cwd ?: state.cwd,
            error = null,
            // 快照是权威窗口，之前挂起的 committed 预览在这里一并作废。
            committedSeq = -1L,
            changes = state.changes.filterKeys { it in liveSeqs },
            changesLoading = state.changesLoading.filterTo(mutableSetOf()) { it in liveSeqs },
            fromCache = false,
            model = frame.model ?: state.model,
            // 快照带权威队列；本地回声里已被服务端确认（同 rpcId）的让位给它。
            queued = mergeQueued(frame.inbox, state.queued),
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
        // committed 预览的兜底作废：`committed` 的落盘事件若因信封畸形被解析器整帧丢弃，
        // 「seq == committedSeq」这条永远等不到。游标一旦越过它，这个预览就不可能再被
        // 对应的落盘事件清掉了——继续留着会一直显示到本轮 `turn/end`，看上去像同一条消息
        // 渲染了两遍。**清 committedSeq 的地方必须同时清 live**（R006 P2-10）。
        val committedExpired = !clearLive && seq >= 0L && state.committedSeq in 0L until seq
        val dropLive = clearLive || committedExpired
        val committedSeq = when {
            clearLive || committedExpired -> -1L
            else -> state.committedSeq
        }

        // 2) 模型选择。`model/selection` 事件由 session controller 在**任何一侧**改模型时
        //    追加（桌面端与手机端走同一条路），data 就是 {provider, model, reasoningEffort?}。
        //    不收它就只能等下一次快照才同步——用户看到的就是「桌面端改了模型，手机端不动，
        //    退出会话重进才看到」（T4 实测）。这里与 selectModel 的乐观更新汇合到同一字段。
        val model = if (type == "model/selection") {
            ModelCatalogParser.parseSelectionValue(frame.envelope?.get("data")) ?: state.model
        } else {
            state.model
        }

        // 3) 排队队列。权威来源有两个：快照的 `inbox` 投影（上面 onSnapshot 处理）与实时
        //    事件 `agent/inbox/spliced`；「这条消息被服务端取出执行」则由 `user/message`
        //    标记（事件 data 就是 UserMessage，`source.rpcId` 是我们发出去的 requestId）。
        //
        //     ⚠️ **不要**在 `turn/start` 上清空全部队列。服务端是一条一清的
        //    （用户 m20729/m20733 实测：连着发两条，序列是
        //    `user/message(A) → turn/start → … → turn/end → user/message(B) → turn/start`），
        //    所以 `turn/start` 到达时**后面的消息还在队列里**，清空就等于让它们重新变成
        //    看不见的消息——正是用户 m20380 反馈的缺口，只是窗口短一些。
        //    回声因此可能在一个角落情形里残留：另一个客户端（桌面端）用 `updateQueue`
        //    `remove` 把条目撤掉，此时不会有 `user/message`。代价是一条不会自愈的
        //    「排队中」气泡，远好过把真实还排着队的消息吞掉。
        val queued = when (type) {
            "agent/inbox/spliced" -> {
                val splice = QueueInboxParser.parseSplice(frame.envelope?.get("data"))
                if (splice == null) state.queued else applySplice(state.queued, splice)
            }

            "user/message" -> dropDispatched(state.queued, frame)
            else -> state.queued
        }

        if (item == null || item.isInvisible()) {
            // 不占行的东西：纯噪声事件（`step/end` 等），或「只承载工具调用」的助手消息。
            //
            // 这里**不能**提前 return state。早前对「与预览无关的噪声事件」直接返回，
            // 于是走不到下面的游标推进：断线重连后 `cursor` 会落后于真实收到的事件，
            // 下一次翻页会重复请求已经拿到的区间（R006 P3-15）。
            //
            // `ready`/`loading` 也一样必须更新，判据是「收到了一条可解析的真实事件信封」
            // 而不是「解析出了卡片」：整个会话只有工具调用时，用 `item != null` 当判据会让
            // 页面永远停在转圈（R006 P3-14）。
            return state.copy(
                generating = generating,
                live = if (dropLive) null else live,
                committedSeq = committedSeq,
                cursor = if (seq >= 0L) maxOf(state.cursor, seq) else state.cursor,
                ready = true,
                loading = false,
                model = model,
                queued = queued,
            )
        }

        // 同 seq 的新条目必须**替换**旧条目。写成 `state.items + item` 再 `distinctBy` 会保留
        // 首次出现的那份（旧内容），于是一条被重新投递、内容已更新的同 seq 事件被静默丢弃
        // （R006 P3-13）。
        val items = (state.items.filterNot { it.seq == item.seq } + item).sortedBy { it.seq }

        return state.copy(
            items = items,
            live = if (dropLive) null else live,
            committedSeq = committedSeq,
            ready = true,
            loading = false,
            cursor = if (seq >= 0L) maxOf(state.cursor, seq) else state.cursor,
            generating = generating,
            model = model,
            queued = queued,
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
                //
                // **不动 `generating`**：不变量 4 规定它只由 `turn/start`/`turn/end` 翻转。
                // 一次尝试被丢弃并不代表本轮结束——多步轮次里控制器会立刻开新尝试，若在这里
                // 置 false，中止按钮会提前消失且直到下一轮 `turn/start` 才恢复（R006 P2-11）。
                state.copy(live = null)
            } else {
                // `committed`：对应的持久化事件随后到达，届时由 [onEvent] 清预览。
                // 这里**不**清，否则会在落盘事件与预览之间闪一下空白；只记下 seq 兜底。
                state.copy(committedSeq = frame.committedSeq)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 排队队列（服务端 inbox）的合并规则
    // ---------------------------------------------------------------------------

    /**
     * 把服务端权威队列 [server] 与本地回声 [local] 合并。
     *
     * 本地回声（`confirmed == false`）在拿到服务端 itemId 之前必须留着，否则用户刚点发送
     * 会看到气泡闪一下就没了。配对键是 `rpcId`：服务端在 `UserMessage.source.rpcId` 里
     * **原样带回**我们传出去的 requestId（`_inboxp4.mjs` 实测），比按文本匹配稳得多。
     *
     * 服务端条目排在前面——它是权威；回声只补充「服务端还没确认」的那几条。
     */
    private fun mergeQueued(server: List<QueuedMessage>, local: List<QueuedMessage>): List<QueuedMessage> {
        if (local.isEmpty()) return server
        val confirmed = server.mapTo(mutableSetOf()) { it.rpcId }
        val echoes = local.filter { it.rpcId.isNotEmpty() && it.rpcId !in confirmed }
        return server + echoes
    }

    /**
     * 应用一条 `agent/inbox/spliced`。
     *
     * 关键点：`start` 是**目标 target 数组内**的下标（`index.js:969` 的 `inbox.replace`
     * 与 `:975` 的 `inbox.remove` 都按 target 各自的下标体系走，官方
     * `client.js:2158-2173` 也是按 target 分组的）。所以两个 target 必须分开计数，
     * 混成一个列表会把下标算错。
     *
     * 插入位置取「被删的第一条」的位置：这样 `edit`（`removedCount:1` + `inserted:[m']`）
     * 就是就地替换，纯插入（`removedCount` 缺席）则排在原第 `start` 条之前。
     */
    private fun applySplice(current: List<QueuedMessage>, splice: InboxSplice): List<QueuedMessage> {
        val out = mutableListOf<QueuedMessage>()
        var seenInTarget = 0
        var inserted = false
        for (message in current) {
            if (message.target == splice.target) {
                val indexInTarget = seenInTarget
                seenInTarget++
                if (indexInTarget >= splice.start &&
                    indexInTarget < splice.start + splice.removedCount
                ) {
                    if (!inserted && indexInTarget == splice.start) {
                        out.addAll(splice.inserted)
                        inserted = true
                    }
                    continue
                }
            }
            out.add(message)
        }
        // 删除区间在段尾之外（或该 target 段是空的）：追加到末尾。
        // 用户主动发起的 prompt 就是这种形状（服务端 append 到队列尾）。
        if (!inserted && seenInTarget <= splice.start) out.addAll(splice.inserted)
        // 服务端把我们发出的 requestId 原样带回 → 对应的本地回声让位。
        val confirmedRpcIds = splice.inserted
            .map { it.rpcId }
            .filter { it.isNotEmpty() }
            .toSet()
        return out.filterNot { !it.confirmed && it.rpcId in confirmedRpcIds }
    }

    /**
     * 一条 `user/message` 到达：服务端把某条排队消息取出执行了，队列里对应的那一条该消失。
     *
     * 匹配键按可靠性排序：`source.rpcId`（我们自己发出去的 requestId，服务端原样带回）
     * → `id`（服务端 itemId）→ **FIFO 兜底**（摘最老的一条）。
     *
     * 兜底为什么留着： splice 事件可能因为丢帧/重连没收到，而 `user/message` 一定会在
     * 日志里。此时摘最老的一条仍然是最佳猜测——回声本来就只有我们自己发的那几条。
     */
    private fun dropDispatched(current: List<QueuedMessage>, frame: ChatFrame.Event): List<QueuedMessage> {
        if (current.isEmpty()) return current
        val data = frame.envelope?.get("data") as? JsonObject
        val source = data?.get("source") as? JsonObject
        val rpcId = source?.stringOrNull("rpcId")
        val id = data?.stringOrNull("id")
        val index = current.indexOfFirst { message ->
            (rpcId != null && message.rpcId == rpcId) ||
                (id != null && message.confirmed && message.id == id)
        }
        return when {
            index >= 0 -> current.filterIndexed { i, _ -> i != index }
            // 匹配不上说明这条 `user/message` 不是我们的排队消息（例如桌面端发的），不动队列。
            rpcId == null && id == null -> current
            else -> current.drop(1)
        }
    }

    /**
     * 把一个 delta 折进 [LiveAttempt]。
     *
     * @param index 帧上的 `index`；基线回放传 -1（无校验、不推进）。
     */
    private fun applyDelta(        attempt: LiveAttempt,
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
     *
     * `index` 超 `Int` 范围时**不推进**：早前写成 `index.toInt() == nextIndex`，`toInt()`
     * 高位截断会让畸形的巨大 index 恰好等于 `nextIndex` 而错误推进（R006 P3-17）。
     */
    private fun advance(attempt: LiveAttempt, index: Long): Int {
        if (index < 0L || index > Int.MAX_VALUE.toLong()) return attempt.nextIndex
        return if (index.toInt() == attempt.nextIndex) attempt.nextIndex + 1 else attempt.nextIndex
    }

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
