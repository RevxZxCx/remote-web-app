package com.revxzxcx.remotewebapp.core.capsule

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.revxzxcx.remotewebapp.MainActivity
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter
import com.revxzxcx.remotewebapp.core.notify.NoticeActionReceiver

/**
 * 胶囊卡片上三个按钮各自的 [PendingIntent] 工厂（R059）。
 *
 * 单独一个对象而不是塞进 [CapsuleCenter]：工厂是纯函数（[Context] + 参数 → PendingIntent），
 * 而 `CapsuleCenter` 负责的是「怎么把通知画出来」。分开之后单测可以直接验
 * 「同一个 eventId 两次生成的 PendingIntent 相等」（`FLAG_UPDATE_CURRENT` +
 * 稳定 requestCode 的必然结果），不必造一个真的 `NotificationManager`。
 *
 * ## requestCode 必须唯一且稳定
 * `PendingIntent` 的身份是 `(requestCode, Intent filterEquals)`。两条**不同的**审批
 * 如果算出同一个 requestCode、Intent 又恰好 filterEquals，后建的会**顶掉**先建的
 * ——症状是「点了批准，结果回答的是另一条请求」。所以这里对 `eventId` 取散列，
 * 再按「批准/拒绝」错开一位（沿用 [NotificationCenter] 里 `answerIntent` 的老办法）。
 */
internal object CapsuleActionIntents {

    /** 「打开这条会话」。与现有通知走同一个 action，`MainActivity` 不必再认新的。 */
    fun open(context: Context, sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        NotificationCenter.CAPSULE_REQUEST_CODE_OPEN,
        Intent(context, MainActivity::class.java).apply {
            action = NotificationCenter.ACTION_VIEW
            putExtra(NotificationCenter.EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 「批准 / 拒绝」。复用 [NoticeActionReceiver] 现有的 `ACTION_ANSWER` 通路。
     *
     * 不新写一个接收器：那条通路已经处理了「宿主不对就撤通知」「用 `hasExtra` 区分
     * 拒绝与没带参数」这些坑，重写一遍只会漏掉其中一条。
     */
    fun answer(context: Context, eventId: String, allow: Boolean): PendingIntent {
        // 与通知栏那条走**同一个** requestCode 与同一份 Id：`noticeId * 2 + allow`。
        // 第一版自己算了一套 `hashCode shl 1 or allow`，两套公式算出不同的码，
        // 于是两条通知各持一份 PendingIntent——回答成功后另一份仍然挂着能点
        // （R059 审查 P2-4）。这里直接复用 `NotificationCenter.askNoticeId`，
        // 公式只有一处，也就不存在「以后改了一边忘了另一边」。
        val noticeId = NotificationCenter.askNoticeId(eventId)
        return PendingIntent.getBroadcast(
            context,
            noticeId * 2 + if (allow) 1 else 0,
            Intent(context, NoticeActionReceiver::class.java).apply {
                action = NotificationCenter.ACTION_ANSWER
                putExtra(NotificationCenter.EXTRA_EVENT_ID, eventId)
                putExtra(NotificationCenter.EXTRA_ALLOW, allow)
                // 接收方在「宿主不对」时要撤掉这条通知，靠的就是它。
                // 第一版漏了这个 extra，落到默认值 -1 什么都撤不掉。
                putExtra(NotificationCenter.EXTRA_NOTICE_ID, noticeId)
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 「选这个选项」。
     *
     * 与审批不同的地方：提问的回答是**业务值**（`{answers:[{id, selected}]}`），
     * 必须先拿到 `questionId` 才能组出来，所以这里把三道信息一起带过去，
     * 由 `AppContainer` 那边查当前仍在等待的提问、组答案、发给服务端。
     *
     * 不在这里直接组 `AskAnswer`：接收器只有 `Intent`，而 `AskAnswer` 要用到的
     * `callId` / `questionId` 都可能已经过期（用户点了通知栏里一条早就被别的客户端
     * 回答掉的请求）。让真正发请求的那一层去校验「还在不在等」，失败时才能像审批一样
     * 把原因写回通知，而不是静默。
     */
    fun answerOption(
        context: Context,
        eventId: String,
        callId: String,
        questionId: String,
        label: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        optionRequestCode(eventId, label),
        Intent(context, NoticeActionReceiver::class.java).apply {
            action = NotificationCenter.ACTION_CAPSULE_OPTION
            putExtra(NotificationCenter.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationCenter.EXTRA_CALL_ID, callId)
            putExtra(NotificationCenter.EXTRA_QUESTION_ID, questionId)
            putExtra(NotificationCenter.EXTRA_OPTION_LABEL, label)
            setPackage(context.packageName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 选项按钮的 requestCode。
     *
     * 用 `label` 的散列而不是下标：同一条请求上「选项 A」和「选项 B」必须算出不同的码，
     * 而选项顺序可能被服务端调整。散列冲突的概率在这个规模（每题 2~16 个选项）下
     * 可以接受；真撞了的表现是「点 A 得到 B 的答案」，但两者的 `EXTRA_OPTION_LABEL`
     * 是各自携带的，实际发出去的仍然是正确的标签。
     */
    private fun optionRequestCode(eventId: String, label: String): Int =
        ((eventId + '\u0000' + label).hashCode() and NotificationCenter.ASK_ID_MASK) or
            NotificationCenter.CAPSULE_OPTION_CODE_BIT
}
