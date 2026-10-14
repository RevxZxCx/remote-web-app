package com.revxzxcx.remotewebapp.ui.markdown

/**
 * Markdown **块级**解析器：CommonMark 的一个够用子集 + GFM 表格 / 任务列表 / 删除线。
 *
 * 两个刻意的设计：
 *
 * 1. **永不抛异常**。流式生成时文本永远处于「写了一半」的状态（未闭合的围栏、
 *    只有一半的表格分隔行）。任何认不出的行都退化成段落，绝不中断渲染。
 * 2. **每个块都记录字符区间**（[MdBlock.startOffset] / [MdBlock.endOffset]）。
 *    这是增量渲染的基础：已经稳定（后面还有内容）的块可以冻结，只重解析尾部，
 *    避免每收到一个字就重排整段（桌面端 `MarkdownText` 的做法，见 `需求文档.md` §5 FR-5.2）。
 *    区间的确切坐标基准见 [MdBlock] 的注释——它是**归一化后**的文本坐标，
 *    且引用块内的子块在各自的局部坐标系里，只用于冻结比较，不能拿去切原文。
 *
 * 与桌面端一致的行为：**未闭合的围栏照常渲染成代码块**，而不是等到闭合才显示。
 */
object MdParser {

    /**
     * 引用块递归解析的嵌套上限（R005 P3 补遗）。
     *
     * 每层 `>` 只剥一个前缀，所以嵌套深度直接等于 `>` 的个数。没有上限时，一段带
     * 几千个 `>` 的文本会让解析（这里）与渲染（`MdView` 的 `MdBlock.Quote` 分支）
     * 双双递归到 `StackOverflowError`，而渲染递归发生在组合期 —— 直接崩进程。
     * 16 层已远超任何真实文档的需要。
     */
    private const val MAX_NESTING_DEPTH = 16

    /**
     * 解析整段 Markdown。
     *
     * @param text 已把 `\r\n` 归一化的文本。
     */
    fun parse(text: String): List<MdBlock> = parseBlocks(normalize(text), 0)

    /**
     * 供 [MdStream] 用的模块内入口：解析**已经归一化**的 [text]，区间按 [base] 平移到外部坐标。
     *
     * 与 [parse] 只差两点：不再做一次全文归一化（调用方已经做过，流式场景下每帧省一次
     * 全文字符串拷贝），以及接受 base 偏移。有了 base，MdStream 就不必先把块解析出来
     * 再整体 `shift` 一遍——那既是 O(块数) 的对象拷贝，也容易漏掉嵌套块。
     */
    internal fun parseNormalized(text: String, base: Int): List<MdBlock> = parseBlocks(text, base)

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * 解析 [text] 从 [base] 开始的一段为块序列。
     *
     * [base] 是这段文本在**外部原始文本**里的偏移，用于把区间换算回外部坐标
     * （引用块内部递归解析时要用）。
     */
    private fun parseBlocks(text: String, base: Int, depth: Int = 0): List<MdBlock> {
        val lines = LineIndex(text)
        val blocks = ArrayList<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines.text(i)

            // 空行跳过。
            if (line.isBlank()) {
                i++
                continue
            }

            // 围栏代码块（``` 或 ~~~，可带语言）。
            val fence = fenceInfo(line)
            if (fence != null) {
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val closing = fenceInfo(lines.text(j))
                    if (closing != null && closing.char == fence.char && closing.len >= fence.len &&
                        closing.info.isBlank()
                    ) {
                        closed = true
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines.text(j))
                    j++
                }
                val endLine = if (closed) j else lines.size - 1
                blocks += MdBlock.CodeBlock(
                    language = fence.info.ifBlank { null },
                    code = body.toString(),
                    closed = closed,
                    startOffset = lines.startOf(i) + base,
                    endOffset = lines.endOf(endLine) + base,
                )
                i = if (closed) j + 1 else lines.size
                continue
            }

            // 分割线（必须在列表之前判断：`---` 也可能是 setext 标题的下划线，这里只当分割线）。
            if (isRule(line)) {
                blocks += MdBlock.Rule(lines.startOf(i) + base, lines.endOf(i) + base)
                i++
                continue
            }

            // ATX 标题。
            val heading = headingOf(line)
            if (heading != null) {
                blocks += MdBlock.Heading(
                    level = heading.first,
                    inlines = MdInlineParser.parse(heading.second),
                    startOffset = lines.startOf(i) + base,
                    endOffset = lines.endOf(i) + base,
                )
                i++
                continue
            }

            // 引用块：连续的 `>` 行合起来递归解析。
            if (line.trimStart().startsWith(">")) {
                // 嵌套深度上限（R005 P3 补遗）：每层只剥一个 `>`，一段有成千上万个
                // `>` 前缀的文本会让解析与渲染一起递归到 StackOverflowError，而渲染
                // 递归发生在**组合期** —— 那不是崩溃某一行，是整个进程。
                // 超过上限就按普通段落渲染（内容照旧可见，只是不当作引用）。
                if (depth >= MAX_NESTING_DEPTH) {
                    val end = lines.endOf(i) + base
                    blocks += MdBlock.Paragraph(
                        inlines = MdInlineParser.parse(line.trimStart()),
                        startOffset = lines.startOf(i) + base,
                        endOffset = end,
                    )
                    i++
                    continue
                }
                val quoteLines = ArrayList<String>()
                val quoteStarts = ArrayList<Int>()
                var j = i
                while (j < lines.size) {
                    val raw = lines.text(j)
                    val trimmed = raw.trimStart()
                    if (!trimmed.startsWith(">")) break
                    quoteLines += trimmed.removePrefix(">").removePrefix(" ")
                    quoteStarts += lines.startOf(j)
                    j++
                }
                val innerText = quoteLines.joinToString("\n")
                blocks += MdBlock.Quote(
                    blocks = parseBlocks(innerText, lines.startOf(i) + base, depth + 1),
                    startOffset = lines.startOf(i) + base,
                    endOffset = lines.endOf(j - 1) + base,
                )
                i = j
                continue
            }

            // 表格：当前行含 `|` 且下一行是分隔行。
            if (i + 1 < lines.size && isTableDelimiter(lines.text(i + 1)) && line.contains('|')) {
                val table = parseTable(lines, i, base)
                if (table != null) {
                    blocks += table
                    i = tableEnd(lines, i)
                    continue
                }
            }

            // 列表（无序 / 有序）。
            val bullet = bulletOf(line)
            if (bullet != null) {
                i = parseList(lines, i, ordered = false, base = base, out = blocks)
                continue
            }
            val ordered = orderedOf(line)
            if (ordered != null) {
                i = parseList(lines, i, ordered = true, base = base, out = blocks)
                continue
            }

            // 段落：连续的非空、非块起始行。
            val paraStart = i
            val para = StringBuilder()
            while (i < lines.size) {
                val raw = lines.text(i)
                if (raw.isBlank() || isBlockStart(lines, i)) break
                if (para.isNotEmpty()) para.append('\n')
                para.append(raw.trim())
                i++
            }
            if (para.isNotEmpty()) {
                blocks += MdBlock.Paragraph(
                    inlines = MdInlineParser.parse(para.toString()),
                    startOffset = lines.startOf(paraStart) + base,
                    endOffset = lines.endOf(i - 1) + base,
                )
            }
        }
        return blocks
    }

    /** 判断第 [at] 行是否是某个块的开始（用于段落的终止条件）。 */
    private fun isBlockStart(lines: LineIndex, at: Int): Boolean {
        val line = lines.text(at)
        if (fenceInfo(line) != null) return true
        if (isRule(line)) return true
        if (headingOf(line) != null) return true
        if (line.trimStart().startsWith(">")) return true
        if (bulletOf(line) != null || orderedOf(line) != null) return true
        if (line.contains('|') && at + 1 < lines.size && isTableDelimiter(lines.text(at + 1))) return true
        return false
    }

    // ------------------------------------------------------------------ 列表

    private fun parseList(
        lines: LineIndex,
        from: Int,
        ordered: Boolean,
        base: Int,
        out: MutableList<MdBlock>,
    ): Int {
        val items = ArrayList<MdListItem>()
        val startNo = if (ordered) orderedOf(lines.text(from)) ?: 1 else 1
        var i = from

        while (i < lines.size) {
            val line = lines.text(i)
            val entry = if (ordered) orderedOf(line) else bulletOf(line)
            if (entry == null) break

            // 条目正文 = 第一行的剩余部分 + 后续缩进更深的续行。
            val text = StringBuilder(line.trimStart())
            text.setLength(0)
            text.append(line.trimStart().removePrefix(entryMarker(line, ordered)).trim())

            var j = i + 1
            while (j < lines.size) {
                val raw = lines.text(j)
                if (raw.isBlank()) {
                    // 空行后若下一行仍有更深缩进则继续这个条目，否则列表结束。
                    if (j + 1 < lines.size && indentOf(lines.text(j + 1)) > entryIndent(lines.text(i), ordered)) {
                        text.append('\n')
                        j++
                        continue
                    }
                    break
                }
                if (indentOf(raw) <= entryIndent(line, ordered)) break
                if (bulletOf(raw) != null || orderedOf(raw) != null) break
                text.append('\n').append(raw.trim())
                j++
            }

            val content = text.toString()
            val (checked, body) = taskOf(content)
            items += MdListItem(
                inlines = MdInlineParser.parse(body),
                checked = checked,
            )
            i = j
        }

        val block = if (ordered) {
            MdBlock.OrderedList(
                start = startNo,
                items = items,
                startOffset = lines.startOf(from) + base,
                endOffset = lines.endOf(maxOf(from, i - 1)) + base,
            )
        } else {
            MdBlock.BulletList(
                items = items,
                startOffset = lines.startOf(from) + base,
                endOffset = lines.endOf(maxOf(from, i - 1)) + base,
            )
        }
        out += block
        return i
    }

    private fun entryMarker(line: String, ordered: Boolean): String {
        val trimmed = line.trimStart()
        return if (ordered) {
            val at = trimmed.indexOfFirst { it == '.' || it == ')' }
            if (at >= 0) trimmed.substring(0, at + 1) else "-"
        } else {
            trimmed.take(1)
        }
    }

    private fun entryIndent(line: String, ordered: Boolean): Int {
        val marker = entryMarker(line, ordered)
        return indentOf(line) + marker.length + 1
    }

    /** `- [x] 文本` → `(true, "文本")`；非任务列表返回 `(null, 原文本)`。 */
    private fun taskOf(text: String): Pair<Boolean?, String> {
        val trimmed = text.trimStart()
        if (trimmed.length >= 3 && trimmed[0] == '[' && trimmed[2] == ']') {
            val mark = trimmed[1]
            if (mark == ' ' || mark == 'x' || mark == 'X') {
                return (mark != ' ') to trimmed.substring(3).trimStart()
            }
        }
        return null to text
    }

    private fun bulletOf(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.length >= 2 && (trimmed[0] == '-' || trimmed[0] == '*' || trimmed[0] == '+') &&
            trimmed[1] == ' '
        ) {
            return trimmed
        }
        return null
    }

    private fun orderedOf(line: String): Int? {
        val trimmed = line.trimStart()
        val at = trimmed.indexOfFirst { it == '.' || it == ')' }
        if (at <= 0 || at > 9) return null
        val digits = trimmed.substring(0, at)
        if (!digits.all { it.isDigit() }) return null
        if (at + 1 >= trimmed.length || trimmed[at + 1] != ' ') return null
        return digits.toIntOrNull() ?: 1
    }

    // ------------------------------------------------------------------ 表格

    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = splitRow(trimmed)
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim().trim(':')
            c.isNotEmpty() && c.all { it == '-' }
        }
    }

    private fun parseTable(lines: LineIndex, from: Int, base: Int): MdBlock.Table? {
        val headers = splitRow(lines.text(from)).map { MdInlineParser.parse(it.trim()) }
        var i = from + 2
        val rows = ArrayList<List<List<MdInline>>>()
        while (i < lines.size) {
            val raw = lines.text(i)
            if (raw.isBlank() || !raw.contains('|')) break
            rows += splitRow(raw).map { MdInlineParser.parse(it.trim()) }
            i++
        }
        if (headers.isEmpty()) return null
        val lastLine = if (rows.isEmpty()) from + 1 else i - 1
        return MdBlock.Table(
            headers = headers,
            rows = rows,
            startOffset = lines.startOf(from) + base,
            endOffset = lines.endOf(lastLine) + base,
        )
    }

    private fun tableEnd(lines: LineIndex, from: Int): Int {
        var i = from + 2
        while (i < lines.size && lines.text(i).isNotBlank() && lines.text(i).contains('|')) i++
        return i
    }

    /** 切一行表格单元；去掉首尾的裸 `|`，并把 `\|` 还原。 */
    private fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        val cells = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
                sb.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells += sb.toString()
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }

    // ------------------------------------------------------------------ 小块

    /**
     * 围栏行信息。
     *
     * **internal 是为了让 [MdStream] 复用同一套判定**：M5-10 修的真缺陷就是
     * `MdStream` 自己写了一份「只认裸围栏」的 `fenceAt`，于是 ```` ```kotlin ````
     * 这种带语言标签的开栏行被当成普通行，代码块内部的空行就被误判成安全块边界，
     * 冻结线切进代码块中间——尾部代码掉成段落、「生成中…」永驻。
     * 围栏判定只能有一份，否则两边必然漂移。
     */
    internal data class Fence(val char: Char, val len: Int, val info: String)

    internal fun fenceInfo(line: String): Fence? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return null
        val c = trimmed[0]
        if (c != '`' && c != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == c) n++
        if (n < 3) return null
        val info = trimmed.substring(n).trim()
        // 围栏的 info 里不能有反引号（CommonMark）。
        if (c == '`' && info.contains('`')) return null
        return Fence(c, n, info)
    }

    private fun headingOf(line: String): Pair<Int, String>? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        if (n > 6) return null
        if (n >= trimmed.length || trimmed[n] != ' ') return null
        // 去掉结尾的闭合 `#`。
        val body = trimmed.substring(n).trim().trimEnd('#').trimEnd()
        return n to body
    }

    private fun isRule(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val c = trimmed[0]
        if (c != '-' && c != '*' && c != '_') return false
        if (!trimmed.all { it == c || it == ' ' }) return false
        return trimmed.count { it == c } >= 3
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }
}

/**
 * 行索引：把文本切成行，并保留每行在原文里的区间。
 *
 * 之所以要这个而不是 `text.lines()`：块的区间必须能映射回原始文本（增量冻结、
 * 错误定位、以及「这段文字在原文的哪里」都依赖它）。
 */
private class LineIndex(private val text: String) {

    private val starts = ArrayList<Int>()
    private val ends = ArrayList<Int>()

    init {
        var i = 0
        while (i <= text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) {
                starts += i
                ends += text.length
                break
            }
            starts += i
            ends += nl
            i = nl + 1
        }
        if (starts.isEmpty()) {
            starts += 0
            ends += 0
        }
    }

    val size: Int get() = starts.size

    fun text(index: Int): String = text.substring(starts[index], ends[index])

    fun startOf(index: Int): Int = starts[index.coerceIn(0, starts.size - 1)]

    fun endOf(index: Int): Int = ends[index.coerceIn(0, ends.size - 1)]
}
