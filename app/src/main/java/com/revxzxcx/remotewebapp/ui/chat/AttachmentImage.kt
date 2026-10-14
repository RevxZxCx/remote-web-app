package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.AttachmentMeta
import com.revxzxcx.remotewebapp.core.session.ImageMeasure
import com.revxzxcx.remotewebapp.core.session.SessionAttachmentRepository
import com.revxzxcx.remotewebapp.ui.common.decodeScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天里的图片块（需求 FR-5 的「图片」）。
 *
 * 会话日志里的 `{type:'image'}` 内容块只带 `attachmentId`（`ContentBlock.Image`），
 * 真正的像素要按 id 现取（`session/attachment`）。所以这里是一个**按需加载**的组件：
 *
 * - 解码失败或取不到 → 显示一行说明 + `attachmentId` 前 8 位，不静默变成空白；
 * - 取到但解不出位图（例如字节被截断）→ 同上，理由如实说；
 * - `offloaded: true` 是常态（服务端把像素卸载到附件里），不是错误。
 *
 * 内存上只用 [decodeScaled] 按宽度下采样，避免原图（可能 12 MP）直接把堆打爆。
 * 缓存交给 [SessionAttachmentRepository] 的调用方（[ChatViewModel]），滚过再滚回来不重复请求。
 */
@Composable
internal fun AttachmentImage(
    sessionId: String,
    attachmentId: String,
    label: String?,
    repository: SessionAttachmentRepository?,
    modifier: Modifier = Modifier,
) {
    if (repository == null || sessionId.isEmpty() || attachmentId.isEmpty()) {
        AttachmentPlaceholder(
            text = "[图片 ${label ?: attachmentId.take(8)}]",
            modifier = modifier,
        )
        return
    }

    val state by produceState<ImageLoadState>(ImageLoadState.Loading, sessionId, attachmentId) {
        value = withContext(Dispatchers.IO) {
            // IO 而不是 Default：`repository.read` 是阻塞式 OkHttp 调用，
            // 放在 Default（线程数 = CPU 核数）上会把解码这类真 CPU 工作挤掉。
            runCatching { repository.read(sessionId, attachmentId) }
                .fold(
                    onSuccess = { payload ->
                        val bytes = payload.decode()
                        val bitmap = if (bytes.isEmpty()) null else decodeScaled(bytes)
                        if (bitmap != null) {
                            ImageLoadState.Ready(bitmap, payload.attachment)
                        } else {
                            ImageLoadState.Failed("取到了 ${bytes.size} 字节，但解不出图片")
                        }
                    },
                    onFailure = { cause ->
                        // 与 [com.revxzxcx.remotewebapp.ui.chat.ChatViewModel] 的
                        // describe() 同一套格式：带上错误码，否则用户看到的
                        // 「附件不存在」分不清是附件被清了还是网络断了。
                        val typert = cause as? com.revxzxcx.remotewebapp.core.protocol.TypertException
                        ImageLoadState.Failed(
                            typert?.let { "${it.code}：${it.message}" }
                                ?: (cause.message ?: cause::class.java.simpleName),
                        )
                    },
                )
        }
    }

    when (val current = state) {
        is ImageLoadState.Ready -> Column(modifier = modifier.fillMaxWidth()) {
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        is ImageLoadState.Failed -> AttachmentPlaceholder(
            text = "[图片加载失败 ${label ?: attachmentId.take(8)}] ${current.reason}",
            modifier = modifier,
            tint = MaterialTheme.colorScheme.error,
        )

        ImageLoadState.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在读取图片…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 量一张图片的尺寸与字节数（发送前按 [ImageLimits] 挡一道）。
 *
 * 只用 `inJustDecodeBounds`，**不分配像素内存**，所以在主线程调用也安全。
 * 解不出尺寸时 `width`/`height`/`pixels` 都是 0，[ImageLimits] 对 0 像素的判定会放过
 * （客户端只是尽力挡，真正的强制校验在服务端 `attachments.admitPromptContent`）。
 */
fun measureImage(bytes: ByteArray): ImageMeasure {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth.coerceAtLeast(0)
    val height = bounds.outHeight.coerceAtLeast(0)
    return ImageMeasure(
        bytes = bytes.size.toLong(),
        pixels = width.toLong() * height.toLong(),
        width = width,
        height = height,
    )
}

/** 图片加载的三态。故意不用 `null` 同时表示「进行中」和「失败」——R005 P1 的教训。 */
private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(
        val bitmap: android.graphics.Bitmap,
        val meta: AttachmentMeta,
    ) : ImageLoadState

    data class Failed(val reason: String) : ImageLoadState
}

/** 图片的降级展示：一行说明文字，颜色可调（失败用 error 色）。 */
@Composable
private fun AttachmentPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tint,
        modifier = modifier.fillMaxWidth(),
    )
}
