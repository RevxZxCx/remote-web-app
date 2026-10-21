package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `session/projections`（一次性读）+ `session/control`（实时投影流）+ `userQuestions/answer`
 * 三个 RPC 的数据源。
 *
 * 为什么需要三条路而不是一条：
 *  - **unary `session/projections`** 是唯一能给「桌面端当前选中会话以外的会话」读投影的路。
 *    实测（`F:\bot\_ctlprobe3.mjs`）`session/control` 的 baseline 在 90 个会话里只返回
 *    **1 个**（桌面端当前选中那个），所以冷启动不能只靠它。
 *  - **stream `session/control`** 提供实时 `projection` 帧（`{type:'projection', sessionId, key, value, seq}`），
 *    是「别人在桌面端答掉了这个问题」能立刻反应到手机上的唯一途径。
 *  - **unary `userQuestions/answer`** 是唯一的写入口。
 *
 * ⚠️ `session/control` 是 `mode:'stream'` 且**没有参数**，走 mux（[DshConnection.mux]）。
 */
class SessionProjectionRepository(
    private val connection: DshConnection,
) {

    /** 一次 unary 读：提问 + 权限两个投影。会话不存在时两个都是 null（服务端回 `null`）。 */
    suspend fun read(sessionId: String): ProjectionSnapshot = withContext(Dispatchers.IO) {
        val values = readValues(sessionId)
        ProjectionSnapshot(
            questions = userQuestionsOf(values),
            permission = permissionOf(values),
            values = values,
        )
    }

    /**
     * 一次 unary 读，返回**原始的 `values` 对象**。
     *
     * 给「对话详情」侧边栏用：那边要的是 `contextTimeline` / `costUsage` / `tokenUsage` /
     * `contextPressure` / `contextBreakdown` / `sessionStats` 六个键，且**一次读全**
     * （响应 ≈88 KB，其中 `contextHeaders` 70 KB + `turnOutline` 11 KB 占了绝大部分，
     * 但 descriptor 不支持只取指定键，所以要么全拿要么不拿）。
     *
     * 会话不存在时服务端整体回 `null`，这里返回 null 而不是空对象——调用方据此区分
     * 「会话没了」与「会话还在但没有这些投影」。
     */
    suspend fun readValues(sessionId: String): JsonObject? = withContext(Dispatchers.IO) {
        val raw = connection.typert.callRaw(DshEndpoints.SESSION_PROJECTIONS, ChatArgs.projections(sessionId))
        if (raw is JsonNull) return@withContext null
        (raw as? JsonObject)?.get("values") as? JsonObject
    }

    /** 只读提问投影（生成中周期重读用，比 [read] 少解析一个键）。 */
    suspend fun readQuestions(sessionId: String): UserQuestionsSnapshot? = read(sessionId).questions

    /** 只读权限投影。 */
    suspend fun readPermission(sessionId: String): PermissionSnapshot? = read(sessionId).permission

    /**
     * 订阅实时投影流。
     *
     * `session/control` 无参数，所以第二参传**空对象**（mux 会自己包一层 `{"args":{}}`，
     * 见 `RemoteStreamMux.open`）。
     *
     * 帧只有两种，其余形状一律忽略：
     *  - `{type:'baseline', value:{projections:{<sessionId>:{asOfSeq, values}}}}` —— 首帧，
     *    但只含桌面端当前选中会话（见 KDoc）；
     *  - `{type:'projection', sessionId, key, value, seq}` —— 增量。
     */
    fun control(): Flow<ProjectionFrame> =
        connection.mux
            .open(DshEndpoints.SESSION_CONTROL, JsonObject(emptyMap()))
            .mapNotNull { element -> parseFrame(element) }

    /**
     * 回答一组提问（`userQuestions/answer` → `{accepted:true}`）。
     *
     * @param answers 该 `callId` 下所有问题的答案；没答的题**不要**放进来（服务端按
     *   `answers` 数组覆盖，缺的题会被当成空答案）。
     */
    suspend fun answerQuestions(
        sessionId: String,
        callId: String,
        answers: List<AskAnswer>,
    ) = withContext(Dispatchers.IO) {
        connection.typert.callRaw(
            DshEndpoints.USER_QUESTIONS_ANSWER,
            ChatArgs.answerQuestions(sessionId, callId, answers),
        )
        Unit
    }

    private fun parseFrame(element: JsonElement): ProjectionFrame? {
        val obj = element as? JsonObject ?: return null
        return when (obj.strOrNullLocal("type")) {
            "projection" -> {
                val sessionId = obj.strOrNullLocal("sessionId") ?: return null
                val key = obj.strOrNullLocal("key") ?: return null
                ProjectionFrame.Projection(
                    sessionId = sessionId,
                    key = key,
                    value = obj["value"],
                    seq = obj.longLocal("seq") ?: -1L,
                )
            }

            "baseline" -> {
                val projections = (obj["value"] as? JsonObject)?.get("projections") as? JsonObject
                    ?: return null
                ProjectionFrame.Baseline(projections)
            }

            else -> null
        }
    }

    private fun JsonObject.strOrNullLocal(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.longLocal(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    /** 一次 unary 读到的两个投影（任一都可能为 null：会话不存在时服务端整体回 null）。 */
    data class ProjectionSnapshot(
        val questions: UserQuestionsSnapshot? = null,
        val permission: PermissionSnapshot? = null,
        /**
         * 同一次响应的原始 `values`。
         *
         * 带出来是为了让「问一次拿三个键」成为可能：`session/projections` 一次 88 KB，
         * 侧边栏再单独发一次请求就是白拉第二遍 88 KB。
         */
        val values: JsonObject? = null,
    ) {
        companion object {
            val EMPTY = ProjectionSnapshot()
        }
    }

    /** `session/control` 的两种有用帧。 */
    sealed interface ProjectionFrame {
        /** 首帧：`{<sessionId>:{asOfSeq, values}}`，只含桌面端当前选中会话。 */
        data class Baseline(val projections: JsonObject) : ProjectionFrame

        /** 增量帧：某个会话的某个投影变了。 */
        data class Projection(
            val sessionId: String,
            val key: String,
            val value: JsonElement?,
            val seq: Long,
        ) : ProjectionFrame
    }
}
