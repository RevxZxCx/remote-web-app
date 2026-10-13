package com.revxzxcx.remotewebapp.ui.markdown

/**
 * 流式 Markdown 的**增量解析**：冻结已稳定的前缀，只重解析尾部。
 *
 * 为什么必须做这件事（`需求文档.md` §5 FR-5.2）：助手回复是逐字到达的，如果每收到
 * 一个字就把整段 Markdown 重新解析一遍，代价是 O(n²)——一篇 2 万字的回复会在生成
 * 过程中重排上亿字符，手机上肉眼可见地卡。
 *
 * 桌面端 `MarkdownText` 的做法是「冻结除最后两块以外的全部块，只重解析尾部，未闭合的
 * 围栏交给同一套 GFM 语法」。本类把同一条策略落成纯 Kotlin：
 *
 * 1. 找**安全分割点**：最后一个「后面不可能再被追加内容改写」的行边界
 *    ——即不在围栏内的空行之后。没有安全点就整段重解析（短文本无所谓）。
 * 2. 分割点之前的部分一旦稳定就并入冻结区（只解析这一次，之后永久复用）。
 * 3. 只对分割点之后的尾部分调用 [MdParser.parse]。
 *
 * **不变量**：`update(text)` 的结果永远等于 `MdParser.parse(text)`。
 * 增量只是一个优化，不允许改变输出（单测直接断言这条）。
 *
 * **非线程安全**：每个正在流式的气泡持有自己的实例，只在 Compose 的组合线程上更新。
 */
class MdStream {

    private var frozenText: String = ""
    private var frozenBlocks: List<MdBlock> = emptyList()
    private var lastText: String? = null
    private var lastResult: List<MdBlock> = emptyList()

    /**
     * 用最新的全文内容刷新。
     *
     * @return 完整的块列表（冻结块 + 重解析的尾部），可直接交给 Compose 渲染。
     */
    fun update(text: String): List<MdBlock> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')

        // 内容没变（例如这一帧只更新了思考块）：直接复用，一次解析都不做。
        if (normalized == lastText) return lastResult

        // 文本被改写（重新生成、用户编辑过）→ 整个冻结区作废。
        if (!normalized.startsWith(frozenText)) {
            frozenText = ""
            frozenBlocks = emptyList()
        }

        // 把新稳定的前缀并进冻结区。
        val split = safeSplit(normalized)
        if (split > frozenText.length) {
            val extensionText = normalized.substring(frozenText.length, split)
            if (extensionText.isNotEmpty()) {
                // 尾部残段的块区间必须整体后移，否则冻结块与尾部块的坐标会对不上。
                frozenBlocks = frozenBlocks + shift(MdParser.parse(extensionText), frozenText.length)
                frozenText = normalized.substring(0, split)
            }
        }

        val tail = normalized.substring(frozenText.length)
        val result = if (tail.isEmpty()) {
            frozenBlocks
        } else {
            frozenBlocks + shift(MdParser.parse(tail), frozenText.length)
        }
        lastText = normalized
        lastResult = result
        return result
    }

    /** 丢弃全部缓存（切换会话、重新生成时用）。 */
    fun reset() {
        frozenText = ""
        frozenBlocks = emptyList()
        lastText = null
        lastResult = emptyList()
    }

    /**
     * 找安全分割点：最后一个「之后的内容永远不会改写它」的字符下标。
     *
     * 只有两种位置算安全：**已闭合围栏之后的换行**，以及**不在围栏内的空行之后的换行**。
     * 未闭合的围栏是不安全区——它后面追加的任何一行都会被算进代码块里。
     */
    private fun safeSplit(text: String): Int {
        var split = 0
        var fenceChar: Char? = null
        var openFenceStart = -1
        var lineStart = 0
        var prevLine = ""

        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(lineStart, lineEnd)

            val fence = fenceAt(line)
            if (fence != null) {
                if (fenceChar == null) {
                    fenceChar = fence
                    openFenceStart = lineStart
                } else if (fence == fenceChar) {
                    fenceChar = null
                    openFenceStart = -1
                    if (lineEnd < text.length) split = lineEnd + 1
                }
            } else if (fenceChar == null && line.isBlank() && lineEnd < text.length && isSafeBoundary(prevLine)) {
                split = lineEnd + 1
            }

            if (line.isNotBlank()) prevLine = line
            if (nl < 0) break
            lineStart = nl + 1
        }

        // 尾部是未闭合围栏：绝不能冻结到它里面去。
        if (fenceChar != null && openFenceStart >= 0) split = minOf(split, openFenceStart)
        return split
    }

    /**
     * 空行是否是**真正安全**的块边界。
     *
     * 只看「空行在围栏外」还不够：**列表条目可以跨过空行继续**（更深的缩进续行仍属于
     * 同一个条目），表格/段落也可能被缩进续行接着写。所以要求前一行必须是顶格的、
     * 非列表、非表格的普通内容行——这样空行之后的内容一定是新块的开始，
     * 拆开解析与整体解析结果才必然一致。
     */
    private fun isSafeBoundary(prevLine: String): Boolean {
        if (prevLine.isBlank()) return false
        if (prevLine.first().isWhitespace()) return false          // 缩进续行 → 可能还有下文
        val trimmed = prevLine.trimStart()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) return false
        if (trimmed.startsWith(">")) return false                   // 引用块可以跨空行继续
        if (trimmed.contains('|')) return false                     // 表格行
        val ordered = trimmed.indexOfFirst { it == '.' || it == ')' }
        if (ordered in 1..9 && trimmed.substring(0, ordered).all { it.isDigit() } &&
            ordered + 1 < trimmed.length && trimmed[ordered + 1] == ' '
        ) {
            return false
        }
        return true
    }

    /** @return 裸围栏的字符（`` ` `` 或 `~`）；不是裸围栏则返回 null。 */
    private fun fenceAt(line: String): Char? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return null
        val c = trimmed[0]
        if (c != '`' && c != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == c) n++
        if (n < 3) return null
        return if (trimmed.substring(n).isBlank()) c else null
    }

    /**
     * 把一段块的区间整体后移 [offset]，让它们对齐到全文坐标。
     *
     * 引用块里的嵌套块也要一起移——不动的话，引用块内部的坐标会指向尾部局部位置，
     * 冻结之后就会错位。
     */
    private fun shift(blocks: List<MdBlock>, offset: Int): List<MdBlock> {
        if (offset == 0) return blocks
        return blocks.map { shiftBlock(it, offset) }
    }

    private fun shiftBlock(block: MdBlock, offset: Int): MdBlock {
        val s = block.startOffset + offset
        val e = block.endOffset + offset
        return when (block) {
            is MdBlock.Paragraph -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.Heading -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.CodeBlock -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.BulletList -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.OrderedList -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.Table -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.Rule -> block.copy(startOffset = s, endOffset = e)
            is MdBlock.Quote -> block.copy(
                blocks = shift(block.blocks, offset),
                startOffset = s,
                endOffset = e,
            )
        }
    }
}
