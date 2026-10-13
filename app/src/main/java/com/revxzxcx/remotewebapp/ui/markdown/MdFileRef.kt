package com.revxzxcx.remotewebapp.ui.markdown

/**
 * 判断一段行内代码看起来是不是「工作区里的文件路径」（需求 FR-5.9，P2）。
 *
 * 这个判断**故意保守**：命中就会把一段普通代码变成可点的入口，误判的代价
 * （点开一个不存在的路径 → 文件查看页报「文件不存在」）比漏判大得多。
 * 所以要求同时满足：
 *  - 至少含一个路径分隔符（`/` 或 `\`）——排除 `requestId`、`isActive` 这类普通标识符；
 *  - 不含量词/空白/引号这类只可能出现在代码片段里的字符；
 *  - 有扩展名且扩展名只含字母数字（`a.kt`、`build.gradle.kts`、`x.png`）。
 *
 * 相对的纯文件名（`ChatScreen.kt`）**不算**：工作区里有同名的测试文件、
 * 生成文件、多个模块各一份，猜错反而更糟。带目录的相对路径（`app/xxx.kt`）
 * 会命中，它是唯一相对会话 `cwd` 解析的形态（M3.5 探针实测）。
 */
internal object MdFileRef {

    /** 只可能出现在代码表达式里的字符：出现即认定不是路径。 */
    private val NOT_PATH_CHARS = charArrayOf(' ', '\t', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', '=', '+', '*', '?', '|', '!', '$', '%', '^', '&', ';', ',')

    /**
     * Windows 的盘符前缀（`C:\` / `C:/`）。
     *
     * 冒号**只在盘符位置**合法，所以它不进 [NOT_PATH_CHARS]，而是先由这个正则把
     * 盘符剥掉、再校验剩余部分——否则 `F:\bot\x.kt` 会因为那个冒号被整条否决
     * （R005 审查发现：这个分支曾经永远走不到，Windows 绝对路径全部点不开）。
     */
    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    /** 最后一段的扩展名：点 + 1..12 位字母数字（`gradle.kts`、`patch` 都算）。 */
    private val EXTENSION = Regex(".*\\.[A-Za-z0-9]{1,12}$")

    fun looksLikePath(text: String): Boolean {
        val value = text.trim()
        if (value.length < 3 || value.length > 400) return false
        // 盘符先剥掉，剩下的部分不允许再有冒号（`a:b/c.kt`、`http://x/y.md` 因此被否）。
        val drive = DRIVE.find(value)?.value
        val rest = if (drive != null) value.substring(drive.length) else value
        if (rest.isEmpty()) return false
        // 换行 / 制表符 / 绝大多数代码字符直接否决。
        if (rest.any { it in NOT_PATH_CHARS || it == ':' }) return false
        if (rest.startsWith('/') && rest.count { it == '/' } < 2) return false
        // 盘符之后可以只有一个文件名（`C:/x.kt`），但相对路径必须有目录分隔符，
        // 否则 `ChatScreen.kt` 这种到处都有的同名文件也会变成可点入口。
        if (drive == null && !rest.contains('/') && !rest.contains('\\')) return false
        if (rest.endsWith('/') || rest.endsWith('\\')) return false
        // 单个分隔符且不以 ./ ../ 开头、也没有扩展名 → 更像 `ui/chat` 这种目录名。
        if (!EXTENSION.matches(rest)) return false
        return true
    }
}
