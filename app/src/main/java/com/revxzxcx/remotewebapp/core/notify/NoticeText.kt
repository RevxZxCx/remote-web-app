package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest

/**
 * 通知里的文案（R056）。
 *
 * ## 为什么文案要单独抽出来
 * 三个理由，都不是洁癖：
 * 1. **通知栏的宽度不可控**。同一句话在锁屏、下拉通知栏、常亮屏、手表投射上的可用
 *    字符数完全不同。所以标题/正文的分工必须按「标题短、正文可截断」来切，
 *    而不能把一整句塞进标题等系统去截。
 * 2. **审批文案的字段是四级回落**（`displayReason["zh"]` → `["en"]` → `reason` →
 *    「工具 X 需要你的授权」）。这个回落逻辑已经写在 [ApprovalRequest.reasonText] 里，
 *    通知不能重写一遍——重写就会出现「卡片上是对的，通知上是英文技术说明」。
 * 3. 文案是最容易被改的东西，抽成纯函数才能一条条断言；写在 `NotificationCompat.Builder`
 *    的调用链里就只能靠读代码。
 */
object NoticeText {

    /** 「任务结束」通知的标题：跑完了。 */
    fun doneTitle(sessionName: String): String = "任务结束 · $sessionName"

    /**
     * 「任务结束」通知的正文。
     *
     * 说清楚是哪一轮结束，而不是只说「已完成」——用户可能同时挂了三个会话在跑，
     * 光看「已完成」分不出是哪个。
     */
    fun doneText(): String = "这一轮已跑完，点开查看结果。"

    /** 「任务跑挂」通知的标题。 */
    fun failedTitle(sessionName: String): String = "任务出错 · $sessionName"

    /**
     * 「任务跑挂」通知的正文。
     *
     * [reason] 是服务端拼好的 user-safe failure chain（`api-session/error` 的第二项）。
     * 空串时给一句兜底——红点该亮还是要亮，文案只是附加信息。
     */
    fun failedText(reason: String): String =
        reason.ifBlank { "这一轮没有跑完，点开查看详情。" }

    /** 审批请求通知的标题：动作 + 工具名。 */
    fun askTitle(request: ApprovalRequest): String = "请求授权：${request.toolName}"

    /**
     * 审批请求通知的正文。
     *
     * 直接用 [ApprovalRequest.reasonText]，**不自己回落**：四级回落的顺序是协议层
     * 的知识（有些请求只有 `en` 没有 `zh`），复制一份到通知里迟早会分叉。
     */
    fun askText(request: ApprovalRequest): String = request.reasonText()

    /**
     * 会话在通知里的名字。
     *
     * 与 `SessionSummary.displayName` 同一个兜底（用 id 后 8 位），但**不能直接复用**
     * 它：通知侧拿到的可能只有一个 id（标题表还没播种到这条会话），此时没有
     * `SessionSummary` 可用。两处兜底必须产生**同样的字符串**，否则用户会看到
     * 「列表里叫『未命名会话 a1b2c3d4』，通知里叫『未命名会话 4f5e6d7c』」这种对不上的情况。
     */
    fun sessionName(title: String?, sessionId: String): String =
        title?.takeIf { it.isNotBlank() } ?: "未命名会话 ${sessionId.takeLast(8)}"
}
