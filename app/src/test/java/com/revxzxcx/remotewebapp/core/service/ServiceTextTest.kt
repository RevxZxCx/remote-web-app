package com.revxzxcx.remotewebapp.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻通知与设置页状态行的文案（R057 用户要求第 3 条「显示运行时长」）。
 *
 * ## 为什么要逐句断言
 * 「状态如实写」最终落在**这几个字符串**上：同一份 [ServiceStatus] 在通知栏、
 * 设置页状态行两处出现，口径必须一致；而最容易犯的错是「该说的地方没说」——
 * 比如把 [ServicePhase.Reconnecting] 写成和 [ServicePhase.Connected] 一样的标题，
 * 用户就不知道此刻收不到通知。
 */
class ServiceTextTest {

    @Test
    fun `不到一分钟用秒`() {
        assertEquals("0 秒", formatDuration(0L))
        assertEquals("42 秒", formatDuration(42_000L))
        assertEquals("59 秒", formatDuration(59_999L))
    }

    @Test
    fun `到一分钟就换成分钟 不再显示秒`() {
        assertEquals("1 分钟", formatDuration(60_000L))
        assertEquals("59 分钟", formatDuration(59 * 60_000L))
    }

    @Test
    fun `到小时就换成小时加分钟`() {
        assertEquals("1 小时", formatDuration(3_600_000L))
        assertEquals("1 小时 5 分", formatDuration(3_900_000L))
        assertEquals("23 小时 59 分", formatDuration((23 * 60L + 59L) * 60_000L))
    }

    @Test
    fun `到天就换成天加小时`() {
        assertEquals("1 天", formatDuration(24L * 3_600_000L))
        assertEquals("1 天 2 小时", formatDuration(26L * 3_600_000L))
        assertEquals("3 天 5 小时", formatDuration((3 * 24L + 5L) * 3_600_000L))
    }

    @Test
    fun `负数时长写 0 秒 不写 -3 秒`() {
        // 时钟回拨（NTP 校正 / 用户改时间）会让差值变负。
        assertEquals("0 秒", formatDuration(-3_000L))
    }

    @Test
    fun `通知里的秒数按五秒档走 不能每秒变一次`() {
        // 第五轮审查 P2。常驻通知的去重键是「标题 + 正文」的可见文案，
        // 一秒一变就等于每秒 notify 一次，系统进不了静止态（真机表现为
        // uiautomator dump 报 `ERROR: could not get idle state.`）。
        assertEquals("5 秒", formatNoticeDuration(0L))
        assertEquals("5 秒", formatNoticeDuration(4_999L))
        assertEquals("5 秒", formatNoticeDuration(5_000L))
        assertEquals("10 秒", formatNoticeDuration(9_999L))
        assertEquals("10 秒", formatNoticeDuration(10_000L))
        assertEquals("55 秒", formatNoticeDuration(54_000L))
        // 边界如实记录：59.999 秒向上归到 60 秒档，画出来是「60 秒」而不是「1 分钟」。
        // 这不是 bug——再下一秒（60_000）就交回 formatDuration 说「1 分钟」了，
        // 只差一帧，且两帧都是稳定档位，不会造成每秒重发。
        assertEquals("60 秒", formatNoticeDuration(59_999L))
    }

    @Test
    fun `通知里满一分钟就交回原来的写法`() {
        // 60 秒之后本来就不会每秒变了（formatDuration 只到分钟），
        // 所以 formatNoticeDuration 必须原样交回去，不能自己再算一套。
        assertEquals(formatDuration(60_000L), formatNoticeDuration(60_000L))
        assertEquals(formatDuration(3_900_000L), formatNoticeDuration(3_900_000L))
        assertEquals("1 小时 5 分", formatNoticeDuration(3_900_000L))
        assertEquals("1 天 2 小时", formatNoticeDuration(26L * 3_600_000L))
    }

    @Test
    fun `通知里的负数时长也写 5 秒 不写 0 秒或负数`() {
        // 同上，时钟回拨。这里刻意不返回 "0 秒"：档位下界就是 5 秒，
        // 返回 0 会让「刚起来」看起来像「没在计时」。
        assertEquals("5 秒", formatNoticeDuration(-1L))
        assertEquals("5 秒", formatNoticeDuration(-60_000L))
    }

    @Test
    fun `常驻通知的标题与正文都用五秒档 不会退回到秒级`() {
        // 直接盯住调用点：这三个地方一旦改回 formatDuration，
        // 头一分钟又会每秒 notify 一次，而上面几条档位测试仍会全绿。
        val connected = ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 7_000L)
        assertTrue(
            "已连接的标题必须用五秒档",
            ServiceText.title(connected).endsWith("10 秒"),
        )
        assertTrue(
            "已连接的正文必须用五秒档",
            ServiceText.text(connected).contains("已连接 10 秒"),
        )
        val reconnecting = ServiceStatus(
            phase = ServicePhase.Reconnecting,
            downForMillis = 7_000L,
            attempt = 1,
        )
        assertTrue(
            "重连中的正文必须用五秒档",
            ServiceText.text(reconnecting).contains("已断开 10 秒"),
        )
    }

    @Test
    fun `设置页状态行仍是秒级 不跟着通知降精度`() {
        // 设置页是用户主动盯着看的那一屏，秒级跳动不打扰任何人，
        // 降成五秒档反而让「刚刚连上」看不出变化。两处精度不同是故意的。
        val connected = ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 7_000L)
        assertTrue(
            "设置页状态行要说 7 秒，不能是 10 秒",
            ServiceText.statusLine(connected).contains("7 秒"),
        )
    }

    @Test
    fun `已连接不能写正在连接 收起态只看得到标题`() {
        // 这条曾经是反的：当时断言「两相共用标题」，理由是「对用户含义相同」。
        // 但通知默认就是收起态，用户不展开只看得到标题——连上几个小时、正文写着
        // 「已连接 2 小时」，标题却一直说「正在连接」，用户会以为它坏了。
        val connecting = ServiceText.title(ServiceStatus(phase = ServicePhase.Connecting))
        val connected = ServiceText.title(
            ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 90_000L),
        )
        assertFalse("收起态标题必须能分出「还在连」与「已连上」", connecting == connected)
    }

    @Test
    fun `已连接的标题要带上时长 不展开也能看出连了多久`() {
        val title = ServiceText.title(
            ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 2L * 3_600_000L + 14L * 60_000L),
        )
        assertTrue("标题里要有时长：$title", title.contains("2 小时"))
        assertTrue("标题里要有时长：$title", title.contains("14 分"))
    }

    @Test
    fun `重连中必须换标题 它意味着此刻收不到任何通知`() {
        val connected = ServiceText.title(ServiceStatus(phase = ServicePhase.Connected))
        val reconnecting = ServiceText.title(ServiceStatus(phase = ServicePhase.Reconnecting, attempt = 3))
        assertFalse("重连与已连接共用标题的话，用户不知道此刻收不到通知", connected == reconnecting)
        assertTrue("标题要带上第几次，用户据此判断是不是卡住了", reconnecting.contains("3"))
    }

    @Test
    fun `配对失效也要换标题 它不会自己好`() {
        val reconnecting = ServiceText.title(ServiceStatus(phase = ServicePhase.Reconnecting, attempt = 3))
        val unpaired = ServiceText.title(ServiceStatus(phase = ServicePhase.Unpaired))
        assertFalse("配对失效与重连是相反结论：一个会自己好，一个永远不会", unpaired == reconnecting)
        assertTrue(unpaired.contains("配对"))
    }

    @Test
    fun `已停止没有标题 调用方据此撤通知而不是发一条空通知`() {
        assertEquals("", ServiceText.title(ServiceStatus(phase = ServicePhase.Stopped)))
        assertEquals("", ServiceText.text(ServiceStatus(phase = ServicePhase.Stopped)))
    }

    @Test
    fun `展开正文里带得出运行时长`() {
        val text = ServiceText.text(
            ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 2L * 3_600_000L + 14L * 60_000L),
        )
        assertTrue("用户要求第 3 条就是显示运行时长，正文里必须有它", text.contains("2 小时 14 分"))
    }

    @Test
    fun `重连正文里带得出已断开多久`() {
        val text = ServiceText.text(ServiceStatus(phase = ServicePhase.Reconnecting, downForMillis = 42_000L))
        // 42 秒按五秒档**向上**归到「45 秒」（第五轮审查 P2）。这里断的是「正文里确实有
        // 断开时长」，不是断精确值——精确值已经在 `通知里的秒数按五秒档走` 那条里逐档钉死了。
        assertTrue("重连正文要说明断了多久", text.contains("45 秒"))
        // 越过一分钟就该恢复成「分钟」，不能永远停在秒档。
        val longDown = ServiceText.text(
            ServiceStatus(phase = ServicePhase.Reconnecting, downForMillis = 3_900_000L),
        )
        assertTrue("断久了要说 1 小时 5 分", longDown.contains("1 小时 5 分"))
    }

    @Test
    fun `配对失效正文带上桌面端给的原因`() {
        val text = ServiceText.text(
            ServiceStatus(phase = ServicePhase.Unpaired, unpairedReason = "凭证已被撤销"),
        )
        assertTrue("桌面端给的原因是最有用的线索，不该被丢掉", text.contains("凭证已被撤销"))
    }

    @Test
    fun `配对失效没给原因时不留一个空的说明段`() {
        val text = ServiceText.text(ServiceStatus(phase = ServicePhase.Unpaired, unpairedReason = "   "))
        assertFalse("没有原因却写「桌面端的说明：」，读起来像漏了内容", text.contains("桌面端的说明"))
    }

    @Test
    fun `设置页状态行在连上时必须有时长`() {
        val line = ServiceText.statusLine(
            ServiceStatus(phase = ServicePhase.Connected, connectedForMillis = 90_000L),
        )
        assertTrue(line.contains("1 分钟"))
        assertTrue("这行要回答的是「在不在跑」", line.contains("运行中"))
    }

    @Test
    fun `设置页状态行在没跑时说明未运行`() {
        assertEquals("未运行", ServiceText.statusLine(ServiceStatus(phase = ServicePhase.Stopped)))
    }

    @Test
    fun `设置页状态行在重连时不能只说运行中`() {
        // 「运行中」是真的，但它会让人以为一切正常。必须同时说明断开多久。
        val line = ServiceText.statusLine(
            ServiceStatus(phase = ServicePhase.Reconnecting, downForMillis = 42_000L, attempt = 3),
        )
        assertTrue(line.contains("重连"))
        assertTrue(line.contains("42 秒"))
    }

    @Test
    fun `退出按钮上的字就是退出 不是停止服务`() {
        assertEquals("退出", ServiceText.EXIT_LABEL)
    }

    @Test
    fun `自动恢复关闭时那条通知的正文说明点了会发生什么`() {
        assertTrue(ServiceText.stoppedText().contains("点这里"))
    }
}

/**
 * 设置页「后台连接」那一段的文案与置灰逻辑（R057 原型第 4 节）。
 *
 * 这一层的价值在于**「什么时候说」**：失败行、置灰两处任何一处判错，
 * 用户要么看不到问题（以为好好的），要么看到不该出现的东西（关着还说耗电）。
 */
class LinkServiceUiTest {

    @Test
    fun `开关关着时说明不提耗电 只说需要一条通知`() {
        val detail = keepAliveDetail(LinkServiceUi(enabled = false))
        // 关着还说「耗电略增」会让用户以为已经付出代价了。
        assertFalse(detail.contains("耗电略增"))
    }

    @Test
    fun `开关打开后要预告常驻通知与耗电`() {
        val detail = keepAliveDetail(LinkServiceUi(enabled = true))
        assertTrue("常驻通知是打开后立刻能看见的变化，不预告会让人以为出 bug", detail.contains("常驻通知"))
        assertTrue(detail.contains("耗电略增"))
    }

    @Test
    fun `有失败原因时说明行直接摊开原因`() {
        val detail = keepAliveDetail(LinkServiceUi(enabled = true, lastFailure = "通知不可用"))
        assertTrue(detail.contains("通知不可用"))
    }

    @Test
    fun `自动恢复开与关的说明必须是相反的`() {
        val on = autoRecoverDetail(LinkServiceUi(autoRecover = true))
        val off = autoRecoverDetail(LinkServiceUi(autoRecover = false))
        assertFalse(on == off)
        assertTrue(on.contains("自动拉回来"))
        assertTrue(off.contains("只发一条通知"))
    }

    @Test
    fun `开机自启的说明说清不用先打开 App`() {
        assertTrue(bootStartDetail().contains("不用先打开"))
    }
}
