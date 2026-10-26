package com.revxzxcx.remotewebapp.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 顶栏洞察入口（◔ 按钮 + 5dp 圆点）的语义回归测试（T076）。
 *
 * ## 守的是什么
 *
 * 「有新数据」这个状态**必须挂在按钮自己的描述里**，而不是挂在圆点上。
 *
 * 初版实现把 `contentDescription = "有新数据"` 写在那个 5dp 圆点上，同时按钮仍是
 * 「总上下文洞察」。后果有两个：读屏把圆点当成一个**独立**的可停留节点，用户得滑两次
 * 才能拼出「这里有个按钮、它说有新数据」；而且圆点上同时写了 `stateDescription` 与
 * `contentDescription`，TalkBack 会把同一句话说两遍。
 *
 * 修法：圆点对读屏隐形（`clearAndSetSemantics { }`），状态并进按钮描述
 * （「总上下文洞察，有新数据」）。
 *
 * ## 为什么用 `onAllNodesWithContentDescription` 断言「只有一个」
 *
 * 只断言「存在某个节点描述含新数据」是不够的：旧实现里圆点自己也带着这句话，
 * 那样断言依然通过。必须同时钉住「全树只有一个节点带这个描述」，
 * 才能证明圆点已经隐形、状态已经并进按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeInsightsEntryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dotMergesNewDataIntoTheButtonDescription() {
        compose.setContent {
            InsightsIconButton(hasNew = true, onClick = {})
        }
        compose.waitForIdle()
        // 按钮描述带上状态。
        compose.onNodeWithContentDescription("总上下文洞察，有新数据").assertExists()
        // 圆点不再是一个独立的「有新数据」节点：全树只有一个节点带这句描述
        // （就是按钮自己）。旧实现这里是 2（按钮 + 圆点）。
        compose.onAllNodesWithContentDescription("总上下文洞察，有新数据").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("有新数据").assertCountEquals(0)
    }

    @Test
    fun noDotLeavesTheDescriptionPlain() {
        compose.setContent {
            InsightsIconButton(hasNew = false, onClick = {})
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("总上下文洞察").assertExists()
        compose.onAllNodesWithContentDescription("有新数据", substring = true).assertCountEquals(0)
    }

    @Test
    fun clickReachesTheCaller() {
        var taps = 0
        compose.setContent {
            InsightsIconButton(hasNew = true, onClick = { taps++ })
        }
        compose.waitForIdle()
        // 圆点对读屏隐形、也不可点：点描述命中的必须是按钮本体，动作要真的到达调用方。
        compose.onNodeWithContentDescription("总上下文洞察，有新数据").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }
}
