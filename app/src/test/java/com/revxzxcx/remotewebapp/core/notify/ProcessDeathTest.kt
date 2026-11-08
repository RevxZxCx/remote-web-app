package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上次进程是怎么没的」判定逻辑（R056 / Q3）。
 *
 * 这一类的价值全在**不该提示的场合**：用户被多弹一次「上次是被杀掉的」，比漏一次更糟——
 * 他会以为这功能压根没生效。所以下面每一个 `assertNull` 都对着一条真实的误报路径。
 */
class ProcessDeathTest {

    private fun exit(reason: ExitReason, at: Long, description: String? = null) =
        ExitFacts(reason = reason, timestamp = at, description = description)

    // ── 归类 ──────────────────────────────────────────────────────────────

    @Test
    fun `内存不足算系统杀的`() {
        assertEquals(DeathCause.SystemKilled, classifyExit(ExitReason.LowMemory))
    }

    @Test
    fun `被信号杀死算系统杀的 一加的电池应用走这条路`() {
        assertEquals(DeathCause.SystemKilled, classifyExit(ExitReason.Signaled))
    }

    @Test
    fun `冻结器回收算系统杀的`() {
        assertEquals(DeathCause.SystemKilled, classifyExit(ExitReason.Freezer))
    }

    @Test
    fun `没说法的原因也算系统杀的 否则一加上这个功能等于没做`() {
        // 一加/ColorOS 的部分杀法在 ApplicationExitInfo 里落 REASON_OTHER。
        // 把它排除掉，用户在最需要提示的那台手机上永远看不到提示。
        assertEquals(DeathCause.SystemKilled, classifyExit(ExitReason.Other))
    }

    @Test
    fun `用户自己划掉的不算系统杀的`() {
        assertEquals(DeathCause.Stopped, classifyExit(ExitReason.UserRequested))
    }

    @Test
    fun `自己的崩溃不许怪到电池头上`() {
        assertEquals(DeathCause.NotAKill, classifyExit(ExitReason.Crash))
    }

    @Test
    fun `系统更新这类清理不算杀`() {
        assertEquals(DeathCause.NotAKill, classifyExit(ExitReason.SystemHousekeeping))
    }

    @Test
    fun `不认识的原因宁可少说 不能引导用户去改电池`() {
        // 系统版本比我们新时会出现这种情况。此时提示「去开保活」是在为一个
        // 可能属于我方 bug 的现象给出错误建议。
        assertEquals(DeathCause.NotAKill, classifyExit(ExitReason.Unknown))
    }

    // ── 挑选：四条规则 ─────────────────────────────────────────────────────

    @Test
    fun `没有退出记录时不提示`() {
        assertNull(pickDeathNotice(emptyList(), lastSeenAt = 1000L, backgroundedAt = 500L))
    }

    @Test
    fun `第一次装完没有基线时不提示 历史里的旧记录不能拿来吓人`() {
        val exits = listOf(exit(ExitReason.LowMemory, 9_000L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 0L, backgroundedAt = 500L))
    }

    @Test
    fun `没记到过进后台的时刻就不提示 无法确认就不说`() {
        val exits = listOf(exit(ExitReason.LowMemory, 9_000L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 1000L, backgroundedAt = null))
    }

    @Test
    fun `进后台时刻为 0 同样不提示`() {
        val exits = listOf(exit(ExitReason.LowMemory, 9_000L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 1000L, backgroundedAt = 0L))
    }

    @Test
    fun `后台被杀要提示 这是这个功能存在的全部理由`() {
        val exits = listOf(exit(ExitReason.LowMemory, 9_000L))
        val notice = pickDeathNotice(exits, lastSeenAt = 1000L, backgroundedAt = 500L)
        assertNotNull(notice)
        assertEquals(DeathCause.SystemKilled, notice!!.cause)
        assertEquals(9_000L, notice.occurredAt)
    }

    @Test
    fun `已经提示过的那条不再提示 基线之后没有新的就闭嘴`() {
        val exits = listOf(exit(ExitReason.LowMemory, 9_000L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 9_000L, backgroundedAt = 500L))
    }

    @Test
    fun `基线之前的旧记录不提示 只看比基线新的`() {
        val exits = listOf(
            exit(ExitReason.LowMemory, 1_000L),
            exit(ExitReason.Signaled, 2_000L),
        )
        assertNull(pickDeathNotice(exits, lastSeenAt = 5_000L, backgroundedAt = 100L))
    }

    @Test
    fun `死亡时刻早于进后台时刻的不算 那是进后台之前就死了`() {
        // 「正开着 App 时进程没了」= 崩溃。这类已被归类排除，但即使原因看起来像
        // 系统杀的，时间上也对不上，不能算到「切出去之后被杀」头上。
        val exits = listOf(exit(ExitReason.LowMemory, 400L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 100L, backgroundedAt = 500L))
    }

    @Test
    fun `崩溃不提示 那是我们自己的 bug`() {
        val exits = listOf(exit(ExitReason.Crash, 9_000L))
        assertNull(pickDeathNotice(exits, lastSeenAt = 1000L, backgroundedAt = 500L))
    }

    @Test
    fun `用户划掉的也提示 但原因说成划掉`() {
        val exits = listOf(exit(ExitReason.UserRequested, 9_000L))
        val notice = pickDeathNotice(exits, lastSeenAt = 1000L, backgroundedAt = 500L)
        assertNotNull(notice)
        assertEquals(DeathCause.Stopped, notice!!.cause)
    }

    @Test
    fun `多条里挑最新的那条 不能拿几天前的旧事当刚发生`() {
        val exits = listOf(
            exit(ExitReason.LowMemory, 2_000L),
            exit(ExitReason.Signaled, 8_000L),
            exit(ExitReason.Freezer, 5_000L),
        )
        val notice = pickDeathNotice(exits, lastSeenAt = 1_000L, backgroundedAt = 1_500L)
        assertNotNull(notice)
        assertEquals(8_000L, notice!!.occurredAt)
    }

    @Test
    fun `最新的一条是崩溃时 不去翻更早的那条系统杀`() {
        // 挑「最新的可提示项」而不是「最新的任意项」会让这条通过，
        // 但那样用户看到的是 2 秒前崩溃、而卡片说「后台被杀」——时间对不上。
        val exits = listOf(
            exit(ExitReason.LowMemory, 2_000L),
            exit(ExitReason.Crash, 8_000L),
        )
        assertNull(pickDeathNotice(exits, lastSeenAt = 1_000L, backgroundedAt = 1_500L))
    }

    @Test
    fun `顺序无关 系统给什么次序都算得对`() {
        val asc = listOf(exit(ExitReason.LowMemory, 2_000L), exit(ExitReason.Signaled, 8_000L))
        val desc = asc.reversed()
        assertEquals(
            pickDeathNotice(asc, 1_000L, 1_500L)!!.occurredAt,
            pickDeathNotice(desc, 1_000L, 1_500L)!!.occurredAt,
        )
    }

    // ── 基线 ──────────────────────────────────────────────────────────────

    @Test
    fun `基线取最新时刻 即使最新那条是崩溃也要算进去`() {
        // 基线只回答「这批记录我看过了」，与提不提示无关。
        // 若只拿「可提示项」当基线，崩溃那条会被反复重读，下次启动仍可能挑到更早的系统杀。
        val exits = listOf(
            exit(ExitReason.LowMemory, 2_000L),
            exit(ExitReason.Crash, 8_000L),
        )
        assertEquals(8_000L, baselineFor(exits))
    }

    @Test
    fun `空历史基线为 0`() {
        assertEquals(0L, baselineFor(emptyList()))
    }

    // ── 文案 ──────────────────────────────────────────────────────────────

    @Test
    fun `三种原因的标题互不相同 且崩溃不给标题`() {
        val system = deathTitle(DeathCause.SystemKilled)
        val stopped = deathTitle(DeathCause.Stopped)
        assertTrue(system.isNotEmpty())
        assertTrue(stopped.isNotEmpty())
        assertTrue(system != stopped)
        assertEquals("", deathTitle(DeathCause.NotAKill))
        // 真机实测：getSubReason() 不是公开 API，划掉 / 系统 killBackground / force stop
        // 都是 REASON_USER_REQUESTED。标题因此不能说是谁干的。
        assertTrue("Stopped 的标题不能指名道姓说是用户划的", !stopped.contains("划"))
        assertTrue("Stopped 的标题也不能栽给系统", !stopped.contains("系统把"))
    }

    @Test
    fun `系统杀的那条正文要指向保活设置`() {
        val body = deathBody(DeathCause.SystemKilled)
        assertTrue(body, body.contains("保活"))
        // 必须说清「通知不到」这个后果，否则用户不知道改设置能换来什么。
        assertTrue(body, body.contains("通知"))
    }

    @Test
    fun `分不出是谁干的 正文就把两种可能都摆出来`() {
        val body = deathBody(DeathCause.Stopped)
        // 两个可能都要说：自己划掉、系统在后台清掉。只说一种就会冤枉另一半用户。
        assertTrue(body, body.contains("划"))
        assertTrue(body, body.contains("系统"))
        // 后果必须讲清楚，否则用户不知道这条卡片跟自己有什么关系。
        assertTrue(body, body.contains("通知"))
        // 既然分不出来，就一律当作「想让它别停」处理，所以这里可以提保活。
        assertTrue(body, body.contains("保活"))
    }

    @Test
    fun `按钮文案 两种被杀都用去开保活 崩溃不给按钮`() {
        assertEquals("去开保活", deathActionLabel(DeathCause.SystemKilled))
        // 分不出是划掉还是系统清的，两种都指向保活设置：这是唯一能改善现状的动作。
        assertEquals("去开保活", deathActionLabel(DeathCause.Stopped))
        assertEquals("", deathActionLabel(DeathCause.NotAKill))
    }

    // ── 系统常量映射（真机数据定下来的） ────────────────────────────────────

    @Test
    fun `用户划掉与用户停止都算 Stopped 不算 NotAKill`() {
        // REASON_USER_REQUESTED(10) 与 REASON_USER_STOPPED(11) 是同一族。
        // 用字面量而不是 ApplicationExitInfo.REASON_*：那个类在 JVM 上是空桩，
        // 常量读不到（真机上这两个值也确实是 10 和 11）。
        assertEquals(ExitReason.UserRequested, classifySystemReason(10))
        assertEquals(ExitReason.UserRequested, classifySystemReason(11))
        assertEquals(DeathCause.Stopped, classifyExit(classifySystemReason(10)))
        assertEquals(DeathCause.Stopped, classifyExit(classifySystemReason(11)))
    }

    @Test
    fun `低内存与信号杀都算系统杀的`() {
        assertEquals(ExitReason.LowMemory, classifySystemReason(3))
        assertEquals(ExitReason.Signaled, classifySystemReason(2))
        assertEquals(DeathCause.SystemKilled, classifyExit(classifySystemReason(3)))
        assertEquals(DeathCause.SystemKilled, classifyExit(classifySystemReason(2)))
    }

    @Test
    fun `崩溃与 ANR 归到 Crash 不给提示`() {
        listOf(4, 5, 6, 7).forEach { code ->
            assertEquals("reason=$code", ExitReason.Crash, classifySystemReason(code))
            assertEquals(DeathCause.NotAKill, classifyExit(classifySystemReason(code)))
        }
    }

    @Test
    fun `系统更新与安装包替换不算被杀 否则每次升级都弹一次`() {
        listOf(1, 8, 9, 12, 15, 16).forEach { code ->
            assertEquals("reason=$code", ExitReason.SystemHousekeeping, classifySystemReason(code))
            assertEquals(DeathCause.NotAKill, classifyExit(classifySystemReason(code)))
        }
    }

    @Test
    fun `不认识的系统原因不提示 系统版本比我们新时宁可少说`() {
        assertEquals(ExitReason.Unknown, classifySystemReason(999))
        assertEquals(DeathCause.NotAKill, classifyExit(classifySystemReason(999)))
    }

}
