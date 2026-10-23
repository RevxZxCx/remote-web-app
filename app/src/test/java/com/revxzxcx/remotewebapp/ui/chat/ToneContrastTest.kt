package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续写 / 重试本轮」胶囊的前景挑选（R045 审查 P2）。
 *
 * 早先用「tone 亮度 > 0.5 就取 background」的二分法：浅色主题的 tertiary 是
 * `#F2C14E`（亮度 0.576，落在阈值之上），于是白底字被换成 `background`=#F4F7FB，
 * 实测对比度 1.56:1 —— 比原来写死白色（1.68:1）还差。改成按实际对比度择优。
 */
class ToneContrastTest {

    /** 与 TurnResultCard 里同一套公式：WCAG 相对对比度。 */
    private fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun best(background: Color, candidates: List<Color>): Color =
        candidates.maxByOrNull { ratio(it, background) }!!

    /**
     * 浅色 tertiary `#F2C14E`：白色只有 1.68:1、`background` 只有 1.56:1，两者都不过 AA。
     * 主题为它备了 `onTertiary = #3A2A00`（约 7:1），候选集必须含它，否则「择优」只是
     * 在两个不达标的颜色里挑一个（R045 三轮审查 P2）。
     */
    @Test
    fun `浅色 tertiary 上要选主题给的 onTertiary 而不是白色`() {
        val tertiary = Color(0xFFF2C14E)
        val onTertiary = Color(0xFF3A2A00)
        val picked = best(tertiary, listOf(onTertiary, Color.White, Color(0xFFF4F7FB)))
        assertTrue("挑中的前景必须是 onTertiary，实际 $picked", picked == onTertiary)
        assertTrue(
            "onTertiary 在 tertiary 上的对比度必须过 AA(4.5:1)，实际 ${ratio(onTertiary, tertiary)}",
            ratio(onTertiary, tertiary) >= 4.5f,
        )
    }

    @Test
    fun `候选里没有合格前景时退化成最好的那个而不是崩`() {
        // 中灰底上白与背景都差不多烂，择优仍要给出一个确定的结果。
        val mid = Color(0xFF808080)
        val picked = best(mid, listOf(Color.White, Color(0xFF111C2B)))
        assertTrue("必须挑出一个候选", picked == Color.White || picked == Color(0xFF111C2B))
    }

    @Test
    fun `深度色上的字也应该选白色`() {
        val errorRed = Color(0xFFB3261E)
        val picked = best(errorRed, listOf(Color.White, Color(0xFFF4F7FB)))
        assertTrue("挑中的前景必须是白色，实际 $picked", picked == Color.White)
    }

    @Test
    fun `极浅底色上选深色而不是白字`() {
        val pale = Color(0xFFE9EFF7)
        val picked = best(pale, listOf(Color.White, Color(0xFF111C2B)))
        assertTrue("挑中的前景必须是深色，实际 $picked", picked == Color(0xFF111C2B))
    }
}
