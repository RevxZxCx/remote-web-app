package com.revxzxcx.remotewebapp.core.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码**内容**的往返测试。
 *
 * 为什么值得写：M1 起扫码路径（相机权限 + `DecoratedBarcodeView` + `decodeSingle`）
 * 就**从未在设备上验证过**（T007 只验了手输链接）。模拟器没法往摄像头里灌一张图，
 * 所以设备侧只能做「取景器能渲染、不崩」的冒烟测试；但「桌面端生成的 URL 编成二维码后
 * 能不能被同一个解码器读回来、读回来又能不能被 [PairLinkParser] 接住」这一整段是
 * **纯 JVM** 的，完全可以钉死——而它才是扫码路径里真正可能静默失败的部分
 * （比如 URL 里带了大小写混合的 token，或二维码内容被引号包裹）。
 *
 * 解码用的 `MultiFormatReader` + `TRY_HARDER` + `UTF-8` 与
 * `zxing-android-embedded` 的 `DecodeHandler` 是同一套（见其源码
 * `multiFormatReader.setHints(...)`），所以这里的结论对真机解码同样成立。
 */
class PairLinkQrRoundTripTest {

    /** 与 `DecodeHandler` 一致的一组提示：只认二维码、开 TRY_HARDER、按 UTF-8 解。 */
    private fun hints() = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** 编成二维码再解一遍，返回解码出的文本。 */
    private fun roundTrip(text: String, size: Int = 420): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix.get(index % size, index / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(size, size, pixels)
        return MultiFormatReader().apply { setHints(hints()) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    }

    @Test
    fun `桌面端生成的配对链接编成二维码后能解回来并被解析器接住`() {
        // 形状与真实 issue 响应一致（`routes.ts:712` 的 `url` 字段）。
        val url = "https://beverages-auburn-taste-viewpicture.trycloudflare.com/pair-accept?pair=t8a1b2c3d4e5"
        val decoded = roundTrip(url)

        assertEquals(url, decoded)
        val parsed = PairLinkParser.parse(decoded)
        assertTrue("应当解析成功，实际：$parsed", parsed is PairParse.Ok)
        val link = (parsed as PairParse.Ok).link
        assertTrue("期望 Accept，实际：$link", link is PairLink.Accept)
        link as PairLink.Accept
        assertEquals("https://beverages-auburn-taste-viewpicture.trycloudflare.com", link.baseUrl)
        assertEquals("t8a1b2c3d4e5", link.token)
    }

    @Test
    fun `带端口与裸 token 的二维码也能解回来`() {
        val withPort = "http://127.0.0.1:19387/pair-accept?pair=9f8e7d6c5b4a"
        assertEquals(withPort, roundTrip(withPort))
        val parsed = PairLinkParser.parse(roundTrip(withPort)) as PairParse.Ok
        assertEquals(PairLink.Accept("http://127.0.0.1:19387", "9f8e7d6c5b4a"), parsed.link)
    }

    @Test
    fun `base64url 风格的 token 也能解回来`() {
        // `clock.randomToken()` 的字符集不保证是纯十六进制；[PairLinkParser.BARE_TOKEN]
        // 同时放行 `-` 与 `_`，所以这条必须成立，否则长 token 会被判成「不是合法链接」。
        val token = "aB3xY9_-zQ7kL2mN"
        val url = "https://host.example.com/pair-accept?pair=$token"
        assertEquals(token, (PairLinkParser.parse(roundTrip(url)) as PairParse.Ok).let { (it.link as PairLink.Accept).token })
    }

    @Test
    fun `浏览器专用的 grant 链接会被拒绝而不是误当成设备链接`() {
        val grantUrl = "https://host.example.com/pair-app?grant=onetimegrant"
        val decoded = roundTrip(grantUrl)
        val parsed = PairLinkParser.parse(decoded)
        assertTrue("grant 链接必须被拒绝，实际：$parsed", parsed is PairParse.Failed)
        assertTrue(
            "拒绝原因应当提示重新生成二维码",
            (parsed as PairParse.Failed).reason.contains("重新生成"),
        )
    }

    @Test
    fun `解码出的文本被引号或空白包裹时仍能解析`() {
        // 某些扫码实现会在回调里带上额外空白；`clean()` 就是为这个存在的。
        val url = "https://host.example.com/pair-accept?pair=deadbeef"
        assertEquals(
            PairLink.Accept("https://host.example.com", "deadbeef"),
            (PairLinkParser.parse("  \"$url\"\n") as PairParse.Ok).link,
        )
        // 二维码本身不带引号——这里只是确认 clean() 的容错，不走编码往返。
        assertEquals(url, roundTrip(url))
    }
}
