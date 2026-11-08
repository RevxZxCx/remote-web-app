package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次 `turn/end` 的结局（R056 缺口 5）。
 *
 * [seq] 是这条事件信封的序号，除了排序还扮演**去重键**：同一个会话可能先后从两条
 * 路径（`api-session/error` 与 `api-session/status`）得知「这一轮结束了」，两条路径各自
 * 去查一次结局，查回来必然是**同一条** `turn/end`，于是 [seq] 相等，第二条被丢掉。
 * 用它而不是 `turn`：`turn` 字段在线上**可以缺失**（见 `ParseTurnOutcome` 的 KDoc），
 * 而 `seq` 是信封的必填键，永远不会缺。
 */
data class TurnEnd(
    val seq: Long,
    val turn: Long?,
    val outcome: TurnOutcome,
)

/**
 * 一次查询会话结局的完整结果（R056 缺口 5 + 缺口 3）。
 *
 * 比单个 [TurnEnd] 多两个字段，都是**给重连补账用的**：
 *
 * ## 为什么补账时不能只看「最后一条 turn/end」
 * 「跑完的那一刻」查一次，最后一条 `turn/end` 必然是刚结束那轮（缺口 5 的情形）。
 * 但**断线重连之后**再查，情况就不同了：断线期间那一轮可能还在跑，最后一条
 * `turn/end` 是**更早的上一轮**。照它发一条「任务结束」，等于把正在跑的任务
 * 报成跑完了——用户会以为可以去看结果了，而实际上还得等。
 *
 * [tailTurnOpen] 正是这个歧义的权威信号：它由窗口里最后一个 `turn/start` 与
 * `turn/end` 谁在后来决定（见 `ChatFrame.Snapshot` 的 KDoc，R004 P2-1 的产物）。
 * 为 true 就表示「有一轮开了还没结束」，此时 [lastEnd] 一律不可信。
 *
 * @param lastEnd 窗口内最后一条 `turn/end`；没有则为 null。
 * @param tailTurnOpen 窗口末尾是否处于「已开始但未结束」的轮次。
 */
data class TurnProbe(
    val lastEnd: TurnEnd?,
    val tailTurnOpen: Boolean,
) {
    /**
     * 能否确定「没有轮次正在跑」。
     *
     * 补账路径必须过这一关：为 false 说明还有一轮开着，此刻报任何结局都是错的。
     * 缺口 5 的即时路径不需要判——那是从「running 已翻转」的边沿进来的，
     * 结束是既定事实，`tailTurnOpen` 可能因为窗口滞后还是 true，判了反而漏报。
     */
    val settled: Boolean get() = !tailTurnOpen
}

/**
 * 从一批**原始事件信封**里找最后一个 `turn/end` 的结局。
 *
 * ## 为什么需要这个函数
 * 通知层订阅的是 `sessionNotificationFeed` 那条轻量旁路（29 条白名单事件），
 * **看不到 `turn/end`**——它只在逐会话的 `session/follow` 流里。于是三类结局
 * 永远传不到通知层：
 *
 * - `max-tokens`：输出到上限被切断，内容不完整但可以续写；
 * - `blocked`：被权限/策略拦下，需要用户去桌面端批准；
 * - `aborted`：被用户、父会话或进程销毁中止。
 *
 * 它们都不会触发 `api-session/error`（官方摘要写的是「failed **outside a durable
 * turn position**」，语义上就不是轮次内失败的渠道），所以按旧实现只有「跑完」和
 * 「报错」两种通知，这三类一律沉默。这正是用户 m11638 说的「任务出问题也要通知，
 * 而现在实际是没有的」。
 *
 * ## 为什么不直接判定「最后一条一定是刚结束的那一轮」
 * 不需要判定：查询发生在「检测到 running 翻转」的那一刻，此刻若已有新一轮开跑，
 * 最后一条 `turn/end` 仍然属于**上一轮**——也就是我们关心的那一轮；若没有新一轮，
 * 最后一条就是刚结束的那条。两种情形取最后一个都对。
 *
 * ⚠️ 上面这条推理**只在缺口 5 的即时路径成立**。重连补账时用同一个函数取值，
 * 必须额外看 [TurnProbe.tailTurnOpen]——见 `TurnProbe` 的 KDoc。
 *
 * @param envelopes `ChatFrame.Snapshot.envelopes`，即窗口内全部可解析事件的原始信封
 *   （`turn/start` / `turn/end` 这类不产生卡片的事件也在里面，见 `ChatFrameParser`）。
 */
fun lastTurnEnd(envelopes: List<JsonObject>): TurnEnd? {
    for (envelope in envelopes.asReversed()) {
        if (envelope.stringOrNull("type") != "turn/end") continue
        // ⚠️ 找到**第一条** `turn/end` 就定案，解析不出也返回 null，绝不往前再找。
        //
        // 原先这里对 `seq`/`reason` 缺失都用 `continue`，于是「最新一轮的结局不认识」
        // 会静默退回「更早那一轮的结局」。两个后果都很坏：
        // ①拿旧轮的 `seq` 去比 `postedSeq`，多半命中「已经报过」→ 用户一条通知都没有；
        // ②没命中时，会用**很早以前那一轮**的结局编一条提醒，说「任务结束了」
        //   而当前这一轮其实还在跑或者刚刚失败。
        val seq = envelope.longOrNull("seq") ?: return null
        val data = envelope["data"] as? JsonObject
        val outcome = parseTurnOutcome(data?.get("reason")) ?: return null
        return TurnEnd(seq = seq, turn = data?.longOrNull("turn"), outcome = outcome)
    }
    return null
}

/**
 * 从一批原始信封判断「窗口末尾是否有一轮开着」。
 *
 * 与 `ChatFrameParser` 里的同名判据同源（那边算 `Snapshot.tailTurnOpen`），
 * 这里单独实现是因为通知层拿到的是信封而**不是** `Snapshot`：重连补账只需要
 * 两个字段，为它构造一个完整 `Snapshot` 反而要喂一堆无关参数。
 *
 * 判据：从尾部往前找到第一条 `turn/start` 或 `turn/end`，是 `turn/start` 即为开着。
 * 只看这两类事件——`step/start` 之类不改变轮次的开合状态。
 */
fun tailTurnOpen(envelopes: List<JsonObject>): Boolean {
    for (envelope in envelopes.asReversed()) {
        when (envelope.stringOrNull("type")) {
            "turn/start" -> return true
            "turn/end" -> return false
        }
    }
    // 窗口里一条轮次事件都没有：没有轮次开着，也没有结局。
    return false
}
