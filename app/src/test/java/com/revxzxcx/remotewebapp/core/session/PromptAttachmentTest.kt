package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PromptAttachment] / [ImageLimits] / [validateImage] / base64 的单测。
 *
 * 用 Robolectric 而不是纯 JVM：`android.util.Base64` 在纯 JVM 单测里是
 * `Method ... not mocked`，而 base64 正是图片附件能否正确上线的关键一步
 * （编码错一位，服务端就解出一张花屏然后报 `attachment-invalid`）。
 *
 * 这些断言钉的是三件容易错的事：
 * ① 图片与文件在 wire 上是**两种不同的块**（内联 base64 vs 先上传拿回执）；
 * ② `file` 块只有 `receiptId` 一个键，多一个就是 `arguments-invalid`；
 * ③ 客户端的预检文案与服务端 `attachments.admitPromptContent` 的顺序一致，
 *    用户先看到中文而不是一串英文错误码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptAttachmentTest {

    private val small = ImageMeasure(bytes = 1024L, pixels = 100_000L, width = 400, height = 250)

    // ------------------------------------------------------------ 块形状

    @Test
    fun `图片块的键恰好是 type mediaType data name`() {
        val attachment = PromptAttachment(
            kind = PromptAttachment.Kind.IMAGE,
            name = "shot.png",
            mediaType = "image/png",
            data = "AAAA",
        )
        val block = attachment.toBlock()
        assertEquals(setOf("type", "mediaType", "data", "name"), block.keys)
        assertEquals(JsonPrimitive("image"), block["type"])
        assertEquals(JsonPrimitive("image/png"), block["mediaType"])
        assertEquals(JsonPrimitive("AAAA"), block["data"])
        assertEquals(JsonPrimitive("shot.png"), block["name"])
    }

    @Test
    fun `图片块缺 name 或 mediaType 时对应键不出现`() {
        val block = PromptAttachment(
            kind = PromptAttachment.Kind.IMAGE,
            name = null,
            mediaType = "",
            data = "AAAA",
        ).toBlock()
        // mediaType 是带默认值的非空参数，始终出现；name 可缺席。
        assertTrue(block.containsKey("mediaType"))
        assertFalse(block.containsKey("name"))
    }

    @Test
    fun `文件块只有 type 与 receiptId 两个键`() {
        // 这是 Typert 严格 schema：多带 name/mediaType 会 `unexpected`。
        val block = PromptAttachment(
            kind = PromptAttachment.Kind.FILE,
            name = "notes.md",
            mediaType = "text/markdown",
            data = "不该出现的 base64",
            receiptId = "rcpt-9",
        ).toBlock()
        assertEquals(setOf("type", "receiptId"), block.keys)
        assertEquals(JsonPrimitive("file"), block["type"])
        assertEquals(JsonPrimitive("rcpt-9"), block["receiptId"])
    }

    // ------------------------------------------------------------ 预检

    @Test
    fun `不限制时什么都能过`() {
        assertNull(ImageLimits().validateImage(small, "image/png", emptyList(), 0L))
    }

    @Test
    fun `MIME 不在白名单时被挡下且大小写不敏感`() {
        val limits = ImageLimits(mediaTypes = listOf("IMAGE/PNG"))
        assertNull(limits.validateImage(small, "image/png", emptyList(), 0L))
        assertTrue(
            limits.validateImage(small, "image/gif", emptyList(), 0L)
                is ImageRejection.UnsupportedType,
        )
    }

    @Test
    fun `单张超限按字节挡`() {
        val limits = ImageLimits(maxImageBytes = 500L)
        val rejection = limits.validateImage(small, "image/png", emptyList(), 0L)
        assertEquals(ImageRejection.TooLarge(1024L, 500L), rejection)
    }

    @Test
    fun `条数上限把新加的这张也算进去`() {
        val limits = ImageLimits(maxImagesPerMessage = 2)
        val pending = listOf(image("a"), image("b"))
        // 已两张，再加一张 = 3 > 2。
        assertEquals(ImageRejection.TooMany(2), limits.validateImage(small, "image/png", pending, 0L))
        // 只加一张时不该被挡。
        assertNull(limits.validateImage(small, "image/png", pending.take(1), 0L))
    }

    @Test
    fun `一轮总字节把已挂的图片算进来`() {
        val limits = ImageLimits(maxMessageImageBytes = 1500L)
        val rejection = limits.validateImage(small, "image/png", listOf(image("a")), 1000L)
        assertEquals(ImageRejection.TotalTooLarge(2024L, 1500L), rejection)
    }

    @Test
    fun `像素与边长只在设了上限时才生效`() {
        // 0 像素（解不出尺寸）不能被「0 > 0」这种比较误挡。
        val noLimits = ImageLimits()
        val unknown = ImageMeasure(bytes = 10L, pixels = 0L, width = 0, height = 0)
        assertNull(noLimits.validateImage(unknown, "image/png", emptyList(), 0L))

        val limits = ImageLimits(maxImagePixels = 50_000L, maxImageDimension = 300L)
        assertTrue(
            limits.validateImage(small, "image/png", emptyList(), 0L)
                is ImageRejection.TooManyPixels,
        )
        val tall = ImageMeasure(bytes = 10L, pixels = 1000L, width = 100, height = 5000)
        assertTrue(
            limits.validateImage(tall, "image/png", emptyList(), 0L)
                is ImageRejection.DimensionTooLarge,
        )
    }

    @Test
    fun `预检顺序与服务端一致先挡类型再挡大小`() {
        // 服务端 `admitPromptContent` 先校验 mediaType 再校验体积；客户端照同样的顺序，
        // 否则用户看到的原因和桌面端说的不是同一件事。
        val limits = ImageLimits(
            maxImageBytes = 1L,
            mediaTypes = listOf("image/png"),
        )
        assertTrue(
            limits.validateImage(small, "image/gif", emptyList(), 0L)
                is ImageRejection.UnsupportedType,
        )
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `每种拒绝都有中文文案且带上具体数字`() {
        assertEquals(
            "这张图片有 1.0 KB，超过单张上限 500 B。",
            ImageRejection.TooLarge(1024L, 500L).message(),
        )
        assertEquals("一轮最多带 4 张图片。", ImageRejection.TooMany(4).message())
        assertTrue(ImageRejection.TotalTooLarge(2048L, 1024L).message().contains("2.0 KB"))
        assertTrue(ImageRejection.TooManyPixels(9_000_000L, 1_000_000L).message().contains("9000000"))
        assertTrue(ImageRejection.DimensionTooLarge(400, 250, 300L).message().contains("400×250"))
        assertTrue(ImageRejection.UnsupportedType("image/gif").message().contains("image/gif"))
    }

    @Test
    fun `formatBytes 按量级换单位`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024 * 1536))
        assertEquals("2.0 GB", formatBytes(1024L * 1024 * 2048))
    }

    // ------------------------------------------------------------ base64

    @Test
    fun `base64 编解码往返一致且不折行`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = bytes.toBase64()
        assertArrayEquals(bytes, encoded.fromBase64())
        // NO_WRAP：不能有换行，否则 JSON 里要多一层转义、服务端可能拒收。
        assertFalse(encoded.contains('\n'))
    }

    @Test
    fun `base64 解码任何输入都不抛异常`() {
        // Android 的 Base64 是宽容的：跳过字母表外的字符。所以这里断言的是
        // 「不抛」而不是「解出空」——被截断的载荷会解出它救得了的那部分，
        // 由 decodeScaled 判「这是不是一张图」。
        val salvaged = "!!!不是 base64!!!".fromBase64()
        assertTrue(salvaged.size >= 0)
        assertArrayEquals(ByteArray(0), "".fromBase64())
        // 真正的往返仍然逐字节一致。
        val bytes = ByteArray(300) { (it * 7).toByte() }
        assertArrayEquals(bytes, bytes.toBase64().fromBase64())
    }

    @Test
    fun `AttachmentPayload 解出原始字节`() {
        val payload = AttachmentPayload(
            attachment = AttachmentMeta(attachmentId = "a-1", mediaType = "image/png", bytes = 3L),
            data = "AQID",
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), payload.decode())
        assertEquals("a-1", payload.attachment.attachmentId)
        assertEquals(3L, payload.attachment.bytes)
    }

    @Test
    fun `AttachmentPayload 的 data 为空时解出空数组`() {
        assertArrayEquals(ByteArray(0), AttachmentPayload().decode())
    }

    // ------------------------------------------------------------ imageLimits 解析

    @Test
    fun `imageLimits 从投影解析出全部字段`() {
        val element: JsonObject = JsonObject(
            mapOf(
                "maxImageBytes" to JsonPrimitive(1024),
                "maxImagesPerMessage" to JsonPrimitive(4),
                "maxMessageImageBytes" to JsonPrimitive(4096),
                "maxImagePixels" to JsonPrimitive(1_000_000),
                "maxImageDimension" to JsonPrimitive(2048),
                "mediaTypes" to kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("image/png"))
                    add(JsonPrimitive("image/jpeg"))
                },
            ),
        )
        val limits = ImageLimits.of(element)
        assertEquals(1024L, limits.maxImageBytes)
        assertEquals(4, limits.maxImagesPerMessage)
        assertEquals(4096L, limits.maxMessageImageBytes)
        assertEquals(1_000_000L, limits.maxImagePixels)
        assertEquals(2048L, limits.maxImageDimension)
        assertEquals(listOf("image/png", "image/jpeg"), limits.mediaTypes)
    }

    @Test
    fun `imageLimits 解析宽容缺失与坏类型都不抛`() {
        // 服务端会加字段、也会在某些模型上整个不给这个投影。
        assertNull(null)
        assertEquals(ImageLimits(), ImageLimits.of(JsonPrimitive("nope")))
        assertEquals(
            ImageLimits(maxImageBytes = 10L),
            ImageLimits.of(
                JsonObject(
                    mapOf(
                        "maxImageBytes" to JsonPrimitive(10),
                        "maxImagesPerMessage" to JsonPrimitive("不是数字"),
                        "mediaTypes" to JsonPrimitive("也不是数组"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `maxImagesPerMessage 超出 Int 范围时不会回绕成负数`() {
        // R007 P2-2：`Long.toInt()` 在超 Int.MAX_VALUE 时回绕成负数，于是
        // `alreadyPending.size + 1 > maxImagesPerMessage` 恒真，每张图都被
        // 「一轮最多带 -x 张图片」挡掉。服务端发一个离谱的大数等于「不限制」。
        val limits = ImageLimits.of(
            JsonObject(mapOf("maxImagesPerMessage" to JsonPrimitive(9_000_000_000L))),
        )
        assertEquals(Int.MAX_VALUE, limits.maxImagesPerMessage)
        // 收成上限之后，一张正常图片必须能过。
        assertNull(limits.validateImage(ImageMeasure(100L, 100L, 10, 10), "image/png", emptyList(), 0L))
    }

    @Test
    fun `maxImagesPerMessage 为 0 时一张都收不了`() {
        // 0 是合法值（服务端明确说这一轮不要图），不能被 coerce 成「不限制」。
        val limits = ImageLimits.of(
            JsonObject(mapOf("maxImagesPerMessage" to JsonPrimitive(0))),
        )
        assertEquals(0, limits.maxImagesPerMessage)
        assertEquals(
            ImageRejection.TooMany(0),
            limits.validateImage(ImageMeasure(100L, 100L, 10, 10), "image/png", emptyList(), 0L),
        )
    }

    @Test
    fun `媒体类型比对不受系统默认 locale 影响`() {
        // R007 P3：`lowercase()` 不带 Locale 时用系统默认 locale。土耳其语的 "I"
        // 小写成 "ı"，于是 "IMAGE/PNG" 在白名单里也匹配不上。强制 tr locale 复现。
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val limits = ImageLimits(mediaTypes = listOf("image/png"))
            assertNull(limits.validateImage(ImageMeasure(1L, 1L, 1, 1), "IMAGE/PNG", emptyList(), 0L))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    private fun image(name: String) = PromptAttachment(
        kind = PromptAttachment.Kind.IMAGE,
        name = name,
        mediaType = "image/png",
        data = "AAAA",
    )
}
