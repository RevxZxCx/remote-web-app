package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话详情侧栏里几处纯计算的几何与格式。
 *
 * ## 为什么这些要抽成纯函数来测
 *
 * 侧栏的三张图（构成圆环、耗时圆环、堆叠上下文条）全靠 `Canvas` 画，Robolectric 下
 * 像素读不到、动画也不推进。能守住的只有「给定占比算出的弧」和「给定字节算出的文案」。
 *
 * ## 这些断言各自防的是什么
 *
 * 都是「画错了不会崩、但一眼假」的那一类：段与段首尾不接（圆环缺一道口子）、缝宽
 * 算错把占比 0.1% 的小段吃光（用户会以为那段数据丢了）、相邻段没有缝（两种颜色糊成
 * 一条，看上去像一段）。这类缺陷没有单测就只能靠人盯截图，而截图不会覆盖所有占比组合。
 */
class DonutGeometryTest {

    // ---------------------------------------------------------------- 圆环弧段

    @Test
    fun `首段起点让出一个缝，长度两端各削一个缝`() {
        val arc = donutArc(listOf(0.5f, 0.5f), 0)

        // 缝宽 t = min(0.5/2, len/4) = min(0.25, 12.5) = 0.25；起点 +t、终点 -t。
        assertEquals(0.25f, arc.startPercent, 0.0001f)
        assertEquals("长度两端各削一个缝", 49.5f, arc.sweepPercent, 0.0001f)
    }

    @Test
    fun `相邻两段之间正好隔两个缝宽`() {
        val shares = listOf(0.6f, 0.4f)
        val first = donutArc(shares, 0)
        val second = donutArc(shares, 1)

        val firstEnd = first.startPercent + first.sweepPercent
        assertEquals(
            "前段终点已让出 t、后段起点也后移 t，中间共 2t；对不上说明两段会叠在一起",
            second.startPercent,
            firstEnd + 0.5f,
            0.0001f,
        )
    }

    @Test
    fun `每段都落在自己那份区间内，末段以整圈末尾为准`() {
        val shares = listOf(0.572f, 0.392f, 0.029f, 0.004f, 0.001f, 0.001f, 0.001f)
        val arcs = shares.indices.map { donutArc(shares, it) }

        var consumed = 0f
        arcs.forEachIndexed { index, arc ->
            val len = shares[index] * 100f
            assertTrue("第 $index 段起点不能越过自己那份区间的左界", arc.startPercent >= consumed - 0.0001f)
            assertTrue(
                "第 $index 段终点不能越过自己那份区间的右界（越界就会压到下一段的颜色）",
                arc.startPercent + arc.sweepPercent <= consumed + len + 0.0001f,
            )
            consumed += len
        }

        // 末段的缝由 `len/4` 决定：最小的三份各是 0.1，t = 0.025，所以终点停在 100 - 0.025。
        val last = arcs.last()
        assertEquals(100f - 0.025f, last.startPercent + last.sweepPercent, 0.0001f)
    }

    @Test
    fun `占比极小的段不会被缝吃光`() {
        // 0.1% 的段：len = 0.1，缝上限 min(0.25, 0.1/4 = 0.025) = 0.025。
        val arc = donutArc(listOf(0.998f, 0.001f, 0.001f), 1)

        assertTrue("小段的弧长必须为正，否则用户会以为那段数据丢了", arc.sweepPercent > 0f)
        assertEquals(0.05f, arc.sweepPercent, 0.0001f)
    }

    @Test
    fun `单段时不削缝，占满整圈`() {
        val arc = donutArc(listOf(1f), 0)

        assertEquals(0f, arc.startPercent, 0.0001f)
        assertEquals(100f, arc.sweepPercent, 0.0001f)
    }

    @Test
    fun `关掉缝隙时首尾严格相接`() {
        val shares = listOf(0.3f, 0.7f)
        val first = donutArc(shares, 0, gaps = false)
        val second = donutArc(shares, 1, gaps = false)

        assertEquals(0f, first.startPercent, 0.0001f)
        assertEquals(30f, first.sweepPercent, 0.0001f)
        assertEquals(30f, second.startPercent, 0.0001f)
        assertEquals(70f, second.sweepPercent, 0.0001f)
    }

    @Test
    fun `越界的下标返回零弧而不是抛异常`() {
        val beyond = donutArc(listOf(0.5f, 0.5f), 5)

        assertEquals(0f, beyond.startPercent, 0.0001f)
        assertEquals(0f, beyond.sweepPercent, 0.0001f)
    }

    @Test
    fun `空列表也返回零弧`() {
        val arc = donutArc(emptyList(), 0)

        assertEquals(0f, arc.sweepPercent, 0.0001f)
    }

    // ---------------------------------------------------------------- 调色板

    @Test
    fun `七段各有独立颜色，不能有两段同色`() {
        val keys = listOf("system", "tools", "user", "inject", "skill", "assistant", "tool")
        val colors = keys.map { ContextPalette.ofSegment(it) }

        assertEquals("七段颜色必须互不相同，否则图例指代不清", 7, colors.distinct().size)
    }

    @Test
    fun `降级态的消息段复用助手输出色，不新造颜色`() {
        assertEquals(
            "降级态的消息是五个段的合并值，用助手输出的蓝最好认",
            ContextPalette.assistant,
            ContextPalette.ofSegment("message"),
        )
    }

    @Test
    fun `耗时六段各有独立颜色`() {
        val keys = listOf("ttft", "reasoning", "text", "toolarg", "tools", "other")
        val colors = keys.map { ContextPalette.ofTiming(it) }

        assertEquals(6, colors.distinct().size)
    }

    @Test
    fun `未知段键回落到轨道色而不是抛异常`() {
        assertEquals(ContextPalette.track, ContextPalette.ofSegment("nope"))
        assertEquals(ContextPalette.track, ContextPalette.ofTiming("nope"))
    }

    @Test
    fun `耗时里的模型段与首字等待同色`() {
        assertEquals(ContextPalette.ttft, ContextPalette.ofTiming("llm"))
    }

    // ---------------------------------------------------------------- 路径拼接

    @Test
    fun `根目录下拼出名字而不带前导斜杠`() {
        assertEquals(
            "空父目录拼成 `/name` 会让服务端按绝对路径解析，直接越界",
            "docs",
            joinPath("", "docs"),
        )
    }

    @Test
    fun `子目录下按斜杠拼接`() {
        assertEquals("docs/img", joinPath("docs", "img"))
        assertEquals("a/b/c", joinPath("a/b", "c"))
    }

    // ---------------------------------------------------------------- 文件大小

    @Test
    fun `文件大小按 B KB MB 三档`() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("1.0 MB", formatSize(1_048_576))
        assertEquals("15.0 MB", formatSize(15_728_640))
    }

    @Test
    fun `零字节也给出可读文案`() {
        assertEquals("0 B", formatSize(0))
    }
}
