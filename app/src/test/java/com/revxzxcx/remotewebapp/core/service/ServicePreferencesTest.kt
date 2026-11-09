package com.revxzxcx.remotewebapp.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 后台连接的三个开关 + 失败原因（R057）。
 *
 * ## 为什么用 Robolectric 而不是纯 JVM
 * 这个类的全部价值在「**真写进 SharedPreferences、进程重启后能读回**」。
 * 纯 JVM 上 `SharedPreferences` 是空实现，测出来的只是「我调用了 setter」。
 *
 * ## 三个默认值一个都不能改
 * - `foregroundEnabled` 默认 **关**：会多一条常驻通知，用户没要求就不该做。
 * - `autoRecover` 默认 **开**：静默拉起，纯收益；代价已由上一项承担。
 * - `bootStart` 默认 **关**：开机自启是额外的后台行为。
 *
 * 任何一个默认值写反，用户都会在完全没意识到的情况下多出后台行为——
 * 这正是「默认值」这一类错误最难被发现的地方：没有任何人会去点那个开关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServicePreferencesTest {

    private lateinit var context: Context
    private lateinit var prefs: ServicePreferences

    private val host = "b.zcsb.ltd"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dsh-link-service", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = ServicePreferences(context)
    }

    @Test
    fun `总开关默认关 用户没要求就不该多一条常驻通知`() {
        assertFalse(prefs.foregroundEnabled(host))
    }

    @Test
    fun `自动恢复默认开 它是纯收益不打扰`() {
        assertTrue(prefs.autoRecover(host))
    }

    @Test
    fun `开机自启默认关 额外的后台行为要用户自己选`() {
        assertFalse(prefs.bootStart(host))
    }

    @Test
    fun `默认没看过确认框`() {
        assertFalse(prefs.confirmSeen(host))
    }

    @Test
    fun `默认没有失败原因`() {
        assertEquals("", prefs.lastFailure(host))
    }

    @Test
    fun `三个开关各自持久化 新实例能读回`() {
        prefs.setForegroundEnabled(host, true)
        prefs.setAutoRecover(host, false)
        prefs.setBootStart(host, true)
        val reopened = ServicePreferences(context)
        assertTrue(reopened.foregroundEnabled(host))
        assertFalse(reopened.autoRecover(host))
        assertTrue(reopened.bootStart(host))
    }

    @Test
    fun `三个开关互不影响`() {
        prefs.setForegroundEnabled(host, true)
        assertTrue("改总开关不该动自动恢复", prefs.autoRecover(host))
        assertFalse("改总开关不该动开机自启", prefs.bootStart(host))
    }

    @Test
    fun `按主机分开记 换个主机就是另一套`() {
        prefs.setForegroundEnabled(host, true)
        assertFalse("用户在 A 机器开的开关不该摊到 B 机器上", prefs.foregroundEnabled("127.0.0.1:19387"))
    }

    @Test
    fun `同一主机名的开关不会互相串`() {
        // 前缀相同的两个主机名（a.com / a.com.cn）如果 key 拼接有歧义就会串。
        prefs.setForegroundEnabled("a.com", true)
        prefs.setBootStart("a.com.cn", true)
        assertFalse(prefs.bootStart("a.com"))
        assertFalse(prefs.foregroundEnabled("a.com.cn"))
    }

    @Test
    fun `没有主机时用特殊后缀 不抛异常`() {
        // 设置页在配对页也可能被打开，那时还没有 activeHost。
        assertFalse(prefs.foregroundEnabled(""))
        prefs.setForegroundEnabled("", true)
        assertTrue(prefs.foregroundEnabled(""))
        assertFalse("空主机名不该污染正常主机的开关", prefs.foregroundEnabled(host))
    }

    @Test
    fun `失败原因能写能清`() {
        prefs.setLastFailure(host, "通知不可用（未授权或已被关闭）")
        assertEquals("通知不可用（未授权或已被关闭）", prefs.lastFailure(host))
        prefs.setLastFailure(host, "")
        assertEquals("", prefs.lastFailure(host))
    }

    @Test
    fun `失败原因也按主机分开`() {
        prefs.setLastFailure(host, "boom")
        assertEquals("", prefs.lastFailure("other.host"))
    }

    @Test
    fun `确认框看过一次就记住`() {
        prefs.setConfirmSeen(host, true)
        assertTrue(ServicePreferences(context).confirmSeen(host))
    }
}
