package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.session.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ModelBar] 的渲染测试（Robolectric，决策 D22）。
 *
 * 为什么值得写：模型条在**输入区展开态的配置行**里，是用户改模型的唯一入口，而它的
 * 信息密度（`provider/model` + 推理档）在 R017 被重构成胶囊徽标。重构引入了三条新的
 * 可回归契约，都只能由渲染层钉住：
 *
 * ① 文案去掉了「模型 · 」前缀。胶囊本身已经是「这是一条模型信息」的形状，前缀是
 *    冗余文字；留着会让 `provider/model` 在窄屏上更早被截断。
 * ② 推理档是**独立的一枚小徽标**而不是拼进同一串文字。`provider/model` 与 `high`
 *    是两个维度的信息，混在一行等宽字体里用户得自己断开读。
 * ③ 点了胶囊必须真的调 [onOpen]（`Surface(onClick=…)` 的 enabled 参数容易写错：
 *    `loading = true` 时点它不该有任何反应）。
 *
 * R019（用户 m27990）起模型条与 [PermissionChip] 并排收进 Composer 的展开态配置行，
 * 因此 [ModelBar] 自身从「整行 surfaceVariant 容器」改成裸 `Row`。那条容器要是加回来，
 * 配置行会变成一整块糊色、`fillMaxWidth` 还会把权限徽标挤出屏幕——**这类回归只有渲染
 * 测试能发现**：单测不会碰 Compose，而语义树里 `Row` 与 `Surface(color=surfaceVariant)`
 * 都不留痕迹。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `有模型时显示等宽的 provider 与 model 且不带前缀`() {
        compose.setContent {
            ModelBar(
                model = ModelRef("deepseek", "deepseek-chat", "high"),
                loading = false,
                error = null,
                onOpen = {},
            )
        }
        compose.onNodeWithText("deepseek/deepseek-chat").assertExists()
        // 前缀被去掉（R017 ①）：这个断言就是防止有人把「模型 · 」加回来。
        assertEquals(0, compose.onAllNodesWithText("模型").fetchSemanticsNodes().size)
    }

    @Test
    fun `推理档渲染成独立徽标`() {
        compose.setContent {
            ModelBar(
                model = ModelRef("deepseek", "deepseek-chat", "high"),
                loading = false,
                error = null,
                onOpen = {},
            )
        }
        compose.onNodeWithText("high").assertIsDisplayed()
    }

    @Test
    fun `没有 effort 时不出现第二枚徽标`() {
        compose.setContent {
            ModelBar(
                model = ModelRef("stepfun", "step-5-preview"),
                loading = false,
                error = null,
                onOpen = {},
            )
        }
        compose.onNodeWithText("stepfun/step-5-preview").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("high").fetchSemanticsNodes().size)
    }

    @Test
    fun `没有模型时显示桌面端默认`() {
        compose.setContent {
            ModelBar(model = null, loading = false, error = null, onOpen = {})
        }
        compose.onNodeWithText("桌面端默认模型").assertIsDisplayed()
    }

    @Test
    fun `点胶囊真的打开选择器`() {
        var opens = 0
        compose.setContent {
            ModelBar(
                model = ModelRef("deepseek", "deepseek-chat"),
                loading = false,
                error = null,
                onOpen = { opens++ },
            )
        }
        compose.onNodeWithText("deepseek/deepseek-chat").performClick()
        compose.waitForIdle()
        assertEquals(1, opens)
    }

    @Test
    fun `读取目录时点不动`() {
        var opens = 0
        compose.setContent {
            ModelBar(
                model = null,
                loading = true,
                error = null,
                onOpen = { opens++ },
            )
        }
        compose.onNodeWithText("正在读取模型列表…").performClick()
        compose.waitForIdle()
        assertEquals(0, opens)
    }

    @Test
    fun `拉取失败时错误与胶囊并存`() {
        compose.setContent {
            ModelBar(
                model = ModelRef("deepseek", "deepseek-chat"),
                loading = false,
                error = "模型目录读取失败",
                onOpen = {},
            )
        }
        // 失败不能把模型条吃掉：用户仍该看到当前用的是哪个模型。
        compose.onNodeWithText("deepseek/deepseek-chat").assertExists()
        compose.onNodeWithText("模型目录读取失败").assertExists()
    }
}
