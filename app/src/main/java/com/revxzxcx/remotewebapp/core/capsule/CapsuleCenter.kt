package com.revxzxcx.remotewebapp.core.capsule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.revxzxcx.remotewebapp.MainActivity
import com.revxzxcx.remotewebapp.R
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter

/**
 * 流体云胶囊的通知发布器（R059）。
 *
 * ## 职责边界
 * 和 [NotificationCenter] 一样，这一层**不做判断**：展示谁、显示什么文案、
 * 挂哪组按钮，全部由 [CapsuleContent] / [CapsuleFocus] 这些纯函数算好。
 * 这里只负责「把 [CapsuleState] 变成一个系统真的会渲染成胶囊的 [Notification]」。
 *
 * ## 为什么不能复用 [NotificationCenter] 的频道
 * [com.revxzxcx.remotewebapp.core.notify.NoticeChannels.SERVICE] 是
 * `IMPORTANCE_MIN`，而 promoted ongoing 通知**明确不接受 IMPORTANCE_MIN 的频道**
 * （见调研文档 3.2 的官方硬性要求）。更关键的是**频道重要性创建后不可改**
 * ——已有用户的 `service` 频道永远是 MIN，唯一的出路是新建一个频道。
 *
 * 也不复用它 `ASK`/`DONE`：那两个是「事件提醒」，会被 `setAutoCancel`、
 * 每轮响一次等一套语义绑定；胶囊是**常驻状态**，两者的生命周期完全不同，
 * 混在一个频道里会让用户没法单独静音其中一个。
 *
 * ## 真机实测出来的五条硬约束（都写进了构造代码，改动前先读调研文档 3.7）
 * 1. `Segment(length)` 的参数是「这一段代表多少进度单位」而不是比例 →
 *    一律 `Segment(1)`，配合 `setProgress(total, done, false)`。
 * 2. `setUsesChronometer(true)` **会整体接管 `setContentText` 那一行** →
 *    所以「汇报 / 当前动作」两行都塞进 `setContentTitle`（中间一个换行符），
 *    `contentText` 让给计时器。
 * 3. action 上限是 **3 个**，第 4 个不渲染。
 * 4. `setSubText` 渲染在**进度条上方**、正文下方。
 * 5. 胶囊条上那行字来自 `setShortCriticalText`，**不是** `contentTitle`。
 */
internal class CapsuleCenter(private val context: Context) {

    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * 建胶囊频道。**幂等**，进程启动时调一次。
     *
     * `IMPORTANCE_LOW`：胶囊本身就不该响铃——它是一条常驻状态。
     * 真正需要打断人的审批/提问另有 [com.revxzxcx.remotewebapp.core.notify.NoticeChannels.ASK]。
     * 但**不能是 MIN**：MIN 频道会被系统判定为不符合 promoted 资格，
     * 且 MIN 会把通知折叠到最底部，用户连胶囊都看不到。
     */
    fun ensureChannel() {
        val nm = manager ?: return
        // minSdk 26，NotificationChannel 一定存在。
        val channel = NotificationChannel(
            CHANNEL_ID,
            "流体云状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "在状态栏/锁屏的流体云里显示正在进行的会话与待处理请求。"
            // 胶囊是常驻状态而不是消息，不该在桌面图标上堆角标。
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 系统是否允许我们发胶囊。
     *
     * 两层，缺一不可：
     * - **普通通知权限**：`POST_NOTIFICATIONS`（API 33+ 运行时权限）。没有它，
     *   promoted 通知连底层的普通通知都发不出去。
     * - **promoted 资格**：`POST_PROMOTED_NOTIFICATIONS` 这个 AppOp。它默认是
     *   `ignore`，只能由用户在系统设置里手动打开（零售机上 `pm grant` 与
     *   `cmd appops set` 都会被拒，见调研文档 3.7-C）。
     *
     * 两者都查的理由：只查前者会出现「通知栏有小图标但永远没有胶囊」，
     * 只查后者会在没给通知权限时以为一切正常。
     */
    fun canPost(): Boolean {
        // 低版本上胶囊整体不可用：`ProgressStyle` / `setShortCriticalText` /
        // `canPostPromotedNotifications` 全是 API 36 才有的方法，走到 build() 会
        // `NoSuchMethodError`。这里提前返回 false，让上层降级成「不发胶囊」，
        // 而不是让整个通知链路崩掉。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        if (!runtimePermissionGranted()) return false
        val nm = manager ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (!isChannelEnabled(CHANNEL_ID)) return false
        return nm.canPostPromotedNotifications()
    }

    /**
     * promoted 资格单独暴露给设置页。
     *
     * 设置页要区分「没给通知权限」和「给了通知权限但没开流体云开关」——
     * 两者的引导路径完全不同（前者是权限弹窗，后者只能跳系统设置）。
     */
    fun promotedAllowed(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            manager?.canPostPromotedNotifications() == true

    /**
     * API 36（Baklava）起才有 promoted ongoing 概念。
     *
     * 低版本上胶囊整体不可用，但**不该报错**：降级成普通通知即可，
     * 用户仍能在通知栏看到状态，只是没有状态栏胶囊。
     */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    private fun runtimePermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 频道是否可用。
     *
     * 注意**不能**在这里 `createNotificationChannel`：那会在设置页刚打开时
     * 凭空建出一个频道，而用户可能根本没开胶囊功能。
     * 频道由 [ensureChannel] 在功能启用时创建。
     */
    private fun isChannelEnabled(channelId: String): Boolean {
        val nm = manager ?: return false
        val channel = nm.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 发布/更新胶囊。
     *
     * 用**固定 id** 原地更新，不 cancel 再 notify：后者会让通知闪一下并重新计时，
     * 而胶囊是每几秒就要刷新的东西。
     *
     * @param actions 卡片底部按钮，最多 3 个（第 4 个系统直接不渲染）。
     * @return 是否真的发出去了。返回 false 时调用方不该认为「已经在显示了」。
     */
    fun post(state: CapsuleState, actions: List<CapsuleAction> = emptyList()): Boolean {
        val nm = manager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        if (!canPost()) return false

        val notification = build(state, actions)
        return try {
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // 权限在两次检查之间被收回（用户在设置里刚关掉）。不谎报成功。
            Log.w(TAG, "capsule notify rejected", e)
            false
        }
    }

    /** 撤掉胶囊。没有发过也安全。 */
    fun cancel() {
        manager?.cancel(NOTIFICATION_ID)
    }

    /**
     * 把快照拼成通知。
     *
     * `Notification.Builder` 在纯 JVM 单测里是 `Method not mocked`，所以这段不被
     * JVM 单测覆盖——真正被覆盖的是 [CapsuleContent]（文案）与 [CapsuleFocus]（选谁）。
     * 这一层的正确性靠**真机验证**，不是单测。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun build(state: CapsuleState, actions: List<CapsuleAction>): Notification {
        // B018：标题两行各自独立，缺一行就整行省略。拼装逻辑抽在 `capsuleTitle` 里，
        // 因为 `build()` 是 private + @RequiresApi(36)，单测够不着（变异验证实测存活过）。
        val title = capsuleTitle(
                report = state.report,
                action = state.action,
                fallback = state.title,
                thinking = state.thinking,
            )

        val builder = Notification.Builder(context, CHANNEL_ID)
            // 图标必须跟着状态走（R059-B016）：之前这里固定 `ic_capsule`，
            // 于是「执行中 / 待批准 / 待回答 / 已完成 / 出错」在状态栏上是同一个
            // 三条横线，用户在原型里看到的勾、手、问号、叹号一个都没出现。
            // 真机上这比「没图标」更糟：图标承诺了「能看出状态」，实际却看不出来。
            .setSmallIcon(state.iconRes())
            .setContentTitle(title)
            // 胶囊条上那行字（见类 KDoc 第 5 条）。不设它，状态栏上只有一个图标。
            .setShortCriticalText(state.capsuleText)
            .setContentIntent(openSessionIntent(state.sessionId))
            .setDeleteIntent(deleteIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 计时器只在 Running 相位才有意义。
            //
            // 一旦胶囊对 Done / Failed 也可见（第三轮修复后就是如此），无脑开计时器会出现
            // 「已完成」旁边的时间还在 00:01、00:02 地涨，一直涨到用户点开——语义正好反了。
            // 所以结算相位只显示一个静态时间戳（setShowWhen(true) + setWhen(startedAt)），
            // 不再挂 chronometer。
            .setShowWhen(true)
            .setWhen(state.startedAt)
            .setUsesChronometer(state.phase == CapsulePhase.Running)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // 步骤行（渲染在进度条上方，见类 KDoc 第 4 条）。
        state.step?.let { builder.setSubText(it) }

        // 进度条：每段长度恒为 1（见类 KDoc 第 1 条）。
        val progress = state.progress
        if (progress != null && progress.drawable) {
            val style = Notification.ProgressStyle().setStyledByProgress(false)
            val done = progress.safeDone
            // 每段显式配色（R059-B013）。
            //
            // 不设 `setColor` 时颜色完全由系统主题决定：真机上「已完成」的段落
            // 与「未完成」的段落是同一个灰，进度条看上去整条不动——用户说
            // 「已经完成的任务进度条不会变成绿色」就是这个。
            // 颜色必须是**每段各自设**，`ProgressStyle` 没有全局前景色入口。
            repeat(progress.total) { index ->
                style.addProgressSegment(
                    Notification.ProgressStyle.Segment(1)
                        .setColor(if (index < done) SEGMENT_DONE_COLOR else SEGMENT_TODO_COLOR),
                )
            }
            style.setProgress(done)
            builder.setStyle(style)
            builder.setProgress(progress.total, done, false)
        }

        // 动作按钮：最多 3 个，超出直接丢弃（系统本来也不渲染）。
        actions.take(MAX_ACTIONS).forEach { action ->
            val icon = action.iconRes?.let { Icon.createWithResource(context, it) }
            builder.addAction(
                Notification.Action.Builder(icon, action.title, action.intent(context)).build(),
            )
        }

        // promoted 资格的开关。
        //
        // 官方文档给的名字是 `EXTRA_REQUEST_PROMOTED_ONGOING`，但它在 API 36 的
        // `android.jar` 里**没有公开常量**（`javap` 查不到），只能写字面量。
        // 真机 dumpsys 里能看到 `android.requestPromotedOngoing=Boolean (true)`，
        // 证明这个 key 就是系统实际读的那个（调研文档 3.7-B）。
        builder.addExtras(
            Bundle().apply { putBoolean(KEY_REQUEST_PROMOTED_ONGOING, true) },
        )

        return builder.build()
    }

    /** 点卡片主体 → 打开那个会话。复用 [NotificationCenter] 的 action/extras 约定。 */
    private fun openSessionIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = NotificationCenter.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationCenter.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            // 必须用高位保留码段：`151_080 and 0x7FFF == 20_008` 落在会话码段内，
            // 而两处 getActivity 的 Intent 结构一致（filterEquals 不看 extras），
            // 配合 FLAG_UPDATE_CURRENT 会互相覆盖 → 点胶囊跳进错误的会话。
            NotificationCenter.CAPSULE_REQUEST_CODE_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 用户从通知栏划掉胶囊。
     *
     * 官方文档要求必须有：用户临时隐藏实时更新时，应用应当知道而不该继续以为
     * 「他看得见」。这里走一个空广播，由协调器决定要不要重新发。
     */
    private fun deleteIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_DELETE,
        Intent(context, CapsuleDismissReceiver::class.java).apply {
            action = ACTION_DISMISS
            setPackage(context.packageName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 卡片底部的一个按钮。
     *
     * ## 两种形态（都真机验证过）
     * - **图标按钮**：`label = null` + 给 [iconRes]。真机实测空标题会渲染成纯图标按钮，
     *   不会留一块空白占位。用于「批准 / 拒绝」这类含义明确的动作。
     * - **文字按钮**：`label = "选项文案"` + `iconRes = null`。真机实测渲染成文字胶囊，
     *   两个中文选项并排各占一半宽，中英文都不截断。用于「待回答」的选项。
     *
     * @param intent 必须是**每次重新构造**的工厂而不是现成的 PendingIntent：
     *   同一个 PendingIntent 复用在多个 action 上会让所有按钮干同一件事。
     */
    internal class CapsuleAction(
        private val label: String?,
        internal val iconRes: Int?,
        private val intentFactory: (Context) -> PendingIntent,
    ) {
        /** action 的标题；图标按钮必须是空串（不是 null）。 */
        internal val title: String get() = label.orEmpty()

        internal fun intent(context: Context): PendingIntent = intentFactory(context)
    }

    companion object {

        /**
         * 频道 id。
         *
         * 与 `NoticeChannels` 的三个刻意不同（见类 KDoc）：这是一条独立的
         * 「常驻状态」频道，用户可以单独关掉它而不影响任务提醒。
         */
        const val CHANNEL_ID: String = "capsule"

        /**
         * 通知 id。
         *
         * **只有一个**：真机实测多条 promoted 通知不会变成多个胶囊，系统只给一个
         * 展示位并轮播（调研文档 3.7-A）。所以焦点切换时原地更新这一条，
         * 而不是再发一条。
         *
         * 取值避开 [NotificationCenter] 的 `10_000..~` 与提问段
         * `85_536..151_071`，以及 `151_072..151_076` 那几个单例 id。
         */
        const val NOTIFICATION_ID: Int = 151_080

        /**
         * 划掉胶囊时的取消广播。
         *
         * 同样必须在高位保留段：它走 `getBroadcast`、action 也与会话通知不同，
         * 但仍与前一条保持同一约定，避免以后有人把两者合并时踩坑。
         */
        private const val REQUEST_CODE_DELETE: Int = 0x9001

        /** 系统最多渲染 3 个 action（真机实测，第 4 个直接不出现）。 */
        const val MAX_ACTIONS: Int = 3

        const val ACTION_DISMISS: String =
            "com.revxzxcx.remotewebapp.capsule.DISMISS"

        /**
         * 申请 promoted ongoing 资格的 extras key。
         *
         * `android.jar` 里没有公开常量（`Notification.EXTRA_REQUEST_PROMOTED_ONGOING`
         * 编译期 Unresolved），只能写字面量。真机 dumpsys 已证实这是系统读的 key。
         */
        internal const val KEY_REQUEST_PROMOTED_ONGOING: String = "android.requestPromotedOngoing"

        /**
         * 已完成段的颜色（R059-B013）。
         *
         * 与原型 `docs/R059-流体云原型.html` 的 `--running` 同值。**必须显式给**：
         * 不设时完全由系统主题决定，真机上已完成段与未完成段同为灰色，
         * 进度条看上去整条不动——用户报的「已完成的进度条不会变成绿色」。
         */
        private val SEGMENT_DONE_COLOR: Int = 0xFF4ADE80.toInt()

        /**
         * 未完成段的颜色（R059-B013）。
         *
         * ## 为什么不用原型那个 `rgba(255,255,255,.22)`
         * 半透明白只在**深色**底上成立。`ProgressStyle.Segment.setColor` 的颜色由系统
         * 直接绘制，不保证叠在深色底上：系统切浅色主题时通知底板是白的，
         * 22% 白叠白等于与背景同色，未完成段整段消失，只剩一条短绿——
         * 那比「整条不变绿」更难理解（用户会以为总数变成了 2）。
         *
         * 所以取一个**不透明**的中性灰：它在深色底上看得见（比底色亮），
         * 在浅色底上也看得见（比底色深）。代价是不如半透明白那样贴合主题。
         */
        private val SEGMENT_TODO_COLOR: Int = 0xFF5A6472.toInt()

        internal const val TAG = "DshCapsule"
    }
}
