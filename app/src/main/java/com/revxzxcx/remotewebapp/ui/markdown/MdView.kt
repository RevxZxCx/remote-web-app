package com.revxzxcx.remotewebapp.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 单次组合最多画多少个 [MdBlock]。
 *
 * 选中 200 的理由：一行 `bodyMedium` 约 18dp，200 块 ≈ 三四屏内容，一次测量完全无压力；
 * 而一份两万字的 Markdown 回复 / 文件通常只有几十个块，正常文档永远碰不到这个上限。
 *
 * `internal` 而不是 `private`：渲染测试要按同一个数字造超长文档（见 `MdViewRenderTest`），
 * 写死字面量的话常量一改测试就悄悄失去意义。
 */
internal const val MAX_RENDERED_BLOCKS: Int = 200

/** 单个列表最多画多少条。列表条目比段落矮，但每条的测量/组合成本更高。 */
internal const val MAX_LIST_ITEMS: Int = 200

/** 单个表格最多画多少行。表格每行还要乘列数，所以比列表更早收手。 */
internal const val MAX_TABLE_ROWS: Int = 100

/**
 * 表格每列的**最小**宽度。
 *
 * 表格总宽取 `maxOf(可用宽度, 本值 × 列数)`（用户 m45428 第 4 条）：
 *  - 列少时总宽 = 可用宽度 → 铺满整行，各列按 `weight(1f)` 均分；
 *  - 列多时总宽 = 本值 × 列数 → 超过可用宽度，靠横向滚动看全。
 *
 * 旧实现把总宽写死成 `96 * columns`，于是 2 列表格在 360dp 屏上只有 192dp，
 * 右边留一大片空白、表格被挤成窄窄一条。
 */
internal const val TABLE_MIN_COLUMN_WIDTH: Int = 96

/**
 * 把 [MdBlock] 列表渲染成 Compose UI（需求 FR-5.1 / FR-5.2）。
 *
 * 设计取舍：
 *  - **行内链接用 [LinkAnnotation.Clickable]**，不用 `Modifier.clickable` ——
 *    后者会让整段文本变成一个点击区，段落里的其它文字点哪儿都跳转。
 *  - 代码块**不做自动折行**，而是横向滚动（与桌面端一致）：折行会把缩进结构打乱，
 *    代码反而更难读。
 *  - 表格同样横向滚动；单元格等宽分配，够用且不必引入测量逻辑。
 *  - **单个气泡内的块数/列表条数/表格行数有上限**（见 [MAX_RENDERED_BLOCKS] 一族常量）：
 *    助手回复或一个 Markdown 文件完全可能有几千个块，而 [MdBlocks] 外层就是聊天页的
 *    `LazyColumn` 条目——**不能再套一个 LazyColumn**（父级可竖向滚动，内层
 *    `LazyColumn` 会因无限高约束直接抛异常），所以唯一的兜底就是「先画一部分，
 *    剩下的等用户点开」。折叠永远只是性能保护，不会丢内容：点一下就把剩下的全画出来。
 *
 * @param onLinkClick 命中 `[文字](地址)` 里的 http(s) 地址时回调。
 * @param onFilePathClick 命中可点文件路径时回调（需求 FR-5.9）。
 */
@Composable
internal fun MdBlocks(
    blocks: List<MdBlock>,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 折叠状态按组合位置记忆：滚动出屏再回来会重新折叠（LazyColumn 会丢弃条目的
    // rememberSaveable 槽位），这是可接受的代价——上限本身就是给病态文档兜底。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val limit = if (expanded) Int.MAX_VALUE else MAX_RENDERED_BLOCKS
    val visible = blocks.take(limit)
    val remaining = blocks.size - visible.size

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { block -> MdBlockView(block, onLinkClick, onFilePathClick) }
        if (remaining > 0) {
            OverflowNote(remaining = remaining, unit = "个内容块", expanded = expanded) { expanded = true }
        }
    }
}

/**
 * 「还有 N 项没画」的提示条。
 *
 * @param expanded false 时是「展开其余…」按钮（点了才全画）；true 时折叠按钮不再显示
 *   （已经全展开了），只在理论上留一个说明位。
 */
@Composable
private fun OverflowNote(remaining: Int, unit: String, expanded: Boolean, onExpand: () -> Unit) {
    if (expanded) return
    TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "展开其余 $remaining $unit",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    when (block) {
        is MdBlock.Paragraph -> MdText(
            inlines = block.inlines,
            style = MaterialTheme.typography.bodyMedium,
            onLinkClick = onLinkClick,
            onFilePathClick = onFilePathClick,
        )

        is MdBlock.Heading -> MdText(
            inlines = block.inlines,
            style = headingStyle(block.level),
            onLinkClick = onLinkClick,
            onFilePathClick = onFilePathClick,
        )

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.BulletList -> ListView(
            items = block.items,
            ordered = false,
            start = 1,
            onLinkClick = onLinkClick,
            onFilePathClick = onFilePathClick,
        )

        is MdBlock.OrderedList -> ListView(
            items = block.items,
            ordered = true,
            start = block.start,
            onLinkClick = onLinkClick,
            onFilePathClick = onFilePathClick,
        )

        is MdBlock.Quote -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 左侧竖条：用 3dp 的实心 Surface 模拟引用的缩进线。
            Box(
                Modifier
                    .width(3.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.blocks.forEach { nested -> MdBlockView(nested, onLinkClick, onFilePathClick) }
            }
        }

        is MdBlock.Table -> TableView(block, onLinkClick, onFilePathClick)

        is MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

/** 标题字号随级别递减；这里只做「层级看得见」，不必与文档排版一一对应。 */
@Composable
private fun headingStyle(level: Int): TextStyle = when (level.coerceIn(1, 6)) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    3 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * 列表。任务列表（GFM `- [x]`）渲染成 `☑`/`☐`，其它用 `•` 或递增编号。
 *
 * 嵌套子块（[MdListItem.children]）缩进在条目文字下方。
 *
 * 条目数超过 [MAX_LIST_ITEMS] 时只画前一部分，末尾给一个「展开」按钮
 * —— 内层不能再套 `LazyColumn`（外层就是竖向滚动的 `LazyColumn`，无限高约束会直接抛异常）。
 */
@Composable
private fun ListView(
    items: List<MdListItem>,
    ordered: Boolean,
    start: Int,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val limit = if (expanded) Int.MAX_VALUE else MAX_LIST_ITEMS
    val visible = items.take(limit)
    val remaining = items.size - visible.size

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val marker = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    ordered -> "${start + index}."
                    else -> "•"
                }
                Text(
                    text = marker,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (item.inlines.isNotEmpty()) {
                        MdText(
                            inlines = item.inlines,
                            style = MaterialTheme.typography.bodyMedium,
                            onLinkClick = onLinkClick,
                            onFilePathClick = onFilePathClick,
                        )
                    }
                    if (item.children.isNotEmpty()) {
                        MdBlocks(item.children, onLinkClick, onFilePathClick)
                    }
                }
            }
        }
        if (remaining > 0) {
            OverflowNote(remaining = remaining, unit = "个列表项", expanded = expanded) { expanded = true }
        }
    }
}

/**
 * 表格。每行等宽分列，整体可横向滚动。
 *
 * 单元格里的行内元素仍走 [MdText]，所以表格里的代码、链接都能正常显示。
 *
 * 行数超过 [MAX_TABLE_ROWS] 时只画前一部分，末尾给一个「展开」按钮
 * —— 表格每行要乘列数，是最容易把一次组合撑爆的块类型。
 */
@Composable
private fun TableView(
    table: MdBlock.Table,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val columns = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    ).coerceAtLeast(1)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val limit = if (expanded) Int.MAX_VALUE else MAX_TABLE_ROWS
    val visible = table.rows.take(limit)
    val remaining = table.rows.size - visible.size

    // 🔴 表格宽度必须是「至少撑满可用宽度，列多时才横向滚动」，
    // 不能是固定的 `96 * columns`（用户 m45428 第 4 条）。
    //
    // 旧实现写死每列 96dp：2 列表格在 360dp 屏上只有 192dp 宽，右边留一大片空白，
    // 表格被挤成窄窄一条——用户原话「有的时候不会占满整个屏幕的宽度，导致表被挤成
    // 比较窄的一条」。
    //
    // 现在用 `BoxWithConstraints` 拿真实可用宽度：`maxOf(可用宽度, 96 * columns)`。
    // 列少 → 铺满整行（各列按 weight 均分）；列多 → 超过可用宽度，横向滚动看全。
    Surface2 {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val minTableWidth = (TABLE_MIN_COLUMN_WIDTH * columns).dp
            val width = maxOf(maxWidth, minTableWidth)
            Column(modifier = Modifier.horizontalScroll(scroll)) {
                Row(modifier = Modifier.width(width)) {
                table.headers.forEach { cell ->
                    TableCell(cell, header = true, onLinkClick, onFilePathClick, Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            visible.forEach { row ->
                Row(modifier = Modifier.width(width)) {
                    // 行比表头短时补空单元格，避免行内列数与表头不一致导致错位。
                    repeat(columns) { column ->
                        val cell = row.getOrNull(column) ?: emptyList()
                        TableCell(cell, header = false, onLinkClick, onFilePathClick, Modifier.weight(1f))
                    }
                }
            }
                if (remaining > 0) {
                    OverflowNote(remaining = remaining, unit = "个数据行", expanded = expanded) { expanded = true }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    header: Boolean,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
    modifier: Modifier,
) {
    MdText(
        inlines = inlines,
        style = if (header) {
            MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        } else {
            MaterialTheme.typography.bodySmall
        },
        onLinkClick = onLinkClick,
        onFilePathClick = onFilePathClick,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

/** 带底色圆角的容器（代码块、表格共用）。 */
@Composable
private fun Surface2(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.TopStart,
    ) { content() }
}

/**
 * 围栏代码块。
 *
 * [MdBlock.CodeBlock.closed] 为 false（流式过程中围栏还没收尾）时**照常渲染**，
 * 只在右上角标一个「生成中」的提示——这样代码不会在最后一行闪来闪去。
 */
@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val base = MaterialTheme.colorScheme.onSurface
    // 深浅色以 **MaterialTheme 实际配色**为准，而不是 `isSystemInDarkTheme()`（R005 P3 补遗）：
    // 前者一旦被固定或被上层覆盖，后者仍报系统值，代码块就会出现「浅色文字配浅色底」
    // 而正文一切正常。背景亮度 < 0.5 视为深色。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val language = MdCodeHighlight.languageOf(block.language)
    // 分词与着色都可能很贵（几千字符），只在代码/语言/主题变化时重算。
    //
    // ⚠️ 超长代码块**必须整段跳过**，不能只是让 `tokenize` 返回空表（R005 P3 补遗）：
    // `runs()` 会按长度分配 `IntArray(n)` + `BooleanArray(n)` 再逐字符扫一遍，于是
    // >20k 字符的块反而比短块更贵，流式生成时每一帧都按当前长度重来一次。
    val annotated = remember(block.code, language, dark, base) {
        if (block.code.length > MdCodeHighlight.MAX_HIGHLIGHT_CHARS) {
            AnnotatedString(block.code)
        } else {
            val tokens = MdCodeHighlight.tokenize(block.code, language, dark)
            MdCodeHighlight.annotate(block.code, MdCodeHighlight.runs(block.code.length, tokens), base)
        }
    }
    Surface2 {
        Column {
            val label = block.language?.trim().orEmpty()
            // 代码块的标题行**总是渲染**（用户 m45428 第 6 条：要一个独立的复制按钮）。
            // 早先没有语言标签时整行都不画，于是无语言围栏的代码块没有地方放复制按钮。
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.ifEmpty { "代码" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!block.closed) {
                    Text(
                        text = "生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                // 复制按钮只在代码已经收尾时出现：流式过程中内容还在变，
                // 复制到半截代码没有意义，而且会让按钮在眼前反复闪。
                if (block.closed) {
                    CopyIconButton(
                        text = block.code,
                        contentDescription = "复制代码",
                    )
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 一组行内元素 → 一段可点击文本（需求 FR-5.1）。
 *
 * 可点命中两类：
 *  1. `[文字](https://…)` → [LinkAnnotation.Url]（交给系统的 uriHandler 打开浏览器）；
 *  2. 行内代码/链接文字长得像工作区路径 → [LinkAnnotation.Clickable] → [onFilePathClick]。
 */
@Composable
internal fun MdText(
    inlines: List<MdInline>,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val annotated = remember(inlines, linkStyle, onFilePathClick, onLinkClick) {
        buildMdAnnotatedString(inlines, linkStyle, onLinkClick, onFilePathClick)
    }
    Text(text = annotated, style = style, modifier = modifier)
}

/**
 * 构造带链接标注的 [AnnotatedString]。
 *
 * 抽成非 Composable 的纯构建函数，方便单测直接在 JVM 上断言文本与链接区间。
 */
internal fun buildMdAnnotatedString(
    inlines: List<MdInline>,
    linkStyle: TextLinkStyles,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    appendInlines(inlines, linkStyle, onLinkClick, onFilePathClick)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    linkStyle: TextLinkStyles,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    inlines.forEach { inline -> appendInline(inline, linkStyle, onLinkClick, onFilePathClick) }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    inline: MdInline,
    linkStyle: TextLinkStyles,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    when (inline) {
        is MdInline.Text -> append(inline.text)

        is MdInline.Code -> {
            // 行内代码命中唯一文件路径时变成可点（FR-5.9）。
            if (MdFileRef.looksLikePath(inline.text)) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = inline.text,
                        styles = linkStyle,
                        linkInteractionListener = { onFilePathClick(inline.text) },
                    ),
                ) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(inline.text) }
                }
            } else {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Transparent,
                    ),
                ) { append(inline.text) }
            }
        }

        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlines(inline.children, linkStyle, onLinkClick, onFilePathClick)
        }

        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlines(inline.children, linkStyle, onLinkClick, onFilePathClick)
        }

        is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(inline.children, linkStyle, onLinkClick, onFilePathClick)
        }

        is MdInline.Link -> {
            val text = inline.text.plainText()
            // 链接文字本身是个路径 → 当文件打开；否则当外部地址。
            if (MdFileRef.looksLikePath(text)) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = text,
                        styles = linkStyle,
                        linkInteractionListener = { onFilePathClick(text) },
                    ),
                ) { appendInlines(inline.text, linkStyle, onLinkClick, onFilePathClick) }
            } else if (inline.href.startsWith("http://") || inline.href.startsWith("https://")) {
                withLink(LinkAnnotation.Url(url = inline.href, styles = linkStyle)) {
                    appendInlines(inline.text, linkStyle, onLinkClick, onFilePathClick)
                }
            } else {
                // 相对地址等无法处理的协议：按普通文字渲染并标下划线，点击交给回调。
                withLink(
                    LinkAnnotation.Clickable(
                        tag = inline.href,
                        styles = linkStyle,
                        linkInteractionListener = { onLinkClick(inline.href) },
                    ),
                ) { appendInlines(inline.text, linkStyle, onLinkClick, onFilePathClick) }
            }
        }

        is MdInline.Image -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append("[图片 ${inline.alt.ifBlank { "未命名" }}]")
        }

        MdInline.LineBreak -> append('\n')
    }
}
