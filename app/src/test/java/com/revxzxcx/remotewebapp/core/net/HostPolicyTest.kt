package com.revxzxcx.remotewebapp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostPolicy] 的单测。
 *
 * 判定规则必须与插件侧 `isPrivateOrLocalHostname`（`src/routes.ts:120-145`）一致：
 * 放行了一个桌面端不认的地址，用户只会看到一个语焉不详的 403。
 */
class HostPolicyTest {

    @Test
    fun allowsHttpsAnywhere() {
        assertTrue(HostPolicy.isAllowed("https://5b4551427f0ab7aa.dsh-market.com"))
        assertTrue(HostPolicy.isAllowed("https://example.com:8443"))
    }

    @Test
    fun allowsCleartextOnPrivateRanges() {
        assertTrue(HostPolicy.isAllowed("http://192.168.1.5:19387"))
        assertTrue(HostPolicy.isAllowed("http://10.0.0.7"))
        assertTrue(HostPolicy.isAllowed("http://172.16.3.9:19387"))
        assertTrue(HostPolicy.isAllowed("http://172.31.255.254"))
        assertTrue(HostPolicy.isAllowed("http://127.0.0.1:19387"))
        assertTrue(HostPolicy.isAllowed("http://localhost:19387"))
        assertTrue(HostPolicy.isAllowed("http://169.254.1.1"))
    }

    @Test
    fun allowsPrivateSuffixHostnames() {
        assertTrue(HostPolicy.isAllowed("http://dsh.local"))
        assertTrue(HostPolicy.isAllowed("http://box.lan"))
        assertTrue(HostPolicy.isAllowed("http://host.internal"))
        assertTrue(HostPolicy.isAllowed("http://host.home.arpa"))
    }

    @Test
    fun rejectsCleartextOnPublicHost() {
        val reason = HostPolicy.rejectionReason("http://example.com")
        assertTrue(reason != null && reason.contains("局域网"))
        assertFalse(HostPolicy.isAllowed("http://8.8.8.8"))
        assertFalse(HostPolicy.isAllowed("http://172.32.0.1"))
        assertFalse(HostPolicy.isAllowed("http://172.15.0.1"))
        assertFalse(HostPolicy.isAllowed("http://192.169.1.1"))
    }

    @Test
    fun rejectsUnsupportedSchemeAndGarbage() {
        assertTrue(HostPolicy.rejectionReason("ftp://192.168.1.5")!!.contains("http"))
        assertTrue(HostPolicy.rejectionReason("not a url") != null)
        assertTrue(HostPolicy.rejectionReason("") != null)
    }

    @Test
    fun privateHostnameMatcherMirrorsPluginRules() {
        assertTrue(HostPolicy.isPrivateOrLocalHostname("LOCALHOST"))
        assertTrue(HostPolicy.isPrivateOrLocalHostname("[::1]"))
        assertTrue(HostPolicy.isPrivateOrLocalHostname("fd00::1"))
        assertTrue(HostPolicy.isPrivateOrLocalHostname("fe80::1"))
        assertTrue(HostPolicy.isPrivateOrLocalHostname("fc00::1"))
        assertFalse(HostPolicy.isPrivateOrLocalHostname("fe00::1"))
        assertFalse(HostPolicy.isPrivateOrLocalHostname("8.8.8.8"))
        assertFalse(HostPolicy.isPrivateOrLocalHostname(""))
        assertFalse(HostPolicy.isPrivateOrLocalHostname("192.168.1"))
        assertFalse(HostPolicy.isPrivateOrLocalHostname("999.1.1.1"))
    }

    @Test
    fun rejectionReasonIsNullWhenAllowed() {
        assertNull(HostPolicy.rejectionReason("https://example.com"))
        assertNull(HostPolicy.rejectionReason("http://192.168.1.5:19387"))
    }
}
