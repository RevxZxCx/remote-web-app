package com.revxzxcx.remotewebapp.core.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.revxzxcx.remotewebapp.core.session.AgentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新会话默认模式的记忆（R055 Q3：记住上次的，**按主机分开记**）。
 *
 * 用 Robolectric 而不是纯 JVM：这一层的全部价值就在「真的写进了 SharedPreferences、
 * 换一个实例还能读回来」。纯 JVM 里只能断内存 Map，铺不到持久化那条路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PresetPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fresh(): PresetPreferences {
        context.getSharedPreferences("dsh-agent-preset", Context.MODE_PRIVATE).edit().clear().commit()
        return PresetPreferences(context)
    }

    @Test
    fun `没有记录时用默认档`() {
        val prefs = fresh()
        assertEquals(AgentPreset.DEFAULT, prefs.presetFor("host-a"))
        assertEquals(AgentPreset.STANDARD, prefs.presetFor("host-a"))
    }

    @Test
    fun `记住之后同一个实例能读回`() {
        val prefs = fresh()
        prefs.remember("host-a", AgentPreset.CORDIS)
        assertEquals(AgentPreset.CORDIS, prefs.presetFor("host-a"))
    }

    @Test
    fun `换一个实例也能读回 这才叫记住`() {
        fresh().remember("host-a", AgentPreset.MINIMAL)
        assertEquals(AgentPreset.MINIMAL, PresetPreferences(context).presetFor("host-a"))
    }

    @Test
    fun `按主机分开记 一台改了不影响另一台`() {
        val prefs = fresh()
        prefs.remember("host-a", AgentPreset.PTC)
        prefs.remember("host-b", AgentPreset.CORDIS)
        assertEquals(AgentPreset.PTC, prefs.presetFor("host-a"))
        assertEquals(AgentPreset.CORDIS, prefs.presetFor("host-b"))
        assertEquals(AgentPreset.DEFAULT, prefs.presetFor("host-c"))
    }

    @Test
    fun `空主机名不写 避免生成一个读不回来的孤儿键`() {
        val prefs = fresh()
        prefs.remember("", AgentPreset.CORDIS)
        prefs.remember("   ", AgentPreset.CORDIS)
        assertEquals(AgentPreset.DEFAULT, prefs.presetFor(""))
        assertFalse(prefs.presets.value.containsKey(""))
        assertFalse(prefs.presets.value.containsKey("   "))
    }

    @Test
    fun `盘上有不认识的 wire 值时退回默认档 而不是崩`() {
        context.getSharedPreferences("dsh-agent-preset", Context.MODE_PRIVATE)
            .edit().putString("preset_host-a", "brand-new-preset").commit()
        val prefs = PresetPreferences(context)
        assertEquals(AgentPreset.DEFAULT, prefs.presetFor("host-a"))
        // 不认识的值也不该留在镜像表里，否则「这台有没有偏好」的判断会含糊。
        assertFalse(prefs.presets.value.containsKey("host-a"))
    }

    @Test
    fun `盘上其它前缀的键不会被当成本类的数据`() {
        context.getSharedPreferences("dsh-agent-preset", Context.MODE_PRIVATE)
            .edit().putString("something-else", "cordis").commit()
        val prefs = PresetPreferences(context)
        assertEquals(emptyMap<String, AgentPreset>(), prefs.presets.value)
    }

    @Test
    fun `两个实例之间不会互相覆盖已经写下的值`() {
        val a = fresh()
        a.remember("host-a", AgentPreset.PTC)
        val b = PresetPreferences(context)
        b.remember("host-b", AgentPreset.CORDIS)
        // b 是从盘上读出来的，所以它已经包含 host-a；再写 host-b 不会抹掉前者。
        assertEquals(AgentPreset.PTC, PresetPreferences(context).presetFor("host-a"))
        assertEquals(AgentPreset.CORDIS, PresetPreferences(context).presetFor("host-b"))
    }
}
