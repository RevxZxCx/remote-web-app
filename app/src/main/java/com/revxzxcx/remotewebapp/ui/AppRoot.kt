package com.revxzxcx.remotewebapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revxzxcx.remotewebapp.core.AppContainer
import com.revxzxcx.remotewebapp.core.AppState
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.ui.chat.ChatScreen
import com.revxzxcx.remotewebapp.ui.chat.ChatViewModel
import com.revxzxcx.remotewebapp.ui.debug.DebugScreen
import com.revxzxcx.remotewebapp.ui.debug.DebugViewModel
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.files.FileViewerScreen
import com.revxzxcx.remotewebapp.ui.files.FileViewerViewModel
import com.revxzxcx.remotewebapp.ui.home.HomeScreen
import com.revxzxcx.remotewebapp.ui.home.HomeViewModel
import com.revxzxcx.remotewebapp.ui.pair.PairScreen
import com.revxzxcx.remotewebapp.ui.pair.PairViewModel
import com.revxzxcx.remotewebapp.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 一个生命周期与「这一次进入某屏」绑定的 [ViewModelStoreOwner]。
 *
 * Compose 的 `viewModel(key = …)` 默认解析自 `LocalViewModelStoreOwner`，也就是 Activity。
 * 于是 `openSession = null` 只是让 ChatScreen 离开组合，**ViewModel 仍留在 Activity 的
 * ViewModelStore 里**：`onCleared()` 不跑，`session/follow` 长连接流、changes 观察协程、
 * 缓存写入口全部继续跑——依次打开 N 个会话就是 N 条并发 follow 流 + N 个缓存写入口，
 * 直到 Activity 销毁（R007 P1-1）。
 *
 * 用这个 owner 包一层，离开分支时 composition 销毁 → `onDispose` 里 `clear()`，
 * ViewModel 的寿命就与「停留在这屏的这段时间」一致，而不是与整个 Activity 一致。
 *
 * 旋转屏幕同样走这条路（manifest 没声明 `configChanges`，Activity 会重建）：那时的
 * ViewModel 本来就要重建，没有额外代价。
 *
 * 只给**持有长连接或大内存**的屏用：ChatViewModel（follow 流）与 FileViewerViewModel
 * （`media` 最大 32 MiB）。工作区页的 HomeViewModel 是常驻订阅方（FR-4.1），调试页
 * 只读无流，配对页转瞬即逝——它们保持挂在 Activity 上即可。
 */
@Composable
private fun rememberScopedViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember { ScopedViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

private class ScopedViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * 根组合：按 [AppState] 决定显示配对页、工作区页、聊天页还是文件查看页。
 *
 * 用 `when` 而不是 navigation-compose：本期屏少、无深链接，少一个依赖（见 MainActivity 注释）。
 * 冷启动先 [AppContainer.bootstrap] 读凭证，读完才决定第一屏。
 *
 * 层级用四个本地状态表示（调试 → 设置 → 会话 → 文件），最深两层下钻：
 *  - [openSession] 非 null 表示在聊天页；
 *  - [openFile] 非 null 表示在文件查看页（从聊天页的卡片或正文路径点进来）。
 * 这样不必引入导航库的返回栈，也不必处理返回栈与状态不同步的问题。
 *
 * 四个状态都是 `rememberSaveable`（M5-2）：早先是普通 `remember`，旋转屏幕或进程被系统
 * 回收后重建时会**退回工作区页**——用户正在看的聊天/文件凭空消失。`SessionSummary` 与
 * `FileTarget` 因此实现了 `Parcelable`（@Parcelize）。
 */
@Composable
fun AppRoot(container: AppContainer) {
    val state by container.state.collectAsState()
    val scope = rememberCoroutineScope()

    /** 当前打开的会话；null 表示停在工作区页。 */
    var openSession by rememberSaveable { mutableStateOf<SessionSummary?>(null) }

    /** 当前查看的文件；null 表示不在文件查看页。 */
    var openFile by rememberSaveable { mutableStateOf<FileTarget?>(null) }

    /** 是否停在调试页（需求 FR-4.4 / 可观测）。 */
    var openDebug by rememberSaveable { mutableStateOf(false) }

    /** 是否停在设置页（M5-9 主题切换的入口）。 */
    var openSettings by rememberSaveable { mutableStateOf(false) }

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
                    openDebug = false
                    openSettings = false
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
                        openDebug -> {
                            val vm: DebugViewModel = viewModel(
                                key = "debug",
                                factory = DebugViewModel.factory(
                                    connection = connection,
                                    cache = container.cache,
                                    ownerId = container.ownerId,
                                ),
                            )
                            BackHandler(enabled = true) { openDebug = false }
                            DebugScreen(viewModel = vm, onBack = { openDebug = false })
                        }

                        openSettings -> {
                            // 主题是**进程级**偏好，不依赖配对状态，所以设置页不读 connection。
                            BackHandler(enabled = true) { openSettings = false }
                            SettingsScreen(
                                themePreferences = container.themePreferences,
                                onBack = { openSettings = false },
                                onUnpair = { scope.launch { container.unpair() } },
                            )
                        }

                        session == null -> {
                            val vm: HomeViewModel = viewModel(
                                key = "home-${current.credential.deviceId}",
                                factory = HomeViewModel.factory(
                                    connection = connection,
                                    cache = container.cache,
                                    ownerId = container.ownerId,
                                ),
                            )
                            HomeScreen(
                                viewModel = vm,
                                onUnpair = { scope.launch { container.unpair() } },
                                onOpenSession = { summary, _ ->
                                    openSession = summary
                                    openFile = null
                                },
                                onOpenDebug = { openDebug = true },
                                onOpenSettings = { openSettings = true },
                            )
                        }

                        file != null -> {
                            // **每个会话只有一个**文件查看 ViewModel（R005 P1）：早前按
                            // `file-$seq-$index-$path` 建 key，每看一个文件就多一个挂在 Activity
                            // ViewModelStore 上永不回收的实例（`lines` + 最大 32 MiB 的 `media`），
                            // 连看几张图就够 OOM。现在换文件走 `open(target)` 就地重置。
                            //
                            // 再套一层 scoped owner（R007 P1-1）：离开文件页时 composition
                            // 销毁，ViewModel 随之 clear，32 MiB 的 `media` 不会钉到 Activity
                            // 销毁才放。key 同样带 deviceId（理由同聊天分支：换设备必须换 VM）。
                            val owner = rememberScopedViewModelStoreOwner()
                            val vm: FileViewerViewModel = viewModel(
                                key = "file-${current.credential.deviceId}-${session.sessionId}",
                                factory = FileViewerViewModel.factory(
                                    connection = connection,
                                    sessionId = session.sessionId,
                                ),
                                viewModelStoreOwner = owner,
                            )
                            LaunchedEffect(file) { vm.open(file) }
                            BackHandler(enabled = true) { openFile = null }
                            FileViewerScreen(viewModel = vm, onBack = { openFile = null })
                        }

                        else -> {
                            // key 带上 sessionId：切换会话时拿到的是新 ViewModel（新流、新状态）。
                            //
                            // **key 还必须带上 deviceId**：解绑 → 重新配对会换一个新设备，
                            // 而 scoped owner 是 `remember` 在分支里的——万一某条路径上分支
                            // 没有离开组合（例如解绑与重连发生在同一帧内），旧 owner 的 store
                            // 里那个绑定着**旧连接**的 ChatViewModel 会被原样交回来，于是聊天页
                            // 一直显示着旧的 `unpaired` 错误与已取消的流，直到冷启动才恢复
                            // （设备实测复现：解绑重配后打开会话，三条 unpaired 错误条常驻；
                            // `am force-stop` 冷启动后同一会话完全正常）。deviceId 进 key 后，
                            // 换设备必然换 VM，与 owner 是否被回收无关。
                            val owner = rememberScopedViewModelStoreOwner()
                            val vm: ChatViewModel = viewModel(
                                key = "chat-${current.credential.deviceId}-${session.sessionId}",
                                factory = ChatViewModel.factory(
                                    connection = connection,
                                    sessionId = session.sessionId,
                                    cache = container.cache,
                                    ownerId = container.ownerId,
                                ),
                                viewModelStoreOwner = owner,
                            )
                            // 系统返回键与标题栏「返回」等效：不然两层下钻后按返回会直接退出 App。
                            BackHandler(enabled = true) { openSession = null }
                            ChatScreen(
                                viewModel = vm,
                                title = session.displayName,
                                onBack = { openSession = null },
                                onOpenFile = { target -> openFile = target },
                                onUnpair = { scope.launch { container.unpair() } },
                            )
                        }
                    }
                }
            }
        }
    }
}
