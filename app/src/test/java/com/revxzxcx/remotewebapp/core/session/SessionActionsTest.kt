package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档 / 置顶四个动作的 wire 参数形状。
 *
 * 守的是 Typert 的严格 schema：多一个键或少一个键都直接
 * `gateway/arguments-invalid`，而这类错误只有在真机上点了长按菜单才看得见。
 * 依据 `dsh-api-workspace-controller/lib/typert.host.js` 的 descriptor：
 * 四个方法的参数 wire 名**都是 `request`**（源码签名 `async archiveSession(request)`），
 * schema 分别是 `{sessionId, stopActivity?}` / `{sessionId}` / `{sessionId}` / `{sessionId}`。
 */
class SessionActionsTest {

    private fun body(args: kotlinx.serialization.json.JsonElement): JsonObject {
        val obj = args as JsonObject
        // 必须是 `{request: {...}}` 一层包裹，不能把字段直接摊在顶层。
        assertEquals(setOf("request"), obj.keys)
        return obj["request"] as JsonObject
    }

    @Test
    fun `archive 默认带上 stopActivity`() {
        val request = body(SessionActions.archive("s-1"))
        assertEquals(setOf("sessionId", "stopActivity"), request.keys)
        assertEquals("s-1", request["sessionId"]?.jsonPrimitive?.content)
        assertTrue(request["stopActivity"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `archive 可以显式不叫停活动`() {
        val request = body(SessionActions.archive("s-1", stopActivity = false))
        // 服务端只认 `=== true`（`index.js:299`），传 false 与不传**等价**。我们选择
        // **不传**：少一个键就少一个被服务端 schema 拒绝的机会，也让「调用方到底想
        // 不想叫停」在 wire 上一眼可见。所以这里断言键不存在，而不是存在且为 false。
        assertEquals(setOf("sessionId"), request.keys)
        assertFalse(request["stopActivity"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `unarchive 只有 sessionId 一个键`() {
        val request = body(SessionActions.unarchive("s-2"))
        assertEquals(setOf("sessionId"), request.keys)
        assertEquals("s-2", request["sessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `pin 与 unpin 都只有 sessionId 一个键`() {
        assertEquals(setOf("sessionId"), body(SessionActions.pin("s-3")).keys)
        assertEquals(setOf("sessionId"), body(SessionActions.unpin("s-3")).keys)
        assertEquals("s-3", body(SessionActions.pin("s-3"))["sessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `四个动作都不会漏掉 request 包裹`() {
        // `session/list` 的 `_request` 教训（M1 实测）说明 wire 名只能从 descriptor 拿，
        // 不能凭印象写。这里把四个方法一起钉住，将来新增第五个动作时也会被提醒。
        listOf(
            SessionActions.archive("x"),
            SessionActions.unarchive("x"),
            SessionActions.pin("x"),
            SessionActions.unpin("x"),
        ).forEach { args ->
            val obj = args as JsonObject
            assertTrue("缺少 request 包裹: ${obj.keys}", obj.containsKey("request"))
            assertNull("request 不该是可空占位", obj["request"] as? JsonPrimitive)
        }
    }
}
