package com.revxzxcx.remotewebapp.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairLinkParser] 的单测。
 *
 * 覆盖真实链路：桌面端 `POST /api/pair/issue` 返回的 `url` 字段就是
 * `<base>/pair-accept?pair=<token>`（`src/routes.ts:712`），二维码内容即该 URL；
 * 以及插件 `/pair-app?device=<id>` 形式（`src/remote-methods.ts:61-62`）。
 */
class PairLinkParserTest {

    /** 32 位十六进制，与 `clock.randomToken()` 的实际形状一致。 */
    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun parsesPairAcceptUrlWithTunnelOrigin() {
        val parsed = PairLinkParser.parse("https://5b4551427f0ab7aa.dsh-market.com/pair-accept?pair=$token")
        val link = (parsed as PairParse.Ok).link as PairLink.Accept
        assertEquals("https://5b4551427f0ab7aa.dsh-market.com", link.baseUrl)
        assertEquals(token, link.token)
    }

    @Test
    fun parsesPairAcceptUrlWithLanOriginAndPort() {
        val parsed = PairLinkParser.parse("http://192.168.1.5:19387/pair-accept?pair=$token")
        val link = (parsed as PairParse.Ok).link as PairLink.Accept
        assertEquals("http://192.168.1.5:19387", link.baseUrl)
        assertEquals(token, link.token)
    }

    @Test
    fun parsesPairAppDeviceUrl() {
        val parsed = PairLinkParser.parse("http://192.168.1.5:19387/pair-app?device=dev-9")
        val link = (parsed as PairParse.Ok).link as PairLink.Device
        assertEquals("http://192.168.1.5:19387", link.baseUrl)
        assertEquals("dev-9", link.deviceId)
    }

    @Test
    fun rejectsBrowserOnlyGrantLink() {
        val parsed = PairLinkParser.parse("http://192.168.1.5:19387/pair-app?grant=g-1")
        val failed = parsed as PairParse.Failed
        assertTrue(failed.reason.contains("一次性"))
    }

    @Test
    fun treatsBareTokenAsTokenWithoutOrigin() {
        val parsed = PairLinkParser.parse(token)
        val link = (parsed as PairParse.Ok).link as PairLink.BareToken
        assertEquals(token, link.token)
    }

    @Test
    fun toleratesQuotesAndWhitespaceAroundQrPayload() {
        val parsed = PairLinkParser.parse("  \"http://192.168.1.5:19387/pair-accept?pair=$token\"  ")
        val link = (parsed as PairParse.Ok).link as PairLink.Accept
        assertEquals(token, link.token)
    }

    @Test
    fun rejectsEmptyInput() {
        assertTrue(PairLinkParser.parse("   ") is PairParse.Failed)
    }

    @Test
    fun rejectsNonHttpScheme() {
        val failed = PairLinkParser.parse("ftp://192.168.1.5/pair-accept?pair=$token") as PairParse.Failed
        assertTrue(failed.reason.contains("http"))
    }

    @Test
    fun rejectsPairAcceptWithoutToken() {
        val failed = PairLinkParser.parse("http://192.168.1.5:19387/pair-accept") as PairParse.Failed
        assertTrue(failed.reason.contains("pair"))
    }

    @Test
    fun rejectsUnrecognizedPath() {
        assertTrue(PairLinkParser.parse("http://192.168.1.5:19387/somewhere") is PairParse.Failed)
    }

    @Test
    fun normalizesHostPortWithoutScheme() {
        assertEquals(
            "http://192.168.1.5:19387",
            PairLinkParser.normalizeBaseUrl("192.168.1.5:19387"),
        )
    }

    @Test
    fun normalizeKeepsExplicitHttpsAndDropsPath() {
        assertEquals(
            "https://5b4551427f0ab7aa.dsh-market.com",
            PairLinkParser.normalizeBaseUrl("https://5b4551427f0ab7aa.dsh-market.com/pair-accept?pair=x"),
        )
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertEquals(null, PairLinkParser.normalizeBaseUrl(""))
    }
}
