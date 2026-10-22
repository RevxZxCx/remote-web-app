package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.AbortCause
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轮次结局卡的**渲染层**（R043 第 1 条，用户 m26743：「app 端是看不到具体的内容的」）。
 *
 * 这里守的是 [TurnResultTextTest] 覆盖不到的那部分：卡片真的被挂到界面上、
 * 上游错误原文真的出现在界面上、按钮真的能点、点了真的回调。
 *
 * 为什么必须单独有一条渲染测试：文案函数全对但 `ChatScreen` 的 `when` 漏了
 * `is ChatItem.TurnResult` 分支时，用户看到的仍然是空白——`when` 不是穷尽
 * 检查能抓住的那种错（它编译不过，但把分支写成 `else -> Unit` 就静默吞掉）。
 * 所以这里直接渲染 [TurnResultCard] 本体，钉住「有内容可看」这个事实。
 *
 * Robolectric 默认视口只有 320×470 px，比一张结局卡还矮——卡片会被排到视口外
 * （节点坐标变负），`assertIsDisplayed` 就会全红，看着像「没渲染」其实是「没在屏内」。
 * 所以这里用 `qualifiers` 把虚拟屏放大到 400×1600dp（真机竖屏的 2 倍高），
 * 并保留 `requiredHeight(1200.dp)` 容器（**不能**用 `height`，会被父约束收敛回去）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1600dp-xhdpi")
class TurnResultCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setCard(
        outcome: TurnOutcome,
        onRetry: (() -> Unit)? = null,
        onContinue: (() -> Unit)? = null,
    ) {
        compose.setContent {
            Box(modifier = Modifier.requiredHeight(1200.dp)) {
                TurnResultCard(
                    item = ChatItem.TurnResult(seq = 42L, time = 1_000L, turn = 7L, outcome = outcome),
                    onRetry = onRetry,
                    onContinue = onContinue,
                )
            }
        }
    }

    @Test
    fun upstreamErrorMessageIsVisibleOnScreen() {
        // 这条是用户抱怨的核心：以前只有「第 7 轮结束」，现在必须看到上游原话。
        setCard(TurnOutcome.Failed(message = "UID rate limit reached for TPM for UID", code = "RATE_LIMIT", status = null))
        compose.onNodeWithText("本轮失败 · 模型限流").assertIsDisplayed()
        compose.onNodeWithText("RATE_LIMIT").assertIsDisplayed()
        compose.onNodeWithText("UID rate limit reached for TPM for UID", substring = true).assertIsDisplayed()
        // 卡片显示的是「第 turn+1 轮」：turn 是 0 基的轮次序号，用户看到的是第 8 轮。
        compose.onNodeWithText("第 8 轮", substring = true).assertIsDisplayed()
    }

    @Test
    fun retryButtonCallsBackOnceWhenOffered() {
        var retried = 0
        setCard(
            outcome = TurnOutcome.Failed(message = "boom", code = "SERVER", status = null),
            onRetry = { retried++ },
        )
        compose.onNodeWithText("重试本轮").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun retryIsAbsentWhenTheCodeCannotBeHelped() {
        // QUOTA_EXCEEDED 点重试必然再失败，给了按钮等于骗用户再撞一次。
        // 判据在 `canRetry`，由调用方（ChatScreen）决定传给卡片的是 null 还是回调——
        // 卡片自己只负责「给了就画」，不重复判断，所以这里按真实契约传 null。
        assertFalse(canRetry(TurnOutcome.Failed(message = "quota", code = "QUOTA_EXCEEDED", status = null)))
        setCard(
            outcome = TurnOutcome.Failed(message = "quota", code = "QUOTA_EXCEEDED", status = null),
            onRetry = null,
        )
        compose.onNodeWithText("重试本轮").assertDoesNotExist()
    }

    @Test
    fun truncatedOffersContinueAndCallsBack() {
        var continued = 0
        setCard(outcome = TurnOutcome.Truncated, onContinue = { continued++ })
        compose.onNodeWithText("输出被截断").assertIsDisplayed()
        compose.onNodeWithText("继续写").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun userAbortExplainsWhoStoppedIt() {
        setCard(TurnOutcome.Aborted(AbortCause.USER))
        compose.onNodeWithText("本轮已中止").assertIsDisplayed()
        compose.onNodeWithText("STOPPED").assertIsDisplayed()
    }

    @Test
    fun parentAbortReadsDifferentlyFromUserAbort() {
        setCard(TurnOutcome.Aborted(AbortCause.PARENT))
        compose.onNodeWithText("被父会话中止").assertIsDisplayed()
        compose.onNodeWithText("本轮已中止").assertDoesNotExist()
    }

    @Test
    fun longErrorTextCanBeExpandedToFull() {
        // 折叠默认 3 行；超过闸值的原文给「展开全文」，点开后按钮消失。
        val long = "x".repeat(200)
        setCard(TurnOutcome.Failed(message = long, code = "INVALID_REQUEST", status = null))
        compose.onNodeWithText("展开全文").assertIsDisplayed().performClick()
        compose.onNodeWithText("展开全文").assertDoesNotExist()
    }
}
