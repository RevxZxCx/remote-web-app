package com.revxzxcx.remotewebapp.core.session

import android.util.Base64
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `session/attachment` 返回的附件元数据。
 *
 * 线级形状（`dsh-api-session-controller/lib/typert.remote-client.js` 的
 * `attachmentDescriptor`）：
 * ```
 * { attachmentId, mediaType, bytes: number, width: number, height: number,
 *   name?: string, originalDimensions?: {width, height} }
 * ```
 * 解析必须宽容：`name` 与 `originalDimensions` 都可能缺席，宽高在非图片附件上是 0。
 *
 * ⚠️ 这里**故意没有** `originalDimensions`：未知键由 `DshJson` 的
 * `ignoreUnknownKeys = true`（见 `core/protocol/DshJson.kt`）忽略，加一个用不到的字段
 * 只会让这个 DTO 与 descriptor 多一处需要同步的地方。这个「宽松」是**全局配置隐式提供的**，
 * 不是本文件的功劳——新增任何 `@Serializable` DTO 前先确认走的是 `DshJson`。
 */
@Serializable
data class AttachmentMeta(
    val attachmentId: String = "",
    val mediaType: String = "",
    val bytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val name: String? = null,
)

/**
 * `session/attachment` 的**成功值**。
 *
 * 权威形状（同上文件 `_deepseek_ai_dsh_session_session_attachment_parameter_1$schema`）：
 * ```
 * { attachment: {attachmentId, mediaType, bytes, width, height, name?},
 *   data: string }
 * ```
 * `data` 是 base64 的原始字节。失败值 `{ok:false,error:{code:'attachment-not-found'}}`
 * 会由 [TypertClient] 抛成 [com.revxzxcx.remotewebapp.core.protocol.TypertException]，
 * 所以这里只需要描述成功路径。
 */
@Serializable
data class AttachmentPayload(
    val attachment: AttachmentMeta = AttachmentMeta(),
    val data: String = "",
) {
    /**
     * base64 解码后的原始字节。**不是图片时返回空数组**，调用方据此走失败文案。
     *
     * 是函数而不是 `by lazy` 属性：这个类会被 [SessionAttachmentRepository] 的缓存长期持有，
     * 惰性属性一旦被访问就把 ByteArray 钉在缓存条目里直到逐出（一张 4000×3000 的图 ≈ 48 MB，
     * 十几张就是几百 MB）。改成按需解码后，字节数组的寿命与调用方（`produceState`）相同，
     * 解码完就可以被回收。
     */
    fun decode(): ByteArray =
        if (data.isEmpty()) {
            ByteArray(0)
        } else {
            // Base64.decode 对非法字符是宽容的（跳过），所以这里不会抛；坏载荷交给
            // decodeScaled 判成「解不出图片」。块内没有挂起函数，取消不可能在此投递。
            runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        }
}

/**
 * 附件读取（需求 FR-5 的「图片与文件」）。
 *
 * `session/attachment` 是**普通 unary RPC**（`endpoints.json:74-87`，`kind: "rpc"`，流式的是
 * `session/follow`），所以走 [TypertClient.call] 而不是 mux。
 *
 * 为什么要单独一个仓库：会话日志里 `{type:'image'}` 内容块只带 `attachmentId`，
 * 真正的像素必须按 id 现取（`attachments.readAttachment`，`attachmentService.ts:274-311`）。
 * 桌面端 UI 也是这样按需取，不预加载——一轮对话可能贴了十几张图，全预取会白等。
 *
 * ## 有界内存缓存
 * 滚动回已经看过的图片时不该再打一次网络，而一轮对话的图往往比一屏多。三条规则：
 *
 * 1. **按访问顺序淘汰**（`accessOrder = true`）：命中即淘汰（R006 式的「删了不放回去」）
 *    会让缓存永远只命中一次，`CACHE_BUDGET_BYTES` 的淘汰逻辑成为死代码。
 * 2. **按字节数设预算**，不按条数：一张 4000×3000 的照片 ≈ 48 MB base64，按条数算的话
 *    「16 条」可能是 700 MB；按字节算的话大图自己一条就占满预算，小截图则能缓存很多。
 * 3. **只缓存 base64 原文，不缓存解码后的字节**（见 [AttachmentPayload.decode]）。
 */
class SessionAttachmentRepository(private val client: TypertClient) {

    private val cache = LinkedHashMap<String, AttachmentPayload>(16, 0.75f, true)

    /**
     * 读一个附件的字节。
     *
     * @param sessionId 会话 id。wire 形状是 `{request: {sessionId, attachmentId}}`
     *   ——**不是**扁平的两个参数。值与 `workspaceFileScopeId` 传 sessionId 是同一套约定
     *   （`endpoints.json` 的 `readAttachment` 第一个形参）。
     * @param attachmentId 内容块里的 `attachmentId`。
     * @throws com.revxzxcx.remotewebapp.core.protocol.TypertException 附件不存在或已被清理。
     */
    suspend fun read(sessionId: String, attachmentId: String): AttachmentPayload {
        // 命中只读不删：accessOrder 下这次访问已经把条目挪到队尾。
        synchronized(cache) { cache[attachmentId] }?.let { return it }
        val payload = client.call(
            endpoint = "session/attachment",
            args = buildJsonObject {
                // ⚠️ 必须包一层 `request`。descriptor 只有一个形参 `request`
                // （`{request: {sessionId, attachmentId}}`），扁平传参会过不了
                // `assertExactArguments`：实测回
                // `gateway/arguments-invalid: session/attachment: args fields do not match
                //  the descriptor: missing "request"; unexpected "sessionId", "attachmentId"`。
                // 这正是 R039 用户报的「发图片报错」的根因（R040 修复）。
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("attachmentId", attachmentId)
                })
            },
            serializer = AttachmentPayload.serializer(),
        )
        synchronized(cache) {
            cache[attachmentId] = payload
            while (cache.size > 1 && cachedBytesLocked() > CACHE_BUDGET_BYTES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
        return payload
    }

    /** 调用方必须已持有 [cache] 的锁。base64 字符数 ≈ 原始字节数 × 4/3，是内存的保守上界。 */
    private fun cachedBytesLocked(): Long =
        cache.values.sumOf { it.data.length.toLong() }

    private companion object {
        /**
         * 常驻内存的 base64 原文预算。8 MB ≈ 20 张截图，或 1 张 4000×3000 的照片。
         * 超预算时淘汰最久未访问的条目；永远保留至少 1 条（刚放进去的那条），
         * 否则「读一张超大图 → 立刻被自己淘汰 → 滚动回来再打一次网络」。
         */
        const val CACHE_BUDGET_BYTES = 8L * 1024 * 1024
    }
}
