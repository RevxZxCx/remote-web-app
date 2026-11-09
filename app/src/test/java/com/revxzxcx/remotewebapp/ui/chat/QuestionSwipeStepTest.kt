package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [questionSwipeStep] 的单测 —— B010 的「横向滑动切题」判据。
 *
 * 用户原话（m 见缺陷记录 B010）：「多提问的时候只显示了最后一道，而且没有上一题/下一题
 * 按钮，也不行滑动切换」。
 *
 * 手势本身要在真机上验，但**判据**是纯函数：累计位移到没到阈值、往哪个方向切。
 * 这一段能被 JVM 单测钉死，任何一条被改坏都会变成「轻轻一碰就切题」或「划不动」。
 */
class QuestionSwipeStepTest {

    private val threshold = 48f

    /** 契约 1：没到阈值一律不动 —— 点选项时手指的横向抖动是 5~15px 量级。 */
    @Test
    fun `未达阈值不切题`() {
        assertEquals(0, questionSwipeStep(0f, threshold))
        assertEquals(0, questionSwipeStep(10f, threshold))
        assertEquals(0, questionSwipeStep(47.9f, threshold))
        assertEquals(0, questionSwipeStep(-47.9f, threshold))
    }

    /** 契约 2：往左拖 = 下一题（返回 +1），往右拖 = 上一题（返回 -1）。 */
    @Test
    fun `左拖是下一题右拖是上一题`() {
        assertEquals(1, questionSwipeStep(-threshold, threshold))
        assertEquals(1, questionSwipeStep(-200f, threshold))
        assertEquals(-1, questionSwipeStep(threshold, threshold))
        assertEquals(-1, questionSwipeStep(200f, threshold))
    }

    /** 契约 3：阈值必须在**恰好等于**时触发，不能写成 `>` 让用户多划一次。 */
    @Test
    fun `恰好等于阈值时触发`() {
        assertEquals(1, questionSwipeStep(-48f, 48f))
        assertEquals(-1, questionSwipeStep(48f, 48f))
        assertEquals(0, questionSwipeStep(-47.999f, 48f))
    }

    /** 契约 4：阈值非正时一律不动（density 为 0 之类的退化输入不能变成「每次都切题」）。 */
    @Test
    fun `阈值为零或负数时不切题`() {
        assertEquals(0, questionSwipeStep(-1000f, 0f))
        assertEquals(0, questionSwipeStep(1000f, -1f))
        assertEquals(0, questionSwipeStep(0f, 0f))
    }

    /** 契约 5：阈值是 48dp —— 与 Material 常规拖拽阈值同一量级，且明显大于点按抖动。 */
    @Test
    fun `阈值常量是四十八dp`() {
        assertEquals(48.dp, QUESTION_SWIPE_THRESHOLD)
        assertTrue("阈值不能小到把点选项的抖动判成滑动", QUESTION_SWIPE_THRESHOLD.value >= 40f)
    }
}
