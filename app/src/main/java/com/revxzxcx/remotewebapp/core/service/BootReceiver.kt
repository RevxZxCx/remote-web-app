package com.revxzxcx.remotewebapp.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.revxzxcx.remotewebapp.DshApplication

/**
 * 开机后把后台连接拉起来（R057）。
 *
 * ## 为什么 `specialUse` 才能在开机广播里起服务
 * Android 15 起，`BOOT_COMPLETED` 接收器**不允许**启动这几类前台服务：
 * `dataSync` / `camera` / `mediaPlayback` / `phoneCall` / `mediaProjection`
 * （`microphone` 自 14 起已禁）。`specialUse` **不在**这个清单里，
 * 而 `BOOT_COMPLETED` 本身在「后台启动前台服务的豁免情形」清单里。
 * 两个条件同时成立，这条路才通——这也是 R056 时我误判「开机自启被官方堵死」的原因：
 * 那次是拿 `dataSync` 当默认选项推出来的结论。
 *
 * ## 三个开关都要判
 * 「保持后台连接」+「开机后自动连接」+ 通知可用，缺一不可（见 [shouldStartOnBoot]）。
 * 任何一个不满足就**静默返回**——用户没要求，开机弹一条「连不上了」是骚扰。
 *
 * ## 为什么不在这里读凭证
 * 服务本身不建连接（见 [ForegroundLinkService] 的 KDoc），它只负责把进程提到前台。
 * 连接由 App 进程启动后由 `AppContainer.bootstrap()` 建。所以这里只要判断
 * 「该不该起来」，不需要知道连哪台。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // 任何进入都要留一行日志（R057-8）：此前「不该起」的路径一行都不打，
        // 真机排查开机自启时完全分不清「广播没来」和「来了但判定不该起」，
        // 白白多花一轮重启验证。
        Log.i(TAG, "收到广播：$action")
        // 只认开机与「本包被替换」，其余广播一律不理。
        //
        // ⚠️ 这里**不接** `LOCKED_BOOT_COMPLETED`（R057 第四轮审查 P3）：它只在
        // `directBootAware` 的组件上才会送达，而本 receiver 与 Application 都不是
        // ——凭证在 Keystore + 文件里，直接启动阶段根本读不到，提前起来只会拿到空簿
        // 再回退成 `Unpaired`，反而多一条假状态。清单里也已一并去掉，三处保持一致。
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "不是开机广播，忽略")
            return
        }

        val app = context.applicationContext as? DshApplication ?: run {
            Log.w(TAG, "applicationContext 不是 DshApplication，跳过开机自启")
            return
        }
        val container = app.container
        try {
            // `startOnBoot` **自己就会启动服务**（见 LinkServiceController.startOnBoot）。
            // 早期版本这里写成 `if (container.startServiceOnBoot()) ForegroundLinkService.start(context)`
            // —— 服务被启动两次（R057 审查 P2-6）：第二次进来时 `onStartCommand` 又跑一遍，
            // 部分 ROM 上第二次 `startForegroundService` 会抛
            // `ForegroundServiceStartNotAllowedException`，进 catch 后把开关关掉，
            // 于是「开机自启开着，第一次开机就自己关了」。
            val started = container.startServiceOnBoot()
            Log.i(TAG, "开机自启判定：started=$started")
        } catch (e: Exception) {
            // 开机阶段系统可能还没准备好，`startForegroundService` 会抛
            // `ForegroundServiceStartNotAllowedException`。这里不能崩——
            // 崩了就是「开机后 App 闪退」的观感，而用户根本没打开过它。
            Log.w(TAG, "开机自启失败", e)
            container.onServiceStartFailed(e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
