package com.revxzxcx.remotewebapp.ui.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B002 的回归测试：扫码失败之后「重新扫描」必须能让**同一个**控件重新开始解码。
 *
 * 缺陷原文（用户）："当使用扫码配对功能时，扫码配对失败后（显示红色提醒报错）扫码功能
 * 将不再可用，也就是扫码框将不识别二维码，这种情况只有退出界面重进才能解决。"
 *
 * 根因（zxing-android-embedded 4.3.0 字节码实证）：`decodeSingle` 送达一次结果后，
 * `BarcodeView.handleMessage` 会顺手 `stopDecoding()`，而 `stopDecoding()` 把
 * `callback` 置空、`decodeMode` 归 `NONE`。旧实现靠 `key(scanEpoch)` 重建整个
 * `AndroidView` 来"复位"，但重建会与旧控件的相机关闭抢同一个摄像头，新控件在
 * `isPreviewActive() == false` 时 `startDecoderThread()` 直接 return 且无人重试——
 * 于是扫码框永久失效。
 *
 * 这组用例锁死三件事：
 * ① 闸门落下后确实不再交付（复现缺陷的现象，保证测试不是在测空气）；
 * ② `rescan()` 之后**同一个** rearm 目标被重新武装，且能再次交付；
 * ③ 控件不在时 `rescan()` 不会丢失这次重新扫描，等控件挂上来补做。
 */
class ScanChannelTest {

    /** 记录被要求重新武装了几次，以及是否始终是同一个实例。 */
    private class FakeRearmer(
        val name: String,
        /** 模拟「rearmer 已挂上但 AndroidView.factory 还没把控件塞进来」的窗口。 */
        var reachable: Boolean = true,
    ) : ScanRearmer {
        var rearmCount = 0

        override fun rearmScan(): Boolean {
            if (!reachable) return false
            rearmCount++
            return true
        }
    }

    @Test
    fun `扫到一次就交付一次并且闸门落下`() {
        val channel = ScanChannel()
        channel.attach(FakeRearmer("v"))

        assertEquals("PAIR-TOKEN", channel.onResult("PAIR-TOKEN", enabled = true))
        assertTrue(channel.isConsumed)
    }

    @Test
    fun `闸门落下后同一个控件再送结果也不交付`() {
        // 这一条复现用户看到的现象：`decodeSingle` 已经用掉，再扫没有任何反应。
        val channel = ScanChannel()
        channel.attach(FakeRearmer("v"))

        channel.onResult("FIRST", enabled = true)

        assertNull(channel.onResult("SECOND", enabled = true))
        assertNull(channel.onResult("THIRD", enabled = true))
    }

    @Test
    fun `重新扫描之后对同一个控件重新武装而不是新建控件`() {
        val channel = ScanChannel()
        val rearmer = FakeRearmer("same")
        channel.attach(rearmer)
        channel.onResult("FIRST", enabled = true)

        val outcome = channel.rescan()

        assertEquals(ScanRescanOutcome.Rearmed, outcome)
        // 关键断言：重新武装落在**被 attach 的那一个**实例上。若实现改成"新建一个控件"，
        // 新实例的 rearmCount 会是 1 而这一个仍是 0——那正是 B002 的成因。
        assertEquals(1, rearmer.rearmCount)
        assertFalse(channel.isConsumed)
    }

    @Test
    fun `重新扫描之后能再交付一次 这就是缺陷本身`() {
        // 用户报的就是这条：配对失败后点「重新扫描」，必须还能扫。
        val channel = ScanChannel()
        channel.attach(FakeRearmer("v"))
        assertEquals("FAILED-TOKEN", channel.onResult("FAILED-TOKEN", enabled = true))

        channel.rescan()

        assertEquals("RETRY-TOKEN", channel.onResult("RETRY-TOKEN", enabled = true))
    }

    @Test
    fun `控件还没挂上时重新扫描不丢 等挂上补做`() {
        val channel = ScanChannel()
        channel.onResult("FIRST", enabled = true)

        // 控件此刻不在（还没建好或刚释放）。
        assertEquals(ScanRescanOutcome.Deferred, channel.rescan())

        // 挂上时必须把欠着的那次补做，否则"重新扫描"就白点了。
        val rearmer = FakeRearmer("late")
        channel.attach(rearmer)
        assertEquals(1, rearmer.rearmCount)
    }

    @Test
    fun `闸门没落时重新扫描是空操作 不打断底层`() {
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        assertEquals(ScanRescanOutcome.NotConsumed, channel.rescan())
        assertEquals(0, rearmer.rearmCount)
    }

    @Test
    fun `旧控件释放不会把新控件的引用抹掉`() {
        val channel = ScanChannel()
        val old = FakeRearmer("old")
        channel.attach(old)
        val fresh = FakeRearmer("new")
        channel.attach(fresh)

        // 旧控件的 onRelease 晚到一步。
        channel.detach(old)

        channel.onResult("FIRST", enabled = true)
        assertEquals(ScanRescanOutcome.Rearmed, channel.rescan())
        // 被重新武装的必须是新控件。
        assertEquals(1, fresh.rearmCount)
        assertEquals(0, old.rearmCount)
    }

    @Test
    fun `配对进行中扫到不交付 但配对结束后补一次武装`() {
        // 真实时序：扫到码 → 回调里 `enabled` 已经是 false（VM 先置 busy）→ 这次结果被丢掉，
        // 而底层的 `stopDecoding()` 已经发生。若不记这笔欠账，通道就此静默死掉，
        // 用户看到的是"配对失败了，扫码框也不动了"。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        assertNull(channel.onResult("WHILE-BUSY", enabled = false))
        assertFalse(channel.isConsumed)
        assertEquals(0, rearmer.rearmCount)

        // 配对结束。
        channel.onEnabled(true)
        assertEquals(1, rearmer.rearmCount)
    }

    @Test
    fun `空白文本不算扫到 但必须把底层重新武装起来`() {
        // zxing 的 `handleMessage` 只要求 `msg.obj != null` 就叫「解码成功」，
        // 走出来之后它已经 `stopDecoding()` 过了，与文本是否为空无关。若这里只
        // return 而不重新武装，控件就再也不会回调任何人，而界面又因为闸门没落下
        // 不显示「重新扫描」——通道静默死掉，表现与 B002 一致。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        assertNull(channel.onResult("   ", enabled = true))
        assertFalse(channel.isConsumed)
        assertEquals("空白结果也必须补一次武装", 1, rearmer.rearmCount)

        assertNull(channel.onResult(null, enabled = true))
        assertFalse(channel.isConsumed)
        assertEquals("null 同样意味着底层已经走过一轮", 2, rearmer.rearmCount)

        // 之后仍然能正常交付一次。
        assertEquals("OK", channel.onResult("OK", enabled = true))
        assertTrue(channel.isConsumed)
    }

    @Test
    fun `切走标签再切回来 新控件的第一个码不能被旧闸门吞掉`() {
        // 用户切到「链接」再切回「扫码」时，ScanCard 整个离开组合又重新进来，
        // 新控件的 factory 里已经 decodeSingle(callback) 武装好了。闸门如果还停在
        // 上一轮控件的「已扫过」，新控件收到的第一个码会被静默丢弃，界面却还显示
        // 「重新扫描」——这跟 B002 的表现一模一样，只是触发入口换了。
        val channel = ScanChannel()
        val old = FakeRearmer("old")
        channel.attach(old)
        assertEquals("A", channel.onResult("A", enabled = true))
        assertTrue(channel.isConsumed)

        // 切走：旧控件释放。
        channel.detach(old)
        // 切回：新控件挂上，factory 里已经重新武装过了。
        val fresh = FakeRearmer("fresh")
        channel.attach(fresh)

        assertFalse("新控件已经武装，闸门必须跟着复位", channel.isConsumed)
        assertEquals("新控件的第一个码必须能交付", "B", channel.onResult("B", enabled = true))
    }

    @Test
    fun `配对进行中扫到空白 也不能立刻补武装`() {
        // 分支顺序：`!enabled` 必须比「空文本补武装」先判。否则配对正在进行时
        // 扫到一个空结果，会在配对没结束时就把解码线程重新拉起来，与正在进行
        // 的那次配对抢解码线程。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        assertNull(channel.onResult("", enabled = false))
        assertEquals("配对中不得立刻重新武装", 0, rearmer.rearmCount)

        channel.onEnabled(true)
        assertEquals("配对结束后再补", 1, rearmer.rearmCount)
    }

    @Test
    fun `多次重新扫描每次都恰好武装一次 不叠加`() {
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        channel.onResult("A", enabled = true)
        channel.rescan()
        channel.rescan() // 闸门还没落下，这次是空操作
        channel.onResult("B", enabled = true)
        channel.rescan()

        assertEquals(2, rearmer.rearmCount)
    }

    @Test
    fun `后台期间补武装失败 欠账必须留着 回前台再补`() {
        // 配对进行中把 App 切到后台、配对随后失败时，`enabled` 会在后台变回 true，
        // 触发补武装。而补武装要 resume() 才能起解码线，那等于在用户看不见的时候
        // 打开摄像头，抵消 R035。所以 rearmer 在不可用时返回 false，欠账保留。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v", reachable = false)
        channel.attach(rearmer)
        channel.onResult("A", enabled = true)

        assertEquals(ScanRescanOutcome.Deferred, channel.rescan())
        channel.onEnabled(true)
        assertEquals("后台不得补武装", 0, rearmer.rearmCount)

        // 回到前台。
        rearmer.reachable = true
        channel.onActive()
        assertEquals("回前台必须补上", 1, rearmer.rearmCount)
    }

    @Test
    fun `回前台但没欠账时 onActive 不空转`() {
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)

        channel.onActive()
        assertEquals(0, rearmer.rearmCount)

        channel.onResult("A", enabled = true)
        channel.onActive()
        assertEquals("闸门落着就不该再武装", 0, rearmer.rearmCount)
    }

    @Test
    fun `权限一变控件重建 新控件的第一个码不能被旧闸门吞掉`() {
        // 相机权限被撤掉又授予时，`hasPermission` 一变 `AndroidView` 就整个离开组合再
        // 回来，配件重跑 factory；但 attach 所在的 DisposableEffect 的 key 里没有
        // hasPermission，不会重跑。闸门如果只在 attach 里复位，这条路径就会一直关着，
        // 新控件的第一个码被静默丢弃——白扫一次码。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)
        assertEquals("A", channel.onResult("A", enabled = true))
        assertTrue(channel.isConsumed)

        // 控件重建（没有 detach / attach），factory 里重新武装。
        channel.onControlArmed()

        assertFalse("刚武装的控件一定能交付，闸门必须复位", channel.isConsumed)
        assertEquals("B", channel.onResult("B", enabled = true))
    }

    @Test
    fun `控件重建时配对还在进行中 复位闸门也不会让同一个 token 提交两次`() {
        // onControlArmed 会复位闸门，但复位不等于可以重复提交：配对进行中
        // （enabled == false）扫到的码只记欠账不交付，这条分支顺序必须保持。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v")
        channel.attach(rearmer)
        channel.onResult("A", enabled = true)

        channel.onControlArmed()
        assertNull("配对进行中不得交付", channel.onResult("A", enabled = false))

        channel.onEnabled(true)
        assertEquals("配对结束后补一次武装", 1, rearmer.rearmCount)
    }

    @Test
    fun `新控件建好并武装之后 欠账必须销掉 不能事后多武装一次`() {
        // 欠账的产生时机是「控件不在」。而 AndroidView.factory 无条件会给新控件
        // 注册一次 decodeSingle，所以欠账到新控件建好时其实已经被满足了。若不在
        // factory 里销账，pendingRearm 会跨控件一直挂着，之后任意一次 enabled
        // 抖动都会多触发一次 decodeSingle——多拉一条解码线程去抢同一个摄像头。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v", reachable = false)
        channel.attach(rearmer)
        channel.onResult("A", enabled = true)

        assertEquals(ScanRescanOutcome.Deferred, channel.rescan())
        assertEquals(0, rearmer.rearmCount)

        // factory 跑起来：控件塞进 rearmer，并销账。
        rearmer.reachable = true
        channel.onControlArmed()

        channel.onEnabled(true)
        assertEquals("欠账已被新控件满足，不能再补一次", 0, rearmer.rearmCount)
        assertEquals("B", channel.onResult("B", enabled = true))
    }

    @Test
    fun `控件还不可用时欠账要留着 等真能武装了再补`() {
        // DisposableEffect 先于 AndroidView.factory 执行：rearmer 已经挂上，而
        // `scanner` 还是 null，此时 rearmScan() 打不到控件。这一笔不能算已经补过。
        val channel = ScanChannel()
        val rearmer = FakeRearmer("v", reachable = false)
        channel.attach(rearmer)
        channel.onResult("A", enabled = true)

        assertEquals(ScanRescanOutcome.Deferred, channel.rescan())
        assertEquals(0, rearmer.rearmCount)

        // 控件就绪（factory 跑过之后）再补一次。
        rearmer.reachable = true
        channel.attach(rearmer)
        assertEquals(1, rearmer.rearmCount)
        assertEquals("B", channel.onResult("B", enabled = true))
    }
}
