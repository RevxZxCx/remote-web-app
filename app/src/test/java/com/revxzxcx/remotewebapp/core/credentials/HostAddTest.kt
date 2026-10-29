package com.revxzxcx.remotewebapp.core.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B005：重复添加同一台设备必须被判出来、并给出替换而不是新增。
 *
 * 这些断言都钉在 [planHostAdd] 上——它是 `AppContainer.addHost` 唯一的判断来源，
 * 容器层本身需要 `Context`、无法纯 JVM 测。
 */
class HostAddTest {

    private fun cred(
        baseUrl: String = "http://192.168.1.5:19387",
        deviceId: String = "dev-1",
        label: String = "",
        hostId: String = "",
    ) = DeviceCredential(
        baseUrl = baseUrl,
        deviceId = deviceId,
        label = label,
        hostId = hostId,
        pairedAt = 1_700_000_000_000L,
    )

    /**
     * 固定序列，让 `hostId` 可预测。
     *
     * ⚠️ 必须是**类级**计数器：早先写成 `planHostAdd(..., newHostId = seq())`，
     * 每次 `seq()` 都新建一个从 0 开始的计数器，于是第二台主机也拿到 `id-1`，
     * 与第一台撞号后在簿里就地替换——测试假红了一轮。生产用的是随机 UUID，不会撞，
     * 但测试必须显式保证唯一。
     */
    private var counter = 0

    private fun seq(): () -> String = { counter++; "id-$counter" }

    @Test
    fun `第一次添加不算重复`() {
        val plan = planHostAdd(CredentialBook.EMPTY, cred(), newHostId = seq())
        assertFalse(plan.added.alreadyListed)
        assertEquals("id-1", plan.added.credential.hostId)
    }

    @Test
    fun `同一个地址再加一次必须被判成重复`() {
        // 这就是用户反复点「开始配对」的现场：桌面端每次都发新 deviceId，
        // 按 deviceId 判等永远判不出重复。
        val first = planHostAdd(CredentialBook.EMPTY, cred(deviceId = "a"), newHostId = seq())
        val second = planHostAdd(first.book, cred(deviceId = "b"), newHostId = seq())
        assertTrue(second.added.alreadyListed)
    }

    @Test
    fun `重复添加只更新那一行 列表不会变长`() {
        val first = planHostAdd(CredentialBook.EMPTY, cred(deviceId = "a"), newHostId = seq())
        val second = planHostAdd(first.book, cred(deviceId = "b"), newHostId = seq())
        assertEquals(1, second.book.hosts.size)
        // 新 deviceId 必须写进去，否则那一行的鉴权会一直失败。
        assertEquals("b", second.book.hosts.single().deviceId)
        // hostId 沿用，设备栏里那一行的位置不变。
        assertEquals("id-1", second.book.hosts.single().hostId)
    }

    @Test
    fun `不同地址不算重复`() {
        val first = planHostAdd(CredentialBook.EMPTY, cred(baseUrl = "http://h:1"), newHostId = seq())
        val second = planHostAdd(first.book, cred(baseUrl = "http://h:2"), newHostId = seq())
        assertFalse(second.added.alreadyListed)
        assertEquals(2, second.book.hosts.size)
    }

    @Test
    fun `尾部斜杠不同依然算重复`() {
        // 手工登记走的是不经 normalizeBaseUrl 的路径，尾斜杠必须自己兜住。
        val first = planHostAdd(CredentialBook.EMPTY, cred(baseUrl = "http://h:1"), newHostId = seq())
        val second = planHostAdd(first.book, cred(baseUrl = "http://h:1/"), newHostId = seq())
        assertTrue(second.added.alreadyListed)
        assertEquals(1, second.book.hosts.size)
    }

    @Test
    fun `返回的是刚添加那台 而不是当前激活那台`() {
        // B005 第三层：早先返回 `next.active`，即「当前正在用那台」，而添加远程的契约
        // 恰恰是不改变当前主机——于是 addedHost 指向了旧主机，UI 会显示错名字。
        counter = 0
        val first = planHostAdd(CredentialBook.EMPTY, cred(baseUrl = "http://h:1", deviceId = "a"), newHostId = seq())
        val second = planHostAdd(first.book, cred(baseUrl = "http://h:2", deviceId = "b"), newHostId = seq())
        assertEquals("http://h:2", second.added.credential.baseUrl)
        // 当前激活还是第一台，添加远程不抢激活。
        assertEquals("http://h:1", second.book.active?.baseUrl)
    }

    @Test
    fun `添加远程不动激活`() {
        // 「不动激活」= activeHostId 一个字都不写。添加到一本空簿上也一样留空，
        // 由 `CredentialBook.active` 的回退规则（没有 activeHostId 就用第一条）兜住。
        val first = planHostAdd(CredentialBook.EMPTY, cred(baseUrl = "http://h:1"), newHostId = seq())
        val second = planHostAdd(first.book, cred(baseUrl = "http://h:2"), newHostId = seq())
        assertEquals(null, second.book.activeHostId)
        // 但 active 仍然指向先加入那台，不是刚加的这台。
        assertEquals("http://h:1", second.book.active?.baseUrl)
    }

    @Test
    fun `空簿里加一台 active 回退到这一台`() {
        val plan = planHostAdd(CredentialBook.EMPTY, cred(), newHostId = seq())
        assertEquals(plan.book.hosts.single().hostId, plan.book.active?.hostId)
    }

    @Test
    fun `重复添加时返回的也是刷新后的那台`() {
        val first = planHostAdd(CredentialBook.EMPTY, cred(deviceId = "a"), newHostId = seq())
        val second = planHostAdd(first.book, cred(deviceId = "b"), newHostId = seq())
        assertEquals("b", second.added.credential.deviceId)
        assertEquals("id-1", second.added.credential.hostId)
    }
}
