package com.revxzxcx.remotewebapp.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 把 [MdBlock] 列表渲染成 Compose UI（需求 FR-5.1 / FR-5.2）。
 *
 * 设计取舍：
 *  - **行内链接用 [LinkAnnotation.Clickable]**，不用 `Modifier.clickable` ——
 *    后者会让整段文本变成一个点击区，段落里的其它文字点哪儿都跳转。
 *  - 代码块**不做自动折行**，而是横向滚动（与桌面端一致）：折行会把缩进结构打乱，
 *    代码反而更难读。
 *  - 表格同样横向滚动；单元格等宽分配，够用且不必引入测量逻辑。
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MdBlockView(block, onLinkClick, onFilePathClick) }
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
 */
@Composable
private fun ListView(
    items: List<MdListItem>,
    ordered: Boolean,
    start: Int,
    onLinkClick: (String) -> Unit,
    onFilePathClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
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
    }
}

/**
 * 表格。每行等宽分列，整体可横向滚动。
 *
 * 单元格里的行内元素仍走 [MdText]，所以表格里的代码、链接都能正常显示。
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
    // 每列至少 96dp，内容长的表格靠横向滚动看全。
    val width = (96 * columns).dp
    Surface2 {
        Column(modifier = Modifier.horizontalScroll(scroll)) {
            Row(modifier = Modifier.width(width)) {
                table.headers.forEach { cell ->
                    TableCell(cell, header = true, onLinkClick, onFilePathClick, Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            table.rows.forEach { row ->
                Row(modifier = Modifier.width(width)) {
                    // 行比表头短时补空单元格，避免行内列数与表头不一致导致错位。
                    repeat(columns) { column ->
                        val cell = row.getOrNull(column) ?: emptyList()
                        TableCell(cell, header = false, onLinkClick, onFilePathClick, Modifier.weight(1f))
                    }
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
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.onSurface
    val language = MdCodeHighlight.languageOf(block.language)
    // 分词与着色都可能很贵（几千字符），只在代码/语言/主题变化时重算。
    val annotated = remember(block.code, language, dark, base) {
        val tokens = MdCodeHighlight.tokenize(block.code, language, dark)
        MdCodeHighlight.annotate(block.code, MdCodeHighlight.runs(block.code.length, tokens), base)
    }
    Surface2 {
        Column {
            val label = block.language?.trim().orEmpty()
            if (label.isNotEmpty() || !block.closed) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label.ifEmpty { "代码" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!block.closed) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "生成中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
