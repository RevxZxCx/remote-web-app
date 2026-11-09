package com.revxzxcx.remotewebapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.revxzxcx.remotewebapp.core.notify.NoticeIntents
import com.revxzxcx.remotewebapp.core.notify.NotificationCenter
import com.revxzxcx.remotewebapp.ui.AppRoot
import com.revxzxcx.remotewebapp.ui.theme.DshAssistantTheme

/**
 * 唯一 Activity。
 *
 * 屏幕切换用 [AppRoot] 内部的 Compose 状态机（配对 → 工作区 → …），不引入 navigation-compose：
 * 本期只有 4~6 个屏、且没有深链接需求，`when` + `rememberSaveable` 足够，还少一个依赖。
 *
 * 清单里声明 `launchMode="singleTop"`（R056）：点通知时如果 App 已经在栈里，系统会把新的
 * intent 交给**已有的这个实例**走 [onNewIntent]，而不是再起一个 Activity。没有它的话
 * 点一次通知就叠一个 MainActivity，返回要按好几次才退出。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // `enableEdgeToEdge()` 让窗口不再为输入法收缩（Android 15 的 EDGE_TO_EDGE_ENFORCED
        // 连 `adjustResize` 都一并失效），而 Compose 的 `Modifier.imePadding()` 在
        // MuMu + 搜狗输入法上实测拿不到 inset（见 ImeInsets 的 KDoc）。
        // 所以在 DecorView 上挂框架层监听，把原生 ime inset 桥进 Compose。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            ImeInsets.update(ime)
            insets
        }

        // 冷启动走 onCreate（进程原本不在）。注意**不能**只在这里 dispatchIntent：
        // App 已在后台时点通知走的是 onNewIntent，那条路也必须处理，否则「从后台点通知」
        // 这个最主流的场景会完全失效（而它偏偏是最难在开发机上复现的）。
        dispatchIntent(intent)

        val container = (application as DshApplication).container
        setContent {
            // 主题偏好必须在 setContent 之外同步读一次初值（冷启动第一帧就生效），
            // 之后交给 StateFlow 驱动重组。M5-9。
            val themeMode by container.themePreferences.mode.collectAsState()
            DshAssistantTheme(themeMode = themeMode) {
                AppRoot(container = container)
            }
        }
    }

    /**
     * `singleTop` 下从通知再次进入走这里，而**不是** `onCreate`。
     *
     * 这是「点通知没跳对页」最常见的原因：只在 `onCreate` 读 `intent` 的实现，
     * App 在后台时永远读不到新参数，看到的还是最初启动那次的老 intent。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 系统会把新 intent 交给这个实例，但 `getIntent()` 仍返回旧的；
        // 同步一次，之后任何读 `intent` 的地方（含进程重建后的 `savedInstanceState` 路径）都对得上。
        setIntent(intent)
        dispatchIntent(intent)
    }

    /**
     * 把通知带来的会话 id 投进 [NoticeIntents]。
     *
     * 只做投递、不在这里导航：此刻组合可能还没建立（冷启动），也可能已经在看别的页面
     * （热启动）。真正「打开会话」的时机由 `AppRoot` 决定，见那边的 `LaunchedEffect`。
     */
    private fun dispatchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != NotificationCenter.ACTION_VIEW) return
        NoticeIntents.offer(intent.getStringExtra(NotificationCenter.EXTRA_SESSION_ID))
        // 另一个信箱：点「后台连接断了」那条要落到设置页（R057 / 用户 Q5 拍板）。
        // 与会话 id 分开判断——两者可以同时存在（一条通知带 id、另一条带这个标记），
        // 但绝不共用同一个键。
        if (intent.getBooleanExtra(NotificationCenter.EXTRA_OPEN_SETTINGS, false)) {
            NoticeIntents.offerSettings()
        }
    }
}
