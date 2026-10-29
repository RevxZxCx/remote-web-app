package com.revxzxcx.remotewebapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * B007 的几何契约测试：设备栏描边**只画两侧 + 下沿，上沿必须开口**。
 *
 * 为什么断纯数据而不是断 `Path`：`androidx.compose.ui.graphics.Path` 在 Android 上
 * 转发给 `android.graphics.Path`，Robolectric 里 `PathMeasure.length()` 恒为 0，
 * 长度断言全变假红（第一版就踩了）。所以几何由 [OutlineEdgePlan] 用坐标描述，
 * 这里只断数字。
 *
 * 判据是解析的：描边总长必须等于「两条竖线 + 下沿 + 两段四分之一圆弧」；
 * 一旦顺手把上沿连上，长度就会多出整整一条宽度 [OutlineEdgePlan.topEdgeLength]。
 */
class DeviceBarEdgePathTest {

    @Test
    fun `上沿必须是开口的 长度里不能含上沿那条边`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 0f)
        // 两条竖线各 168（=200-32），下沿 336（=400-2*32），两段四分之一圆弧各 pi*32/2。
        val expected = (200f - 32f) * 2f + (400f - 2 * 32f) + 2f * (Math.PI.toFloat() / 2f * 32f)
        assertEquals("描边总长必须只含两侧与下沿", expected, plan.edgeLength, 0.5f)
    }

    @Test
    fun `如果补上上沿 长度会整整多出一条宽度 这条必须能区分开`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 0f)
        assertEquals(400f, plan.topEdgeLength, 0.01f)
        val withTop = plan.edgeLength + plan.topEdgeLength
        assertTrue(
            "少画上沿与多画上沿必须差出一整条宽度，否则这条断言没有分辨力",
            abs(withTop - plan.edgeLength) > 399f,
        )
    }

    @Test
    fun `半径为零时退化成三条直线且仍然没有上沿`() {
        val plan = deviceBarEdgePlan(width = 300f, height = 120f, radius = 0f, strokeWidth = 0f)
        assertEquals(0f, plan.radius, 0.01f)
        assertEquals(120f * 2f + 300f, plan.edgeLength, 0.5f)
    }

    @Test
    fun `描边半宽内缩 路径不会画到节点外面被裁掉`() {
        val raw = deviceBarEdgePlan(width = 400f, height = 200f, radius = 0f, strokeWidth = 0f)
        val inset = deviceBarEdgePlan(width = 400f, height = 200f, radius = 0f, strokeWidth = 2f)
        assertEquals(1f, inset.left, 0.01f)
        assertEquals(399f, inset.right, 0.01f)
        assertEquals(199f, inset.bottom, 0.01f)
        assertTrue("内缩后必须比不内缩短", inset.edgeLength < raw.edgeLength)
    }

    @Test
    fun `半径过大时被夹到一半高度 不能画出畸形路径`() {
        val plan = deviceBarEdgePlan(width = 100f, height = 100f, radius = 500f, strokeWidth = 0f)
        assertEquals("半径必须被夹到一半高度", 50f, plan.radius, 0.01f)
        assertTrue("必须记录这次夹取，调用方才知道传大了", plan.wasClamped(500f))
        assertFalse(plan.wasClamped(40f))
    }

    @Test
    fun `正常半径不会被误判成夹取`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 16f, strokeWidth = 0f)
        assertEquals(16f, plan.radius, 0.01f)
        assertFalse(plan.wasClamped(16f))
    }

    @Test
    fun `左右两侧都必须存在 否则描边只有下沿`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 16f, strokeWidth = 0f)
        assertEquals(0f, plan.left, 0.01f)
        assertEquals(400f, plan.right, 0.01f)
        assertTrue("两侧必须真的在下沿两端之外", plan.right - plan.left > 0f)
    }

    @Test
    fun `描边宽度必须是一像素级 不能粗到抢内容`() {
        assertEquals(1f, DeviceBarEdgeWidth.value, 0.01f)
    }

    // ---- 命令序列：这是几何的唯一真相，专治「改 buildPath 但长度公式不变」的变异 ----

    @Test
    fun `首条命令必须从左上角起笔 上沿的左端点才存在`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 2f)
        val first = plan.commands.first()
        assertTrue("第一条命令必须是抬笔而不是画线，否则会在屏幕上多出一条线", first is EdgeCommand.MoveTo)
        assertEquals(1f, (first as EdgeCommand.MoveTo).x, 0.001f)
        assertEquals(0f, first.y, 0.001f)
    }

    @Test
    fun `末条命令必须落在右上角 且中间任何直线都不许回到 y 为零`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 2f)
        val last = plan.commands.last()
        assertTrue("最后一条必须是直线收笔", last is EdgeCommand.LineTo)
        assertEquals(399f, (last as EdgeCommand.LineTo).x, 0.001f)
        assertEquals(0f, last.y, 0.001f)

        // 关键：y == 0 的直线就是上沿那条边。除了收笔那一条，其余直线一律不许在 y = 0。
        val topLines = plan.commands.dropLast(1).filterIsInstance<EdgeCommand.LineTo>().filter { it.y == 0f }
        assertTrue("除了回到右上角那一条，任何直线都不能落在 y = 0（那就是上沿）", topLines.isEmpty())
    }

    @Test
    fun `命令里必须有两条竖直的侧边 少一条描边就只剩半个轮廓`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 2f)
        val lines = plan.commands.filterIsInstance<EdgeCommand.LineTo>()
        val leftX = plan.left
        val rightX = plan.right
        assertTrue("必须有一条落在左边 x = $leftX 上的直线", lines.any { it.x == leftX })
        assertTrue("必须有一条落在右边 x = $rightX 上的直线", lines.any { it.x == rightX })
    }

    @Test
    fun `有半径时必须是两个圆弧加一段下横 少一段轮廓就断了`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 32f, strokeWidth = 2f)
        val arcs = plan.commands.filterIsInstance<EdgeCommand.ArcTo>()
        assertEquals("下两角各一段圆弧", 2, arcs.size)
        assertEquals("左下角从正左扫到正下", 180f, arcs[0].startAngleDegrees, 0.001f)
        assertEquals("右下角从正下扫到正右", 270f, arcs[1].startAngleDegrees, 0.001f)
        assertTrue("圆弧必须顺时针扫 90 度", arcs.all { it.sweepAngleDegrees == 90f })

        val bottomLines = plan.commands.filterIsInstance<EdgeCommand.LineTo>().filter { it.y == plan.bottom }
        assertEquals("两个下圆角之间必须有一段下横", 1, bottomLines.size)
    }

    @Test
    fun `半径为零时退化成两竖一下横 仍然没有上沿`() {
        val plan = deviceBarEdgePlan(width = 400f, height = 200f, radius = 0f, strokeWidth = 2f)
        assertTrue("退化形态不该有圆弧", plan.commands.filterIsInstance<EdgeCommand.ArcTo>().isEmpty())
        val lines = plan.commands.filterIsInstance<EdgeCommand.LineTo>()
        assertEquals("退化成三条直线：左竖、下横、右竖", 3, lines.size)
        assertEquals("其中两条落在下沿", 2, lines.count { it.y == plan.bottom })
    }
}
