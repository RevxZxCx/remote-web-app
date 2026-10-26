package com.revxzxcx.remotewebapp.core.session

/**
 * 会话在**列表里**显示哪一种状态标记（R049，用户 m32994 + m33325 纠正）。
 *
 * ## 这个模型要表达什么
 *
 * 列表行原来只有两态：`running` 时转「生成中」小柱，其余什么都不显示。用户要的是
 * 「完成时给个标记提醒我来看」「报错、需要继续时也该有颜色」。
 *
 * 但用户随后明确纠正了一句话，这句话决定了整个模型的形状：
 *
 * > 这个点不是常驻的，而是一个完成的标记，提醒用户查看对话的
 *
 * 也就是说 [Done] **不是「这条会话跑完了」**（那是历史事实，永远成立），而是
 * **「它跑完了，但你还没来看」**（一次性提醒，看过即灭）。判据在 [CompletionLedger]。
 *
 * ## 数据来源与各自的置信度
 *
 * | 状态 | 依据 | 是否持久 |
 * |---|---|---|
 * | [Ongoing] | `session/list` 的 `running`，或 `api-session/status(id, true)` | 服务端权威 |
 * | [Attention] | `projections.values.userQuestions.active` 非空（有人在等回答）或 `inbox` 非空（有排队未发） | 随 `session/list` 一起读 |
 * | [Done] | [CompletionLedger] 的边沿判定：跑完了且用户没在看 | **仅本次运行期** |
 * | [Idle] | 以上都不是（没有未完成的事，也没有未读提醒） | —— |
 *
 * ## 与官方桌面端的对应
 *
 * DSH 桌面端 `ui-workspace` 的 `sessionStatuses(node, t)` 优先级是：
 * `pendingInteraction`（审批 / 计划审查 / 提问，`warning`）> `running`（`ongoing`）>
 * `node.completed`（`done`）> 其余（`idle`）。这里的 [Attention] 对应其中的
 * `pendingInteraction` 里 App 能拿到的那部分（用户提问、排队消息；审批与计划审查
 * 走的是会话内的审批面板，列表侧没有推送），[Done] 对应 `node.completed`，而它背后
 * 正是 `status.completionUnread`。
 *
 * ## [Failed] 是从哪来的（与官方的一处有意偏移）
 *
 * 官方桌面端没有把红色 `error` 挂在会话行上——`api-session/error` 在那边的落点是
 * 进会话看失败卡片。但用户要求「对话出现报错……也应该有对应的颜色表现，提醒用户
 * 进行查看审阅」，而列表侧只有 `api-session/error` 这一个信号能拿到。所以这里把它
 * 做成与 [Done] 同构的一次性提醒（看过即灭、重跑即灭），而不是常驻红点——常驻红点
 * 正是用户已经否掉的那种形态。
 */
enum class SessionStatus {
    /** 正在跑（列表里显示「生成中」动画）。 */
    Ongoing,

    /** 有提问等回答，或有排队消息没发出去（琥珀）。 */
    Attention,

    /** 刚跑完、用户还没来看（绿）。看过就变 [Idle]。 */
    Done,

    /** 上一轮跑挂了、用户还没来看（红）。看过、或它重新跑起来，就消失。 */
    Failed,

    /** 没有未完成的事，也没有未读提醒：不画任何标记。 */
    Idle,
    ;
}

/**
 * 从一条列表摘要推断它该显示的状态。
 *
 * 判据顺序就是优先级，与官方 `sessionStatuses` 一致（asar 偏移 49084078 起，
 * `ui-workspace` 的 `sessionStatuses(node, t)`）：**待交互 > 正在跑 > 完成未读 > 其余**。
 * 官方那次实现里 `pendingInteraction` 在 `node.running` **之前**返回，并在注释里写明
 * 「pending interaction is primary and live activity outranks completion reminders」。
 *
 * [SessionStatus.Attention] 必须排在 [SessionStatus.Ongoing] 之前，这是有真实场景支撑的：
 * agent 挂起等你回答/等你审批时，它这一轮**并没有结束**，`running` 依然是 true。把
 * running 放前面，「需要继续」这一档在提问场景下就永远不会出现——而这正是用户
 * m32994 明确要求的三种状态之一。
 *
 * 「正在跑」仍然高于 [SessionStatus.Done]：一个会话可能同时「上一轮刚跑完没人看」且
 * 「这一轮又跑起来了」（用户从别处给它发了新消息），此时显示绿点会让用户以为它停着。
 *
 * [SessionStatus.Failed] 排在 [SessionStatus.Done] 之前：同一条会话既跑完又报错时，
 * 红点承载更多信息，且它是「这次没成」的唯一提示（`session/list` 不提供 outcome）。
 */
fun statusOf(summary: SessionSummary): SessionStatus = when {
    // 待交互先于 running：挂起等回答/审批时这一轮还没结束，running 仍为 true。
    summary.awaitingAttention -> SessionStatus.Attention
    summary.running -> SessionStatus.Ongoing
    // 失败优先于「跑完了没看」：红点承载更多信息。
    summary.failureUnread -> SessionStatus.Failed
    summary.completionUnread -> SessionStatus.Done
    else -> SessionStatus.Idle
}
