package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 新建会话的结果。
 *
 * [agentPreset] 是服务端**回显**的档位，不是我们传的那个。[AgentPreset] 的 KDoc 说过
 * 服务端将来可能加档；如果解析退回默认档，这里就会与请求不一致，UI 据此可以提示
 * 「实际用的是 X」而不是假装成功。
 */
data class CreatedSession(
    val sessionId: String,
    val agentPreset: AgentPreset,
)

/**
 * 新会话的参数构造。
 *
 * 依据：`session/create` 的 descriptor（`dsh-api-gateway` 的 session-controller）参数
 * wire 名是 **`request`**（与 `session/list` 的 `_request` 不同），schema 是
 * `{workspaceId?, cwd?, title?, agentPreset?, model?}` —— **全部可选**。
 *
 * 2026-11-02 真实探针（`测试记录.md` T086-1）逐条确认：
 * | 传法 | 结果 |
 * | --- | --- |
 * | `{"request":{}}` | ✅ 建成功，落到服务端默认工作区 |
 * | `{"request":{"agentPreset":"ptc"}}` | ✅ 回显 `{sessionId, agentPreset:"ptc"}` |
 * | `{"request":{"workspaceId":"<真实 id>"}}` | ✅ 建在那台工作区里 |
 * | `{"request":{"agentPreset":"bogus"}}` | ❌ `agent-preset/not-found`，details.available 给四档 |
 * | `{"request":{"workspaceId":"nope"}}` | ❌ `workspace/not-found` |
 * | 外层给 `agentPreset`（不在 request 里） | ❌ `unexpected "agentPreset"` |
 * | `{"request":{"cwd":""}}` | ❌ `failed to ensure project directory "": ENOENT mkdir ''` |
 *
 * **空值一律不写字段**，而不是写空串：`cwd: ""` 会让服务端拿空路径去 mkdir 然后报错，
 * 而**省略** `cwd` 表示「用工作区默认」。`workspaceId` 同理——省略才是「默认工作区」。
 */
object SessionCreateArgs {

    /**
     * @param workspaceId null 表示不指定，由服务端落到默认工作区。
     * @param agentPreset null 表示不指定，由服务端用它自己的默认档。
     */
    fun create(
        workspaceId: String? = null,
        agentPreset: AgentPreset? = null,
        title: String? = null,
    ): JsonObject = buildJsonObject {
        put(
            DshEndpoints.ARG_REQUEST,
            buildJsonObject {
                workspaceId?.takeIf { it.isNotBlank() }?.let {
                    put("workspaceId", JsonPrimitive(it))
                }
                agentPreset?.let { put("agentPreset", JsonPrimitive(it.wire)) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", JsonPrimitive(it)) }
            },
        )
    }
}

/**
 * `session/create` 成功响应的解析。
 *
 * 抽成独立对象而不是 `SessionCreateRepository` 的私有方法：`Repository` 要构造
 * [DshConnection]（需要 OkHttp 与凭据），而这段解析**一个字段都不碰连接**。分开之后
 * 每个分支都能在纯 JVM 里钉死——这也是 R055 里唯一会「悄悄错」的地方：缺 `sessionId`
 * 时若返回空串，上层会拿假 id 去订阅对话流，症状是「点了没反应」。
 */
internal object SessionCreateParse {

    /**
     * @throws TypertException `CODE_BAD_ENVELOPE` —— 响应形状不对或 `sessionId` 缺失/为空。
     */
    fun parse(raw: JsonElement): CreatedSession {
        val obj = raw as? JsonObject
            ?: throw TypertException(TypertException.CODE_BAD_ENVELOPE, "新建会话没有返回对象")
        val sessionId = stringField(obj, "sessionId")
            ?: throw TypertException(TypertException.CODE_BAD_ENVELOPE, "新建会话没有返回 sessionId")
        if (sessionId.isBlank()) {
            throw TypertException(TypertException.CODE_BAD_ENVELOPE, "新建会话返回了空 sessionId")
        }
        val preset = AgentPreset.fromWire(stringField(obj, "agentPreset"))
        return CreatedSession(sessionId = sessionId, agentPreset = preset)
    }

    /**
     * 只接受**字符串**字段。
     *
     * `JsonPrimitive.content` 对数字和布尔也会返回字面量（`123` 读成 `"123"`），
     * 于是 `{"sessionId":123}` 会被当成合法的 `"123"` 一路带下去。这一层判 `isString`
     * 就是为了拦住它。
     */
    private fun stringField(obj: JsonObject, key: String): String? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}

/**
 * 新建会话的数据源：一次 unary `session/create`。
 *
 * 为什么是 unary：`session/create` 的调用类型是 `direct`（一问一答），不是流。建出来的
 * 会话会通过 `workspace/follow` 的 `upsert` 帧推回流里，所以调用方**不必**手动刷新
 * 会话列表——但也不要因此吞掉失败：失败时流不会推任何帧，UI 会静静地什么都不变。
 */
class SessionCreateRepository(
    private val connection: DshConnection,
) {

    /**
     * 建一个新会话。
     *
     * @throws TypertException 服务端拒绝（`agent-preset/not-found` / `workspace/not-found` …）
     *   或传输层失败。**不做静默兜底**：新建会话是用户的主动作，失败必须让他看见。
     */
    suspend fun create(
        workspaceId: String? = null,
        agentPreset: AgentPreset? = null,
        title: String? = null,
    ): CreatedSession = withContext(Dispatchers.IO) {
        val raw = connection.typert.callRaw(
            DshEndpoints.SESSION_CREATE,
            SessionCreateArgs.create(workspaceId, agentPreset, title),
        )
        SessionCreateParse.parse(raw)
    }
}
