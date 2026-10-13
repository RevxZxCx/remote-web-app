package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
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
        PageResult(items = items, hasMore = hasMore)
    }

    /**
     * 发送一条文本消息。
     *
     * @param mode `'queue'` 排到当前轮之后；`'steer'` 插进当前轮（FR-3.5，M3 只用 queue）。
     * @param clientTimeZone IANA 时区，服务端用它解释消息里的相对时间。
     * @return 客户端生成的 `requestId`，便于日志关联与失败重试。
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: ChatArgs.PromptMode = ChatArgs.PromptMode.Queue,
        clientTimeZone: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        connection.typert.callRaw(
            DshEndpoints.SESSION_PROMPT,
            ChatArgs.prompt(requestId, sessionId, text, mode, clientTimeZone),
        )
        requestId
    }

    /** 中止当前生成（`session/cancel` → `{accepted:true}`）。 */
    suspend fun cancel(sessionId: String) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(DshEndpoints.SESSION_CANCEL, ChatArgs.cancel(sessionId))
        Unit
    }

    /** 一次翻页的结果。 */
    data class PageResult(
        val items: List<ChatItem>,
        val hasMore: Boolean,
    )
}
