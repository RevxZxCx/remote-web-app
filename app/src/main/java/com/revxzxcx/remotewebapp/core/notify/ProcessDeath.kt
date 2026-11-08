package com.revxzxcx.remotewebapp.core.notify

/**
 * 「上一次进程是怎么没的」判定（R056 / Q3）。
 *
 * ## 这个文件要回答的问题
 * Q1 的边界是「只做事件 → 通知，不加前台服务」，代价写在原型第 7 节：**进程被系统
 * 回收之后什么都收不到**。用户在一加上的真实经历是「任务在跑，我切出去买杯咖啡，
 * 回来什么提示都没有」——而他并不知道是系统把 App 杀了，只会认为「这个 App 的通知坏掉了」。
 *
 * Q3 选的方案是「设置里放二级页 + **检测到后台被杀过时主动弹一次提示**」：
 * 用户不会主动去找设置，所以要在他**下一次打开 App 时**告诉他「上次是被杀掉的」。
 *
 * ## 为什么判定逻辑要跟「读退出原因」分开
 * 读退出原因要 `ActivityManager.getHistoricalProcessExitReasons`（API 30+，且只有真机
 * 才有真实数据），在 JVM 单测里跑不了。而「哪种退出原因该提示、什么时候不该提示」
 * 恰恰是这里最容易写错的部分（第一次装也弹、每次冷启动都弹、把自己的崩溃怪到电池头上）。
 * 所以：**读数据的手在 [ExitHistoryReader]，判定的脑子在这里**，后者是纯函数。
 *
 * ## 为什么不重写成「用一个干净退出的标记」
 * Android 上 `Application.onTerminate()` **在真机上根本不调用**（只有模拟器调），
 * 所以「上次退出时写一个干净标记」这条路拿不到可靠数据。`ApplicationExitInfo`
 * 是系统记的，唯一不需要我们猜的来源。
 */
enum class ExitReason {
    /** 系统内存不足时回收的。 */
    LowMemory,

    /** 被别人 SIGKILL（一加的电池应用最常见的就是这个）。 */
    Signaled,

    /** 被系统的冻结器回收（Android 11+ 的 cached app freezer）。 */
    Freezer,

    /** 系统给了个没说法的原因。一加/ColorOS 的某些杀法落在这里。 */
    Other,

    /** 用户自己从最近任务里划掉的。 */
    UserRequested,

    /** 我们自己的崩溃 / ANR / 初始化失败。 */
    Crash,

    /** 系统更新、权限变更、依赖进程死亡等，与省电策略无关。 */
    SystemHousekeeping,

    /** 不认识的原因（含系统新增的、我们还没映射的）。 */
    Unknown,
}

/** 一条系统记录的进程退出事实。时间戳是 epoch 毫秒。 */
data class ExitFacts(
    val reason: ExitReason,
    val timestamp: Long,
    /** 系统给的人类可读说明（`ApplicationExitInfo.getDescription()`），常为空。 */
    val description: String? = null,
)

/**
 * 进程退出原因的归类。
 *
 * 分成三类而不是两类，是因为**「该不该提示」的答案不同**：
 *
 * | 归类 | 提示吗 | 为什么 |
 * | --- | --- | --- |
 * | [SystemKilled] | 提示「去开保活」 | 正是 Q3 要说的那件事 |
 * | [Stopped] | 提示，但**不说是谁干的** | 原因不同但后果一样：通知停了；不解惑就永远不知道为什么 |
 * | [NotAKill] | 不提示 | 崩溃 / ANR 是我们自己的 bug，把用户引去改电池设置只会让他白忙 |
 *
 * ## ⚠️ 为什么没有「用户划掉的」这一类（真机实测推翻的设计）
 * 最初的实现有 `UserSwiped`，把 `REASON_USER_REQUESTED` 当成「从最近任务划掉」，文案写
 * 「上次是从最近任务里划掉的」。真机验证据此打脸：
 *
 * ```
 * # adb shell am kill <pkg> 之后
 * reason=10 (USER REQUESTED) subreason=24 (KILL BACKGROUND) description=kill background
 * # adb shell am force-stop <pkg> 之后
 * reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)      description=stop ... due to from pid
 * ```
 *
 * **我并没有划掉 App，只是让它在后台被 kill。** 而 OEM 电池应用调
 * `killBackgroundProcesses()` 杀进程走的正是同一条 `subreason=24`。
 * 能区分两者的只有 `ApplicationExitInfo.getSubReason()`，而它**不是公开 API**
 * （`javap android.app.ApplicationExitInfo` 在 android-36 上只有
 * `getReason/getTimestamp/getDescription` 等，没有 `getSubReason`）。
 *
 * 所以 `REASON_USER_REQUESTED` 在真实设备上是「用户划掉 / 系统 killBackground /
 * force stop」的混合体，**没有任何可靠手段分辨**。
 *
 * 出路只有两条，选的是后者：
 * - 猜：用 `getDescription()` 的字符串做启发式 → 非契约、随 OEM 与版本变化、且可为 null。
 * - 不猜：承认分不出来，[Stopped] 的文案**不指名道姓**，把两种可能都告诉用户。
 *
 * 与 `pickDeathNotice` 的「少说一次的代价远小于说错一次」是同一条纪律：用户明明没划，
 * 却被告知「你划掉了」，他会认定这个 App 在瞎猜，之后连真话也不信了。
 */
enum class DeathCause { SystemKilled, Stopped, NotAKill }

/**
 * 归类表。
 *
 * ## 为什么 `Other` 算系统杀的、`Unknown` 不算
 * `Other` 是系统明确记录的「进程被拿走了，但原因不属于上面任何一类」，一加的电池
 * 应用杀进程常落在这里——把它排除掉，这个功能在一加上就等于没做。
 * `Unknown` 相反：它意味着**我们没认出这个原因**（系统版本比我们新），这时宁可少提示，
 * 也不要对着一个可能是我方崩溃的东西让用户去改电池设置。这条和
 * `NoticeGate.decide` 的默认分支一样，取「不打扰」而不是「尽量说」。
 *
 * ## `UserRequested` 为什么归到 [DeathCause.Stopped] 而不是「系统杀的」
 * 它同时代表「用户划掉」「OEM 电池杀」「force stop」三种（见 [DeathCause] 的 KDoc），
 * 后两种其实是系统的锅。按 [DeathCause.SystemKilled] 处理会让文案理直气壮地说
 * 「系统把 App 杀掉的」——对划掉的用户是错的；按「用户干的」处理则对 OEM 杀的更错。
 * 只有 [DeathCause.Stopped] 这种「不指名道姓」的措辞两种情况都不冤枉。
 *
 * `REASON_USER_STOPPED`（11，API 30 起有）也归到 [ExitReason.UserRequested]：它表示
 * 「用户明确停止了这个进程」（长按停止、设置里强制停止），与上面的 force stop 是同一族。
 */
fun classifyExit(reason: ExitReason): DeathCause = when (reason) {
    ExitReason.LowMemory, ExitReason.Signaled, ExitReason.Freezer, ExitReason.Other ->
        DeathCause.SystemKilled
    ExitReason.UserRequested -> DeathCause.Stopped
    ExitReason.Crash,
    ExitReason.SystemHousekeeping,
    ExitReason.Unknown,
    -> DeathCause.NotAKill
}

/** 要弹给用户的那一条。 */
data class DeathNotice(
    val cause: DeathCause,
    /** 发生时刻（epoch 毫秒）。用于「同一次死亡只提示一次」的去重。 */
    val occurredAt: Long,
)

/**
 * 从系统给的退出历史里挑出「该提示的那一条」。
 *
 * ## 只看最近的那一条，不做「最新的可提示项」
 * 卡片写的是「**上次**是被系统杀掉的」，主语是最近一次死亡。若实现成「筛掉崩溃、再取
 * 剩下里最新的」，就会出现：用户刚经历一次**本应用崩溃**（2 秒前），冷启动后看到的却是
 * 「上次是被系统杀掉的」——时间与事实都对不上，而真正该修的 bug 被这张卡掩盖了。
 *
 * 所以规则是：先取时间上最新的一条，再判它算不算「被杀」。最新那条是我们自己的问题时，
 * 宁可这次什么都不说。少说一次的代价远小于说错一次。
 *
 * 下面四条前置规则，每条都对着一个具体的坏结果：
 *
 * 1. **没有基线就不提示**（`lastSeenAt <= 0`）。第一次装完打开 App 时，系统的历史里
 *    往往还留着几天前的一次旧退出——照着它弹「上次你被杀了」，用户会觉得莫名其妙。
 *    与 `CompletionEdge.observe` 的「第一次见返回 null」是同一条纪律（D177）。
 * 2. **只看比基线新的**（`timestamp > lastSeenAt`）。否则每次冷启动都弹同一条。
 * 3. **必须是进过后台之后死的**。用户正开着 App 时进程没了，那是崩溃（规则 4 也会排除），
 *    提示「去开保活」是答非所问。`backgroundedAt == null` 表示我们没记到过进后台的时刻，
 *    这时无法确认，按「不提示」处理。
 * 4. **最新那条的归类必须是 [DeathCause.NotAKill] 之外**。
 *
 * @param exits 系统给的退出历史，**顺序无所谓**，这里自己排序。
 * @param lastSeenAt 上次提示过的时刻；`0` 表示还没有基线。
 * @param backgroundedAt 上一次进后台的时刻；`null` 表示没记录。
 */
fun pickDeathNotice(
    exits: List<ExitFacts>,
    lastSeenAt: Long,
    backgroundedAt: Long?,
): DeathNotice? {
    if (lastSeenAt <= 0L) return null
    if (backgroundedAt == null || backgroundedAt <= 0L) return null
    val latest = exits.maxByOrNull { it.timestamp } ?: return null
    if (latest.timestamp <= lastSeenAt) return null
    if (latest.timestamp < backgroundedAt) return null
    val cause = classifyExit(latest.reason)
    if (cause == DeathCause.NotAKill) return null
    return DeathNotice(cause, latest.timestamp)
}

/**
 * 记录基线时要写进去的时刻。
 *
 * 单独抽出来是因为它和 [pickDeathNotice] 必须**看同一批数据**：挑的时候拿最新那条的
 * 时间戳当基线，下次才不会重复提示，也不会因为基线落在中间而漏掉更早的一条。
 * `lastSeenAt <= 0` 时（首次）直接返回最新时刻，即「只建基线、不提示」。
 */
fun baselineFor(exits: List<ExitFacts>): Long = exits.maxOfOrNull { it.timestamp } ?: 0L

/** 提示卡的标题。 */
fun deathTitle(cause: DeathCause): String = when (cause) {
    DeathCause.SystemKilled -> "上次是系统把 App 杀掉的"
    // 不写「你划掉的」：getSubReason 不是公开 API，划掉 / 系统 killBackground /
    // force stop 在这里分不出来（见 DeathCause 的 KDoc）。说是用户干的，冤枉人的概率很高。
    DeathCause.Stopped -> "上次 App 是被停掉的"
    DeathCause.NotAKill -> ""
}

/**
 * 提示卡的正文。
 *
 * 两种原因分开写：用户划掉的却被告知「系统杀的」，他会觉得 App 在瞎猜；
 * 而两种情况的建议动作本来也不同（一个去改电池设置，一个别再划掉）。
 *
 * [DeathCause.Stopped] 的正文**必须把两种可能都摆出来**（自己划掉 / 系统在后台清掉）：
 * 我们分不出是哪一种，只讲一种就会在另一半用户那里显得在胡说。
 */
fun deathBody(cause: DeathCause): String = when (cause) {
    DeathCause.SystemKilled ->
        "后台被杀之后连接就断了——任务跑完、模型要你确认，都不会通知你。" +
            "把下面几项保活设置打开可以少被杀。"
    DeathCause.Stopped ->
        "从最近任务里划掉，或者系统在后台把它清掉，都会让 App 停掉：" +
            "连接断开，通知也跟着停。想让它尽量活着，可以去开下面几项保活设置。"
    DeathCause.NotAKill -> ""
}

/** 提示卡上那颗按钮的文案。三种归类里只有两种会给按钮。 */
fun deathActionLabel(cause: DeathCause): String = when (cause) {
    // 两种都指向保活设置：既然分不出是不是用户划的，就当作「想让它别停」来处理。
    DeathCause.SystemKilled, DeathCause.Stopped -> "去开保活"
    DeathCause.NotAKill -> ""
}
