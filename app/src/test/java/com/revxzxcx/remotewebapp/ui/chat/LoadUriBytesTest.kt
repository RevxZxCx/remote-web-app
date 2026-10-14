package com.revxzxcx.remotewebapp.ui.chat

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [loadUriBytes] 的单测（纯 JVM，不需要 Robolectric）。
 *
 * 为什么单独测它：这段代码跑在**选择器回调**里，也就是主线程。它一旦抛异常，
 * 用户看到的是进程崩溃而不是「这个文件读不出来」。所以它的契约是
 * 「任何失败都返回 null」，这里逐条钉死。
 *
 * 写成接受 `() -> InputStream?` 而不是 `Context` + `Uri`，就是为了这一刻——
 * ContentResolver 的失败模式（ SecurityException / FileNotFoundException /
 * 跨提供方不可读）在测试里没法真实复现，而「open 抛了怎么办」恰恰是要保证的那条。
 */
class LoadUriBytesTest {

    @Test
    fun `正常读出字节与类型和名字`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val loaded = loadUriBytes(
            open = { ByteArrayInputStream(bytes) },
            mediaType = "image/png",
            displayName = "shot.png",
        )
        assertEquals(Triple(bytes.toList(), "image/png", "shot.png"), loaded?.let { Triple(it.first.toList(), it.second, it.third) })
    }

    @Test
    fun `open 返回 null 时整体失败`() = runTest {
        assertNull(loadUriBytes(open = { null }, mediaType = "image/png", displayName = "x"))
    }

    @Test
    fun `open 抛异常时整体失败而不是崩进程`() = runTest {
        val loaded = loadUriBytes(
            open = { throw java.io.FileNotFoundException("gone") },
            mediaType = "image/png",
            displayName = "x",
        )
        assertNull(loaded)
    }

    @Test
    fun `读流过程中抛异常也整体失败`() = runTest {
        val loaded = loadUriBytes(
            open = {
                object : InputStream() {
                    override fun read(): Int = throw java.io.IOException("boom")
                }
            },
            mediaType = "image/png",
            displayName = "x",
        )
        assertNull(loaded)
    }

    @Test
    fun `空文件当作失败`() = runTest {
        // 0 字节的「图片」选了也没意义，早失败早提示。
        assertNull(loadUriBytes({ ByteArrayInputStream(ByteArray(0)) }, "image/png", "empty.png"))
    }

    @Test
    fun `MIME 取不到时退回通用二进制类型`() = runTest {
        val loaded = loadUriBytes({ ByteArrayInputStream(byteArrayOf(9)) }, mediaType = null, displayName = null)
        assertEquals("application/octet-stream", loaded?.second)
        assertNull(loaded?.third)
    }
}
