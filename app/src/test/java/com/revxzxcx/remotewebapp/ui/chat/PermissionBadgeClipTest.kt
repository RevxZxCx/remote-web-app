package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.events.ApprovalPolicy
import com.revxzxcx.remotewebapp.core.events.SandboxMode
import com.revxzxcx.remotewebapp.core.events.SessionPermission
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 待批角标不能被父级裁剪（R028b 审查 P1-2）。
 *
 * 早先 `PermissionChip` 的根 `Box` 带 `clip(CircleShape)`，而角标用
 * `offset(x = 3.dp, y = (-3).dp)` 挂在 32dp 圆**外**——被父级图层裁掉，只剩内侧一角，
 * 数字读不出来。而「有待批」是用户最需要看见的信号（用户 m44550 第 4 条在意这个按钮）。
 *
 * 修法：父 Box 不再 `clip`，改让背景层自裁（`background(color, CircleShape)`），
 * 描边走 `border`（不裁剪子项）。
 *
 * 判据：角标节点的 bounds 必须**伸出**父级 32dp 方框的右边与上边。
 * 这条测试只在「角标没被裁」时通过——被裁掉时它根本不在语义树里（`assertExists` 会挂）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionBadgeClipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `待批角标伸出圆钮之外且可见`() {
        compose.setContent {
            DshAssistantTheme {
                Box(modifier = Modifier.size(120.dp)) {
                    PermissionChip(
                        permission = SessionPermission(
                            sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                            approvalPolicy = ApprovalPolicy.ASK,
                        ),
                        pendingCount = 2,
                        onClick = {},
                    )
                }
            }
        }

        // 🔴 用 testTag 而不是 `onNodeWithText("2")`：`PermissionChip` 的根 `Box` 带
        // `clickable`，Compose 会把子节点语义**合并**进去，`onNodeWithText("2")` 命中的
        // 是合并后的圆钮本身（bounds 恒等于 32dp）；即使加 `useUnmergedTree`，量到的也是
        // `Text` 而不是角标容器（内边距会把 bounds 缩进 1dp）。首跑两次都是这么假失败的。
        val badge = compose.onNodeWithTag(PermissionBadgeTestTag, useUnmergedTree = true).getBoundsInRoot()
        val chip = compose.onNodeWithContentDescription("审批权限").getBoundsInRoot()

        assertTrue(
            "角标 (right=${badge.right.value}) 没有伸出圆钮 (right=${chip.right.value})，" +
                "多半又被 clip 裁掉了",
            badge.right.value > chip.right.value,
        )
        assertTrue(
            "角标 (top=${badge.top.value}) 没有伸出圆钮 (top=${chip.top.value})",
            badge.top.value < chip.top.value,
        )
    }

    @Test
    fun `没有待批时不显示角标`() {
        compose.setContent {
            DshAssistantTheme {
                PermissionChip(
                    permission = SessionPermission(sandboxMode = SandboxMode.READ_ONLY),
                    pendingCount = 0,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithContentDescription("审批权限").assertExists()
        compose.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
    }
}
