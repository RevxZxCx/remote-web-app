package com.revxzxcx.remotewebapp.core.workspace

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.serialization.json.JsonObject

/**
 * 工作区数据源：把 `workspace/follow` 流折叠成不断更新的 [WorkspaceSnapshot]。
 *
 * `workspace/follow` 是 `mode:'stream'`、**无参数**（`typert.remote-client.js`），帧通过
 * [RemoteStreamMux.open] 走 `/remote/api/remote.mux`。首帧是 `baseline`，所以订阅者拿到的
 * 第一个 [WorkspaceSnapshot] 就是完整列表——不需要额外先调一次 unary。
 *
 * 失败策略：流异常不吞掉（`catch` 只做标记后重抛由上层决定），因为「连不上」和「连上了但
 * 是空列表」在 UI 上必须区分（需求文档 §2.3 的成功判据）。
 */
class WorkspaceRepository(
    private val connection: DshConnection,
) {

    private val parser = WorkspaceFrameParser()

    /**
     * 工作区快照流。
     *
     * 这里**故意不放 `onStart { emit(WorkspaceSnapshot()) }`**：`retryWhen` 在上层，重试会重新
     * 订阅本流；若首帧在这里发一个空快照，每次断线重连都会先把列表清空一次并短暂显示
     * 「这个桌面端还没有工作区。」，违反 `docs/dev/开发流程.md` 的「重连不清空 UI」。
     * 首帧空快照的职责交给 `HomeUiState.snapshot` 的默认值（它本来就只在首次组合时生效）。
     *
     * @param onRawFrame 可选的调试钩子：每一帧原始 JSON 都会回调一次（M4 调试页用）。
     */
    fun snapshotFlow(
        onRawFrame: ((String) -> Unit)? = null,
    ): Flow<WorkspaceSnapshot> =
        connection.mux
            .open(ENDPOINT, JsonObject(emptyMap()))
            .map { element ->
                onRawFrame?.invoke(element.toString())
                parser.parse(element)
            }
            .scan(WorkspaceSnapshot()) { acc, frame -> WorkspaceReducer.reduce(acc, frame) }
            .catch { throw it }

    companion object {
        /** `workspace/follow`（`dsh-api-workspace-controller`，service `workspaceController`）。 */
        const val ENDPOINT: String = "workspace/follow"
    }
}
