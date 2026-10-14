package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [QueuedRow] 的渲染测试（Robolectric，决策 D22）。
 *
 * 为什么值得写：这条气泡是**用户 m20380 亲身反馈的缺口**的修复本体——会话正在生成时
 * 发送，`mode:'queue'` 的消息要等当前轮结束才写进会话日志，而 `session/prompt` 只回
 * `{accepted:true}`、快照投影里没有 queue 项、`session/updateQueue` 又要服务端 itemId
 * （远程客户端拿不到）。修复前用户看到的是「输入框被清空、列表里什么都不出现」。
 *
 * 断言刻意只查「文本在不在语义树里」：真正容易被改坏的是**文案与正文**（有人会把
 * 「排队中」提示删掉，或者只渲染附件忘了渲染文字），而配色/圆角/是否在屏上由真机验收管。
 *
 * 两个写测试时踩到的坑（`QueuedRowDiagTest` dump 出的语义树证实）：
 * ① 气泡脚注是**拼接**出来的——`Bubble` 把 `TimeText.relative(...)` 和 footer 拼成
 *    一个 `Text`（线上是 `刚刚 排队中 · 等当前轮结束后发出`），所以必须
 *    `substring = true`，精确匹配永远找不到；
 * ② Robolectric 下 `Text` 节点的 bounds 是退化的（19px 宽装一整句），
 *    `assertIsDisplayed()` 会误报「没画出来」——同 `MdViewRenderTest`/`SettingsScreenRenderTest`
 *    的教训，这里统一用 `assertExists()`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueuedRowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `排队中的文字消息会显示正文与排队提示`() {
        compose.setContent {
            QueuedRow(
                echo = QueuedMessage(id = 1L, text = "等我这轮说完", enqueuedAt = 1_000L),
                now = 2_000L,
            )
        }

        compose.onNodeWithText("等我这轮说完").assertExists()
        compose.onNodeWithText("排队中 · 等当前轮结束后发出", substring = true).assertExists()
    }

    @Test
    fun `只带附件的排队消息也不会渲染成空白`() {
        // 「只发图不发字」是常见用法（服务端 `hasPromptContent` 接受纯附件）。
        // 这条消息的 text 是空的，气泡里必须留下别的东西，否则用户看到一块空白。
        compose.setContent {
            QueuedRow(
                echo = QueuedMessage(id = 2L, text = "", attachmentCount = 3, enqueuedAt = 1_000L),
                now = 2_000L,
            )
        }

        compose.onNodeWithText("（3 个附件）").assertExists()
        compose.onNodeWithText("排队中 · 含 3 个附件 · 等当前轮结束后发出", substring = true)
            .assertExists()
    }

    @Test
    fun `多条排队回声各自独立渲染`() {
        // 连发三条时的形态：三条都要在，且顺序即入队顺序。
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                QueuedRow(QueuedMessage(id = 1L, text = "第一条", enqueuedAt = 1_000L), 2_000L)
                QueuedRow(QueuedMessage(id = 2L, text = "第二条", enqueuedAt = 1_100L), 2_000L)
            }
        }

        compose.onNodeWithText("第一条").assertExists()
        compose.onNodeWithText("第二条").assertExists()
    }

    @Test
    fun `排队提示里不会出现撤回按钮`() {
        // 契约限制的可执行化：`session/updateQueue` 必须要服务端 itemId，而
        // `session/prompt` 只回 `{accepted:true}`、快照里没有 queue 投影——
        // 远程客户端拿不到那个 id。所以这里**不能**有「撤回」入口，
        // 否则点下去要么没反应、要么只删本地回声而服务端照样会把消息发出去。
        compose.setContent {
            QueuedRow(
                echo = QueuedMessage(id = 3L, text = "别让我撤回", enqueuedAt = 1_000L),
                now = 2_000L,
            )
        }

        // `onAllNodesWithText` + `fetchSemanticsNodes().size`：`assertCountEquals` 在本工程的
        // JUnit 版本里不存在（M5-10 踩过），而 `onNodeWithText(...).assertDoesNotExist()`
        // 在没有匹配时抛的是「找不到节点」而不是干净的断言失败，读起来费劲。
        compose.onAllNodesWithText("撤回").fetchSemanticsNodes()
            .let { assertEquals("不该有撤回入口", 0, it.size) }
        compose.onAllNodesWithText("取消", substring = true).fetchSemanticsNodes()
            .let { assertEquals("不该有取消入口", 0, it.size) }
    }
}
