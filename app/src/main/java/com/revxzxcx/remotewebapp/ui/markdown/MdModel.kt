package com.revxzxcx.remotewebapp.ui.markdown

/**
 * Markdown 的**块级**模型。
 *
 * 为什么自绘（决策 D12）：桌面端用的是 GFM + 冻结尾部增量重解析
 * （`dsh-client-ui-primitives` 的 `MarkdownText`：除最后两块外全部冻结，只重解析尾部，
 * 未闭合的围栏仍交给同一套语法）。Android 上没有等价的成熟库能做到同样的事，
 * 而我们要的只是「聊天里那几件事」——标题、列表、表格、引用、围栏代码、行内代码。
 *
 * [startOffset] / [endOffset] 是字符区间，用于流式增量渲染时判断哪些块已经稳定
 * （可以冻结）——这是避免 O(n²) 重排的关键。坐标的确切含义（R005 审查后修正，
 * 原先的注释声称是「原始文本」，与实现不符）：
 *
 * - 基准是 **`\r\n` 已归一化为 `\n` 的文本**，不是调用方传进来的原始字符串。
 *   Windows 换行在归一化时会各少一个字符，所以拿这里的区间去切原始文本会偏移。
 *   目前没有任何渲染代码这么切（[MdView] 不读区间，[MdStream] 只做加减），
 *   保持「不切原文」是使用前提。
 * - 引用块内部的子块坐标在**引用块自己的内层坐标系**里（前缀 `>` 已剥掉），
 *   不直接映射回外层文本。同样只用于冻结判定。
 */
sealed interface MdBlock {

    val startOffset: Int
    val endOffset: Int

    /** 普通段落。 */
    data class Paragraph(
        val inlines: List<MdInline>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** `#` ~ `######` 标题。 */
    data class Heading(
        val level: Int,
        val inlines: List<MdInline>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /**
     * 围栏代码块。
     *
     * [closed] 为 false 表示流式过程中围栏还没收到结尾——**必须照常渲染**，
     * 否则用户会看到代码块在最后一行闪来闪去（桌面端同样把未闭合围栏交给同一套语法）。
     */
    data class CodeBlock(
        val language: String?,
        val code: String,
        val closed: Boolean,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** 无序列表（`-` / `*` / `+`）。 */
    data class BulletList(
        val items: List<MdListItem>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** 有序列表（`1.`）。[start] 是首项的编号。 */
    data class OrderedList(
        val start: Int,
        val items: List<MdListItem>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** 引用块（`>`），内容可以再嵌套块。 */
    data class Quote(
        val blocks: List<MdBlock>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** GFM 表格。 */
    data class Table(
        val headers: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock

    /** 分割线（`---` / `***`）。 */
    data class Rule(
        override val startOffset: Int,
        override val endOffset: Int,
    ) : MdBlock
}

/** 列表里的一个条目，支持 GFM 任务列表的勾选框。 */
data class MdListItem(
    val inlines: List<MdInline>,
    /** `[ ]` / `[x]` 任务列表的勾选状态；非任务列表为 null。 */
    val checked: Boolean? = null,
    /** 嵌套的子块（缩进更深的内容）。 */
    val children: List<MdBlock> = emptyList(),
)

/** 行内元素。 */
sealed interface MdInline {

    data class Text(val text: String) : MdInline

    /** 行内代码 `` `x` ``。UI 会尝试把它变成可点击的文件链接。 */
    data class Code(val text: String) : MdInline

    data class Strong(val children: List<MdInline>) : MdInline

    data class Emphasis(val children: List<MdInline>) : MdInline

    data class Strikethrough(val children: List<MdInline>) : MdInline

    /** `[文字](地址)`。 */
    data class Link(val text: List<MdInline>, val href: String) : MdInline

    /** `![替代文字](地址)`。 */
    data class Image(val alt: String, val src: String) : MdInline

    /** 硬换行（行尾两个空格或 `\`）。 */
    data object LineBreak : MdInline
}

/** 把行内元素拍平成纯文本（用于文件链接匹配、无障碍朗读等）。 */
fun List<MdInline>.plainText(): String = buildString { appendInlines(this@plainText, this) }

private fun appendInlines(inlines: List<MdInline>, out: StringBuilder) {
    for (inline in inlines) {
        when (inline) {
            is MdInline.Text -> out.append(inline.text)
            is MdInline.Code -> out.append(inline.text)
            is MdInline.Strong -> appendInlines(inline.children, out)
            is MdInline.Emphasis -> appendInlines(inline.children, out)
            is MdInline.Strikethrough -> appendInlines(inline.children, out)
            is MdInline.Link -> appendInlines(inline.text, out)
            is MdInline.Image -> out.append(inline.alt)
            MdInline.LineBreak -> out.append('\n')
        }
    }
}
