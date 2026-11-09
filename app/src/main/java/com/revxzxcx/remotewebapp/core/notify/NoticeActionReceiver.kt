package com.revxzxcx.remotewebapp.core.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知里「批准 / 拒绝」两个按钮的接收方（R056，用户 m08797 明确要求带动作）。
 *
 * ## 为什么是广播而不是 Activity
 * 用户点「批准」的意思是「同意继续跑」，不是「打开 App」。用 Activity 承接会把界面
 * 拉到前台，打断他手上别的事，而且从通知起的 Activity 会往返回栈里塞一层。
 *
 * ## 为什么这里只投递、不等待结果
 * `goAsync()` + `answer()` 是一次网络往返，可能几秒。广播接收器的默认时限是 10 秒
 * （前台广播）或更短，`answer()` 超时就来不及。所以这里**只把意图交给容器**，
 * 由容器在自己的 `appScope` 上跑完；接收方立刻返回。
 *
 * 代价是「点完之后通知什么时候消失」不再与「回答成功」严格同步——所以
 * [NotificationCenter.postAsk] 特意**不** autoCancel，并且这里也**不**主动 cancel：
 * 撤通知由 `NotifyCoordinator` 在「回答成功」或「请求已从列表消失」时做。
 * 如果在这里提前 cancel，回答失败时用户就看不到那条请求了，也没有任何提示。
 *
 * ## 这是导出组件吗
 * **不是**（清单里 `android:exported="false"`）。它只接自己进程内
 * `PendingIntent.getBroadcast` 发出的显式广播，外部应用不该能伪造一次「批准」。
 */
class NoticeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 「退出后台连接」（R057）：不带任何参数，只让服务停下来。
        // 与审批那条 action 分开判断，两者的载荷完全不同（见 ACTION_EXIT_SERVICE 的 KDoc）。
        if (intent.action == NotificationCenter.ACTION_EXIT_SERVICE) {
            val host = context.applicationContext as? NoticeActionHost
            // 宿主不对时也要撤掉通知条：用户刚点了「退出」，若通知还挂着他会以为没生效，
            // 而那条通知是 ongoing 的——划也划不掉。
            if (host == null) {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(com.revxzxcx.remotewebapp.core.service.ServiceNotice.NOTICE_ID)
                return
            }
            host.exitServiceFromNotification()
            return
        }

        // 胶囊卡片上「选这个选项」（R059）：载荷是 callId + questionId + 选项标签。
        // 与审批那条分开判断——两者虽然都带 eventId，但回答的内容形状完全不同。
        if (intent.action == NotificationCenter.ACTION_CAPSULE_OPTION) {
            val eventId = intent.getStringExtra(NotificationCenter.EXTRA_EVENT_ID)
            val callId = intent.getStringExtra(NotificationCenter.EXTRA_CALL_ID)
            val questionId = intent.getStringExtra(NotificationCenter.EXTRA_QUESTION_ID)
            val label = intent.getStringExtra(NotificationCenter.EXTRA_OPTION_LABEL)
            // 四样缺一不可：少 eventId 就没法把失败写回**原来那条**提问通知
            // （通知 id 由 eventId 决定，用 callId 会写到审批段去，见 P2-2）；
            // 少其余三样则组不出 `{answers:[{id, selected}]}`。
            // 与其发一条会被服务端 schema 拒绝的请求，不如什么都不做让用户进 App。
            if (eventId.isNullOrBlank() || callId.isNullOrBlank() ||
                questionId.isNullOrBlank() || label.isNullOrBlank()
            ) {
                return
            }
            val host = context.applicationContext as? NoticeActionHost ?: run {
                // 与审批分支对齐（P3-10）：宿主不对时撤掉那条通知，
                // 否则用户点着一个永远没反应的按钮反复尝试。
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(intent.getIntExtra(NotificationCenter.EXTRA_NOTICE_ID, -1))
                return
            }
            host.answerQuestionOptionFromCapsule(eventId, callId, questionId, label)
            return
        }

        if (intent.action != NotificationCenter.ACTION_ANSWER) return
        val eventId = intent.getStringExtra(NotificationCenter.EXTRA_EVENT_ID)
        if (eventId.isNullOrBlank()) return
        // 用 `hasExtra` 判而不是 `getBooleanExtra(..., false)`：后者在「extra 没带」时
        // 与「显式给了 false（拒绝）」无法区分，一次构造错误的 Intent 会被当成拒绝，
        // 而拒绝是不可撤销的（工具已经失败）。
        val allow = if (intent.hasExtra(NotificationCenter.EXTRA_ALLOW)) {
            intent.getBooleanExtra(NotificationCenter.EXTRA_ALLOW, false)
        } else {
            return
        }

        val app = context.applicationContext as? NoticeActionHost ?: run {
            // Application 不是我们期待的宿主：撤掉通知条，免得用户点着一条永远没反应的
            // 按钮反复尝试。
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(intent.getIntExtra(NotificationCenter.EXTRA_NOTICE_ID, -1))
            return
        }

        app.answerFromNotification(eventId, allow)
    }
}

/**
 * [NoticeActionReceiver] 与 `DshApplication` 之间的窄接口。
 *
 * 定义在这里而不是直接依赖 `AppContainer`：接收器只需要「替我回答一条审批」这一件事，
 * 让它能看到整个容器（凭证簿、缓存、连接）没有好处。也让测试可以只实现这一个方法。
 */
interface NoticeActionHost {

    /**
     * 代表用户回答一条审批请求。
     *
     * 必须在**应用级作用域**上执行：接收器 `onReceive` 返回后进程随时可能被收回，
     * 用接收器自己的生命周期跑不完一次网络往返。
     */
    fun answerFromNotification(eventId: String, allow: Boolean)

    /**
     * 用户在流体云卡片上点了一个选项（R059）。
     *
     * `eventId` 是必须的：回答失败时要把原因写回**原来那条提问通知**，
     * 而通知 id 由 `eventId` 决定。只给 `callId` 会写到审批段，凭空多出一条孤儿卡片
     * （R059 审查 P2-2）。
     */
    fun answerQuestionOptionFromCapsule(
        eventId: String,
        callId: String,
        questionId: String,
        label: String,
    )

    /**
     * 用户在常驻通知里点了「退出」（R057）。
     *
     * 语义是「这一次不要了」：停服务、撤通知、把「本次已退出」标记置上让自动恢复
     * 别再拉起它，但**不动开关**——用户原话是「这个按钮只影响这一次的前台服务状态，
     * 后续启动还是会启动前台服务」。
     */
    fun exitServiceFromNotification()

    /**
     * 实现方要**先校验「这道题还在等」**：通知栏里那条卡片可能已经挂了几分钟，
     * 期间用户可能在桌面端答过了。此时不该静默——失败原因要能写回卡片，
     * 与审批走同一条「没送出」通路，而且要用 `eventId` 定位**原来那条**提问通知
     * （`questionId(eventId)` 段），不是审批段。
     *
     * @param eventId 这条提问事件的 id，决定通知 id，失败时必须靠它写回。
     * @param callId 题组的 callId。
     * @param questionId 单题的 id（`AskQuestion.id`）。
     * @param label 被选中的选项原文（`AskOption.label`）。
     */
}
