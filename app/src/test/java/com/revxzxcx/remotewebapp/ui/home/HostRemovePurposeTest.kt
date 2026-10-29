package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `HostRemoveDialog` 的两种用途文案测试（M57 真机验收发现的缺陷）。
 *
 * ## 这条测试守的是什么
 *
 * 修复前：`pendingRepairHost` 与 `pendingRemoveHost` 共用 `HostRemoveDialog` 且**没传任何
 * 区分信息**，于是点错误横幅上的「重新配对」，弹出来的框写的是「从列表移除？」、图标是
 * 红色垃圾桶、确认按钮写「确认移除」。用户点的是「修好它」，看到的是「删掉它」。
 *
 * 真机截图证据：`F:/bot/_al0.png`（标题「从列表移除？」+ 正文「桌面端不受影响，
 * 这台在本机缓存的会话与草稿会被清掉。重新配对就能加回来；其余 3 台保留。」）
 * —— 正文里那句「其余 3 台保留」是 Repair 语义想要的，但标题和按钮全错。
 *
 * 所以这里断言三件事，任何一条退化都会红：
 * 1. `Repair` 的标题**不能**出现「移除」二字——那个词是「本机不再显示」，
 *    而用户点「重新配对」期望的是「重新建立连接」。
 * 2. `Repair` 的确认按钮**不能**是「确认移除」。
 * 3. 两种用途的标题必须不同（防止有人图省事又把文案统一回去）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostRemovePurposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val host = fakeHost()

    @Test
    fun `Repair 的标题不说移除 说的是重新配对`() {
        val copy = HostRemovePurpose.Repair.resolve(total = 4)
        assertFalse(
            "重新配对的标题里出现了「移除」，用户会以为自己在删东西：${copy.title}",
            copy.title.contains("移除"),
        )
        assertTrue("标题应指向重新配对：${copy.title}", copy.title.contains("重新配对"))
    }

    @Test
    fun `Repair 的确认按钮不是确认移除`() {
        val copy = HostRemovePurpose.Repair.resolve(total = 4)
        assertFalse("确认按钮仍是移除语义：${copy.confirm}", copy.confirm.contains("移除"))
    }

    @Test
    fun `两种用途的标题必须不同`() {
        val remove = HostRemovePurpose.Remove.resolve(total = 4)
        val repair = HostRemovePurpose.Repair.resolve(total = 4)
        assertTrue(
            "两种用途文案相同，说明区分丢了：${remove.title}",
            remove.title != repair.title,
        )
        assertTrue("移除的标题应是「从列表移除？」：${remove.title}", remove.title.contains("移除"))
    }

    @Test
    fun `最后一台时正文要提示会回到配对页 多台时要报剩余数量`() {
        val only = HostRemovePurpose.Remove.resolve(total = 1)
        assertTrue("只剩一台时应提示会回到配对页：${only.body}", only.body.contains("最后一台"))

        val many = HostRemovePurpose.Repair.resolve(total = 4)
        assertTrue("多台时应说明其余几台保留：${many.body}", many.body.contains("其余 3 台"))

        val repairOnly = HostRemovePurpose.Repair.resolve(total = 1)
        assertTrue(
            "重新配对最后一台时也要提示列表会空：${repairOnly.body}",
            repairOnly.body.contains("清空"),
        )
    }

    @Test
    fun `Repair 弹窗渲染出来的是重新配对 不是从列表移除`() {
        compose.setContent {
            MaterialTheme {
                HostRemoveDialog(
                    host = host,
                    total = 3,
                    purpose = HostRemovePurpose.Repair,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        compose.onNodeWithText("重新配对这台？").assertIsDisplayed()
        compose.onNodeWithText("删掉重配").assertIsDisplayed()
    }

    @Test
    fun `Remove 弹窗渲染出来仍是从列表移除`() {
        compose.setContent {
            MaterialTheme {
                HostRemoveDialog(
                    host = host,
                    total = 3,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        compose.onNodeWithText("从列表移除？").assertIsDisplayed()
        compose.onNodeWithText("确认移除").assertIsDisplayed()
    }
}

/** 造一台足够渲染弹窗的凭证。 */
private fun fakeHost() = com.revxzxcx.remotewebapp.core.credentials.DeviceCredential(
    baseUrl = "http://127.0.0.1:19387",
    deviceId = "d1",
    pairedAt = 0L,
    label = "PGBM10",
    hostId = "h1",
)
