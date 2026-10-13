package com.revxzxcx.remotewebapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
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
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.files.FileViewerScreen
import com.revxzxcx.remotewebapp.ui.files.FileViewerViewModel
import com.revxzxcx.remotewebapp.ui.home.HomeScreen
import com.revxzxcx.remotewebapp.ui.home.HomeViewModel
import com.revxzxcx.remotewebapp.ui.pair.PairScreen
import com.revxzxcx.remotewebapp.ui.pair.PairViewModel
import kotlinx.coroutines.launch

/**
 * 根组合：按 [AppState] 决定显示配对页、工作区页、聊天页还是文件查看页。
 *
 * 用 `when` 而不是 navigation-compose：本期屏少、无深链接，少一个依赖（见 MainActivity 注释）。
 * 冷启动先 [AppContainer.bootstrap] 读凭证，读完才决定第一屏。
 *
 * 层级用两个本地 `remember` 表示（会话 → 文件），最深两层下钻：
 *  - [openSession] 非 null 表示在聊天页；
 *  - [openFile] 非 null 表示在文件查看页（从聊天页的卡片或正文路径点进来）。
 * 这样不必引入导航库的返回栈，也不必处理返回栈与状态不同步的问题。
 */
@Composable
fun AppRoot(container: AppContainer) {
    val state by container.state.collectAsState()
    val scope = rememberCoroutineScope()

    /** 当前打开的会话；null 表示停在工作区页。 */
    var openSession by remember { mutableStateOf<SessionSummary?>(null) }

    /** 当前查看的文件；null 表示不在文件查看页。 */
    var openFile by remember { mutableStateOf<FileTarget?>(null) }

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
                LaunchedEffect(Unit) {
                    openSession = null
                    openFile = null
                }
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
                    val file = openFile
                    when {
                        session == null -> {
                            val vm: HomeViewModel = viewModel(
                                key = "home-${current.credential.deviceId}",
                                factory = HomeViewModel.factory(connection),
                            )
                            HomeScreen(
                                viewModel = vm,
                                onUnpair = { scope.launch { container.unpair() } },
                                onOpenSession = { summary, _ ->
                                    openSession = summary
                                    openFile = null
                                },
                            )
                        }

                        file != null -> {
                            // **每个会话只有一个**文件查看 ViewModel（R005 P1）：早前按
                            // `file-$seq-$index-$path` 建 key，每看一个文件就多一个挂在 Activity
                            // ViewModelStore 上永不回收的实例（`lines` + 最大 32 MiB 的 `media`），
                            // 连看几张图就够 OOM。现在换文件走 `open(target)` 就地重置。
                            val vm: FileViewerViewModel = viewModel(
                                key = "file-${session.sessionId}",
                                factory = FileViewerViewModel.factory(
                                    connection = connection,
                                    sessionId = session.sessionId,
                                ),
                            )
                            LaunchedEffect(file) { vm.open(file) }
                            BackHandler(enabled = true) { openFile = null }
                            FileViewerScreen(viewModel = vm, onBack = { openFile = null })
                        }

                        else -> {
                            // key 带上 sessionId：切换会话时拿到的是新 ViewModel（新流、新状态）。
                            val vm: ChatViewModel = viewModel(
                                key = "chat-${session.sessionId}",
                                factory = ChatViewModel.factory(connection, session.sessionId),
                            )
                            // 系统返回键与标题栏「返回」等效：不然两层下钻后按返回会直接退出 App。
                            BackHandler(enabled = true) { openSession = null }
                            ChatScreen(
                                viewModel = vm,
                                title = session.displayName,
                                onBack = { openSession = null },
                                onOpenFile = { target -> openFile = target },
                            )
                        }
                    }
                }
            }
        }
    }
}
