package com.revxzxcx.remotewebapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.revxzxcx.remotewebapp.core.AppContainer
import com.revxzxcx.remotewebapp.core.notify.NoticeActionHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程级的输入法 inset 桥（用户 m21177 附加反馈的修复载体）。
 *
 * **为什么需要它**：MainActivity 调了 `enableEdgeToEdge()`
 * （= `WindowCompat.setDecorFitsSystemWindows(window, false)`），Android 15 起
 * `EDGE_TO_EDGE_ENFORCED` 又让 `adjustResize` 彻底失效，窗口不再为输入法收缩。
 * 按文档应当由 Compose 的 `Modifier.imePadding()` 消费 `WindowInsets.ime`，
 * 但**在 MuMu（Android 15 + 搜狗输入法）上实测 `WindowInsets.ime` 恒为 0**
 * （`ime bottom=0.0.dp`，而同一时刻 `WindowInsets.statusBars` 正常返回 41dp，
 * 说明 inset 通道是通的、只有 ime 这一路没有值）。
 *
 * 所以退回框架层：在 DecorView 上挂 `OnApplyWindowInsetsListener` 直接读
 * `WindowInsetsCompat.Type.ime()`（这是不受 Compose 影响的原生路径），
 * 把像素高度放进这个 StateFlow，再由 AppRoot 换算成 dp 做 padding。
 *
 * 放在 Application 上而不是 Activity：输入法弹起/收起要跨 Activity 重建保持，
 * 而 `rememberSaveable` 存不了像素值。
 */
object ImeInsets {

    private val _bottomPx = MutableStateFlow(0)

    /** 输入法占据的像素高度；0 表示输入法没有弹出。 */
    val bottomPx: StateFlow<Int> = _bottomPx

    /** 由 MainActivity 的 inset 监听器调用。 */
    fun update(bottomPx: Int) {
        if (_bottomPx.value != bottomPx) _bottomPx.value = bottomPx
    }
}

/**
 * 应用入口，持有唯一的 [AppContainer]。
 *
 * 本期不引入 Hilt：模块数量还少，一个手工容器 + `ViewModelProvider.Factory` 足够，
 * 也少一个注解处理器（决策记录 D05 的补充）。
 */
class DshApplication : Application(), NoticeActionHost {

    /** 应用级依赖容器，进程内唯一。 */
    lateinit var container: AppContainer
        private set

    /**
     * 通知里「批准 / 拒绝」按钮落到这里（R056）。
     *
     * **这个 `implements` 不是可选的。** [NoticeActionReceiver] 第一件事就是
     * `context.applicationContext as? NoticeActionHost`：拿不到就只撤掉通知条然后返回。
     * 少了它，用户点「批准」的观感是「通知消失了，但什么都没发生」——比按钮不存在更糟，
     * 因为任务其实还在原地等授权，界面上那张审批卡也还在。
     *
     * 真机验收时这个遗漏就露过一次：单测只测 [AppContainer.answerFromNotification]，
     * 从不经过 `Application` 这一跳，所以全绿。
     */
    override fun answerFromNotification(eventId: String, allow: Boolean) {
        container.answerFromNotification(eventId, allow)
    }

    /**
     * 用户在常驻通知里点了「退出」（R057）。
     *
     * 这个 implements 与 [answerFromNotification] 同样不是可选的：少了它，
     * 接收器走 `as? NoticeActionHost` 拿不到宿主，只撤通知不做事——用户点「退出」，
     * 通知条消失，服务却还在跑、下一帧又把它画回来。**那比按钮不存在更糟**，
     * 因为他会以为点成功了。
     */
    override fun exitServiceFromNotification() {
        container.exitLinkService()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        watchForeground()
        // 🔴 连接必须**由进程**建，不能等界面（R057 审查 P1）。
        //
        // 早先只有 `AppRoot` 的 `LaunchedEffect(Unit)` 调 `bootstrap()`。那条路径在
        // 用户打开 App 时成立，但 R057 的两个核心场景根本不经过界面：
        // 开机自启（`BootReceiver` 直接起服务）与 `START_STICKY` 重启（系统拉起服务）。
        // 那两刻没人建连接，`watchCarrier` 不跑，服务拿不到任何载波帧 →
        // 常驻通知整夜写「正在连接桌面端」、时长永不出现，而用户不打开 App 就永远等不到。
        //
        // 放在这里而不是服务里：Application.onCreate 一定先于任何组件，
        // 且服务被拉起时进程必然已经走过这里，两个入口天然有序。`bootstrapOnce`
        // 用 CAS 兜住并发，界面那边再调一次也只是空转。
        container.bootstrapOnce()
    }

    /**
     * 把「App 现在是不是在前台」推给通知协调器（R056）。
     *
     * **为什么用 [ProcessLifecycleOwner] 而不是 Activity 生命周期**：
     * 1. 分屏/画中画时 Activity 仍可见，但用户注意力可能在另一半屏——按 Activity 判会漏发通知；
     * 2. 反过来，从通知点进来、旋转屏幕重建时 Activity 会经历 `onStop → onStart`，
     *    按 Activity 判会误判成「切走了」而多发一条通知；
     * 3. 进程级生命周期只在**所有** Activity 都停下之后才 `onStop`，语义正好是「用户离开了 App」。
     *
     * 判错的代价是双向的：判「在前台」则该响的不响（用户以为任务卡住），
     * 判「在后台」则用户眼皮底下弹横幅（更烦人）。所以这条信号必须准。
     *
     * 观察者加在 `Application.onCreate`（主线程，且早于任何 Activity），
     * 而不是某个 Composable 里——通知的触发不依赖任何界面是否活着，这也正是
     * 「切到后台」这个场景的全部意义。
     */
    private fun watchForeground() {
        val notifier = container.notifier
        // R056 / Q3：进后台的时刻也要记进偏好。`DeathWatcher` 靠它区分「用户正开着 App 时
        // 进程没了」（那是崩溃，提示去改电池设置等于答非所问）与「切出去之后被系统杀掉」。
        //
        // 跟着 `onStop` 而不是 `onPause`：`onPause` 在弹权限框、下拉通知栏时都会触发，
        // 用它会把「其实还开着」记成进后台，从而把一次崩溃误判成系统杀的。
        val watcher = container.deathWatcher
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        // 先按当前实际状态对齐一次。进程刚起来时还没 Activity 启动，
        // 这里会是 false——这是对的：此刻用户确实还没看到界面。
        notifier.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    notifier.setForeground(true)
                    // 用户很可能是离开 App 去系统设置里开通知权限，回来时不会有
                    // 任何权限回调（那个回调只在弹窗路径上有）。补一次后台连接的重试，
                    // 否则「授权后服务永远起不来」——第五轮审查 P1。
                    container.onAppForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    notifier.setForeground(false)
                    watcher.onBackgrounded()
                }
            },
        )
    }
}
