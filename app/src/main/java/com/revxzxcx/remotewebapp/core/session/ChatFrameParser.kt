package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `session/follow` 的一帧。
 *
 * 线上形状（`dsh-api-session-controller/lib/typert.remote-client.js:221-424`
 * `SessionFollowFrame`）——三种，判别键是 `type`：
 * ```
 * {type:'snapshot', header, cursor, records:[{type:'event',event}], hasMore, projections, assistantStream?}
 * {type:'event', event:{type,seq,time,data,...}}
 * {type:'assistant-stream', frame:{...}}
 * ```
 *
 * 第三种 `assistant-stream` 只在请求里带 `assistantStream:true` 时才出现，
 * 是**瞬时预览**（不落盘），用于逐字上屏。
 */
sealed interface ChatFrame {

    /** 开场快照：一段历史窗口 + 游标 + 是否还有更早的。 */
    data class Snapshot(
        val items: List<ChatItem>,
        /** 当前日志尾部的 seq；`session/page` 的 `throughSeq` 用它。 */
        val cursor: Long,
        val hasMore: Boolean,
        /** 会话标题（`projections.values.title`），可为空。 */
        val title: String?,
        /** 工作目录（`header.cwd`）。 */
        val cwd: String?,
        /**
         * 重连基线：一个仍在进行中的助手尝试的**压缩**流记录。
         *
         * 桌面端 `ClientAssistantStream.replace()` 用它把「断开期间已生成但尚未落盘」的
         * 增量补回来（`lib/types/client/sessions/assistant-stream.js:22-56`）。
         */
        val baseline: List<AssistantStreamExpander.Delta>,
        /**
         * 基线对应的尝试元数据（`assistantStream.activeAttempt`）。
         *
         * 这些字段必须从线上读、不能猜：预览与落盘事件靠 `(turn, step)` 匹配才能被丢弃，
         * 猜错就会「同一条消息渲染两遍」并永久停在生成态（R004 P2-2）。
         */
        val baselineAttemptId: String? = null,
        val baselineTurn: Long = 0L,
        val baselineStep: Long = 0L,
        val baselineNextIndex: Int = 0,
        /**
         * 快照窗口的末尾是否处于「一个已开始但未结束的轮次」。
         *
         * 由窗口里最后一个 `turn/start` / `turn/end` 谁在后决定。它是
         * [ChatUiState.generating] 在快照路径上的权威来源（R004 P2-1）。
         */
        val tailTurnOpen: Boolean = false,
        /**
         * 窗口里所有可解析事件的**原始信封**，供离线缓存落盘（需求 FR-4.3）。
         *
         * 与 [items] 是同一批事件的两种视图：`items` 只含能渲染成卡片的（`turn/start` 这类
         * 噪声不占行，却是生成状态的权威来源），缓存要存的是**全部**事件——不然重连回放时
         * 生成状态就丢了。
         */
        val envelopes: List<JsonObject> = emptyList(),
        /**
         * 窗口内**全部可解析记录**（含不占行的事件）的最小 `seq`。
         *
         * 必须与 [items] 的最小 seq 区分开：`turn/start`、`step/end` 这类事件解析不出卡片，
         * 但同样在快照覆盖范围内。重连合并时若用 [items] 的最小 seq 当窗口下界，这些事件
         * 的 seq 会被当成「窗口之外」而保留旧条目，`distinctBy` 又保留先到的旧内容
         * （R006 P3-1）。
         */
        val windowMinSeq: Long? = null,
        /**
         * 当前/下一次轮次要用的模型（`projections.values.modelSelection`）。
         *
         * 取 `next` 而不是 `lastUsed`：用户改了模型但还没开始下一轮时，`lastUsed` 仍是旧值，
         * 按钮上显示旧模型会让用户以为「没改上」。为 null 表示这个投影还没到（服务端可能
         * 在会话还没有任何模型选择时不下发）。
         */
        val model: ModelRef? = null,
        /**
         * 这一轮消息里图片的硬限制（`projections.values.imageLimits`）。
         *
         * 客户端在用户选图时先按它挡一道，给出中文即时反馈；真正的强制校验在服务端
         * `attachments.admitPromptContent`（`attachmentService.ts:782-829`）。
         * 为 null 表示该投影还没下发（服务端可能在某些版本不带），此时按「不限制」处理。
         */
        val imageLimits: ImageLimits? = null,
        /**
         * 服务端**排队队列**（`projections.values.inbox`）。
         *
         * 这是 [ChatUiState.queued] 的权威来源之一（另一个是 `agent/inbox/spliced` 事件）。
         * 为 emptyList 表示这个投影没下发或队列是空的——两者对 UI 是同一件事。
         */
        val inbox: List<QueuedMessage> = emptyList(),
    ) : ChatFrame

    /**
     * 一条持久化事件。
     *
     * [eventType] 与 [seq] 即使在 [item] 为 `null` 时也必须有值：`turn/start` / `turn/end`
     * 不产生卡片，却是生成状态的唯一权威来源。
     *
     * [turn] / [step] 同样如此：`assistant/message` 的 `content` 为空（中止、或纯
     * tool-call 块被过滤后）时 [item] 可能解析不出卡片，但它是「本步已落盘」的信号，
     * 预览必须靠它清掉，否则流式气泡会永久残留在权威内容旁边（R004 P2-3）。
     */
    data class Event(
        val eventType: String,
        val seq: Long,
        val item: ChatItem?,
        /** 事件载荷自带的轮次；`user/message` 等没有这个字段，故可空。 */
        val turn: Long? = null,
        /** 事件载荷自带的步骤；`turn/start` / `turn/end` 没有，故可空。 */
        val step: Long? = null,
        /**
         * 原始事件信封 `{type,seq,time,data}`，供离线缓存落盘（需求 FR-4.3）。
         *
         * 为什么要把原文带出来而不是让调用方从 [item] 反推：缓存要存的是**服务端原样**
         * 下发的东西（见 `CachedMessage` 的 KDoc）。从 [item] 反推需要把「解析」这套规则
         * 再写一遍反向版本，两份实现必然漂移。这里只是把已经拿到的对象多带一程。
         */
        val envelope: JsonObject? = null,
    ) : ChatFrame

    /** 瞬时流式帧。 */
    data class Live(val frame: LiveFrame) : ChatFrame

    /** 瞬时流式帧的三种形态（`SessionFollowFrame` 里 `frame` 的联合）。 */
    sealed interface LiveFrame {

        /** `{type:'start', attemptId, revision, startedAfterSeq, turn, step}`。 */
        data class Start(
            val attemptId: String,
            val turn: Long,
            val step: Long,
        ) : LiveFrame

        /** `{type:'chunk', attemptId, revision, index, time, chunk}`。 */
        data class Chunk(
            val attemptId: String,
            val index: Long,
            val time: Long,
            val delta: AssistantStreamExpander.Delta?,
        ) : LiveFrame

        /**
         * `{type:'end', attemptId, revision, index, outcome}`。
         *
         * `outcome.kind == 'committed'` 表示对应 seq 的持久化事件会（或已经）到达；
         * `'abandoned'` 表示这次尝试被丢弃，预览内容应当丢掉。
         */
        data class End(
            val attemptId: String,
            val index: Long,
            val committedSeq: Long?,
        ) : LiveFrame
    }
}

/**
 * 把 `session/follow` 的原始帧解析成 [ChatFrame]。
 *
 * 与 [com.revxzxcx.remotewebapp.core.workspace.WorkspaceFrameParser] 同样的策略：
 * 手写、宽容、未知帧返回 `null` 由调用方忽略（不抛异常）。
 */
class ChatFrameParser(
    private val events: SessionEventParser = SessionEventParser(),
    private val expander: AssistantStreamExpander = AssistantStreamExpander(),
) {

    /** 解析一帧；无法识别时返回 `null`。 */
    fun parse(element: JsonElement): ChatFrame? {
        val obj = element.asObjOrNull() ?: return null
        return when (obj.stringOrNull("type")) {
            "snapshot" -> snapshot(obj)
            "event" -> eventFrame(obj["event"])
            "assistant-stream" ->
                (obj["frame"] as? JsonObject)?.let { liveFrame(it) }?.let { ChatFrame.Live(it) }

            else -> null
        }
    }

    /**
     * 解析 `session/page` 的结果 `{records:[{type:'event',event}], hasMore}`。
     *
     * 返回 (可渲染条目, 是否还有更早)。
     */
    fun parsePage(element: JsonElement): Pair<List<ChatItem>, Boolean> {
        val obj = element.asObjOrNull() ?: return emptyList<ChatItem>() to false
        return records(obj["records"]) to (obj.booleanOrNull("hasMore") == true)
    }

    /** 把 `[{type:'event',event}]` 解析成有序、去重的条目列表。 */
    fun records(element: JsonElement?): List<ChatItem> {
        val array = element as? JsonArray ?: return emptyList()
        return array
            .mapNotNull { entry ->
                val wrapper = entry as? JsonObject ?: return@mapNotNull null
                wrapper["event"]?.let { events.parse(it) }
            }
            .distinctBy { it.seq }
            .sortedBy { it.seq }
    }

    /**
     * 从 `[{type:'event',event}]` 里取出**原始事件信封**，供离线缓存落盘。
     *
     * 只保留能解析出 `{type,seq,time,data}` 的对象：缓存里的每一行都要能被
     * [SessionEventParser] 原样读回来，塞进无法解析的东西只会在读取时被丢掉。
     */
    fun recordEnvelopes(element: JsonElement?): List<JsonObject> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val wrapper = entry as? JsonObject ?: return@mapNotNull null
            val event = wrapper["event"] as? JsonObject ?: return@mapNotNull null
            event.takeIf { events.parseEvent(it) != null }
        }
    }

    /**
     * 解析一条 `event` 帧。
     *
     * 信封解析不出 `type`/`seq` 时返回 `null`（整帧丢弃）；能解析出信封但载荷不可渲染时
     * 仍然返回 [ChatFrame.Event]，只是 `item == null`——因为 `turn/start`、`turn/end`
     * 这类事件必须让 reducer 看见。
     */
    private fun eventFrame(element: JsonElement?): ChatFrame.Event? {
        val obj = element as? JsonObject ?: return null
        val event = events.parseEvent(obj) ?: return null
        return ChatFrame.Event(
            eventType = event.type,
            seq = event.seq,
            item = events.toChatItem(event),
            // 从**信封的 data** 直接取轮次/步骤，不经过 item：item 可能因为 content 为空
            // 或只含 tool-call 块而解析不出卡片，但预览清理依赖这对坐标。
            turn = event.data.longOrNull("turn"),
            step = event.data.longOrNull("step"),
            envelope = obj,
        )
    }

    private fun snapshot(obj: JsonObject): ChatFrame.Snapshot {
        val header = obj.objectOrNull("header")
        val values = obj.objectOrNull("projections")?.objectOrNull("values")
        val activeAttempt = obj.objectOrNull("assistantStream")?.objectOrNull("activeAttempt")
        val rawRecords = obj["records"]
        val envelopes = recordEnvelopes(rawRecords)
        return ChatFrame.Snapshot(
            items = records(rawRecords),
            cursor = obj.longOrNull("cursor") ?: -1L,
            hasMore = obj.booleanOrNull("hasMore") == true,
            title = values?.stringOrNull("title"),
            cwd = header?.stringOrNull("cwd"),
            // `activeAttempt.stream` 是压缩记录数组，展开成 delta 才能回放。
            baseline = activeAttempt?.let { expander.expand(it["stream"]) }.orEmpty(),
            baselineAttemptId = activeAttempt?.stringOrNull("attemptId"),
            baselineTurn = activeAttempt?.longOrNull("turn") ?: 0L,
            baselineStep = activeAttempt?.longOrNull("step") ?: 0L,
            baselineNextIndex = (activeAttempt?.longOrNull("nextIndex") ?: 0L)
                .coerceAtLeast(0L)
                .toInt(),
            tailTurnOpen = tailTurnOpen(rawRecords),
            envelopes = envelopes,
            // 窗口下界取**全部可解析事件**的 seq，而不是能渲染成卡片的 items：
            // 噪声事件的 seq 同属窗口覆盖范围（R006 P3-1）。
            windowMinSeq = envelopes
                .mapNotNull { events.parseEvent(it)?.seq }
                .minOrNull(),
            model = ModelCatalogParser.parseSelection(
                values?.get("modelSelection"),
                which = "next",
            ),
            imageLimits = ImageLimits.of(values?.get("imageLimits")),
            inbox = QueueInboxParser.parseInbox(values),
        )
    }

    /**
     * 窗口末尾是否处于一个未结束的轮次。
     *
     * 只扫 `turn/start` 与 `turn/end` 两类事件的先后——不做配对计数，因为服务端保证
     * 轮次是严格嵌套的（`turn/start` 之后必有 `turn/end`，且不交叉）。
     */
    private fun tailTurnOpen(element: JsonElement?): Boolean {
        val array = element as? JsonArray ?: return false
        var open = false
        for (entry in array) {
            val wrapper = entry.asObjOrNull() ?: continue
            val type = wrapper.objectOrNull("event")?.stringOrNull("type") ?: continue
            when (type) {
                "turn/start" -> open = true
                "turn/end" -> open = false
            }
        }
        return open
    }

    private fun liveFrame(frame: JsonObject): ChatFrame.LiveFrame? {
        val attemptId = frame.stringOrNull("attemptId")?.takeIf { it.isNotEmpty() } ?: return null
        return when (frame.stringOrNull("type")) {
            "start" -> ChatFrame.LiveFrame.Start(
                attemptId = attemptId,
                turn = frame.longOrNull("turn") ?: 0L,
                step = frame.longOrNull("step") ?: 0L,
            )

            "chunk" -> {
                val time = frame.longOrNull("time") ?: 0L
                val chunk = frame.objectOrNull("chunk")
                ChatFrame.LiveFrame.Chunk(
                    attemptId = attemptId,
                    index = frame.longOrNull("index") ?: -1L,
                    time = time,
                    delta = chunk?.let { expander.deltaOf(time, it) },
                )
            }

            "end" -> {
                val outcome = frame.objectOrNull("outcome")
                val committed =
                    if (outcome?.stringOrNull("kind") == "committed") outcome.longOrNull("seq")
                    else null
                ChatFrame.LiveFrame.End(
                    attemptId = attemptId,
                    index = frame.longOrNull("index") ?: -1L,
                    committedSeq = committed,
                )
            }

            else -> null
        }
    }
}
