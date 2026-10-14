package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelCatalogParser] 的契约测试。
 *
 * 断言用的是**实测**形状（`F:\bot\_model_probe.mjs` 打真实 127.0.0.1:19387 抓回来的 JSON），
 * 不是凭 schema 想象的——本项目的 wire 契约已经错过好几次（`session/list` 的 `_request`、
 * `changes.diff` 的 `before`/`after` 而不是 `beforeExists`/`afterExists`）。
 *
 * 每条都同时钉两件事：**该取的取到**、**不该崩的不崩**（多余字段、缺字段、类型不对）。
 */
class ModelCatalogParserTest {

    private val parser = ModelCatalogParser
    private val json = Json

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `实测形状能完整解析`() {
        val raw = parse(
            """
            {
              "default": {"provider": "deepseek", "model": "deepseek-reasoner", "reasoningEffort": "high"},
              "routableProviders": ["deepseek", "openrouter"],
              "groups": [
                {
                  "id": "deepseek",
                  "name": "DeepSeek",
                  "models": [
                    {
                      "id": "deepseek-chat",
                      "name": "DeepSeek Chat",
                      "description": "通用对话模型",
                      "reasoning": {
                        "efforts": [
                          {"id": "low", "name": "低", "description": "更快"},
                          {"id": "high", "name": "高", "description": "更准"}
                        ],
                        "defaultEffort": "high"
                      }
                    },
                    {
                      "id": "deepseek-reasoner",
                      "name": "DeepSeek Reasoner"
                    }
                  ]
                }
              ],
              "failures": [{"id": "openrouter", "name": "OpenRouter", "message": "timeout"}]
            }
            """.trimIndent(),
        )

        val catalog = parser.parseCatalog(raw)

        assertEquals("deepseek", catalog.default?.provider)
        assertEquals("deepseek-reasoner", catalog.default?.model)
        assertEquals("high", catalog.default?.reasoningEffort)
        assertEquals(listOf("deepseek", "openrouter"), catalog.routableProviders)
        assertEquals(1, catalog.groups.size)

        val group = catalog.groups.single()
        assertEquals("deepseek", group.id)
        assertEquals("DeepSeek", group.name)
        assertEquals(2, group.models.size)

        val chat = group.models.first()
        assertEquals("deepseek-chat", chat.id)
        assertEquals("DeepSeek Chat", chat.name)
        assertEquals("通用对话模型", chat.description)
        assertEquals(2, chat.efforts.size)
        assertEquals("low", chat.efforts[0].id)
        assertEquals("低", chat.efforts[0].name)
        assertEquals("更快", chat.efforts[0].description)
        assertEquals("high", chat.defaultEffort)

        // 没有 reasoning 的模型：档位列表为空，而不是 null 或抛异常。
        val reasoner = group.models[1]
        assertTrue(reasoner.efforts.isEmpty())
        assertNull(reasoner.description)
        assertNull(reasoner.defaultEffort)

        assertEquals(1, catalog.failures.size)
        assertEquals("OpenRouter", catalog.failures.single().name)
        assertEquals("timeout", catalog.failures.single().message)
    }

    @Test
    fun `label 带推理档`() {
        assertEquals(
            "deepseek/deepseek-chat · high",
            ModelRef("deepseek", "deepseek-chat", "high").label,
        )
        assertEquals(
            "deepseek/deepseek-chat",
            ModelRef("deepseek", "deepseek-chat").label,
        )
        // 空字符串与 null 等价：服务端可能回 `reasoningEffort: ""`。
        assertEquals(
            "deepseek/deepseek-chat",
            ModelRef("deepseek", "deepseek-chat", "").label,
        )
    }

    @Test
    fun `modelSelection 投影取 next 而不是 lastUsed`() {
        val raw = parse(
            """
            {
              "lastUsed": {"provider": "deepseek", "model": "deepseek-chat"},
              "next": {"provider": "deepseek", "model": "deepseek-reasoner", "reasoningEffort": "low"}
            }
            """.trimIndent(),
        )

        val next = parser.parseSelection(raw)
        assertEquals("deepseek-reasoner", next?.model)
        assertEquals("low", next?.reasoningEffort)

        val lastUsed = parser.parseSelection(raw, which = "lastUsed")
        assertEquals("deepseek-chat", lastUsed?.model)
        assertNull(lastUsed?.reasoningEffort)
    }

    @Test
    fun `modelSelection 缺 next 时返回 null`() {
        val raw = parse("""{"lastUsed": {"provider": "p", "model": "m"}}""")
        assertNull(parser.parseSelection(raw))
        // 整个投影缺失也不崩。
        assertNull(parser.parseSelection(null))
        assertNull(parser.parseSelection(JsonPrimitive("nope")))
    }

    @Test
    fun `缺 provider 或 model 的引用返回 null`() {
        assertNull(parser.parseSelection(parse("""{"next": {"model": "m"}}""")))
        assertNull(parser.parseSelection(parse("""{"next": {"provider": "p"}}""")))
        assertNull(parser.parseSelection(parse("""{"next": {}}""")))
        // 空字符串等同缺失：拿它去 selectModel 只会拿到 arguments-invalid。
        assertNull(parser.parseSelection(parse("""{"next": {"provider": "", "model": "m"}}""")))
    }

    @Test
    fun `未知字段被忽略而不是报错`() {
        val raw = parse(
            """
            {
              "default": {"provider": "p", "model": "m", "futureField": {"deep": [1, 2]}},
              "routableProviders": ["p"],
              "groups": [
                {
                  "id": "p",
                  "name": "P",
                  "models": [
                    {"id": "m", "name": "M", "contextWindow": 65536, "pricing": {"in": 1}}
                  ]
                }
              ],
              "failures": [],
              "asOf": 12345
            }
            """.trimIndent(),
        )

        val catalog = parser.parseCatalog(raw)
        assertEquals(1, catalog.groups.single().models.size)
        assertEquals("m", catalog.allModels.single().id)
    }

    @Test
    fun `空目录与坏输入都退化成空目录而不是抛异常`() {
        assertEquals(ModelCatalog(), parser.parseCatalog(null))
        assertEquals(ModelCatalog(), parser.parseCatalog(JsonPrimitive("x")))
        assertEquals(ModelCatalog(), parser.parseCatalog(buildJsonObject { }))

        val catalog = parser.parseCatalog(
            parse("""{"groups": [], "routableProviders": [], "failures": []}"""),
        )
        assertTrue(catalog.groups.isEmpty())
        assertTrue(catalog.allModels.isEmpty())
        assertTrue(catalog.refs().isEmpty())
    }

    @Test
    fun `坏条目被跳过而不是让整份目录消失`() {
        val raw = parse(
            """
            {
              "groups": [
                {"id": "good", "name": "Good", "models": [{"id": "m1", "name": "M1"}]},
                {"name": "no id"},
                "not an object",
                {"id": "bad", "name": "Bad", "models": [null, "x", {"name": "no id"}, {"id": "ok", "name": "OK"}]}
              ]
            }
            """.trimIndent(),
        )

        val catalog = parser.parseCatalog(raw)
        assertEquals(listOf("good", "bad"), catalog.groups.map { it.id })
        assertEquals(listOf("m1", "ok"), catalog.allModels.map { it.id })
    }

    @Test
    fun `refs 带出 provider 便于按 ModelRef 比较`() {
        val raw = parse(
            """
            {"groups": [
              {"id": "a", "name": "A", "models": [{"id": "m1", "name": "M1"}, {"id": "m2", "name": "M2"}]},
              {"id": "b", "name": "B", "models": [{"id": "m3", "name": "M3"}]}
            ]}
            """.trimIndent(),
        )

        val refs = parser.parseCatalog(raw).refs()
        assertEquals(
            listOf("a" to "m1", "a" to "m2", "b" to "m3"),
            refs.map { it.first to it.second.id },
        )
    }

    @Test
    fun `groupOf 与 modelOf 按 id 查`() {
        val catalog = parser.parseCatalog(
            parse(
                """
                {"groups": [
                  {"id": "a", "name": "A", "models": [{"id": "m1", "name": "M1"}]},
                  {"id": "b", "name": "B", "models": [{"id": "m2", "name": "M2"}]}
                ]}
                """.trimIndent(),
            ),
        )

        assertEquals("A", catalog.groupOf("a")?.name)
        assertNull(catalog.groupOf("zzz"))
        assertEquals("M1", catalog.modelOf("a", "m1")?.name)
        assertNull(catalog.modelOf("a", "m2"))
        assertNull(catalog.modelOf("zzz", "m1"))
    }

    @Test
    fun `group 缺 name 时退回 id`() {
        val catalog = parser.parseCatalog(
            parse("""{"groups": [{"id": "deepseek", "models": [{"id": "m", "name": "M"}]}]}"""),
        )
        assertEquals("deepseek", catalog.groups.single().name)
    }

    @Test
    fun `efforts 不是数组时视为没有档位`() {
        val catalog = parser.parseCatalog(
            parse(
                """
                {"groups": [{"id": "p", "name": "P", "models": [
                  {"id": "m", "name": "M", "reasoning": "high"},
                  {"id": "n", "name": "N", "reasoning": {"efforts": "high"}}
                ]}]}
                """.trimIndent(),
            ),
        )
        assertTrue(catalog.allModels[0].efforts.isEmpty())
        assertTrue(catalog.allModels[1].efforts.isEmpty())
    }
}
