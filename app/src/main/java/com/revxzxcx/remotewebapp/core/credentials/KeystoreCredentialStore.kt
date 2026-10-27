package com.revxzxcx.remotewebapp.core.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 **Android Keystore** 保护的凭证簿存储（决策 D16；R054 从单条升级为多主机）。
 *
 * 为什么不直接用 `androidx.security:security-crypto`：该库 1.1.0 的稳定版在 Maven 上取不到
 * （只有 alpha，且 alpha 也要额外校验），而 Keystore + AES/GCM 本身只有几十行，依赖更少、
 * 行为更透明（见 M1 依赖探测结论）。
 *
 * 文件格式（`filesDir/dsh-device-credential.bin`）：
 * ```
 * [0]      版本号：1 = 单条凭证（旧），2 = 凭证簿（新）
 * [1]      iv 长度（GCM 固定 12）
 * [2..13]  iv
 * [14..]   AES/GCM 密文（内含 GCM tag）
 * ```
 * 版本字节在**密文外层**、不参与加密，所以升级时能先读出它是第几版再决定怎么解。
 *
 * - **v2 明文**：`CredentialBook` 的 JSON。
 * - **v1 明文**：单个 `DeviceCredential` 的 JSON。读到 v1 时自动升级成「只有一条的簿」并
 *   **立刻以 v2 落盘**（见 [loadBook]），用户不必重新配对——这是 R054 迁移的核心承诺。
 *
 * 为什么不把老文件换个名字留着：一份能解开就有效的完全控制权凭证，多留一份只是多一个泄露面。
 * 升级成功即覆盖同一个文件，失败则保持原样（下次启动还能再试）。
 *
 * **密钥不进备份**：Keystore 里的密钥本来就随 App 数据走，`AndroidManifest` 已设
 * `allowBackup="false"`，所以备份/换机后密文解不开 → [loadBook] 返回空簿 → 走重新配对。
 *
 * 线程：所有方法都切到 [Dispatchers.IO]。
 */
class KeystoreCredentialStore(
    context: Context,
    private val file: File = File(context.filesDir, FILE_NAME),
) : CredentialStore {

    override suspend fun loadBook(): CredentialBook = withContext(Dispatchers.IO) {
        val decrypted = decryptFile() ?: return@withContext CredentialBook.EMPTY
        val (version, plain) = decrypted
        when (version) {
            VERSION_BOOK -> decodeBook(plain)
            VERSION_SINGLE -> migrateSingle(plain)
            else -> {
                Log.w(TAG, "凭证文件版本不认识（$version），按未配对处理")
                CredentialBook.EMPTY
            }
        }
    }

    /** 解 v1：单条凭证 → 包成一条的簿，并以 v2 覆盖回去。 */
    private suspend fun migrateSingle(plain: String): CredentialBook {
        val single = runCatching {
            DshJson.decodeFromString(DeviceCredential.serializer(), plain)
        }.getOrElse {
            Log.w(TAG, "v1 凭证 JSON 解析失败，按未配对处理", it)
            return CredentialBook.EMPTY
        }
        val book = CredentialBook.ofSingle(single)
        // 升级落盘。失败不算致命：这一轮照样能用（内存里有簿），下次启动会再试一次。
        runCatching { writeBook(book) }
            .onSuccess { Log.i(TAG, "凭证已从 v1 升级为 v2（凭证簿）") }
            .onFailure { Log.w(TAG, "凭证升级落盘失败，本次先用内存中的结果", it) }
        return book
    }

    private fun decodeBook(plain: String): CredentialBook =
        runCatching {
            DshJson.decodeFromString(CredentialBook.serializer(), plain)
        }.getOrElse {
            Log.w(TAG, "凭证簿 JSON 解析失败，按未配对处理", it)
            CredentialBook.EMPTY
        }

    override suspend fun saveBook(book: CredentialBook) = withContext(Dispatchers.IO) {
        writeBook(book)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
        Unit
    }

    // ---------------------------------------------------------------- 加解密

    /**
     * 读出并解密文件。
     *
     * @return `版本号 to 明文`；文件不存在 / 太短 / 密钥没了 / 解密失败一律 `null`。
     */
    private fun decryptFile(): Pair<Int, String>? {
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrElse {
            Log.w(TAG, "读取凭证文件失败", it)
            return null
        }
        if (bytes.size < HEADER_SIZE) {
            Log.w(TAG, "凭证文件过短（${bytes.size} 字节），按未配对处理")
            return null
        }
        // 版本字节是 Byte，`toInt()` 会做符号扩展（0x02 没问题，但防御性地按无符号读）。
        val version = bytes[0].toInt() and 0xFF
        val ivLength = bytes[1].toInt()
        if (ivLength <= 0 || bytes.size < HEADER_SIZE + ivLength) {
            Log.w(TAG, "凭证文件 iv 长度非法（$ivLength）")
            return null
        }
        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val cipherText = bytes.copyOfRange(2 + ivLength, bytes.size)

        val key = runCatching { existingKey() }.getOrNull()
        if (key == null) {
            // 密钥没了（清过 App 数据 / 换机）→ 密文等同垃圾，直接清掉。
            Log.w(TAG, "Keystore 里没有凭证密钥，清除本地密文")
            runCatching { file.delete() }
            return null
        }

        val plain = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(cipherText)
        }.getOrElse {
            // 密钥被失效（例如系统重置了锁屏）或密文损坏：丢弃，让用户重新配对。
            Log.w(TAG, "解密凭证失败，按未配对处理", it)
            runCatching { file.delete() }
            return null
        }
        return version to String(plain, Charsets.UTF_8)
    }

    private fun writeBook(book: CredentialBook) {
        val plain = DshJson.encodeToString(CredentialBook.serializer(), book)
            .toByteArray(Charsets.UTF_8)

        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)

        val out = ByteArray(2 + iv.size + cipherText.size)
        out[0] = VERSION_BOOK.toByte()
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(cipherText, 0, out, 2 + iv.size, cipherText.size)

        // 先写临时文件再原子替换，避免写一半掉电留下半截密文。
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- Keystore

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? {
        val ks = keyStore()
        if (!ks.containsAlias(KEY_ALIAS)) return null
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // 不要求用户认证：App 冷启动要立刻用凭证，弹指纹会毁掉「秒开」这个核心目标（G1）。
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "DshCredentialStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dsh_device_credential_v1"
        const val KEY_SIZE_BITS = 256
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FILE_NAME = "dsh-device-credential.bin"

        /** 版本字节 + iv 长度字节。 */
        const val HEADER_SIZE = 2

        /** 旧版：明文是单个 `DeviceCredential`。 */
        const val VERSION_SINGLE = 1

        /** 新版（R054）：明文是 `CredentialBook`。 */
        const val VERSION_BOOK = 2
    }
}
