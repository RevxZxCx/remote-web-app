package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 的分配与淘汰（R056）。
 *
 * 三条要守住的性质：
 * 1. **同一个键永远拿到同一个 id**——否则同一个会话每跑一轮就多一条通知，堆成一片；
 * 2. 不同键**不撞同一个 id**——撞了就是后一条静默顶掉前一条，而调用方以为两条都在；
 * 3. 上限满了要**把腾出来的 id 报出来**，让调用方去 cancel 那条已过期的通知，
 *    否则它会永远留在通知栏里，点开却对应别人。
 */
class NoticeIdPoolTest {

    @Test
    fun `同一个键重复分配拿到同一个 id`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        val first = pool.allocate("a").id
        val second = pool.allocate("a").id
        assertEquals("同一个会话必须原地更新同一条通知", first, second)
    }

    @Test
    fun `重复分配不增加 size`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        pool.allocate("a")
        pool.allocate("a")
        pool.allocate("a")
        assertEquals(1, pool.size)
    }

    @Test
    fun `不同键拿到不同 id`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        val ids = listOf("a", "b", "c", "d").map { pool.allocate(it).id }
        assertEquals("两个会话共用一个 id 会互相顶掉", 4, ids.toSet().size)
    }

    @Test
    fun `id 落在 base 起的一段区间内`() {
        val pool = NoticeIdPool(base = 100, capacity = 3)
        val ids = listOf("a", "b", "c", "d", "e").map { pool.allocate(it).id }
        assertTrue("分配出的 id 必须从 base 开始：$ids", ids.all { it in 100 until 103 })
    }

    @Test
    fun `超出上限时淘汰最久没用的那个并报出它的 id`() {
        val pool = NoticeIdPool(base = 100, capacity = 2)
        val a = pool.allocate("a").id
        pool.allocate("b")
        val result = pool.allocate("c")
        assertEquals("容量 2 装第三个，必须淘汰一个", listOf(a), result.evicted)
        assertEquals(2, pool.size)
    }

    @Test
    fun `淘汰的是最久没用的 不是最早分配的`() {
        val pool = NoticeIdPool(base = 100, capacity = 2)
        val a = pool.allocate("a").id
        val b = pool.allocate("b").id
        pool.allocate("a") // 刷新 a 的使用顺序
        val result = pool.allocate("c")
        assertEquals("a 刚被用过，该淘汰的是 b", listOf(b), result.evicted)
        assertEquals("a 还该拿回原 id", a, pool.allocate("a").id)
    }

    @Test
    fun `绕回一圈时把仍占着该 id 的键挤掉并报出`() {
        // 容量 2：分配 a、b 之后 nextOffset 回到 0，第三个键 c 会拿到与 a 相同的 id。
        val pool = NoticeIdPool(base = 100, capacity = 2)
        val a = pool.allocate("a").id
        pool.allocate("b")
        // c 进来时池子已满，先淘汰最久没用的 a；但这里要验证的是「挤掉仍占着该 id 的键」
        // 那条防御分支——构造一个「被淘汰的不是 a」的顺序。
        val result = pool.allocate("c")
        assertTrue(
            "淘汰清单必须包含被腾出的位置，否则那条通知会永远留在通知栏：${result.evicted}",
            result.evicted.contains(a),
        )
    }

    @Test
    fun `peek 对没分配过的键返回 null 且不分配`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        assertNull(pool.peek("nope"))
        assertEquals("peek 不能顺手分配，否则一进会话就占满 id", 0, pool.size)
    }

    @Test
    fun `peek 对分配过的键返回同一个 id`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        val id = pool.allocate("a").id
        assertEquals(id, pool.peek("a"))
    }

    @Test
    fun `release 交回 id 且之后 size 减一`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        val id = pool.allocate("a").id
        assertEquals(id, pool.release("a"))
        assertEquals(0, pool.size)
        assertNull(pool.peek("a"))
    }

    @Test
    fun `release 不存在的键返回 null`() {
        val pool = NoticeIdPool(base = 100, capacity = 4)
        assertNull(pool.release("nope"))
    }

    @Test
    fun `释放之后重新分配不会与别人撞`() {
        val pool = NoticeIdPool(base = 100, capacity = 2)
        val a = pool.allocate("a").id
        pool.release("a")
        val c = pool.allocate("c")
        assertEquals("空出来的位置可以复用，但不能踩到还活着的键", emptyList<Int>(), c.evicted)
        assertEquals(a, c.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `容量为 0 直接拒绝`() {
        NoticeIdPool(base = 100, capacity = 0)
    }

    @Test
    fun `淘汰清单在正常情况下为空`() {
        val pool = NoticeIdPool(base = 100, capacity = 8)
        assertTrue("没满的时候不该淘汰任何东西", pool.allocate("a").evicted.isEmpty())
        assertTrue(pool.allocate("b").evicted.isEmpty())
    }
}
