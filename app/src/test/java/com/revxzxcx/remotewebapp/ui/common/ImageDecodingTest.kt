package com.revxzxcx.remotewebapp.ui.common

import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [decodeScaled] / [sampleSizeFor] / [exifMatrix] 的回归测试（R007d P1-1 / P2-2 / P3-4）。
 *
 * 放在 Robolectric 里跑：`Matrix` 与 `BitmapFactory` 在纯 JVM 单测下是
 * `Method not mocked`，而下采样与 EXIF 摆正都只有端到端才能证明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageDecodingTest {

    // ---------- sampleSizeFor：像素预算（R007d P1-1） ----------

    @Test
    fun `宽和高都在预算内时不降采样`() {
        assertEquals(1, sampleSizeFor(1000, 800, maxWidth = 1440, maxPixels = 8_000_000L))
    }

    @Test
    fun `宽度超限时按宽度降采样`() {
        // 3000/2 = 1500 仍超 1440，所以要降到 4（sample 是 2 的幂，不能取 3）。
        assertEquals(4, sampleSizeFor(3000, 800, maxWidth = 1440, maxPixels = 8_000_000L))
        assertEquals(8, sampleSizeFor(6000, 800, maxWidth = 1440, maxPixels = 8_000_000L))
    }

    @Test
    fun `宽度合格但极高的图也按面积降采样`() {
        // R007d P1-1 的原始案例：1440×20000 宽合格（sample=1 时宽度判定通过）却要 28.8 MP
        // ≈ 115 MB。修好之后 sample 至少是 2（720×10000 = 7.2 MP 进预算）。
        val sample = sampleSizeFor(1440, 20_000, maxWidth = 1440, maxPixels = 8_000_000L)
        assertTrue("应因面积而降采样，实际 sample=$sample", sample >= 2)
        assertTrue(
            "降采样后面积必须进预算",
            (1440L / sample) * (20_000L / sample) <= 8_000_000L,
        )
    }

    @Test
    fun `降采样后像素数始终不超预算`() {
        // 遍历一批真实世界会出现的尺寸，断言不变量而不是逐个记期望值。
        val sizes = listOf(
            4000 to 3000, 8000 to 6000, 1440 to 20_000, 1080 to 10_000,
            20000 to 100, 100 to 20000, 64 to 64, 1 to 1, 12000 to 12000,
        )
        for ((w, h) in sizes) {
            val sample = sampleSizeFor(w, h, maxWidth = 1440, maxPixels = 8_000_000L)
            assertTrue("sample 必须是 2 的正幂：$sample", sample > 0 && (sample and (sample - 1)) == 0)
            assertTrue("sample 必须有上界：$sample", sample <= 64)
            assertTrue(
                "($w,$h) sample=$sample 降采样后面积仍超预算",
                (w.toLong() / sample) * (h.toLong() / sample) <= 8_000_000L,
            )
        }
    }

    @Test
    fun `超大图会收敛到上界而不是死循环`() {
        val sample = sampleSizeFor(100_000, 100_000, maxWidth = 1440, maxPixels = 8_000_000L)
        assertEquals(64, sample)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxWidth 非正数直接拒绝而不是死循环`() {
        // R007d P3-4：maxWidth=0 时 width/(sample*2) >= 0 恒真，sample 翻倍到 Int 溢出。
        sampleSizeFor(100, 100, maxWidth = 0, maxPixels = 8_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxPixels 非正数直接拒绝`() {
        sampleSizeFor(100, 100, maxWidth = 1440, maxPixels = 0L)
    }

    // ---------- exifMatrix：方向映射（R007d P2-2） ----------

    @Test
    fun `常规与未定义方向不需要变换`() {
        assertNull(exifMatrix(ExifInterface.ORIENTATION_NORMAL))
        assertNull(exifMatrix(ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(exifMatrix(999))
    }

    @Test
    fun `三个旋转角各自映射`() {
        // 基准向量 (1,0)：旋转 90° → (0,1)；180° → (-1,0)；270° → (0,-1)。
        assertVector(0f, 1f, exifMatrix(ExifInterface.ORIENTATION_ROTATE_90))
        assertVector(-1f, 0f, exifMatrix(ExifInterface.ORIENTATION_ROTATE_180))
        assertVector(0f, -1f, exifMatrix(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `水平与垂直翻转各自映射`() {
        assertVector(-1f, 0f, exifMatrix(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertVector(1f, 0f, exifMatrix(ExifInterface.ORIENTATION_FLIP_VERTICAL))
    }

    @Test
    fun `转置是旋转加翻转两步`() {
        // TRANSPOSE = 沿主对角线翻转 = 旋转 90° + 水平翻转。
        assertVector(0f, 1f, exifMatrix(ExifInterface.ORIENTATION_TRANSPOSE))
    }

    private fun assertVector(expectedX: Float, expectedY: Float, matrix: Matrix?) {
        assertNotNull("期望有变换矩阵", matrix)
        val values = floatArrayOf(1f, 0f)
        matrix!!.mapVectors(values)
        assertEquals(expectedX, values[0], 0.001f)
        assertEquals(expectedY, values[1], 0.001f)
    }

    // ---------- decodeScaled：端到端 ----------

    @Test
    fun `真实 PNG 按预算解码且宽高都变小`() {
        // 3000×2400 = 7.2 MP 在 8 MP 预算内，但宽度超 1440 → sample=4 → 750×600。
        val bitmap = decodeScaled(solidPng(3000, 2400))!!
        assertEquals(750, bitmap.width)
        assertEquals(600, bitmap.height)
    }

    @Test
    fun `解码结果不是原图尺寸`() {
        val bitmap = decodeScaled(solidPng(4000, 4000))!!
        assertTrue("宽度应被压到 1440 以下，实际 ${bitmap.width}", bitmap.width <= 1440)
        assertTrue(bitmap.width * bitmap.height <= MAX_DECODED_PIXELS)
    }

    @Test
    fun `空字节与非图片字节都不会抛异常`() {
        // 真机上 BitmapFactory 对这两种输入都返回 null；Robolectric 的 ShadowBitmapFactory
        // 更宽松（会造一张默认尺寸的位图），所以只能断言「不抛」这个所有平台一致的契约。
        // 生产代码的 `?: return null` 与调用方的 runCatching 才是真正的兜底。
        assertTrue(runCatching { decodeScaled(ByteArray(0)) }.isSuccess)
        assertTrue(runCatching { decodeScaled("这不是图片".toByteArray()) }.isSuccess)
    }

    @Test
    fun `小图原样解码`() {
        val bitmap = decodeScaled(solidPng(10, 8))!!
        assertEquals(10, bitmap.width)
        assertEquals(8, bitmap.height)
    }

    @Test
    fun `解码出来的位图是可用的`() {
        val bitmap = decodeScaled(solidPng(64, 64))
        assertNotNull(bitmap)
        assertFalse(bitmap!!.isRecycled)
    }

    // ---------- 测试基建 ----------

    /**
     * 造一张 [width]×[height] 的纯色 PNG（colorType 2 / RGB，每行一个 filter 字节）。
     *
     * 不引第三方库：PNG 结构固定，手写比加一个 testImplementation 便宜。
     */
    private fun solidPng(width: Int, height: Int): ByteArray {
        val rowBytes = 1 + width * 3
        val raw = ByteArray(height * rowBytes)
        for (row in 0 until height) {
            val base = row * rowBytes
            raw[base] = 0 // filter: none
            for (col in 0 until width) {
                val p = base + 1 + col * 3
                raw[p] = 0x40
                raw[p + 1] = 0x60.toByte()
                raw[p + 2] = 0x80.toByte()
            }
        }
        val deflater = java.util.zip.Deflater(9)
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(16_384)
        val compressed = java.io.ByteArrayOutputStream(raw.size)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            compressed.write(buffer, 0, n)
        }
        deflater.end()

        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", ihdr(width, height))
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(out, data.size)
        out.write(typeBytes)
        out.write(data)
        writeInt(out, crc32(typeBytes + data))
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = -0x1 // 0xFFFFFFFF
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc xor -0x1
    }

    private fun writeInt(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeInt(out, width)
        writeInt(out, height)
        out.write(8) // bit depth
        out.write(2) // color type: truecolor RGB
        out.write(0) // compression method
        out.write(0) // filter method
        out.write(0) // interlace method
        return out.toByteArray()
    }
}
