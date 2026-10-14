package com.revxzxcx.remotewebapp.core.session

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 输入区里**待发送**的一个附件（需求 FR-5 的「图片与文件」）。
 *
 * 两种附件走两条不同的 wire 路径，这是服务端强制的：
 *
 * | 种类 | 发送前的准备 | `content` 里的块 |
 * |---|---|---|
 * | 图片 | 直接内联 base64 | `{type:'image', mediaType, data, name?}` |
 * | 文件 | 先 `fileUploads/upload` 换回执 | `{type:'file', receiptId}` |
 *
 * 形状来源（`F:\bot\_sdk\dsh\node_modules\@deepseek-ai\dsh-api-session-controller\lib\typert.remote-client.js`
 * 的 `imageDescriptor` / `fileDescriptor`）：
 * ```
 * image: {type:'image', mediaType:string, data:string, name?:string}
 * file : {type:'file',  receiptId:string}
 * ```
 * `file` 块**只有** `receiptId` 一个键——所以文件必须先上传拿到回执，图片不用。
 *
 * 服务端 `attachments.admitPromptContent` 还会再做一次校验（`attachmentService.ts:782-829`）：
 * 超出 `imageLimits` 的条数/字节/像素会抛 `session/attachment-invalid`（`TOO_MANY_IMAGES`
 * `IMAGE_TOO_LARGE` `MODEL_DOES_NOT_SUPPORT_IMAGES`…）。客户端先按同样上限挡一道，
 * 是为了给出**中文可读的即时反馈**，而不是让用户等到服务端报一串英文码。
 */
data class PromptAttachment(
    /** `image` 或 `file`，见 [PromptAttachment] 的 KDoc 表格。 */
    val kind: Kind,
    /** 文件名，可为 null（服务端接受 `name` 缺席）。 */
    val name: String? = null,
    /** MIME 类型，仅图片有意义。 */
    val mediaType: String = "",
    /** base64 载荷，仅图片（图片走内联，不需要先上传）。 */
    val data: String = "",
    /** 文件上传后拿到的回执，仅文件。 */
    val receiptId: String? = null,
) {

    enum class Kind(val wire: String) {
        IMAGE("image"),
        FILE("file"),
    }

    /** 转成 `session/prompt` 的 `content` 数组里的一项。 */
    fun toBlock(): JsonObject = when (kind) {
        Kind.IMAGE -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("mediaType", JsonPrimitive(mediaType))
            put("data", JsonPrimitive(data))
            if (!name.isNullOrBlank()) put("name", JsonPrimitive(name))
        }

        Kind.FILE -> buildJsonObject {
            put("type", JsonPrimitive("file"))
            put("receiptId", JsonPrimitive(receiptId.orEmpty()))
        }
    }
}

/** `maxImagesPerMessage` 从 `Long` 收成 `Int` 的安全上界。 */
private const val MAX_IMAGE_COUNT = Int.MAX_VALUE
/**
 * `projections.values.imageLimits` —— 服务端对一轮消息里图片的硬限制。
 *
 * 实测形状（真机探针，47/47 条会话都有该投影）：
 * ```
 * { maxImageBytes: number, maxImagesPerMessage: number,
 *   maxMessageImageBytes: number, maxImagePixels: number,
 *   maxImageDimension: number, mediaTypes: string[] }
 * ```
 * 解析宽容：任何键缺失都退化成「不限制」（用 `Long.MAX_VALUE`），因为限制是服务端的事，
 * 客户端少挡一道顶多少一句提示，多挡一道会把合法图片挡住。
 */
data class ImageLimits(
    val maxImageBytes: Long = Long.MAX_VALUE,
    val maxImagesPerMessage: Int = Int.MAX_VALUE,
    val maxMessageImageBytes: Long = Long.MAX_VALUE,
    val maxImagePixels: Long = Long.MAX_VALUE,
    val maxImageDimension: Long = Long.MAX_VALUE,
    val mediaTypes: List<String> = emptyList(),
) {
    companion object {
        /** 从 `projections.values.imageLimits` 解析；不是对象时返回「不限制」。 */
        fun of(element: kotlinx.serialization.json.JsonElement?): ImageLimits {
            val obj = element as? JsonObject ?: return ImageLimits()
            return ImageLimits(
                maxImageBytes = obj.longOrNull("maxImageBytes") ?: Long.MAX_VALUE,
                // `Long.toInt()` 在超 Int.MAX_VALUE 时会回绕成**负数**（R007 P2-2）：
                // 于是 `alreadyPending.size + 1 > maxImagesPerMessage` 恒真，每张图都被
                // 「一轮最多带 -3 张图片」挡掉。服务端发一个离谱的大数等于「不限制」，
                // 所以必须 coerce 而不是直接截断。
                maxImagesPerMessage = obj.longOrNull("maxImagesPerMessage")
                    ?.coerceIn(0L, MAX_IMAGE_COUNT.toLong())?.toInt() ?: Int.MAX_VALUE,
                maxMessageImageBytes = obj.longOrNull("maxMessageImageBytes") ?: Long.MAX_VALUE,
                maxImagePixels = obj.longOrNull("maxImagePixels") ?: Long.MAX_VALUE,
                maxImageDimension = obj.longOrNull("maxImageDimension") ?: Long.MAX_VALUE,
                mediaTypes = (obj.arrayOrNull("mediaTypes"))
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?: emptyList(),
            )
        }
    }
}

/**
 * 一张待发送图片的**本地度量**，用于在发送前按 [ImageLimits] 挡一道。
 *
 * @param bytes 原始字节数（不是 base64 后的长度）。
 * @param pixels 宽×高；解不出尺寸时为 0（例如 SVG 或畸形文件）。
 * @param width / height 解出的尺寸，0 表示未知。
 */
data class ImageMeasure(
    val bytes: Long,
    val pixels: Long,
    val width: Int,
    val height: Int,
)

/** 图片被 [ImageLimits] 挡下来的原因。文案由 UI 展示，所以这里直接给中文。 */
sealed interface ImageRejection {
    /** 单张超出 `maxImageBytes`（[actual] / [limit] 均为原始字节）。 */
    data class TooLarge(val actual: Long, val limit: Long) : ImageRejection

    /** 一轮里图片太多。 */
    data class TooMany(val limit: Int) : ImageRejection

    /** 这一轮所有图片的字节总和超出 `maxMessageImageBytes`。 */
    data class TotalTooLarge(val actual: Long, val limit: Long) : ImageRejection

    /** 像素超出 `maxImagePixels`。 */
    data class TooManyPixels(val pixels: Long, val limit: Long) : ImageRejection

    /** 边长超出 `maxImageDimension`。 */
    data class DimensionTooLarge(val width: Int, val height: Int, val limit: Long) : ImageRejection

    /** MIME 不在 `mediaTypes` 白名单里。 */
    data class UnsupportedType(val mediaType: String) : ImageRejection
}

/**
 * 把 [ImageMeasure] 与当前待发送列表一起，按 [ImageLimits] 判定能不能再收一张。
 *
 * 命名成 `validateImage` 而不是 `check`：`kotlin.check(Boolean)` 是同名标准库函数，
 * 写成 `limits.check(...)` 会被解析成它然后报「参数个数不对」，而且报错信息看不出
 * 真实原因（本项目踩过）。
 *
 * @return 能收就返回 null；不能收就返回具体原因。
 */
fun ImageLimits.validateImage(
    measure: ImageMeasure,
    mediaType: String,
    alreadyPending: List<PromptAttachment>,
    alreadyBytes: Long,
): ImageRejection? {
    // `lowercase()` 不带 Locale 时用系统默认 locale：土耳其语的 "I" 会小写成 "ı"，
    // 于是 "IMAGE/PNG" 在白名单里也匹配不上（R007 P3）。统一用 ROOT。
    val wanted = mediaType.lowercase(java.util.Locale.ROOT)
    val allowed = mediaTypes.map { it.lowercase(java.util.Locale.ROOT) }
    if (mediaTypes.isNotEmpty() && wanted !in allowed) {
        return ImageRejection.UnsupportedType(mediaType)
    }
    if (measure.bytes > maxImageBytes) return ImageRejection.TooLarge(measure.bytes, maxImageBytes)
    if (alreadyPending.size + 1 > maxImagesPerMessage) return ImageRejection.TooMany(maxImagesPerMessage)
    if (alreadyBytes + measure.bytes > maxMessageImageBytes) {
        return ImageRejection.TotalTooLarge(alreadyBytes + measure.bytes, maxMessageImageBytes)
    }
    if (maxImagePixels != Long.MAX_VALUE && measure.pixels > maxImagePixels) {
        return ImageRejection.TooManyPixels(measure.pixels, maxImagePixels)
    }
    if (maxImageDimension != Long.MAX_VALUE && (measure.width > maxImageDimension || measure.height > maxImageDimension)) {
        return ImageRejection.DimensionTooLarge(measure.width, measure.height, maxImageDimension)
    }
    return null
}

/** 附件被挡下时的中文文案。放在这里而不是 Composable 里，是为了能被 JVM 单测钉住。 */
fun ImageRejection.message(): String = when (this) {
    is ImageRejection.TooLarge ->
        "这张图片有 ${formatBytes(actual)}，超过单张上限 ${formatBytes(limit)}。"

    is ImageRejection.TooMany -> "一轮最多带 $limit 张图片。"
    is ImageRejection.TotalTooLarge ->
        "这一轮的图片加起来有 ${formatBytes(actual)}，超过上限 ${formatBytes(limit)}。"

    is ImageRejection.TooManyPixels ->
        "这张图片有 $pixels 像素，超过上限 $limit 像素。"

    is ImageRejection.DimensionTooLarge ->
        "这张图片是 ${width}×${height}，边长超过上限 $limit。"

    is ImageRejection.UnsupportedType -> "不支持 $mediaType 格式的图片。"
}

/**
 * 按**字节**嗅探一张图的真实类型。
 *
 * 为什么不能只信 `contentResolver.getType(uri)`（T5 设备验收实测缺陷）：桌面端的
 * `attachmentService` 会自己按魔数再嗅一遍，并与 `mediaType` 比对，不一致就抛
 * `session/attachment-invalid: Declared image type does not match its bytes.`
 * ——一条用户完全看不懂的英文。照片选择器/provider 给出的 MIME 有几种常见的不准：
 *
 * - 某些相册把 HEIC/HEIF 的照片按 `image/jpeg` 报出来；
 * - 从文件管理器选图时拿到 `application/octet-stream`；
 * - 文件被改过扩展名（`x.png` 其实是 JPEG）。
 *
 * 所以**嗅探结果优先于声明值**：认得出就按真实类型发，认不出才退回声明值交给
 * [ImageLimits.validateImage] 的白名单。
 */
internal sealed interface SniffedImage {
    /** 认得，而且是桌面端接受的四种之一。 */
    data class Supported(val mediaType: String) : SniffedImage

    /** 认得，但桌面端不收（[label] 是给人看的名字）。 */
    data class Unsupported(val label: String) : SniffedImage

    /** 认不出来：太短、不是图片、或格式未知。 */
    data object Unknown : SniffedImage
}

/**
 * 魔数嗅探。实现刻意保持「只读前 16 个字节」——相册里的大图动辄几十 MB，
 * 而判定只需要文件头。
 */
internal fun sniffImage(bytes: ByteArray): SniffedImage {
    if (bytes.size < 12) return SniffedImage.Unknown
    // ISO-BMFF 容器（HEIC/HEIF/AVIF 都是）：字节 0..3 是大端长度，4..7 是 "ftyp"，
    // 8..11 是品牌。所以判 `bytes[4..7] == "ftyp"` 而不是看扩展名。
    if (
        bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x00.toByte() &&
        bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
    ) {
        val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase(java.util.Locale.ROOT)
        return when (brand) {
            "avif", "avis" -> SniffedImage.Unsupported("AVIF")
            else -> SniffedImage.Unsupported("HEIC/HEIF")
        }
    }
    if (startsWith(bytes, byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) ||
        startsWith(bytes, byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
    ) {
        return SniffedImage.Supported("image/gif")
    }
    if (startsWith(bytes, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
        return SniffedImage.Supported("image/png")
    }
    if (startsWith(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
        return SniffedImage.Supported("image/jpeg")
    }
    if (startsWith(bytes, byteArrayOf(0x42, 0x4D))) {
        return SniffedImage.Unsupported("BMP")
    }
    if (startsWith(bytes, byteArrayOf(0x49, 0x49, 0x2A, 0x00)) ||
        startsWith(bytes, byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
    ) {
        return SniffedImage.Unsupported("TIFF")
    }
    if (startsWith(bytes, byteArrayOf(0x38, 0x42, 0x50, 0x53, 0x00, 0x01))) {
        return SniffedImage.Unsupported("Photoshop PSD")
    }
    // RIFF 容器：前 4 字节 "RIFF"，第 8..12 字节是格式。WebP 之外还有 WAV/AVI。
    if (startsWith(bytes, byteArrayOf(0x52, 0x49, 0x46, 0x46))) {
        val form = String(bytes, 8, 4, Charsets.US_ASCII).uppercase(java.util.Locale.ROOT)
        return if (form == "WEBP") {
            SniffedImage.Supported("image/webp")
        } else {
            SniffedImage.Unsupported("RIFF($form)")
        }
    }
    return SniffedImage.Unknown
}

private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
    if (bytes.size < prefix.size) return false
    for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
    return true
}

/** 把字节数格式化成 `1.5 MB` 这样的人话。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    return String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0)
}

/** base64 编码（发送图片用）。 */
fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

/**
 * base64 解码；**不抛异常**，解不出来返回空数组。
 *
 * 注意 `android.util.Base64.decode` 是宽容的：它会**跳过**字母表之外的字符，所以
 * 一段被截断/被污染的载荷不会报错，只会解出它能救的那部分（本项目实测
 * `"!!!不是 base64!!!"` 也能解出 4 个字节）。真正的「这是不是一张图」由
 * `decodeScaled` 判，坏载荷在那里落成 `Failed` 而不是在这里。
 */
fun String.fromBase64(): ByteArray =
    if (isEmpty()) ByteArray(0) else runCatching { Base64.decode(this, Base64.DEFAULT) }
        .getOrDefault(ByteArray(0))
