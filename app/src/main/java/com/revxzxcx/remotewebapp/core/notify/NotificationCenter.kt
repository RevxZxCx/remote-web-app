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
import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
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

    /** 一条审批通知的 id。用 `eventId` 的稳定哈希——同一条请求重发也落在同一格。 */
    private fun askId(eventId: String): Int = ASK_ID_BASE + (eventId.hashCode() and ASK_ID_MASK)

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
    private fun openSessionIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW
            // 必须带 NEW_TASK：从通知启动 Activity 时若没有它，`startActivity`
            // 会抛 `AndroidRuntimeException: Calling startActivity() from outside of
            // an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(sessionId),
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
    private fun requestCodeFor(sessionId: String?): Int =
        if (sessionId.isNullOrBlank()) 0 else sessionId.hashCode() and REQUEST_CODE_MASK

    companion object {
        /** 「任务结束」通知 id 的起点，与审批的 id 段错开，避免互相顶掉。 */
        const val ID_BASE = 10_000

        /** 同时最多记多少条会话的完成通知。超出的按 LRU 淘汰。 */
        const val ID_CAPACITY = 32

        /** 审批通知 id 的起点。 */
        const val ASK_ID_BASE = 20_000

        /**
         * 提问通知 id 的起点。
         *
         * 与审批段**必须错开**：两者都是 waterfall，同时挂着的可能性很高；共用一段
         * 就会互相顶掉，而顶掉的那条背后是一个正在等回答的任务。取 20_000 + 0xFFFF + 1
         * 之后的整数（= 85_536），保证 `questionId()` 的结果永远落在审批段之外。
         */
        const val QUESTION_ID_BASE = 85_536

        /** 审批 id 的取值范围掩码（`20_000..20_000+0xFFFF`，与完成通知段不重叠）。 */
        const val ASK_ID_MASK = 0xFFFF

        /** 跳转用的 requestCode 掩码。 */
        const val REQUEST_CODE_MASK = 0x7FFF

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
    }
}
