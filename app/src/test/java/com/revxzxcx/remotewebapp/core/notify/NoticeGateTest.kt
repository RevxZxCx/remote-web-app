package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.notify.NoticeDelivery.Alert
import com.revxzxcx.remotewebapp.core.notify.NoticeDelivery.Silent
import com.revxzxcx.remotewebapp.core.notify.NoticeDelivery.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一刻该用哪种方式投递通知」的判定表（R056）。
 *
 * ## 为什么断言三态而不是布尔
 * 原型 `docs/R056-系统通知.html` 第 6 节的表里有三行而不是两行：「不发」「发但不响铃」
 * 「发并正常提醒」。第一版把它压成布尔，结果「前台看别的页」被当成不发整条丢掉。
 * 用 [NoticeDelivery] 后每一行都能单独钉住。
 *
 * ## 最容易写错的一格：后台 + 视角停在同一个会话
 * `viewingSessionId` 是「最后一次打开的会话」，**按 Home 切后台不会清它**。所以
 * 「后台 + viewingSessionId == completedSessionId」必须判成 [Alert] 而不是 [Skip]——
 * 那恰好是「发完任务就切出去等结果」这个最高频场景。第一版就是在这里发不出通知，
 * 真机跑了两轮才查出来（见 `完成通知在切到后台时必须正常提醒` 的注释）。
 *
 * 审批请求是刻意留的**例外**：哪怕用户正盯着那个会话也发。这条例外最容易在重构时
 * 被「顺手统一」掉，所以单独钉一条用例。
 */
class NoticeGateTest {

    private val notForeground = NoticeVisibility(foreground = false)
    private val foreground = NoticeVisibility(foreground = true)

    private fun decide(
        visibility: NoticeVisibility,
        channelId: String = NoticeChannels.DONE,
        permissionGranted: Boolean = true,
        channelEnabled: Boolean = true,
    ): NoticeDelivery = NoticeGate.decide(
        visibility = visibility,
        channelId = channelId,
        permissionGranted = permissionGranted,
        channelEnabled = channelEnabled,
    )

    // ── 两条硬闸门：没权限 / 频道被关 ────────────────────────────────

    @Test
    fun `没给权限时什么都不发`() {
        assertEquals(
            "没权限时 notify 会静默失败，账本却以为发出去了",
            Skip,
            decide(notForeground, permissionGranted = false),
        )
    }

    @Test
    fun `审批请求没给权限也不发`() {
        assertEquals(
            "审批是例外，但例外也不能越过「没有权限」这一层",
            Skip,
            decide(notForeground, NoticeChannels.ASK, permissionGranted = false),
        )
    }

    @Test
    fun `频道被关掉时什么都不发`() {
        assertEquals(Skip, decide(notForeground, channelEnabled = false))
    }

    @Test
    fun `审批请求频道被关掉时也不发`() {
        assertEquals(Skip, decide(notForeground, NoticeChannels.ASK, channelEnabled = false))
    }

    // ── 后台：主场景，一律正常提醒 ──────────────────────────────────

    @Test
    fun `后台且看着别的会话时 任务结束要发`() {
        assertEquals(
            Alert,
            decide(NoticeVisibility(foreground = false, viewingSessionId = "other", completedSessionId = "s1")),
        )
    }

    @Test
    fun `完成通知在切到后台时必须正常提醒`() {
        // 真实路径：会话 X 里点发送 → 进聊天页（viewingSessionId = X）→ 按 Home
        // （foreground = false，但 viewingSessionId 仍是 X）→ 任务跑完。
        // 第一版在这里返回 false，导致「切出去等结果」完全收不到通知。
        assertEquals(
            "按 Home 不会清 viewingSessionId，它不代表用户还在看；后台是通知的主场景",
            Alert,
            decide(NoticeVisibility(foreground = false, viewingSessionId = "s1", completedSessionId = "s1")),
        )
    }

    @Test
    fun `在首页没有打开任何会话时 任务结束要发`() {
        assertEquals(
            Alert,
            decide(NoticeVisibility(foreground = false, viewingSessionId = null, completedSessionId = "s1")),
        )
    }

    @Test
    fun `viewingSessionId 为空与 completedSessionId 为空不能被当成同一个会话`() {
        assertEquals(
            "两个 null 相等，若不单独判 null 就会把「哪条都没在看」误判成「正看着它」",
            Alert,
            decide(NoticeVisibility(foreground = false, viewingSessionId = null, completedSessionId = null)),
        )
    }

    // ── 前台：正看着这条静默，看别的页只更新不响铃 ────────────────────

    @Test
    fun `App 在前台正看着这条会话时 任务结束彻底不发`() {
        assertEquals(
            "界面里已经有完成提示，再弹系统横幅是重复告知",
            Skip,
            decide(NoticeVisibility(foreground = true, viewingSessionId = "s1", completedSessionId = "s1")),
        )
    }

    @Test
    fun `App 在前台看着别的会话时 任务结束要有通知但不响铃`() {
        assertEquals(
            "用户没在看这条，需要知道；但他在用 App，横幅会挡住界面顶部，所以静默投递",
            Silent,
            decide(NoticeVisibility(foreground = true, viewingSessionId = "other", completedSessionId = "s1")),
        )
    }

    @Test
    fun `App 在前台且没打开任何会话时 任务结束也要静默投递`() {
        assertEquals(
            "在首页也是「在用 App」，同样不该响铃",
            Silent,
            decide(NoticeVisibility(foreground = true, viewingSessionId = null, completedSessionId = "s1")),
        )
    }

    // ── 审批：永远 Alert ───────────────────────────────────────────

    @Test
    fun `正看着这个会话时 审批仍然要发`() {
        assertEquals(
            "审批是 waterfall，服务端在等；看不见就会一直卡住。这是刻意的例外",
            Alert,
            decide(
                NoticeVisibility(foreground = false, viewingSessionId = "s1", completedSessionId = "s1"),
                NoticeChannels.ASK,
            ),
        )
    }

    @Test
    fun `App 在前台时 审批仍然要发`() {
        assertEquals(
            "用户可能正在打字回上一条，没注意到卡片；横幅出现一次的成本远小于工具静默失败",
            Alert,
            decide(foreground, NoticeChannels.ASK),
        )
    }

    @Test
    fun `App 在前台正看着这条会话时 审批仍然要响铃`() {
        assertEquals(
            "审批不区分前台/后台、不区分看没看这条 —— 服务端在等，任何降级都可能让它静默卡住",
            Alert,
            decide(
                NoticeVisibility(foreground = true, viewingSessionId = "s1", completedSessionId = "s1"),
                NoticeChannels.ASK,
            ),
        )
    }
}

/**
 * 通知文案（R056）。
 *
 * 断的是**信息量**而不是措辞：标题必须带会话名（否则三个会话同时跑时分不清谁跑完了）、
 * 失败正文必须带原因（否则红点亮了却不知道为什么）、审批正文必须走
 * [com.revxzxcx.remotewebapp.core.events.ApprovalRequest.reasonText] 的四级回落
 * （否则会出现「卡片上中文、通知上英文技术说明」）。
 */
class NoticeTextTest {

    private fun approval(
        tool: String = "Bash",
        reason: String? = null,
        display: Map<String, String> = emptyMap(),
    ) = com.revxzxcx.remotewebapp.core.events.ApprovalRequest(
        eventId = "e1",
        agentId = "s1",
        toolName = tool,
        callId = null,
        reason = reason,
        displayReason = display,
    )

    @Test
    fun `结束标题带会话名`() {
        assertTrue(
            "三个会话同时跑时，光看「任务结束」分不清是谁跑完了",
            NoticeText.doneTitle("重构登录页").contains("重构登录页"),
        )
    }

    @Test
    fun `结束标题与出错标题不一样`() {
        assertFalse(
            "跑完与跑挂在通知栏上必须一眼可辨，否则用户得点进去才知道要不要处理",
            NoticeText.doneTitle("甲") == NoticeText.failedTitle("甲"),
        )
    }

    @Test
    fun `出错正文带服务端给的原因`() {
        assertEquals("ENOENT: no such file", NoticeText.failedText("ENOENT: no such file"))
    }

    @Test
    fun `出错原因为空时给兜底句而不是空白通知`() {
        val text = NoticeText.failedText("")
        assertTrue(
            "空白正文的通知看起来像 bug；红点该亮还是要亮，文案只是附加信息",
            text.isNotBlank(),
        )
    }

    @Test
    fun `出错原因全是空格时也给兜底句`() {
        assertTrue(
            "isBlank 而不是 isEmpty —— 服务端偶尔回一两个空格",
            NoticeText.failedText("   ").isNotBlank(),
        )
    }

    @Test
    fun `审批标题带工具名`() {
        assertTrue(
            "用户要在一屏里判断「这条值不值得现在处理」，工具名是最短的判据",
            NoticeText.askTitle(approval(tool = "Bash")).contains("Bash"),
        )
    }

    @Test
    fun `审批正文优先中文`() {
        val request = approval(display = mapOf("zh" to "要删除 dist 目录", "en" to "delete dist"))
        assertEquals(
            "displayReason 的四级回落是协议层的知识，通知里不能再回落一遍",
            "要删除 dist 目录",
            NoticeText.askText(request),
        )
    }

    @Test
    fun `审批正文只有英文时用英文而不是空白`() {
        val request = approval(display = mapOf("en" to "delete dist"))
        assertEquals("delete dist", NoticeText.askText(request))
    }

    @Test
    fun `审批正文没有任何理由时兜底到工具名`() {
        val text = NoticeText.askText(approval(tool = "WebFetch"))
        assertTrue("不能出现空白正文", text.isNotBlank())
        assertTrue("兜底句里要有工具名，否则用户不知道要授权什么", text.contains("WebFetch"))
    }

    @Test
    fun `会话名优先用标题`() {
        assertEquals("重构登录页", NoticeText.sessionName("重构登录页", "session-4f5e6d7c"))
    }

    @Test
    fun `没有标题时用 id 后八位`() {
        assertEquals("未命名会话 4f5e6d7c", NoticeText.sessionName(null, "session-4f5e6d7c"))
    }

    @Test
    fun `标题是空串时也走兜底`() {
        assertEquals(
            "空串标题不是「没有标题」，但显示效果一样是空白，必须一起兜底",
            "未命名会话 4f5e6d7c",
            NoticeText.sessionName("   ", "session-4f5e6d7c"),
        )
    }

    @Test
    fun `兜底名与列表里的 displayName 完全一致`() {
        val summary = com.revxzxcx.remotewebapp.core.session.SessionSummary(
            sessionId = "session-4f5e6d7c",
            title = "",
        )
        assertEquals(
            "两处不一致就会出现「列表里叫 a1b2c3d4、通知里叫 4f5e6d7c」这种对不上的情况",
            summary.displayName,
            NoticeText.sessionName(null, "session-4f5e6d7c"),
        )
    }
}
