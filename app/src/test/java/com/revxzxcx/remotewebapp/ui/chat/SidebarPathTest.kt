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

    @Test
    fun backGoesUpWhileInsideADirectory() {
        // R042 用户要求的核心一条：在子目录里按返回是「上一级」，不是关掉侧栏。
        assertEquals(SidebarBackAction.GoUp(""), sidebarBackAction(DetailTab.FILES, "docs"))
        assertEquals(SidebarBackAction.GoUp("docs"), sidebarBackAction(DetailTab.FILES, "docs/dev"))
        assertEquals(
            SidebarBackAction.GoUp("docs/dev"),
            sidebarBackAction(DetailTab.FILES, "docs/dev/notes"),
        )
    }

    @Test
    fun backClosesSidebarOnlyAtWorkspaceRoot() {
        // 根目录再按返回就是退无可退，这时关侧栏才符合直觉。
        assertEquals(SidebarBackAction.CloseSidebar, sidebarBackAction(DetailTab.FILES, ""))
    }

    @Test
    fun backOnSessionTabAlwaysClosesSidebar() {
        // 会话详情页签没有层级概念；即使文件页签刚才停在深层目录，也不该在这里「向上」。
        assertEquals(SidebarBackAction.CloseSidebar, sidebarBackAction(DetailTab.SESSION, ""))
        assertEquals(SidebarBackAction.CloseSidebar, sidebarBackAction(DetailTab.SESSION, "docs/dev"))
    }

    @Test
    fun emptyListingLoadsFromScratch() {
        // 列表还没内容（刚进会话、或从独立文件页返回），只能老老实实列一次。
        assertEquals(FilesEntryAction.Load, filesEntryAction(hasListing = false))
    }

    @Test
    fun existingListingResumesInsteadOfSilentlyReturning() {
        // 列表已在屏幕上（侧栏关过一次又打开）。这条是 R042 审查 P2 的回归：
        // 走 openFiles 会命中缓存直接 return，既不请求也不重启监听，于是侧栏关着那段
        // 时间里的变化永远补不上——画面上完全看不出来，只有「新文件不出现」这一种症状。
        assertEquals(FilesEntryAction.ResumeAndReconcile, filesEntryAction(hasListing = true))
    }
}
