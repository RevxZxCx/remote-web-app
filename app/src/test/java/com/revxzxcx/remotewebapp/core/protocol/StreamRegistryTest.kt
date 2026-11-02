package com.revxzxcx.remotewebapp.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [drainConcurrent] 的回归测试 —— B008 崩溃。
 *
 * 真实崩溃栈（手机 PKG110 / Android 16，切主机时）：
 * ```
 * java.util.NoSuchElementException
 *     at java.util.concurrent.ConcurrentHashMap$ValueIterator.next
 *     at kotlin.collections.CollectionsKt___CollectionsKt.toList(_Collections.kt:1329)
 *     at RemoteStreamMux.close(RemoteStreamMux.kt:217)
 *     at DshConnection.close(DshConnection.kt:103)
 *     at AppContainer.installConnection(AppContainer.kt:360)
 *     at AppContainer.switchHost(AppContainer.kt:237)
 * ```
 * 根因是 `streams.values.toList()`：`Iterable.toList()` 在 `this is Collection` 时按
 * `size` 走快路径（`1 -> iterator().next()`），读 `size` 与取 `next()` 之间若有人清空
 * 了表就抛。**只在流数量恰好为 1 且并发清理时命中**，所以线上是偶发。
 *
 * 本文件用**真实并发**（`Thread` + `CountDownLatch`）反复压同一个「表里只有 1 条、
 * 两个线程同时摘」的场景，跑几千轮；旧写法在这种压测下几乎必然炸。
 */
class StreamRegistryTest {

    @Test
    fun `并发摘取同一条不会抛异常也不会重复摘到`() {
        repeat(3000) {
            val map = ConcurrentHashMap<String, String>()
            map["only"] = "value"
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val drainedTotal = AtomicInteger(0)
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())

            repeat(2) {
                Thread {
                    start.await()
                    try {
                        drainedTotal.addAndGet(drainConcurrent(map).size)
                    } catch (t: Throwable) {
                        errors += t
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("两个线程必须都在 5 秒内完成", done.await(5, TimeUnit.SECONDS))

            assertEquals("并发摘取不许抛任何异常，实测: $errors", 0, errors.size)
            assertEquals("同一条只能被摘到一次，否则会重复关闭同一个 Channel", 1, drainedTotal.get())
            assertTrue("摘完之后表必须是空的", map.isEmpty())
        }
    }

    @Test
    fun `逐条摘取不会漏掉并发注册的条目`() {
        repeat(500) {
            val map = ConcurrentHashMap<String, String>()
            repeat(8) { map["k$it"] = "v$it" }
            val start = CountDownLatch(1)
            val writerDone = CountDownLatch(1)
            val drained = java.util.Collections.synchronizedList(ArrayList<String>())
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())

            Thread {
                start.await()
                try {
                    repeat(8) { map["late$it"] = "late$it" }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    writerDone.countDown()
                }
            }.start()

            start.countDown()
            try {
                drained += drainConcurrent(map)
            } catch (t: Throwable) {
                errors += t
            }
            assertTrue(writerDone.await(5, TimeUnit.SECONDS))

            assertEquals("摘取期间不许抛，实测: $errors", 0, errors.size)
            assertEquals("一次摘取不许重复摘到同一个 key", drained.size, drained.toSet().size)

            // 剩下的必须能被下一次摘干净，且绝不出现「被 clear 掉却没被摘到」的条目
            val rest = drainConcurrent(map)
            val all = drained + rest
            assertEquals("摘到的总数必须等于曾经注册过的总数（8 旧 + 8 新）", 16, all.size)
            assertEquals("摘到的 key 不许重复", 16, all.toSet().size)
            assertTrue("两轮摘完表必须是空的", map.isEmpty())
        }
    }

    @Test
    fun `空表摘取返回空列表`() {
        val map = ConcurrentHashMap<String, String>()
        assertEquals(emptyList<String>(), drainConcurrent(map))
        assertTrue(map.isEmpty())
    }

    @Test
    fun `单条时也能正确摘到 这正是崩溃场景的形状`() {
        // `size == 1` 正是 toList() 快路径的条件，也就是线上崩溃的触发形状。
        repeat(2000) {
            val map = ConcurrentHashMap<String, String>()
            map["single"] = "boom"
            val drained = drainConcurrent(map)
            assertEquals(listOf("boom"), drained)
            assertTrue(map.isEmpty())
        }
    }
}
