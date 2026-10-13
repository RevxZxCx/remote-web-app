package com.revxzxcx.remotewebapp.core.credentials

/**
 * 设备凭证的读写抽象。
 *
 * 抽成接口是为了让上层（Repository / ViewModel）不依赖 Android Keystore，从而能在 JVM 单测里
 * 换成 [InMemoryCredentialStore]（Keystore 只有真机 / Robolectric 才有）。
 */
interface CredentialStore {

    /** 读取当前凭证；没有或解不开时返回 `null`（不抛异常，调用方按「未配对」处理）。 */
    suspend fun load(): DeviceCredential?

    /** 覆盖写入。 */
    suspend fun save(credential: DeviceCredential)

    /** 清除（退出配对 / 凭证失效）。 */
    suspend fun clear()
}

/** 测试与预览用。 */
class InMemoryCredentialStore(
    initial: DeviceCredential? = null,
) : CredentialStore {

    private var current: DeviceCredential? = initial

    override suspend fun load(): DeviceCredential? = current

    override suspend fun save(credential: DeviceCredential) {
        current = credential
    }

    override suspend fun clear() {
        current = null
    }
}
