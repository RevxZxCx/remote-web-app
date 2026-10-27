package com.revxzxcx.remotewebapp.core.credentials

/**
 * 凭证簿的读写抽象（R054：从「单份凭证」升级为「多主机凭证簿」）。
 *
 * 抽成接口是为了让上层（Repository / ViewModel）不依赖 Android Keystore，从而能在 JVM 单测里
 * 换成 [InMemoryCredentialStore]（Keystore 只有真机 / Robolectric 才有）。
 *
 * ## 为什么读写的是整本而不是单条
 *
 * 三个操作（改名 / 改地址 / 删一条）都需要「读-改-写」整个集合，而**并发**是真实存在的：
 * 用户可能一边在设备栏里改名、一边因为切换主机触发 `bootstrap`。如果接口只暴露
 * `save(credential)`，两个调用方各自读到一份旧簿再写回，后写的那个会静默覆盖前一个改动。
 * 把整本作为唯一的读写单位，让「一次改动 = 一次整本替换」成为接口层面的约定，
 * 由上层（[com.revxzxcx.remotewebapp.core.AppContainer]）用单飞锁串行化。
 */
interface CredentialStore {

    /**
     * 读取整本凭证簿；没有、解不开、或格式不认识时返回 [CredentialBook.EMPTY]
     * （不抛异常，调用方按「未配对」处理）。
     */
    suspend fun loadBook(): CredentialBook

    /** 覆盖写入整本。 */
    suspend fun saveBook(book: CredentialBook)

    /** 清除整本（退出配对 / 全部删除）。 */
    suspend fun clear()

    /**
     * 读取当前激活的那条；没有则 `null`。
     *
     * 这是最常用的读法（连接建立、缓存分区、UI 顶栏标题），单独开一个方法是为了让
     * 调用方拿到正确回退后的结果（[CredentialBook.active] 会处理悬空 `activeHostId`），
     * 而不是各自再写一遍。
     */
    suspend fun load(): DeviceCredential? = loadBook().active

    /**
     * 兼容旧调用点的单条写入：等价于「加入或更新一条并设为激活」。
     *
     * 保留它是因为配对流程（[com.revxzxcx.remotewebapp.core.pairing.PairingCoordinator]）
     * 天然产出「一条凭证」而不是「一本簿」。实现放在接口里做默认方法，
     * 避免每个实现各写一遍导致行为分叉。
     */
    suspend fun save(credential: DeviceCredential) {
        saveBook(loadBook().withUpsert(credential))
    }
}

/** 测试与预览用。 */
class InMemoryCredentialStore(
    initial: DeviceCredential? = null,
    initialBook: CredentialBook = initial?.let { CredentialBook.ofSingle(it) } ?: CredentialBook.EMPTY,
) : CredentialStore {

    private var book: CredentialBook = initialBook

    override suspend fun loadBook(): CredentialBook = book

    override suspend fun saveBook(book: CredentialBook) {
        this.book = book
    }

    override suspend fun clear() {
        book = CredentialBook.EMPTY
    }
}
