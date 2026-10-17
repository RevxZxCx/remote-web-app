package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 展开分组**不能崩**，且成员必须能渲染出来。
 *
 * ## 这条测试为什么存在（R028b 审查 P1-1 的教训）
 * 修「分组左侧 1dp 层级导轨画不出来」时，第一版修法是给外层 `Row` 加
 * `height(IntrinsicSize.Min)`。它会让 Row 向子项请求**固有高度**，而子项里有
 * `LazyColumn`——`SubcomposeLayout` 家族一律不支持固有测量，直接抛
 *
 * ```
 * IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout
 * layouts is not supported. This includes components that are built on top of
 * SubcomposeLayout, such as lazy lists, BoxWithConstraints, TabRow, etc.
 * ```
 *
 * 定稿改成 `drawBehind`（布局完成后才画，`size.height` 已是实测值）：零固有测量、
 * 零额外布局开销，也不受无界高度影响。
 *
 * **既有测试抓到了这个崩溃**（`GroupRowRenderTest` 三条），说明那条测试有分辨力；
 * 这里再显式钉一次「展开分组在 LazyColumn 里能渲染」，把约束写成断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupRailTest {

    @get:Rule
    val compose = createComposeRule()

    private val attachments = SessionAttachmentRepository(
        TypertClient(
            baseUrl = "http://127.0.0.1:1",
            deviceId = "test-device",
            http = OkHttpClient(),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        ),
    )

    private fun toolCall(seq: Long, name: String, args: String = "{}") =
        ChatItem.ToolCall(
            seq = seq, time = 0L, turn = 1L, step = 1L,
            callId = "c$seq", name = name, arguments = args,
        )

    /** 展开一个含多个成员的分组，成员必须全部出现在语义树里且不崩。 */
    @Test
    fun `LazyColumn 里的展开分组能渲染全部成员`() {
        val members = listOf(
            toolCall(1, "read", """{"file_path":"a.kt"}"""),
            toolCall(2, "pwsh", """{"command":"ls"}"""),
            toolCall(3, "write", """{"file_path":"b.kt","content":"x"}"""),
        )
        compose.setContent {
            DshAssistantTheme {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(listOf(Unit)) {
                        GroupRow(
                            members = members,
                            running = false,
                            now = 0L,
                            changes = emptyMap(),
                            changesLoading = emptySet(),
                            onOpenFile = { _: FileTarget -> },
                            onRetryChanges = {},
                            attachments = attachments,
                            sessionId = "s",
                        )
                    }
                }
            }
        }

        // 标题由 `ChatFold.kt` 的 `foldTitle` 决定：三个类别（read/pwsh/write）各 1 次
        // → 用逗号连接成三段（只有 >3 类才加「等」）。先前我按两类的口径写成
        // 「已读取文件并写入文件」，点不中。
        compose.onNodeWithText("已读取文件，执行了命令，已写入文件").performClick()
        compose.waitForIdle()

        // 三个成员各自的行都在（工具名 chip 是裸文本）。
        compose.onNodeWithText("read").assertExists()
        compose.onNodeWithText("pwsh").assertExists()
        compose.onNodeWithText("write").assertExists()
    }
}
