package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 输入区「+」面板的数据源：命令清单与文件引用（R043，用户 m26743 第 2 条）。
 *
 * 两条都是 unary direct RPC，参数名是 **`agentId`**（不是 `request`，也不是
 * `_request`），且值必须带 `session-` 前缀——`session/list` 那种 `_request` 的习惯
 * 在这里套不上，传错服务端会报 `args fields do not match the descriptor`。
 *
 * 为什么命令不需要 invoke：桌面端选中命令后只是把 `/name …` 塞进输入框，回车走的是
 * 普通 `session/prompt`，由服务端的命令解析器接管（`toggleSource("command", {trigger:"/"})`
 * 与手打斜杠完全同一条路径）。所以在 App 侧「发命令」等于「发一条以斜杠开头的消息」，
 * 不需要新 RPC，也不会因为服务端没开某条命令而在客户端分叉失败。
 */
class ComposerCatalogRepository(
    private val connection: DshConnection,
) {

    private val commands = CommandCatalogParser
    private val references = FileReferenceParser

    /** 拉一次命令清单；失败抛异常由调用方决定降级（面板只降级命令段）。 */
    suspend fun loadCommands(sessionId: String): List<SlashCommand> = withContext(Dispatchers.IO) {
        commands.parse(
            connection.typert.callRaw(
                DshEndpoints.COMMANDS_LIST,
                JsonObject(mapOf(ARG_AGENT_ID to JsonPrimitive(agentIdOf(sessionId)))),
            ),
        )
    }

    /** 按关键词查文件引用；空 query 也会返回一批（服务端自己决定给什么）。 */
    suspend fun searchReferences(sessionId: String, query: String): List<FileReference> =
        withContext(Dispatchers.IO) {
            references.parse(
                connection.typert.callRaw(
                    DshEndpoints.FILE_REFERENCES_LIST,
                    buildJsonObject {
                        put(ARG_AGENT_ID, JsonPrimitive(agentIdOf(sessionId)))
                        put(ARG_QUERY, JsonPrimitive(query))
                    },
                ),
            )
        }

    companion object {
        private const val ARG_AGENT_ID = "agentId"
        private const val ARG_QUERY = "query"

        /**
         * 会话 id → agent id。
         *
         * `commands/list` 要的是 agent 而不是 session：桌面端传的是 `session-<uuid>`。
         * 已经带前缀的（将来若服务端直接给 agent id）不重复加，否则会变成
         * `session-session-…` 查不到东西。
         */
        fun agentIdOf(sessionId: String): String =
            if (sessionId.startsWith("session-")) sessionId else "session-$sessionId"
    }
}
