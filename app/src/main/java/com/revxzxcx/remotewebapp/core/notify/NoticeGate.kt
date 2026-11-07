package com.revxzxcx.remotewebapp.core.notify

/**
 * 「这一轮是不是刚跑完」的**边沿**判定（R056）。
 *
 * ## 为什么必须是边沿而不是电平
 * 电平判定 = 「现在 `running == false` 就发通知」。冷启动时 App 拿到一行行
 * `running = false` 的会话，电平判定会**一次涌出几十条**通知——用户刚装好 App
 * 就被通知轰炸，随后必然去关掉整个频道。
 *
 * 边沿判定 = 「上一次是 `true`、这一次是 `false`」才算跑完一次。这需要记住上一态。
 *
 * ## 与 `CompletionLedger` 的关系（为什么不直接复用）
 * `core/session/CompletionLedger.kt` 已经在做同一件事，但它有三个**不适合通知**的性质：
 *
 * 1. **它住在 `HomeViewModel` 里**（`ui/home/HomeViewModel.kt:273` 的
 *    `private val ledger = CompletionLedger()`）。Activity 一销毁 ViewModel 就没了，
 *    而通知模块要活到进程结束。去重账必须在**进程级**，否则每次重建都会把
 *    「我记着它在跑」这件事忘掉——下次事件到达时「上一态」是未知的，边沿判不出来。
 * 2. 它的语义是「**用户还没来看**」：`currentSessionId` 命中时会主动清账，
 *    而通知与「用户此刻在看哪个会话」无关（前台可见时我们自己压掉通知，
 *    那是另一层判断，见 [NoticeGate]）。
 * 3. 它会因为用户点进会话而清掉标记，通知却需要「这一轮跑完了」这个事实本身。
 *
 * 所以这里另起一本账，判据与它一致（**第一次见到不算跑完**），但生命周期与容器同级。
 *
 * ## 第一次见到为什么不算
 * 与 `CompletionLedger.observe` 同一个理由：进程刚起来、还没收到过任何
 * `running = true` 的会话，直接判成「刚跑完」会把所有历史会话都通知一遍。
 * 返回 `null` 表示「无法判定」，调用方据此什么都不发。
 */
class CompletionEdge {

    /**
     * 每个会话上一次看到的运行态。
     *
     * 用 `HashMap` 而不是 `ArrayDeque` 环形账：会话数量本身有界（宿主机的会话表规模
     * 是几十到几百），而「忘掉某条会话」会让它下次跑完时判成第一次见 → 漏通知。
     * 宁可多占几百个 `Boolean`，也不要漏。
     */
    private val running = HashMap<String, Boolean>()

    /**
     * 把 [running] 这一态喂进来，返回**这次是不是跑完的边沿**。
     *
     * @return `true` = 刚从跑到停（该发「任务结束」通知）；`false` = 不该发；
     *   `null` = 第一次见到这个会话，无法判定。**调用方必须把 `null` 与 `false`
     *   区别对待**——`null` 说明我们错过了它的启动，此时把它当 `false` 记账没问题，
     *   但当 `true` 记账就会导致下次停下时判出边沿（漏发）。
     */
    fun observe(sessionId: String, running: Boolean): Boolean? {
        val previous = this.running.put(sessionId, running)
        return when {
            previous == null -> null
            previous && !running -> true
            else -> false
        }
    }

    /** 当前是否记着它在跑（测试与诊断用）。 */
    fun isRunning(sessionId: String): Boolean = running[sessionId] == true

    /** 忘掉一条会话（它被删除了）。 */
    fun forget(sessionId: String) {
        running.remove(sessionId)
    }

    /**
     * 清空全部记录（换了一台主机时用，见 `NotifyCoordinator.attach`）。
     *
     * 会话 id 是**每台主机各自**的：A 机器上的 `s-1` 与 B 机器上的 `s-1` 毫无关系。
     * 不清的话，切到 B 之后第一次看到 `s-1` 的 `running = false`，会把 A 留下的
     * `true` 当上一态 → 凭空发一条「任务结束」，而 B 上的这个会话可能正跑着。
     */
    fun reset() {
        running.clear()
    }

    /** 账本里的会话数。 */
    val size: Int get() = running.size
}

/**
 * 什么时候该真的发通知（R056）。
 *
 * ## 为什么需要它，而不是「收到就跑 `notify()`」
 * 三种情况发了就是骚扰：
 * 1. **用户正在看这个会话**——通知栏响一声，可他眼睛就盯着那个气泡。
 * 2. **App 在前台**——用户明明在用，还弹横幅挡住界面顶部。
 * 3. **没给通知权限**——`notify()` 会静默失败（Android 13+ 未授权时**不抛异常**），
 *    我们已经发过了，账本却以为通知到了。
 *
 * ## 为什么做成纯函数而不是直接读状态
 * 「前台/后台」与「当前打开哪个会话」在真实运行时来自 `ProcessLifecycleOwner` 与
 * Compose 状态，两者都很难在单测里构造。把它们收成一个 [NoticeVisibility] 数据类，
 * 判定本身就变成可以逐条断言的表。
 */
/**
 * 一条通知最终怎么投递（[NoticeGate.decide] 的结果）。
 *
 * 三态而不是布尔，是因为原型第 6 节的表里有一行是「**发，但可以只更新不响铃**」。
 * 用布尔就只能二选一，要么漏发要么多响——而这正是第一次实现时踩的坑。
 */
enum class NoticeDelivery {
    /** 什么都不做。 */
    Skip,

    /** 发，但不响铃不震动（`setSilent(true)`）。 */
    Silent,

    /** 发，正常提醒。 */
    Alert,
}

object NoticeGate {

    /**
     * 此刻这条通知该怎么投递。
     *
     * ## 三行判定（与原型 `docs/R056-系统通知.html` 第 6 节的表逐行对应）
     * | 场景 | 结果 |
     * | --- | --- |
     * | 前台 + 正看着这条会话 | [NoticeDelivery.Skip] |
     * | 前台 + 看的是别的页 | [NoticeDelivery.Silent] |
     * | 后台（**不管视角停在哪**） | [NoticeDelivery.Alert] |
     *
     * ## ⚠️ 为什么「正看着」必须以前台为前提
     * 第一版把 `foreground` 与「正看着这条」当成两个**各自独立**的阻断条件，于是
     * 「切到后台但视角仍停在那个会话」被两边同时判成不发——而这恰好是**最高频的
     * 真实场景**：用户在会话 X 里点发送 → 进入聊天页（`viewingSessionId = X`）→
     * 按 Home（`foreground = false`，但 `viewingSessionId` 仍是 X）→ 任务跑完 →
     * 两个 id 相等 → 通知被压掉。功能等于白做。
     *
     * `viewingSessionId` 只在切到别的会话时才变，**按 Home 不会动它**，所以它反映的
     * 是「最后一次看的会话」而不是「此刻正在看」。只有 App 在前台时这两者才等价。
     *
     * @param visibility 用户当前的可见状态。
     * @param channelId 打算发到哪个频道。
     * @param permissionGranted 通知权限是否已授予（`NotificationManagerCompat.areNotificationsEnabled()`
     *   **不够**：它在 API 33+ 只反映总开关，用户关掉单个频道时仍返回 true。
     *   频道级判断见 [NoticeSettings.channelEnabled]）。
     * @param channelEnabled 目标频道是否被用户关掉了。
     */
    fun decide(
        visibility: NoticeVisibility,
        channelId: String,
        permissionGranted: Boolean,
        channelEnabled: Boolean,
    ): NoticeDelivery {
        if (!permissionGranted) return NoticeDelivery.Skip
        if (!channelEnabled) return NoticeDelivery.Skip
        // 审批请求是**例外**：哪怕用户正盯着这个会话，也可能正看着别的部分（比如正在
        // 打字回上一条），而服务端在等。让横幅出现一次的成本，远小于「工具静默失败」。
        if (channelId == NoticeChannels.ASK) return NoticeDelivery.Alert
        val viewingThis = visibility.viewingSessionId != null &&
            visibility.viewingSessionId == visibility.completedSessionId
        if (visibility.foreground) {
            // 前台：正看着这条就彻底静默（界面里已有完成提示），看的是别的页则发但不响铃。
            return if (viewingThis) NoticeDelivery.Skip else NoticeDelivery.Silent
        }
        // 后台：**一律正常提醒**。「切出去等结果」是这个功能存在的全部理由，
        // 此时 `viewingSessionId` 只是残留值，不代表用户在看。
        return NoticeDelivery.Alert
    }
}

/**
 * 用户此刻的可见状态（[NoticeGate.decide] 的输入）。
 *
 * @param foreground App 是否在前台。**不等于「Activity 存在」**：分屏、画中画、
 *   或者用户刚按 Home 而 Activity 还没 `onStop` 时都不该算前台。判据见 `DshApplication`
 *   里挂的 `ProcessLifecycleOwner`。
 * @param viewingSessionId 最后一次打开的会话（在首页列表页时为 null）。
 *   **按 Home 切后台不会把它清空**，所以它不等于「此刻正在看」——只有在
 *   [foreground] 为 true 时两者才等价。这是 [NoticeGate.decide] 里最容易搞错的一条。
 * @param completedSessionId **本次要通知的那个**会话，用于判「是不是正看着它」。
 *   放进同一个数据类而不是当参数传，是为了让两个字段的比较关系在同一处被读出来。
 */
data class NoticeVisibility(
    val foreground: Boolean = false,
    val viewingSessionId: String? = null,
    val completedSessionId: String? = null,
)
