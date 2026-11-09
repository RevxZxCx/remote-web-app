package com.revxzxcx.remotewebapp.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QueueSteer] 的单测 —— B009 的「该不该发这个插队请求」判据。
 *
 * 判据必须是纯函数：`ChatViewModel.steerQueued` 在发请求**之前**调它，命中就直接给
 * 一句话提示、一个 RPC 都不发。所以这里测的是「什么情况下不该发」。
 */
class QueueSteerTest {

    private fun message(id: String = "item-1", target: String = INBOX_NEXT_TURN) = QueuedMessage(
        id = id,
        rpcId = "rpc-1",
        text = "排队的话",
        target = target,
    )

    /** 请求正在飞：再来一下连提示都要认出来，否则第二次会照样发出去并被服务端拒。 */
    @Test
    fun `正在插队的条目再点一次给在途提示`() {
        val reason = QueueSteer.blockReason(message(), setOf("item-1"))

        assertEquals(QueueSteer.NOTICE_IN_FLIGHT, reason)
    }

    /** 已经插过队（服务端把 target 从 next-turn 挪走了）：一次性的动作不能再发。 */
    @Test
    fun `已经不在下一轮的条目给已插队提示`() {
        val reason = QueueSteer.blockReason(message(), emptySet())

        assertNull(reason)

        val steered = QueueSteer.blockReason(message(target = "steer"), emptySet())
        assertEquals(QueueSteer.NOTICE_ALREADY, steered)
    }

    /** 在途判定优先于「已插队」：两个条件同时成立时说的是「等一下」而不是「已经好了」。 */
    @Test
    fun `在途判定优先于已插队判定`() {
        val reason = QueueSteer.blockReason(message(target = "steer"), setOf("item-1"))

        assertEquals(QueueSteer.NOTICE_IN_FLIGHT, reason)
    }

    /** 正常可插队的条目返回 null —— 调用方据此放行。 */
    @Test
    fun `可插队的条目不拦截`() {
        assertNull(QueueSteer.blockReason(message(), emptySet()))
        // 别的条目在飞不影响这一条。
        assertNull(QueueSteer.blockReason(message(id = "item-2"), setOf("item-1")))
    }

    /** 三条提示都是给人看的中文句子，不能是 `sendError` 那种错误码拼接。 */
    @Test
    fun `提示文案是中文且不含错误码`() {
        listOf(
            QueueSteer.NOTICE_IN_FLIGHT,
            QueueSteer.NOTICE_QUEUE_BUSY,
            QueueSteer.NOTICE_ALREADY,
            QueueSteer.NOTICE_REJECTED,
        )
            .forEach { notice ->
                assertEquals(notice, notice.trim())
                assert(notice.isNotBlank()) { "提示文案不能为空" }
                assert(!notice.contains("：")) { "提示不该像 describe() 那样拼错误码：$notice" }
                assert(!notice.contains("session/")) { "提示不该泄漏服务端错误码：$notice" }
            }
    }

    /**
     * 服务端拒绝插队时的文案**不许宣称成功**（R058 审查 P2）。
     *
     * `session/steer-unavailable` 不只表示「已经插过队了」，它同样覆盖「会话已经不在
     * 运行」。写成「这条已经插过队了，不用再点」会让用户以为操作成功了，于是不再重试，
     * 转而去别处找那条消息。
     */
    @Test
    fun `拒绝文案不许宣称已经插队成功`() {
        val rejected = QueueSteer.NOTICE_REJECTED

        assert(!rejected.contains("已经插过")) { "拒绝文案不能断言操作成功：$rejected" }
        assert(rejected.contains("没能")) { "拒绝文案要明确说没成：$rejected" }
        assertNotEquals(
            "拒绝文案与「本地已判定插过队」的提示必须是两句不同的话",
            QueueSteer.NOTICE_ALREADY,
            rejected,
        )
    }
}
