package com.revxzxcx.remotewebapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revxzxcx.remotewebapp.ImeInsets
import com.revxzxcx.remotewebapp.core.AppContainer
import com.revxzxcx.remotewebapp.core.AppState
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.session.SessionSummary
import com.revxzxcx.remotewebapp.ui.chat.ChatScreen
import com.revxzxcx.remotewebapp.ui.chat.ChatViewModel
import com.revxzxcx.remotewebapp.ui.chat.DetailTab
import com.revxzxcx.remotewebapp.ui.common.ConfirmUnpairDialog
import com.revxzxcx.remotewebapp.ui.debug.DebugScreen
import com.revxzxcx.remotewebapp.ui.debug.DebugViewModel
import com.revxzxcx.remotewebapp.ui.files.FileTarget
import com.revxzxcx.remotewebapp.ui.files.FileViewerScreen
import com.revxzxcx.remotewebapp.ui.files.FileViewerViewModel
import com.revxzxcx.remotewebapp.ui.home.HomeScreen
import com.revxzxcx.remotewebapp.ui.home.HostRemoveDialog
import com.revxzxcx.remotewebapp.ui.home.HostRemovePurpose
import com.revxzxcx.remotewebapp.ui.home.HomeViewModel
import com.revxzxcx.remotewebapp.ui.pair.PairScreen
import com.revxzxcx.remotewebapp.ui.pair.PairViewModel
import com.revxzxcx.remotewebapp.ui.settings.SettingsScreen
import com.revxzxcx.remotewebapp.ui.theme.Motion
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
 * 读的是 app 级 `StateFlow`（mux 的 stats / errors，`DebugViewModel.init` 里那条
 * `combine(...).collect`），不持有长连接、不占大内存，因此可以不进 scoped owner——
 * **但 key 仍必须带 deviceId**（R018 P2-2：换设备不换 VM 会显示上一个连接的诊断数据）。
 * 配对页转瞬即逝——它保持挂在 Activity 上即可。
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
 * 屏幕进场动画（R016）。
 *
 * ## 为什么不用 `Crossfade` / `AnimatedContent`
 *
 * 那两个会把**新旧两屏同时**组合一段时间。聊天页挂着 `session/follow` 长连接、
 * 文件查看页最多 32 MiB 的 `media`，切换时两屏并存就意味着两条流同时跑、
 * 两份大内存同时占——为了一个转场动画付这个代价不划算。
 *
 * 这里的做法是「新屏自己淡入」：`route` 一变，分支换掉，新分支的 [remember] 从
 * `visible = false` 起，`LaunchedEffect(route)` 随后把它推到 true。旧屏在同一帧
 * 就被销毁，不存在并存窗口。
 *
 * 位移只有 12dp：聊天页这种重屏，大幅滑入会让用户觉得「等它滑完才能操作」，
 * 而 12dp 只是给眼睛一个「这里是新的一层」的暗示。
 */
@Composable
private fun ScreenTransition(route: String, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(route) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(Motion.SLOW, easing = Motion.Emphasized),
        label = "screenAlpha",
    )
    val offset by animateFloatAsState(
        targetValue = if (visible) 0f else 12f,
        animationSpec = tween(Motion.SLOW, easing = Motion.Emphasized),
        label = "screenOffset",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                translationY = offset * density
            },
    ) {
        content()
    }
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

    // R054 / B004：订阅**一份**快照（凭证簿 + 它对应那条连接 + 连接代次）。
    //
    // 早先这里收的是 `container.book`，连接则另外从 `container.connection` 读，两者分属
    // 不同字段：切换主机时簿先发布、连接后安装，中间隔着预热缓存库的真挂起点，于是重组时
    // 能读到「新簿 + 旧连接」，而 `Paired → Paired` 不发射、没有后续事件纠正，页面就永远
    // 停在上一台的工作区（B004）。收成一份之后，UI 结构上读不到半新半旧的组合。
    val link by container.link.collectAsState()
    val book = link.book
    val scope = rememberCoroutineScope()

    /** 当前打开的会话；null 表示停在工作区页。 */
    var openSession by rememberSaveable { mutableStateOf<SessionSummary?>(null) }

    /** 当前查看的文件；null 表示不在文件查看页。 */
    var openFile by rememberSaveable { mutableStateOf<FileTarget?>(null) }

    // ── R041（用户 m24881 第 ③ 条）：对话详情侧栏的跨屏状态 ──────────────────
    //
    // 三个值本来在 `ChatScreen` 里 `remember`，但「从侧栏点开文件」会切到
    // `file != null` 分支，聊天分支整个离开组合——那些 `remember` 随之消失，
    // 返回时侧栏就是关的（用户原话：「点开文件后，再返回就直接关闭侧边栏到对话
    // 界面」）。提到这里之后它们跨分支存活，返回时侧栏还在、页签还是「文件」、
    // 目录还是刚才那一层。
    //
    // 用 `rememberSaveable`：这是「用户手动走到哪了」的导航状态，进程被回收后
    // 重建应当回到同一处，而不是弹回会话详情根目录。
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    /** 侧栏当前页签（存 name，枚举在 Bundle 里不如 String 稳当）。 */
    var sidebarTabName by rememberSaveable { mutableStateOf(DetailTab.SESSION.name) }
    val sidebarTab = DetailTab.entries.firstOrNull { it.name == sidebarTabName } ?: DetailTab.SESSION

    /** 侧栏文件页签当前所在目录（相对会话 cwd，根是空串）。 */
    var sidebarFilesPath by rememberSaveable { mutableStateOf("") }

    // 离开会话就把侧栏收回到初始态。
    //
    // 不重置会串味：从会话 A 的文件页签下钻到 `docs/dev`，返回首页再进会话 B，
    // B 的侧栏一打开就停在 A 的目录上——而 B 的 cwd 可能根本没有 `docs`，于是
    // 列出来是空的，看起来像「文件页签坏了」。这条状态的归属是「某个会话的一次
    // 查看」，不是「这个 App 的一次查看」。
    //
    // 放在 `openSession` 置空/换值的同一处，避免两处各写一遍。
    fun resetSidebar() {
        sidebarOpen = false
        sidebarTabName = DetailTab.SESSION.name
        sidebarFilesPath = ""
    }

    /** 是否停在调试页（需求 FR-4.4 / 可观测）。 */
    var openDebug by rememberSaveable { mutableStateOf(false) }

    /** 是否停在设置页（M5-9 主题切换的入口）。 */
    var openSettings by rememberSaveable { mutableStateOf(false) }

    /**
     * 是否停在「添加远程」页（R054）。
     *
     * 它从首页的加号、设备栏的「添加远程 Web」、溢出菜单三处发起，
     * 都只是把这个布尔值置真；它与会话无关，因此不并入 `openSession` 那条树。
     */
    var addHostOpen by rememberSaveable { mutableStateOf(false) }

    /**
     * 「退出配对」的统一出口（R018 P1-1）。
     *
     * 早前 `AppRoot` 把 `onUnpair = { scope.launch { container.unpair() } }` 直连给
     * Home / Chat / Settings 三屏。HomeScreen 与 SettingsScreen 自己套了
     * [ConfirmUnpairDialog]，但 **Home 错误横幅与 ConnectionBanner 的「重新配对」按钮
     * 传的是同一个 onUnpair**，那两处没有任何确认——误触一次就清掉本机凭证，
     * 而配对只能由桌面端重新发起。且它们只在异常状态出现，用户注意力在错误信息上、
     * 最容易误触。
     *
     * 现在 `onUnpair` 只置位这一个状态，确认框在 [AppRoot] 层渲染**一次**，
     * 三屏共用。顺带让「重新配对」与「退出配对」这两个语义分开：
     * `ConnectionBanner` 的 `onRepair` 参数改名为 `onRepairRequest`，它现在是
     * 「请求退出配对并重新配对」，与「重连」(`onRetry`) 不是一回事。
     */
    var pendingUnpair by rememberSaveable { mutableStateOf(false) }

    /**
     * 请求首页展开设备栏（设置页「我的远程」→ 回首页看那几台）。
     *
     * 为什么要一个跨屏的布尔量：设备栏的展开状态属于 `HomeScreen` 的局部 `rememberSaveable`，
     * AppRoot 拿不到句柄。而「从设置页跳过去」必须让它在挂载那一刻就是展开的，否则用户
     * 看到的是普通首页，还得再点一次标题。
     */
    var requestDeviceBar by rememberSaveable { mutableStateOf(false) }

    /**
     * 待确认「重新配对」的主机（R054 修正）。
     *
     * 与 [pendingRemoveHost] 的区别只在**确认后的去向**：移除后停在首页（那是设备栏
     * 里的「从列表移除」），重新配对后要落到配对页。两者共用 [HostRemoveDialog]，
     * 因为对用户而言都是「这台不要了，桌面端不受影响」。
     *
     * 为什么不能沿用 [pendingUnpair]：那个走 `container.unpair()`，会 `clear()` 掉
     * 整个凭证文件——多主机下等于把另外几台也一起删了。而「重新配对」的入口
     * （错误横幅、ConnectionBanner、连接面板）全部只针对**当前这一台**。
     */
    var pendingRepairHost by remember { mutableStateOf<DeviceCredential?>(null) }

    /** 待确认「从列表移除」的主机；非空时渲染确认框（与设备栏 ⋯ 菜单同一个框）。 */
    var pendingRemoveHost by remember { mutableStateOf<DeviceCredential?>(null) }

    LaunchedEffect(Unit) {
        // 幂等：重复调用只是重读一次凭证。
        container.bootstrap()
    }

    // `imePadding()` 是**必需的**（用户 m21177 附加反馈）：MainActivity 调了
    // `enableEdgeToEdge()`（= `WindowCompat.setDecorFitsSystemWindows(window, false)`），
    // 窗口不再为输入法收缩，于是 manifest 里的 `android:windowSoftInputMode="adjustResize"`
    // 形同虚设——不消费 IME inset 的话，输入法弹起时整个底部（输入框 + 排队队列）被
    // 压在键盘下面，用户看不到自己打的字。
    //
    // 放在最外层 `Surface` 上而不是 ChatScreen 的 Scaffold：配对页也有手输框，
    // 同一个问题在那里一样存在。
    // 输入法适配（用户 m21177 附加反馈）。`imePadding()` 在 MuMu + 搜狗输入法上实测
    // 拿不到 inset（`WindowInsets.ime` 恒 0），所以改用 MainActivity 从 DecorView 桥进来的
    // 原生 ime 高度，见 `ImeInsets` 的 KDoc。放在最外层 `Surface` 上而不是 ChatScreen 的
    // Scaffold：配对页也有手输框，同一个问题在那里一样存在。
    val imeBottomPx by ImeInsets.bottomPx.collectAsState()
    val imeBottomDp = with(LocalDensity.current) { imeBottomPx.toDp() }
    Surface(
        modifier = Modifier.fillMaxSize().padding(bottom = imeBottomDp),
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
                ScreenTransition(route = "pair") { PairScreen(viewModel = vm) }
            }

            AppState.Paired -> {
                // B004：连接与「当前是哪台主机」取自**同一份快照**，且 key 用
                // `link.scopeToken`（含连接代次）——只带 deviceId 的话，「同一台设备换地址」
                // 不会换 VM，VM 继续握着旧 baseUrl 的连接，症状与切换设备一模一样。
                val connection = link.connection
                val activeHost = link.activeHost
                val scopeToken = link.scopeToken
                val ownerId = link.ownerId
                if (connection == null || activeHost == null) {
                    // 理论上不会发生（Paired 与 connection 同时设置）；防御性回到配对页。
                    LaunchedEffect(Unit) { container.unpair() }
                } else {
                    val session = openSession
                    val file = openFile

                    // 列表页的 ViewModel 提到这一层（R049）：它现在还要负责「完成/失败
                    // 未读提醒」的账本，必须**在会话里也活着**——否则用户进会话期间跑完
                    // 的那一轮没有观测者，等回到列表时「上一刻在跑」这个边沿已经丢了，
                    // 绿点永远不会点亮。放到 Paired 这一层后它与聊天页共享同一个实例
                    // （Activity 的 ViewModelStore），进出会话都不重建。
                    val homeVm: HomeViewModel = viewModel(
                        key = "home-$scopeToken",
                        factory = HomeViewModel.factory(
                            connection = connection,
                            cache = container.cache,
                            ownerId = ownerId,
                        ),
                    )

                    // 进入会话即「看过了」：账本把这条会话的两枚未读提醒熄灭并同步进列表。
                    // 看文件（`file != null`）也算，那是同一个会话的内容。
                    LaunchedEffect(session?.sessionId) {
                        homeVm.onSessionOpened(session?.sessionId)
                    }
                    when {
                        // R054：「添加远程」。放在最前面是因为它是从**任意一屏**（首页 /
                        // 设置页）发起的临时路由，不应被下面那三个常驻页面掩盖。
                        addHostOpen -> {
                            val vm: PairViewModel = viewModel(
                                // ⚠️ key 必须是**稳定**的，不能带 `activeHost.deviceId`。
                                // 「添加远程」成功时 `container.addHost()` 会写 `_book`，
                                // 于是 `activeHost` 变成刚加的那台、key 随之改变 → Compose
                                // 销毁持有 `addedHost` 的那个 VM 并新建一个空的，成功卡
                                // （「已加入列表 / 留在当前主机 / 现在切过去」）永远渲染不出来。
                                // 真机症状：点了登记毫无反馈，用户以为没成功又点一次，每次
                                // 都真的写进凭证簿，实测攒出 11 台同地址主机。
                                // 与 DebugViewModel 不同，PairViewModel 只捕捉 `container`
                                // （不碰 connection / cache），不存在「换主机后指向旧对象」
                                // 的问题，所以这里不需要按 deviceId 隔离。
                                key = "pair-add",
                                factory = PairViewModel.factory(
                                    container = container,
                                    deviceLabel = Build.MODEL.orEmpty(),
                                    addMode = true,
                                ),
                            )
                            BackHandler(enabled = true) { addHostOpen = false }
                            ScreenTransition(route = "pair-add") {
                                PairScreen(
                                    viewModel = vm,
                                    addMode = true,
                                    onCancel = { addHostOpen = false },
                                    // 「留在当前主机」：新主机已在列表里，直接退出。
                                    onAdded = { addHostOpen = false },
                                    // 「现在切过去」：先退出这个路由再切。顺序不能反：
                                    // 切换会把整个 home 分支连 VM 一起重建，而这个路由属于旧分支。
                                    onSwitchToAdded = { added ->
                                        addHostOpen = false
                                        container.runInBackground { container.switchHost(added.hostId) }
                                    },
                                )
                            }
                        }

                        openDebug -> {
                            // key 带 deviceId（R018 P2-2）：DebugViewModel 构造时捕获
                            // connection / cache / ownerId，换设备后这些全指向旧对象。
                            // 早前 key 是裸 "debug"，重新配对后进诊断页看到的是上一个
                            // 连接的地址、设备 id、帧计数与缓存行数——调试页的唯一目的
                            // 就是「界面说错话时能立刻找到为什么」，这里反而给出误导数据。
                            val vm: DebugViewModel = viewModel(
                                key = "debug-$scopeToken",
                                factory = DebugViewModel.factory(
                                    connection = connection,
                                    cache = container.cache,
                                    ownerId = ownerId,
                                ),
                            )
                            BackHandler(enabled = true) { openDebug = false }
                            ScreenTransition(route = "debug") {
                                DebugScreen(viewModel = vm, onBack = { openDebug = false })
                            }
                        }

                        openSettings -> {
                            // 主题是**进程级**偏好，不依赖配对状态，所以设置页不读 connection。
                            BackHandler(enabled = true) { openSettings = false }
                            ScreenTransition(route = "settings") {
                                SettingsScreen(
                                    themePreferences = container.themePreferences,
                                    onBack = { openSettings = false },
                                    onUnpair = { pendingUnpair = true },
                                    onOpenDebug = { openDebug = true },
                                    book = book,
                                    // 回首页并展开设备栏。不能只关设置页：设置页里看不到设备栏，
                                    // 用户点「我的远程」就是为了看那几台机器。
                                    onManageHosts = {
                                        openSettings = false
                                        requestDeviceBar = true
                                    },
                                    // 「断开当前远程」只删这一台，走与设备栏同一个确认框。
                                    onRemoveActiveHost = {
                                        book.active?.let { pendingRemoveHost = it }
                                    },
                                )
                            }                        }

                        session == null -> {
                            ScreenTransition(route = "home") {
                                HomeScreen(
                                    viewModel = homeVm,
                                    // 「断开当前远程」溢出菜单：只删这台、留在首页可再切别台。
                                    onUnpair = {
                                        book.active?.let { pendingRemoveHost = it }
                                    },
                                    // 「重新配对」入口（错误横幅 / ConnectionBanner / 连接面板）：
                                    // 同样只删这台，但删完要落到配对页（R054）。
                                    onRepairRequest = {
                                        book.active?.let { pendingRepairHost = it }
                                    },
                                    onOpenSession = { summary, _ ->
                                        openSession = summary
                                        openFile = null
                                        resetSidebar()
                                    },
                                    onOpenDebug = { openDebug = true },
                                    onOpenSettings = { openSettings = true },
                                    // R054 多主机。四个写操作全走 `runInBackground`：切主机会把整个
                                    // home 分支重建（VM key 带 deviceId），用界面 scope 会让写到一半被取消。
                                    book = book,
                                    // 设置页「我的远程」跳过来时立刻展开（消费一次即清）。
                                    expandDeviceBarOnStart = requestDeviceBar,
                                    onDeviceBarExpanded = { requestDeviceBar = false },
                                    onSwitchHost = { hostId ->
                                        container.runInBackground { container.switchHost(hostId) }
                                    },
                                    onRenameHost = { host, label ->
                                        container.runInBackground { container.renameHost(host.hostId, label) }
                                    },
                                    onEditHostEndpoint = { host, baseUrl ->
                                        container.runInBackground {
                                            container.updateEndpoint(host.hostId, baseUrl)
                                        }
                                    },
                                    onRemoveHost = { host ->
                                        container.runInBackground { container.removeHost(host.hostId) }
                                    },
                                    onAddRemote = { addHostOpen = true },
                                    onProbeHost = { host -> container.testHost(host) },
                                )
                            }
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
                                key = "file-$scopeToken-${session.sessionId}",
                                factory = FileViewerViewModel.factory(
                                    connection = connection,
                                    sessionId = session.sessionId,
                                ),
                                viewModelStoreOwner = owner,
                            )
                            LaunchedEffect(file) { vm.open(file) }
                            BackHandler(enabled = true) { openFile = null }
                            ScreenTransition(route = "file") {
                                FileViewerScreen(viewModel = vm, onBack = { openFile = null })
                            }
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
                                key = "chat-$scopeToken-${session.sessionId}",
                                factory = ChatViewModel.factory(
                                    connection = connection,
                                    sessionId = session.sessionId,
                                    cache = container.cache,
                                    ownerId = ownerId,
                                ),
                                viewModelStoreOwner = owner,
                            )
                            // 系统返回键与标题栏「返回」等效：不然两层下钻后按返回会直接退出 App。
                            BackHandler(enabled = true) {
                                openSession = null
                                resetSidebar()
                            }
                            ScreenTransition(route = "chat") {
                                ChatScreen(
                                    viewModel = vm,
                                    title = session.displayName,
                                    onBack = {
                                        openSession = null
                                        resetSidebar()
                                    },
                                    onOpenFile = { target -> openFile = target },
                                    // ChatScreen 的 onUnpair 只喂给 ConnectionBanner 的
                                    // onRepairRequest（见 chat/ChatScreen.kt 的接线），
                                    // 语义就是「这台失效了，重新配对」，因此走 repair 那条。
                                    onRepairRequest = {
                                        book.active?.let { pendingRepairHost = it }
                                    },
                                    sidebarOpen = sidebarOpen,
                                    onSidebarOpenChange = { sidebarOpen = it },
                                    sidebarTab = sidebarTab,
                                    onSidebarTabChange = { sidebarTabName = it.name },
                                    filesPath = sidebarFilesPath,
                                    onFilesPathChange = { sidebarFilesPath = it },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 三个破坏性动作的确认框都渲染在 AppRoot 层，而不是各自屏内（R018 P1-1 / R054）。
        //
        // 放在 `when` 外面是因为它们对应的是**跨屏**的意图：同一次「这台不要了」
        // 可能从首页错误横幅、ConnectionBanner、聊天页横幅、溢出菜单、设置页发起，
        // 散在各屏自己渲染就会出现「有的入口有确认、有的没有」。
        //
        // R054 起分成**两种**框，因为后果差一个数量级：
        // - [HostRemoveDialog]：删列表里的一条，桌面端不受影响，别的机器照跑；
        // - [ConfirmUnpairDialog]：`unpair()` 清空整本凭证，所有远程一起没。
        // 用同一个框会让用户以为自己在做后者（或以为前者很轻）。
        //
        // 「移除当前这台」的确认框（R054）。与下面的「退出配对」分开渲染：
        // 一个是删一条列表项、桌面端不受影响；另一个是清空整本凭证。用同一个框会让
        // 用户以为自己在做后者。
        pendingRemoveHost?.let { host ->
            HostRemoveDialog(
                host = host,
                total = book.hosts.size,
                onDismiss = { pendingRemoveHost = null },
                onConfirm = {
                    pendingRemoveHost = null
                    scope.launch {
                        val remains = container.removeHost(host.hostId)
                        if (!remains) openSettings = false
                    }
                },
            )
        }

        // 「重新配对」的确认框（R054 修正）。复用 HostRemoveDialog 而不是
        // ConfirmUnpairDialog：文案要说的是「只删这台」，说「退出配对」会让用户
        // 以为整本凭证都要没了（那是设置页「移除全部远程」的事）。
        pendingRepairHost?.let { host ->
            HostRemoveDialog(
                host = host,
                total = book.hosts.size,
                purpose = HostRemovePurpose.Repair,
                onDismiss = { pendingRepairHost = null },
                onConfirm = {
                    pendingRepairHost = null
                    scope.launch {
                        val remains = container.removeHost(host.hostId)
                        // 无论是否还有别台，都送用户去配对页：他点的是「重新配对」。
                        // remains==false 时 removeHost 已把状态置成 Unpaired，
                        // 配对页会自然接管；remains==true 时是「这台不要了、去加新的」。
                        addHostOpen = remains
                    }
                },
            )
        }

        if (pendingUnpair) {
            ConfirmUnpairDialog(
                hostCount = book.hosts.size,
                onConfirm = {
                    pendingUnpair = false
                    scope.launch { container.unpair() }
                },
                onDismiss = { pendingUnpair = false },
            )
        }
    }
}
