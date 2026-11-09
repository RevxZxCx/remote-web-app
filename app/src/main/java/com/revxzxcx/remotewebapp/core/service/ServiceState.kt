package com.revxzxcx.remotewebapp.core.service

/**
 * 后台连接的五个状态（R057 原型第 3 节 + 真机补的第五相）。
 *
 * ## 为什么「连接中」与「已连接」必须分开
 * 用户在通知栏看到的这一行，是他判断「现在到底能不能收到提醒」的唯一依据。
 * 合并成一个「后台运行中」会让最要命的那一相（[Reconnecting]）藏起来：
 * 服务明明在跑，但连接断了，此刻**收不到任何通知**——而标题偏偏说「运行中」。
 *
 * [Connecting] 与 [Connected] 也不能合并：一个是「正在建立」，一个是「已建立」，
 * 用户问「是不是已经好了」时候答案不同。它们只在收起态标题上共用一个说法，
 * 展开后正文必须分开（见 `ServiceText`）。
 *
 * ## 为什么有 [Unpaired] 这个第五相（原型里没有）
 * 原型画了四相，写代码时发现 `CarrierState.Unpaired` 无处可去：它是**终态**，
 * 重试多少次都不会成功（凭证被桌面端撤销了），只能由用户重新配对。
 * 把它塞进 [Reconnecting] 会让通知栏一直说「正在重连桌面端…（第 7 次）」——
 * 而它永远连不上。用户的原话第一条就是「状态如实写」，这不是如实。
 */
enum class ServicePhase {

    /** 服务在跑，socket 还没连上（冷启动后的头一两秒）。 */
    Connecting,

    /** 服务在跑，socket 连着。这个状态下通知才是实时的。 */
    Connected,

    /** 服务在跑，socket 断了，正在退避重试。**此刻收不到通知。** */
    Reconnecting,

    /**
     * 服务在跑，但凭证已经不被桌面端认了。**重试没有意义，必须重新配对。**
     *
     * 仍然算「在跑」（[ServiceStatus.running] 为 true）：服务不能自己停，
     * 否则用户连这条「去重新配对」的提示都没有了。
     */
    Unpaired,

    /**
     * 服务没在跑。
     *
     * 没在跑就**没有常驻通知**——包括用户点了「退出」之后：按 Q4 的口径，
     * 退出就是真的退干净，设置页那一行也整个消失，不留「已退出（本次）」这种半吊子状态。
     */
    Stopped,
}

/**
 * 常驻通知与设置页状态行要显示的全部信息。
 *
 * 纯数据：不读时钟、不碰系统服务。谁持有这个对象谁负责按时间刷新它，
 * 这样「各相怎么算」可以在 JVM 单测里逐条断言，不用建 Service。
 *
 * @param phase 当前是哪一相。
 * @param connectedForMillis 已连接多久（只在 [ServicePhase.Connected] 有意义）。
 * @param downForMillis 已断开多久（只在 [ServicePhase.Reconnecting] 有意义）。
 * @param attempt 第几次重连（只在 [ServicePhase.Reconnecting] 有意义）。
 * @param unpairedReason 为什么不被认了（只在 [ServicePhase.Unpaired] 有意义；
 *   来自 `CarrierState.Unpaired.reason`，是桌面端给的原因）。
 */
data class ServiceStatus(
    val phase: ServicePhase = ServicePhase.Stopped,
    val connectedForMillis: Long = 0L,
    val downForMillis: Long = 0L,
    val attempt: Int = 0,
    val unpairedReason: String = "",
) {

    /** 服务此刻在不在跑。`false` 时不该有任何常驻通知。 */
    val running: Boolean get() = phase != ServicePhase.Stopped
}

/**
 * 服务被停之后该怎么办（用户 Q2 的拍板：自动恢复可开关，**关闭时才发恢复通知**）。
 *
 * 「自动恢复」与「恢复通知」是**互斥分支，不叠加**——这是用户原话里最容易读漏的一点：
 * 「服务停了之后应该自动恢复，并且自动恢复要可开关，关闭状态下才做恢复的通知」。
 * 两者同时做的话，用户会在服务已经被悄悄拉回来之后，还收到一条「后台连接断了」。
 */
enum class RecoveryAction {

    /** 静默拉起，不响不弹。 */
    RestartSilently,

    /** 不发服务，只发一条「后台连接断了」让用户自己决定。 */
    NotifyUser,

    /** 什么都不做。 */
    DoNothing,
}

/**
 * 服务没了之后该做什么。
 *
 * @param foregroundEnabled 「保持后台连接」总开关。关着就什么都不该发生——
 *   用户明确表示不要这个常驻服务，我们不能既不起服务又给他发一条「连不上了」。
 * @param autoRecover 「服务停了自动恢复」开关。
 * @param userExited 本次运行内用户点过「退出」（见 [ServiceExitMarker]）。
 *   **这是「退出」按钮有意义的前提**：不然服务一停，自动恢复立刻把它拉回来，
 *   用户点了跟没点一样。
 * @param noticeUsable 通知此刻能不能发出去（权限 + 总开关）。
 *   前台服务**必须**挂一条常驻通知，发不出去的话服务会被系统判为无效，
 *   所以这里一并挡住：宁可什么都不做，也不要起一个注定被回收的服务。
 */
fun planRecovery(
    foregroundEnabled: Boolean,
    autoRecover: Boolean,
    userExited: Boolean,
    noticeUsable: Boolean,
): RecoveryAction = when {
    // 总开关关着：用户不要这个功能，连「断了」都不必告诉他。
    !foregroundEnabled -> RecoveryAction.DoNothing
    // 用户刚亲手关掉：再弹一条通知等于问「你确定吗」，那是骚扰。
    userExited -> RecoveryAction.DoNothing
    // 起不了（前台服务没有通知就会被回收），通知也发不出去：两条路都走不通。
    !noticeUsable -> RecoveryAction.DoNothing
    autoRecover -> RecoveryAction.RestartSilently
    else -> RecoveryAction.NotifyUser
}

/**
 * 开机时该不该把服务拉起来。
 *
 * 三个条件缺一不可，且**不含「本次已退出」**：那个标记只活在内存里，
 * 手机重启后本来就没了。这与用户「后续启动还是会启动前台服务」的要求一致——
 * 退出只影响这一次，重启之后照旧。
 *
 * @param noticeUsable 开机这一刻通知权限可能是关着的（用户在系统设置里关过）。
 *   前台服务起不来，所以先判它，免得做一次注定失败且要 try/catch 的尝试。
 */
fun shouldStartOnBoot(
    foregroundEnabled: Boolean,
    bootStart: Boolean,
    noticeUsable: Boolean,
): Boolean = foregroundEnabled && bootStart && noticeUsable

/**
 * 前台服务启动失败之后，对「开关」和「自动重试」该怎么办。
 *
 * @param blocksRetry 要不要挡住后续自动重试。挡住之后只有用户亲手重开开关才能解除。
 * @param turnsSwitchOff 要不要替用户把开关关掉。
 */
data class StartFailurePolicy(
    val blocksRetry: Boolean,
    val turnsSwitchOff: Boolean,
)

/**
 * 一次启动失败该归到哪一类（真机验收发现的缺陷，见下方三类）。
 *
 * ## 为什么要分类：真机上「开机自启」被这个函数以外的一刀切kill掉了
 *
 * 真机日志实证：`startForegroundService() not allowed due to mAllowStartForeground false`
 * 这条失败被当成「重试也没用」，于是**开关被替用户关掉**并写了「别重试」标记。
 * 用户开机时撞一次，之后每次重启 `shouldStartOnBoot` 都因为开关是 false 恒不成立，
 * 整条开机自启链路静默什么都不做，而设置页只剩一行过期的原因。
 *
 * 三类区别对待：
 * - **时机性**（[isTemporaryStartFailure]）：系统当下不许起前台服务。不是用户能修的，
 *   也不是永久没戏——开机后的临时白名单只有 20 秒，用户手动打开 App、连接建立、
 *   下一次开机都可能成功。所以**只记原因，不关开关、不挡重试**。
 * - **通知不可用**：用户去系统设置授权就能好。同样不关、不挡（R057 审查 P1-5）。
 * - **其余**：重试也不会好。关开关 + 挡重试，别让亮着的开关骗人（R056-B1 的病）。
 */
fun startFailurePolicy(
    temporary: Boolean,
    noticeUsable: Boolean,
): StartFailurePolicy = when {
    temporary -> StartFailurePolicy(blocksRetry = false, turnsSwitchOff = false)
    !noticeUsable -> StartFailurePolicy(blocksRetry = false, turnsSwitchOff = false)
    else -> StartFailurePolicy(blocksRetry = true, turnsSwitchOff = true)
}

/** 判定「系统当下不许起前台服务」时抛的那个异常（按名字认，不引类）。 */
private const val TEMPORARY_START_FAILURE_TYPE = "ForegroundServiceStartNotAllowedException"

/** 同一个异常的 message 里那句话——机型/版本不同，可能只留 message 没留类型。 */
private const val TEMPORARY_START_FAILURE_MESSAGE = "mAllowStartForeground"

private const val MAX_CAUSE_DEPTH = 8

/**
 * 这次失败是不是「时机性」的（系统暂时不许起前台服务，稍后可能就行）。
 *
 * ## 为什么不写 `cause is ForegroundServiceStartNotAllowedException`
 * 那个类要到 API 31 才有，而 minSdk 是 26。Kotlin 的 `is` 会编成 `instanceof`，
 * 在 26~30 的机器上遇到不存在的类会直接 `NoClassDefFoundError`——为了一条诊断
 * 把 App 搞崩不值。所以按**类名 + message** 认，全程只用字符串，任何版本都安全。
 *
 * 顺带把 [Throwable.cause] 链走一遍：真实抛出来时它常常被包在
 * `RuntimeException` 里（`startForegroundService` 的调用方各有各的包装）。
 */
fun isTemporaryStartFailure(cause: Throwable): Boolean {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current.javaClass.simpleName == TEMPORARY_START_FAILURE_TYPE) return true
        if (current.message.orEmpty().contains(TEMPORARY_START_FAILURE_MESSAGE)) return true
        current = current.cause
        depth++
    }
    return false
}

/**
 * 「本次已退出」标记（用户 Q4 + 「只影响这一次」）。
 *
 * ## 为什么是内存变量，不落盘
 * 「本次」的含义就是「这次 App 活着期间」。真落盘的话，用户退出后再杀一次进程、
 * 再打开，标记还在，服务就永远起不来——**等于把「退出一次」变成了「永久关闭」**，
 * 与用户的要求正好相反。
 *
 * 因此它只能住在 [com.revxzxcx.remotewebapp.core.AppContainer] 里（与进程同寿）。
 *
 * ## 为什么要 `@Volatile`
 * 写的是主线程（用户点通知按钮 → 接收器 → 容器），读的是服务线程（自动恢复判定）。
 * 不加的话「刚退出、恢复逻辑还看到 false」会导致服务被立刻拉起，用户会觉得按钮坏了。
 */
class ServiceExitMarker {

    @Volatile
    private var exited: Boolean = false

    /**
     * 用户在**哪台主机**上点的「退出」（R057 审查第三轮 P2-5）。
     *
     * ## 为什么必须记住是谁
     * 标记原本是全局一个布尔。可用户点过「退出」之后完全可能发生这些事：
     * 桌面端下线（`watchCarrier(null)` → `onNoConnection()`）、改地址、换主机、
     * 重新配对。这些都会让 `ensureRunning()` 有机会再被调一次，而当时那个布尔
     * 还是 true，于是「开关亮着、服务却永远起不来、设置页毫无提示」。
     *
     * 记住 hostKey 之后，「退出」的作用域就收紧成「这台主机这一次」：
     * 换到别的主机时 [isExitedFor] 自然返回 false，不需要谁去手工复位。
     */
    @Volatile
    private var exitedHostKey: String? = null

    /** 本次运行内用户点过「退出」，且之后服务没有再成功启动过。 */
    val isExited: Boolean get() = exited

    /** 用户在某台主机上点过「退出」。 */
    fun markExited() {
        exited = true
    }

    /**
     * 用户在 [hostKey] 这台主机上点了「退出」。
     *
     * 空串（此时容器还没发布活动主机）按「不知道是哪台」处理，仍然全局生效——
     * 宁可多挡一次，也不要在不知道端口的情况下把它当成别的机器而放行。
     */
    fun markExited(hostKey: String) {
        exited = true
        exitedHostKey = hostKey.ifBlank { null }
    }

    /**
     * [hostKey] 这台主机此刻是否被「退出」挡着。
     *
     * 记不住是谁时（[exitedHostKey] 为 null）保持旧行为：一律算退出。
     */
    fun isExitedFor(hostKey: String): Boolean {
        if (!exited) return false
        val owner = exitedHostKey ?: return true
        return owner == hostKey
    }

    /**
     * 服务真的起来了。
     *
     * 清掉标记而不是保留：走到这里说明这条路径没有被「退出」挡住（可能是用户
     * 重开了 App、或手动关了再打开开关）。继续挡着会让「退出」变成一个再也解不开的锁。
     */
    fun markRunning() {
        exited = false
        exitedHostKey = null
    }

    /** 换主机 / 退配对时复位。 */
    fun reset() {
        exited = false
        exitedHostKey = null
    }
}
