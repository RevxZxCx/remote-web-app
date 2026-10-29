package com.revxzxcx.remotewebapp.core

import com.revxzxcx.remotewebapp.core.cache.CacheMappers
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「当前凭证簿 + 它对应那条连接」的**原子快照**（B004）。
 *
 * ## 为什么必须粘在一起
 *
 * 早先这两样是分开读的：UI 从 `AppContainer.book` 取「当前是哪一台主机」，再从
 * `AppContainer.connection` 取连接句柄。切换主机时簿先发布、连接后安装，两者之间有一个
 * 真实的挂起点（`installConnection` 里 `withContext(Dispatchers.IO)` 预热缓存库）：
 *
 * 1. 簿发布 → 主线程下一帧重组，`activeHost` 已经是新主机；
 * 2. 重组时读 `container.connection` 拿到的**还是旧连接**；
 * 3. `HomeViewModel` 的 key 变成 `home-<新设备>`，于是新建一个 VM，但它捕获的是旧连接；
 * 4. 之后新连接才装上，而 `AppState` 是 `Paired → Paired`——[StateFlow] 对相等值不发射，
 *    `AppState.Paired` 又不带载荷，**没有任何后续事件再去重建那个 VM**。
 *
 * 新 VM 握着旧连接，旧连接被 `close()` 后它的流以「正常完成」结束（不带 cause、不触发
 * `retryWhen`），页面就永远停在旧主机的工作区，只有重启应用才恢复——这就是用户报的 B004。
 *
 * 粘成一个 [data class] 之后，[LinkStore] 每次发射的都是自洽的一份，UI 只读一次，
 * 结构上不可能读到「新簿 + 旧连接」。
 *
 * @property book 当前凭证簿（设备栏直接观察它画列表）。
 * @property connection 与 `book.active` 对应的连接；未配对 / 无可用主机时为 `null`。
 * @property generation 连接代次。**每次重建连接自增**，复用连接时不变。
 */
data class ActiveLink(
    val book: CredentialBook,
    val connection: DshConnection?,
    val generation: Int,
) {
    /** 当前激活的主机；没有可用主机时为 `null`。 */
    val activeHost: DeviceCredential?
        get() = book.active

    /**
     * 当前分区键（凭证指纹）；未配对为空串。
     *
     * 与 [activeHost] 同源，因此不会出现「簿是新的、ownerId 是旧的」这种半新半旧组合
     * （R054 的 KDoc 已经警告过分开两次读会在换主机的瞬间拿到不一致的组合）。
     */
    val ownerId: String
        get() = activeHost?.let { CacheMappers.ownerIdOf(it) } ?: ""

    /**
     * ViewModel key 的作用域令牌。
     *
     * **必须带 [generation]**：`HomeViewModel` / `ChatViewModel` / `FileViewerViewModel`
     * 都只在 key 变化时重建，而它们把连接**捕获在 factory 里**。只按 `deviceId` 做 key
     * 时，「换设备」能换 VM，但「同一台设备换地址」（`updateEndpoint`）不会——于是 VM
     * 继续握着旧 `baseUrl` 的连接，症状与 B004 一模一样。代次一自增，所有带它的 key
     * 立刻失效，VM 必然重建并重新捕获新连接。
     */
    val scopeToken: String
        get() = "${activeHost?.deviceId ?: "none"}-$generation"

    companion object {
        /** 未配对 / 空簿的初始值。 */
        val EMPTY: ActiveLink = ActiveLink(CredentialBook.EMPTY, null, 0)
    }
}

/**
 * 换成 [book] 这个快照时，现有连接该怎么办。
 *
 * 抽成纯函数是为了能在 JVM 单测里直接断言：这段判断原先写在
 * `AppContainer.installConnection` 里，而那个方法是 `suspend` 且要真建 [DshConnection]，
 * 没法在不碰网络的前提下测（M57 复审 P1 的教训：编排留在没有测试覆盖的地方，
 * 改回错误写法单测依然全绿）。
 */
sealed interface ConnectionPlan {

    /**
     * 端点没变，可以就地复用现有连接。
     *
     * 复用是**必须**的，不是优化：`HomeViewModel` / `ChatViewModel` 在 Activity 的
     * ViewModelStore 里仍持有旧连接，关掉再建会让旧流以「正常完成」结束，页面停在旧内容
     * 且不报错（见 [ActiveLink] 的 KDoc）。
     */
    data class Reuse(val connection: DshConnection, val credential: DeviceCredential) : ConnectionPlan

    /** 端点变了 / 还没有连接，必须新建一条，并关掉旧的。 */
    data class Rebuild(val credential: DeviceCredential) : ConnectionPlan

    /** 没有可用主机了（空簿 / 全部失效），连接该关掉。 */
    data object Drop : ConnectionPlan
}

/**
 * 决定换成 [book] 时要怎么处理现有连接。
 *
 * 判据只比**影响 socket 的字段**（`baseUrl` + `deviceId`）：多主机模式下 `label` /
 * `hostId` 都会变，用整个 [DeviceCredential] 相等会让「改个显示名」触发重建连接，
 * 而那正是 [ActiveLink] KDoc 里描述的坑。
 */
fun planFor(current: DshConnection?, book: CredentialBook): ConnectionPlan {
    val target = book.active?.takeIf { it.isUsable } ?: return ConnectionPlan.Drop
    val reusable = current != null && current.baseUrl == target.baseUrl &&
        current.deviceId == target.deviceId
    return if (reusable) ConnectionPlan.Reuse(current, target) else ConnectionPlan.Rebuild(target)
}

/**
 * [ActiveLink] 的唯一发布点（B004）。
 *
 * 存在的理由有两个，缺一不可：
 * 1. **原子性**——簿与连接在同一次 `_link.value = ...` 里换掉，观察者永远读不到
 *    「新簿 + 旧连接」；
 * 2. **代次自增只在一处**——散落在各个调用点里迟早会漏（漏了就是 ViewModel 抓着旧连接
 *    不放，正是 B004 的形状），收进来之后「换连接必自增」变成这个类的不变量，可以被
 *    单测钉住。
 */
internal class LinkStore(initial: ActiveLink = ActiveLink.EMPTY) {

    private val _link = MutableStateFlow(initial)

    /** UI 观察这一个 flow 就够，不需要再分开读簿和连接。 */
    val link: StateFlow<ActiveLink> = _link.asStateFlow()

    /** 当前快照（非响应式读，供 getter 用）。 */
    fun current(): ActiveLink = _link.value

    /**
     * 一次性发布新的簿与连接。
     *
     * **代次由这里自己算**，不接受调用方传「要不要自增」：那样每个调用点都得记住在
     * 新建连接时置 `true`、在复用/改名时置 `false`，漏一处就是「ViewModel 抓着旧连接
     * 不放」——正是 B004 的形状。改成比较连接**实例身份**之后，「连接换了就自增」
     * 成为 [LinkStore] 自己的不变量，调用方无从写错：
     *
     * - 复用连接 → 同一个实例 → 代次不变（改显示名不会把整组 VM 重建一遍）；
     * - 新建连接 → 新实例 → 代次自增（所有带 [ActiveLink.scopeToken] 的 key 立刻失效）；
     * - 掉回未配对 → `null` → 代次自增（原来是 `null` 则不变，冷启动不发无意义的重组）。
     */
    fun publish(book: CredentialBook, connection: DshConnection?): ActiveLink {
        val previous = _link.value
        val generation = if (previous.connection !== connection) previous.generation + 1 else previous.generation
        val next = ActiveLink(book = book, connection = connection, generation = generation)
        _link.value = next
        return next
    }
}
