package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 判一次 `snapshotFlow` 到底观察谁。
 *
 * T7 的第一条症状（「生成时不自动往下滚」）让我怀疑 `snapshotFlow` 观察不到
 * `StateFlow` 的变化。这里把它钉成可执行的契约，避免下次再靠猜：
 *
 * - `MutableStateFlow`（kotlinx.coroutines）**不参与** Compose 快照系统，所以
 *   `snapshotFlow { flow.value }` 只在建立时发射一次。
 * - `collectAsState()` 把它桥接成 Compose `State`，所以
 *   `snapshotFlow { state }` 会在每次状态变化时重新发射。
 *
 * 结论直接决定 [ChatScreen] 里跟随滚动该读哪一个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SnapshotFlowObservationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `StateFlow 的原值不被快照系统观察`() {
        val flow = MutableStateFlow(0)
        var emissions = 0
        compose.setContent {
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { flow.value }
                    .collect { emissions++ }
            }
        }
        compose.runOnIdle { runBlocking { flow.value = 1 } }
        compose.runOnIdle { flow.value = 2 }
        compose.runOnIdle {
            assertTrue(
                "直接读 StateFlow 的原值不被快照系统观察：两次赋值不该各自触发一次发射，实际 $emissions 次",
                emissions < 3,
            )
        }
    }

    @Test
    fun `collectAsState 桥接出来的 State 会被快照系统观察`() {
        val flow = MutableStateFlow(0)
        var emissions = 0
        compose.setContent {
            val state by flow.collectAsState()
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { state }
                    .collect { emissions++ }
            }
        }
        compose.runOnIdle { runBlocking { flow.value = 1 } }
        compose.runOnIdle { flow.value = 2 }
        compose.runOnIdle {
            assertTrue(
                "通过 collectAsState 读到的状态变化应当触发 snapshotFlow 重新发射，实际只发射了 $emissions 次",
                emissions >= 3,
            )
        }
    }
}
