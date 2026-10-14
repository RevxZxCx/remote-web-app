package com.revxzxcx.remotewebapp.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface

/**
 * 按目标宽度与**像素预算**下采样解码 [bytes]，并按 EXIF 方向摆正。
 *
 * 之所以从 `FileViewerScreen` 提到这里共用（M5-10）：文件查看器与聊天里的图片附件
 * 都要「按展示尺寸缩小后再解码」，避免把 4000×3000 的原图整张搬进 Java 堆。
 *
 * ## 两个必须同时看的约束
 *
 * **宽度**取 1440：手机上超过这个宽度肉眼已无差别。
 * **像素预算**取 [MAX_DECODED_PIXELS]：只按宽度算会漏掉超高图。一张 1440×20000 的
 * 滚动截图宽度合格（sample = 1）却要 115 MB ARGB_8888，Java 堆上这种量级的单次分配
 * 几乎必然 OOM（本 App 未开 `largeHeap`）。调用方虽然都用 `runCatching` 包着不会硬崩，
 * 但后果是合法图片被折成永久失败态、且进程已被推到堆顶——那是把崩溃转移位置。
 *
 * **EXIF 方向**：手机相机竖拍普遍写 Orientation = 6/8，`decodeByteArray` 只返回原始像素。
 * 不摆正的话相册图/拍照图一律旋转 90°（截图没有 EXIF，所以平时看不出来）。
 *
 * @return 解码出来并摆正的位图；[bytes] 不是图片时返回 `null`。
 *
 * @throws OutOfMemoryError 位图超出堆预算时。**调用方必须用 `runCatching` 包住**
 *   （它捕 `Throwable`，所以能接住 OOM）——这是给下一个人的契约。
 */
fun decodeScaled(
    bytes: ByteArray,
    maxWidth: Int = 1440,
    maxPixels: Long = MAX_DECODED_PIXELS,
): Bitmap? {
    require(maxWidth > 0) { "maxWidth 必须为正数，否则下采样循环不会终止" }
    require(maxPixels > 0L) { "maxPixels 必须为正数" }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(width, height, maxWidth, maxPixels)
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return applyExifOrientation(bytes, decoded)
}

/** 下采样后的像素预算（宽 × 高）。8 MP ≈ 32 MB ARGB_8888。 */
internal const val MAX_DECODED_PIXELS = 8_000_000L

/** [decodeScaled] 的下采样倍率上限：2^6 = 64，足够把 1 亿像素压到预算内。 */
private const val MAX_SAMPLE_SIZE = 64

/**
 * 按目标宽度与像素预算算 `inSampleSize`（2 的幂）。
 *
 * 两个条件任一不满足就继续降采样：宽度超了，或者总面积超了预算。
 * **高度/面积必须一并看**：滚动截图这类「宽合格但极高」的输入只靠宽度判不出来——
 * 1440×20000 的宽度合格（sample = 1）却要 115 MB ARGB_8888（R007d P1-1）。
 *
 * 抽成 internal 纯函数是为了能 JVM 单测（不依赖 Robolectric 的解码）。
 */
internal fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxPixels: Long): Int {
    require(maxWidth > 0) { "maxWidth 必须为正数，否则下采样循环不会终止" }
    require(maxPixels > 0L) { "maxPixels 必须为正数" }
    var sample = 1
    while (sample < MAX_SAMPLE_SIZE) {
        val w = width / sample
        val h = height / sample
        if (w <= maxWidth && w.toLong() * h.toLong() <= maxPixels) break
        sample *= 2
    }
    return sample
}

/**
 * EXIF 方向标记 → 位图变换矩阵；`null` 表示无需变换（含未定义/未知值）。
 *
 * 抽成 internal 纯函数是为了能在 JVM 上钉住八种方向的映射（R007d P2-2 的回归测试）：
 * 造一张带 EXIF 的真实 JPEG 代价太大，而映射表本身才是容易写错的部分。
 */
internal fun exifMatrix(orientation: Int): android.graphics.Matrix? {
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) {
        return null
    }
    val matrix = android.graphics.Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }

        else -> return null
    }
    return matrix
}

/** EXIF 方向标记到位图变换的映射。 */
private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
    val orientation = try {
        ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Throwable) {
        // 不是 JPEG、或 EXIF 段损坏：当作「无需摆正」。截图/PNG 走这条，是常态路径。
        return bitmap
    }
    val matrix = exifMatrix(orientation) ?: return bitmap
    return try {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        rotated
    } catch (_: Throwable) {
        // 变换失败（极少见）时退回未摆正的位图：总比整张图打不开好。
        bitmap
    }
}
