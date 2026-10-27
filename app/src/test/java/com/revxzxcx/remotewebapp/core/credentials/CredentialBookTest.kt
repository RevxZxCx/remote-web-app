package com.revxzxcx.remotewebapp.core.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证簿（R054 多主机）的纯函数行为。
 *
 * 这一层是**整本凭证的唯一真相**：改名、改地址、切换、移除、旧版迁移全在这里算，
 * 上层（`AppContainer`）只负责读-改-写与串行化。所以这里的每条断言都对应一个
 * 「用户真做得出来的动作」，而不是内部实现细节。
 */
class CredentialBookTest {

    private fun cred(
        baseUrl: String = "http://192.168.1.5:19387",
        deviceId: String = "dev-1",
        label: String = "",
        hostId: String = "",
        pairedAt: Long = 1_700_000_000_000L,
    ) = DeviceCredential(
        baseUrl = baseUrl,
        deviceId = deviceId,
        pairedAt = pairedAt,
        label = label,
        hostId = hostId,
    )

    private var counter = 0

    /** 固定序列生成器，让断言能直接写出期望 id。 */
    private fun seq(): () -> String = { "id-${++counter}" }

    // ── active 的回退语义 ────────────────────────────────────────────────

    @Test
    fun `空簿没有激活主机`() {
        assertNull(CredentialBook.EMPTY.active)
    }

    @Test
    fun `activeHostId 悬空时回退到第一条而不是返回 null`() {
        // 悬空 id 只可能来自手改文件或旧版本脏数据，属异常态。
        // 这时让用户看见主机列表、自己选一个，比掉回配对页要好。
        val book = CredentialBook(
            hosts = listOf(cred(hostId = "a"), cred(deviceId = "dev-2", hostId = "b")),
            activeHostId = "已经不存在了",
        )
        assertEquals("a", book.active?.hostId)
    }

    @Test
    fun `activeHostId 为 null 时也用第一条`() {
        val book = CredentialBook(hosts = listOf(cred(hostId = "a")), activeHostId = null)
        assertEquals("a", book.active?.hostId)
    }

    @Test
    fun `activeHostId 命中时就用它`() {
        val book = CredentialBook(
            hosts = listOf(cred(hostId = "a"), cred(deviceId = "dev-2", hostId = "b")),
            activeHostId = "b",
        )
        assertEquals("b", book.active?.hostId)
    }

    // ── withUpsert ─────────────────────────────────────────────────────

    @Test
    fun `空簿里加入一条会成为激活`() {
        val book = CredentialBook.EMPTY.withUpsert(cred(), newHostId = seq())
        assertEquals(1, book.hosts.size)
        assertEquals("id-1", book.hosts[0].hostId)
        assertEquals("id-1", book.activeHostId)
    }

    @Test
    fun `同地址同设备再加一次沿用原 hostId 不产生重复行`() {
        // 用户在同一台桌面端上重新扫码：桌面端每次 pair/accept 都会发新 deviceId，
        // 但假如它重发了同一个 deviceId，那这就是同一台机器，不该多出一行。
        counter = 0
        val first = CredentialBook.EMPTY.withUpsert(cred(), newHostId = seq())
        val second = first.withUpsert(cred(label = "改过的名字"), newHostId = seq())
        assertEquals(1, second.hosts.size)
        assertEquals("id-1", second.hosts[0].hostId)
        assertEquals("改过的名字", second.hosts[0].label)
    }

    @Test
    fun `同地址换 deviceId 会新增一条`() {
        // 桌面端重新配对后旧 deviceId 已失效，这是另一条记录，不是更新。
        counter = 0
        val first = CredentialBook.EMPTY.withUpsert(cred(deviceId = "old"), newHostId = seq())
        val second = first.withUpsert(cred(deviceId = "new"), newHostId = seq())
        assertEquals(2, second.hosts.size)
        assertEquals(listOf("id-1", "id-2"), second.hosts.map { it.hostId })
    }

    @Test
    fun `带 hostId 的凭证按 hostId 就地替换且位置不变`() {
        // 编辑场景：调用方已经知道要改哪一条。
        // 位置必须保持——filterNot + 追加会把它挪到末尾，用户会看到「刚改过的那行跳走了」。
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
        val edited = book.withUpsert(
            cred(baseUrl = "http://10.0.0.9:19387", deviceId = "d1", hostId = "id-1"),
            newHostId = seq(),
        )
        assertEquals(2, edited.hosts.size)
        assertEquals("id-1", edited.hosts[0].hostId)
        assertEquals("http://10.0.0.9:19387", edited.hosts[0].baseUrl)
        assertEquals("id-2", edited.hosts[1].hostId)
    }

    @Test
    fun `makeActive=false 时不动当前激活`() {
        // 「添加远程」用的就是这条路径：新机器进列表，但别把用户正在用的那台抢走。
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(
                cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"),
                makeActive = false,
                newHostId = seq(),
            )
        assertEquals(2, book.hosts.size)
        assertEquals("id-1", book.activeHostId)
        assertEquals("d1", book.active?.deviceId)
    }

    @Test
    fun `makeActive=true 时新加的那条成为激活`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
        assertEquals("id-2", book.activeHostId)
    }

    // ── withActive ─────────────────────────────────────────────────────

    @Test
    fun `切到不存在的 id 原样返回`() {
        // 不制造指向空处的 activeHostId。
        counter = 0
        val book = CredentialBook.EMPTY.withUpsert(cred(), newHostId = seq())
        assertSame(book, book.withActive("nope"))
    }

    @Test
    fun `切到存在的 id 生效`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
            .withActive("id-1")
        assertEquals("id-1", book.activeHostId)
    }

    // ── withRemoved ────────────────────────────────────────────────────

    @Test
    fun `删掉激活的那条会把 activeHostId 清空并回退`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
            .withActive("id-1")
            .withRemoved("id-1")
        assertEquals(1, book.hosts.size)
        assertNull(book.activeHostId)
        assertEquals("id-2", book.active?.hostId)
    }

    @Test
    fun `删掉不是激活的那条时激活不变`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
            .withActive("id-2")
            .withRemoved("id-1")
        assertEquals("id-2", book.activeHostId)
        assertEquals(1, book.hosts.size)
    }

    @Test
    fun `删不存在的 id 原样返回`() {
        counter = 0
        val book = CredentialBook.EMPTY.withUpsert(cred(), newHostId = seq())
        assertSame(book, book.withRemoved("nope"))
    }

    @Test
    fun `删掉最后一条后 active 为 null`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(), newHostId = seq())
            .withRemoved("id-1")
        assertTrue(book.hosts.isEmpty())
        assertNull(book.active)
    }

    // ── withRenamed / withEndpoint ─────────────────────────────────────

    @Test
    fun `改名会 trim 且不动 deviceId 与 baseUrl`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(), newHostId = seq())
            .withRenamed("id-1", "  家里的主机  ")
        assertEquals("家里的主机", book.hosts[0].label)
        assertEquals("dev-1", book.hosts[0].deviceId)
        assertEquals("http://192.168.1.5:19387", book.hosts[0].baseUrl)
    }

    @Test
    fun `改名为空等于清掉自定义名`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(label = "旧名字"), newHostId = seq())
            .withRenamed("id-1", "   ")
        assertEquals("", book.hosts[0].label)
        // displayName 回退到地址短形态。
        assertEquals("192.168.1.5:19387", book.hosts[0].displayName)
    }

    @Test
    fun `改地址会去掉末尾斜杠且不动 deviceId`() {
        // 换地址的典型场景是桌面端换了局域网 IP，「配对关系」本身没变。
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(), newHostId = seq())
            .withEndpoint("id-1", "http://10.1.2.3:19387/")
        assertEquals("http://10.1.2.3:19387", book.hosts[0].baseUrl)
        assertEquals("dev-1", book.hosts[0].deviceId)
    }

    @Test
    fun `改名改地址只影响目标那一条`() {
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "d1"), newHostId = seq())
            .withUpsert(cred(baseUrl = "http://10.0.0.2:19387", deviceId = "d2"), newHostId = seq())
            .withRenamed("id-2", "公司服务器")
            .withEndpoint("id-2", "https://office.example.com")
        assertEquals("", book.hosts[0].label)
        assertEquals("http://192.168.1.5:19387", book.hosts[0].baseUrl)
        assertEquals("公司服务器", book.hosts[1].label)
        assertEquals("https://office.example.com", book.hosts[1].baseUrl)
    }

    // ── 迁移与 hostId ───────────────────────────────────────────────────

    @Test
    fun `ofSingle 给旧凭证补上 id 并设为激活`() {
        val book = CredentialBook.ofSingle(cred(), newHostId = { "migrated" })
        assertEquals(1, book.hosts.size)
        assertEquals("migrated", book.hosts[0].hostId)
        assertEquals("migrated", book.activeHostId)
    }

    @Test
    fun `ofSingle 不会覆盖已有的 hostId`() {
        val book = CredentialBook.ofSingle(cred(hostId = "already"), newHostId = { "ignored" })
        assertEquals("already", book.hosts[0].hostId)
        assertEquals("already", book.activeHostId)
    }

    @Test
    fun `ensureHostId 只给空的补`() {
        assertEquals("fresh", cred().ensureHostId { "fresh" }.hostId)
        assertEquals("keep", cred(hostId = "keep").ensureHostId { "ignored" }.hostId)
    }

    @Test
    fun `hostId 与 deviceId 是两回事`() {
        // 缓存分区按 ownerId(sha256(baseUrl+deviceId)) 算，身份按 hostId；
        // 这条用例是防止有人把两者合并（合并后「改地址」会变成「换了一台主机」）。
        counter = 0
        val book = CredentialBook.EMPTY
            .withUpsert(cred(deviceId = "dev-1"), newHostId = seq())
            .withEndpoint("id-1", "http://10.9.9.9:19387")
        assertNotEquals(book.hosts[0].deviceId, book.hosts[0].hostId)
        assertEquals("id-1", book.hosts[0].hostId)
    }

    // ── displayName / endpointLabel ────────────────────────────────────

    @Test
    fun `displayName 优先自定义名`() {
        assertEquals("我的机器", cred(label = "我的机器").displayName)
    }

    @Test
    fun `displayName 没有自定义名时回退地址并去掉协议`() {
        assertEquals("192.168.1.5:19387", cred(baseUrl = "http://192.168.1.5:19387").displayName)
        assertEquals(
            "office.example.com",
            cred(baseUrl = "https://office.example.com/").displayName,
        )
    }

    @Test
    fun `endpointLabel 保留端口`() {
        // 同一台机器可能用 LAN 地址和隧道域名各配一次；抹掉端口会让两行看起来一样。
        assertEquals("192.168.1.5:19387", cred(baseUrl = "http://192.168.1.5:19387").endpointLabel)
    }

    @Test
    fun `isUsable 挡掉半截数据`() {
        assertTrue(cred().isUsable)
        assertEquals(false, cred(baseUrl = "192.168.1.5:19387").isUsable)
        assertEquals(false, cred(deviceId = "").isUsable)
    }

    // ── isSameEndpoint：决定「旧连接能不能复用」 ─────────────────────────

    @Test
    fun `同端点即使改了名也算可复用`() {
        // 这条是 R054 最关键的回归：整体相等会让「改个显示名」触发重建连接，
        // 而重建会让 HomeViewModel 持有的旧流以「正常完成」结束、页面停在旧列表。
        val before = cred(label = "旧名字")
        val after = cred(label = "新名字")
        assertTrue(isSameEndpoint(before, after))
    }

    @Test
    fun `hostId 不同不影响端点判定`() {
        // 旧版单凭证迁移时补 hostId，那一下不该重建连接。
        val before = cred(hostId = "")
        val after = cred(hostId = "补上的")
        assertTrue(isSameEndpoint(before, after))
    }

    @Test
    fun `改地址必须算不同端点`() {
        // typert/mux/routes/attachments/uploads 都用构造参数建出来，换地址只能整条重建。
        assertEquals(false, isSameEndpoint(cred(), cred(baseUrl = "http://10.0.0.9:19387")))
    }

    @Test
    fun `换 deviceId 算不同端点`() {
        assertEquals(false, isSameEndpoint(cred(), cred(deviceId = "dev-2")))
    }

    @Test
    fun `任一侧为 null 都不复用`() {
        assertEquals(false, isSameEndpoint(null, cred()))
        assertEquals(false, isSameEndpoint(cred(), null))
        assertEquals(false, isSameEndpoint(null, null))
    }
}
