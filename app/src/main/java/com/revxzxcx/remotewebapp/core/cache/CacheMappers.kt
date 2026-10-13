package com.revxzxcx.remotewebapp.core.cache

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshTime
import com.revxzxcx.remotewebapp.core.session.ChatItem
import com.revxzxcx.remotewebapp.core.session.SessionEventParser
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.core.workspace.WorkspaceSnapshot
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 缓存实体 ↔ 领域模型之间的映射。
 *
 * 全部是**纯函数**，因此可以在 JVM 单测里覆盖（Room 的 DAO/数据库本身需要 Android 运行时，
 * 本工程不引入 Robolectric——见 `决策记录.md` D21）。这样「缓存写进去什么、读出来什么」
 * 这件事是可测的，只有「Room 会不会把行存对」需要靠真机验证，而那是 Room 自己的责任。
 */
internal object CacheMappers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 设备凭证的**指纹**，用作所有缓存行的 `ownerId`。
     *
     * 为什么不用 `deviceId` 原文：需求文档「不在日志中打印 deviceId」的安全约束之外，
     * 缓存库是**明文 SQLite**（没有 Keystore 保护），把凭证本身写进去等于把「已配对设备 =
     * FULL-CONTROL 凭证」这个事实降级到任何能读 App 私有目录的人手里。指纹只用于分区，
     * 换设备后旧数据也不可能被拼回去。
     *
     * 同时带上 `baseUrl`：同一部手机对**同一台机器**重新配对通常会拿到新的 `deviceId`，
     * 但也可能相同；把地址一起哈希，能保证「换到另一台桌面端」一定换分区。
     */
    fun ownerIdOf(credential: DeviceCredential): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${credential.baseUrl}\u0000${credential.deviceId}".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    // ------------------------------------------------------------------ workspaces

    fun toEntity(ownerId: String, workspace: Workspace, orderIndex: Int): CachedWorkspace =
        CachedWorkspace(
            ownerId = ownerId,
            workspaceId = workspace.workspaceId,
            path = workspace.path,
            title = workspace.title,
            sessionIdsJson = encodeStringList(workspace.sessionIds),
            createdAt = workspace.createdAt,
            updatedAt = workspace.updatedAt,
            orderIndex = orderIndex,
        )

    fun toModel(entity: CachedWorkspace): Workspace =
        Workspace(
            workspaceId = entity.workspaceId,
            path = entity.path,
            title = entity.title,
            sessionIds = decodeStringList(entity.sessionIdsJson),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    /**
     * 把一份完整快照展开成行。
     *
     * `orderIndex` 用列表下标：`WorkspaceSnapshot.items` 的顺序就是服务端下发的显示顺序
     * （`workspace/follow` 的 baseline 与 `order` 帧都作用于它），丢失顺序会让冷启动时
     * 工作区跳来跳去。
     */
    fun toEntities(ownerId: String, snapshot: WorkspaceSnapshot): List<CachedWorkspace> =
        toEntities(ownerId, snapshot.items)

    /** 同上，只收工作区列表（归档/置顶集合由 `meta` 表另存，不在这里）。 */
    fun toEntities(ownerId: String, items: List<Workspace>): List<CachedWorkspace> =
        items.mapIndexed { index, workspace -> toEntity(ownerId, workspace, index) }

    // ------------------------------------------------------------------ sessions

    fun toEntity(ownerId: String, session: SessionSummary): CachedSession =
        CachedSession(
            ownerId = ownerId,
            sessionId = session.sessionId,
            title = session.title,
            updatedAt = session.updatedAt,
            agentAvailable = session.agentAvailable,
            running = session.running,
            blank = session.blank,
            parentSessionId = session.parentSessionId,
            origin = session.origin,
            cwd = session.cwd,
            lastPromptAt = session.lastPromptAt,
            lastModel = session.lastModel,
        )

    fun toModel(entity: CachedSession): SessionSummary =
        SessionSummary(
            sessionId = entity.sessionId,
            updatedAt = entity.updatedAt,
            agentAvailable = entity.agentAvailable,
            running = entity.running,
            blank = entity.blank,
            parentSessionId = entity.parentSessionId,
            origin = entity.origin,
            cwd = entity.cwd,
            title = entity.title,
            lastPromptAt = entity.lastPromptAt,
            lastModel = entity.lastModel,
        )

    // ------------------------------------------------------------------ messages

    /**
     * 把一条会话事件信封序列化成缓存里的字符串。
     *
     * 存的是**原文**，不是解析结果：解析规则只应该有一份实现（`SessionEventParser`），
     * 存解析结果就等于复制一份规则，将来必然漂移（见 [CachedMessage] 的 KDoc）。
     */
    fun encodeEnvelope(envelope: JsonObject): String = envelope.toString()

    /**
     * 把缓存里的信封字符串还原成可渲染条目。
     *
     * 解析失败返回 `null`（而不是抛异常）：缓存是**尽力而为**的数据，一条坏行不该让整个
     * 会话打不开。坏行会被 `DshCache.readMessages` 过滤掉，下一次写入时自然被覆盖。
     */
    fun decodeEnvelope(
        envelopeJson: String,
        parser: SessionEventParser,
    ): ChatItem? {
        val element = runCatching { json.parseToJsonElement(envelopeJson) }.getOrNull() ?: return null
        return runCatching { parser.parse(element) }.getOrNull()
    }

    /**
     * 把一条信封包成 [CachedMessage]。
     *
     * [time] 走 [DshTime.epochMillis]：线上 `time` 可能是数字也可能被序列化成 ISO 字符串，
     * 两套形态在同一个服务端上都出现过（M1/M2 探针），不能直接 `toLong()`。
     */
    fun toRow(ownerId: String, sessionId: String, seq: Long, envelope: JsonObject): CachedMessage =
        CachedMessage(
            ownerId = ownerId,
            sessionId = sessionId,
            seq = seq,
            time = DshTime.epochMillis((envelope["time"] as? JsonPrimitive)?.content),
            envelopeJson = encodeEnvelope(envelope),
        )

    // ------------------------------------------------------------------ helpers

    private fun encodeStringList(values: List<String>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun decodeStringList(text: String): List<String> {
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** 把字符串集合编码成 JSON 数组，供 `meta` 表存归档/置顶集合。 */
    fun encodeSet(values: Set<String>): String = encodeStringList(values.sorted())

    /** `meta` 表里 JSON 数组的读取；解析不出来就当空集合。 */
    fun decodeSet(text: String?): Set<String> =
        text?.let { decodeStringList(it).toSet() } ?: emptySet()

    /** 一个 `meta` 行；[CacheKeys] 是允许的键。 */
    fun meta(ownerId: String, key: String, value: String): CachedMeta =
        CachedMeta(ownerId = ownerId, key = key, value = value)

    /** 空对象，用于「没有 data」的兜底（[buildJsonObject] 保证类型是 [JsonObject]）。 */
    fun emptyEnvelope(): JsonObject = buildJsonObject { }
}
