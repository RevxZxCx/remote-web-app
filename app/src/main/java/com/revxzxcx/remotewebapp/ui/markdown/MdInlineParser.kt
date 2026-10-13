package com.revxzxcx.remotewebapp.ui.markdown

/**
 * 行内语法的解析（CommonMark 的一个够用子集 + GFM 删除线与自动链接）。
 *
 * 为什么手写而不是用正则一次性替换：行内语法是**递归**的
 * （`**加粗里还有 `代码`**`），而且必须对**未闭合**的定界符保持宽容——流式过程中
 * 文本永远处在「写了一半」的状态，一个没配对的 `**` 不该把后面的内容全吃掉。
 *
 * 本解析器不抛异常、不假设输入合法；任何认不出的东西都原样当文本。
 */
internal object MdInlineParser {

    /**
     * 解析一段行内文本。
     *
     * @param text 已经去掉块级标记的行内内容。
     * @param allowImages `![alt](src)` 是否解析成图片（表格/引用里也可以，保持一致）。
     */
    fun parse(text: String, allowImages: Boolean = true): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MdInline>()
        val buffer = StringBuilder()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdInline.Text(buffer.toString())
                buffer.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]

            // 转义：\* \+ 等一律当字面量。
            if (c == '\\' && i + 1 < text.length && isEscapable(text[i + 1])) {
                buffer.append(text[i + 1])
                i += 2
                continue
            }

            // 硬换行：行尾两个空格，或反斜杠换行。
            if (c == '\n') {
                val trailingSpaces = buffer.length >= 2 &&
                    buffer[buffer.length - 1] == ' ' && buffer[buffer.length - 2] == ' '
                if (trailingSpaces) {
                    while (buffer.isNotEmpty() && buffer.last() == ' ') buffer.deleteCharAt(buffer.length - 1)
                    flush()
                    out += MdInline.LineBreak
                } else {
                    buffer.append(' ')
                }
                i++
                continue
            }

            // 行内代码：定界符长度可变（``x``），内容不解析其它语法。
            if (c == '`') {
                val fenceLen = runLength(text, i, '`')
                val close = findClosingRun(text, i + fenceLen, '`', fenceLen)
                if (close >= 0) {
                    val raw = text.substring(i + fenceLen, close)
                    // CommonMark：内容首尾各去掉一个空格（` `` ` → ``）
                    val code = if (raw.length >= 2 && raw.first() == ' ' && raw.last() == ' ') {
                        raw.substring(1, raw.length - 1)
                    } else {
                        raw
                    }
                    flush()
                    out += MdInline.Code(code.replace('\n', ' '))
                    i = close + fenceLen
                    continue
                }
                // 未闭合：当作字面量（流式里很常见）。
                buffer.append(text, i, minOf(i + fenceLen, text.length))
                i += fenceLen
                continue
            }

            // 图片：![alt](src)
            if (allowImages && c == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val parsed = parseLink(text, i + 1)
                if (parsed != null) {
                    flush()
                    out += MdInline.Image(parsed.label, parsed.href)
                    i = parsed.end
                    continue
                }
            }

            // 链接：[文字](地址)
            if (c == '[') {
                val parsed = parseLink(text, i)
                if (parsed != null) {
                    flush()
                    out += MdInline.Link(parse(parsed.label, allowImages), parsed.href)
                    i = parsed.end
                    continue
                }
            }

            // 强调定界符：** __ * _ ~~
            val delims = emphasisAt(text, i)
            if (delims != null) {
                val inner = findEmphasisInner(text, i, delims)
                if (inner != null) {
                    flush()
                    val children = parse(inner.first, allowImages)
                    out += when (delims) {
                        Delims.Strong -> MdInline.Strong(children)
                        Delims.Emphasis -> MdInline.Emphasis(children)
                        Delims.Strike -> MdInline.Strikethrough(children)
                    }
                    i = inner.second
                    continue
                }
            }

            // GFM 自动链接：裸 URL 与裸邮箱。
            if (c == 'h' && (text.startsWith("http://", i) || text.startsWith("https://", i))) {
                val end = urlEnd(text, i)
                if (end > i) {
                    flush()
                    val url = text.substring(i, end)
                    out += MdInline.Link(listOf(MdInline.Text(url)), url)
                    i = end
                    continue
                }
            }

            buffer.append(c)
            i++
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------ 强调

    private enum class Delims { Strong, Emphasis, Strike }

    private fun emphasisAt(text: String, at: Int): Delims? = when {
        text.startsWith("~~", at) -> Delims.Strike
        text.startsWith("**", at) -> Delims.Strong
        text.startsWith("__", at) -> Delims.Strong
        text[at] == '*' -> Delims.Emphasis
        text[at] == '_' -> Delims.Emphasis
        else -> null
    }

    private fun markerOf(set: Delims): String = when (set) {
        Delims.Strong -> "**"
        Delims.Emphasis -> "*"
        Delims.Strike -> "~~"
    }

    /**
     * 找到与 [at] 处定界符配对的收尾，返回 `(内容, 结束后的下标)`；找不到返回 null。
     *
     * `__` / `_` 只在词边界起作用（`snake_case_name` 不该变成斜体），这是 CommonMark 的
     * 「强调的左右侧约束」里最实用的一条子集。
     */
    private fun findEmphasisInner(text: String, at: Int, set: Delims): Pair<String, Int>? {
        // `_` 开头要求左边界是空或非字母数字（否则是单词内部的普通字符）。
        if ((set == Delims.Emphasis || set == Delims.Strong) && text[at] == '_') {
            if (at > 0 && text[at - 1].isLetterOrDigit()) return null
        }
        val marker = when (set) {
            Delims.Strong -> if (text.startsWith("__", at)) "__" else "**"
            Delims.Emphasis -> if (text[at] == '_') "_" else "*"
            Delims.Strike -> "~~"
        }
        var search = at + marker.length
        val end = text.length - marker.length
        while (search <= end) {
            val hit = text.indexOf(marker, search)
            if (hit < 0) return null
            // 内容不为空且收尾前一个字符不是空白（`** **` 不构成强调）。
            if (hit > at + marker.length && !text[hit - 1].isWhitespace()) {
                // `_` 收尾要求右边界是尽头或非字母数字。
                val next = hit + marker.length
                val rightOk = marker != "_" || next >= text.length || !text[next].isLetterOrDigit()
                if (rightOk) return text.substring(at + marker.length, hit) to next
            }
            search = hit + marker.length
        }
        return null
    }

    // ------------------------------------------------------------------ 链接

    private data class ParsedLink(val label: String, val href: String, val end: Int)

    /** 从 [at] 处的 `[` 解析 `[label](href)`；不成形返回 null。 */
    private fun parseLink(text: String, at: Int): ParsedLink? {
        if (at >= text.length || text[at] != '[') return null
        // 标签里允许嵌套的方括号（一层即可，够用）。
        var depth = 1
        var i = at + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        val label = text.substring(at + 1, i - 1)
        if (i >= text.length || text[i] != '(') return null

        var paren = 1
        var j = i + 1
        while (j < text.length && paren > 0) {
            when (text[j]) {
                '\\' -> j++
                '(' -> paren++
                ')' -> paren--
            }
            j++
        }
        if (paren != 0) return null
        val target = text.substring(i + 1, j - 1).trim()
        // `[x](url "标题")` —— 去掉标题部分。
        val href = target.split(Regex("""\s+(?=["'])"""), limit = 2).first()
            .removeSurrounding("<", ">")
            .trim()
        if (href.isEmpty()) return null
        return ParsedLink(label, href, j)
    }

    // ------------------------------------------------------------------ 小工具

    private fun runLength(text: String, at: Int, c: Char): Int {
        var n = 0
        while (at + n < text.length && text[at + n] == c) n++
        return n
    }

    /** 找长度至少为 [len] 的反引号连续串，返回其起始下标；找不到返回 -1。 */
    private fun findClosingRun(text: String, from: Int, c: Char, len: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == c) {
                val n = runLength(text, i, c)
                if (n == len) return i
                i += n
            } else {
                i++
            }
        }
        return -1
    }

    private fun urlEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isWhitespace() && text[i] != '<' && text[i] != '>' && text[i] != '"') i++
        // 去掉常见的尾随标点（`见 https://x.com。` 里的句号不该进链接）。
        while (i > from && text[i - 1] in ".,;:!?)]}。，、；：！？）】") i--
        return i
    }

    private fun isEscapable(c: Char): Boolean =
        c in "\\`*_{}[]()#+-.!|>~\"'"
}
