package com.revxzxcx.remotewebapp.ui.common

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R039 第 6 条（用户 m17042）：「标题/路径过长换行：应单行截断 + 可滚动显示」。
 *
 * 这条改动的风险不在滚动本身（`basicMarquee` 是框架给的），而在**截断的边界**：
 * 一旦 `maxLines` 或 `softWrap` 写错，长标题会把顶栏撑成两行、把下面的消息区顶下去，
 * 而那正是用户报的原始问题。
 *
 * ## 这个文件能守住什么、守不住什么（实测记录，不要被绿光骗）
 *
 * 守得住：
 * ① **语义里是完整文本**——读屏与搜索拿到全文，不是被切掉的片段。`onNodeWithText` 是
 *    精确匹配，能命中等价于字符串没被人为改短。
 * ② **关掉系统动画时不退化内容**（R017 铁律 4）：`ANIMATOR_DURATION_SCALE = 0` 只该停
 *    滚动，不该把文字换成别的什么，也不该丢掉截断。
 * ③ 长标题的高度**不大于**短标题——这一条能抓住「顶栏被撑高一倍」这类粗放回归。
 *
 * 守不住（已做变异验证确认）：
 * ④ **把 `maxLines = 1` 改成 3、`softWrap = false` 改成 true，三条用例仍然全绿。**
 *    原因是 Robolectric 的字体度量是假的：字符宽度全部按 0 计，任何长度的字符串在
 *    120dp 容器里都「装得下」，于是**永远不折行**，`lineCount` 恒为 1。这与 R037
 *    （`lineHeight` 度量不出来）、R033 P3-1（`IntrinsicSize.Min` 变异不变红）是同一类
 *    局限。所以「真的只有一行」这件事**只能在设备上看**——见 `测试记录.md` 的 T065 截图
 *    证据。这里保留高度断言，但把它当粗放回归用，不当精确守门。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SingleLineMarqueeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val longTitle =
        "App公网配对 Connection Reset 问题排查与修复全过程记录（含三处分段结论）"

    private val shortTitle = "短标题"

    /**
     * 同一屏里并排摆一条短标题与一条长标题，宽度都钉死。
     *
     * **为什么不写「高度 ≤ 32dp」**：Robolectric 的字体度量是假的（R037 已经踩过），
     * 实测单行 `bodyMedium` 量出来是 36dp 而不是真机的 ~20dp。写死绝对值等于把测试
     * 绑在假度量上，真机一改字号就误报。真正要守的约束是**长标题不比短标题多占一行**，
     * 而这个比值与字体度量无关。
     */
    private fun setPair(width: Dp = 120.dp) {
        compose.setContent {
            Column {
                // 固定宽度：不给宽度时 Measurable 按内容量，「是否溢出」永远为假。
                SingleLineMarqueeText(text = shortTitle, modifier = Modifier.width(width))
                SingleLineMarqueeText(text = longTitle, modifier = Modifier.width(width))
            }
        }
    }

    private fun heightOf(text: String): Dp {
        val bounds = compose.onNodeWithText(text).getBoundsInRoot()
        return bounds.bottom - bounds.top
    }

    @Test
    fun `超长标题只占一行高度`() {
        setPair()
        assertEquals(
            "长标题必须与短标题同样高；多出来的高度就是「折行把顶栏撑成了两行」",
            heightOf(shortTitle),
            heightOf(longTitle),
        )
    }

    @Test
    fun `语义里保留完整标题而不是截断片段`() {
        setPair()
        // 命中即证明语义节点上的字符串仍是全文（`onNodeWithText` 是精确匹配）。
        compose.onNodeWithText(longTitle).assertExists()
    }

    @Test
    fun `关掉系统动画时文字仍然完整且仍是一行`() {
        // 关闭动画只该停滚动，不该动内容——但**截断必须还在**。
        //
        // 关掉动画后走的是**只加 Text 参数**的分支（不挂 `basicMarquee`），
        // 所以这里还能顺带盯住「截断参数被误删」。但它同样受假字体度量限制：
        // 文字永远装得下、永不折行，`maxLines = 3` 也不会让这条变红（见类 KDoc ③④）。
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        setPair()
        compose.onNodeWithText(longTitle).assertExists()
        assertEquals(
            "关闭动画后长标题仍必须与短标题等高，否则说明截断参数失效、文案会折行",
            heightOf(shortTitle),
            heightOf(longTitle),
        )
    }
}
