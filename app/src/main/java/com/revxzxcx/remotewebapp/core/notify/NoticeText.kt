package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.events.ApprovalRequest
import com.revxzxcx.remotewebapp.core.session.AskQuestion

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

    // ── 提问（user-questions/request）───────────────────────────────────────
    //
    // 为什么提问也要通知：它与审批是**同一种协议**——`user-questions/request` 在
    // `API_REMOTE_FORWARDED_EVENTS` 里同样是 `mode: 'waterfall'`，服务端把请求抛给
    // 第一个愿意回答的远程监听者然后**等**。没人答就是
    // `NO_PROVIDER`（`no user-questions answerer accepted the request`），
    // 也就是整个任务直接失败。只通知审批、不通知提问是不自洽的：
    // 两者对用户的意义完全一样——「模型停下来等你」。
    //
    // 文案不复用审批那套：提问的标题应该直接是问题本身，因为问题往往一句话
    // 就能让人决定「要不要现在处理」，而「请求授权：工具名」必须点开才知道问什么。

    /**
     * 提问通知的标题。
     *
     * 用**第一道题的 `header`**（模型的短标签，形如「选择方案」）优先，
     * 没有就用问题正文的首行。都不为空时也不拼在一起：通知标题只有一行，
     * 拼起来必然被截断，反而两头都读不全。
     *
     * @param count 这次一共几道题；>1 时标题带上「等 N 个问题」，让用户知道
     *   点进去不是答一道就完事。
     */
    fun questionTitle(first: AskQuestion?, count: Int): String {
        val head = first?.header?.takeIf { it.isNotBlank() }
            ?: first?.question?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: "模型有问题要问你"
        return if (count > 1) "$head（等 $count 个问题）" else head
    }

    /**
     * 提问通知的正文。
     *
     * 优先用第一道题的 `detail`（模型给的补充说明），没有就用问题正文本身；
     * 两者都空时给一句兜底。**不再回落成「工具 X 需要你的授权」**——那是审批的语义，
     * 用在提问上会让用户以为要批准什么危险操作。
     */
    fun questionText(first: AskQuestion?): String {
        val detail = first?.detail?.takeIf { it.isNotBlank() }
        if (detail != null) return detail
        val question = first?.question?.takeIf { it.isNotBlank() }
        if (question != null) return question
        return "点开回答问题，任务才能继续。"
    }

    // ── 四种「没跑成但也不算报错」的结局（R056 缺口 5）────────────────────
    //
    // 这四类原先一条通知都没有：`api-session/status(false)` 只表示「不在跑了」，
    // `api-session/error` 只在轮次之外失败时来，而它们既不是「跑完了」也不是
    // 「报错了」。用户看到的是「转圈消失了，什么都没有」。
    //
    // 标题**不能**复用「任务结束」：那四个字的意思是「一切按预期收尾」，
    // 而这几种恰恰不是。每一类都要说清「没成」以及「接下来怎么办」，
    // 否则用户只能猜。

    /** 输出被 token 上限切断：内容不完整，但能续写。 */
    fun truncatedTitle(sessionName: String): String = "输出被截断 · $sessionName"

    fun truncatedText(): String = "内容写到一半到了上限，没有跑完整。点开可以接着写。"

    /** 被权限/策略拦下：需要用户去桌面端批准。 */
    fun blockedTitle(sessionName: String): String = "被拦下了 · $sessionName"

    fun blockedText(): String = "工具调用被权限策略拦下，需要你去桌面端批准后才能继续。"

    /** 上游断流或进程重启：内容可能只有半截。 */
    fun interruptedTitle(sessionName: String): String = "生成被中断 · $sessionName"

    fun interruptedText(): String = "连接或进程在生成过程中断了，内容可能只有半截。点开看看。"

    /**
     * 非用户主动中止：进程被销毁、父会话取消、或说不清来源。
     *
     * **不含用户自己按停止的那种**——那种他知道，见 `OutcomeNotice` 的说明。
     */
    fun abortedTitle(sessionName: String): String = "本轮已中止 · $sessionName"

    fun abortedText(): String = "这一轮没跑完就停了，不是你在主界面上按的停止。点开看进度。"

    /** 认不出的结局：不猜语义，只说「结束了但 App 不认识」。 */
    fun unknownTitle(sessionName: String): String = "本轮结束得不明不白 · $sessionName"

    fun unknownText(): String = "这一轮结束了，但服务端给的结局 App 还不认识。点开看看现在是什么状态。"

    // --- 连接状态（R056 缺口 3）---------------------------------------------
    //
    // 这两条与上面那批「某一轮的结局」不是一类：它们说的是**现在还能不能收到东西**。
    // 标题里必须带上「收不到」这层意思——只说「连接断开」用户会以为是网络问题，
    // 而真正要紧的是「桌面端在跑什么，你这边不会知道了」。

    /** 连接断了：暂时收不到任何提醒。 */
    fun offlineTitle(): String = "连不上桌面端了"

    fun offlineText(seconds: Long): String {
        val span = if (seconds >= 60) "${seconds / 60} 分钟" else "$seconds 秒"
        return "已经断了 $span。这段时间里任务跑完了、要你确认，都不会提醒你——不是没发生，是你收不到。" +
            "检查一下网络，App 会自己重连。"
    }

    /** 配对凭证失效：不会自己好了，必须重新配对。 */
    fun unpairedTitle(): String = "这台手机已经不被信任了"

    fun unpairedText(): String =
        "桌面端那边已经撤销了这台设备的授权，不会自己恢复。回到 App 重新扫码配对才能继续收提醒。"

    /** 重连成功后说一句，只在之前提醒过时才发。 */
    fun onlineTitle(): String = "已经连回来了"

    fun onlineText(): String = "断线期间可能漏掉了几条提醒，已经在补。"

    // --- 账号（R056 缺口 4）-------------------------------------------------
    //
    // 这两条都要**说清楚去做什么**。模型凭据失效时 App 里看到的是「模型报了一句
    // 看不懂的错」，用户的第一反应是「这模型坏了」，而真实原因是「去桌面端重登一下」。
    // 只说「账号有问题」等于把同一个谜语再出一遍。

    /** 桌面端账号授权失效。 */
    fun accountExpiredTitle(): String = "桌面端的账号掉线了"

    fun accountExpiredText(): String =
        "桌面端那边的模型账号授权已经失效。在手机上重试没有用——所有任务都会失败。" +
            "去桌面端重新登录一次，这边就会恢复。"

    /** 某个模型 provider 要求重新登录。 */
    fun accountSignInTitle(): String = "有一个模型要重新登录"

    fun accountSignInText(): String =
        "桌面端在这个模型上的登录失效了，用它跑的任务会一直失败。去桌面端重新登录一下这个模型。"

    /** 凭据变了：之前的账号问题已经处理掉。 */
    fun accountFixedTitle(): String = "账号已经恢复了"

    fun accountFixedText(): String = "桌面端的登录已经更新，接下来不会再因为这个原因失败了。"
}
