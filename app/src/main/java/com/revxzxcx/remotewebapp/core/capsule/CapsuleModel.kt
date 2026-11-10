package com.revxzxcx.remotewebapp.core.capsule

import com.revxzxcx.remotewebapp.R
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import com.revxzxcx.remotewebapp.core.session.statusOf
import com.revxzxcx.remotewebapp.core.session.unreadRank

/**
 * 胶囊展示的那一个会话的完整快照（R059）。
 *
 * ## 为什么是一个扁平快照而不是直接引用 `ChatUiState`
 * 胶囊跑在 `appScope` 上，它的数据来自两处**不同粒度**的源：
 * - 正在看的会话：`ChatUiState`（有 `todos`、有流式正文）
 * - 后台会话：只有 `SessionSummary`（没有 `todos`，见调研文档 3.7-G）
 *
 * 若直接吃 `ChatUiState`，后台会话就永远构不出胶囊。所以先归一化成这个快照，
 * 「谁有什么」由上游 [CapsuleContent] 负责填，本类只描述「要显示成什么样」。
 *
 * @param sessionId 会话 id（点卡片时用来跳转，也是焦点选举的去重键）。
 * @param title 会话标题，可空（`session/list` 还没刷到时会没有）。
 */
internal data class CapsuleState(
    val sessionId: String,
    val title: String?,
    /** 顶部汇报行（`setContentTitle` 第 1 行）。 */
    val report: String,
    /** 当前动作行（`setContentTitle` 第 2 行，前面由构建方加 `▸ `）。 */
    val action: String?,
    /** 步骤说明（`setSubText`）。 */
    val step: String?,
    /** 分段进度：总段数与已完成段数。`null` 表示不画进度条。 */
    val progress: CapsuleProgress?,
    /** 起算时刻（`setWhen`），驱动卡片上的 SystemUI 计时器。 */
    val startedAt: Long,
    /** 待处理事项数量（审批 + 提问），决定卡片上挂哪组按钮。 */
    val pending: PendingKind,
    /** 除自己以外还有几个会话在跑。0 表示只有一个。 */
    val otherRunning: Int,
    /** 会话此刻处在哪个阶段。只影响胶囊条那行短字，不影响卡片正文。 */
    val phase: CapsulePhase = CapsulePhase.Running,
) {

    /**
     * 是否该显示胶囊。空闲且没有任何待处理时**不显示**（调研文档 3.4）。
     *
     * ## 为什么 `phase` 也算进来（R059 第三轮审查 P1）
     * 只算 pending / progress / otherRunning 时，**最常见的那个场景反而不显示**：
     * 一个会话在跑、用户没打开它、因此拿不到 todo → `progress == null`；
     * 同时没有别的会话 → `otherRunning == 0`；也没有待处理。
     * 三者全空，`visible` 为 false，`recompute()` 直接 `cancel()`，状态栏什么都没有。
     *
     * 而胶囊存在的意义正是「**我不在 App 里**时也能看见它在干活」。只要焦点会话
     * 还在跑（`Running`），或者刚出错还没被看过（`Failed`），就该占着展示位——
     * 哪怕只能说一句「执行中」、连进度都不知道。
     *
     * `Done` 不算：跑完且没有未读的会话不该常驻，那是书签不是提醒。
     * 但「跑完了、我还没看过」（`completionUnread`）要显示，所以 `Done` 分支
     * 在 [CapsuleCoordinator] 里会连同 `completionUnread` 一起判——这里只排除
     * 纯空闲。
     */
    val visible: Boolean
        get() = pending != PendingKind.None ||
            phase != CapsulePhase.Idle ||
            progress != null ||
            otherRunning > 0

    /**
     * 状态栏胶囊条上那一行字（`setShortCriticalText`）。
     *
     * ## 为什么必须单独给一个短字段
     * 真机实测：胶囊条上显示的**不是** `contentTitle`，而是 `shortCriticalText`。
     * 不设它，状态栏上只有一个孤零零的图标，用户看不出在干什么（调研文档 3.7-B）。
     *
     * ## 为什么控制在 7 个字以内
     * 官方文档：状态栏标签宽度上限 96dp，**7 个字符以内不截断**。超出的部分会被
     * 系统自己截，且截断位置不可控（可能把「待批准」截成「待批」）。
     * 宁可自己写得短，也不要让系统截。
     */
    val capsuleText: String
        get() = when {
            pending == PendingKind.Approval -> "待批准"
            pending == PendingKind.Question -> "待回答"
            phase == CapsulePhase.Failed -> "出错"
            phase == CapsulePhase.Done -> "已完成"
            // 多会话时把个数带上：+1 是因为 otherRunning 不含自己。
            // 上限 9：状态栏标签 96dp 只保证 7 个字符不截断，而「执行中 12 个」
            // 是 8 个 code point，会命中系统自己截、且截断位置不可控。
            // 超过 9 个并发会话就写「执行中 9+ 个」（4+1+3 = 7 字），保持定长。
            otherRunning >= 8 -> "执行中 9+ 个"
            otherRunning > 0 -> "执行中 ${otherRunning + 1} 个"
            progress != null -> "第 ${progress.safeDone} 步"
            // Idle 走不到：`visible` 会把 Idle 挡掉。留着只是为了让 `when` 穷尽，
            // 真出现了也宁可显示「执行中」而不是空串。
            else -> "执行中"
        }

}

/**
 * 分段进度。
 *
 * ## 每段长度必须是 1（这是 R059 最贵的一条教训）
 * `Notification.ProgressStyle.Segment(length)` 的参数**不是「这一段占多长」的比例**，
 * 而是「这一段代表多少个进度单位」。真机实测的公式是：
 * - 圆点位置 = `done / Σ(所有 Segment 的 length)`
 * - 绿色填充 = `已完成段数 / 总段数`
 *
 * 按「段长 = 总步数」写时，5 段的总长度是 25，圆点按 `2/25 = 8%` 画，
 * 而绿条按 `2/5 = 40%` 画 —— **分母不同，必然错位**。用户就是这么发现的（m01450）。
 *
 * 所以这里把「段长恒为 1」编码进类型：不提供自定义长度的入口，
 * 调用方只能给 [total]，构建通知时一律 `Segment(1)`。
 *
 * @param total 总段数（= todo 条目数，见 [fromTodos]）。
 * @param done 已完成段数，会被夹到 `0..total`。
 */
internal data class CapsuleProgress(val total: Int, val done: Int) {

    init {
        require(total >= 0) { "total must not be negative: $total" }
    }

    /** 夹紧后的已完成段数。上游给负数或超过总数时不会画出界。 */
    val safeDone: Int get() = done.coerceIn(0, total)

    /** 进度条是否该画。0 段不画（`ProgressStyle` 收到空列表没有意义）。 */
    val drawable: Boolean get() = total > 0

    companion object {

        /**
         * 从 todo 列表构造分段进度。
         *
         * **一条 todo 一段**（用户 m01212 拍板）。空列表返回 `null` 而不是 0 段，
         * 让调用方用 `?.` 直接把「没有任务」和「有任务但还没开始」区分开。
         */
        fun fromTodos(todos: TodoList): CapsuleProgress? =
            if (todos.total <= 0) null else CapsuleProgress(total = todos.total, done = todos.done)
    }
}

/**
 * 胶囊条上的状态图标（R059-B016）。
 *
 * ## 为什么必须按状态分开
 * 之前所有状态共用 `ic_capsule`（三条横线）。用户在原型里看到的勾、手、问号、
 * 叹号一个都没出现——**而且这比「没有图标」更糟**：图标的位置本身在向用户承诺
 * 「你能从图标看出状态」，实际看不出来就等于每个状态都在骗人。
 *
 * ## 取值顺序与 [CapsuleState.capsuleText] 完全一致
 * 胶囊条上图标与文字并排显示，两者必须说同一件事。任何一处改了顺序，
 * 就会出现「手 + 执行中」这种自相矛盾的组合。所以本函数与 `capsuleText`
 * 的 `when` 分支顺序**逐条对应**，改一处必须改另一处。
 *
 * 都是纯白剪影（会被系统染色），形状取自原型 `ICO` 表。
 */
internal fun CapsuleState.iconRes(): Int = when {
    pending == PendingKind.Approval -> R.drawable.ic_capsule_wait
    pending == PendingKind.Question -> R.drawable.ic_capsule_question
    phase == CapsulePhase.Failed -> R.drawable.ic_capsule_failed
    phase == CapsulePhase.Done -> R.drawable.ic_capsule_done
    else -> R.drawable.ic_capsule_running
}

/** 会话此刻所处阶段，决定胶囊条短字与卡片配色。 */
internal enum class CapsulePhase {
    /**
     * 不在跑、也没有未读。焦点选举正常情况下不会选出这种会话，
     * 纯防御——但必须有：否则「已经跑完且已读」会被当成 Running。
     */
    Idle,

    /** 正在跑。 */
    Running,

    /** 这一轮跑完了，但用户还没看。 */
    Done,

    /** 这一轮跑挂了。 */
    Failed,
}

/** 卡片上要挂哪一组动作按钮。 */
internal enum class PendingKind {
    /** 没有待处理。卡片上只有「⋯」或什么都不挂。 */
    None,

    /** 有待批准的审批请求。挂勾/叉两个图标按钮。 */
    Approval,

    /** 有待回答的提问。挂前两个选项的文字按钮。 */
    Question,
}

/**
 * 焦点会话选举（R059）。
 *
 * ## 为什么需要它
 * 真机实测（调研文档 3.7-A）：**多条 promoted 通知不等于多个胶囊**。
 * 系统只给一个展示位，多条时轮播。所以必须自己决定「此刻展示谁」，
 * 否则用户看到的是几个会话随机闪烁。
 *
 * ## 四层刚性顺序（不是可调参数）
 * 1. **有待处理**（审批 / 提问）——层内 FIFO 防饥饿。
 *    不能用 `running` 判断：挂起等回答时 `running` 仍是 true。
 * 2. **用户正在看**——胶囊是「我不在 App 里」的替代品，我在看时不必抢镜。
 * 3. **粘滞保持**——上一轮焦点仍在跑就不换。这是**硬约束**：
 *    ColorOS 轻打扰会把频繁变动的胶囊缩成气泡，且气泡不会自动展开。
 * 4. **重新选举**——`unreadRank` 降序 → `updatedAt` 降序 → `sessionId` 字典序。
 *
 * 第三段（`sessionId`）不能省：同样输入两次必须算出同一个焦点，
 * 否则画面会闪，且单测无法断言。
 *
 * ## 为什么复用 `SessionActivity` 而不是自己再写一套排序
 * 会话列表上的红点用的是 `core/session/SessionActivity.kt` 里的 `unreadRank` / `statusOf`。
 * 若胶囊自己定一套优先级，就会出现「列表是红点、胶囊是绿点」的自相矛盾。
 */
internal object CapsuleFocus {

    /**
     * 选出本轮焦点。
     *
     * @param candidates 全部会话摘要（通常就是 `session/list` 那份）。
     * @param pendingSessionIds 有待处理事项的会话 id，**按到达先后排列**（FIFO）。
     * @param viewingSessionId 用户正看着的会话（`CompletionLedger.currentSessionId`）。
     * @param previousSessionId 上一轮的焦点，用于粘滞。
     * @return 焦点会话；没有可展示的就返回 `null`。
     */
    fun elect(
        candidates: List<SessionSummary>,
        pendingSessionIds: List<String> = emptyList(),
        viewingSessionId: String? = null,
        previousSessionId: String? = null,
    ): SessionSummary? {
        if (candidates.isEmpty()) return null
        val byId = candidates.associateBy { it.sessionId }

        // ── 第 1 层：有待处理的最先。FIFO：列表顺序即到达顺序，取第一个还在的。
        //
        // 不做迟滞：用户被一条请求卡住时，胶囊必须立刻反映出来。
        for (id in pendingSessionIds) {
            byId[id]?.let { return it }
        }

        // ── 第 2 层：用户正看着的那个。
        //
        // 仍然要求它「值得展示」（在跑或有未读）——否则用户盯着一个已经跑完、
        // 也看过的会话时，胶囊会一直挂着一条没信息量的东西。
        viewingSessionId?.let { id ->
            val viewed = byId[id]
            if (viewed != null && isWorthShowing(viewed)) return viewed
        }

        // ── 第 3 层：粘滞。上一轮焦点仍在跑就不换。
        previousSessionId?.let { id ->
            val previous = byId[id]
            if (previous != null && previous.running) return previous
        }

        // ── 第 4 层：重新选举。三段排序，最后一段保证确定性。
        return candidates
            .filter { isWorthShowing(it) }
            .maxWithOrNull(COMPARATOR)
    }

    /**
     * 三条排序键，从强到弱。
     *
     * 用 `compareBy` 而不是手写比较：三段都参与，少写一段编译器不会提醒，
     * 表现为「偶尔闪一下」这种最难查的 bug。
     */
    private val COMPARATOR: Comparator<SessionSummary> = compareBy<SessionSummary> {
        unreadRank(statusOf(it))
    }.thenBy {
        it.updatedAt
    }.thenBy {
        // 字典序**降序**：让 id 大的在前，只是个稳定的 tie-break，
        // 关键在它必须确定 —— 同样是「选一个」，两次输入不能给出不同答案。
        it.sessionId
    }

    /**
     * 这个会话值不值得占用胶囊展示位。
     *
     * 判据复用会话列表的红点口径：在跑、有待处理、有未读的完成/失败。
     * **已读且空闲的会话不该占位**——胶囊是提醒，不是书签。
     */
    fun isWorthShowing(summary: SessionSummary): Boolean =
        summary.running ||
            summary.awaitingAttention ||
            summary.completionUnread ||
            summary.failureUnread

    /**
     * 统计「除焦点以外还有几个会话在跑」。
     *
     * 用于副行「另 N 个在执行」。**只算在跑的**，不算有未读的：
     * 用户关心的是「还有活在干」，不是「还有几条消息没看」。
     */
    fun countOthersRunning(candidates: List<SessionSummary>, focusId: String?): Int =
        candidates.count { it.sessionId != focusId && it.running }
}

/**
 * 把原始数据归一化成 [CapsuleState]（R059）。
 *
 * 这一层只做「拼字符串 + 决定显不显示」，不含任何 Android 依赖，
 * 因此焦点选举、截断规则、降级判定都能在 JVM 单测里穷举。
 */
internal object CapsuleContent {

    /**
     * 汇报行的最大字数——**两行总量**，不是单行容量。
     *
     * ## 为什么是 22
     * `setContentTitle` 是 2 行硬上限。真机实测（1080 宽 / ColorOS 17 / 默认字号）：
     * **一行 13 个汉字**，第 14 个就折行。两行约 26 个，留余量取 22。
     *
     * ⚠ 这个值**只适用于「只有汇报、没有操作行」**的情形。
     * 一旦同时还要显示操作行，汇报必须压到一行以内，那时由
     * [TITLE_LINE_MAX_CHARS]（12）拦一道；否则汇报自己占满两行、`▸` 开头那行
     * **整行消失**——「操作会消失」的 bug 会以另一个原因复发（B018，2026-11-10 真机）。
     *
     * 注意不同 ROM 的字号设置会改变实际容量，超出的部分由 [clip] 用省略号收尾，
     * 而不是让系统自己截。
     */
    const val REPORT_MAX_CHARS: Int = 22

    /**
     * **只有汇报、没有操作行**时，title 一行实际放得下的字数。
     *
     * 上面 [REPORT_MAX_CHARS] 的 22 是「两行总量」的保守值（与之一致）；一旦同时还要显示操作行，
     * 汇报必须压到一行以内。真机实测（1080 宽 / ColorOS 17 / 默认字号）：一行 13 个汉字，
     * 第 14 个就折行。旧实现让汇报用满 22 字 → 它自己占掉两行 → `▸` 开头那行
     * **整行消失**，于是「操作会消失」的 bug 以另一个原因复发（2026-11-10 13:38 真机复现：
     * dumpsys 里 title 确有两行，卡片上只剩汇报）。
     */
    const val TITLE_LINE_MAX_CHARS: Int = 12

    /** 动作行（title 第 2 行）的最大字数。前缀 `▸ ` 占 2 个字符宽度，已从这里扣。 */
    const val ACTION_MAX_CHARS: Int = 20

    /** 步骤行（subText）的最大字数。subText 只有 1 行且字号更小，能多放一点。 */
    const val STEP_MAX_CHARS: Int = 24

    /**
     * 超出长度就截断并加省略号。
     *
     * ## 为什么不交给系统截
     * 真机实测系统截断是在**渲染层**做的，且对 title 的第 1 行与第 2 行
     * 处理不一致（第 2 行被挤掉而不是截断）。自己截能保证两行都完整可见。
     *
     * 用 `codePointCount` 而不是 `length`：一个 emoji 或生僻字在 UTF-16 里是
     * 两个 char，按 `length` 截会把它劈成半个字符，渲染成方块。
     */
    fun clip(text: String, max: Int): String {
        if (max <= 0) return ""
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints <= max) return trimmed
        // 留 1 个位置给省略号，保证总宽不超。
        val end = trimmed.offsetByCodePoints(0, max - 1)
        return trimmed.substring(0, end).trimEnd() + "…"
    }

    /**
     * 构造「执行中」的胶囊内容。
     *
     * @param todos 该会话的任务列表；后台会话拿不到时传 `null`（见调研文档 3.7-G）。
     * @param report 最近一次汇报（agent 每轮输出的进度说明）。
     * @param action 当前正在做的事。
     */
    fun running(
        sessionId: String,
        title: String?,
        todos: TodoList?,
        report: String?,
        action: String?,
        otherRunning: Int,
        startedAt: Long,
        /**
         * 相位。**必须由调用方从 [com.revxzxcx.remotewebapp.core.session.SessionSummary]
         * 的 `running` / `failureUnread` / `completionUnread` 算出来传进来**
         * （R059 第三轮审查 P1）。
         *
         * 不能只靠 live 快照：后台会话根本没有 live 快照，`snapshot == null`
         * 时旧写法一律当 `Running`，于是「已经跑完的会话」会一直显示「执行中」。
         * 会话摘要才是这台机器上唯一知道它到底跑没跑的东西。
         */
        phase: CapsulePhase = CapsulePhase.Running,
    ): CapsuleState {
        val progress = todos?.let { CapsuleProgress.fromTodos(it) }
        // 步骤行优先用 todo 里那条 in_progress 的正文 —— 那是「实际在做的步骤」，
        // 比任何自己编的措辞都准（用户 m01212 要的就是这个）。
        val currentStep = todos?.items?.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.content
        val step = when {
            progress != null && currentStep != null ->
                // 前缀「第 N 步 / 共 M 步：」本身就占 11~15 个字符，所以正文
                // 的预算必须从整行预算里扣掉它（R059 第三轮审查 P2）。
                // 只扣正文会让整行到 39 字，超出一行 subText，被系统从右边切掉——
                // 切掉的恰好是这段裁剪想保住的那几个字，等于白裁。
                "第 ${progress.safeDone + 1} 步 / 共 ${progress.total} 步：" +
                    clip(currentStep, (STEP_MAX_CHARS - stepPrefixChars(progress)).coerceAtLeast(4))
            progress != null ->
                "第 ${progress.safeDone} 步 / 共 ${progress.total} 步"
            // 后台会话没有 todo：只能诚实地说不知道进度，不要编一个假的 n/m。
            else -> null
        }
        return CapsuleState(
            sessionId = sessionId,
            title = title,
            // B018：**不再回落成会话标题**。
            //
            // 旧写法 `report.orEmpty().ifEmpty { title }` 会让「这一帧没有汇报」变成
            // 「把会话标题抄到第一行」，于是第一行永远有字、第二行的动作却因为没有
            // 新内容而消失——用户看到的就是「标题一直是那个，操作一会儿就没了」。
            // 现在没有汇报就留空，由 `CapsuleCenter` 决定整行省略，
            // 把标题的两行都还给「汇报 / 操作」。
            report = clip(report.orEmpty(), REPORT_MAX_CHARS),
            action = action?.takeIf { it.isNotBlank() }?.let { clip(it, ACTION_MAX_CHARS) },
            step = step,
            progress = progress,
            startedAt = startedAt,
            pending = PendingKind.None,
            otherRunning = otherRunning,
            phase = phase,
        )
    }

    /**
     * 构造「待批准」的胶囊内容。
     *
     * 副行**必须写明作用对象**（多会话时）：否则用户以为自己批的是另一个会话。
     */
    fun approval(
        sessionId: String,
        title: String?,
        toolName: String?,
        report: String?,
        otherRunning: Int,
        startedAt: Long,
    ): CapsuleState = CapsuleState(
        sessionId = sessionId,
        title = title,
        // 汇报行只写会话名，不写「需要你批准才能继续」。
        //
        // 理由：胶囊条上的短文本已经是「待批准」（`setShortCriticalText`），
        // 卡片第一行再说一遍是重复。而那句前缀有 9 个字，占掉一行
        // （12 字）的四分之三，真机上变成「需要你批准才能继续：按…」
        // —— 剩下那个字几乎没信息量。省下来的空间留给会话名，
        // 用户一眼就能看出「是哪个任务在等我批」。
        report = title?.takeIf { it.isNotBlank() }?.let { clip(it, REPORT_MAX_CHARS) }.orEmpty(),
        // 动作行给出被拦下的具体工具 —— 那是用户下判断的唯一依据。
        action = toolName?.takeIf { it.isNotBlank() }?.let { clip(it, ACTION_MAX_CHARS) },
        step = null,
        progress = null,
        startedAt = startedAt,
        pending = PendingKind.Approval,
        otherRunning = otherRunning,
    )

    /**
     * 构造「待回答」的胶囊内容。
     *
     * @param firstQuestion 第一个问题的题干（显示在动作行）。
     * @param count 一共几题。
     */
    fun question(
        sessionId: String,
        title: String?,
        firstQuestion: String?,
        count: Int,
        otherRunning: Int,
        startedAt: Long,
    ): CapsuleState = CapsuleState(
        sessionId = sessionId,
        title = title,
        // 同上：短文本已经是「待回答」，“有 N 个问题”那句话占满一行却不如会话名有用。
        // 题目数已经在下面 subText 的「本组共 N 道」里说了。
        report = title?.takeIf { it.isNotBlank() }?.let { clip(it, REPORT_MAX_CHARS) }.orEmpty(),
        action = firstQuestion?.takeIf { it.isNotBlank() }?.let { clip(it, ACTION_MAX_CHARS) },
        // 不写「问题 i / 共 n」：这个计数是**这道题在组里的序号**，而卡片上只放
        // 当前这一道，用户答完一道后服务端推来剩下几道，序号会重新从 1 开始——
        // 真机上就出现过「已经答过一道，它还写问题 1」。所以只陈述这一组还有
        // 题（`count` 是服务端给的实数），不声称位置。
        //
        // 「答完会自动出现下一道」不仅是文案，是**实测行为**：点掉一道后服务端
        // 推回剩下的题，卡片原地换成下一道的选项。写出来是为了让用户敢点。
        step = if (count > 1) {
            "本组共 $count 道，答完一道自动出现下一道"
        } else {
            "选择一项即可"
        },
        progress = null,
        startedAt = startedAt,
        pending = PendingKind.Question,
        otherRunning = otherRunning,
    )
}

/**
 * 步骤行前缀「第 N 步 / 共 M 步：」的字符数。
 *
 * 抽成函数而不是写死：`done`/`total` 的位数会变（9 → 10 位多一个字），
 * 写死一个常量，10 步以上就又溢出。
 */
internal fun stepPrefixChars(progress: CapsuleProgress): Int =
    "第 ${progress.safeDone + 1} 步 / 共 ${progress.total} 步：".length

/** 这条 todo 是否「正在进行」。 */
internal val TodoStatus.capsuleActive: Boolean
    get() = this == TodoStatus.IN_PROGRESS

/**
 * 给胶囊用的短文案裁剪（进程内工具函数）。
 *
 * 与 [CapsuleContent.clip] 同一套逻辑，单独暴露是因为调用方（`ChatViewModel`）
 * 需要在**判重指纹**里就用裁剪后的值——用原值判重会让「同一句话末尾多一个字」
 * 也算新指纹，于是每条流式 token 都触发一次胶囊重算。
 */
internal fun clipForCapsule(text: String, max: Int = CapsuleContent.ACTION_MAX_CHARS): String =
    CapsuleContent.clip(text, max)

/**
 * B018：卡片标题的拼装 —— 「汇报」一行、「操作」一行，**缺一行就整行省略**。
 *
 * 抽成纯函数（而不是留在 `CapsuleCenter.build()` 里）有两个理由：
 * 1. `build()` 是 `private` 且 `@RequiresApi(36)`，单测够不着，于是「report 为空时会
 *    拼出一个以换行开头的字符串」这种错误可以一路通过全绿测试活到真机（变异验证实测存活）。
 * 2. 换行符是本项目最容易写错的东西（Python 补丁里一个 `BSN` 没拼好就把 Kotlin 源码劈成两行）。
 *
 * 为什么不能无条件拼：`report + "\n" + "▸ " + action` 在 `report` 为空时得到
 * 一个以换行开头的字符串——第一行是空的，系统仍按两行排版，白占掉一半高度，
 * 而第二行真正的动作反而被挤得没地方显示。
 */
internal fun capsuleTitle(
    report: String?,
    action: String?,
    fallback: String? = null,
): String {
    val act = action?.takeIf { it.isNotBlank() }
    // 两行都在时，汇报必须先压到一行，否则它自己占满两行、把操作行挤没。
    // 反过来（只有汇报）才允许它吃掉两行——那时多显示几个字是纯收益。
    val rep = report?.takeIf { it.isNotBlank() }
        ?.let { if (act != null) CapsuleContent.clip(it, CapsuleContent.TITLE_LINE_MAX_CHARS) else it }
    val lines = buildList {
        rep?.let { add(it) }
        act?.let { add("▸ " + it) }
    }
    if (lines.isNotEmpty()) return lines.joinToString("\n")
    // 两行都空时别交白卷。
    //
    // 真机上确实会出现这一刻：换轮次时 `LiveAttempt` 被清空，而新的汇报还没到，
    // 于是通知标题变成空串——系统照旧按两行排版，卡片上半部分整块空白（实测 13:22:28）。
    // 拿会话名顶上比空白强：它至少说明「这是哪个任务」，也符合用户最初的抱怨方向
    // （他嫌的是标题显示 **todo 正文**，不是会话名）。
    return fallback?.takeIf { it.isNotBlank() }.orEmpty()
}

/**
 * B018：粘滞合并 —— 「有新的才换，没有就留旧的」。
 *
 * 抽成纯函数而不是写在 `ChatViewModel` 的协程里，是为了能被直接单测与变异验证
 * （R059 的 `capsulePendingOrder` 同一种理由：埋在私有方法里的逻辑，怎么测都测不到）。
 *
 * 判空用 [isNullOrBlank] 而不是 `!= null`：助手在两次工具调用之间会推送**空正文**的
 * 帧，若只判 null，那一行会被空串顶掉，用户看到的就是「显示一会儿就没了」。
 */
internal fun stickyText(previous: String?, fresh: String?): String? =
    if (fresh.isNullOrBlank()) previous else fresh

/**
 * B018、审查第一轮 P2：离散判定「这一帧是不是新一轮开始」。
 *
 * 粘滞必须有轮次边界：否则同一个会话的第二轮开头、模型还没吐字时，卡片第 1 行仍是
 * 上一轮的末句、第 2 行仍是上一轮最后一个工具名（`▸ Bash`），看起来像那个工具正在跑。
 *
 * 只在 false → true 边沿返回 true：轮内每一帧都清一次等于取消粘滞（300ms 去抖下仍会闪）。
 *
 * 抽成纯函数的理由同 [stickyText]：埋在 `ChatViewModel` 的协程里怎么测都测不到。
 */
internal fun beginsNewTurn(wasRunning: Boolean, nowRunning: Boolean): Boolean =
    nowRunning && !wasRunning

/**
 * 裁「汇报行」（标题第 1 行）。
 *
 * 与 [clipForCapsule] 分开是因为两行的预算不同：汇报是整句人话（[CapsuleContent.REPORT_MAX_CHARS]），
 * 操作是工具名/短句（[CapsuleContent.ACTION_MAX_CHARS]）。共用一个上限会要么把汇报砍太狠、
 * 要么让操作行超出两行标题的可用宽度（B018）。
 */
internal fun clipReportForCapsule(
    text: String,
    max: Int = CapsuleContent.REPORT_MAX_CHARS,
): String = CapsuleContent.clip(text, max)
