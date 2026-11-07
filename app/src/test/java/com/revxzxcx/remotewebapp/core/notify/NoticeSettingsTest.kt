package com.revxzxcx.remotewebapp.core.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知偏好（R056）。
 *
 * 用 Robolectric 而不是纯 JVM：这一层的全部价值就在「真的写进了 SharedPreferences、
 * **进程重启后还能读回来**」。`permissionRequested` 一旦丢了，App 下次冷启动又会
 * 弹一次权限框，而永久拒绝之后那次弹框是不显示的——用户只会觉得按钮坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoticeSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fresh(): NoticeSettings {
        context.getSharedPreferences("dsh-notice", Context.MODE_PRIVATE).edit().clear().commit()
        return NoticeSettings(context)
    }

    @Test
    fun `默认两类提醒都开着`() {
        val settings = fresh()
        assertTrue("装完 App 一条通知都不发，用户会以为功能没做", settings.doneEnabled)
        assertTrue(settings.askEnabled)
    }

    @Test
    fun `默认锁屏显示详细内容`() {
        assertTrue(fresh().lockScreenDetail)
    }

    @Test
    fun `默认没请求过权限`() {
        assertFalse(fresh().permissionRequested)
    }

    @Test
    fun `问过权限后换一个实例仍记得`() {
        val settings = fresh()
        settings.permissionRequested = true
        assertTrue(
            "记不住的话永久拒绝后每次冷启动又弹一次框，而系统那时根本不显示界面",
            NoticeSettings(context).permissionRequested,
        )
    }

    @Test
    fun `关掉任务结束后换一个实例仍记得`() {
        val settings = fresh()
        settings.doneEnabled = false
        assertFalse(NoticeSettings(context).doneEnabled)
    }

    @Test
    fun `关掉需要确认后换一个实例仍记得`() {
        val settings = fresh()
        settings.askEnabled = false
        assertFalse(NoticeSettings(context).askEnabled)
    }

    @Test
    fun `关掉锁屏详情后换一个实例仍记得`() {
        val settings = fresh()
        settings.lockScreenDetail = false
        assertFalse(NoticeSettings(context).lockScreenDetail)
    }

    @Test
    fun `两个频道开关互不影响`() {
        val settings = fresh()
        settings.doneEnabled = false
        assertTrue("关掉一个不能顺手把另一个也关了", settings.askEnabled)
        settings.askEnabled = false
        settings.doneEnabled = true
        assertTrue(settings.doneEnabled)
        assertFalse(settings.askEnabled)
    }

    @Test
    fun `频道可用要求系统频道与 App 内开关同时成立`() {
        val settings = fresh()
        assertTrue(settings.effectiveEnabled(NoticeChannels.DONE, channelEnabled = true))
        assertFalse(
            "系统频道关掉时 notify 会静默失败，App 内开关开着也没用",
            settings.effectiveEnabled(NoticeChannels.DONE, channelEnabled = false),
        )
        settings.doneEnabled = false
        assertFalse(
            "App 内关掉是我们主动不发",
            settings.effectiveEnabled(NoticeChannels.DONE, channelEnabled = true),
        )
    }

    @Test
    fun `关掉审批开关后 审批频道不可用 任务结束频道不受影响`() {
        val settings = fresh()
        settings.setChannelEnabled(NoticeChannels.ASK, false)
        assertFalse(settings.effectiveEnabled(NoticeChannels.ASK, channelEnabled = true))
        assertTrue(settings.effectiveEnabled(NoticeChannels.DONE, channelEnabled = true))
    }

    @Test
    fun `setChannelEnabled 与属性读写一致`() {
        val settings = fresh()
        settings.setChannelEnabled(NoticeChannels.DONE, false)
        assertFalse(settings.doneEnabled)
        assertFalse(settings.appEnabled(NoticeChannels.DONE))
        settings.setChannelEnabled(NoticeChannels.DONE, true)
        assertTrue(settings.doneEnabled)
        assertTrue(settings.appEnabled(NoticeChannels.DONE))
    }

    @Test
    fun `未知频道 id 不写坏偏好`() {
        val settings = fresh()
        settings.setChannelEnabled("unknown-channel", false)
        assertTrue("未知频道不该顺手关掉任何一个已知开关", settings.doneEnabled)
        assertTrue(settings.askEnabled)
    }

    @Test
    fun `未知频道默认可用`() {
        assertTrue(
            "未知频道没有对应开关，不该被当成关掉",
            fresh().effectiveEnabled("unknown-channel", channelEnabled = true),
        )
    }

    @Test
    fun `未知频道仍然受系统频道状态约束`() {
        assertFalse(fresh().effectiveEnabled("unknown-channel", channelEnabled = false))
    }

    @Test
    fun `appEnabled 读的是原始值 不受系统频道影响`() {
        val settings = fresh()
        settings.doneEnabled = false
        assertFalse(
            "appEnabled 要能区分「用户嫌烦」与「系统静音」，所以不能把频道状态合进来",
            settings.appEnabled(NoticeChannels.DONE),
        )
    }
}

/**
 * 会话标题表（R056）。
 *
 * 这里守的是「**空标题绝不覆盖已有标题**」这一条。事件流里的 `api-session/added`
 * 有时带来还没起名的会话（`title` 是空串），而首页列表里那条已经有名字了；
 * 若无条件覆盖，用户会看到通知标题从「任务结束 · 修登录接口」退化成一串 id，
 * 而且**不会自己恢复**——列表下次刷新才可能再灌回来。
 */
class SessionNameTableTest {

    @Test
    fun `记过的标题能取回`() {
        val table = SessionNameTable()
        table.remember("s1", "修登录接口")
        assertEquals("修登录接口", table.titleOf("s1"))
    }

    @Test
    fun `没记过的返回 null 而不是空串`() {
        assertNull(
            "返回空串会让调用方分不清「记着空标题」与「没记过」，也就没法走兜底名",
            SessionNameTable().titleOf("nope"),
        )
    }

    @Test
    fun `空标题不记`() {
        val table = SessionNameTable()
        table.remember("s1", "")
        assertNull(table.titleOf("s1"))
        assertEquals(0, table.size)
    }

    @Test
    fun `空标题不覆盖已有标题`() {
        val table = SessionNameTable()
        table.remember("s1", "修登录接口")
        table.remember("s1", "")
        assertEquals(
            "被空标题覆盖后不会自己恢复，用户只会看到一串 id",
            "修登录接口",
            table.titleOf("s1"),
        )
    }

    @Test
    fun `空白 sessionId 不记`() {
        val table = SessionNameTable()
        table.remember("", "某标题")
        assertEquals(0, table.size)
    }

    @Test
    fun `整批记入时跳过空标题 保留其余`() {
        val table = SessionNameTable()
        table.remember(
            listOf(
                "s1" to "甲",
                "s2" to "",
                "s3" to "丙",
            ),
        )
        assertEquals("甲", table.titleOf("s1"))
        assertNull(table.titleOf("s2"))
        assertEquals("丙", table.titleOf("s3"))
        assertEquals(2, table.size)
    }

    @Test
    fun `整批记入不会丢掉之前的条目`() {
        val table = SessionNameTable()
        table.remember("s1", "甲")
        table.remember(listOf("s2" to "乙"))
        assertEquals("列表刷新只带来新会话时，旧的标题不能凭空消失", "甲", table.titleOf("s1"))
        assertEquals("乙", table.titleOf("s2"))
    }

    @Test
    fun `整批记入时新标题覆盖旧标题`() {
        val table = SessionNameTable()
        table.remember("s1", "旧名")
        table.remember(listOf("s1" to "新名"))
        assertEquals("用户在桌面端改了会话名，通知里该跟着变", "新名", table.titleOf("s1"))
    }

    @Test
    fun `空批次什么都不做`() {
        val table = SessionNameTable()
        table.remember("s1", "甲")
        table.remember(emptyList())
        assertEquals(1, table.size)
    }

    @Test
    fun `forget 之后取不到`() {
        val table = SessionNameTable()
        table.remember("s1", "甲")
        table.forget("s1")
        assertNull(table.titleOf("s1"))
        assertEquals(0, table.size)
    }

    @Test
    fun `forget 不存在的键不出错`() {
        val table = SessionNameTable()
        table.forget("nope")
        assertEquals(0, table.size)
    }

    @Test
    fun `重复记同一个标题不改变内容`() {
        val table = SessionNameTable()
        table.remember("s1", "甲")
        table.remember("s1", "甲")
        assertEquals("甲", table.titleOf("s1"))
        assertEquals(1, table.size)
    }

    @Test
    fun `换主机清空标题表 别人的会话名不能挂到这台的同名 id 上`() {
        val table = SessionNameTable()
        table.remember("s-1", "A 机器的会话")
        assertEquals("A 机器的会话", table.titleOf("s-1"))
        table.clear()
        // 不清的话，B 主机上的 s-1 发通知会显示 A 机器那个会话的名字，
        // 而用户根本没在 B 上开过它。
        assertNull(table.titleOf("s-1"))
        assertEquals(0, table.size)
    }

    @Test
    fun `清空之后还能重新记 不是把表弄坏了`() {
        val table = SessionNameTable()
        table.remember("s-1", "旧")
        table.clear()
        table.remember("s-1", "新")
        assertEquals("新", table.titleOf("s-1"))
    }

    @Test
    fun `空表上清空不出错`() {
        val table = SessionNameTable()
        table.clear()
        assertEquals(0, table.size)
    }

}