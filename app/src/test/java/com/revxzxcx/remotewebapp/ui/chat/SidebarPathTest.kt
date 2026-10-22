package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧栏文件浏览的目录运算（R041 第 ③ 条）。
 *
 * 用户 m24881 报的是「点开文件后，再返回就直接关闭侧边栏到对话界面」。修法把
 * 页签与目录提到了 `AppRoot`，于是「向上走一层」这件事有了两个落点：ViewModel
 * 真的去列上一级、`ChatScreen` 把新目录同步回去。两者必须给出同一个答案，
 * 所以统一走 [parentPath]。
 */
class SidebarPathTest {

    @Test
    fun rootStaysRoot() {
        // 根是空串（wire 上转成 `.`）。返回根时必须还是「不向上」，否则会退到工作区外。
        assertEquals("", parentPath(""))
    }

    @Test
    fun firstLevelGoesBackToRoot() {
        assertEquals("", parentPath("docs"))
        assertEquals("", parentPath("README.md"))
    }

    @Test
    fun nestedPathDropsOnlyTheLastSegment() {
        assertEquals("docs", parentPath("docs/dev"))
        assertEquals("docs/dev", parentPath("docs/dev/notes"))
    }

    @Test
    fun joinThenUpIsIdentity() {
        // 进目录再退出来必须回到原处：这是「返回文件列表」时目录能复原的前提。
        assertEquals("docs", parentPath(joinPath("docs", "dev")))
        assertEquals("", parentPath(joinPath("", "docs")))
    }

    @Test
    fun joinPathAddsSeparatorOnlyBelowRoot() {
        assertEquals("docs/dev", joinPath("docs", "dev"))
        assertEquals("docs", joinPath("", "docs"))
    }
}
