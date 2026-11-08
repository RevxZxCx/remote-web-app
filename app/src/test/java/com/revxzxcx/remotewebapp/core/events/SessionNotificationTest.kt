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


    @Test
    fun `账号授权失效要认出来 它没有负载`() {
        // asar 实证：`this.ctx.emit("deepseek-account/session-expired")` —— 一条 args 都没有。
        // 若解析层要求 args 非空，这条会被默默丢掉，而它代表「所有任务都会开始失败」。
        assertEquals(
            SessionNotification.AccountExpired,
            SessionNotification.parse("deepseek-account/session-expired", JsonArray(emptyList())),
        )
    }

    @Test
    fun `模型要重新登录要认出来`() {
        assertEquals(
            SessionNotification.AccountSignIn,
            SessionNotification.parse("deepseek-account/model-sign-in-required", JsonArray(emptyList())),
        )
    }

    @Test
    fun `凭据更新要认出来 它是账号问题的复位信号`() {
        // 服务端不会为「登录成功」专门发一条好消息，只能靠这条复位。
        assertEquals(
            SessionNotification.CredentialsChanged,
            SessionNotification.parse("credentials/record-updated", JsonArray(emptyList())),
        )
    }

    @Test
    fun `没见过的账号事件不能被当成凭据更新`() {
        // 复位信号若误认，用户会在凭据仍然失效的情况下被清掉「已提醒」，
        // 下一次真失效还得多响一遍；更糟的是白名单里将来新增的账号事件
        // 会被当成「好了」而把问题压掉。
        assertNull(SessionNotification.parse("deepseek-account/unknown-thing", JsonArray(emptyList())))
    }
}
