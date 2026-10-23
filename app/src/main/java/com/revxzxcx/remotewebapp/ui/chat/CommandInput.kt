package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.session.FileReference

/**
 * 这一行输入是否该按斜杠命令处理；是则返回要送去 `commands/execute` 的**行**。
 *
 * 三个前提缺一不可：
 * 1. **用草稿原文，不用 `composedText()`**。后者会把引用拼成 ` @path` 后缀，
 *    于是 `/export` 加一个引用就变成 `/export @docs/a.md`，`@path` 会被当成命令
 *    参数发给 `commands/execute`——而引用语义只对 `session/prompt` 有效
 *    （R045 审查 P3）。所以这里有引用时直接返回 null，让它走普通消息。
 * 2. **带附件时不是命令**：`commands/execute` 的 attachments 是它自己的一套形状，
 *    与 `session/prompt` 的上传回执不通用，混用会静默丢附件。
 * 3. 必须是 `/` 开头。认不认这条命令由调用方拿 `commands/list` 再判——
 *    本函数只回答「这看起来是不是命令」，不回答「服务端认不认」。
 */
internal fun commandCandidate(
    draft: String,
    referenceCount: Int,
    attachmentCount: Int,
): String? {
    if (referenceCount > 0 || attachmentCount > 0) return null
    val line = draft.trim()
    return line.takeIf { it.startsWith("/") && it.length > 1 }
}
