package com.revxzxcx.remotewebapp.core.capsule

import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TodoList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面 → 胶囊的进程级中转站（R059）。
 *
 * ## 为什么需要它
 * 三路数据分散在不同层：会话列表在 `HomeViewModel`、正在看的会话在 `AppRoot`
 * 的状态机里、todo 与汇报在 `ChatViewModel`。它们**都拿不到** `AppContainer`
 * （`ChatViewModel` 的构造参数只有 `connection` / `sessionId` / `cache` / `ownerId`），
 * 而胶囊需要「无论界面在不在都要能发通知」——它挂在应用作用域上。
 *
 * 所以中间放一个信箱：界面往里投，[CapsuleCoordinator] 从里面读。
 * 与 [com.revxzxcx.remotewebapp.core.notify.NoticeIntents] 同一个理由、同一种做法。
 *
 * ## 为什么用 `StateFlow` 而不是普通字段
 * 冷启动时 `AppContainer` 先建好（`Application.onCreate`），界面后组合。
 * 投信与读信之间隔着若干挂起点：用普通字段就得轮询，用 `StateFlow` 则
 * 「谁先谁后都对」——后订阅的一方会立刻拿到当前值。
 */
object CapsuleBridge {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** 权威会话列表（与首页红点同一份）。 */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _viewing = MutableStateFlow<String?>(null)

    /** 用户正看着的会话；`null` = 在列表或设置页。 */
    val viewing: StateFlow<String?> = _viewing.asStateFlow()

    private val _live = MutableStateFlow<CapsuleCoordinator.LiveSnapshot?>(null)

    /** 正在看的会话的实时快照（todo + 汇报）。 */
    internal val live: StateFlow<CapsuleCoordinator.LiveSnapshot?> = _live.asStateFlow()

    /** 首页在 `session/list` 落地后调用。 */
    fun publishSessions(items: List<SessionSummary>) {
        _sessions.value = items
    }

    /** `AppRoot` 在进/出会话时调用。 */
    fun publishViewing(sessionId: String?) {
        if (_viewing.value == sessionId) return
        _viewing.value = sessionId
        // 离开会话时立刻清掉它的快照：那是「看着时才成立」的数据，
        // 留着会让后台卡片显示一个已经过期的进度。
        if (_live.value != null && _live.value?.sessionId != sessionId) {
            _live.value = null
        }
    }

    /** `ChatViewModel` 在 todo / 汇报变化时调用。 */
    internal fun publishLive(sessionId: String, todos: TodoList?, report: String?, action: String?, running: Boolean) {
        // 只发正在看的那个会话：后台会话拿不到 todo，硬发会让卡片显示别人的进度。
        if (_viewing.value != sessionId) return
        _live.value = CapsuleCoordinator.LiveSnapshot(
            sessionId = sessionId,
            todos = todos,
            report = report,
            action = action,
            running = running,
        )
    }

    /** 退出会话 / 断开连接时清空。 */
    internal fun clearLive() {
        _live.value = null
    }
}
