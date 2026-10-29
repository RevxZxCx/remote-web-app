package com.revxzxcx.remotewebapp.ui.pair

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 扫码控件「重新武装」的能力（B002）。
 *
 * 之所以要单独抽一个接口，而不是在 UI 里直接持有 `BarcodeView`：`BarcodeView` 是
 * Android 类，只有 Robolectric 能构造，而 B002 的回归点恰恰是「点重新扫描之后有没有
 * 落到**同一个**控件上」——这条必须能在纯 JVM 单元测试里断言，否则只能靠真机目视。
 */
internal interface ScanRearmer {
    /**
     * 对**同一个**控件重新 `decodeSingle()`。
     *
     * 注意这里不能新建控件：新控件的 `isPreviewActive()` 还是 false 时
     * `startDecoderThread()` 直接 return 且无人重试，旧控件又还占着摄像头，
     * 于是扫码框永久不再识别二维码——这正是 B002 的成因。
     */
    /** @return 真的武装上了返回 true；控件此刻不可用（还没建好或刚释放）返回 false。 */
    fun rearmScan(): Boolean
}

/** [ScanChannel.rescan] 的三种结果。 */
internal enum class ScanRescanOutcome {
    /** 闸门本来是开的，没有需要重新武装的东西。 */
    NotConsumed,

    /** 已经对活着的控件重新武装。 */
    Rearmed,

    /** 控件此刻不在（还没建好或刚释放），等它挂上来再补做。 */
    Deferred,
}

/**
 * 扫码通道：把 `decodeSingle` 的「一次性」这件事显式建模出来（B002）。
 *
 * `com.journeyapps.barcodescanner.BarcodeView` 的字节码事实（4.3.0，`javap -c` 实证）：
 * - `decodeSingle(cb)` 只做三件事：`decodeMode = SINGLE`、`callback = cb`、
 *   `startDecoderThread()`；
 * - `startDecoderThread()` 在 `decodeMode == NONE` 或 `!isPreviewActive()` 时**直接
 *   return，且没有任何重试**；
 * - 回调送达之后，`handleMessage` 发现 `decodeMode == SINGLE` 会顺手调
 *   `stopDecoding()`，而 `stopDecoding()` 把 `callback` 置空、`decodeMode` 归 NONE。
 *
 * 也就是说：**扫到一次之后，控件自己就把自己关掉了**，再往它身上"等"下一个码是等不到的。
 * 旧实现靠 `key(scanEpoch)` 把整个 `AndroidView` 拆了重建，而重建会与旧控件的相机关闭
 * 抢同一个摄像头，于是「配对失败之后扫码框再也不识别二维码，只有退出本页重进才好」。
 *
 * 现在的语义是：闸门（[isConsumed]）与控件解耦，重新扫 = 复位闸门 + 对**同一个**控件
 * 重新 `decodeSingle()`，控件本身始终不重建。
 */
internal class ScanChannel {
    /**
     * 闸门是否已经落下（扫到过一次且还没重新武装）。
     *
     * 必须是 Compose 可观察状态：UI 靠它决定要不要显示「重新扫描」入口。
     */
    var isConsumed: Boolean by mutableStateOf(false)
        private set

    private var rearmer: ScanRearmer? = null

    /**
     * 有待补做的武装。产生于两种情形：①点了「重新扫描」但控件此刻不在；
     * ②配对进行中（`enabled == false`）扫到了码，不能立刻重新武装（会与正在进行的那次
     * 配对抢解码线程），等配对结束后再补。
     */
    private var pendingRearm: Boolean = false

    /**
     * 控件建好了：记下来，并把欠着的那次武装补上。
     *
     * 这里必须**顺带把闸门复位**。切到「链接」再切回「扫码」时 [PairScreen] 里的
     * `ScanCard` 会整个离开组合又重新进来，新控件的 `factory` 里已经
     * `decodeSingle(callback)` 武装好了；如果闸门还停在上一轮控件的「已扫过」，
     * 新控件收到的第一个码会被 [onResult] 静默丢弃，界面却还挂着「重新扫描」——
     * 表现和 B002 一模一样，只是触发入口从「配对失败」换成了「切标签」。
     *
     * 因为 `factory` 无论如何都会武装新控件，这里的补做即使因控件还没就绪而空转，
     * 也不会丢功能。
     */
    fun attach(next: ScanRearmer) {
        rearmer = next
        isConsumed = false
        drainPending()
    }

    /** 控件释放了：只有当前记着的那个才清空，避免旧控件的 `onRelease` 把新控件的引用抹掉。 */
    fun detach(target: ScanRearmer) {
        if (rearmer === target) rearmer = null
    }

    /**
     * 新建的控件已经武装好了（`AndroidView.factory` 里注册完 `decodeSingle` 之后调用）。
     *
     * 这里做两件事，都不能只做一半：
     *
     * ① **销账**：欠账的产生时机是「控件不在」，而 `factory` 无条件会给新控件注册一次
     * `decodeSingle`，所以欠账到这里就已经被满足了。不销账，`pendingRearm` 会跨控件一直
     * 挂着，之后任意一次 `onEnabled(true)` 都会多触发一次 `decodeSingle()`——多拉一条
     * 解码线程去抢同一个摄像头。
     *
     * ② **复位闸门**：一个刚武装好的控件一定能交付下一个码。若闸门还停在上一轮控件的
     * 「已扫过」，新控件收到的第一个码会被 [onResult] 静默丢弃。这条路径不经过 [attach]
     * ——例如相机权限被撤掉又授予（`hasPermission` 一变，`AndroidView` 就整个离开组合再
     * 回来，而 [attach] 所在的 `DisposableEffect` 的 key 里没有它，不会重跑），于是闸门
     * 会一直关着。虽然那时界面还挂着「重新扫描」可以救回来，但白扫一次码本身就是缺陷。
     *
     * 复位闸门不会让同一个 token 被提交两次：配对进行中（`enabled == false`）扫到的码
     * 只会记一笔欠账，不会交付（见 [onResult] 的分支顺序）。
     */
    fun onControlArmed() {
        pendingRearm = false
        isConsumed = false
    }

    /**
     * 收到一个解码结果。
     *
     * @return 需要交给上层去配对时返回文本，否则返回 null（不交付）。
     */
    fun onResult(text: String?, enabled: Boolean): String? {
        if (isConsumed) return null
        if (!enabled) {
            // 配对中扫到了：这次不能交付，但底层的 `stopDecoding()` 已经发生，
            // 必须记一笔，等配对结束补一次武装，否则通道就此静默死掉。
            // 这里**不能**立刻补：会在配对进行中重新拉起解码线程。
            pendingRearm = true
            return null
        }
        if (text.isNullOrBlank()) {
            // ⚠️ 空文本同样意味着底层 `handleMessage` 已经走完并 `stopDecoding()` 过了
            // （它只要求 `msg.obj != null`，不要求文本非空）。此时闸门还没落下、界面不会
            // 给「重新扫描」，若不在这里补一次武装，通道就静默死掉——又是 B002。
            // 空闲态可以立刻补。
            pendingRearm = true
            drainPending()
            return null
        }
        isConsumed = true
        return text
    }

    /** 配对进行状态变化时调用：配对结束（`enabled` 变 true）就把欠着的武装补上。 */
    fun onEnabled(enabled: Boolean) {
        if (enabled) drainPending()
    }

    /**
     * 页面重新可见、相机重新可用时调用：**只在确实能用的时候**把欠着的武装补上。
     *
     * 为什么需要单独一条：配对进行中把 App 切到后台，配对随后失败，`enabled` 在后台
     * 变回 true，[onEnabled] 会立刻补武装；而重新武装要 `resume()` 相机才能起解码线，
     * 那等于在用户看不见的时候把摄像头又打开一次，把 R035「退到后台必须暂停相机」
     * 的成果抵消掉。真正的重新武装（`ScanRearmer.rearmScan` 会判定自己是否可用）
     * 因此会失败并保留欠账，等回到前台由这里补做。
     */
    fun onActive() {
        drainPending()
    }

    /** 用户点了「重新扫描」。 */
    fun rescan(): ScanRescanOutcome {
        if (!isConsumed) {
            // 闸门还开着就没必要打断底层；但底层可能刚因「配对中扫到」被关掉，
            // 这里顺手把欠账清掉，让按钮是幂等的。
            drainPending()
            return ScanRescanOutcome.NotConsumed
        }
        isConsumed = false
        pendingRearm = true
        return if (drainPending()) ScanRescanOutcome.Rearmed else ScanRescanOutcome.Deferred
    }

    /**
     * 把欠着的那次武装补上。
     *
     * ⚠️ 只有 [ScanRearmer.rearmScan] 真的武装成功才清 `pendingRearm`。`attach` 发生在
     * `DisposableEffect` 里，而 `AndroidView.factory` 稍后才把控件塞给 rearmer——这中间
     * 存在「rearmer 在、控件还不在」的窗口。若先清标志再调用，那次重新扫描就被静默吞掉，
     * 与 [ScanRescanOutcome.Deferred] 的承诺相反。
     *
     * @return 这次是否真的武装了。
     */
    private fun drainPending(): Boolean {
        if (!pendingRearm) return false
        val target = rearmer ?: return false
        if (!target.rearmScan()) return false
        pendingRearm = false
        return true
    }
}
