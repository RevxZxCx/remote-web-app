package com.revxzxcx.remotewebapp.ui.files

import com.revxzxcx.remotewebapp.core.files.WorkspaceFileError
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileViewerViewModel] 里两个顶层 internal 辅助函数的单测。
 *
 * 它们被抽成顶层函数**就是**为了能脱离网络单测（`FileViewerViewModel.kt:285-296` 的注释）：
 * 分页的行数一旦算错，「加载更多」会永远取不到下一页、或者静默跳过一行，而这两种错
 * 在 UI 上几乎看不出来（顶多觉得「内容怪怪的」）。所以这里把行切分与 offset 的算术
 * 用显式数字钉死。
 */
class FileViewerHelpersTest {

    // ------------------------------------------------------------------ splitPageLines

    @Test
    fun `空文本切成空列表`() {
        assertTrue(splitPageLines("").isEmpty())
    }

    @Test
    fun `结尾换行不产生多余空行`() {
        // 这是分页算术的护栏：`"a\n"` 是**一行**内容，结尾的 \n 只是行终止符。
        // 若这里返回 ["a",""]，每翻一页都会在末尾多出一个文件里并不存在的空行，
        // 并且 lines.size 会偏大，下一段 offset 直接算飞。
        assertEquals(listOf("a"), splitPageLines("a\n"))
    }

    @Test
    fun `多行结尾换行时行数等于真实行数`() {
        assertEquals(listOf("a", "b"), splitPageLines("a\nb\n"))
        assertEquals(listOf("a", "b", "c", "d", "e"), splitPageLines("a\nb\nc\nd\ne\n"))
    }

    @Test
    fun `没有结尾换行时按原样切`() {
        assertEquals(listOf("a", "b"), splitPageLines("a\nb"))
        assertEquals(listOf("a"), splitPageLines("a"))
    }

    @Test
    fun `中间的真实空行被保留`() {
        // 与「结尾换行」相对：中间的连续换行是文件里真的存在的空行，必须留下，
        // 否则代码里的空行会被吃掉、行号与 diff 对不上。
        assertEquals(listOf("a", "", "b"), splitPageLines("a\n\nb"))
        assertEquals(listOf("a", "", "", "b"), splitPageLines("a\n\n\nb"))
    }

    @Test
    fun `只有换行的文本切出一个空行`() {
        // "\n" = 一个空行 + 行终止符。空列表才是错的（页大小算成 0 会导致翻页死循环）。
        assertEquals(listOf(""), splitPageLines("\n"))
        assertEquals(listOf("", ""), splitPageLines("\n\n"))
    }

    @Test
    fun `单行文本与含回车的文本不被误切`() {
        // 只按 \n 切：Windows 的 \r\n 会在行尾留下 \r（服务端给的行是以 \n 计的，
        // 这里记录的是「不做额外清理」这一实际行为）。
        assertEquals(listOf("a"), splitPageLines("a"))
        assertEquals(listOf("a\r"), splitPageLines("a\r\n"))
        assertEquals(listOf("a\r", "b"), splitPageLines("a\r\nb"))
    }

    @Test
    fun `尾随空格的行被原样保留`() {
        assertEquals(listOf("a  "), splitPageLines("a  \n"))
    }

    // ------------------------------------------------------------------ 分页算术不变量

    @Test
    fun `startLine 加 lines size 就是下一页的 offset`() {
        // 不变量：调用方用 1-based 的 offset 请求下一页，它必须等于
        // `上一页 offset + 上一页实际行数`。差一个就会重复或跳过一行。
        val page1Offset = 1
        val page1 = splitPageLines("l1\nl2\nl3\nl4\nl5\n")
        assertEquals(5, page1.size)

        val nextOffset = page1Offset + page1.size
        assertEquals("第 1 页 offset=1 共 5 行 → 下一页 offset 必须是 6", 6, nextOffset)
    }

    @Test
    fun `中间页的 offset 递推`() {
        val page2Offset = 6
        val page2 = splitPageLines("l6\nl7\nl8\nl9\n")
        assertEquals(4, page2.size)
        assertEquals(10, page2Offset + page2.size)

        // 满页（offset=10 + 5 行）→ 15。
        assertEquals(15, 10 + splitPageLines("l10\nl11\nl12\nl13\nl14\n").size)
    }

    @Test
    fun `末尾页行数偏少时 offset 仍然正确`() {
        // 非满页是常态：`eof` 之外还要靠 offset 单调递增保证不重复。
        val lastPage = splitPageLines("l20\nl21\n")
        assertEquals(2, lastPage.size)
        assertEquals(22, 20 + lastPage.size)
    }

    @Test
    fun `不含结尾换行的末页不会少算一行`() {
        // 服务端最后一页可能没有结尾 \n（eof=true 时实测如此），
        // 这条钉死「有无结尾 \n 都不影响行数」。
        assertEquals(splitPageLines("l6\nl7\nl8\n").size, splitPageLines("l6\nl7\nl8").size)
        assertEquals(3, splitPageLines("l6\nl7\nl8").size)
    }

    @Test
    fun `空页不会让 offset 停滞`() {
        // 防御性：空页行数为 0，offset 不会前进——调用方必须靠 eof 退出而不是靠行数。
        val empty = splitPageLines("")
        assertEquals(0, empty.size)
        assertEquals(5, 5 + empty.size)
    }

    // ------------------------------------------------------------------ describeFileFailure

    @Test
    fun `已知工作区文件错误码返回中文说明而不是原始码`() {
        // 用户看到的必须是中文，不能是 `workspace-file/not-text` 这种内部码。
        assertEquals(
            "这是二进制文件，不能按文本打开",
            describeFileFailure(TypertException(WorkspaceFileError.NOT_TEXT, "not text")),
        )
        assertEquals(
            "文件不存在（可能在桌面端被删除或重命名）",
            describeFileFailure(TypertException(WorkspaceFileError.NOT_FOUND, "not found")),
        )
    }

    @Test
    fun `全部已知文件错误码都有中文说明`() {
        // 文案取自 WorkspaceFileModel.kt:87-97 的字面量，改动文案会让这里失败——
        // 这是有意的：文案是产品决定，不该被顺手改掉。
        val expected = mapOf(
            WorkspaceFileError.NOT_FOUND to "文件不存在（可能在桌面端被删除或重命名）",
            WorkspaceFileError.NOT_REGULAR_FILE to "这不是普通文件",
            WorkspaceFileError.NOT_DIRECTORY to "这不是目录",
            WorkspaceFileError.OUTSIDE_WORKSPACE to "文件不在工作区目录内",
            WorkspaceFileError.NOT_TEXT to "这是二进制文件，不能按文本打开",
            WorkspaceFileError.TOO_LARGE to "文件超过服务端上限（32 MiB）",
            WorkspaceFileError.UNSUPPORTED_ADDRESS to "不支持的文件地址",
            WorkspaceFileError.UNKNOWN_WORKSPACE to "找不到这个会话对应的工作区",
        )

        for ((code, text) in expected) {
            assertEquals(code, text, describeFileFailure(TypertException(code, "raw message")))
            // 已知码时不当它是「未知码」，因此不会出现 `code：message` 的形式。
            assertTrue(
                "已知码不该回显原始码",
                !describeFileFailure(TypertException(code, "raw message")).startsWith("$code："),
            )
        }
    }

    @Test
    fun `已知错误码的异常 message 为空时也不影响文案`() {
        // 已知码时完全不看 message，所以 message 为空串也能给出完整中文提示。
        assertEquals(
            "这是二进制文件，不能按文本打开",
            describeFileFailure(TypertException(WorkspaceFileError.NOT_TEXT, "")),
        )
        assertEquals(
            "文件不存在（可能在桌面端被删除或重命名）",
            describeFileFailure(TypertException(WorkspaceFileError.NOT_FOUND, "")),
        )
    }

    @Test
    fun `未知 TypertException 码返回 码冒号消息`() {
        // 未知码没有中文可给，但也不能只丢一句「加载失败」——把码与消息原样带出来便于排查。
        assertEquals(
            "gateway/bad-request：请求参数不对",
            describeFileFailure(TypertException("gateway/bad-request", "请求参数不对")),
        )
        assertEquals(
            "transport：网络请求失败：timeout",
            describeFileFailure(TypertException(TypertException.CODE_TRANSPORT, "网络请求失败：timeout")),
        )
    }

    @Test
    fun `码的判定是精确匹配大小写敏感`() {
        // `WorkspaceFileError.describe` 走 when(code) 精确比较；大小写不同即视为未知码。
        assertEquals(
            "WORKSPACE-FILE/NOT-TEXT：x",
            describeFileFailure(TypertException("WORKSPACE-FILE/NOT-TEXT", "x")),
        )
    }

    @Test
    fun `未知码且消息为空串时只剩码与冒号`() {
        // TypertException 的 message 是非空 String（构造参数 `message: String`），
        // 所以不存在消息为 null 的情形；空串会得到 "code："。
        assertEquals("some/code：", describeFileFailure(TypertException("some/code", "")))
    }

    @Test
    fun `非 TypertException 返回异常自身的消息`() {
        assertEquals("磁盘满了", describeFileFailure(RuntimeException("磁盘满了")))
    }

    @Test
    fun `非 TypertException 且消息为空时回退到类名`() {
        // R005 审查发现并修复：原先用 `message ?: 类名`，`message == ""` 时返回空字符串，
        // UI 上会出现一个没有文字的报错条。改为 `takeIf { it.isNotBlank() } ?: 类名`。
        assertEquals("RuntimeException", describeFileFailure(RuntimeException("")))
        assertEquals("IllegalStateException", describeFileFailure(IllegalStateException("   ")))
    }

    @Test
    fun `非 TypertException 且消息为 null 时返回类名`() {
        // 兜底到简单类名，至少让用户看到「哪种失败」而不是一片空白。
        assertEquals("RuntimeException", describeFileFailure(RuntimeException()))
        assertEquals("IllegalStateException", describeFileFailure(IllegalStateException()))
    }

    @Test
    fun `嵌套 cause 的 TypertException 也被识别`() {
        // R005 审查发现并修复：调用方常把协议错误包一层再抛，只检查最外层会丢掉
        // `workspace-file/not-found` 这类真正的成因，把中文文案降级成一句英文 message。
        val wrapped = RuntimeException("外层", TypertException(WorkspaceFileError.NOT_FOUND, "x"))
        assertEquals(WorkspaceFileError.describe(WorkspaceFileError.NOT_FOUND), describeFileFailure(wrapped))
        // 多层嵌套同样能挖到。
        val deep = IllegalStateException(
            "更外层",
            RuntimeException("外层", TypertException(WorkspaceFileError.NOT_TEXT, "x")),
        )
        assertEquals(WorkspaceFileError.describe(WorkspaceFileError.NOT_TEXT), describeFileFailure(deep))
        // 外层有消息也不会遮蔽内层的 Typert 码：文件页要的是「为什么打不开」，不是包装层的话。
        assertEquals(
            "transport：网络请求失败：timeout",
            describeFileFailure(
                RuntimeException("包装层", TypertException(TypertException.CODE_TRANSPORT, "网络请求失败：timeout")),
            ),
        )
    }

    @Test
    fun `cause 链有环时也不死循环`() {
        // 自引用 cause 在 JVM 上造不出来（initCause 拒绝），但畸形实现可以；这里直接
        // 构造一个 cause 指向自己的异常，验证深度上限生效。
        val looping = object : RuntimeException("环") {
            override val cause: Throwable get() = this
        }
        assertEquals("环", describeFileFailure(looping))
    }

    @Test
    fun `describeFileFailure 对任意 Throwable 都不抛异常`() {
        val throwables: List<Throwable> = listOf(
            TypertException("workspace-file/not-text", "x"),
            TypertException("", ""),
            RuntimeException(),
            Error("boom"),
            IllegalStateException("state"),
        )
        for (cause in throwables) {
            assertNull("不应抛异常：$cause", runCatching { describeFileFailure(cause) }.exceptionOrNull())
        }
    }
}
