package com.revxzxcx.remotewebapp.core.protocol

import java.util.concurrent.ConcurrentHashMap

/**
 * 把 [map] 里当前的条目**逐条原子摘出**并返回它们的值。
 *
 * 存在的理由只有一条：`streams.values.toList()` 会崩（B008）。Kotlin 的
 * `Iterable.toList()` 在 `this is Collection` 时按 `size` 走快路径：
 * `0 -> emptyList()`、`1 -> iterator().next()`、其余 `toMutableList()`。
 * 于是「读 `size`」与「取 `next()`」之间只要有人把表清空，`next()` 就抛
 * `NoSuchElementException`；`ConcurrentHashMap` 的 `values` 正是这种弱一致的
 * `Collection` 视图，所以这条崩溃只在**流数量恰好为 1** 且**并发清理**时偶发。
 *
 * 本函数用 `ConcurrentHashMap.forEach`（弱一致遍历，不抛）配合逐条 `remove`：
 * - 只有 `remove(key)` 真的返回了同一个值才计入，保证同一条不会被摘两次；
 * - 两个线程同时摘同一批是安全的，各自只能摘到自己成功 `remove` 掉的那些；
 * - 遍历期间新注册的条目留在表里等下一次，不会出现「被清掉却没被关闭」的空洞。
 */
internal fun <V> drainConcurrent(map: ConcurrentHashMap<String, V>): List<V> {
    val drained = ArrayList<V>(map.size)
    map.forEach { key, value ->
        if (map.remove(key) === value) drained += value
    }
    return drained
}
