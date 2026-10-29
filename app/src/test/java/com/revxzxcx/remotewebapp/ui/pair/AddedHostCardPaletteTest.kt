package com.revxzxcx.remotewebapp.ui.pair

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.revxzxcx.remotewebapp.ui.theme.DarkColors
import com.revxzxcx.remotewebapp.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已加入列表」成功卡的配色对比度（B005 真机验收时发现的自引入缺陷）。
 *
 * ## 这条缺陷是怎么来的
 *
 * B005 让重复添加同一地址时不再多出一行，并把那张卡改成 `tertiaryContainer` 底色
 * 以区别于「首次加入」。但只有**标题与图标**跟着换成了 `onTertiaryContainer`，
 * 设备名 `PGBM10` 与端点 `127.0.0.1:19387` 仍是 `onSurface` / `onSurfaceVariant`。
 *
 * 浅色主题里 `tertiaryContainer = #4A3A12`（深棕）、`onSurface = #182430`（近黑），
 * 两者对比度只有 **1.41:1** —— 真机上那两行基本看不见。截图见
 * `docs/dev/缺陷记录.md` 的 B005 修复记录。
 *
 * ## 为什么不用「断言颜色等于某个常量」
 *
 * 那种断言只能守住「有人改了常量」，守不住「配色本身读不读得出来」。这里直接按
 * WCAG 2.1 的相对亮度公式算对比度：正文要 ≥ 4.5:1（AA 正文），大字号标题 ≥ 3:1。
 * 换主题、换品牌色都仍然成立。
 */
class AddedHostCardPaletteTest {

    // ------------------------------------------------------------ WCAG 2.1

    /** sRGB 单通道线性化（WCAG 2.1 相对亮度定义的第一段）。 */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** WCAG 2.1 相对亮度。 */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    /** WCAG 2.1 对比度，范围 1.0–21.0。 */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertReadable(
        label: String,
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$label 的对比度只有 %.2f:1，低于 %.1f:1 —— 用户读不出来".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    // ------------------------------------------------------------ 重复添加

    @Test
    fun `浅色主题下重复添加那张卡 正文必须读得出来`() = assertRepeatedReadable(LightColors, "浅色")

    @Test
    fun `深色主题下重复添加那张卡 正文必须读得出来`() = assertRepeatedReadable(DarkColors, "深色")

    private fun assertRepeatedReadable(scheme: ColorScheme, name: String) {
        val palette = addedHostCardPalette(repeated = true, scheme = scheme)
        // 4.5:1 是 WCAG AA 对正文的要求，这两行是 12sp / 10sp 的正文。
        assertReadable("$name 正文（设备名与端点）", palette.body, palette.container, 4.5)
        assertReadable("$name 底部说明", palette.note, palette.container, 4.5)
        assertReadable("$name 标题", palette.title, palette.container, 3.0)
    }

    @Test
    fun `重复添加时四个色必须一起换 不能只换标题漏掉正文`() {
        // 这正是当初的 bug：只换了 title，body 还留在 onSurface。
        val palette = addedHostCardPalette(repeated = true, scheme = LightColors)
        assertEquals(
            "重复添加时正文必须跟标题同色，否则就是深字压深底",
            palette.title,
            palette.body,
        )
        assertEquals(
            "重复添加时底部说明也必须同色",
            palette.title,
            palette.note,
        )
        assertEquals(
            "重复添加时按钮文字也必须同色——primary 蓝压在深棕上只有 2.38:1",
            palette.title,
            palette.action,
        )
    }

    @Test
    fun `按钮文字压在深色底上必须读得出来`() {
        // 真机复验时发现的第二处：正文修好后，两个 TextButton 还是 primary 蓝。
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            val repeated = addedHostCardPalette(repeated = true, scheme = scheme)
            assertReadable("$name 重复添加的按钮文字", repeated.action, repeated.container, 4.5)
        }
    }

    @Test
    fun `首次添加时按钮仍然是主色 没有顺带改掉观感`() {
        val first = addedHostCardPalette(repeated = false, scheme = LightColors)
        assertEquals(LightColors.primary, first.action)
    }

    @Test
    fun `把正文改回 onSurface 会立刻跌破可读线 这条断言必须能红`() {
        // 反例：证明上面那几条不是自证。这就是修复前真实的搭配。
        val container = LightColors.tertiaryContainer
        val broken = contrastRatio(LightColors.onSurface, container)
        assertTrue(
            "修复前的搭配应当是不可读的（当前 %.2f:1），否则这条缺陷不成立".format(broken),
            broken < 4.5,
        )
    }

    // ------------------------------------------------------------ 首次添加

    @Test
    fun `首次添加那张卡的正文也必须读得出来`() {
        val scheme = LightColors
        val palette = addedHostCardPalette(repeated = false, scheme = scheme)
        assertReadable("首次添加正文", palette.body, palette.container, 4.5)
        assertReadable("首次添加底部说明", palette.note, palette.container, 4.5)
        assertReadable("首次添加标题", palette.title, palette.container, 3.0)
    }

    @Test
    fun `首次添加仍然是原来那套颜色 没有顺带改掉观感`() {
        val palette = addedHostCardPalette(repeated = false, scheme = LightColors)
        assertEquals(LightColors.surfaceVariant, palette.container)
        assertEquals(LightColors.onSurface, palette.body)
        assertEquals(LightColors.onSurfaceVariant, palette.note)
    }

    @Test
    fun `两种状态的底色必须不同 否则用户分不出是首次还是重复`() {
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            val first = addedHostCardPalette(repeated = false, scheme = scheme)
            val again = addedHostCardPalette(repeated = true, scheme = scheme)
            assertTrue(
                "$name 主题下首次添加与重复添加的底色必须能区分开",
                first.container != again.container,
            )
        }
    }
}
