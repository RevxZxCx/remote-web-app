package com.revxzxcx.remotewebapp.core.notify

/**
 * 把一个字符串键（会话 id / 请求 eventId）映射成一个**稳定**的通知 id，并给 id 设上限。
 *
 * ## 为什么要稳定
 * 同一个会话连跑五轮，`notify()` 必须用**相同 id** 才能原地更新那条通知
 * （配合 `setOnlyAlertOnce(true)` 只响第一次）。若每轮都换新 id，通知栏会堆五条，
 * 用户划掉一条还有四条——这比不通知更烦，而且会逼用户直接关掉整个频道。
 *
 * ## 为什么要上限
 * 会话 id 与 eventId 都是无界增长的长字符串。若直接 `hashCode()` 当 id：
 * ① 两个不同的键可能撞到同一个 id（后一条静默顶掉前一条）；
 * ② 用户可能真的同时开十个会话跑任务，通知栏十条也不算离谱，但**我们不该**允许
 *    任意多的存量——每一条都要占系统资源，且用户划掉后我们并不知道。
 *
 * 所以这里自己分配小整数 id，并按「最近使用」淘汰：淘汰时把腾出来的 id 一并返回，
 * 由调用方去 `cancel()` 那条已经过期的通知（否则那条通知会永远留在通知栏里，
 * 点开却对应一个已经被别人复用的 id）。
 *
 * **不是线程安全的**：只在通知协调器的一条收集协程 + 主线程调用点里用。
 */
class NoticeIdPool(
    /** 分配出的 id 从 `base` 开始，到 `base + capacity - 1` 用完一圈。 */
    private val base: Int,
    /** 最多同时追踪多少个键。 */
    private val capacity: Int,
) {

    /**
     * 一次分配的结果。
     *
     * @param id 该键对应的通知 id。
     * @param evicted 因为超出上限而被淘汰的 id；调用方应当把这些 id 的通知取消掉。
     *   正常情况下为空。
     */
    data class Allocation(val id: Int, val evicted: List<Int> = emptyList())

    /** 插入顺序 = 最近使用顺序（`LinkedHashMap` 的 `accessOrder = true` 会让 get 也刷新顺序）。 */
    private val ids = LinkedHashMap<String, Int>(0, 0.75f, true)

    init {
        require(capacity > 0) { "capacity 必须为正，当前 $capacity" }
    }

    /** 这个池子当前追踪的键数量（测试与诊断用）。 */
    val size: Int get() = ids.size

    /**
     * 取 [key] 的 id；没有就分配一个。
     *
     * 分配时若池子已满，会淘汰**最久没被用过**的那个（`LinkedHashMap` 迭代顺序的第一项）。
     *
     * ## 为什么要找「空闲」的 id 而不是维护一个游标
     * 早先的实现用一个只增不减的 `nextOffset` 轮转。它有个真实的坏结果：淘汰掉一个键
     * 之后游标可能正好落在**仍被别的键占着**的 id 上，于是为了塞进这一个新键，得把那个
     * 还活着的键也挤掉——**一次分配牺牲两条通知**，而调用方按返回的 `evicted` 去 cancel，
     * 其中一条本来是好的。用户看到的是「刚收到的通知莫名其妙消失了」。
     * 直接挑最小空闲 id 就没有这个问题：只要还有空位，淘汰清单里至多一条。
     */
    fun allocate(key: String): Allocation {
        ids[key]?.let { return Allocation(it) }

        val evicted = mutableListOf<Int>()
        var id = firstFree()
        if (id == null) {
            // 池子满了：淘汰最久没用的那个，它的 id 正好空出来给新键。
            val oldest = ids.entries.iterator()
            val victim = oldest.next()
            evicted += victim.value
            id = victim.value
            oldest.remove()
        }
        ids[key] = id
        return Allocation(id, evicted)
    }

    /**
     * `base until base + capacity` 里最小的、没有被任何键占着的 id。
     *
     * 满的时候返回 null（调用方先淘汰再复用）。这里遍历上限而不是无限试探：
     * 容量是本类的硬约束，超出它的 id 一律不发出去。
     */
    private fun firstFree(): Int? {
        val taken = ids.values
        for (offset in 0 until capacity) {
            val candidate = base + offset
            if (candidate !in taken) return candidate
        }
        return null
    }


    /** 已经分配过就返回 id，否则返回 null（**不分配**）。用于「取消某键的通知」。 */
    fun peek(key: String): Int? = ids[key]

    /** 主动放掉一个键（会话被删除 / 请求被撤回），返回它占着的 id。 */
    fun release(key: String): Int? = ids.remove(key)

    /**
     * 清空全部记录（换主机 / 退配对时用，见 `NotificationCenter.cancelAll`）。
     *
     * 只清本池的账，不负责撤通知——那些 id 由调用方 `cancelAll()` 一次性撤掉。
     * 之所以必须清：不清的话，新主机的会话会**复用到旧主机的 id**，而旧通知可能
     * 已经被 `cancelAll` 撤掉、也可能是同 id 的两条不同会话在竞争同一个槽位。
     */
    fun clear() {
        ids.clear()
    }
}
