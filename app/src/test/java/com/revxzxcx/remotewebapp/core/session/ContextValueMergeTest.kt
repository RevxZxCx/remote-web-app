package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 上下文投影累加器（`mergeContextValue`）。
 *
 * ## 为什么这个函数值得单独测
 *
 * 侧栏的实时数字来自 `session/control` 那条流里**逐个投影**的增量帧，每个帧只有一个
 * `key`。要把七段圆环画出来，必须把六个键攒在一个 `JsonObject` 里反复覆盖同一份快照。
 * 这个「攒」的动作有三个很容易写错、但写错了**不会崩**的地方：
 *
 * 1. 把 `contextHeaders`（70 KB）也收进来 → 累加器常驻内存涨到几 MB，而且这串东西
 *    每帧都要被 `StateFlow` 比较一次。
 * 2. 投影卸载时服务端推的是 `null`，实现里当成「值没变」跳过 → 档位判定永远停在高档，
 *    界面继续显示已经不存在的七段。
 * 3. 原地改传入的 `JsonObject` → 返回同一个引用，`StateFlow` 判等之后认为值没变，
 *    整条 UI 更新被吞掉。
 *
 * 三条都不会抛异常、不会编译报错，只会在真机上表现为「数字不动」或「内存慢慢涨」。
 */
class ContextValueMergeTest {

    private fun values(vararg pairs: Pair<String, Int>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `六个上下文键之外的投影一律不吸收`() {
        val current = JsonObject(emptyMap())
        val huge = buildJsonObject { put("blob", "x".repeat(1000)) }

        val merged = mergeContextValue(current, "contextHeaders", huge)

        assertSame("不认识的键必须原样返回同一个对象，连拷贝都不该发生", current, merged)
    }

    @Test
    fun `认识的键会覆盖旧值`() {
        val current = JsonObject(mapOf("contextPressure" to values("pressureTokens" to 100)))

        val merged = mergeContextValue(current, "contextPressure", values("pressureTokens" to 250))

        val pressure = merged["contextPressure"] as JsonObject
        assertEquals(250, (pressure["pressureTokens"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `返回的是新对象而不是原地改`() {
        val current = JsonObject(mapOf("tokenUsage" to values("outputTokens" to 1)))

        val merged = mergeContextValue(current, "tokenUsage", values("outputTokens" to 2))

        assertNotSame("原地改会让 StateFlow 判等后吞掉更新", current, merged)
        assertEquals(
            "入参不能被改，否则调用方手里的旧快照会跟着一起变",
            1,
            ((current["tokenUsage"] as JsonObject)["outputTokens"] as JsonPrimitive).content.toInt(),
        )
    }

    @Test
    fun `服务端推 null 表示投影已卸载，必须删键而不是保留旧值`() {
        val current = JsonObject(
            mapOf(
                "contextTimeline" to values("images" to 3),
                "contextPressure" to values("pressureTokens" to 100),
            ),
        )

        val merged = mergeContextValue(current, "contextTimeline", JsonNull)

        assertEquals("卸载的键必须消失", false, merged.containsKey("contextTimeline"))
        assertEquals("别的键不能被牵连", true, merged.containsKey("contextPressure"))
    }

    @Test
    fun `空值入参同样按卸载处理`() {
        val current = JsonObject(mapOf("costUsage" to values("cost" to 4)))

        val merged = mergeContextValue(current, "costUsage", null)

        assertEquals(false, merged.containsKey("costUsage"))
    }

    @Test
    fun `删掉不存在的键不会抛异常也不会凭空造键`() {
        val current = JsonObject(mapOf("sessionStats" to values("turns" to 5)))

        val merged = mergeContextValue(current, "contextActivity", JsonNull)

        assertEquals(setOf("sessionStats"), merged.keys)
    }

    @Test
    fun `六个键逐个累加之后能凑出完整快照`() {
        var acc: JsonObject = JsonObject(emptyMap())
        // DETAILED 档位的构成来自 `contextTimeline.current` 而不是 `contextBreakdown`，
        // 所以样本里的 timeline 必须带 current——只塞 images 会让 system 段取到 0。
        val timeline = buildJsonObject {
            put("images", 180)
            put(
                "current",
                buildJsonObject {
                    put("system", 3921)
                    put("tools", 16296)
                    put("assistant", 2_202_493)
                    put("tool", 1_520_351)
                    put("total", 3_869_342)
                },
            )
            put(
                "last",
                buildJsonObject {
                    put("total", 3_868_934)
                    put("prompt", 172_744)
                },
            )
        }
        val incoming = mapOf<String, kotlinx.serialization.json.JsonElement>(
            ContextInsightsParser.KEY_TIMELINE to timeline,
            ContextInsightsParser.KEY_BREAKDOWN to values("messageTokens" to 3_836_505),
            ContextInsightsParser.KEY_TOKEN_USAGE to values("outputTokens" to 2_946_867),
            ContextInsightsParser.KEY_COST to values("input" to 9_399_316),
            ContextInsightsParser.KEY_PRESSURE to values("pressureTokens" to 153_988),
            ContextInsightsParser.KEY_SESSION_STATS to values("turns" to 51),
        )
        incoming.forEach { (key, value) -> acc = mergeContextValue(acc, key, value) }

        assertEquals(6, acc.size)
        val parsed = ContextInsightsParser.parse(acc)
        assertEquals("六个键齐了就应该解析出完整快照", true, parsed != null)
        assertEquals(180, parsed!!.images)
        assertEquals(3921L, parsed.breakdown.system)
        assertEquals("有 timeline 时档位必须是 DETAILED", ContextDetailLevel.DETAILED, parsed.detailLevel)
        assertEquals("本轮占用取 last.prompt", 172_744L, parsed.usedTokens)
        assertEquals(2_946_867L, parsed.usage.output)
        assertEquals(153_988L, parsed.pressure)
    }

    // ── 全量读重建 / 增量帧补放（R040 审查 P1 + P2）────────────────────────

    @Test
    fun `全量读缺席的键必须消失而不能沿用旧值`() {
        // 插件在运行期被卸载时，服务端只是**不再给出**那几个键，不会补 null。
        // 沿用旧累加器会让档位永远停在 DETAILED——这是审查抓到的 P1。
        val stale = mergeContextValue(
            JsonObject(emptyMap()),
            ContextInsightsParser.KEY_TIMELINE,
            buildJsonObject { put("total", 10) },
        )
        val rebuilt = rebuildContextValues(
            buildJsonObject {
                put(ContextInsightsParser.KEY_BREAKDOWN, buildJsonObject { put("messageTokens", 5) })
                // 故意不给 KEY_TIMELINE：插件卸载了
            },
        )
        assertNotSame(stale, rebuilt)
        assertEquals("缺席的键必须从快照里消失", 1, rebuilt.size)
        assertEquals(false, ContextInsightsParser.KEY_TIMELINE in rebuilt)
        assertEquals(true, ContextInsightsParser.KEY_BREAKDOWN in rebuilt)
    }

    @Test
    fun `全量读重建只保留六个已知键`() {
        // contextHeaders 是 70 KB 的大家伙，绝不能因为「顺手都在 values 里」就连带存下来。
        val rebuilt = rebuildContextValues(
            buildJsonObject {
                put(ContextInsightsParser.KEY_PRESSURE, buildJsonObject { put("pressureTokens", 1) })
                put("contextHeaders", "x".repeat(1024))
                put("unrelatedProjection", buildJsonObject { })
            },
        )
        assertEquals("只认六个键", 1, rebuilt.size)
        assertEquals(false, "contextHeaders" in rebuilt)
    }

    @Test
    fun `刷新期间攒下的增量帧要盖在快照之上`() {
        val snapshot = buildJsonObject {
            put(ContextInsightsParser.KEY_PRESSURE, buildJsonObject { put("pressureTokens", 100) })
            put(ContextInsightsParser.KEY_COST, buildJsonObject { put("input", 7) })
        }
        val pending = buildJsonObject {
            put(ContextInsightsParser.KEY_PRESSURE, buildJsonObject { put("pressureTokens", 250) })
        }
        val merged = replayPendingContext(snapshot, pending)
        assertEquals("后到的增量必须赢", 250, (merged[ContextInsightsParser.KEY_PRESSURE] as JsonObject)["pressureTokens"]!!.let { (it as JsonPrimitive).content.toInt() })
        assertEquals("没被覆盖的键保留快照值", 7, (merged[ContextInsightsParser.KEY_COST] as JsonObject)["input"]!!.let { (it as JsonPrimitive).content.toInt() })
    }

    @Test
    fun `没有攒下的帧时补放是恒等变换`() {
        val snapshot = buildJsonObject {
            put(ContextInsightsParser.KEY_PRESSURE, buildJsonObject { put("pressureTokens", 100) })
        }
        assertSame(snapshot, replayPendingContext(snapshot, JsonObject(emptyMap())))
    }

    @Test
    fun `攒下的帧里出现 null 表示这个键没了`() {
        val snapshot = buildJsonObject {
            put(ContextInsightsParser.KEY_PRESSURE, buildJsonObject { put("pressureTokens", 100) })
        }
        val merged = replayPendingContext(
            snapshot,
            buildJsonObject { put(ContextInsightsParser.KEY_PRESSURE, JsonNull) },
        )
        assertEquals("null 帧就是卸载该键", 0, merged.size)
    }
}
