package com.revxzxcx.remotewebapp.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes

/**
 * 代码高亮：把 `dev.snipme:highlights` 的 token 区间翻译成 Compose 的 [AnnotatedString]。
 *
 * 为什么用这个库（决策 D13）：Android 上没有 Shiki（桌面端用的），
 * 而 `highlights` 是纯 Kotlin、无 native 依赖、体积小，且**只返回字符区间**，
 * 不绑定任何 UI 框架——渲染由我们自己控制，正好符合「自绘 Markdown」的路线。
 *
 * ⚠️ 库本身**不提供 Compose 渲染**，`getHighlights()` 返回的是一组
 * `PhraseLocation(start, end)`（左闭右开，相对传入的 `code` 字符串）。
 * 区间之间**可以互相覆盖**（库按 mark → punctuation → … → comment 的顺序构造，
 * 后构造的语义更具体），所以本实现先把区间压成逐字符着色表（后写覆盖先写），
 * 再合并成连续同色的**色块**——直接把区间逐个叠加到 `AnnotatedString` 会因区间
 * 交叉而出现「后加的生反而被先加的盖住」。
 *
 * 分词逻辑（[runs]）刻意与 Compose 解耦成纯函数，单测可以在 JVM 上直接跑。
 */
internal object MdCodeHighlight {

    /**
     * 超过这个长度就不做分词了。
     *
     * `getHighlights()` 是同步的、跑在组合线程上；一个几千行的日志会让首帧明显卡顿。
     */
    const val MAX_HIGHLIGHT_CHARS = 20_000

    /**
     * 把 Markdown 围栏的语言标签或文件名映射到库的枚举。
     *
     * 库只认 18 种语言，别名很多（`sh`/`bash`/`zsh` 都是 SHELL，`kts` 是 KOTLIN），
     * 认不出的一律落到 [SyntaxLanguage.DEFAULT]——它是**通用分词器**，
     * 对 `json`/`yaml`/`toml` 这类没有专门支持的语言也比不高亮更可读。
     */
    fun languageOf(label: String?): SyntaxLanguage {
        val key = label?.trim()?.lowercase()?.trimStart('.') ?: return SyntaxLanguage.DEFAULT
        if (key.isEmpty()) return SyntaxLanguage.DEFAULT
        return when (key) {
            "sh", "shell", "bash", "zsh", "console", "terminal" -> SyntaxLanguage.SHELL
            "js", "jsx", "mjs", "cjs", "node" -> SyntaxLanguage.JAVASCRIPT
            "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "py", "python3" -> SyntaxLanguage.PYTHON
            "rb", "ruby" -> SyntaxLanguage.RUBY
            "rs", "rust" -> SyntaxLanguage.RUST
            "cs", "c#" -> SyntaxLanguage.CSHARP
            "c++", "cc", "cxx", "hpp", "hxx" -> SyntaxLanguage.CPP
            "h" -> SyntaxLanguage.C
            "php" -> SyntaxLanguage.PHP
            "go", "golang" -> SyntaxLanguage.GO
            "coffee" -> SyntaxLanguage.COFFEESCRIPT
            else -> SyntaxLanguage.getByName(key) ?: SyntaxLanguage.DEFAULT
        }
    }

    /**
     * 配色主题。本 App 跟随系统深浅色（见 `ui/theme/Theme.kt`），两套都要给。
     * 库的 `darcula` 在 light 表里是深色文字配色，与浅色背景搭配正确。
     */
    fun theme(darkMode: Boolean): SyntaxTheme = SyntaxThemes.darcula(darkMode = darkMode)

    /**
     * 分词。任何异常都降级成空列表（等价于不高亮），绝不让一个畸形代码块崩掉聊天页。
     */
    fun tokenize(code: String, language: SyntaxLanguage, darkMode: Boolean): List<CodeHighlight> {
        if (code.isEmpty() || code.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        return runCatching {
            Highlights.Builder()
                .code(code)
                .language(language)
                .theme(theme(darkMode))
                .build()
                .getHighlights()
        }.getOrDefault(emptyList())
    }

    /** 一段连续同色（且同粗细）的字符。`argb == null` 表示用基础色。 */
    data class Run(val start: Int, val end: Int, val argb: Int?, val bold: Boolean)

    /**
     * 把（可能互相交叉的）token 区间压成**不重叠、已排序**的色块序列。
     *
     * 这是纯函数，与 Compose 无关；[annotate] 只负责把它画出来。
     */
    fun runs(codeLength: Int, highlights: List<CodeHighlight>): List<Run> {
        if (codeLength <= 0) return emptyList()
        val colors = IntArray(codeLength) { UNTOUCHED }
        val bolds = BooleanArray(codeLength)
        for (highlight in highlights) {
            val start = highlight.location.start.coerceIn(0, codeLength)
            val end = highlight.location.end.coerceIn(0, codeLength)
            if (start >= end) continue
            when (highlight) {
                is ColorHighlight -> {
                    // 库给的是 0xRRGGBB，补上不透明 alpha。
                    val argb = highlight.rgb or OPAQUE
                    for (i in start until end) colors[i] = argb
                }

                is BoldHighlight -> for (i in start until end) bolds[i] = true
            }
        }
        val out = ArrayList<Run>()
        var index = 0
        while (index < codeLength) {
            var next = index + 1
            while (next < codeLength && colors[next] == colors[index] && bolds[next] == bolds[index]) next++
            out += Run(
                start = index,
                end = next,
                argb = colors[index].takeIf { it != UNTOUCHED },
                bold = bolds[index],
            )
            index = next
        }
        return out
    }

    /** 把色块画成 [AnnotatedString]。 */
    fun annotate(code: String, runs: List<Run>, base: Color): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        return buildAnnotatedString {
            for (run in runs) {
                val start = run.start.coerceIn(0, code.length)
                val end = run.end.coerceIn(start, code.length)
                if (start >= end) continue
                withStyle(
                    SpanStyle(
                        color = run.argb?.let { Color(it) } ?: base,
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                    ),
                ) { append(code.substring(start, end)) }
            }
        }
    }

    /**
     * 未被打分的字符。
     *
     * 不能用 `0`（合法 ARGB：透明黑），也不能用 `-1`（R005 P3 补遗：库给的是
     * `0xRRGGBB`，纯白 `0xFFFFFF or OPAQUE` 恰好就是 `-1`，会被误判成「没打过分」
     * 而退回基础色）。这里取 alpha 为 `0x80` 的值 —— 所有打得上的颜色都经过
     * `or OPAQUE`，alpha 恒为 `0xFF`，两者永不相等。
     */
    private const val UNTOUCHED = Int.MIN_VALUE

    /** 库给的是 `0xRRGGBB`，补上不透明 alpha。 */
    private const val OPAQUE = 0xFF000000.toInt()
}
