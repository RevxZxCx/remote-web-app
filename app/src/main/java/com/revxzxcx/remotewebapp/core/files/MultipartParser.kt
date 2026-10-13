package com.revxzxcx.remotewebapp.core.files

/**
 * 极简的 `multipart/form-data` 解析器。
 *
 * 为什么自己写：`workspaceFiles/readBytes` 成功时回的是
 * `Content-Type: multipart/form-data; boundary=----formdata-undici-…`，body 是
 * 一个（或几个）part，形如
 * ```
 * ------formdata-undici-089731638061\r\n
 * Content-Disposition: form-data; name="bytes-0"; filename="blob"\r\n
 * Content-Type: application/octet-stream\r\n
 * \r\n
 * <裸字节>\r\n
 * ------formdata-undici-089731638061--\r\n
 * ```
 * 只为了拿到第一个 part 的字节而引入 OkHttp 的 multipart 或多部分库并不划算，
 * 而且 OkHttp 的 `MultipartReader` 是内部 API。
 *
 * 本实现按**字节**切分（不能先转成 String：PNG/PDF 的内容会被破坏），
 * 并且只依赖 boundary 字面量，对 part 数量的增减保持宽容。
 */
internal object MultipartParser {

    /**
     * 从 `Content-Type` 头里取 `boundary`；取不到返回 null。
     *
     * 必须**逐参数**比对参数名，不能用 `indexOf("boundary=")` 找子串：那样
     * `multipart/form-data; x-boundary=zzz; boundary=real` 会先命中 `x-boundary=`，
     * 把假参数的值当成真 boundary（R005 审查发现，已加回归测试）。
     */
    fun boundaryOf(contentType: String): String? {
        // 允许 boundary 不加引号（实测）与加引号两种写法；分号后可能还有别的参数。
        for (part in contentType.split(';')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            if (!part.substring(0, eq).trim().equals("boundary", ignoreCase = true)) continue
            val value = part.substring(eq + 1).trim()
                .removeSurrounding("\"")
                .trim()
            return value.ifEmpty { null }
        }
        return null
    }

    /** 解析出的一个 part。 */
    data class Part(
        val name: String?,
        val filename: String?,
        val contentType: String?,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Part && name == other.name && filename == other.filename &&
                contentType == other.contentType && data.contentEquals(other.data))

        override fun hashCode(): Int =
            (((name?.hashCode() ?: 0) * 31 + (filename?.hashCode() ?: 0)) * 31 +
                (contentType?.hashCode() ?: 0)) * 31 + data.contentHashCode()
    }

    /**
     * 解析所有 part。
     *
     * @param contentType 响应头里的 `Content-Type`，用于取 boundary。
     * @param body 原始响应字节。
     * @return 解析出的 part；boundary 缺失或格式不对时返回空列表（调用方决定怎么报错）。
     */
    fun parse(contentType: String, body: ByteArray): List<Part> {
        val boundary = boundaryOf(contentType) ?: return emptyList()
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val positions = indexOfAll(body, delimiter)
        if (positions.isEmpty()) return emptyList()

        val parts = ArrayList<Part>()
        for (i in positions.indices) {
            val start = positions[i] + delimiter.size
            // 结束标记是 `--boundary--`。
            if (i == positions.size - 1 && startsWith(body, start, "--".toByteArray(Charsets.ISO_8859_1))) break

            val headerEnd = indexOf(body, CRLF + CRLF, start)
            if (headerEnd < 0) break
            val next = positions.getOrNull(i + 1) ?: break
            // part 内容到下一个边界之前，但要去掉紧邻边界的那个 CRLF。
            var contentEnd = next
            if (contentEnd >= 2 && body[contentEnd - 2] == CR && body[contentEnd - 1] == LF) {
                contentEnd -= 2
            }
            if (contentEnd < headerEnd + 4) contentEnd = headerEnd + 4

            val headerText = String(body, start, headerEnd - start, Charsets.ISO_8859_1)
            val headers = parseHeaders(headerText)
            val disposition = headers["content-disposition"].orEmpty()
            parts += Part(
                name = dispositionParam(disposition, "name"),
                filename = dispositionParam(disposition, "filename"),
                contentType = headers["content-type"],
                data = body.copyOfRange(headerEnd + 4, contentEnd),
            )
        }
        return parts
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private val CRLF = byteArrayOf(CR, LF)

    private fun parseHeaders(block: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in block.split("\r\n")) {
            val at = line.indexOf(':')
            if (at <= 0) continue
            out[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }
        return out
    }

    private fun dispositionParam(disposition: String, key: String): String? =
        Regex("""(?:^|;)\s*$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.get(1)

    private fun indexOfAll(haystack: ByteArray, needle: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = indexOf(haystack, needle, from)
            if (at < 0) return out
            out += at
            from = at + needle.size
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from.coerceAtMost(haystack.size)
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun startsWith(haystack: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at + needle.size > haystack.size) return false
        for (j in needle.indices) if (haystack[at + j] != needle[j]) return false
        return true
    }
}

/**
 * 自动探测文件大类，供文件查看页选择渲染方式。
 *
 * 规则对齐桌面端的 documentpreview 类型矩阵（`dsh-client-ui-sidebar-documentpreview/README.zh.md`）：
 * Markdown、代码、纯文本走分页文本；图片与 PDF 走整块字节；其余兜底纯文本。
 */
enum class FileKind {
    MARKDOWN,
    CODE,
    TEXT,
    IMAGE,
    PDF,
    BINARY,
    ;

    companion object {

        /** 按扩展名判断；未知扩展名归 [TEXT]（服务端能回文本就当文本）。 */
        fun of(path: String): FileKind {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "md", "markdown", "mdown" -> MARKDOWN
                "png", "jpg", "jpeg", "webp", "gif", "bmp" -> IMAGE
                "pdf" -> PDF
                in CODE_EXTENSIONS -> CODE
                "txt", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "properties" -> TEXT
                "" -> TEXT
                else -> TEXT
            }
        }

        /** 高亮用的语言名（自绘 token 化器按它选择规则）。 */
        fun languageOf(path: String): String? {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            return when (name.substringAfterLast('.', "").lowercase()) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "js", "mjs", "cjs" -> "javascript"
                "ts", "tsx" -> "typescript"
                "py" -> "python"
                "sh", "bash" -> "bash"
                "ps1" -> "powershell"
                "json" -> "json"
                "xml", "html", "htm" -> "xml"
                "css" -> "css"
                "yaml", "yml" -> "yaml"
                "toml" -> "toml"
                "gradle" -> "groovy"
                "rs" -> "rust"
                "go" -> "go"
                "c", "h" -> "c"
                "cpp", "cc", "hpp" -> "cpp"
                "sql" -> "sql"
                else -> null
            }
        }

        private val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "py", "sh", "bash",
            "ps1", "css", "scss", "rs", "go", "c", "h", "cpp", "cc", "hpp", "sql",
            "gradle", "pro", "sql", "lua", "rb", "php", "swift", "dart",
        )
    }
}
