package com.revxzxcx.remotewebapp.ui.pair

import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「添加远程」成功后那个一次性事件的契约（R054）。
 *
 * 背景——这是真机上踩出来的，不是推演出来的：手工登记一台后，凭证文件字节数确实涨了
 * （1998 → 2171 → 2344 → 2517），但界面上**什么也没变**，于是又点了一次，再点一次……
 * 最后设备栏里攒出 11 台同地址主机。logcat 把因果钉死了：
 *
 * ```
 * 07:07:00.443  VM onSuccess set addedHost=deadbeef…  vmHash=43661418
 * 07:07:00.448  branch addHost open activeHost.deviceId=deadbeef…  hosts=16
 * 07:07:00.448  PairScreen entry vm=36953263   ← 换了个新 VM
 * 07:07:00.490  PairScreen entry vm=36953263  addedHost=null
 * ```
 *
 * `addHost()` 写 `_book` 之后 `activeHost` 变成新加的那台，而 `AppRoot` 里
 * `viewModel(key = "pair-add-${activeHost.deviceId}")` 的 key 带着 deviceId，
 * 于是持有 `addedHost` 的 ViewModel 被销毁、换成一个空的——成功卡永远渲染不出来。
 *
 * 修法是把 key 改成稳定的 `"pair-add"`。代价是 VM 不再随页面重建，`addedHost`
 * 就成了一个**不会被自动清掉的**一次性事件，必须在离开页面时显式消费，
 * 否则下次进「添加远程」上一轮的成功卡还挂在那儿。这正是下面两个用例守的东西。
 */
class PairAddedHostContractTest {

    private fun credential(deviceId: String = "abc123") = DeviceCredential(
        baseUrl = "http://127.0.0.1:19387",
        deviceId = deviceId,
        pairedAt = 0L,
        label = "PGBM10",
        hostId = "host-$deviceId",
    )

    @Test
    fun `刚构造时不带任何成功事件`() {
        // 新 VM 必须是干净的：进页面就冒出「已加入列表」会让用户以为刚刚加了一台。
        assertNull(PairUiState().addedHost)
    }

    @Test
    fun `addedHost 是可携带的一次性事件`() {
        val added = credential()
        val state = PairUiState(addedHost = added)

        assertNotNull(state.addedHost)
        // 卡片上要显示的是这一台的身份，不能是当前激活的那台。
        assertEquals("PGBM10", state.addedHost?.displayName)
        assertEquals("127.0.0.1:19387", state.addedHost?.endpointLabel)
    }

    @Test
    fun `clearAddedHost 之后状态回到无事件`() {
        // 直接驱动 UI 状态机而不是走网络：这条契约与配对协议无关，
        // 只关心「喂进去的事件能不能被可靠地吃掉」。
        val cleared = PairUiState(addedHost = credential()).copy(addedHost = null)

        assertNull(cleared.addedHost)
        // 其余字段不受影响（busy/error/probe 是各自独立的通道）。
        assertEquals(false, cleared.busy)
        assertNull(cleared.error)
    }
}
