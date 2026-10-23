package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用选择器的路径运算（R044 返工）。
 *
 * 这些函数决定「点文件夹是进入还是选中」，是用户 m27926 第 2 条的直接修复点，
 * 所以必须有测试守着——它出错的表现（点文件夹直接把目录引用进消息）在界面上
 * 看起来完全正常，只有真的用一次才发现。
 */
class ReferencePathTrailTest {

    // ── childQuery：进入下一层 ────────────────────────────────────────────────

    @Test
    fun childQueryAppendsTrailingSlash() {
        assertEquals("docs/", ReferencePathTrail.childQuery("docs"))
    }

    @Test
    fun childQueryKeepsExistingPath() {
        assertEquals("remote-web-app/docs/", ReferencePathTrail.childQuery("remote-web-app/docs"))
    }

    @Test
    fun childQueryOfRootIsEmpty() {
        assertEquals("", ReferencePathTrail.childQuery(""))
    }

    /** 服务端对 `dir//` 会当成不存在的路径；重复调用不能越加越长。 */
    @Test
    fun childQueryDoesNotDoubleTheSlash() {
        assertEquals("docs/", ReferencePathTrail.childQuery("docs/"))
    }

    // ── parentQuery：返回上一级 ──────────────────────────────────────────────

    @Test
    fun parentQueryDropsOneSegment() {
        assertEquals("docs/", ReferencePathTrail.parentQuery("docs/dev/"))
    }

    @Test
    fun parentQueryOfTopLevelIsRoot() {
        assertEquals("", ReferencePathTrail.parentQuery("docs/"))
    }

    @Test
    fun parentQueryOfRootStaysRoot() {
        assertEquals("", ReferencePathTrail.parentQuery(""))
    }

    /** 没有尾斜杠也要能退一级：历史状态或手改的值不能把用户卡死。 */
    @Test
    fun parentQueryToleratesMissingTrailingSlash() {
        assertEquals("docs/", ReferencePathTrail.parentQuery("docs/dev"))
    }

    // ── crumbs：面包屑 ───────────────────────────────────────────────────────

    @Test
    fun crumbsAreEmptyAtRoot() {
        assertTrue(ReferencePathTrail.crumbs("").isEmpty())
    }

    @Test
    fun crumbsCarryTheQueryThatReturnsToEachLevel() {
        val crumbs = ReferencePathTrail.crumbs("a/b/")
        assertEquals(listOf("a", "b"), crumbs.map { it.label })
        assertEquals(listOf("a/", "a/b/"), crumbs.map { it.query })
    }

    @Test
    fun crumbsIgnoreTrailingSlash() {
        assertEquals(
            ReferencePathTrail.crumbs("a/b/").map { it.query },
            ReferencePathTrail.crumbs("a/b").map { it.query },
        )
    }

    // ── rowAction：点一行到底做什么 ──────────────────────────────────────────

    @Test
    fun directoriesAreDescendedIntoNotSelected() {
        assertEquals(
            ReferencePathTrail.RowAction.Descend,
            ReferencePathTrail.rowAction(
                ReferencePathTrail.FileReferenceView("docs", isDirectory = true, selected = false),
            ),
        )
    }

    /**
     * 已选目录仍然是「进入」。
     *
     * 这是用户报的那个 bug 的另一半：第一版看到「已选」就返回 ToggleSelect，
     * 于是选中一个目录之后再点它会被取消选中，而不是进去——用户永远进不去第二层。
     */
    @Test
    fun selectedDirectoryIsStillDescendedInto() {
        assertEquals(
            ReferencePathTrail.RowAction.Descend,
            ReferencePathTrail.rowAction(
                ReferencePathTrail.FileReferenceView("docs", isDirectory = true, selected = true),
            ),
        )
    }

    @Test
    fun unselectedFileTogglesOn() {
        assertEquals(
            ReferencePathTrail.RowAction.ToggleSelect,
            ReferencePathTrail.rowAction(
                ReferencePathTrail.FileReferenceView("a.md", isDirectory = false, selected = false),
            ),
        )
    }

    @Test
    fun selectedFileTogglesOff() {
        assertEquals(
            ReferencePathTrail.RowAction.RemoveSelect,
            ReferencePathTrail.rowAction(
                ReferencePathTrail.FileReferenceView("a.md", isDirectory = false, selected = true),
            ),
        )
    }

    /** 只有目录会往下钻；文件永远不该被当成目录。 */
    @Test
    fun onlyDirectoriesDescend() {
        val files = listOf("a.md", "b.png", "c/deep.txt")
        files.forEach { path ->
            assertFalse(
                "文件 $path 不该被当成目录",
                ReferencePathTrail.rowAction(
                    ReferencePathTrail.FileReferenceView(path, isDirectory = false, selected = false),
                ) == ReferencePathTrail.RowAction.Descend,
            )
        }
    }
}
