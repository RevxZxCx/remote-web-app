package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话目标的持久相位（`projections.values.goal.phase`）。
 *
 * 权威取值来自真实会话日志的统计（`app开发 main`，21 条 `goal/change`）：
 * 事件里出现过 `active` / `paused` / `complete` 三种；DSH 客户端还有第四种
 * `blocked`（`GoalBar.js` 的 `PHASE_LABELS` 里有它，用 `blockedReason.message` 当
 * tooltip），所以一并认下来。
 *
 * **`complete` 的会话不渲染横幅**——这是 DSH 的行为（`goal.phase === "complete"` 时
 * `GoalBar` 直接 `return null`），照抄。
 */
enum class GoalPhase(val raw: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    BLOCKED("blocked"),
    COMPLETE("complete"),

    /** 服务端给了个没见过的相位。按「有目标但状态未知」处理，不崩也不静默丢弃。 */
    UNKNOWN("");

    companion object {
        fun of(raw: String?): GoalPhase =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN

        /** 认不出的相位串（用于 UNKNOWN 时保留原文，便于排查）。 */
        const val UNRECOGNIZED = "__unrecognized__"
    }
}

/**
 * 会话目标（`projections.values.goal`）。
 *
 * 线上形状（实测，`app开发 main` 的 `goal/change 的 data.goal`）：
 * ```
 * {
 *   id: "goal-342f4004-…", revision: 3, objective: "完成所有开发",
 *   phase: "active", maxGoalRounds: 256, blockedReason?: { message }
 * }
 * ```
 *
 * ## 目标与任务**没有**联动关系（已查证 DSH 源码）
 *
 * 这是本模块最重要的一条语义，写在类型上而不是散在 UI 里：
 *
 *  - `phase` 是 **durable** 状态，`activation`（armed / disarmed）是**进程本地**状态，
 *    两者独立（`GoalBar.js`：`showResume = phase === "paused" || (phase === "active" && activation === "disarmed")`）。
 *  - `goals/pause` 只让 Host GoalService 排一条「模型可见的 goal 上下文消息」，
 *    **不会取消正在跑的轮次**——整个客户端 bundle 里 goal 与 `cancel`/`interrupt`/`abort`
 *    零关联。
 *  - `runMode` 只在埋点里用作字段，不是运行开关。
 *
 * 所以：**目标暂停时，当前这一轮对话继续输出完，任务列表照常推进**；
 * 任务胶囊的状态只跟 [TodoItem.status] 走，绝不跟随 goal。反之任务全完成
 * 也不代表目标完成。
 *
 * @param revision 服务端 CAS 版本。每次变更 +1，回写时必须带上当前值（陈旧就拒绝）。
 * @param blockedReason 仅 [GoalPhase.BLOCKED] 时有值。
 */
data class Goal(
    val id: String,
    val revision: Long,
    val objective: String,
    val phase: GoalPhase,
    /** 认不出的 phase 原文；仅 [GoalPhase.UNKNOWN] 时非空。 */
    val rawPhase: String? = null,
    val maxGoalRounds: Int? = null,
    /**
     * 已经开跑的轮数。**不在 goal 本体里**，而在它外面那层记录上
     * （`{goal:{…}, roundsStarted, createdAt, updatedAt}`），所以 [GoalTodoParser]
     * 是从外层读的——这里跟着 goal 一起存，因为「轮次是否用完」是它和
     * [maxGoalRounds] 共同决定的。
     */
    val roundsStarted: Int = 0,
    val blockedReason: String? = null,
) {
    /** 是否会渲染横幅。`complete` 不渲染（DSH 的行为）。 */
    val visible: Boolean get() = phase != GoalPhase.COMPLETE

    /**
     * 当前该给「暂停」还是「开始」按钮。
     *
     * 只有 ACTIVE 能暂停；PAUSED / BLOCKED 都能恢复。
     * 「active 但进程内 disarmed」那种情形本地拿不到 activation（它是进程本地状态，
     * 只经 `goals.get` 读取），所以按 active 处理——显示「暂停」。
     */
    val canPause: Boolean get() = phase == GoalPhase.ACTIVE

    /**
     * 轮次预算已用尽（用户 m04197 第 4 条）。
     *
     * DSH 的 `goals/resume` 有一道硬闸（`app.asar` 里
     * `GoalsService.resume`）：
     * ```
     * if (currentState.roundsStarted >= current.maxGoalRounds)
     *   throw new GoalError(`goal "…" exhausted ${maxGoalRounds} goal rounds;
     *                        increase maxGoalRounds before resuming`, "GOAL_INVALID_TRANSITION")
     * ```
     * 轮次跑到上限时目标会被自动记为 `blocked`（code = `round-limit`），此时横幅显示
     * 「目标受阻」，右侧给的是「开始目标」按钮——**点下去必然报错**。所以本地先判：
     * 预算用尽就把开始按钮置灰，点击只给一句提示，不浪费一次必然失败的 RPC。
     *
     * 用 `>=` 而不是 `==`：服务端是 `>=`，且 `maxGoalRounds` 被调小时可能已经超了。
     */
    val roundsExhausted: Boolean
        get() = maxGoalRounds != null && maxGoalRounds > 0 && roundsStarted >= maxGoalRounds

    /** 阶段文案。与 DSH 的 `phase.active` / `phase.paused` / `phase.blocked` 对齐。 */
    val label: String
        get() = when (phase) {
            GoalPhase.ACTIVE -> "目标进行中"
            GoalPhase.PAUSED -> "目标已暂停"
            GoalPhase.BLOCKED -> "目标受阻"
            GoalPhase.COMPLETE -> "目标已完成"
            GoalPhase.UNKNOWN -> "目标"
        }
}

/**
 * 一条任务（`projections.values.todos[]`）。
 *
 * 线上形状（实测，`todo_write` 工具的 `arguments`）：
 * ```
 * { content: "摸清数据结构", status: "in_progress" }
 * ```
 * 三个状态名与 DSH 的 `todo.status.*` 完全一致：`pending` / `in_progress` / `completed`。
 *
 * @param content 任务正文。**DSH 用它当列表 key**（`todos.map(... key: item.content)`），
 *   这里也照做——服务端保证同一份 todos 里 content 不重复。
 */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
)

/** 任务状态。取值与 DSH 的 `TodoItem.status` 联合类型逐一对应。 */
enum class TodoStatus(val raw: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    companion object {
        /**
         * 解析状态串。
         *
         * 认不出时返回 [PENDING] 而不是 null：`todos` 是用户可见的进度，
         * 丢掉一条比把状态显示成「待处理」更糟（少一条会让人以为任务被删了）。
         */
        fun of(raw: String?): TodoStatus =
            entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

/**
 * 一份任务列表 + 派生统计。
 *
 * 计数规则与 DSH 的 `progressLabel` 一致：`done` = completed 条数，
 * `active` = in_progress 条数，`pending` = 其余。胶囊上的 `n/m` 用 [done] / [total]。
 */
data class TodoList(val items: List<TodoItem> = emptyList()) {

    val total: Int get() = items.size
    val done: Int get() = items.count { it.status == TodoStatus.COMPLETED }
    val active: Int get() = items.count { it.status == TodoStatus.IN_PROGRESS }
    val pending: Int get() = total - done - active

    /** 胶囊是否该出现。空列表 = 完全没有任务，胶囊不占位置。 */
    val visible: Boolean get() = items.isNotEmpty()

    /** 是否还有进行中的任务（决定胶囊转不转圈）。 */
    val running: Boolean get() = active > 0

    /** 是否全部完成（决定胶囊是否换成绿勾并停止动画）。 */
    val allDone: Boolean get() = items.isNotEmpty() && done == total

    /** `n/m` 里的那两个数。 */
    val counter: String get() = "$done/$total"
}

/**
 * 解析 `projections.values.goal` 与 `projections.values.todos`。
 *
 * 与 [QueueInboxParser] / [SessionListParser] 同一套宽容策略：缺失、类型不符一律退化成
 * 默认值，绝不抛异常——一个投影字段加版本不该让整条 follow 流崩掉。
 */
object GoalTodoParser {

    /**
     * 解析 `projections.values.goal`。`null` / 不是对象 / 没有 id → 没有目标。
     *
     * ## 线上是**双层**包裹（实测，R031 设备验收抓到的缺陷）
     *
     * `values.goal` 并不是 goal 本体，而是一条「目标记录」：
     * ```
     * { goal: { id, revision, objective, phase, maxGoalRounds },
     *   roundsStarted: 0, createdAt: …, updatedAt: … }
     * ```
     * 首版直接把这个外层当本体读 `id` → 恒为 null → **横幅在真机上从来没出现过**。
     * 而单测喂的是扁平样本（`{"goal":{"id":…}}` 一路读下去恰好命中），于是全绿。
     * 这个坑正是「单测的形状必须抄真实报文」的教科书案例。
     *
     * 现在两层都认：外层有 `goal` 子对象就下钻，否则当扁平形态处理（保留旧样本的
     * 兼容性，也让单测里两种形状都能跑）。
     */
    fun parseGoal(values: JsonObject?): Goal? {
        val outer = values?.get("goal") as? JsonObject ?: return null
        val obj = (outer["goal"] as? JsonObject) ?: outer
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return null
        // `roundsStarted` 属于**外层那条目标记录**，不在 goal 本体里：
        // `{ goal:{id,…}, roundsStarted:12, createdAt, updatedAt }`。
        // 扁平形态（单测里的旧样本）就退回到 obj 自己身上找。
        val roundsStarted = (outer.long("roundsStarted") ?: obj.long("roundsStarted"))?.toInt() ?: 0
        val rawPhase = obj.string("phase")
        val phase = GoalPhase.of(rawPhase)
        return Goal(
            id = id,
            revision = obj.long("revision") ?: 0L,
            objective = obj.string("objective").orEmpty(),
            phase = phase,
            rawPhase = rawPhase.takeIf { phase == GoalPhase.UNKNOWN },
            maxGoalRounds = obj.long("maxGoalRounds")?.toInt(),
            roundsStarted = roundsStarted,
            blockedReason = (obj["blockedReason"] as? JsonObject)?.string("message"),
        )
    }

    /** 解析 `projections.values.todos`（数组）。任何异常形态 → 空列表。 */
    fun parseTodos(values: JsonObject?): TodoList {
        val array = values?.get("todos") as? JsonArray ?: return TodoList()
        return TodoList(
            items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val content = obj.string("content")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                TodoItem(content = content, status = TodoStatus.of(obj.string("status")))
            },
        )
    }

    /**
     * 解析 `goal/change` 事件。
     *
     * 线上形状（实测）：
     * ```
     * { kind:"goal/change", version:1, operation:"create"|"resume"|"pause"|"edit"|"complete"|"clear",
     *   goal:{id,revision,objective,phase,maxGoalRounds}, roundsStarted, createdAt, updatedAt }
     * ```
     *
     * `operation == "clear"` 时 `goal` 可能缺席或为 null → 返回 `Clear`。
     * 其余情况返回 `Set(goal)`；`goal` 解析失败也当 `Clear`（保守：宁可横幅消失，
     * 也不要显示一条内容为空的目标）。
     */
    fun parseGoalChange(data: JsonElement?): GoalChange? {
        val obj = data as? JsonObject ?: return null
        // 有些版本把它包在 data.goal 里，有些（未知）可能直接是 goal 本体。
        // 先按实测的 `data.goal` 读，读不到再退回把 data 当 goal。
        val operation = obj.string("operation")
        if (operation == "clear") return GoalChange.Clear
        val goalObj = obj["goal"] as? JsonObject ?: obj
        val id = goalObj.string("id")?.takeIf { it.isNotBlank() }
        if (id == null) return GoalChange.Clear
        val rawPhase = goalObj.string("phase")
        val phase = GoalPhase.of(rawPhase)
        return GoalChange.Set(
            Goal(
                id = id,
                revision = goalObj.long("revision") ?: 0L,
                objective = goalObj.string("objective").orEmpty(),
                phase = phase,
                rawPhase = rawPhase.takeIf { phase == GoalPhase.UNKNOWN },
                maxGoalRounds = goalObj.long("maxGoalRounds")?.toInt(),
                roundsStarted = (obj.long("roundsStarted")
                    ?: goalObj.long("roundsStarted"))?.toInt() ?: 0,
                blockedReason = (goalObj["blockedReason"] as? JsonObject)?.string("message"),
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}

/** `goal/change` 事件折叠后的结果。 */
sealed interface GoalChange {
    /** 目标被创建 / 恢复 / 暂停 / 编辑 / 完成——用这条新的权威快照覆盖。 */
    data class Set(val goal: Goal) : GoalChange

    /** 目标被清除（`operation == "clear"`，或事件里拿不到有效 goal）。 */
    data object Clear : GoalChange
}
