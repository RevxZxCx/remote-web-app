package com.revxzxcx.remotewebapp.core.events

import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `api-session/…` 的 `emit` 帧解析测试。
 *
 * 真实帧形状（`$events` 探针实测）：
 * ```
 * {"type":"emit","event":"api-session/added","args":[{…完整 summary…}]}
 * {"type":"emit","event":"api-session/status","args":["session-x",false]}
 * {"type":"emit","event":"api-session/activity","args":["session-x",1792482015091]}
 * {"type":"emit","event":"api-session/removed","args":["session-x"]}
 * ```
 * **`args` 是数组**——这条曾经被写成对象（`Emit(val args: JsonObject?)`）并一直没被
 * 构造过，属于典型「照着自己想象的样子对齐 waterfall 的 request」。
 */
class SessionNotificationTest {

    private fun args(json: String) = DshJson.parseToJsonElement(json) as JsonArray

    @Test
    fun addedCarriesWholeSummaryObject() {
        val parsed = SessionNotification.parse(
            "api-session/added",
            args("""[{"sessionId":"session-1","updatedAt":123,"running":false,"blank":false}]"""),
        )

        val added = parsed as SessionNotification.Added
        assertEquals("session-1", added.summary["sessionId"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun statusCarriesIdAndRunning() {
        val parsed = SessionNotification.parse("api-session/status", args("""["session-1",true]"""))

        assertEquals(SessionNotification.Status("session-1", true), parsed)
    }

    @Test
    fun statusRejectsNonBooleanSecondArgument() {
        assertNull(SessionNotification.parse("api-session/status", args("""["session-1","running"]""")))
    }

    @Test
    fun statusWithoutSecondArgumentIsIgnored() {
        assertNull(SessionNotification.parse("api-session/status", args("""["session-1"]""")))
    }

    @Test
    fun removedCarriesSessionId() {
        assertEquals(
            SessionNotification.Removed("session-9"),
            SessionNotification.parse("api-session/removed", args("""["session-9"]""")),
        )
    }

    @Test
    fun activityCarriesTimestamp() {
        assertEquals(
            SessionNotification.Activity("session-9", 1792482015091L),
            SessionNotification.parse("api-session/activity", args("""["session-9",1792482015091]""")),
        )
    }

    @Test
    fun unknownEventIsIgnored() {
        // `$events` 的白名单有 27 条，本 App 只认这几条；其余必须安静地丢掉，
        // 否则每收到一条无关事件（例如 `schedule/changed`）都会触发一次列表刷新。
        assertNull(SessionNotification.parse("schedule/changed", args("""[]""")))
    }

    @Test
    fun nullArgsIsIgnored() {
        assertNull(SessionNotification.parse("api-session/added", null))
    }

    @Test
    fun addedWithNonObjectFirstArgumentIsIgnored() {
        // 服务端若改了形状（比如改成 `[id, summary]`），宁可丢掉这一条，
        // 也不要往列表里塞一条 sessionId 残缺的假会话。
        assertNull(SessionNotification.parse("api-session/added", args("""["session-1"]""")))
    }

    @Test
    fun blankSessionIdIsIgnored() {
        assertNull(SessionNotification.parse("api-session/removed", args("""[""]""")))
    }

    @Test
    fun parsingIsTolerantOfExtraArguments() {
        // Host 侧 `ctx.emit(name, a, b, c)` 多传一个参数不该让解析失败。
        assertEquals(
            SessionNotification.Removed("session-1"),
            SessionNotification.parse("api-session/removed", args("""["session-1","extra"]""")),
        )
    }

}
