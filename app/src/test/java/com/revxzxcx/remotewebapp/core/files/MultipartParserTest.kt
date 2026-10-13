package com.revxzxcx.remotewebapp.core.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MultipartParser] 的单测。
 *
 * 这个解析器处理的是 `workspaceFiles/readBytes` **成功**时的响应体：
 * `Content-Type: multipart/form-data; boundary=----formdata-undici-…`，body 里按字节切出 part。
 *
 * 为什么要写得这么啰嗦：只要有人在里面插一次「先转 String 再转回来」，PNG/PDF 的内容就会
 * 被静默破坏，而 UI 上表现为「图片打不开」——非常难查。所以这里所有 payload 断言都用
 * [assertArrayEquals] 逐字节比对，而不是字符串相等。
 *
 * 形状来自真机探针（见 `MultipartParser.kt` 顶部注释与 `测试记录.md` T016）：
 * ```
 * ------formdata-undici-001661913666\r\n
 * Content-Disposition: form-data; name="bytes-0"; filename="blob"\r\n
 * Content-Type: application/octet-stream\r\n
 * \r\n
 * <裸字节>\r\n
 * ------formdata-undici-001661913666--\r\n
 * ```
 * 并且实测响应后面还可能跟一段 JSON 元数据 part，所以两段的情形必须也能解析。
 */
class MultipartParserTest {

    private val boundary = "----formdata-undici-001661913666"
    private val contentType = "multipart/form-data; boundary=$boundary"

    private fun latin1(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** 真实服务端给的 part 头，逐字节照抄。 */
    private fun partHead(
        name: String,
        filename: String? = "blob",
        partContentType: String? = "application/octet-stream",
    ): ByteArray {
        val head = StringBuilder()
        head.append("--").append(boundary).append("\r\n")
        head.append("Content-Disposition: form-data; name=\"").append(name).append('"')
        if (filename != null) head.append("; filename=\"").append(filename).append('"')
        head.append("\r\n")
        if (partContentType != null) head.append("Content-Type: ").append(partContentType).append("\r\n")
        head.append("\r\n")
        return latin1(head.toString())
    }

    /** 一个完整 part：头 + 裸字节 + 紧邻下一个边界的 CRLF。 */
    private fun part(
        name: String,
        payload: ByteArray,
        filename: String? = "blob",
        partContentType: String? = "application/octet-stream",
    ): ByteArray = partHead(name, filename, partContentType) + payload + latin1("\r\n")

    private fun closing(): ByteArray = latin1("--$boundary--\r\n")

    private fun bodyOf(vararg sections: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (section in sections) out += section
        return out
    }

    // ------------------------------------------------------------------ part 解析

    @Test
    fun `解析真实单 part 形状`() {
        val payload = "const a = 1;\n".toByteArray(Charsets.UTF_8)
        val parts = MultipartParser.parse(contentType, bodyOf(part("bytes-0", payload), closing()))

        assertEquals(1, parts.size)
        val only = parts.single()
        assertEquals("bytes-0", only.name)
        assertEquals("blob", only.filename)
        assertEquals("application/octet-stream", only.contentType)
        assertArrayEquals(payload, only.data)
    }

    @Test
    fun `PNG 魔数字节原样保留不被当成字符串解码`() {
        // 0x89 不是合法 ASCII/UTF-8 首字节：任何「先 String 再 ByteArray」的实现都会在这里变形。
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        val data = MultipartParser.parse(contentType, bodyOf(part("bytes-0", png), closing())).single().data

        assertArrayEquals(png, data)
        assertEquals(0x89, data[0].toInt() and 0xFF)
        assertEquals(0x0D, data[4].toInt() and 0xFF)
        assertEquals(0x1A, data[6].toInt() and 0xFF)
    }

    @Test
    fun `UTF-8 续字节原样保留`() {
        val payload = "café".toByteArray(Charsets.UTF_8) // é = 0xC3 0xA9
        val data = MultipartParser.parse(contentType, bodyOf(part("bytes-0", payload), closing())).single().data

        assertArrayEquals(payload, data)
        assertEquals("é 的第一个字节", 0xC3, data[3].toInt() and 0xFF)
        assertEquals("é 的续字节", 0xA9, data[4].toInt() and 0xFF)
    }

    @Test
    fun `payload 内部的 CRLF 不被吃掉`() {
        // 只应剥掉「紧邻下一个边界」的那一个 CRLF，part 内容里的 CRLF 属于文件本身。
        val payload = latin1("line1\r\nline2\r\nline3")
        val data = MultipartParser.parse(contentType, bodyOf(part("bytes-0", payload), closing())).single().data

        assertArrayEquals(payload, data)
        assertEquals(19, data.size)
    }

    @Test
    fun `二进制 part 后跟 JSON 元数据 part 时两段都能取到`() {
        // 实测响应可能是「裸字节 + 尾部 JSON 元数据」两段；调用方目前只取第一段，
        // 但解析器必须两段都吐出来，否则将来要看元数据就得重写。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val meta = """{"bytes":8,"eof":true}"""
        val body = bodyOf(
            part("bytes-0", png),
            part("metadata", meta.toByteArray(Charsets.UTF_8), filename = null, partContentType = "application/json"),
            closing(),
        )

        val parts = MultipartParser.parse(contentType, body)

        assertEquals(2, parts.size)
        assertArrayEquals(png, parts[0].data)
        assertEquals("bytes-0", parts[0].name)
        assertEquals("metadata", parts[1].name)
        assertNull("没有 filename 参数时应当为 null", parts[1].filename)
        assertEquals("application/json", parts[1].contentType)
        assertEquals(meta, String(parts[1].data, Charsets.UTF_8))
    }

    @Test
    fun `空 part 返回空字节数组而不是 null`() {
        val only = MultipartParser.parse(contentType, bodyOf(part("bytes-0", ByteArray(0)), closing())).single()

        assertEquals("bytes-0", only.name)
        assertEquals(0, only.data.size)
        assertArrayEquals(ByteArray(0), only.data)
    }

    @Test
    fun `缺 Content-Disposition 时 name 与 filename 为 null 但字节仍保留`() {
        val payload = byteArrayOf(9, 8, 7)
        val body = bodyOf(
            latin1("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n") + payload + latin1("\r\n"),
            closing(),
        )

        val only = MultipartParser.parse(contentType, body).single()

        assertNull(only.name)
        assertNull(only.filename)
        assertEquals("application/octet-stream", only.contentType)
        assertArrayEquals(payload, only.data)
    }

    // ------------------------------------------------------------------ 降级路径

    @Test
    fun `contentType 里没有 boundary 时优雅降级为空列表`() {
        val body = bodyOf(part("bytes-0", byteArrayOf(1, 2, 3)), closing())

        // 实际行为：boundary 取不到时直接返回空列表，由调用方决定怎么报错（不抛异常）。
        assertTrue(MultipartParser.parse("application/octet-stream", body).isEmpty())
        assertTrue(MultipartParser.parse("multipart/form-data", body).isEmpty())
        assertTrue(MultipartParser.parse("", body).isEmpty())
        // `boundary=` 后面什么都没有，等同于取不到。
        assertTrue(MultipartParser.parse("multipart/form-data; boundary=", body).isEmpty())
        // 注意（跨文件的风险点，本文件无法断言）：WorkspaceFileRepository.readBytes 在
        // parts 为空时会回退成 `response.bytes`，也就是把整个 multipart body（含分隔符与头）
        // 当成文件内容返回。
    }

    @Test
    fun `没有结束分隔符时返回空列表`() {
        // body 被截断：只有起始边界、没有 `--boundary--`。
        val body = bodyOf(part("bytes-0", "abc".toByteArray(Charsets.UTF_8)))

        assertTrue(MultipartParser.parse(contentType, body).isEmpty())
    }

    @Test
    fun `body 里完全没有边界时返回空列表`() {
        assertTrue(MultipartParser.parse(contentType, "hello".toByteArray()).isEmpty())
        assertTrue(MultipartParser.parse(contentType, ByteArray(0)).isEmpty())
    }

    // ------------------------------------------------------------------ boundaryOf

    @Test
    fun `带引号的 boundary 能解析`() {
        assertEquals("----x", MultipartParser.boundaryOf("multipart/form-data; boundary=\"----x\""))
        // 引号后面还有别的参数时也要正确截断。
        assertEquals("----x", MultipartParser.boundaryOf("multipart/form-data; boundary=\"----x\"; charset=utf-8"))
    }

    @Test
    fun `带引号的 boundary 能端到端解析出字节`() {
        val quoted = "multipart/form-data; boundary=\"----x\""
        val payload = byteArrayOf(1, 2, 3)
        val body = latin1("------x\r\nContent-Disposition: form-data; name=\"bytes-0\"\r\n\r\n") +
            payload + latin1("\r\n------x--\r\n")

        assertArrayEquals(payload, MultipartParser.parse(quoted, body).single().data)
    }

    @Test
    fun `boundary 大小写不敏感且忽略后续参数`() {
        assertEquals("----x", MultipartParser.boundaryOf("multipart/form-data; BOUNDARY=----x; charset=utf-8"))
        assertNull(MultipartParser.boundaryOf("multipart/form-data"))
        assertNull(MultipartParser.boundaryOf(""))
    }

    @Test
    fun `boundary 必须逐参数比对参数名而不是找子串`() {
        // R005 审查发现并修复：用 indexOf("boundary=") 找子串时，`x-boundary=` 也会命中，
        // 真正的 boundary 参数被忽略，导致后面按错误的 boundary 切分、解析出空列表。
        assertEquals(
            "real",
            MultipartParser.boundaryOf("multipart/form-data; x-boundary=zzz; boundary=real"),
        )
        // 假参数排在真参数之后同样不能得逞。
        assertEquals(
            "real",
            MultipartParser.boundaryOf("multipart/form-data; boundary=real; x-boundary=zzz"),
        )
        // 只有假参数时，宁可返回 null（拿不到 boundary → 空列表），也不要拿假的值。
        assertNull(MultipartParser.boundaryOf("multipart/form-data; x-boundary=zzz"))
        // 真参数被引号包住 + 前面有假参数。
        assertEquals(
            "----x",
            MultipartParser.boundaryOf("multipart/form-data; x-boundary=zzz; boundary=\"----x\""),
        )
    }

    @Test
    fun `假 boundary 参数不会破坏端到端解析`() {
        val contentType = "multipart/form-data; x-boundary=zzz; boundary=----x"
        val payload = byteArrayOf(9, 8, 7)
        val body = latin1("------x\r\nContent-Disposition: form-data; name=\"bytes-0\"\r\n\r\n") +
            payload + latin1("\r\n------x--\r\n")

        assertArrayEquals(payload, MultipartParser.parse(contentType, body).single().data)
    }

    @Test
    fun `boundary 参数名前后有空格或本来就没有等号时都安全`() {
        // 参数名两侧的空格要忽略；没有等号的段（例如只有 `multipart/form-data`）跳过。
        assertEquals("----x", MultipartParser.boundaryOf("multipart/form-data; boundary = ----x"))
        assertEquals("----x", MultipartParser.boundaryOf("multipart/form-data; boundary =\"----x\""))
        assertNull(MultipartParser.boundaryOf("multipart/form-data; charset=utf-8"))
    }
}
