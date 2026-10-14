package com.revxzxcx.remotewebapp.ui.common

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * 用一张**真实的** EXIF 竖拍图验证摆正链路。
 *
 * 为什么值得单独立一个文件：`exifMatrix` 的八种映射是纯算术，可以用向量单测钉住；
 * 但「ExifInterface 到底能不能从这些字节里读出方向」是**平台/库行为**，只有拿真实文件跑一遍才算数。
 * 这个资源文件由 Pillow 写成（脚本见仓库外的 `_mkexif.py`，480x640 竖图 + Orientation=6），
 * 与手机相机产物的字节结构同构。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExifOrientationResourceTest {

    private fun bytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/exif_portrait_orientation6.jpg")) {
            "测试资源缺失：exif_portrait_orientation6.jpg"
        }.use { it.readBytes() }

    @Test
    fun `真实竖拍图的 EXIF 能被读出来`() {
        val exif = ExifInterface(ByteArrayInputStream(bytes()))
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun `真实竖拍图解码后转成横图`() {
        val source = decodeScaled(bytes()) ?: error("带 EXIF 的真实 JPEG 必须解得出来")
        // 原图 480x640（竖）。Orientation=6 = 顺时针旋转 90°，摆正后应变成 640x480（横）。
        // 注：Robolectric 的 ShadowBitmapFactory 对尺寸的处理与真机不完全一致，
        // 所以这里只断言「宽高互换」这个旋转必然成立的性质，不断言具体数值。
        val matrix = exifMatrix(ExifInterface.ORIENTATION_ROTATE_90)
            ?: error("Orientation=6 必须给出旋转矩阵")
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        assertEquals(source.height, rotated.width)
        assertEquals(source.width, rotated.height)
    }

    @Test
    fun `没有方向的图不会被误转`() {
        // 方向字段缺席时矩阵必须是 null，宽高保持原样。
        assertNull(exifMatrix(ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(exifMatrix(ExifInterface.ORIENTATION_NORMAL))
        assertTrue("资源文件本身应该带 EXIF（否则前两条断言没有意义）", bytes().size > 1000)
    }

    @Test
    fun `八种方向的矩阵都能作用而不抛异常`() {
        val bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888)
        // 1-4 是纯翻转/旋转 180°，宽高不变；5-8 含 90°/270° 转置，宽高互换。
        val swapped = setOf(5, 6, 7, 8)
        for (orientation in 1..8) {
            val matrix = exifMatrix(orientation) ?: continue
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (orientation in swapped) {
                assertEquals("orientation=$orientation 应互换宽高", bitmap.height, out.width)
                assertEquals("orientation=$orientation 应互换宽高", bitmap.width, out.height)
            } else {
                assertEquals("orientation=$orientation 宽高不变", bitmap.width, out.width)
                assertEquals("orientation=$orientation 宽高不变", bitmap.height, out.height)
            }
        }
    }
}
