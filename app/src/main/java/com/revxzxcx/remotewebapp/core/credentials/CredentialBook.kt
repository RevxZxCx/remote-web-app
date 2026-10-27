package com.revxzxcx.remotewebapp.core.credentials

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 整本凭证簿：这台手机上登记过的**所有**远程主机 + 当前正在用哪一台（R054）。
 *
 * ## 为什么要有 `hostId`
 *
 * 改名前用户拿 `deviceId` 当主键就够了——一台手机只连一台桌面端，凭证只有一份。
 * 但支持多主机后，**每一条记录都需要一个不随内容变化的稳定身份**，否则：
 *
 * - 重命名 `label` 会让「哪一条是当前激活的」失效；
 * - 改地址 `baseUrl` 同样会失效，而「桌面端换了局域网 IP、重新登记一次地址」是最常见的操作；
 * - 删除时按 `label` 或 `baseUrl` 匹配，都可能一次删掉两条（同一台机器登记过两次）。
 *
 * 所以 [DeviceCredential.hostId] 是**独立的随机 id**，一旦生成永不改变。
 *
 * ## 为什么不用 `CacheMappers.ownerIdOf()` 那个指纹
 *
 * 指纹是 `SHA-256(baseUrl + deviceId)` 的前 8 字节，它包含 `baseUrl`——地址一改指纹就变。
 * 指纹的用途是**缓存分区**（每台主机天然隔离、换机不可能串数据），那是它该做的事；
 * 但拿它当身份会让「编辑地址」变成「换了一台主机」。两者服务不同目的，不能合并。
 *
 * ## 为什么 `active` 允许回退到第一条
 *
 * [activeHostId] 指向一条已经被删掉的记录是**异常态**（不是用户意图），只可能来自手改文件
 * 或旧版本迁移留下的脏数据。此时回退到 [hosts] 的第一条，比返回 `null` 让整个 App 掉回
 * 配对页要好——用户看得见自己的主机列表，可以自己选一个。
 */
@Serializable
data class CredentialBook(
    /** 登记过的主机，顺序即列表展示顺序（最近激活的排前面由 UI 决定，不在这里排）。 */
    val hosts: List<DeviceCredential> = emptyList(),
    /** 当前激活的主机 id；`null` 表示还没选过。 */
    val activeHostId: String? = null,
) {

    /** 当前该用哪条凭证；一本空簿返回 `null`（= 未配对）。 */
    val active: DeviceCredential?
        get() = hosts.firstOrNull { it.hostId == activeHostId } ?: hosts.firstOrNull()

    /** 按 id 取一条；不存在返回 `null`。 */
    fun hostOf(hostId: String): DeviceCredential? = hosts.firstOrNull { it.hostId == hostId }

    /**
     * 加入或更新一条，并（默认）设为当前激活。
     *
     * **同一台机器重新配对时沿用原 `hostId`**：判据是 `baseUrl` 与 `deviceId` 都相同。
     * 不这样做的话，用户在同一台桌面端上重新扫码就会得到两条一模一样的记录——
     * 桌面端每次 `pair/accept` 都会发新 `deviceId`，所以实际上更常见的是「同地址、新 id」，
     * 那时会新增一条，这是对的（旧那条的 `deviceId` 已经失效了）。
     *
     * @param newHostId 生成新 id 的方式；默认随机 UUID，测试可注入固定序列。
     */
    fun withUpsert(
        credential: DeviceCredential,
        makeActive: Boolean = true,
        newHostId: () -> String = { UUID.randomUUID().toString() },
    ): CredentialBook {
        val sameEndpoint = hosts.firstOrNull {
            it.baseUrl == credential.baseUrl && it.deviceId == credential.deviceId
        }
        val merged = when {
            // 调用方已经给了 hostId：以它为准（编辑场景）。
            credential.hostId.isNotBlank() -> credential
            sameEndpoint != null -> credential.copy(hostId = sameEndpoint.hostId)
            else -> credential.copy(hostId = newHostId())
        }
        // 保证「同一 hostId 只有一条」。命中时**就地替换**而不是先 filterNot 再追加：
        // 后者会把这条挪到列表末尾，用户刚改完名字的那台突然跳到最后一行，
        // 看起来像「点错了」；就地替换则让它原地更新。
        val index = hosts.indexOfFirst { it.hostId == merged.hostId }
        val next = if (index >= 0) {
            hosts.toMutableList().also { it[index] = merged }
        } else {
            hosts + merged
        }
        return copy(
            hosts = next,
            activeHostId = if (makeActive) merged.hostId else activeHostId,
        )
    }

    /** 切到某条主机；id 不存在时原样返回（不制造指向空处的 `activeHostId`）。 */
    fun withActive(hostId: String): CredentialBook =
        if (hostOf(hostId) == null) this else copy(activeHostId = hostId)

    /**
     * 移除一条。
     *
     * 如果删的正好是激活的那条，`activeHostId` 交给 [active] 的回退逻辑处理——
     * 这里显式设为 `null`，让「还剩几条就自动用第一条」成为唯一确定的行为，
     * 而不是留下一个指向已删记录的悬空 id。
     */
    fun withRemoved(hostId: String): CredentialBook {
        val next = hosts.filterNot { it.hostId == hostId }
        if (next.size == hosts.size) return this
        return copy(
            hosts = next,
            activeHostId = if (activeHostId == hostId) null else activeHostId,
        )
    }

    /** 改显示名。空名字按「清掉自定义名」处理（UI 会回退显示地址）。 */
    fun withRenamed(hostId: String, label: String): CredentialBook =
        mapHost(hostId) { it.copy(label = label.trim()) }

    /**
     * 改地址。
     *
     * 只改地址、**不动 `deviceId`**：换地址的典型场景是桌面端换了局域网 IP 或改了隧道域名，
     * 配对关系本身没变。若用户换了另一台桌面端，应该走「添加远程」而不是「编辑地址」。
     */
    fun withEndpoint(hostId: String, baseUrl: String): CredentialBook =
        mapHost(hostId) { it.copy(baseUrl = baseUrl.trimEnd('/')) }

    private fun mapHost(hostId: String, transform: (DeviceCredential) -> DeviceCredential): CredentialBook {
        val index = hosts.indexOfFirst { it.hostId == hostId }
        if (index < 0) return this
        return copy(hosts = hosts.toMutableList().also { it[index] = transform(it[index]) })
    }

    companion object {
        /** 空簿。 */
        val EMPTY: CredentialBook = CredentialBook()

        /** 把旧版（单条凭证）包成一本只有一条的簿，供 v1 文件迁移。 */
        fun ofSingle(
            credential: DeviceCredential,
            newHostId: () -> String = { UUID.randomUUID().toString() },
        ): CredentialBook {
            val hostId = credential.hostId.ifBlank { newHostId() }
            val single = credential.copy(hostId = hostId)
            return CredentialBook(hosts = listOf(single), activeHostId = hostId)
        }
    }
}

/**
 * 两份凭证是否指向**同一个 socket 端点**（R054）。
 *
 * 只比 `baseUrl` + `deviceId`，不比整个 [DeviceCredential]，用来判断「旧连接能不能直接复用」。
 *
 * ## 为什么不能用整体相等
 *
 * R054 之前 `AppContainer.installConnection` 写的是 `current.credential == credential`。
 * 那在「一台手机只连一台桌面端、配对后凭证不再变」的前提下成立；支持多主机后
 * `label`（改名）与 `hostId`（旧数据迁移时补上）都会变，用整体相等会让
 * **改个显示名就触发重建连接**。而重建是有代价的：`HomeViewModel` 活在 Activity 的
 * ViewModelStore 里、仍持有旧连接，旧流会以「正常完成」结束（`RemoteStreamMux.close()`
 * 不带 cause），既不报错也不触发 `retryWhen`，页面就停在旧列表和「已连接」上，
 * 直到用户手动刷新。
 *
 * ## 为什么改地址必须算「不同端点」
 *
 * `typert` / `mux` / `routes` / `attachments` / `uploads` 全都是用构造参数建出来的，
 * 换了地址只能整条重建。所以这里比 `baseUrl`，而不是拿 `hostId` 判「同一台机器」：
 * 同一台机器换地址，连接**必须**重建。
 */
internal fun isSameEndpoint(a: DeviceCredential?, b: DeviceCredential?): Boolean {
    if (a == null || b == null) return false
    return a.baseUrl == b.baseUrl && a.deviceId == b.deviceId
}

/** 便于 [CredentialBook.withUpsert] 之外的地方补一个 id（旧数据迁移时用）。 */
internal fun DeviceCredential.ensureHostId(
    newHostId: () -> String = { UUID.randomUUID().toString() },
): DeviceCredential = if (hostId.isNotBlank()) this else copy(hostId = newHostId())
