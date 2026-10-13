package com.revxzxcx.remotewebapp.core.workspace

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

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
     * ⚠️ **不能**用 `.scan(WorkspaceSnapshot()) { … }`。`scan` 会把种子值**立刻发射一次**，
     * 于是订阅者在真帧到达前就先收到一个空快照——这有两个真实后果（真机 logcat 实测）：
     * 1. 冷启动约 1.9s 时 UI 已显示「已连接 / 这个桌面端还没有工作区。」，把「还没读到」
     *    谎报成「确实没有」；
     * 2. 每次断线重连都会重新订阅，种子空快照把列表清空一次，正是本文件早期 KDoc 想避免的
     *    「重连清空 UI」——当时的结论只防了 `onStart`，却被 `scan` 的初始发射抵消。
     *
     * 因此种子取 `null` 并在之后 [filterNotNull]，让「第一个快照」严格等于「服务端第一帧」。
     * 首帧空快照的展示职责交给 `HomeUiState.snapshot` 的默认值（只在首次组合时生效）。
     */
    fun snapshotFlow(): Flow<WorkspaceSnapshot> =
        connection.mux
            .open(ENDPOINT, JsonObject(emptyMap()))
            .map { element -> parser.parse(element) }
            // 解析不了的帧（未知形状）直接丢弃，否则下面 scan 的 lambda 参数会是可空类型。
            .filterNotNull()
            // 种子必须是 null（而不是空快照），见上方 KDoc。
            // `scan` 有两个类型参数 `<T, R>`，只写一个会报「2 type arguments expected」，
            // 所以这里用 `null as WorkspaceSnapshot?` 显式给出 R，避免手写两个类型实参。
            .scan(null as WorkspaceSnapshot?) { acc, frame ->
                WorkspaceReducer.reduce(acc ?: WorkspaceSnapshot(), frame)
            }
            .filterNotNull()
            .catch { throw it }

    companion object {
        /** `workspace/follow`（`dsh-api-workspace-controller`，service `workspaceController`）。 */
        const val ENDPOINT: String = "workspace/follow"
    }
}
