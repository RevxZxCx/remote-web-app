package com.revxzxcx.remotewebapp.ui.pair

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B002 的**界面契约**测试：配对失败之后「重新扫描」入口的出现与消失。
 *
 * 为什么单独一个类：`ScanCard` 只有在拿到相机权限时才渲染 `AndroidView(BarcodeView)`，
 * 而 `BarcodeView` 在 Robolectric 里会去碰摄像头硬件。这里先把权限授上再试，
 * 构造不出来就 `assumeTrue` 跳过并说明原因——**不假装测过**。
 *
 * 状态机本身（重新武装落在同一个控件上、欠账补做、旧控件不抹新引用等 13 条）
 * 由 [ScanChannelTest] 在纯 JVM 层覆盖，不依赖这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScanCardRescanEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun grantCamera(): Boolean = try {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        true
    } catch (e: Throwable) {
        false
    }

    @Test
    fun `扫到过一次之后才出现重新扫描入口 点掉之后入口消失`() {
        assumeTrue("Robolectric 无法授予 CAMERA，跳过界面契约测试", grantCamera())

        val channel = ScanChannel()
        compose.setContent {
            MaterialTheme {
                ScanCard(enabled = true, channel = channel, onScanned = {})
            }
        }

        // 还在等第一个码：不该有这个入口。
        compose.onAllNodesWithText("重新扫描").assertCountEquals(0)

        compose.runOnIdle { channel.onResult("TOKEN", enabled = true) }

        compose.onNodeWithText("重新扫描").assertExists()
        compose.onNodeWithText("重新扫描").performClick()

        // 点完闸门必须真的复位，否则用户会觉得「点了没反应」。
        compose.onAllNodesWithText("重新扫描").assertCountEquals(0)
    }

    @Test
    fun `配对进行中不显示重新扫描入口`() {
        assumeTrue("Robolectric 无法授予 CAMERA，跳过界面契约测试", grantCamera())

        val channel = ScanChannel()
        compose.setContent {
            MaterialTheme {
                ScanCard(enabled = false, channel = channel, onScanned = {})
            }
        }
        compose.runOnIdle { channel.onResult("TOKEN", enabled = true) }

        compose.onAllNodesWithText("重新扫描").assertCountEquals(0)
    }
}
