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
 * 数据分散在不同层：会话列表（含 todo 投影）在 `HomeViewModel`、正在看的会话在
 * `AppRoot` 的状态机里、流式汇报与动作在 `ChatViewModel`。它们**都拿不到** `AppContainer`
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

    private val _nudges = MutableStateFlow(0)

    /**
     * 「请重算一次」的计数器（R062-B019 第一轮审查 P1）。
     *
     * ## 为什么需要一路假数据
     * 划掉胶囊不会产生任何会话事件，所以只改 `CapsuleDismissal` 的开关
     * 传不到 [CapsuleCoordinator] 耳朵里 —— 覆盖账会一直停在
     * `coversDone = true`，那条被压掉的完成通知再也不会补发。
     *
     * 这里不直接持有协调器（信箱的定位就是「谁都不认识谁」），而是让协调器
     * 多订阅一路自己认识的信号：值一变就重算。
     *
     * 用计数器而不是布尔：连续划两次也得各算一次（布尔第二次就不变了）。
     */
    internal val nudges: StateFlow<Int> = _nudges.asStateFlow()

    /** 划掉胶囊后由 [CapsuleDismissReceiver] 调用。 */
    internal fun nudge() {
        _nudges.value = _nudges.value + 1
    }

    private val _todos = MutableStateFlow<Map<String, TodoList>>(emptyMap())

    /**
     * 逐会话的 todo（R062-B020）。键是会话 id。
     *
     * ## 与 [live] 的分工（这是 B020 的修法核心）
     * [live] 只有**正在看的那个**会话有，且绑在会话页生命周期上（一 `onCleared`
     * 就断）。而状态栏上的胶囊恰恰是「我不在 App 里」时才看的东西——所以它需要
     * 一份**不依赖会话页**的进度来源，这就是这一路。
     *
     * 数据来自 `session/list` 的 `projections.values.todos`（早就在响应体里，
     * 此前被解析器丢掉）。通道是现成的：`api-session/activity` 帧 →
     * `HomeViewModel.scheduleSessionsRefresh()`（1500ms 去抖）→ 全量刷新 →
     * 本信箱。**零新增请求、零轮询**（用户 m11152 的硬约束）。
     *
     * ## 为什么是整张 map 而不是「当前焦点会话的 todo」
     * 焦点由 `CapsuleFocus.elect` 在协调器内部选举，信箱不该复算一遍它——
     * 两处各算一次必然在某次边界上不一致（正是 B019 那类「两个模块各说各话」的
     * 形状）。整张 map 交过去，协调器自己按焦点取，口径只有一处。
     */
    internal val todos: StateFlow<Map<String, TodoList>> = _todos.asStateFlow()

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

    /** 首页在 `session/list` 解析出 todo 后调用（R062-B020）。 */
    fun publishTodos(items: Map<String, TodoList>) {
        // 相等就不发：`StateFlow` 自己会去重，但这里先挡一下能省掉协调器侧
        // 一次 300ms 去抖排队（列表每 1.5s 刷新一次，内容多数时候没变）。
        if (_todos.value == items) return
        _todos.value = items
    }

    /** `ChatViewModel` 在 todo / 汇报变化时调用。 */
    internal fun publishLive(
        sessionId: String,
        todos: TodoList?,
        report: String?,
        action: String?,
        thinking: Boolean,
    ) {
        // 只发正在看的那个会话：后台会话拿不到 todo，硬发会让卡片显示别人的进度。
        if (_viewing.value != sessionId) return
        _live.value = CapsuleCoordinator.LiveSnapshot(
            sessionId = sessionId,
            todos = todos,
            report = report,
            action = action,
            thinking = thinking,
        )
    }

    /** 退出会话 / 断开连接时清空。 */
    internal fun clearLive() {
        _live.value = null
    }

    /**
     * 换连接前把信箱清零（R059 第三轮审查 P2）。
     *
     * ## 为什么必须有
     * 这是**进程级** object，五个 `StateFlow` 会在断连后保留旧值。而
     * `StateFlow` 的语义是「新订阅者立刻拿到当前值」，所以新连接一 attach，
     * 第一条 `collect` 就会把**上一个账号/工作区**的会话列表推进
     * [CapsuleCoordinator.setSessions]，焦点选举据此选出一个已经不属于这台机器的
     * 会话，胶囊标题是它、点进去跳一个不存在的 sessionId。
     *
     * 只在 `watchCapsule(null)`（断连）时调用，**不要**在每次 attach 前清——
     * 那样同一账号重连会白白闪一下空状态。
     *
     * `_nudges` 刻意**不**清零：它不是状态而是「发生过几次划掉」的计数器，
     * 清零会让下一次订阅从 0 重新开始，与已收到的通知重复触发一次
     * [CapsuleCoordinator.recomputeNow]；而它本来就是假的业务数据，
     * 重订阅收到旧值只多跑一次重算，300ms 去抖会合并掉。
     */
    internal fun reset() {
        _sessions.value = emptyList()
        _viewing.value = null
        _live.value = null
        // todo 也属于上一个连接（R062-B020）：它和 `_sessions` 是同一次响应里
        // 的两半，留一半会让新连接的焦点会话拿到上一台机器的进度条。
        _todos.value = emptyMap()
    }
}
