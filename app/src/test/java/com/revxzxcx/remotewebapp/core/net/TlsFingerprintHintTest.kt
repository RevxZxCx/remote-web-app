package com.revxzxcx.remotewebapp.core.net

import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TlsFingerprintHint] 的纯函数单测。
 *
 * 文案背后的实测事实见 docs/dev/测试记录.md T052：zcsb.ltd:38443 链路按 ClientHello
 * 指纹（长度/后量子组）丢弃握手包，Android Conscrypt 的 ~250B hello 必被 RST。
 */
class TlsFingerprintHintTest {

    @Test
    fun `裸 SocketException Connection reset 被识别为握手掐断`() {
        val e = SocketException("Connection reset")
        assertTrue(TlsFingerprintHint.isHandshakeReset(e))
    }

    @Test
    fun `SSLHandshakeException 被识别`() {
        val e = SSLHandshakeException("Handshake failed")
        assertTrue(TlsFingerprintHint.isHandshakeReset(e))
    }

    @Test
    fun `包装在 IOException 链里的 reset 也被识别`() {
        val root = SocketException("Connection reset")
        val wrapped = IOException("unexpected end of stream", root)
        assertTrue(TlsFingerprintHint.isHandshakeReset(wrapped))
    }

    @Test
    fun `普通超时不被误判`() {
        val e = java.net.SocketTimeoutException("connect timed out")
        assertFalse(TlsFingerprintHint.isHandshakeReset(e))
    }

    @Test
    fun `https 且握手 reset 时给出引导`() {
        val hint = TlsFingerprintHint.hintFor(
            "https://zcsb.ltd:38443",
            SocketException("Connection reset"),
        )
        assertTrue(hint != null)
        assertTrue(hint!!.contains("Tailscale Funnel"))
        assertTrue(hint.contains("浏览器"))
    }

    @Test
    fun `明文 http 不给这套引导`() {
        assertNull(
            TlsFingerprintHint.hintFor(
                "http://192.168.0.106:19387",
                SocketException("Connection reset"),
            ),
        )
    }

    @Test
    fun `非握手失败不追加引导`() {
        assertEquals(
            "连接不上 zcsb.ltd:38443：connect timed out",
            TlsFingerprintHint.augment(
                "https://zcsb.ltd:38443",
                "连接不上 zcsb.ltd:38443：connect timed out",
                java.net.SocketTimeoutException("connect timed out"),
            ),
        )
    }

    @Test
    fun `证书主机名不匹配不当作握手掐断`() {
        // 实测形态（2026-10-17，裸 IP 直连、证书无 IP SAN）：
        // SSLHandshakeException: No subject alternative names matching IP address 47.114.41.61 found
        val e = SSLHandshakeException(
            "No subject alternative names matching IP address 47.114.41.61 found",
        )
        e.initCause(
            java.security.cert.CertificateException(
                "No subject alternative names matching IP address 47.114.41.61 found",
            ),
        )
        assertFalse(TlsFingerprintHint.isHandshakeReset(e))
        assertNull(TlsFingerprintHint.hintFor("https://47.114.41.61:8888", e))
        assertEquals(
            "连接不上 47.114.41.61:8888：No subject alternative names matching IP address 47.114.41.61 found",
            TlsFingerprintHint.augment(
                "https://47.114.41.61:8888",
                "连接不上 47.114.41.61:8888：No subject alternative names matching IP address 47.114.41.61 found",
                e,
            ),
        )
    }

    @Test
    fun `SSLPeerUnverifiedException 不当作握手掐断`() {
        assertFalse(
            TlsFingerprintHint.isHandshakeReset(
                javax.net.ssl.SSLPeerUnverifiedException("Hostname 47.114.41.61 not verified"),
            ),
        )
    }

    @Test
    fun `augment 在适用时把引导拼在原消息后面`() {
        val out = TlsFingerprintHint.augment(
            "https://zcsb.ltd:38443",
            "连接不上 zcsb.ltd:38443：Connection reset",
            SocketException("Connection reset"),
        )
        assertTrue(out.startsWith("连接不上 zcsb.ltd:38443：Connection reset\n"))
        assertTrue(out.contains("TLS 握手在收到服务端回包前被网络掐断"))
    }
}
