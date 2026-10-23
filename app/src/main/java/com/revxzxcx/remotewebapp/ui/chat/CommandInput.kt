package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.FileReference

/**
 * 这一行输入是否该按斜杠命令处理；是则返回要送去 `commands/execute` 的**行**。
 *
 * 三个前提缺一不可：
 * 1. **带 `@路径` 引用或附件时不按命令处理**。R047 ③ 起引用不再是「另存一份再拼
 *    后缀」，而是直接写进正文的 `@path`（与桌面端一致）。`commands/execute` 只吃
 *    `line`，没有引用参数——照发过去 `@path` 会被当成命令参数，而用户以为自己在引用
 *    一个文件。宁可当普通消息发（引用对 `session/prompt` 有效），也不要静默变形。
 * 2. **带附件时不是命令**：`commands/execute` 的 attachments 是它自己的一套形状，
 *    与 `session/prompt` 的上传回执不通用，混用会静默丢附件。
 * 3. 必须是 `/` 开头。认不认这条命令由调用方拿 `commands/list` 再判——
 *    本函数只回答「这看起来是不是命令」，不回答「服务端认不认」。
 */
internal fun commandCandidate(
    draft: String,
    attachmentCount: Int,
): String? {
    if (attachmentCount > 0) return null
    val line = draft.trim()
    if (!line.startsWith("/") || line.length <= 1) return null
    // 有 `@路径` 就不当命令（见 KDoc 第 1 条）。
    //
    // 审查 P3-7：这里曾经是 `line.split(' ')`，只认半角空格——一行里用换行或制表符
    // 分隔的 `@a.md` 于是被整行当成一个词，`@path` 照样被当命令参数吞掉；而
    // `inlineHighlightRanges` 用 `isWhitespace()`（含换行、制表符、全角空格），两套判据
    // 在制表符/换行上给出相反的答案（界面高亮成引用、发送时却当命令）。现在两边共用
    // 同一个扫描函数 `referenceRanges`。
    if (referenceRanges(line).isNotEmpty()) return null
    return line
}

/** 正文里需要染色的区间：`[start, end)` 闭开、按出现顺序、互不重叠。 */
internal data class InlineSpan(val start: Int, val end: Int, val kind: InlineSpanKind)

/** [InlineSpan] 的种类，决定渲染时的颜色与字体。 */
internal enum class InlineSpanKind {
    /** 行首的斜杠命令 `/cmd`——DSH 桌面端用主色等宽字体。 */
    Command,

    /** 文件引用 `@path`。 */
    Reference,
}

/**
 * 扫出正文里要染色的区间（R047 ②③，用户 m32024）。
 *
 * 抽成纯函数只为一个理由：这是「看起来对不对」这件事唯一的实质逻辑，而 Compose 的
 * `VisualTransformation` 在 JVM 单测里只能靠 `AnnotatedString` 断言，边界写得对不对
 * 反而不容易看清。这里返回的就是**字符下标**，单测能逐个数字比对。
 *
 * 两条规则：
 * - **命令**：整段正文的第一个词，以 `/` 开头且长度 > 1。只在**第 0 位**成立——
 *   正文中段出现的 `/` 是路径分隔符或分数，不是命令。
 * - **引用**：任何以 `@` 开头、后面跟到空白为止的连续片段。`@` 前面必须是非单词字符
 *   或行首，这样邮箱 `a@b.com` 不会被染色（`a` 是单词字符）。
 */
internal fun inlineHighlightRanges(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()

    // 命令：整个正文的第一个词。
    if (text[0] == '/') {
        var i = 0
        while (i < text.length && !isTokenBoundary(text[i])) i++
        if (i > 1) spans += InlineSpan(0, i, InlineSpanKind.Command)
    }

    spans += referenceRanges(text)
    return spans
}

/**
 * 扫出正文里所有 `@path` 区间。
 *
 * 引用边界判据的**唯一实现**（审查 P2-2 / P3-7）。谁需要「哪些 `@` 算引用」，谁就调
 * 它——不要再各自写一遍字符边界：
 * - [inlineHighlightRanges] 直接拿它决定染哪几段；
 * - [commandCandidate] 直接拿它回答「这一行带不带引用」（带引用就不是命令）；
 * - `hasToken` / [removeReferenceToken] 拿它扫出的**字面量**做比对（经
 *   [referenceTokensOf]），所以「正文里已有 `@docs/a.md.bak`、再插 `@docs/a.md`」
 *   不会被误判成重复；
 * - `ChatViewModel.referenceTokens` 是**间接**复用：它走 [inlineHighlightRanges]
 *   再过滤出 `InlineSpanKind.Reference`。因为 [inlineHighlightRanges] 只是在前面
 *   又加了一段命令 span，两边算出的引用区间完全相同——这条路径不是第二个判据。
 *
 * ## 结束边界是「空白或标点」，不只是空白
 *
 * 中文正文不会在标点后打空格：`看 @docs/a.md，这里有问题` 里，如果只按空白收尾，
 * 整个「@docs/a.md，这里有问题」都会被染成引用色，`referenceTokens` 还会产出一个
 * 根本不存在的路径，而 `removeReferenceToken` 又匹配不到它（它要求 token 后面是
 * 行尾或空白）——移除按钮点了没反应。所以标点也要当边界。
 *
 * `@` 必须在词首：邮箱 `a@b.com` 里那个不算引用。
 */
internal fun referenceRanges(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '@') {
            i++
            continue
        }
        val startsWord = i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')
        if (!startsWord) {
            i++
            continue
        }
        var j = i + 1
        while (j < text.length && !isTokenBoundary(text[j])) j++
        // 句末的点号归标点，不属于路径（`@docs/a.md.` -> `@docs/a.md`）。
        val end = trimTrailingDot(j, text)
        // 只有一个光秃秃的 `@` 不染色——用户可能正在打，染色会让它闪一下。
        if (end > i + 1) spans += InlineSpan(i, end, InlineSpanKind.Reference)
        // 必须推进游标。这里曾经写成「不推进、靠下一轮 `i++` 兜底」，但 `j` 停在
        // 边界字符上时（如 `@a.md，` 的「，」）下一轮又从同一个 `@` 重新扫，
        // 结果死循环 + `OutOfMemoryError`（单测直接崩在 `CommandInputTest:34`）。
        i = if (end > i) end else if (j > i) j else i + 1
    }
    return spans
}

/**
 * 一个字符是不是 token 的边界：空白，或会把引用「切断」的标点。
 *
 * 全角标点单列，因为中文正文里它们后面不会跟空格（这就是审查 P2-2 报的那个 bug）。
 * 斜杠不在里面——`docs/a.md` 的路径分隔符就是斜杠。
 */
private fun isTokenBoundary(c: Char): Boolean {
    if (c.isWhitespace()) return true
    // 半角标点：中文用户在它们后面通常不补空格。
    //
    // 点号**不在**里面：`docs/a.md`、`v1.2/x.txt` 的路径里到处是点，把它当边界会把
    // 引用截成 `docs/a`。句末那个真正属于标点的点是另一回事，由 [trimTrailingDot] 处理。
    if (c == ',' || c == ';' || c == ':' || c == ')' || c == ']' || c == '}' || c == '>' || c == '"') return true
    // 全角标点用**码位区间**判断，不写字面量：本文件曾因为把全角标点写进字符串
    // 字面量、再被工具链的转义改写，整行变成了乱码加一个空字符字面量（编译都过不去）。
    val cp = c.code
    return cp in 0x3000..0x303F ||
        cp in 0xFF01..0xFF0F ||
        cp in 0xFF1A..0xFF20 ||
        cp in 0xFF3B..0xFF40 ||
        cp in 0xFF5B..0xFF65
}

/**
 * 去掉引用 token 末尾那一个点号。
 *
 * `看 @docs/a.md.` 里的最后一个点是句号，不属于路径；而 `@docs/a.md` 自身以字母结尾，
 * 不受影响。只去一个：`@v1.2/x.txt..` 这种连写仍能留下 `@v1.2/x.txt.`，用户能看出来
 * 自己多打了一个，比静默吞掉两个点更好。
 */
private fun trimTrailingDot(end: Int, text: String): Int =
    if (end > 0 && text[end - 1] == '.') end - 1 else end

/**
 * 正文里是否已经有一个「完整的」 [token]（`@path`）。
 *
 * 判据**唯一出处**是 [referenceRanges]：这里比对的是扫描器真正认出来的那段字面量，
 * 不再自己比字符边界。`@docs/a.md` 与 `@docs/a.md.bak` 因此是两个不同的 token，
 * 前者不会命中后者的前缀。
 */
private fun hasToken(text: String, token: String): Boolean =
    referenceTokensOf(text).any { it == token }

/**
 * 正文里出现的所有引用 token 的字面量（`@path`，含前导 `@`）。
 *
 * **判据的唯一出处**：`hasToken` 与 `removeReferenceToken` 都从这里取，不再各自比对
 * 字符边界。上一版把「后面是空白或标点」写成独立判据，于是 `@docs/a.md` 在
 * `@docs/a.md.bak` 里被当成了完整 token（点号被误当句号），删引用时把长 token 的前缀
 * 削掉了。现在两边共用同一个扫描结果，比对的是**扫描器真正认出来的那段文本**。
 */
private fun referenceTokensOf(text: String): List<String> =
    referenceRanges(text).map { text.substring(it.start, it.end) }

/**
 * 把一条引用以 `@path` 插进正文末尾（R047 ③，用户 m32024）。
 *
 * 桌面端就是这么做的：引用不是「附件」，而是正文里的一个 token，随文本一起被编辑、
 * 一起被删掉。R043 实现成了「另存一份再在发送时拼后缀」，于是它在界面上是输入框
 * **下面一排**胶囊，用户点掉胶囊却不影响正文——两处状态迟早分叉。
 *
 * 去重按**词**判：正文里已经有 `@path` 就不再插第二份（同一个文件引用两次没有意义，
 * 服务端会解析出两段）。返回新正文；`path` 为空时原样返回。
 */
internal fun insertReferenceToken(draft: String, path: String): String {
    val token = "@$path"
    if (path.isBlank()) return draft
    val base = draft.trimEnd()
    if (hasToken(base, token)) return draft
    return if (base.isEmpty()) token else "$base $token"
}

/**
 * 从正文里删掉一条 `@path` 引用（[insertReferenceToken] 的逆操作）。
 *
 * 匹配的边界与 [referenceRanges] / [hasToken] 完全一致：前面是非单词字符或行首，
 * 后面是行尾或 token 边界（空白**或标点**）。后者是本轮审查 P2-2 的后果——中文正文里
 * 引用后面常直接跟一个「，」，若只认空白，`看 @a.md，然后` 里的引用在界面上是染色的，
 * 但移除按钮会匹配不到、点了没反应。
 *
 * 连同它前面那一个分隔空格一起删，免得正文里留下「看  两个空格」。
 */
internal fun removeReferenceToken(draft: String, path: String): String {
    val token = "@$path"
    if (path.isBlank()) return draft
    val span = referenceRanges(draft).firstOrNull {
        draft.substring(it.start, it.end) == token
    } ?: return draft
    val at = span.start
    val end = span.end
    val start = if (at > 0 && draft[at - 1] == ' ') at - 1 else at
    // 行首那条没有「前面的空格」可吞，就得吞自己后面那个——否则删完
    // 正文以空格开头（`"@a.md @b.md"` 删前者会剩 `" @b.md"`）。
    val trimmedEnd = if (start == at && end < draft.length && draft[end] == ' ') end + 1 else end
    return draft.removeRange(start, trimmedEnd)
}
