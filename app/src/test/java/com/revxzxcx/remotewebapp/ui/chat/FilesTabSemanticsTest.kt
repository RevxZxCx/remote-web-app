package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 侧栏「文件」页签里三种**静态**状态的无障碍语义。
 *
 * ## 为什么要单独立一条
 *
 * R042 为了让下拉刷新在「加载中 / 读失败 / 空目录」这三种不满一屏的状态下也能被拉出来，
 * 把它们包进了一个滚动容器——`PullToRefreshBox` 靠嵌套滚动收手势，内容不可滚动时手指
 * 下拉根本传不到它那里，指示器不会出现。
 *
 * 首版包的是单 item 的 `LazyColumn`。功能上能拉，但 `LazyColumn` 会给整块内容挂上
 * 「列表，1 项」的**集合语义**：读屏用户打开文件页、目录还没回来时，听到的不是「加载中」
 * 而是一个只有一项的列表，`CircularProgressIndicator` 也被当成了那一项。文字本身没错，
 * 错的是容器。最终改成 `verticalScroll`——它只提供手势，不声明集合。
 *
 * ## 这条用例在守什么
 *
 * 断言「文件页的静态状态里没有任何节点带 CollectionInfo」。把 `PullHint` 换回
 * `LazyColumn` 就会红；换回裸 `Box`（不带滚动）也红——后者守住的是另一半：下拉刷新
 * 必须还有个可滚动的内容源，`assertHasScrollAction` 就是它存在过的证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilesTabSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sidebar(tab: DetailTab = DetailTab.FILES, filesLoading: Boolean = false, filesError: String? = null) {
        compose.setContent {
            DshAssistantTheme {
                // Robolectric 的视口只有 320×470 px，侧栏 318dp 宽会被裁到几乎没地方，
                // 用 requiredSize 给一个确定的方形区域（requiredHeight 而不是 height，
                // 避免被父级可用空间压扁）。
                Box(Modifier.requiredSize(320.dp, 460.dp)) {
                    DetailSidebar(
                        tab = tab,
                        onTabChange = {},
                        insights = null,
                        loading = false,
                        error = null,
                        onClose = {},
                        onRetry = {},
                        files = null,
                        filesPath = "",
                        filesLoading = filesLoading,
                        filesError = filesError,
                        onOpenDirectory = {},
                        onOpenFile = { _, _ -> },
                        onFilesUp = {},
                    )
                }
            }
        }
    }

    private val hasCollectionInfo =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)

    private fun assertNoListSemantics() {
        val count = compose.onAllNodes(hasCollectionInfo).fetchSemanticsNodes().size
        assertEquals("静态状态不该被包成列表（LazyColumn 会挂 CollectionInfo）", 0, count)
    }

    @Test
    fun emptyDirectoryIsNotAnnouncedAsAList() {
        sidebar()
        compose.onNodeWithText("这个目录是空的。").assertExists()
        assertNoListSemantics()
    }

    @Test
    fun failureIsNotAnnouncedAsAList() {
        sidebar(filesError = "http-500：服务端出错了")
        compose.onNodeWithText("读不到这个目录\nhttp-500：服务端出错了").assertExists()
        assertNoListSemantics()
    }

    @Test
    fun loadingIsNotAnnouncedAsAList() {
        sidebar(filesLoading = true)
        // 三条分支里只有加载中没有文案（转圈代替），所以这里换个判据：
        // 侧栏开着、目录还没回来时不该出现列表语义。
        assertNoListSemantics()
    }

    @Test
    fun staticStatesRemainPullable() {
        // 下拉刷新要求在内容不满一屏时也有可滚动的内容源。`verticalScroll` 会挂
        // ScrollBy 动作，这是「手势能被接住」的语义层证据。
        sidebar()
        val scrollable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
        assertEquals("空目录必须仍可滚动（否则下拉手势传不到 PullToRefreshBox）", 1, scrollable.size)
    }
}
