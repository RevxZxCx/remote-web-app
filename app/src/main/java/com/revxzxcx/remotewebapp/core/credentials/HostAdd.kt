package com.revxzxcx.remotewebapp.core.credentials

import java.util.UUID

/**
 * 「添加远程」这一步要写进凭证簿的内容，以及它是不是一次**重复添加**（B005）。
 *
 * @property credential 落盘后的那台（已带 `hostId`）。
 * @property alreadyListed 同一个地址此前已经登记过主机。
 */
data class AddedHost(
    val credential: DeviceCredential,
    val alreadyListed: Boolean,
)

/**
 * [planHostAdd] 的结果。
 *
 * @property book 写之后的新簿。
 * @property added 刚添加那台 + 是否重复。
 */
data class HostAddPlan(
    val book: CredentialBook,
    val added: AddedHost,
)

/**
 * 算出「把 [credential] 加进 [book]」的结果，**不改任何状态**。
 *
 * 抽成纯函数而不是写在 `AppContainer` 里，是因为 `AppContainer` 需要 `Context`、
 * 只能在设备上构造，容器层至今没有任何 JVM 单测。B005 的两个关键判断
 * （「是不是重复」与「返回哪一条」）都在这里，正好可以纯 JVM 钉住。
 *
 * ## 两个必须按顺序的地方
 *
 * 1. **先判重复，再落盘**。`withUpsert` 之后同一个地址只剩一行，事后回查永远
 *    看不出「原来就有」。真机现场是用户反复点配对、列表堆到 11 行同一台机器。
 * 2. **返回的那条按地址回查，不能用 `active`**。本操作的契约是「不改变当前主机」，
 *    所以 `book.active` 是**原来那台**。早先这里读的就是 `active`，
 *    只因配对协调器自己先调了默认激活的 `store.save()`，`active` 恰好已经变成新主机，
 *    才看着像对的（B005 的第三层）。
 *
 * @param newHostId 生成新 `hostId` 的方式；默认随机 UUID，测试可注入固定序列。
 */
fun planHostAdd(
    book: CredentialBook,
    credential: DeviceCredential,
    newHostId: () -> String = { UUID.randomUUID().toString() },
): HostAddPlan {
    // ⚠️ 必须在 withUpsert 之前判，理由见 KDoc 第 1 条。
    val alreadyListed = book.hostWithBaseUrl(credential.baseUrl) != null
    val next = book.withUpsert(credential, makeActive = false, newHostId = newHostId)
    // ⚠️ 按地址回查刚写进去那台，不能用 next.active，理由见 KDoc 第 2 条。
    val saved = next.hostWithBaseUrl(credential.baseUrl)
        ?: next.hosts.lastOrNull()
        ?: credential
    return HostAddPlan(
        book = next,
        added = AddedHost(credential = saved, alreadyListed = alreadyListed),
    )
}
