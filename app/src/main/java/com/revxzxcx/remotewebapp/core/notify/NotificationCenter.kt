package com.revxzxcx.remotewebapp.core.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.revxzxcx.remotewebapp.MainActivity
import com.revxzxcx.remotewebapp.R
import com.revxzxcx.remotewebapp.core.capsule.CapsuleActionIntents
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.session.AskOption
import com.revxzxcx.remotewebapp.core.session.AskQuestion

/**
 * 真正碰系统通知 API 的唯一一层（R056）。
 *
 * ## 职责边界
 * 这个类**不做判断**：发不发、发哪条，全部由 [NotifyCoordinator] 用纯函数
 * （[NoticeGate] / [CompletionEdge]）算好再决定调不调这里。它只负责三件事：
 * 建频道、拼 `Notification`、`notify()` / `cancel()`。
 *
 * 这样切分的理由是**可测性**：`NotificationCompat.Builder` 链在 JVM 单测里全是
 * `Method not mocked`，而「什么情况下该发」才是这个功能真正的风险所在。
 *
 * ## 若干 Android 通知的硬性事实（都踩过或查过文档）
 * 1. **频道创建后重要性改不了**（只有 name/description 可改）。要调重要性只能换新的
 *    channel id。所以 [NoticeChannels.ASK] 一上来就定为 HIGH，不要指望以后调高。
 * 2. **更新同一条通知必须用同一个 id 且不 cancel**。先 `cancel()` 再 `notify()` 会
 *    重新响铃闪一下；用相同 id 直接 `notify()` 才是原地替换，配合
 *    `setOnlyAlertOnce(true)` 连响都省了。
 * 3. **未授权时 `notify()` 静默失败且不抛异常**（Android 13+）。所以调用方必须先查
 *    [canPost]，否则去重账会以为已经通知过了。
 * 4. `PendingIntent` 在 targetSdk ≥ 31 上**必须**显式给 `FLAG_IMMUTABLE` 或
 *    `FLAG_MUTABLE`，漏了直接 `IllegalArgumentException` 崩溃。
 * 5. 批准/拒绝走**广播**而不是 Activity：点通知动作时不该把整个 App 拉到前台——
 *    用户可能正想让它继续在后台跑。
 */
class NotificationCenter(
    private val context: Context,
    private val settings: NoticeSettings,
) {

    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * 通知权限是否真的能发。
     *
     * 两层都要查，缺一不可：
     * - API 33+ 的 `POST_NOTIFICATIONS` 运行时权限（未授权时 `notify()` 静默失败）；
     * - 用户总开关（设置里关掉本应用通知）。`areNotificationsEnabled()` 两者都覆盖，
     *   但它在**用户单独关掉某个频道**时仍返回 true，所以频道级判断交给
     *   [NoticeSettings.effectiveEnabled] + [isChannelEnabled]。
     */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return manager?.areNotificationsEnabled() ?: false
    }

    /** 系统总开关（不含 API 33 运行时权限），设置页用来区分「该要权限」还是「被关了」。 */
    fun systemEnabled(): Boolean = manager?.areNotificationsEnabled() ?: false

    /** API 33 运行时权限是否已授予（旧版本恒 true，因为没有这个权限）。 */
    fun runtimePermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 单个频道是否被用户在系统设置里关掉了。
     *
     * 必须单独查：`areNotificationsEnabled()` 只反映总开关，用户关掉一个频道时它仍是
     * true，我们照发、系统静默丢弃——表现是「设置里开着，但从来不响」。
     */
    fun isChannelEnabled(channelId: String): Boolean {
        val nm = manager ?: return false
        // minSdk 26，`NotificationChannel` 一定存在，不需要版本判断。
        val channel = nm.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 建两个频道。**幂等**，每次进程启动调一次即可。
     *
     * 已存在时 `createNotificationChannel` 是空操作（重要性不会变，这正是我们想要的：
     * 用户手动调过的重要性不该被我们每次启动改回去）。
     */
    fun ensureChannels() {
        val nm = manager ?: return
        for (id in NoticeChannels.ALL) {
            val channel = NotificationChannel(
                id,
                NoticeChannels.name(id),
                NoticeChannels.importance(id),
            ).apply {
                description = NoticeChannels.description(id)
                // 角标：任务结束用得上（一眼看出有几条跑完了）。审批不用——
                // 它是要人立刻处理的事，堆在角标里反而更容易被忽略。
                setShowBadge(id == NoticeChannels.DONE)
                enableVibration(id == NoticeChannels.ASK)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 单条会话当前的通知 id。
     *
     * 用 [NoticeIdPool] 而不是 `sessionId.hashCode()`：后者在几百条会话上撞车概率
     * 不低，撞了就是「两条会话共用一个通知，后一条把前一条顶掉」。池子保证同一时刻
     * 每个 key 独占一个 id，被淘汰的那个通过 `evicted` 返回，调用方顺手 cancel。
     */
    private val idPool = NoticeIdPool(base = ID_BASE, capacity = ID_CAPACITY)

    /** 该会话的通知 id；**没有就分配**（发通知时用）。 */
    fun idFor(sessionId: String): NoticeIdPool.Allocation = idPool.allocate(DONE_KEY_PREFIX + sessionId)

    /** 该会话的通知 id；没分配过返回 null（取消时用，不产生垃圾 id）。 */
    fun peekId(sessionId: String): Int? = idPool.peek(DONE_KEY_PREFIX + sessionId)

    /**
     * 发「任务结束 / 任务出错」。
     *
     * 同一会话固定 id：跑第二轮会**原地替换**上一条，而不是堆两条。配合
     * `setOnlyAlertOnce(true)`，连跑五轮也只响一次——用户顶多想知道「有没有新的跑完」，
     * 不需要被同一个会话反复打断。
     *
     * @param failed true 走「任务出错」文案。
     * @param reason 失败原因（来自 `api-session/error` 帧里服务端拼好的 user-safe 文案）。
     * @param silent true 表示「只更新通知栏，不响铃不震动」。用于「App 在前台但用户看的
     *   是别的页」——[NoticeGate.decide] 对这一行给的是 [NoticeDelivery.Silent]。
     *   切到后台时这个值一定是 false，因为那是最需要提醒的场景。
     */
    fun postDone(
        sessionId: String,
        sessionName: String,
        failed: Boolean,
        reason: String?,
        silent: Boolean = false,
    ) {
        val nm = manager ?: return
        val allocation = idFor(sessionId)
        // 池子绕回来了，旧 id 现在归别人用 —— 必须先撤掉那条残留通知，
        // 否则用户会看到一条永远点不出东西的通知（它的 contentIntent 指向旧会话）。
        for (stale in allocation.evicted) nm.cancel(stale)

        val title = if (failed) {
            NoticeText.failedTitle(sessionName)
        } else {
            NoticeText.doneTitle(sessionName)
        }
        val text = if (failed) NoticeText.failedText(reason.orEmpty()) else NoticeText.doneText()

        val notification = NotificationCompat.Builder(context, NoticeChannels.DONE)
            .setSmallIcon(R.drawable.ic_notice_done)
            .setColor(ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(sessionId))
            .setAutoCancel(true)
            // 🔴 只有「跑完」才只响第一次；「跑挂」必须每次都响。
            //
            // 这里原本写死 `setOnlyAlertOnce(true)`，于是留下一条静默的漏报：
            // 同一会话先正常跑完（响过一声）→ 再跑一轮并且失败时，`notify()` 用的是
            // 同一个 id，而 `ONLY_ALERT_ONCE` 的语义是「这条通知已经响过就不再响」，
            // 结果通知栏文字更新成了「任务出错 · xxx」，却**不响铃、不弹横幅**。
            // 用户看到的现象正是「跑挂了却一声不吭」——失败比跑完更需要他知道。
            //
            // 成功侧保留 `true` 的理由不变：连跑五轮只响一次，否则用户会把这个频道关掉，
            // 那是比不响更坏的结果。失败侧置 `false`：每一次失败都是独立的事故。
            .setOnlyAlertOnce(!failed)
            .setCategory(Notification.CATEGORY_STATUS)
            // 锁屏上显示到什么程度由设置决定，见 lockScreenVisibility()。
            .setVisibility(lockScreenVisibility())
            .apply {
                // 静默投递：仍然出现在通知栏（用户切回来看得见），但不打断用户。
                // 必须放在 setOnlyAlertOnce 之后 —— 两者互不覆盖，但排在一起读着清楚。
                if (silent) setSilent(true)
            }
            .build()

        nm.notify(allocation.id, notification)
    }

    /**
     * 发一条**自定义文案**的「这一轮没跑成」通知（R056 缺口 5）。
     *
     * ## 为什么单独一个方法而不是给 [postDone] 加参数
     * [postDone] 的两个布尔（`failed` / `silent`）已经承载了「跑完 / 跑挂 / 静默」
     * 三种组合，再加一组任意标题正文会让它的分支判断变成四不像。而这类通知
     * 与「任务结束」**只在文案上不同**，其余语义完全一样：
     *
     * - 用同一个 id（[idFor]）：同一会话先「跑完」再「被截断」时原地替换，
     *   而不是在通知栏里堆两条说同一轮的通知；
     * - 每次都响（`setOnlyAlertOnce(false)`）：这一轮没跑成，用户必须知道，
     *   不能因为上一轮响过就静默——这正是 R056 里失败通知踩过的坑；
     * - 点开进同一个会话，自动撤销。
     *
     * @param silent 目前恒为 `false`（这些结局都属于「必须提醒」）。留着参数是为了
     *   将来若想对「用户正看着的会话」也静默投递时不必再改签名。
     */
    fun postAttention(
        sessionId: String,
        title: String,
        text: String,
        silent: Boolean = false,
    ) {
        val nm = manager ?: return
        val allocation = idFor(sessionId)
        for (stale in allocation.evicted) nm.cancel(stale)

        val notification = NotificationCompat.Builder(context, NoticeChannels.DONE)
            .setSmallIcon(R.drawable.ic_notice_done)
            .setColor(ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(sessionId))
            .setAutoCancel(true)
            // 同 postDone 的失败侧：这类结局每一轮都要响。
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(lockScreenVisibility())
            .apply { if (silent) setSilent(true) }
            .build()

        nm.notify(allocation.id, notification)
    }

    /**
     * 发一条「请求授权」，带**批准 / 拒绝**两个动作（用户 m08797 明确要求）。
     *
     * 与 [postDone] 的三处不同，都是有意的：
     * 1. **每条请求一个独立 id**（按 `eventId`），不用会话 id —— 同一个会话可能同时
     *    有两条不同的工具请求，合并会丢一条，而丢一条就是任务永久卡住。
     * 2. **不 autoCancel**：用户可能想连着点两次横幅确认（比如误触滑掉再回来）。
     *    真正的移除时机是「回答成功」或「请求已被服务端撤回」，由协调器调 [cancelAsk]。
     * 3. **不 setOnlyAlertOnce**：每一条都是独立的、必须看见的请求。
     */
    fun postAsk(request: ApprovalRequest) {
        val nm = manager ?: return
        val id = askId(request.eventId)
        trackAsk(request)
        val text = NoticeText.askText(request)

        val notification = NotificationCompat.Builder(context, NoticeChannels.ASK)
            .setSmallIcon(R.drawable.ic_notice_ask)
            .setColor(ACCENT_COLOR)
            .setContentTitle(NoticeText.askTitle(request))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(request.agentId))
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(lockScreenVisibility())
            .addAction(
                0,
                ACTION_ALLOW,
                answerIntent(request.eventId, allow = true, noticeId = id),
            )
            .addAction(
                0,
                ACTION_DENY,
                answerIntent(request.eventId, allow = false, noticeId = id),
            )
            .build()

        nm.notify(id, notification)
    }

    /** 撤掉某条审批通知（回答成功、或请求已消失时）。 */
    fun cancelAskByEventId(eventId: String) {
        postedAsks.remove(eventId)
        manager?.cancel(askId(eventId))
    }

    /**
     * 发一条「模型有问题要问你」。
     *
     * ## 为什么它和审批同频道、同重要性、同样每条独立 id
     * `user-questions/request` 与 `approval/request` 在
     * `API_REMOTE_FORWARDED_EVENTS` 里**都是 `mode: 'waterfall'`**：服务端把请求抛给
     * 第一个愿意回答的远程监听者，然后**等**。没人答就是 `NO_PROVIDER`
     * （`no user-questions answerer accepted the request`）——任务直接失败，
     * 而且失败原因对用户毫无信息量。所以它必须和审批一样能打断用户。
     *
     * 不再单独开第三个频道：频道是给用户**分组静音**用的。用户想「别在干活时烦我」
     * 时会去关「需要确认」——提问和审批在他眼里就是同一件事（模型停下来等钱/等信息）。
     * 拆成两个频道反而会让他关了一个以为安静了、结果另一个还在响。
     *
     * ## 没有动作按钮
     * 审批可以只靠「批准 / 拒绝」两个词完成，适合在通知栏直接点；
     * 而提问的答案是选项列表或自由文本，通知栏里放不下，硬塞一个「同意」
     * 会把多选题变成默认选第一项。所以点通知主体进会话，由会话页的交互卡片处理。
     *
     * @param eventId waterfall 事件 id，用作通知 id 的稳定哈希（同一条请求重发落同一格）。
     * @param sessionId 点通知要跳进的会话。
     */
    fun postQuestion(eventId: String, sessionId: String?, first: AskQuestion?, count: Int) {
        val nm = manager ?: return
        val id = questionId(eventId)
        val text = NoticeText.questionText(first)

        val notification = NotificationCompat.Builder(context, NoticeChannels.ASK)
            .setSmallIcon(R.drawable.ic_notice_ask)
            .setColor(ACCENT_COLOR)
            .setContentTitle(NoticeText.questionTitle(first, count))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(sessionId))
            // 不 autoCancel：用户在通知栏划掉它，问题并不会因此不存在——
            // 任务还在那里等。真正的移除时机是「已回答」或「请求已消失」，
            // 由协调器对账后调 [cancelQuestionByEventId]。
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(lockScreenVisibility())
            // 每条提问都是独立事件，不能因为「这个会话刚响过」就被静默。
            .setOnlyAlertOnce(false)
            .build()

        nm.notify(id, notification)
    }

    /** 撤掉某条提问通知（已回答、或请求已消失）。 */
    fun cancelQuestionByEventId(eventId: String) {
        manager?.cancel(questionId(eventId))
    }

    /**
     * 提问通知的 id。
     *
     * 与审批**共用 `ASK_ID_BASE` 段但用不同哈希源**会撞车，所以这里加一个固定偏移
     * 把两段错开——碰撞的后果是「一条提问把一条审批盖掉」，而那条审批背后是一个
     * 正在等授权的任务。宁可多花 65536 个 id 也不用一个可能撞的方案。
     */
    private fun questionId(eventId: String): Int =
        QUESTION_ID_BASE + (eventId.hashCode() and ASK_ID_MASK)

    /**
     * 发过通知、还没撤掉的那些请求，按 `eventId` 索引。
     *
     * 用途只有一个：回答失败时把同一条通知**原地重绘**成失败提示（见 [postAskFailed]）。
     * `notify()` 是整条替换，不能只更新正文，所以重绘时必须能拿到原请求的
     * `agentId`（拼 contentIntent）与 `toolName`（拼标题）。
     *
     * 有界：超过 `ASK_TRACK_CAPACITY` 条时按插入顺序丢最旧的。审批是 waterfall，
     * 同时挂着的通常只有一两条；留 32 格纯粹是为了「用户连续点了很多条又都没答成」
     * 这种异常路径下不至于无界增长。
     */
    private val postedAsks = LinkedHashMap<String, ApprovalRequest>()

    /**
     * 回答没能送出去：把那条通知换成「送出失败」的说明，并保留按钮以便重试。
     *
     * ## 为什么必须有这个
     * [NotifyCoordinator.syncApprovals] 的对账只在「请求从 `approvals` 列表消失」时撤通知。
     * 而回答失败的语义恰恰是**请求还在列表里**——于是通知原样挂着、按钮原样能点，
     * 用户点第二次、第三次都是同样的沉默，他唯一能得到的结论是「这个按钮坏了」。
     * 真实情况是连接断了或服务端拒了，任务正在那里等。
     *
     * ## 为什么原地替换而不是再发一条
     * 同一条请求发两条通知会让用户以为要批准两次。用相同 id 直接 `notify()`
     * 是原地替换（见类 KDoc 第 2 条）；配合 `setOnlyAlertOnce(true)`，
     * 这次失败更新不会再响一声。
     *
     * @param reason 给用户看的失败原因（已由调用方译成中文），空串时用兜底文案。
     */
    /**
     * 提问的回答没能送出去：把**那条提问通知**换成失败说明，并保留选项按钮。
     *
     * ## 为什么不能复用 [postAskFailed]（R059 审查 P2-2）
     * `postAskFailed` 内部用 `askId(eventId)` 算通知 id，而提问卡片走的是
     * `questionId(eventId)`（`QUESTION_ID_BASE` 段）。从流体云卡片点选项失败时
     * 若调它，会：① 在审批段凭空造出一条孤儿通知；② `postedAsks[eventId]` 查不到
     * 原请求（那个 map 只记审批），于是走不到「保留按钮 + 原地重绘」分支；
     * ③ 原来那张提问卡片既没更新也没撤销，仍然摆着能点的选项。
     * 用户在通知栏会同时看到两张卡片，点旧的那张还是同一句沉默。
     *
     * 所以这里单独寻址、单独重绘，语义与审批那条一致：**同一 id 原地替换**。
     *
     * @param eventId waterfall 事件 id（决定通知 id）。
     * @param options 原提问的选项标签，用于重建按钮；为空时不放按钮，只留正文。
     */
    fun postQuestionFailed(
        eventId: String,
        sessionId: String?,
        questionId: String,
        callId: String?,
        options: List<AskOption>,
        reason: String,
    ) {
        val nm = manager ?: return
        val id = questionId(eventId)
        val text = reason.ifBlank { ASK_FAILED_FALLBACK }
        val builder = NotificationCompat.Builder(context, NoticeChannels.ASK)
            .setSmallIcon(R.drawable.ic_notice_ask)
            .setColor(ACCENT_COLOR)
            .setContentTitle(ASK_FAILED_TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(sessionId))
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(lockScreenVisibility())
            // 失败更新不该再响一次——用户刚刚就在看这条通知。
            .setOnlyAlertOnce(true)
        // 按钮保留：连接恢复后再点一次是有效动作。复用胶囊那套 PendingIntent 构造，
        // 保证「通知栏这条」与「胶囊上那个」按下去是同一个动作。
        options.take(CAPSULE_OPTION_ACTIONS).forEach { option ->
            builder.addAction(
                0,
                option.label,
                CapsuleActionIntents.answerOption(
                    context = context,
                    eventId = eventId,
                    callId = callId.orEmpty(),
                    questionId = questionId,
                    label = option.label,
                ),
            )
        }
        nm.notify(id, builder.build())
    }

    fun postAskFailed(eventId: String, reason: String) {
        val nm = manager ?: return
        val id = askId(eventId)
        val text = reason.ifBlank { ASK_FAILED_FALLBACK }
        val original = postedAsks[eventId]
        val builder = NotificationCompat.Builder(context, NoticeChannels.ASK)
            .setSmallIcon(R.drawable.ic_notice_ask)
            .setColor(ACCENT_COLOR)
            .setContentTitle(ASK_FAILED_TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(lockScreenVisibility())
            // 失败更新不该再响一次——用户刚刚就在看这条通知。
            .setOnlyAlertOnce(true)
        if (original != null) {
            // 按钮保留：连接恢复后再点一次是有效动作，撤掉按钮等于逼用户进 App。
            builder.setContentIntent(openSessionIntent(original.agentId))
            builder.addAction(0, ACTION_ALLOW, answerIntent(eventId, allow = true, noticeId = id))
            builder.addAction(0, ACTION_DENY, answerIntent(eventId, allow = false, noticeId = id))
        }
        nm.notify(id, builder.build())
    }

    /**
     * 锁屏上这条通知显示到什么程度。
     *
     * 关掉「锁屏显示详细内容」时用 `VISIBILITY_SECRET`：系统在锁屏上把内容区整个遮住，
     * 只留 App 名与时间——这正是用户勾掉那个开关时想表达的意思（会话标题可能是
     * 公司代码或私事，旁边站着别人时不该露出来）。
     *
     * 开着时用 `VISIBILITY_PRIVATE` 而不是 `VISIBILITY_PUBLIC`：两者在用户已解锁时
     * 看起来一样，区别只在锁屏上——`PUBLIC` 会把全文摊在锁屏上，而这类通知的价值
     * 是「看一眼知道哪条跑完了」，不需要连失败原因一起摊开。
     *
     * 每条通知都要单独设：`vis` 是逐条通知的属性，不跟着频道走。
     * 这个开关曾经只被存下来、没有任何代码作用到通知上（全项目零处 setVisibility），
     * 表现是「开关拨了没反应」——设置页里一个假开关比没有这个开关更糟。
     */
    private fun lockScreenVisibility(): Int =
        if (settings.lockScreenDetail) {
            NotificationCompat.VISIBILITY_PRIVATE
        } else {
            NotificationCompat.VISIBILITY_SECRET
        }

    /** 记下一条已发通知的请求，供 [postAskFailed] 重绘用。 */
    private fun trackAsk(request: ApprovalRequest) {
        postedAsks.remove(request.eventId)
        postedAsks[request.eventId] = request
        while (postedAsks.size > ASK_TRACK_CAPACITY) {
            val oldest = postedAsks.keys.first()
            postedAsks.remove(oldest)
        }
    }

    /**
     * 撤掉本应用发过的**所有**通知，并清空两本账。
     *
     * 只在「换掉了整台主机」时调（退配对、切到另一台设备）：那些通知背后是上一台
     * 机器上的会话，id 在这台机器上没有任何意义——点进去只会得到「会话不存在」，
     * 审批按钮更是会把回答发给一台根本不相干的机器。
     *
     * 用 `cancelAll()` 而不是逐个 id 撤：池子只记得最多 [ID_CAPACITY] 条，
     * 更早被淘汰的那些 id 已经不在账上了，逐个撤会漏。`cancelAll` 是本应用维度的，
     * 不会碰别的应用。
     */
    fun cancelAll() {
        manager?.cancelAll()
        idPool.clear()
        postedAsks.clear()
    }

    /** 撤掉某会话的「任务结束」通知（用户点进那个会话了，已经看见了）。 */
    fun cancelDone(sessionId: String) {
        val id = idPool.release(DONE_KEY_PREFIX + sessionId) ?: return
        manager?.cancel(id)
    }

    /**
     * 发一条「连不上桌面端了」（R056 缺口 3）。
     *
     * 固定 id [LINK_ID]、**不进池子**（理由见该常量的 KDoc）。用
     * `setOnlyAlertOnce(true)`：断线期间这条会因为重试被反复刷新（每次退避失败
     * 都可能重新走一遍判定），但用户只需要知道一次，反复响只会让他把这个频道关掉。
     *
     * 不带 `setAutoCancel(true)`：它不是「点开就没事了」的通知——点开会话并不能
     * 让连接回来。撤掉它的唯一途径是真的连上（见 [cancelLink]）。
     */
    fun postLink(offline: Boolean, text: String, title: String, openSettings: Boolean = false) {
        val nm = manager ?: return
        val notification = NotificationCompat.Builder(context, NoticeChannels.DONE)
            .setSmallIcon(R.drawable.ic_notice_done)
            .setColor(if (offline) WARN_COLOR else ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // 带会话 id 是「点开去看那条会话」，不带是「点开进首页」；
            // 「后台连接断了」这条要落到设置页那一行，所以第三种：标记 openSettings。
            .setContentIntent(openSessionIntent(null, openSettings))
            .setOngoing(offline)
            // 恢复那条点开就该消失；「连不上」那条不能，理由见上面的 KDoc。
            .setAutoCancel(!offline)
            // ⚠️ 恢复那条必须重新响，不能沿用 `true`。
            //
            // 两条用的是同一个 `LINK_ID`，而 `setOnlyAlertOnce(true)` 的语义是
            // 「这条通知已经响过一次，之后的更新只静默改文字」。于是「连不上」
            // （响过）之后的「已经连回来了」在通知栏里文字变了、**一声不吭**——
            // 而用户最想知道的就是「恢复了没有」。这与 `postDone` 失败侧当年踩的
            // 是同一个坑，只是那次没人把修复带到这里。
            .setOnlyAlertOnce(offline)
            .setCategory(if (offline) Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
            .setVisibility(lockScreenVisibility())
            .build()
        nm.notify(LINK_ID, notification)
    }

    /** 撤掉「连不上」通知。 */
    fun cancelLink() {
        manager?.cancel(LINK_ID)
    }

    /**
     * R057：后台连接被系统停掉、且用户关掉了「自动恢复」时发的那条提醒。
     *
     * ## 为什么不复用 [postLink]
     * 第三轮审查 P2-2。`postLink` 固定用 [NoticeChannels.DONE]（任务结束）频道，
     * 而这条通知讲的是**后台连接服务**，与「任务跑完」是两件事：
     *
     * 1. **语义错位**：用户关掉「任务结束」频道，理由通常是「我不想每跑一轮就响一声」，
     *    不代表他不要「后台连接掉了」这种运维级提醒。塞进同一个频道，等于让一个
     *    与它无关的偏好顺手把它静音。
     * 2. **可见性没有保证**：`postLink` 全程不查频道是否被关。而 [NoticeChannels.SERVICE]
     *    是 MIN 重要性、默认折叠，用户关掉它的概率明显更高——若不检查，这条提醒
     *    会被系统静默丢弃，`planRecovery` 返回的 `NotifyUser` 就成了空操作。
     *
     * 所以这里单开一条：**用自己的 id**（与 [LINK_ID] 错开，两者可以同时成立）、
     * **走 SERVICE 频道**、**发之前先查该频道没被关**。
     *
     * ## 为什么不用 `setOngoing`
     * 它不是「现在就进来看」而是「服务没了，你可能想知道」：用户看完就该能划掉。
     * 真要让服务回来，开关就在设置页那一行，点开即达。
     */
    fun postServiceStopped(title: String, text: String) {
        val nm = manager ?: return
        // 频道被用户关掉时宁可不发：发了也是被系统丢弃，还白白让调用方以为通知到了。
        if (!isChannelEnabled(NoticeChannels.SERVICE)) return
        val notification = NotificationCompat.Builder(context, NoticeChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_notice_service)
            .setColor(WARN_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSessionIntent(null, openSettings = true))
            .setAutoCancel(true)
            // 🔴 `setOnlyAlertOnce(true)` 不是省事，是必需的（第六轮审查 P2）。
            //
            // 第六轮把 `ensureRunning()` 接进了「每次回前台」（`onAppForeground`）与
            // 「拿到通知权限」，调用频率从「连接状态变化时」变成「每次切回 App」。
            // 而「自动恢复」关着时它会走 `RecoveryAction.NotifyUser` 分支发这一条。
            // 没有这一句，用户划掉提醒、切出去再切回来，同一条通知就会被**重新投递**——
            // 反复重建状态栏条目，看起来像功能在抽风。
            //
            // 用 `setOnlyAlertOnce` 而不是在外面记「这次死亡已提示过」：通知 id 固定，
            // 系统本来就按 id 覆盖；`onlyAlertOnce` 让它安静地覆盖而不是重新弹出。
            // 用户真要看原因，点进去就是设置页。
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(lockScreenVisibility())
            .build()
        nm.notify(SERVICE_STOPPED_ID, notification)
    }

    /** 撤掉「后台连接已被停掉」那条提醒。 */
    fun cancelServiceStopped() {
        manager?.cancel(SERVICE_STOPPED_ID)
    }

    /**
     * 发一条账号提醒（R056 缺口 4）：账号掉线 / 某个模型要重新登录 / 已经恢复。
     *
     * ## 为什么固定 id、不进池子
     * 与 [postLink] 同理，「账号要不要重登」是**整个桌面端唯一的一件事**：
     * 同时有三条会话因为这个原因失败，通知栏里也不需要三条一样的提醒。
     * 固定 id 让后一条原地替换前一条，用户左滑一次就清干净。
     *
     * 但与连接提醒**用不同的 id**：连接断了和账号掉了是两个独立的问题，
     * 可能同时成立（一个共用一个槽位会互相顶掉，用户只能看到后发生的那个）。
     *
     * ## 为什么不用 `setOngoing`
     * 它不像「连不上」那样有明确的生命周期：用户看到之后可能在桌面上登录，
     * 也可能先去干别的。钉死在通知栏里没有必要，`setAutoCancel(true)` 更合适。
     */
    fun postAccount(title: String, text: String, resolved: Boolean = false) {
        val nm = manager ?: return
        val notification = NotificationCompat.Builder(context, NoticeChannels.DONE)
            .setSmallIcon(R.drawable.ic_notice_done)
            .setColor(if (resolved) ACCENT_COLOR else WARN_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            // 「已经恢复了」不是错误。混进 ERROR 分类会被系统的勿扰/排序规则区别对待，
            // 而且用户看到的是一张卡片标题写着「账号已经恢复了」、分类却是 error。
            .setCategory(if (resolved) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
            .setVisibility(lockScreenVisibility())
            .build()
        nm.notify(ACCOUNT_ID, notification)
    }

    /** 撤掉账号提醒（用户已经处理掉了，或者我们确认它不再成立）。 */
    /** 一条审批通知的 id。用 `eventId` 的稳定哈希——同一条请求重发也落在同一格。 */
    private fun askId(eventId: String): Int = askNoticeId(eventId)

    /**
     * 点通知主体 → 打开那个会话。
     *
     * `Intent` 带上 `sessionId`，由 `MainActivity` 的 `onNewIntent` 消费
     * （`launchMode="singleTop"`，否则每次点通知都会叠一个新的 Activity，
     * 用户的返回栈会被塞满重复的首页）。
     *
     * 用 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`：前者让同一个会话的重复通知
     * 共用一条 PendingIntent（否则 extras 会停在第一次那个），后者是 31+ 的硬性要求。
     */
    private fun openSessionIntent(sessionId: String?, openSettings: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW
            // 必须带 NEW_TASK：从通知启动 Activity 时若没有它，`startActivity`
            // 会抛 `AndroidRuntimeException: Calling startActivity() from outside of
            // an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
            // 「后台连接断了」那条点开必须停在设置页那一行，否则用户被丢到首页，
            // 还得自己找「通知与后台保活」——而他刚刚点的正是那件事。
            if (openSettings) putExtra(EXTRA_OPEN_SETTINGS, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(sessionId, openSettings),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 批准/拒绝的 `PendingIntent`。
     *
     * 走 [NoticeActionReceiver] 而不是 Activity：用户点「批准」时不该被拉到前台，
     * 那会打断他手上的别的事。广播在后台把 `answer()` 发出去就行。
     */
    private fun answerIntent(eventId: String, allow: Boolean, noticeId: Int): PendingIntent {
        val intent = Intent(context, NoticeActionReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_ALLOW, allow)
            // 带上自己的通知 id：接收方回答成功后要撤掉「这一条」。
            // 不靠 eventId 重算——重算逻辑一旦改动，撤掉的会是另一条通知。
            putExtra(EXTRA_NOTICE_ID, noticeId)
            // 广播接收器在清单里注册，包名与类名都要对上；显式 component 已在
            // 构造时给出，这里再设一次包名是为了让 `Intent` 在 mux 之外也能解析。
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            // 两个动作必须用**不同**的 requestCode，否则第二个会把第一个覆盖掉，
            // 用户点「拒绝」实际执行的是「批准」——这类事故在通知动作里非常经典。
            noticeId * 2 + if (allow) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 会话 id → `PendingIntent` 的 requestCode。
     *
     * `FLAG_UPDATE_CURRENT` 是按 (requestCode, Intent 匹配) 判重的，extras 不参与匹配，
     * 所以不同会话**必须**给不同 requestCode，否则所有通知的 contentIntent 会共用
     * 第一条的 extras，点谁都跳去同一个会话。
     */
    /**
     * 给 `PendingIntent` 分配 requestCode。
     *
     * ## 🔴 为什么不能只是 `sessionId?.hashCode() ?: 0`（R057 子代理审查 P2-7）
     *
     * `PendingIntent.getActivity` 判断「是不是同一个 PendingIntent」用的是
     * `Intent.filterEquals`，而它**只比 action / data / type / component / categories，
     * 不比 extras**。所以两个只有 extras 不同的 Intent、requestCode 又一样时，
     * 第二次会**复用同一个 PendingIntent 并覆盖 extras**——先前那条通知的点击目标
     * 就被悄悄改掉了。
     *
     * 具体到本类：[LINK_ID] 那条（`sessionId = null, openSettings = true`）与
     * [ACCOUNT_ID] 那条（`sessionId = null, openSettings = false`）原本都落在
     * requestCode 0 上。先发连接提醒、再发账号提醒 → 连接提醒的「打开设置页」
     * 被抹成「打开首页」，用户点它只会到首页，还得自己找那一行。
     *
     * 所以这里把「没有会话 id」的两种用途各占一个固定段，与会话 hash 段分开。
     */
    private fun requestCodeFor(sessionId: String?, openSettings: Boolean): Int = when {
        !sessionId.isNullOrBlank() -> sessionId.hashCode() and REQUEST_CODE_MASK
        openSettings -> REQUEST_CODE_SETTINGS
        else -> REQUEST_CODE_HOME
    }

    companion object {
        /** 「任务结束」通知 id 的起点，与审批的 id 段错开，避免互相顶掉。 */
        const val ID_BASE = 10_000

        /** 同时最多记多少条会话的完成通知。超出的按 LRU 淘汰。 */
        const val ID_CAPACITY = 32

        /**
         * 流体云打开会话用的 requestCode。
         *
         * 不能直接用 `151_080`（R059 审查 P2-6）：`requestCodeFor` 算出的会话码是
         * `sessionId.hashCode() and 0x7FFF`（0–32767），而 `151_080 and 0x7FFF == 20_008`
         * 正好落在这个值域里；两处 `PendingIntent.getActivity` 的 Intent 结构又完全一致
         * （同 action / 同组件 / 同 flags，`filterEquals` 不看 extras），配合
         * `FLAG_UPDATE_CURRENT` 就会互相覆盖。后果是点胶囊跳错会话——概率约 1/32768，
         * 正因为罕见才更难查。
         *
         * 这里取 0x9000，与已有的 `REQUEST_CODE_SETTINGS=0x8001` / `REQUEST_CODE_HOME=0x8002`
         * 同属高位保留区，而 `0x7FFF` 掩码永远到不了这里。
         */
        const val CAPSULE_REQUEST_CODE_OPEN = 0x9000

        /** 流体云卡片上最多放几个选项按钮（第三个位置留给「进 App」）。 */
        const val CAPSULE_OPTION_ACTIONS = 2

        /** 审批通知 id 的起点。 */
        /**
         * 某条审批通知的 id（纯函数，实例与外部共用）。
         *
         * 胶囊上那个勾/叉必须用与通知栏**完全相同**的 requestCode
         * （`noticeId * 2 + allow`）与同一份 PendingIntent，否则两处各持一份，
         * 回答完之后另一份还挂着能点（R059 审查 P2-4）。
         */
        fun askNoticeId(eventId: String): Int =
            ASK_ID_BASE + (eventId.hashCode() and ASK_ID_MASK)

        const val ASK_ID_BASE = 20_000

        /**
         * 提问通知 id 的起点。
         *
         * 与审批段**必须错开**：两者都是 waterfall，同时挂着的可能性很高；共用一段
         * 就会互相顶掉，而顶掉的那条背后是一个正在等回答的任务。取 20_000 + 0xFFFF + 1
         * 之后的整数（= 85_536），保证 `questionId()` 的结果永远落在审批段之外。
         */
        const val QUESTION_ID_BASE = 85_536

        /**
         * 连接状态通知的 id（R056 缺口 3）。
         *
         * **固定单条，不进 [NoticeIdPool]**：连接是**全局**事实，不像会话那样可以有多条。
         * 如果塞进池子，一个正在刷新的会话可能把它挤掉——恰恰在最需要它的时候
         * （连着断、又有很多会话在跑）消失。
         *
         * 取值落在完成通知段（`10_000..10_031`）、审批段（`20_000..85_535`）、
         * 提问段（`85_536..151_071`）之外的第一个空位之后，避免与任何一段撞车。
         */
        const val LINK_ID = 151_072

        /**
         * 账号提醒的固定 id。
         *
         * 紧挨着 [LINK_ID] 之后取，与它一样**不进 [NoticeIdPool]**。
         * 两者必须不同：连接断了与账号掉了可以同时成立，共用一个槽位会互相顶掉。
         */
        const val ACCOUNT_ID = 151_073

        /**
         * R057：「后台连接被系统停掉、且用户关了自动恢复」那条提醒的固定 id。
         *
         * 与 [LINK_ID] / [ACCOUNT_ID] 错开：断开连接、账号掉线、服务被停是三件独立的事，
         * 完全可能同时成立，共用一个槽位会互相顶掉，用户只能看到最后发生的那件。
         *
         * 也**不与 [com.revxzxcx.remotewebapp.core.service.ServiceNotice.NOTICE_ID]
         * （151_074）相同**：那条是前台服务的常驻状态条，生命周期完全不同。
         * 取 151_076 而不是 151_075，是为了避开 `ServiceNotice.SETTINGS_REQUEST_CODE`
         * ——两者分属「通知 id」与「PendingIntent requestCode」两个命名空间，技术上不冲突，
         * 但同号会让日后排查多一次无用功。
         */
        const val SERVICE_STOPPED_ID = 151_076

        /** 审批 id 的取值范围掩码（`20_000..20_000+0xFFFF`，与完成通知段不重叠）。 */
        const val ASK_ID_MASK = 0xFFFF

        /** 跳转用的 requestCode 掩码。 */
        const val REQUEST_CODE_MASK = 0x7FFF

        /**
         * 「点通知打开设置页」那条的 requestCode。
         *
         * 与会话 hash 段（`0..0x7FFF`，见 [REQUEST_CODE_MASK]）**不能重叠**：
         * 它与 [LINK_ID] 绑定，一旦和某条会话通知撞上，`FLAG_UPDATE_CURRENT`
         * 会让后发的把先发的 extras 覆盖掉（见 [requestCodeFor] 的 KDoc）。
         */
        const val REQUEST_CODE_SETTINGS = 0x8001

        /** 「点账号提醒回首页」那条的 requestCode；理由同 [REQUEST_CODE_SETTINGS]。 */
        const val REQUEST_CODE_HOME = 0x8002

        /** `Intent` 里带会话 id 的键；`MainActivity` 与这里必须一致。 */
        const val EXTRA_SESSION_ID = "com.revxzxcx.remotewebapp.extra.SESSION_ID"

        /**
         * 点通知进 App 的那个 action；`MainActivity.dispatchIntent` 与这里必须一致。
         *
         * 用自定义 action 而不是系统的 `Intent.ACTION_VIEW`：后者语义是「查看某个 URI」，
         * 系统里任何一处发出 `ACTION_VIEW`（且没有明确 component）都可能被我们的 Activity
         * 接住；自定义 action 只会由我们自己的 `PendingIntent` 产生。
         */
        const val ACTION_VIEW = "com.revxzxcx.remotewebapp.notify.VIEW"

        /** 通知动作广播的 action。 */
        const val ACTION_ANSWER = "com.revxzxcx.remotewebapp.notify.ANSWER"

        /** 广播里带 `eventId` 的键。 */
        const val EXTRA_EVENT_ID = "com.revxzxcx.remotewebapp.extra.EVENT_ID"

        /** 广播里带「允许/拒绝」的键。 */
        const val EXTRA_ALLOW = "com.revxzxcx.remotewebapp.extra.ALLOW"

        /** 广播里带通知 id 的键。 */
        const val EXTRA_NOTICE_ID = "com.revxzxcx.remotewebapp.extra.NOTICE_ID"

        /**
         * 点通知「进设置页」的标记（R057）。
         *
         * 与 [EXTRA_SESSION_ID] 并列而不是复用它：两者的消费方不同——会话 id 要打开
         * 某条对话，这个标记要打开设置页并滚到「后台连接」那一行。混用一个键会让
         * `AppRoot` 里那段路由判断变成「字符串非空就开会话」，而它根本不是会话 id。
         */
        const val EXTRA_OPEN_SETTINGS = "com.revxzxcx.remotewebapp.extra.OPEN_SETTINGS"

        /**
         * 「退出后台连接」的广播 action（R057）。
         *
         * 与 [ACTION_ANSWER] 分开：那个要带 `eventId` 去回答一条 waterfall 请求，
         * 这个什么参数都不带、只让服务停下来。合成一个 action 会让接收器里
         * 「有没有 eventId」变成决定行为的分叉，读起来极易看错。
         */
        const val ACTION_EXIT_SERVICE = "com.revxzxcx.remotewebapp.notify.EXIT_SERVICE"

        /**
         * 胶囊卡片「选这个选项」按钮的广播 action（R059）。
         *
         * 与 [ACTION_ANSWER] 分开而不是复用：审批的回答是「允许/拒绝」二值，
         * 拾取靠 `EXTRA_ALLOW`；提问的回答是业务值 `{answers:[{id, selected}]}`，
         * 需要 callId + questionId + 选项标签三样。塞进同一个 action 会让接收器里
         * 「带没带 ALLOW」变成决定走审批还是走提问的分叉，而这两个分支的失败处理完全不同。
         */
        const val ACTION_CAPSULE_OPTION =
            "com.revxzxcx.remotewebapp.notify.CAPSULE_OPTION"

        /** 胶囊选项广播里带题组 `callId` 的键。 */
        const val EXTRA_CALL_ID = "com.revxzxcx.remotewebapp.extra.CALL_ID"

        /** 胶囊选项广播里带单题 `questionId` 的键。 */
        const val EXTRA_QUESTION_ID = "com.revxzxcx.remotewebapp.extra.QUESTION_ID"

        /** 胶囊选项广播里带被选中的选项原文的键。 */
        const val EXTRA_OPTION_LABEL = "com.revxzxcx.remotewebapp.extra.OPTION_LABEL"

        /**
         * 胶囊选项按钮 requestCode 的高位标记。
         *
         * 与审批用的低位码段错开，否则「同一条请求上的第一个选项」和
         * 「这条请求的批准按钮」会算出同一个码——`PendingIntent` 按
         * `(requestCode, filterEquals)` 判身份，撞码且 Intent 恰好等价时后建的
         * 会顶掉先建的，表现为「点了选项，结果批准了」。
         */
        const val CAPSULE_OPTION_CODE_BIT = 0x4000

        /** 同 id 原地重绘时最多记多少条原请求。 */
        private const val ASK_TRACK_CAPACITY = 32

        /** 回答失败的标题。 */
        const val ASK_FAILED_TITLE = "授权没能送出"

        /** 回答失败且没给原因时的兜底正文。 */
        const val ASK_FAILED_FALLBACK = "检查网络后重试，或直接进 App 处理。"

        /** 「批准」动作的按钮文案。 */
        const val ACTION_ALLOW = "批准"

        /** 「拒绝」动作的按钮文案。 */
        const val ACTION_DENY = "拒绝"

        /** id 池的 key 前缀：完成通知按会话记。 */
        const val DONE_KEY_PREFIX = "done:"

        /** 通知强调色，与主题主色一致（`#5AA2FF`）。 */
        const val ACCENT_COLOR = 0xFF5AA2FF.toInt()

        /**
         * 「连不上了」用的警示色（与原型 `--error:#FF8A80` 同色）。
         *
         * 不复用 [ACCENT_COLOR]：那套蓝是「有事发生了，来看看」，
         * 而这条说的是「你现在收不到东西」——两者在通知栏里必须一眼能分开。
         */
        const val WARN_COLOR = 0xFFFF8A80.toInt()
    }
}
