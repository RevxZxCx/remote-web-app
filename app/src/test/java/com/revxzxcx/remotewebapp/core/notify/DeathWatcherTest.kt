package com.revxzxcx.remotewebapp.core.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DeathWatcher] 的编排逻辑（R056 / Q3）。
 *
 * 用 Robolectric 是因为它真正的价值在「写盘 → 下次启动读回」这条路上：基线存在
 * `SharedPreferences` 里，纯 JVM 里没有 `SharedPreferences`，测出来的东西和真机不是一回事。
 *
 * [ExitSource] 是 `fun interface`，所以每个用例都能给一份固定的退出历史——
 * `ApplicationExitInfo` 在 JVM 上是空桩，真读不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeathWatcherTest {

    private lateinit var settings: NoticeSettings

    @Before
    fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        settings = NoticeSettings(app)
    }

    private fun watcher(
        exits: List<ExitFacts>,
        now: Long = 10_000L,
    ) = DeathWatcher(settings = settings, reader = { exits }, clock = { now })

    private fun exit(reason: ExitReason, at: Long) = ExitFacts(reason = reason, timestamp = at)

    // ── 首次启动 ──────────────────────────────────────────────────────────

    @Test
    fun `第一次启动只建基线不提示 系统历史里的旧记录不能拿来吓人`() {
        val w = watcher(listOf(exit(ExitReason.LowMemory, 5_000L)))
        settings.backgroundedAt = 1_000L
        w.probe()
        assertNull(w.pending.value)
        assertEquals(5_000L, settings.deathBaselineAt)
    }

    @Test
    fun `第一次启动即使没有后台记录也要把基线写掉`() {
        // 否则「装完 → 进来 → 什么都没做」这条路永远建不出基线，
        // 之后每次启动都因为 lastSeenAt <= 0 一路返回 null，提示永远不出现。
        val w = watcher(listOf(exit(ExitReason.LowMemory, 5_000L)))
        w.probe()
        assertEquals(5_000L, settings.deathBaselineAt)
    }

    @Test
    fun `历史为空时基线不动 不能把已有的基线抹成 0`() {
        settings.deathBaselineAt = 7_000L
        val w = watcher(emptyList())
        w.probe()
        assertEquals(7_000L, settings.deathBaselineAt)
        assertNull(w.pending.value)
    }

    // ── 被杀之后 ──────────────────────────────────────────────────────────

    @Test
    fun `第二次启动读到新的系统杀 要提示一次并推进基线`() {
        settings.deathBaselineAt = 1_000L
        settings.backgroundedAt = 2_000L
        val w = watcher(listOf(exit(ExitReason.Signaled, 6_000L)))
        w.probe()
        val notice = w.pending.value
        assertNotNull(notice)
        assertEquals(DeathCause.SystemKilled, notice!!.cause)
        assertEquals(6_000L, notice.occurredAt)
        assertEquals(6_000L, settings.deathBaselineAt)
    }

    @Test
    fun `同一条历史不能被提示两次 下一次启动就是沉默`() {
        settings.deathBaselineAt = 1_000L
        settings.backgroundedAt = 2_000L
        val exits = listOf(exit(ExitReason.LowMemory, 6_000L))

        val first = watcher(exits)
        first.probe()
        assertNotNull(first.pending.value)

        // 模拟进程重启：换一个新的 watcher，读同一批系统历史。
        val second = watcherAfterRestart(exits)
        assertNull(second.pending.value)
    }

    private fun watcherAfterRestart(exits: List<ExitFacts>): DeathWatcher {
        val w = DeathWatcher(settings = settings, reader = { exits })
        w.probe()
        return w
    }

    @Test
    fun `用户划掉的也提示 但原因说成划掉`() {
        settings.deathBaselineAt = 1_000L
        settings.backgroundedAt = 2_000L
        val w = watcher(listOf(exit(ExitReason.UserRequested, 6_000L)))
        w.probe()
        assertEquals(DeathCause.Stopped, w.pending.value!!.cause)
    }

    @Test
    fun `崩溃之后不提示 但基线照样推进`() {
        settings.deathBaselineAt = 1_000L
        settings.backgroundedAt = 2_000L
        val w = watcher(listOf(exit(ExitReason.Crash, 6_000L)))
        w.probe()
        assertNull(w.pending.value)
        // 基线必须推进：否则这次崩溃会一直被当成「新的」，把更早的那条系统杀
        // 永远压在下面（或者说，每次启动都要重新判断一遍这条已看过的记录）。
        assertEquals(6_000L, settings.deathBaselineAt)
    }

    @Test
    fun `没记到过进后台的时刻就不提示`() {
        settings.deathBaselineAt = 1_000L
        val w = watcher(listOf(exit(ExitReason.LowMemory, 6_000L)))
        w.probe()
        assertNull(w.pending.value)
    }

    // ── consume / onBackgrounded ──────────────────────────────────────────

    @Test
    fun `consume 之后不再提示 但基线不回退`() {
        settings.deathBaselineAt = 1_000L
        settings.backgroundedAt = 2_000L
        val w = watcher(listOf(exit(ExitReason.LowMemory, 6_000L)))
        w.probe()
        assertNotNull(w.pending.value)

        w.consume()
        assertNull(w.pending.value)
        // 基线不回退：回退会让同一条死亡在下次启动时再弹一次。
        assertEquals(6_000L, settings.deathBaselineAt)
    }

    @Test
    fun `进后台会记下当前时刻`() {
        val w = watcher(emptyList(), now = 12_345L)
        w.onBackgrounded()
        assertEquals(12_345L, settings.backgroundedAt)
    }

    @Test
    fun `再次进后台会刷新时刻 不能留着上一次的旧值`() {
        // 「先进后台 → 回前台 → 再进后台 → 被杀」这条路上，若沿用旧值，
        // 判据 timestamp >= backgroundedAt 仍然成立（不误伤）；
        // 但反过来会把新一次死亡错判成旧的，所以必须每次刷新。
        val w = watcher(emptyList(), now = 1_000L)
        w.onBackgrounded()
        val later = DeathWatcher(settings = settings, reader = { emptyList() }, clock = { 9_000L })
        later.onBackgrounded()
        assertEquals(9_000L, settings.backgroundedAt)
    }

    // ── 偏好落盘 ──────────────────────────────────────────────────────────

    @Test
    fun `基线与后台时刻都真的写进了偏好 换一个实例能读回`() {
        settings.deathBaselineAt = 4_242L
        settings.backgroundedAt = 1_234L

        // 「换一个实例」= 模拟进程重启后新建的 NoticeSettings。
        val app: Application = ApplicationProvider.getApplicationContext()
        val reread = NoticeSettings(app)
        assertEquals(4_242L, reread.deathBaselineAt)
        assertEquals(1_234L, reread.backgroundedAt)
    }

    @Test
    fun `默认基线与后台时刻都是 0 表示还没有记录`() {
        assertEquals(0L, settings.deathBaselineAt)
        assertEquals(0L, settings.backgroundedAt)
    }
}
