package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「输入法让位」的判定（用户 m45632：「输入框展开显示输入法时应不显示任务胶囊和排队列表」）。
 *
 * 为什么单独抽一个纯函数来测：这条契约在模拟器上**验不动**。MuMu 挂着虚拟硬件键盘
 * （`dumpsys input` 里有 `Xiaomi Keyboard`），搜狗输入法窗口被摆到
 * `mSurfacePosition=Point(0,2400)` 也就是屏幕外——`mInputShown` 报 true，
 * ime inset 却恒为 0，屏幕上根本看不到键盘，也就看不到「让位」。真机才有可观测的
 * 效果，而真机这一轮没接。于是把判定抽成纯函数，至少让逻辑本身有守护。
 */
class ImeDockVisibilityTest {

    @Test
    fun `键盘收起时三块都显示`() {
        assertTrue(dockVisible(imeVisible = false, editing = false))
    }

    @Test
    fun `键盘升起时三块都让位`() {
        assertFalse(dockVisible(imeVisible = true, editing = false))
    }

    @Test
    fun `正在行内编辑的那一块不让位`() {
        // 唯一的例外：编辑中的输入框一旦被移出组合树，焦点和已输入的内容一起没了。
        assertTrue(dockVisible(imeVisible = true, editing = true))
    }
}
