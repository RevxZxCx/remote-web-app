package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.revxzxcx.remotewebapp.core.session.CatalogFailure
import com.revxzxcx.remotewebapp.core.session.CatalogGroup
import com.revxzxcx.remotewebapp.core.session.CatalogModel
import com.revxzxcx.remotewebapp.core.session.ModelCatalog
import com.revxzxcx.remotewebapp.core.session.ModelRef
import com.revxzxcx.remotewebapp.core.session.ReasoningOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ModelPickerDialog] 的渲染测试（Robolectric，决策 D22 推翻 D21）。
 *
 * 形态在 R046 ④（用户 m31204）从 `AlertDialog` 换成了 `ModalBottomSheet`——函数名没改，
 * 是为了不对 8 处调用点做无意义的改名；但它现在渲染的是**上拉面板**，不是对话框。
 * 因此本文件里凡涉及「完成按钮」「关闭路径」的断言都按面板语义重写过。
 *
 * 为什么值得为它写渲染测试：模型切换是 M5 新功能，最容易错的两处都在 UI 与参数的接缝上——
 * ① 点一个模型时该不该带 `reasoningEffort`（带了服务端 `arguments-invalid`，漏带用户以为
 * 没生效）；② 目录拉取失败的提示文案。这两件事纯逻辑层测不到，因为 `effortOf` 的选择规则
 * 恰恰编码在「当前选中谁」这个 UI 状态上。
 *
 * 与 `MdViewRenderTest` 同样的两条教训：Robolectric 虚拟屏幕只有几屏高，落在视口外的节点
 * 仍会被组合，所以断言「在不在屏上」要用 `assertExists()`；点视口外的节点要先滚动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelPickerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val catalog = ModelCatalog(
        default = ModelRef("deepseek", "deepseek-chat", "high"),
        routableProviders = listOf("deepseek", "openrouter"),
        groups = listOf(
            CatalogGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    CatalogModel(
                        id = "deepseek-chat",
                        name = "DeepSeek Chat",
                        description = "通用对话模型",
                        efforts = listOf(
                            ReasoningOption("low", "低"),
                            ReasoningOption("high", "高"),
                        ),
                        defaultEffort = "high",
                    ),
                    CatalogModel(id = "deepseek-reasoner", name = "DeepSeek Reasoner"),
                ),
            ),
            CatalogGroup(
                id = "openrouter",
                name = "OpenRouter",
                models = listOf(CatalogModel(id = "gpt", name = "GPT")),
            ),
        ),
        failures = listOf(CatalogFailure("anthropic", "Anthropic", "timeout")),
    )

    @Test
    fun `分组与模型都渲染出来`() {
        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = null,
                onSelect = { _, _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("选择模型").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek").assertExists()
        compose.onNodeWithText("OpenRouter").assertExists()
        compose.onNodeWithText("DeepSeek Chat").assertExists()
        compose.onNodeWithText("DeepSeek Reasoner").assertExists()
        // id 也显示：用户拿它去桌面端配置时要用。
        compose.onNodeWithText("deepseek-chat").assertExists()
    }

    @Test
    fun `拉取失败的 provider 会告诉用户而不是静默少几个选项`() {
        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = null,
                onSelect = { _, _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("部分模型源不可用：Anthropic").assertExists()
    }

    @Test
    fun `点没有推理档的模型不带 effort`() {
        var provider: String? = null
        var model: String? = null
        var effort: String? = null

        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = null,
                onSelect = { p, m, e ->
                    provider = p
                    model = m
                    effort = e
                },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("DeepSeek Reasoner").performClick()

        assertEquals("deepseek", provider)
        assertEquals("deepseek-reasoner", model)
        // 这个模型没有 reasoning.efforts：带任何字符串都会让服务端回
        // gateway/arguments-invalid: unexpected "reasoningEffort"。
        assertNull(effort)
    }

    @Test
    fun `点有推理档但未选中的模型用服务端默认档`() {
        var effort: String? = null

        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = null,
                onSelect = { _, _, e -> effort = e },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("DeepSeek Chat").performClick()

        // 服务端给了 defaultEffort: "high"，就应当带它——不带的话用户每轮都要手动调档。
        assertEquals("high", effort)
    }

    @Test
    fun `重新点已选中的模型沿用当前档而不是退回默认档`() {
        var effort: String? = null

        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                // 当前选的就是 deepseek-chat，但档位是 low（不是默认的 high）。
                current = ModelRef("deepseek", "deepseek-chat", "low"),
                onSelect = { _, _, e -> effort = e },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("DeepSeek Chat").performClick()

        // 用户就是来换档的：退回默认档会把他的选择悄悄改掉。
        assertEquals("low", effort)
    }

    @Test
    fun `点推理档按钮带出该档`() {
        var effort: String? = null

        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = ModelRef("deepseek", "deepseek-chat", "low"),
                onSelect = { _, _, e -> effort = e },
                onDismiss = {},
            )
        }

        // 档位只在选中的模型下面展开，所以「高」这个节点此刻才存在。
        compose.onNodeWithText("高").performClick()
        assertEquals("high", effort)
    }

    @Test
    fun `当前选中的模型带单选标记`() {
        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = ModelRef("deepseek", "deepseek-chat", "high"),
                onSelect = { _, _, _ -> },
                onDismiss = {},
            )
        }

        // RadioButton 的 selected 状态不可直接断言，用「档位行只在该模型下展开」间接证明：
        // 选中态成立时才会渲染推理档按钮。
        compose.onNodeWithText("低").assertExists()
        compose.onNodeWithText("高").assertExists()
    }

    @Test
    fun `空目录给出说明而不是空白对话框`() {
        compose.setContent {
            ModelPickerDialog(
                catalog = ModelCatalog(),
                current = null,
                onSelect = { _, _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("桌面端没有返回可选的模型。").assertExists()
    }

    /**
     * R046 ④：形态从 `AlertDialog` 换成 `ModalBottomSheet` 之后，「完成」按钮不再存在
     * （`ModalBottomSheet` 靠下滑、点遮罩或返回键关闭）。
     *
     * 这条用例原本断言 `onNodeWithText("完成").performClick()` 会回调 `onDismiss`。换形态后
     * 那个按钮没了，用例直接红。改成守「旧按钮确实不存在」——它同时也是一道回归闸：谁把
     * 面板改回 `AlertDialog`、或者顺手补一个「完成」按钮，这条就会红。
     *
     * 关闭路径（点遮罩 / 下滑 / 返回键）**没有**被 Robolectric 覆盖。试过一次后用
     * `onAllNodesWithContentDescription("Close sheet", substring = true)` 去点遮罩：
     * 节点能找到，但点击不触发 `onDismissRequest`（面板的 `sheetState` 在虚拟时钟下还没
     * settle，遮罩的输入被吞掉）。那次尝试的代码**已经撤回**，没有留在文件里——注释只在
     * 记录「这条路走不通」，别照着它去找不存在的代码。关闭路径留到设备验收覆盖，
     * 不在这里造假通过。
     */
    @Test
    fun `改成上拉面板后没有完成按钮`() {
        compose.setContent {
            ModelPickerDialog(
                catalog = catalog,
                current = null,
                onSelect = { _, _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("选择模型").assertExists()
        compose.onAllNodesWithText("完成").assertCountEquals(0)
    }
}
