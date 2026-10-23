package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * 聊天数据源：`session/follow`（流）+ `session/page`（翻页）+ `session/prompt` / `session/cancel`。
 *
 * 参数形状全部集中在 [ChatArgs]（纯函数，可单测）；本类只负责网络与协程。
 *
 * ⚠️ `session/follow` 是 `mode:'stream'`，走 mux（[DshConnection.mux]），**不是** unary。
 */
class ChatRepository(
    private val connection: DshConnection,
    private val frames: ChatFrameParser = ChatFrameParser(),
) {

    /**
     * 订阅一个会话的事件流。
     *
     * `assistantStream` 恒为 true（见 [ChatArgs.follow]），所以逐字上屏的
     * `assistant-stream` 帧一定会到达。
     *
     * @param sessionId 顶层会话 id。子代理会话需要 `{kind:'subagent', …}` 地址，M3 不支持
     *   （服务端会回 `session/agent-busy`）。
     */
    fun follow(sessionId: String, maxMessages: Int = ChatArgs.DEFAULT_MAX_MESSAGES): Flow<ChatFrame> =
        connection.mux
            .open(DshEndpoints.SESSION_FOLLOW, ChatArgs.follow(sessionId, maxMessages))
            .mapNotNull { frames.parse(it) }

    /**
     * 加载更早的历史。
     *
     * @param throughSeq 当前日志尾部 seq（快照的 `cursor`）。
     * @param beforeSeq 已知的最早条目 seq。
     */
    suspend fun loadPage(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
        maxMessages: Int = ChatArgs.DEFAULT_MAX_MESSAGES,
    ): PageResult = withContext(Dispatchers.IO) {
        val raw = connection.typert.callRaw(
            DshEndpoints.SESSION_PAGE,
            ChatArgs.page(sessionId, throughSeq, beforeSeq, maxMessages),
        )
        val (items, hasMore) = frames.parsePage(raw)
        PageResult(items = items, hasMore = hasMore, envelopes = frames.recordEnvelopes(raw))
    }

    /**
     * 挑出可缓存的信封（供实时事件路径用）。
     *
     * 与 [loadPage] 里的 [ChatFrameParser.recordEnvelopes] 是同一条判据，只是输入已经是
     * 取出的信封而不是 `[{type:'event',event}]` 数组。快照路径与实时路径必须共用，
     * 否则缓存预算只在一条路径上生效（R012 P2-B）。
     */
    fun cacheable(envelopes: List<JsonObject>): List<JsonObject> = frames.cacheable(envelopes)

    /**
     * 发送一条文本消息，可附带图片/文件。
     *
     * @param mode `'queue'` 排到当前轮之后；`'steer'` 插进当前轮（FR-3.5，M3 只用 queue）。
     * @param attachments 追加在文字后面的内容块；图片是内联 base64，文件必须先经
     *   [FileUploadRepository.upload] 换成 `receiptId`（见 [PromptAttachment] 的 KDoc）。
     * @param clientTimeZone IANA 时区，服务端用它解释消息里的相对时间。
     * @param requestId 客户端生成的消息 id。默认自己生成一个；[ui.chat.ChatViewModel.send]
     *   传自己生成的版本，因为排队回声要用同一个 id 与 `agent/inbox/spliced` 的
     *   `inserted[].source.rpcId` 对上（见 [QueuedMessage.rpcId] 的 KDoc）。
     * @return 本次用的 `requestId`，便于日志关联与失败重试。
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: ChatArgs.PromptMode = ChatArgs.PromptMode.Queue,
        attachments: List<PromptAttachment> = emptyList(),
        clientTimeZone: String? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): String = withContext(Dispatchers.IO) {
        connection.typert.callRaw(
            DshEndpoints.SESSION_PROMPT,
            ChatArgs.prompt(requestId, sessionId, text, mode, attachments, clientTimeZone),
        )
        requestId
    }

    /**
     * 执行一条斜杠命令（`commands/execute`，R045）。
     *
     * @param line 完整的一行，含斜杠与前导参数（`/export`、`/goal 把测试补全`）。
     * @return 命令的结果；**`null` 表示服务端不认这条命令**——descriptor 的 result 是
     *   `union([undefined, {commandId, result}])`，命中不了任何已注册命令时服务端回
     *   `{ok:true}` 而**没有** `value`，`callRaw` 于是给出 `JsonNull`。
     *   这不是错误（不抛异常），调用方要自己决定怎么提示。
     */
    suspend fun executeCommand(
        sessionId: String,
        line: String,
        submittedAttachments: JsonArray = JsonArray(emptyList()),
    ): CommandExecution? = withContext(Dispatchers.IO) {
        val raw = connection.typert.callRaw(
            DshEndpoints.COMMANDS_EXECUTE,
            buildJsonObject {
                put("agentId", JsonPrimitive(ComposerCatalogRepository.agentIdOf(sessionId)))
                put("line", JsonPrimitive(line))
                put("submittedAttachments", submittedAttachments)
            },
        )
        CommandExecutionParser.parse(raw)
    }

    /** 中止当前生成（`session/cancel` → `{accepted:true}`）。 */
    suspend fun cancel(sessionId: String) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(DshEndpoints.SESSION_CANCEL, ChatArgs.cancel(sessionId))
        Unit
    }

    /**
     * 拉取可选模型目录（`session/modelCatalog`）。
     *
     * **这个方法没有参数**——源码签名是 `modelCatalog(): Promise<ModelCatalog>`，所以
     * `payload.args` 是空对象。传任何键都会踩 `assertExactArguments`。
     */
    suspend fun modelCatalog(): ModelCatalog = withContext(Dispatchers.IO) {
        ModelCatalogParser.parseCatalog(
            connection.typert.callRaw(DshEndpoints.SESSION_MODEL_CATALOG, ChatArgs.emptyArgs),
        )
    }

    /**
     * 切换这个会话的模型（`session/selectModel`）。
     *
     * @param reasoningEffort 推理档；该模型没有 `reasoning.efforts` 时必须传 null，
     *   否则服务端会因为多一个键回 `gateway/arguments-invalid`（`reasoningEffort` 是可选字段，
     *   传了就一定要是合法档位）。
     * @return 服务端确认的新选择，UI 用它覆盖本地乐观状态。
     */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): ModelRef = withContext(Dispatchers.IO) {
        val raw = connection.typert.callRaw(
            DshEndpoints.SESSION_SELECT_MODEL,
            ChatArgs.selectModel(sessionId, provider, model, reasoningEffort),
        )
        ModelCatalogParser.parseSelection(raw, which = "selected") ?: ModelRef(provider, model, reasoningEffort)
    }

    /**
     * 对一条**排队中**的消息做操作（`session/updateQueue` → `{accepted:true}`）。
     *
     * 三种操作的语义与失败码见 [ChatArgs.updateQueue]。`steer` 在「当前轮已经不接受插队」时
     * 服务端抛 `session/steer-unavailable`，`itemId` 已不在队列时抛
     * `session/queue-item-not-found`——两者都由调用方（ViewModel）映射成中文文案。
     */
    suspend fun updateQueue(
        sessionId: String,
        itemId: String,
        action: ChatArgs.QueueAction,
    ) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(
            DshEndpoints.SESSION_UPDATE_QUEUE,
            ChatArgs.updateQueue(sessionId, itemId, action),
        )
        Unit
    }

    /**
     * 目标变更（R031）：编辑 objective / 暂停 / 继续 / 清除。
     *
     * 四个方法都走 unary RPC，返回后**不做本地状态更新**——权威结果经 `goal/change`
     * 事件回灌（`ChatReducer.onEvent`），这样桌面端与手机端看到的永远是同一条事件。
     * 本地不乐观更新是刻意的：目标带 CAS revision，本地先改会让「服务端拒绝」变成
     * 一个静默的显示错误。
     */
    suspend fun pauseGoal(sessionId: String, goalId: String, revision: Long) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(DshEndpoints.GOALS_PAUSE, ChatArgs.goalVerb(sessionId, goalId, revision))
        Unit
    }

    suspend fun resumeGoal(sessionId: String, goalId: String, revision: Long) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(DshEndpoints.GOALS_RESUME, ChatArgs.goalVerb(sessionId, goalId, revision))
        Unit
    }

    suspend fun clearGoal(sessionId: String, goalId: String, revision: Long) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(DshEndpoints.GOALS_CLEAR, ChatArgs.goalVerb(sessionId, goalId, revision))
        Unit
    }

    suspend fun editGoal(
        sessionId: String,
        goalId: String,
        revision: Long,
        objective: String,
    ) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(
            DshEndpoints.GOALS_EDIT,
            ChatArgs.editGoal(sessionId, goalId, revision, objective),
        )
        Unit
    }

    /** 一次翻页的结果。 */
    data class PageResult(
        val items: List<ChatItem>,
        val hasMore: Boolean,
        /**
         * 本次翻页取回的**原始事件信封**，供离线缓存落盘（需求 FR-4.3）。
         *
         * 与 [items] 是同一批事件的两个视图：前者给 reducer，后者给缓存。分开返回而不是
         * 让缓存层从 [items] 反推，是为了让「缓存里存的永远是服务端原文」这条规则
         * 在类型上就成立。
         */
        val envelopes: List<JsonObject> = emptyList(),
    )
}
