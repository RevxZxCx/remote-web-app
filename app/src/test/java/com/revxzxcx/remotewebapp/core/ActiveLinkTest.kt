package com.revxzxcx.remotewebapp.core

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.credentials.planHostAdd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B004「切换设备后首页工作区不刷新」的回归测试。
 *
 * 缺陷的形状是**半新半旧的读取**：切换主机时凭证簿先发布、连接后安装，中间隔着预热缓存库
 * 的挂起点，于是 UI 重组时读到的 `activeHost` 已经是新主机、`connection` 还是旧连接。
 * 而 `AppState` 是 `Paired → Paired`（[kotlinx.coroutines.flow.StateFlow] 对相等值不发射），
 * 没有任何后续事件去纠正那个握着旧连接的 ViewModel，页面就永远停在上一台的工作区。
 *
 * 所以这里钉的不是「某个函数返回什么」，而是两条**结构性质**：
 * 1. 快照自洽：[ActiveLink] 的 `book.active` 与 `connection` 必须是同一台；
 * 2. 连接换了 key 就变：[ActiveLink.scopeToken] 必须随连接代次变化，否则 ViewModel
 *    不会重建，仍抓着旧连接。
 *
 * 用真 [DshConnection] 而不是 mock：`planFor` 的判据是 `baseUrl` + `deviceId`，真对象的
 * 这两个字段由构造参数决定，够用；mock 反而会让「实例身份」这类断言失去意义。
 */
class ActiveLinkTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val http = OkHttpClient()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /** 造一条**指向不可达地址**的连接：本测试只关心字段与实例身份，不发请求。 */
    private fun connectionOf(deviceId: String, baseUrl: String = "http://127.0.0.1:1"): DshConnection =
        DshConnection(
            initialCredential = DeviceCredential(
                baseUrl = baseUrl,
                deviceId = deviceId,
                pairedAt = 0L,
                hostId = "host-$deviceId",
            ),
            unaryHttp = http,
            streamHttp = http,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it },
        )

    private fun credentialOf(deviceId: String, baseUrl: String = "http://127.0.0.1:1") =
        DeviceCredential(
            baseUrl = baseUrl,
            deviceId = deviceId,
            pairedAt = 0L,
            hostId = "host-$deviceId",
        )

    private fun bookOf(vararg deviceIds: String): CredentialBook =
        CredentialBook(hosts = deviceIds.map { credentialOf(it) }, activeHostId = "host-${deviceIds.first()}")

    // ---------------------------------------------------------------- 快照自洽

    @Test
    fun `快照里的连接必须与簿里激活那台是同一台 这就是 B004 的半新半旧`() {
        val a = connectionOf("device-a")
        val link = ActiveLink(book = bookOf("device-a"), connection = a, generation = 1)

        assertEquals("device-a", link.connection?.deviceId)
        assertEquals(link.activeHost?.deviceId, link.connection?.deviceId)
        assertEquals(link.activeHost?.baseUrl, link.connection?.baseUrl)
    }

    @Test
    fun `切换主机后发布的一份里 连接必须已经是新那台`() {
        val store = LinkStore()
        val a = connectionOf("device-a")
        val b = connectionOf("device-b")
        store.publish(book = bookOf("device-a"), connection = a)

        val next = store.publish(book = bookOf("device-b"), connection = b)

        // 关键断言：**一次发布**之后就没有中间态可观察。
        assertEquals("device-b", next.activeHost?.deviceId)
        assertEquals("device-b", next.connection?.deviceId)
        assertSame(b, next.connection)
    }

    @Test
    fun `ownerId 与 activeHost 同源 不会出现簿是新的分区键是旧的`() {
        val store = LinkStore()
        store.publish(book = bookOf("device-a"), connection = connectionOf("device-a"))

        val next = store.publish(book = bookOf("device-b"), connection = connectionOf("device-b"))

        assertNotEquals(
            "两个不同设备必须落在不同分区（否则缓存会串台）",
            ActiveLink(book = bookOf("device-a"), connection = null, generation = 0).ownerId,
            next.ownerId,
        )
        // ownerId 由 activeHost 现算，不缓存：不可能与 activeHost 错位。
        assertEquals(
            com.revxzxcx.remotewebapp.core.cache.CacheMappers.ownerIdOf(credentialOf("device-b")),
            next.ownerId,
        )
    }

    @Test
    fun `任何一次对外可见的发射都必须自洽 不能出现新簿配旧连接`() {
        // 这条是 B004 最直接的钉子：旧实现里「簿」和「连接」是两个独立 StateFlow，
        // 观察者收两次、中间那一帧就是半新半旧。现在只有一个 flow，每次发射都得自洽。
        val store = LinkStore()
        store.publish(book = bookOf("device-a"), connection = connectionOf("device-a"))

        val seen = mutableListOf<ActiveLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            scope.launch { store.link.collect { seen += it } }
            store.publish(book = bookOf("device-b"), connection = connectionOf("device-b"))
            store.publish(book = bookOf("device-c"), connection = connectionOf("device-c"))
        } finally {
            scope.cancel()
        }

        assertTrue("必须真的观察到多次发射，否则这条测试是空转", seen.size >= 3)
        seen.forEach { snap ->
            assertEquals(
                "发射出去的快照里 簿的激活主机与连接必须是同一台（半新半旧会停在这一帧）",
                snap.activeHost?.deviceId,
                snap.connection?.deviceId,
            )
        }
    }

    // ------------------------------------------------------------ 代次与 key

    @Test
    fun `连接换了 scopeToken 就必须变 否则 ViewModel 不会重建`() {
        val store = LinkStore()
        val a = store.publish(book = bookOf("device-a"), connection = connectionOf("device-a"))

        val b = store.publish(book = bookOf("device-b"), connection = connectionOf("device-b"))

        assertNotEquals("换连接必须换 key", a.scopeToken, b.scopeToken)
        assertNotEquals("代次必须自增", a.generation, b.generation)
        assertEquals(a.generation + 1, b.generation)
    }

    @Test
    fun `复用同一条连接时 scopeToken 不能变 改名不该重建整组 ViewModel`() {
        val store = LinkStore()
        val a = connectionOf("device-a")
        store.publish(book = bookOf("device-a"), connection = a)

        // 只改显示名：同一个连接实例原样带过。
        val renamed = store.publish(
            book = CredentialBook(
                hosts = listOf(credentialOf("device-a").copy(label = "书房的那台")),
                activeHostId = "host-device-a",
            ),
            connection = a,
        )

        assertEquals("改显示名不该换 key", store.current().generation, renamed.generation)
        assertEquals("书房的那台", renamed.activeHost?.label)
    }

    @Test
    fun `同一台设备换地址也要换 scopeToken 这是只带 deviceId 的 key 会漏掉的`() {
        val store = LinkStore()
        val old = connectionOf("device-a", baseUrl = "http://127.0.0.1:1")
        // 切换**之前**的 token 必须先记下来：publish 之后 store.current() 已是新的一份，
        // 拿它跟新值比属于自己跟自己比（第一版就写错了，断言永远不成立）。
        store.publish(book = bookOf("device-a"), connection = old)
        val before = store.current().scopeToken

        // 同一 deviceId、不同 baseUrl：连接必须重建，key 也必须变。
        val next = store.publish(
            book = CredentialBook(
                hosts = listOf(credentialOf("device-a", baseUrl = "http://127.0.0.1:2")),
                activeHostId = "host-device-a",
            ),
            connection = connectionOf("device-a", baseUrl = "http://127.0.0.1:2"),
        )

        assertNotEquals(
            "只按 deviceId 做 key 时这里不会变，ViewModel 就会继续握着旧 baseUrl 的连接",
            before,
            next.scopeToken,
        )
        assertTrue("同一台设备换地址，token 的前缀不变、代次必须递增", next.scopeToken.startsWith("device-a-"))
        assertEquals("http://127.0.0.1:2", next.activeHost?.baseUrl)
    }

    @Test
    fun `掉回未配对时 scopeToken 也要变 否则首页会继续显示上一台的数据`() {
        val store = LinkStore()
        store.publish(book = bookOf("device-a"), connection = connectionOf("device-a"))

        val empty = store.publish(book = CredentialBook.EMPTY, connection = null)

        assertNull(empty.activeHost)
        assertNull(empty.connection)
        assertTrue("失败时不该还是上一台的 token", empty.scopeToken.startsWith("none-"))
    }

    @Test
    fun `冷启动发布空快照不该自增代次 避免无意义的重组`() {
        val store = LinkStore()

        assertEquals(0, store.current().generation)
        val published = store.publish(book = CredentialBook.EMPTY, connection = null)

        assertEquals("本来就是空，再发一次空不该算「换了连接」", 0, published.generation)
    }

    // -------------------------------------------------------------- planFor

    @Test
    fun `端点相同就复用连接 不能关掉重建`() {
        val a = connectionOf("device-a")
        val plan = planFor(a, bookOf("device-a"))

        assertTrue("配置变更重跑 bootstrap 时必须命中复用", plan is ConnectionPlan.Reuse)
        assertSame(a, (plan as ConnectionPlan.Reuse).connection)
    }

    @Test
    fun `只改显示名也要复用 因为 label 不进 socket`() {
        val a = connectionOf("device-a")
        val book = CredentialBook(
            hosts = listOf(credentialOf("device-a").copy(label = "新名字")),
            activeHostId = "host-device-a",
        )

        val plan = planFor(a, book)

        assertTrue(plan is ConnectionPlan.Reuse)
        assertEquals("新名字", (plan as ConnectionPlan.Reuse).credential.label)
    }

    @Test
    fun `换设备必须重建`() {
        val a = connectionOf("device-a")

        val plan = planFor(a, bookOf("device-b"))

        assertTrue(plan is ConnectionPlan.Rebuild)
        assertEquals("device-b", (plan as ConnectionPlan.Rebuild).credential.deviceId)
    }

    @Test
    fun `同一设备换地址必须重建 这是 B004 之外的另一条漏网路径`() {
        val a = connectionOf("device-a", baseUrl = "http://127.0.0.1:1")
        val book = CredentialBook(
            hosts = listOf(credentialOf("device-a", baseUrl = "http://127.0.0.1:2")),
            activeHostId = "host-device-a",
        )

        assertTrue(planFor(a, book) is ConnectionPlan.Rebuild)
    }

    @Test
    fun `还没有连接时必须新建`() {
        assertTrue(planFor(null, bookOf("device-a")) is ConnectionPlan.Rebuild)
    }

    @Test
    fun `空簿必须丢掉连接`() {
        assertTrue(planFor(connectionOf("device-a"), CredentialBook.EMPTY) is ConnectionPlan.Drop)
    }

    @Test
    fun `簿里只剩不可用的凭证时也要丢掉连接`() {
        val broken = CredentialBook(
            hosts = listOf(DeviceCredential(baseUrl = "", deviceId = "", pairedAt = 0L, hostId = "h")),
            activeHostId = "h",
        )

        assertTrue("不可用的凭证不能拿去建连接", planFor(connectionOf("device-a"), broken) is ConnectionPlan.Drop)
    }

    @Test
    fun `EMPTY 快照必须是未配对形状`() {
        assertNull(ActiveLink.EMPTY.activeHost)
        assertNull(ActiveLink.EMPTY.connection)
        assertEquals("", ActiveLink.EMPTY.ownerId)
        assertNotNull(ActiveLink.EMPTY.scopeToken)
    }

    // ------------------------------------------------- 添加远程时的连接判据（B004 第六轮）

    @Test
    fun `重复添加当前正在用那台的地址 必须重建连接而不是沿用旧 deviceId`() {
        // 桌面端每次 pair/accept 都发新 deviceId；同一地址重新配对时 withUpsert 会把
        // 那一行刷成新 id。若 addHost 只是把旧连接原样带过，簿说新 id、连接拿旧 id，
        // 之后每个请求都 403 unpaired —— 与 B004 同形的缺陷。
        val old = connectionOf(deviceId = "old-id")
        val book = bookOf("old-id")
        assertTrue(planFor(old, book) is ConnectionPlan.Reuse)
        // 同一地址、新 deviceId（withUpsert 刷新后的那一行）
        val refreshed = CredentialBook(
            hosts = listOf(credentialOf(deviceId = "new-id")),
            activeHostId = "host-new-id",
        )
        assertTrue(
            "同一地址但 deviceId 换了，必须重建连接",
            planFor(old, refreshed) is ConnectionPlan.Rebuild,
        )
    }

    @Test
    fun `添加另一台地址时 连接必须原样复用 不能把当前连接关掉`() {
        val current = connectionOf(deviceId = "here")
        val book = bookOf("here")
        val withSecond = planHostAdd(book, credentialOf(deviceId = "there", baseUrl = "http://127.0.0.2:19387")).book
        val plan = planFor(current, withSecond)
        assertTrue("加第二台不该换连接", plan is ConnectionPlan.Reuse)
        assertSame(current, (plan as ConnectionPlan.Reuse).connection)
    }
}
