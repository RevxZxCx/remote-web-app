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
 * 3. 只对分割点之后的尾部分调用 [MdParser.parseNormalized]。
 *
 * **不变量**：`update(text)` 的结果永远等于 `MdParser.parse(text)`。
 * 增量只是一个优化，不允许改变输出（单测直接断言这条）。
 *
 * ## 增量的三个层次（M5-3，R005 遗留的 O(n²)）
 *
 * 早先的实现在**每一帧**都要做四件全量的事，所以是 O(n²)：
 *
 * | 早先 | 现在 |
 * |---|---|
 * | `text.replace("\r\n","\n")` 无条件拷一份全文 | 先 `indexOf('\r')`，没有 `\r` 就零拷贝 |
 * | `startsWith(frozenText)` 逐字符比冻结前缀 | 只和**上一帧全文**比一次（`regionMatches`），
 *    纯追加时冻结前缀必然还有效，不必再比 |
 * | `safeSplit` 从第 0 行重扫全文，每行一次 `substring` | 有穷状态机，**从上次的扫描游标续扫**，
 *    只处理新增的行 |
 * | `normalized.substring(0, split)` 拷一份冻结前缀 | 只记 `frozenLen`（一个 Int），不存冻结原文 |
 *
 * 剩下的线性项只有一个：`frozenBlocks + tailBlocks` 每帧重建一次结果列表，代价是
 * O(块数) 的引用拷贝（不是字符工作）。一篇 2 万字回复约 1000 个块，每帧拷 8 KB，
 * 对 Android 的年轻代 GC 无压力；真要消除就得把结果改成序列，那会让调用方复杂得多。
 *
 * **非线程安全**：每个正在流式的气泡持有自己的实例，只在 Compose 的组合线程上更新。
 */
class MdStream {

    private var frozenBlocks: List<MdBlock> = emptyList()

    /** 冻结区在归一化全文里的长度（取代早先保存的 `frozenText` 副本）。 */
    private var frozenLen: Int = 0

    private var lastText: String? = null
    private var lastResult: List<MdBlock> = emptyList()

    // ---- safeSplit 的增量扫描游标 ----
    // safeSplit 是一个从左到右的有穷状态机，纯追加时可以只扫新增部分。
    // 五个量就是它的全部状态：下一个待扫行的行首、围栏字符、未闭合围栏的行首、
    // 最近一个非空行、以及到目前为止找到的最后一个安全分割点。

    /** 下一个待扫行的行首下标；永远 ≤ 上一帧文本长度。 */
    private var scanAt: Int = 0

    /** 当前是否在围栏内；null 表示不在。 */
    private var scanFence: Char? = null

    /** 未闭合围栏的行首；-1 表示没有。 */
    private var scanFenceStart: Int = -1

    /** 未闭合围栏的长度（闭栏必须不短于它，CommonMark）。 */
    private var scanFenceLen: Int = 0

    /** 最近一个非空行的内容（`isSafeBoundary` 要用）。 */
    private var scanPrevLine: String = ""

    /** 到目前为止的最后一个安全分割点。 */
    private var scanSplit: Int = 0

    /**
     * 用最新的全文内容刷新。
     *
     * @return 完整的块列表（冻结块 + 重解析的尾部），可直接交给 Compose 渲染。
     */
    fun update(text: String): List<MdBlock> {
        // 没有 '\r' 就完全跳过归一化：流式文本几乎全是 '\n'，这一条省掉每帧一次全文字符串拷贝。
        val normalized = if (text.indexOf('\r') < 0) text else normalize(text)

        // 内容没变（例如这一帧只更新了思考块）：直接复用，一次解析都不做。
        val prev = lastText
        if (normalized == prev) return lastResult

        // 纯追加（流式的常态）→ 冻结区与扫描游标全部有效，只处理新增部分。
        // 任何非追加（重新生成、被改写、变短）都让全部缓存作废，从头再来。
        val appended = prev != null &&
            normalized.length > prev.length &&
            normalized.regionMatches(0, prev, 0, prev.length)
        if (!appended) resetCache()

        val split = safeSplit(normalized)
        if (split > frozenLen) {
            val extensionText = normalized.substring(frozenLen, split)
            if (extensionText.isNotEmpty()) {
                // 直接用 base = frozenLen 让区间落在全文坐标上，不再需要事后 shift。
                frozenBlocks = frozenBlocks + MdParser.parseNormalized(extensionText, frozenLen)
                frozenLen = split
            }
        }

        val tail = normalized.substring(frozenLen)
        val result = if (tail.isEmpty()) {
            frozenBlocks
        } else {
            frozenBlocks + MdParser.parseNormalized(tail, frozenLen)
        }
        lastText = normalized
        lastResult = result
        return result
    }

    /** 丢弃全部缓存（切换会话、重新生成时用）。 */
    fun reset() = resetCache()

    private fun resetCache() {
        frozenBlocks = emptyList()
        frozenLen = 0
        lastText = null
        lastResult = emptyList()
        scanAt = 0
        scanFence = null
        scanFenceStart = -1
        scanFenceLen = 0
        scanPrevLine = ""
        scanSplit = 0
    }

    /**
     * 找安全分割点：最后一个「之后的内容永远不会改写它」的字符下标。
     *
     * 只有两种位置算安全：**已闭合围栏之后的换行**，以及**不在围栏内的空行之后的换行**。
     * 未闭合的围栏是不安全区——它后面追加的任何一行都会被算进代码块里。
     *
     * @param resumed true 表示接着 [scanAt] 续扫（纯追加）；false 表示从第 0 行重扫。
     *
     * ## 为什么必须回滚最后一行（M5-3 实测踩到）
     *
     * 续扫时 [scanAt] 停在**最后一个可能还没写完的行**的行首，所以那一行会被再处理一次。
     * 若不回滚，同一行就会应用两次状态转移：`"```"` 先是开围栏，重扫时又变成**闭围栏**，
     * 于是 `````` 后面紧跟的换行被当成安全边界冻结出去——紧接着到来的 `x` 就成了段落，
     * 而全文解析里它明明是代码块的内容。表现为 `MdStreamTest` 的两条不变量用例失败
     * （`无语言代码块` 文档 len=5：期望 1 块，实得 CodeBlock + Paragraph）。
     * 因此每次处理一行前先快照状态，遇到没有换行符的行就回滚并中断。
     *
     * ## 开栏必须认语言标签（M5-10 实测踩到，比上面那条更隐蔽）
     *
     * 早先这里自己写了一份 `fenceAt`，**只认裸围栏**（```` ``` ```` 或 `~~~` 后面必须
     * 只有空白）。于是 ```` ```kotlin ```` 这种带语言标签的开栏行被判成普通行，状态机
     * 全程认为「不在围栏内」——代码块内部的空行就此命中下面的「围栏外空行」分支，
     * 把 `split` 推到**代码块中间**：冻结区含一个永不闭合的 CodeBlock 且永不重解析，
     * 表现为代码块尾部几行掉成块外段落、头部标签永远显示「生成中…」。
     * 设备验收（T6）截图的 `fun main()` 掉出块外 + 「生成中…」不消失，正是这条。
     * 修法：围栏判定**只保留 [MdParser.fenceInfo] 一份**，开栏认 info、闭栏要求
     * 同字符、不短于开栏、且 info 为空白。
     */
    private fun safeSplit(text: String, resumed: Boolean = true): Int {
        var lineStart = if (resumed) scanAt else 0
        var fenceChar: Char? = if (resumed) scanFence else null
        var openFenceStart = if (resumed) scanFenceStart else -1
        var openFenceLen = if (resumed) scanFenceLen else 0
        var prevLine = if (resumed) scanPrevLine else ""
        var split = if (resumed) scanSplit else 0

        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl

            // 处理这一行之前的状态快照（见方法 KDoc 的「为什么必须回滚最后一行」）。
            val beforeFence = fenceChar
            val beforeFenceStart = openFenceStart
            val beforeFenceLen = openFenceLen
            val beforePrevLine = prevLine

            val line = text.substring(lineStart, lineEnd)

            val fence = MdParser.fenceInfo(line)
            if (fence != null) {
                if (fenceChar == null) {
                    // 开栏：可带语言标签（```` ```kotlin ````），info 内容不重要。
                    fenceChar = fence.char
                    openFenceStart = lineStart
                    openFenceLen = fence.len
                } else if (fence.char == fenceChar && fence.info.isBlank() && fence.len >= openFenceLen) {
                    // 闭栏：同字符、不短于开栏、后面没有 info。
                    fenceChar = null
                    openFenceStart = -1
                    openFenceLen = 0
                    if (lineEnd < text.length) split = lineEnd + 1
                }
                // 其余围栏行（不同字符 / 更短 / 带 info）是代码块的内容，不是围栏。
            } else if (fenceChar == null && line.isBlank() && lineEnd < text.length && isSafeBoundary(prevLine)) {
                split = lineEnd + 1
            }

            if (line.isNotBlank()) prevLine = line

            if (nl < 0) {
                // 这一行还没有换行符，随时可能被追加内容改写 → 回滚并停在它行首。
                // （`split` 不需要回滚：两条修改它的分支都要求 `lineEnd < text.length`，
                //  而这里 `lineEnd == text.length`，必然不成立。）
                fenceChar = beforeFence
                openFenceStart = beforeFenceStart
                openFenceLen = beforeFenceLen
                prevLine = beforePrevLine
                break
            }
            lineStart = nl + 1
        }

        // 保存游标：`lineStart` 是最后一个（可能还没写完的）行的行首，
        // 下一次追加必须重新扫它——它随时可能补上换行符、闭合围栏、或从半行变成整行。
        scanAt = lineStart
        scanFence = fenceChar
        scanFenceStart = openFenceStart
        scanFenceLen = openFenceLen
        scanPrevLine = prevLine
        scanSplit = split

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

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
