package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边沿判定（R056）。
 *
 * 这一层的全部价值是「**不要把冷启动当成几十条任务同时跑完**」。所以这里的用例
 * 大多在断「不该发」而不是「该发」——发不出去只是少一次提醒，发多了用户会
 * 直接把频道关掉，那之后就一条都收不到了。
 */
class CompletionEdgeTest {

    @Test
    fun `第一次见到一个会话时无法判定`() {
        val edge = CompletionEdge()
        assertNull("第一次见到就判成跑完，冷启动会一次涌出几十条通知", edge.observe("s1", false))
    }

    @Test
    fun `第一次见到时记着它在跑 之后停下才算跑完`() {
        val edge = CompletionEdge()
        assertNull(edge.observe("s1", true))
        assertEquals(true, edge.observe("s1", false))
    }

    @Test
    fun `一直在跑不算跑完`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        assertEquals(false, edge.observe("s1", true))
        assertEquals(false, edge.observe("s1", true))
    }

    @Test
    fun `一直停着不算跑完 停着再来一帧也不该重复通知`() {
        val edge = CompletionEdge()
        edge.observe("s1", false)
        assertEquals(false, edge.observe("s1", false))
        assertEquals("第二次 false 不能又判成跑完一次", false, edge.observe("s1", false))
    }

    @Test
    fun `跑完一次之后再有 stop 帧不能重复报`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        assertEquals(true, edge.observe("s1", false))
        assertEquals("同一次结束只该通知一次", false, edge.observe("s1", false))
    }

    @Test
    fun `两个会话互不干扰`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        edge.observe("s2", true)
        assertEquals(true, edge.observe("s1", false))
        assertEquals(false, edge.observe("s2", true))
        assertEquals(true, edge.observe("s2", false))
    }

    @Test
    fun `forget 之后重新变成第一次见到`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        edge.forget("s1")
        assertNull("忘掉之后不该拿旧状态判边沿", edge.observe("s1", false))
    }

    @Test
    fun `forget 不存在的会话不出错`() {
        val edge = CompletionEdge()
        edge.forget("never-seen")
        assertEquals(0, edge.size)
    }

    @Test
    fun `isRunning 反映最近一帧`() {
        val edge = CompletionEdge()
        assertFalse(edge.isRunning("s1"))
        edge.observe("s1", true)
        assertTrue(edge.isRunning("s1"))
        edge.observe("s1", false)
        assertFalse(edge.isRunning("s1"))
    }

    @Test
    fun `size 记的是会话数而不是帧数`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        edge.observe("s1", false)
        edge.observe("s1", true)
        edge.observe("s2", true)
        assertEquals(2, edge.size)
    }

    @Test
    fun `换主机清空账本后 同一个会话重新从第一次见开始`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        edge.observe("s1", true)
        edge.reset()
        // 不清的话这里会返回 true（旧账记着它在跑），凭空发一条「任务结束」——
        // 而新主机上的 s1 可能正要开始跑。会话 id 是每台主机各自的。
        assertNull(edge.observe("s1", false))
        // 这一行 observe 又把 s1 记进去了，所以是 1 而不是 0——断言 size 是为了证明
        // reset 真的清掉了旧账（否则这里是 1 但带着错误的「在跑」状态）。
        assertEquals(1, edge.size)
    }

    @Test
    fun `清空账本之后 isRunning 不再认为它在跑`() {
        val edge = CompletionEdge()
        edge.observe("s1", true)
        assertTrue(edge.isRunning("s1"))
        edge.reset()
        assertFalse(edge.isRunning("s1"))
    }
}
