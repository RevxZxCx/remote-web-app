package com.revxzxcx.remotewebapp.core.session

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话管理动作的数据源：归档 / 取消归档 / 置顶 / 取消置顶。
 *
 * 为什么不需要「删完再拉列表」：这四个动作的结果都通过 `workspace/follow` 的
 * **专用增量帧**推回来——`{type:'archived', archivedSessionIds:[…]}` 与
 * `{type:'pinned', pinnedSessionIds:[…]}`（`typert.host.js` 的 follow result union 共六种
 * 帧：`baseline` / `upsert` / `remove` / `order` / `archived` / `pinned`）。
 * [com.revxzxcx.remotewebapp.core.workspace.WorkspaceReducer] 已经处理这两种帧，
 * 所以只要 `workspace/follow` 流还活着，UI 会自己更新——调用方**不必**在成功后刷新列表。
 *
 * ⚠️ 但也**不能**因此就把失败吞掉：一次 archive 失败时流不会推 `archived` 帧，
 * UI 会静静地什么都不变，用户以为点了没反应。所以每个方法都抛
 * [com.revxzxcx.remotewebapp.core.protocol.TypertException]，由 ViewModel 转成可见错误。
 */
class SessionActionRepository(
    private val connection: DshConnection,
) {

    /** 归档一个会话（默认同时叫停它的活动）。 */
    suspend fun archive(sessionId: String, stopActivity: Boolean = true) = withContext(Dispatchers.IO) {
        connection.typert.callUnit(
            DshEndpoints.WORKSPACE_ARCHIVE_SESSION,
            SessionActions.archive(sessionId, stopActivity),
        )
    }

    /** 取消归档。 */
    suspend fun unarchive(sessionId: String) = withContext(Dispatchers.IO) {
        connection.typert.callUnit(
            DshEndpoints.WORKSPACE_UNARCHIVE_SESSION,
            SessionActions.unarchive(sessionId),
        )
    }

    /** 置顶。 */
    suspend fun pin(sessionId: String) = withContext(Dispatchers.IO) {
        connection.typert.callUnit(
            DshEndpoints.WORKSPACE_PIN_SESSION,
            SessionActions.pin(sessionId),
        )
    }

    /** 取消置顶。 */
    suspend fun unpin(sessionId: String) = withContext(Dispatchers.IO) {
        connection.typert.callUnit(
            DshEndpoints.WORKSPACE_UNPIN_SESSION,
            SessionActions.unpin(sessionId),
        )
    }
}
