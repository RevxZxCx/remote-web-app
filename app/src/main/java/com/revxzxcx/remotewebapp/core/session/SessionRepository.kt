package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 会话列表数据源：一次 unary `session/list`。
 *
 * 为什么是 unary 而不是流：`session/list` 的调用类型是 `direct`（一问一答，
 * `typert.remote-client.js:1107-1132` `invocation: {kind:'direct'}`，**没有** `mode:'stream'`）。
 * 会话列表的实时状态变化走的是另一条流 `session/control`（投影增量），本类不碰它——
 * M2 先用「拉一次 + 手动刷新」，实时增量留到 M4 的重连/补齐一起做。
 *
 * ⚠️ 参数 wire 名是 **`_request`**，不是 `request`。这是 `session/list` 独有的（源码签名
 * `async list(_request: SessionListRequest, signal)`），传错服务端回
 * `gateway/arguments-invalid: missing "_request"; unexpected "request"`。
 * M2 探针已用「故意传错」的方式反证过（见 `测试记录.md` T009）。
 */
class SessionRepository(
    private val connection: DshConnection,
) {

    private val parser = SessionListParser()

    /** 拉取一次完整会话列表。 */
    suspend fun load(): SessionListResult = withContext(Dispatchers.IO) {
        parser.parse(connection.typert.callRaw(DshEndpoints.SESSION_LIST, listArgs()))
    }

    companion object {
        /**
         * `session/list` 的调用参数。
         *
         * 单独的 `SessionListRequest` 只有可选字段 `cursor`，所以这里传空对象。
         * 实测**不返回游标**、传 `cursor` 也仍返回全部条目（T009），因此不做分页。
         */
        fun listArgs(): JsonObject = JsonObject(
            mapOf(DshEndpoints.ARG_REQUEST_UNDERSCORE to JsonObject(emptyMap())),
        )
    }
}
