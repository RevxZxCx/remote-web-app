package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [prefixHeights] 的纯 JVM 回归守卫。
 *
 * 不需要 Compose、不需要 Android：这个函数的全部风险都在算术上，而它的症状
 * （滚动条标记整体偏移几行）在设备上极难归因——用户只会说「标记位置不太对」，
 * 而「不太对」可能是偏一行也可能是偏十行。所以近似规则必须被钉死。
 */
class ChatScrollbarModelTest {

    @Test
    fun `空列表只有一个零`() {
        val out = prefixHeights(emptyList(), emptyMap())
        assertEquals(1, out.size)
        assertEquals(0f, out[0], 0f)
    }

    @Test
    fun `全部已量到时前缀和精确`() {
        val keys = listOf("a", "b", "c")
        val heights: Map<Any, Int> = mapOf("a" to 100, "b" to 50, "c" to 25)
        val out = prefixHeights(keys, heights)
        assertArrayEquals(floatArrayOf(0f, 100f, 150f, 175f), out, 0.001f)
    }

    @Test
    fun `没量到的行用已量行的平均值补`() {
        // 三行，只量到 a=100 与 c=200 → 平均 150 → b 也按 150 算。
        val keys = listOf("a", "b", "c")
        val heights: Map<Any, Int> = mapOf("a" to 100, "c" to 200)
        val out = prefixHeights(keys, heights)
        assertArrayEquals(floatArrayOf(0f, 100f, 250f, 450f), out, 0.001f)
    }

    @Test
    fun `只量到一行时其余全部按那一行算`() {
        val keys = listOf("a", "b", "c", "d")
        val heights: Map<Any, Int> = mapOf("c" to 80)
        val out = prefixHeights(keys, heights)
        assertArrayEquals(floatArrayOf(0f, 80f, 160f, 240f, 320f), out, 0.001f)
    }

    @Test
    fun `一行都没量到时总和是零`() {
        // 总和为 0 是 [ChatScrollbar] 判定「还没有量到任何一行、先不画」的信号。
        val keys = listOf("a", "b")
        val out = prefixHeights(keys, emptyMap())
        assertEquals(0f, out.last(), 0f)
    }

    @Test
    fun `前缀和单调不减且长度是行数加一`() {
        val keys = (1..50).map { "k$it" }
        val heights: Map<Any, Int> = keys.withIndex().associate { (i, k) -> k to (i * 7 + 3) }
        val out = prefixHeights(keys, heights)
        assertEquals(keys.size + 1, out.size)
        for (i in 1 until out.size) {
            assertTrue("第 $i 项比前一项小", out[i] >= out[i - 1])
        }
        assertTrue("零高度的行不应让前缀和倒退", out[10] == out[9] + (9 * 7 + 3).toFloat())
    }

    @Test
    fun `行高为零是合法的不会除零`() {
        val keys = listOf("a", "b", "c")
        val heights: Map<Any, Int> = mapOf("a" to 0, "b" to 0, "c" to 0)
        val out = prefixHeights(keys, heights)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), out, 0f)
    }
}
