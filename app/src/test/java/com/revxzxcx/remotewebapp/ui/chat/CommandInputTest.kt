package com.revxzxcx.remotewebapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 斜杠命令的入口判据（R045 审查 P3）。
 *
 * 这里的每一条都对应一个**会静默发错东西**的路径：引用被当成命令参数、附件被
 * `commands/execute` 吞掉、或者一条 `/` 开头的正文被误判成命令而进不了模型。
 */
class CommandInputTest {

    @Test
    fun `纯斜杠行是命令候选`() {
        assertEquals("/export", commandCandidate("/export", 0, 0))
    }

    @Test
    fun `带参数的行整行送出去`() {
        assertEquals("/goal 完成 R045", commandCandidate("/goal 完成 R045", 0, 0))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("/export", commandCandidate("  /export  ", 0, 0))
    }

    @Test
    fun `选中引用后不算命令`() {
        // composedText() 会拼出 `/export @docs/a.md`，那个 @path 会被当成命令参数
        // 发给 commands/execute，而引用语义只对 session/prompt 有效。
        assertNull(commandCandidate("/export", referenceCount = 1, attachmentCount = 0))
    }

    @Test
    fun `带附件时不算命令`() {
        // commands/execute 的 attachments 是另一套形状，混用会静默丢附件。
        assertNull(commandCandidate("/export", referenceCount = 0, attachmentCount = 2))
    }

    @Test
    fun `普通正文不是命令候选`() {
        assertNull(commandCandidate("帮我看看这个函数", 0, 0))
    }

    @Test
    fun `只有一个斜杠不算命令`() {
        // 用户可能就是想发一个孤零零的斜杠。
        assertNull(commandCandidate("/", 0, 0))
        assertNull(commandCandidate("   /   ", 0, 0))
    }

    @Test
    fun `空草稿没有任何候选`() {
        assertNull(commandCandidate("", 0, 0))
        assertNull(commandCandidate("   ", 0, 0))
    }
}
