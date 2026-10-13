package com.revxzxcx.remotewebapp.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revxzxcx.remotewebapp.core.AppContainer
import com.revxzxcx.remotewebapp.core.AppState
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.ui.chat.ChatScreen
import com.revxzxcx.remotewebapp.ui.chat.ChatViewModel
import com.revxzxcx.remotewebapp.ui.home.HomeScreen
import com.revxzxcx.remotewebapp.ui.home.HomeViewModel
import com.revxzxcx.remotewebapp.ui.pair.PairScreen
import com.revxzxcx.remotewebapp.ui.pair.PairViewModel
import kotlinx.coroutines.launch

/**
 * 根组合：按 [AppState] 决定显示配对页、工作区页还是聊天页。
 *
 * 用 `when` 而不是 navigation-compose：本期屏少、无深链接，少一个依赖（见 MainActivity 注释）。
 * 冷启动先 [AppContainer.bootstrap] 读凭证，读完才决定第一屏。
 *
 * 聊天页用一个本地 `remember` 的会话摘要作为「当前打开的会话」——只有一层下钻，
 * 不需要引入导航库的返回栈。返回键由 [ChatScreen] 的返回按钮处理；
 * 系统返回键在 M4 接 `BackHandler` 时统一处理。
 */
@Composable
fun AppRoot(container: AppContainer) {
    val state by container.state.collectAsState()
    val scope = rememberCoroutineScope()

    /** 当前打开的会话；null 表示停在工作区页。 */
    var openSession by remember { mutableStateOf<SessionSummary?>(null) }

    LaunchedEffect(Unit) {
        // 幂等：重复调用只是重读一次凭证。
        container.bootstrap()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val current = state) {
            AppState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            AppState.Unpaired -> {
                // 未配对时清掉可能残留的会话选择，避免重新配对后直接跳进旧会话。
                LaunchedEffect(Unit) { openSession = null }
                val vm: PairViewModel = viewModel(
                    key = "pair",
                    factory = PairViewModel.factory(container, deviceLabel = Build.MODEL.orEmpty()),
                )
                PairScreen(viewModel = vm)
            }

            is AppState.Paired -> {
                val connection = container.connection
                if (connection == null) {
                    // 理论上不会发生（Paired 与 connection 同时设置）；防御性回到配对页。
                    LaunchedEffect(Unit) { container.unpair() }
                } else {
                    val session = openSession
                    if (session == null) {
                        val vm: HomeViewModel = viewModel(
                            key = "home-${current.credential.deviceId}",
                            factory = HomeViewModel.factory(connection),
                        )
                        HomeScreen(
                            viewModel = vm,
                            onUnpair = { scope.launch { container.unpair() } },
                            onOpenSession = { summary, _ -> openSession = summary },
                        )
                    } else {
                        // key 带上 sessionId：切换会话时拿到的是新 ViewModel（新流、新状态）。
                        val vm: ChatViewModel = viewModel(
                            key = "chat-${session.sessionId}",
                            factory = ChatViewModel.factory(connection, session.sessionId),
                        )
                        ChatScreen(
                            viewModel = vm,
                            title = session.displayName,
                            onBack = { openSession = null },
                        )
                    }
                }
            }
        }
    }
}
