package com.revxzxcx.remotewebapp.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.revxzxcx.remotewebapp.core.pairing.PairLink
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairParse
import com.revxzxcx.remotewebapp.ui.common.maskDeviceId
import com.revxzxcx.remotewebapp.ui.common.rememberAnimationsEnabled
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.chipShape
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 配对页（原型 `docs/R033-效果图.html` 的 s2）。
 *
 * 三条入口，**分段互斥**：
 * 1. **扫码** —— 桌面端生成二维码（`<base>/pair-accept?pair=<token>`），App 扫完拿 `token`；
 * 2. **粘贴链接** —— 同一段文本手输，边输边解析出 host 与 token 首尾；
 * 3. **手工登记** —— 地址 + 设备 id（调试 / 恢复），拆成「填 1/2」「填 2/2」两步。
 *
 * 为什么从「三张卡平铺」改成互斥分段（R033 ③）：改造前三件事挤在同一张卡里，用户
 * 不知道该填哪一个——真机上踩过把 token 填进「服务器地址」导致配对失败。分段之后
 * 任一时刻只有一条路径可见，每个输入框都带一句它要什么。
 *
 * ⚠️ App 不能发起配对：`POST /api/pair/issue` 是 loopback-only（`routes.ts:688`），
 * 二维码只能在桌面端生成（需求文档 Q7）。
 */
@Composable
fun PairScreen(
    viewModel: PairViewModel,
) {
    val state by viewModel.state.collectAsState()
    // ⚠️ 这四个都必须是 rememberSaveable：`MainActivity` 没有 `configChanges`，转屏会重建
    // Activity。用普通 `remember` 的话「填好地址 + 设备 id → 转屏 → 回到扫码入口、两个框
    // 全空」；`LinkCard` 里那份链接文本更糟——它连转屏都不用，**切一次入口 tab** 就
    // 被销毁重建（`when (entry)` 换了分支），刚粘贴的链接直接丢。
    var entry by rememberSaveable { mutableStateOf(PairEntry.Scan) }
    // 扫一次就消耗掉整个扫码通道（`decodeSingle` + `consumed` 闩）。配对失败后把这个
    // 计数 +1，`ScanCard` 会 `key(scanEpoch)` 重建控件并复位闩。
    var scanEpoch by rememberSaveable { mutableIntStateOf(0) }
    var linkText by rememberSaveable { mutableStateOf("") }
    // 手工登记的两个字段必须同时可见（登记要 (地址, 设备 id) 一对），所以状态提到这里，
    // 两张卡只是它的两个视觉分组。
    var manualBaseUrl by rememberSaveable { mutableStateOf("") }
    var manualDeviceId by rememberSaveable { mutableStateOf("") }

    // `lastBaseUrl` 是异步读出来的（`init` 里 `credentialStore.load()`），不能拿它当
    // `remember` 的键：那样从 null 变成值时会把用户已经敲进去的地址**重置**掉。改成
    // 「只在仍为空时回填一次」。
    LaunchedEffect(state.lastBaseUrl) {
        val remembered = state.lastBaseUrl
        if (manualBaseUrl.isBlank() && !remembered.isNullOrBlank()) manualBaseUrl = remembered
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "配对桌面端",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "在桌面端的 DSH 里生成配对二维码，然后扫码。也可以粘贴配对链接，或手工填地址与设备 id。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }

            PairEntryPicker(selected = entry, onSelect = { entry = it })

            when (entry) {
                PairEntry.Scan -> ScanCard(
                    enabled = !state.busy,
                    scanEpoch = scanEpoch,
                    onRescan = { scanEpoch++ },
                    onScanned = viewModel::pairWithText,
                )

                PairEntry.Link -> LinkCard(
                    busy = state.busy,
                    onPair = viewModel::pairWithText,
                    fallbackBaseUrl = state.lastBaseUrl,
                    text = linkText,
                    onTextChange = { linkText = it },
                )

                PairEntry.Manual -> ManualEntryCards(
                    busy = state.busy,
                    probing = state.probing,
                    baseUrl = manualBaseUrl,
                    onBaseUrlChange = {
                        manualBaseUrl = it
                        // 地址一改，上一次的探测结论就作废（否则绿灯会贴着一个没探过的地址）。
                        viewModel.clearProbe()
                    },
                    deviceId = manualDeviceId,
                    onDeviceIdChange = { manualDeviceId = it },
                    probe = state.probe,
                    onProbe = viewModel::probe,
                    onManual = viewModel::pairManually,
                )
            }

            state.error?.let { error ->
                Surface(
                    // 配色跟随主题（早先是硬编码深红底 + 亮红字）——R016。
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = viewModel::clearError) { Text("知道了") }
                    }
                }
            }

            if (state.busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在配对…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "提示：配对成功后凭证会用 Android Keystore 加密保存在本机。" +
                    "如果桌面端撤销了这台设备，任何请求都会返回 403 unpaired，需要重新配对。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
    }
}

/** 配对页的三条入口。互斥，任一时刻只展开一条。 */
internal enum class PairEntry(val label: String) {
    Scan("扫码"),
    Link("链接"),
    Manual("手工"),
}

/**
 * 三选一的分段控件（R033 ③）。
 *
 * 用分段而不是三张并排的卡：这里的三个选项是**互斥的流程**（扫码 / 粘贴链接 / 手工），
 * 不是三个并列功能，用户不需要同时看到全部输入框。
 */
@Composable
internal fun PairEntryPicker(
    selected: PairEntry,
    onSelect: (PairEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // `selectableGroup()` 才把三个 `selectable` 归成「一组互斥选项」：没有它，
            // TalkBack 会把它们念成三个孤立节点（「扫码，已选中 / 链接，未选中」），
            // 上下文的「这是一组」丢失。反编译确认它只做 `semantics { selectableGroup() }`。
            .selectableGroup()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = pillShape,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PairEntry.entries.forEach { item ->
            val isSelected = item == selected
            Surface(
                // ⚠️ 用 `Modifier.selectable` 而不是 `Surface(onClick)`：反编译 material3 的
                // `SurfaceKt$Surface$2` 可以看到它内部的 `clickable` 把 `role` 传成了
                // `aconst_null`，于是读屏只会读出三个孤立的可点文本，既不知道「这是一组
                // 互斥选项」，也不知道当前停在哪一栏。`selectable(role = Role.Tab)` 会挂上
                // `SemanticsProperties.Selected`，读屏念「已选中」，测试也能真的断言
                // `assertIsSelected()` —— 在此之前「选中态」只有颜色，覆盖率为零。
                // ⚠️ 触达高度必须 ≥48dp：`Modifier.selectable` **不带** Material 的
                // `minimumInteractiveComponentSize` 保底（反编译 `SelectableKt` 里该调用
                // 出现 0 次，而 `Surface(onClick)` 里有）。视觉仍是 34dp 的胶囊，
                // 用 `heightIn(min = 48.dp)` 把可点区域补足——`height` 与 `heightIn`
                // 同向时后者不会反过来压小前者，两者共存得到「48dp 可点、34dp 可见」。
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(item) },
                    ),
                shape = pillShape,
                color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(
                    modifier = Modifier.height(34.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 扫码卡片。
 *
 * 用 zxing-android-embedded 的 [BarcodeView] 直接嵌进 Compose（`AndroidView`），
 * **不用**它自带的 `CaptureActivity` —— 那个 Activity 强制 `sensorLandscape` 且主题不受控。
 *
 * 也**不用** [com.journeyapps.barcodescanner.DecoratedBarcodeView]：它在相机预览之上自带
 * 一层遮罩 + 中央方框 + 左下角状态字（「请将条码置于取景框内扫描。」）。我们的取景框是
 * 自己画的四角标（[ViewfinderBrackets]）与扫描线（[ScanLine]），两层叠在一起会在屏幕下方
 * 露出它那句状态字，看起来像渲染错位（R033 ③ 设备验收实录）。
 *
 * 取景框是**正方形**（`aspectRatio(1f)`）。改造前是 `fillMaxWidth().height(220.dp)`，
 * 在 360dp 屏上得到 296×220 的横矩形（360 − 页面横向 20×2 − 卡片内 12×2）：页面里
 * 唯一一个非正方形的大色块（R033 ③）。
 *
 * ⚠️ 扫码是**一次性**的：`decodeSingle` 只回调一次，加上 `consumed` 闩之后，配对失败
 * （token 过期 / 桌面端不可达）时继续扫不会有任何反应，而相机预览还在动——从界面
 * 完全看不出已经失效。所以 `scanEpoch` 一变就 `key(scanEpoch)` 重建整个 `AndroidView`
 * 并复位 `consumed`，并在失效后给一个显式的「重新扫描」入口。
 *
 * 可见性是 `internal`：没有权限时的分支（授权入口 / 二次拒绝后跳系统设置）只能靠渲染
 * 层测试守——真机上「拒绝一次」之后的状态要复现得先清数据，而这条分支的出入口就只有
 * 这一个界面（`AppState.Unpaired` 下没有别的页面可去）。
 */
@Composable
internal fun ScanCard(
    enabled: Boolean,
    scanEpoch: Int,
    onRescan: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // 用户点过「授予相机权限」而系统仍然没给：说明是「拒绝」或「不再询问」，此时继续
    // 调 `launcher.launch()` 会立刻回调 false，界面毫无变化——一枚死按钮。
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) deniedOnce = true
    }

    // ⚠️ 回调必须是**稳定**的：`BarcodeView.decodeSingle(callback)` 只在 `AndroidView`
    // 的 `factory` 里调用一次，之后再换 callback 也不会重新注册。若这里写
    // `remember(enabled) { … }`，配对开始（`enabled` false）后控件手里还是那个捕获了
    // `enabled = true` 的旧对象，于是「正在配对…」期间再扫一下就又发一次配对请求
    // （拿已被消费的 token 再 accept 一次）。所以用 `rememberUpdatedState` 让同一个
    // callback 每次读最新值。
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnScanned by rememberUpdatedState(onScanned)
    // 扫到一次就停：decodeSingle 保证底层只回调一次；这里再按「已消费」自己兜一层。
    var consumed by remember { mutableStateOf(false) }
    // 换一次 epoch 就允许再扫一次（配合下面 `key(scanEpoch)` 重建控件）。
    LaunchedEffect(scanEpoch) { consumed = false }
    val callback = remember {
        object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text
                if (consumed || !latestEnabled || text.isNullOrBlank()) return
                consumed = true
                latestOnScanned(text)
            }
        }
    }
    var scanner by remember { mutableStateOf<BarcodeView?>(null) }

    // BarcodeView 自身没有 pauseAndWait()（那是 DecoratedBarcodeView 的包装），
    // 它的父类 CameraPreview 有，所以这里按 CameraPreview 的类型调用。
    //
    // ⚠️ 这里**只** `pause()`，不 `pauseAndWait()`：`pauseAndWait()` 会轮询
    // `isCameraClosed()`，上限 2s，而本 effect 的 key 里含 `hasPermission` —— 权限
    // 一变它就会 dispose 一次，此时控件还挂在窗口上，没必要为「还要继续用」的对象
    // 阻塞主线程等相机关闭。真正释放交给 `AndroidView(onRelease = …)`。
    DisposableEffect(scanner, hasPermission) {
        val view = scanner
        if (view != null && hasPermission) view.resume() else view?.pause()
        onDispose { view?.pause() }
    }

    // 退到后台时暂停相机（R035）。
    //
    // 上面那个 `DisposableEffect` 只在**组合被销毁**时释放，而按 Home 键、切到别的 App
    // 并不会销毁组合——`CameraPreview` 的字节码里只有 `onAttachedToWindow()` 一个生命周期
    // override，没有 `onDetachedFromWindow`，也不监听 `ON_STOP`。于是相机与解码线程会在
    // 后台一直跑（耗电、发热，部分机型摄像头指示灯常亮）。这里挂 `ON_RESUME`/`ON_PAUSE`。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, scanner, hasPermission) {
        val view = scanner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 从系统设置授权之后回到本页要能自己发现（见下面的权限复检）。
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission && view != null) {
                        deniedOnce = false
                        view.resume()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("扫码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)

            if (!hasPermission) {
                Text(
                    text = if (deniedOnce) {
                        "相机权限被拒绝了，扫码用不了。可以去系统设置里开启，或换用「链接」「手工」入口。"
                    } else {
                        "需要相机权限才能扫码。也可以换用「链接」或「手工」入口。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (deniedOnce) {
                            // 二次拒绝后系统不会再弹窗，只能把用户送到应用详情页自己开。
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        } else {
                            launcher.launch(Manifest.permission.CAMERA)
                        }
                    },
                ) {
                    Text(if (deniedOnce) "打开系统设置" else "授予相机权限")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Black, groupShape),
                ) {
                    key(scanEpoch) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                BarcodeView(ctx).also { view ->
                                    view.decodeSingle(callback)
                                    scanner = view
                                }
                            },
                            onRelease = { view ->
                                view.pauseAndWait()
                                if (scanner === view) scanner = null
                            },
                        )
                    }
                    ViewfinderBrackets()
                    ScanLine()
                }
                Text(
                    text = "把桌面端的二维码放进取景框内。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (consumed && enabled) {
                    // `consumed && enabled` 的唯一含义是「扫到过，且现在已经不在配对中」
                    // ——即这次配对失败回来了（成功会直接导航离开）。此刻 `decodeSingle`
                    // 已经用掉、再扫不会有任何反应，必须显式给一条回去的路。
                    TextButton(onClick = onRescan) { Text("重新扫描") }
                }
            }
        }
    }
}

/**
 * 取景框四角的白色角标。
 *
 * 画四个 L 形短线而不是一整圈边框：整圈会把相机预览压成「相框里的照片」，
 * 角标只提示对焦区域，不遮挡画面。
 */
@Composable
private fun ViewfinderBrackets(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.85f),
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val arm = 22.dp.toPx()
        val stroke = 2.dp.toPx()
        val inset = 12.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x + dx * arm, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + dy * arm),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        corner(inset, inset, 1f, 1f)
        corner(w - inset, inset, -1f, 1f)
        corner(inset, h - inset, 1f, -1f)
        corner(w - inset, h - inset, -1f, -1f)
    }
}

/**
 * 上下往复的扫描线。
 *
 * 用 `Canvas` 画在取景框自己的坐标系里：线要横穿整个预览高度，而一个 1.5dp 高的
 * `Box` 的 `size` 只是它自己的尺寸，不是父容器高度。
 *
 * 只在相机预览真的在跑时才出现（[ScanCard] 的有权限分支），所以整页的无限动画
 * 数量仍是 1，符合 R017 的「每屏可见无限动画 ≤2」。
 */
@Composable
private fun ScanLine(modifier: Modifier = Modifier) {
    // 尊重系统「动画时长缩放＝关闭」（无障碍/省电）：工程里另外两处无限动画
    // （`GeneratingGlyph`、`ConnectionIndicator`）都先过 `rememberAnimationsEnabled()`，
    // 只有这里漏了。关掉动画时退化成一条静止的线，仍然能看见取景框。
    val animated = rememberAnimationsEnabled()
    val transition = rememberInfiniteTransition(label = "pairScanLine")
    val animatedProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pairScanLineOffset",
    )
    // 关掉动画时停在取景框正中（progress = 0.5 的观感），而不是停在顶边。
    val progress = if (animated) animatedProgress else 0.5f
    // 颜色要在组合里取出来：`Canvas` 的 draw lambda 不是 @Composable，读不到 MaterialTheme。
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val inset = 12.dp.toPx()
        val y = inset + (size.height - inset * 2f) * progress
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    lineColor,
                    Color.Transparent,
                ),
                startX = inset,
                endX = size.width - inset,
            ),
            start = Offset(inset, y),
            end = Offset(size.width - inset, y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** 链接解析预览：把「这段文本会被当成什么」提前说清楚。 */
internal data class ParsePreview(
    val ok: Boolean,
    val headline: String,
    val detail: String,
)

/**
 * 把 [PairParse] 的结论翻译成一句人话 + 一行细节（R033 ③）。
 *
 * 为什么需要它：真机验收时踩过「把 token 填进服务器地址」——原文案只写「配对链接或
 * token」，用户没法判断自己贴的东西会被理解成哪一种。这里在贴完立刻回显解析结论
 * （解析成 accept / device / 裸 token / 失败），贴错当场看得见。
 *
 * ⚠️ `fallbackBaseUrl` 决定裸 token 能不能用：`PairingCoordinator` 对
 * `PairLink.BareToken` 的处理是 `fallbackBaseUrl?.let(::normalizeBaseUrl) ?: throw`，
 * 而全新安装或进程重启后根本没有「上一次用过的地址」（App 内解绑不会清掉
 * `PairViewModel` 里缓存的那份值——它是 Activity 作用域的，`unpair()` 也不碰它）。
 * 所以裸 token 的 `ok` 必须跟着 fallback 走——早先恒 `ok = true` 会让「开始配对」
 * 亮着，点下去必然失败。
 */
internal fun previewOf(parse: PairParse, fallbackBaseUrl: String? = null): ParsePreview = when (parse) {
    is PairParse.Failed -> ParsePreview(
        ok = false,
        headline = "这段内容还不能用",
        detail = parse.reason,
    )

    is PairParse.Ok -> when (val link = parse.link) {
        is PairLink.Accept -> ParsePreview(
            ok = true,
            headline = "将配对到 ${hostOf(link.baseUrl)}",
            detail = "token ${maskToken(link.token)}",
        )

        is PairLink.Device -> ParsePreview(
            ok = true,
            headline = "将直接登记到 ${hostOf(link.baseUrl)}",
            detail = "设备 id ${maskDeviceId(link.deviceId)}",
        )

        is PairLink.BareToken -> if (fallbackBaseUrl.isNullOrBlank()) {
            ParsePreview(
                ok = false,
                headline = "还缺服务器地址",
                detail = "本机没有上次用过的地址，请粘贴完整配对链接，或改用「手工」入口",
            )
        } else {
            ParsePreview(
                ok = true,
                headline = "这是一个裸 token",
                detail = "将用上次的地址 ${hostOf(fallbackBaseUrl)} 去配对",
            )
        }
    }
}

/**
 * 取出 `scheme://host:port` 里的 `host:port`，用于「配对到哪台机器」这一句。
 *
 * 必须**同时**剥掉尾斜杠和后缀路径：`PairLinkParser.baseUrlOf` 目前产出的 baseUrl 确实
 * 不带路径，但 `hostOf` 也被 `ProbeResultCard` 用在用户手输的地址上（`probe.baseUrl`），
 * 而手输 `http://h:1/a/b` 是可能的。原来只 `trimEnd('/')`，会把 `"h:1/a/b"` 原样显示
 * 出来——与 `PairingApi.hostOf`（`substringBefore('/')`，会剥路径）同一语义两种行为。
 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://", baseUrl).trimEnd('/').substringBefore('/')

/**
 * token 只露首尾各 4 位。
 *
 * 阈值取 16 而不是 8：真实 token 是 32 位（`PairLinkParserTest` 的注释与夹具一致），
 * 但 9~16 位的短 token 若按「首尾各 4 位」只遮掉一两个字符，等于没遮。宁可整段打码。
 */
internal fun maskToken(token: String): String = when {
    token.length <= 16 -> "…"
    else -> token.take(4) + "…" + token.takeLast(4)
}

/**
 * 粘贴链接 / 裸 token 的入口。
 *
 * 可见性是 `internal`：`开始配对` 的禁用判据（`preview?.ok == true`）只能靠渲染层
 * 测试守住——解析失败时按钮必须点不动，否则用户点下去只会拿到一次注定失败的配对请求。
 * 把这张卡抽出来单独测，不必为了它构造整个 `PairViewModel`。
 *
 * `fallbackBaseUrl` 由 [PairScreen] 传进来（`state.lastBaseUrl`），决定裸 token 的
 * `ok`。输入文本也由调用方持有（`text` / `onTextChange`）——卡片自持状态会在切入口
 * tab 时被销毁重建，粘贴的链接当场丢失。
 */
@Composable
internal fun LinkCard(
    busy: Boolean,
    onPair: (String) -> Unit,
    fallbackBaseUrl: String? = null,
    text: String = "",
    onTextChange: (String) -> Unit = {},
) {
    val preview = remember(text, fallbackBaseUrl) {
        if (text.isBlank()) null else previewOf(PairLinkParser.parse(text), fallbackBaseUrl)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("粘贴链接", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("配对链接或 token") },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "桌面端「配对」窗口里的整段链接，或二维码里的 token 本身。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            preview?.let { ParsePreviewCard(it) }
            Button(
                enabled = !busy && text.isNotBlank() && preview?.ok == true,
                onClick = { onPair(text) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始配对") }
        }
    }
}

/** 解析结论卡：解析成功绿描边，失败红描边。 */
@Composable
internal fun ParsePreviewCard(
    preview: ParsePreview,
    modifier: Modifier = Modifier,
) {
    val accent = if (preview.ok) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = chipShape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), chipShape),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = preview.headline,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 手工登记的两张卡（R033 ③）。
 *
 * 抽成一个组合项而不是在 [PairScreen] 里平铺两个调用：登记需要 (地址, 设备 id) 这一
 * **对**值，拆成两张卡只是视觉分组，状态必须共用——放在这里也让渲染层测试能整块验。
 */
@Composable
internal fun ManualEntryCards(
    busy: Boolean,
    probing: Boolean = false,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    probe: PairProbe?,
    onProbe: (String) -> Unit,
    onManual: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ServerCard(
            busy = busy,
            probing = probing,
            baseUrl = baseUrl,
            onBaseUrlChange = onBaseUrlChange,
            probe = probe,
            onProbe = onProbe,
        )
        DeviceIdCard(
            busy = busy,
            probing = probing,
            baseUrl = baseUrl,
            deviceId = deviceId,
            onDeviceIdChange = onDeviceIdChange,
            onManual = onManual,
        )
    }
}

/** 手工登记第 1 步：服务器地址 + 探测。 */
@Composable
private fun ServerCard(
    busy: Boolean,
    probing: Boolean,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    probe: PairProbe?,
    onProbe: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle(step = "填 1/2", title = "服务器")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "可以省略 http://，例如 192.168.1.5:19387。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            OutlinedButton(
                // 探测只受「探测中」与「配对中」两个条件约束；`probing` 期间禁用是为了
                // 不让用户连点发出多个请求（晚回的会盖掉早回的结论）。
                enabled = !busy && !probing && baseUrl.isNotBlank(),
                onClick = { onProbe(baseUrl) },
            ) { Text(if (probing) "探测中…" else "探测地址") }
            probe?.let { ProbeResultCard(it) }
        }
    }
}

/** 手工登记第 2 步：设备 id + 登记。 */
@Composable
private fun DeviceIdCard(
    busy: Boolean,
    probing: Boolean,
    baseUrl: String,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    onManual: (String, String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle(step = "填 2/2", title = "设备 id")
            OutlinedTextField(
                value = deviceId,
                onValueChange = onDeviceIdChange,
                label = { Text("设备 id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                // 真机验收时这一格被留空过——给出真实长度区间比说「填 id」有用。
                text = "32 位十六进制，桌面端「已配对设备」里能看到完整值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Text(
                text = "登记不会经过桌面端确认，凭证会直接写进本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Button(
                enabled = !busy && !probing && baseUrl.isNotBlank() && deviceId.isNotBlank(),
                onClick = { onManual(baseUrl, deviceId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("登记") }
        }
    }
}

/** 「填 1/2 · 服务器」这种步骤标题。 */
@Composable
private fun StepTitle(step: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = step,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 地址探测结果卡（R033 ③）。
 *
 * 改造前失败也写进全局错误条，与「配对失败」混在一起，用户分不清是地址打不通还是
 * 凭证不对。现在成功实心绿描边、失败实心红描边，都贴着地址输入框显示。
 */
@Composable
internal fun ProbeResultCard(
    probe: PairProbe,
    modifier: Modifier = Modifier,
) {
    // 必须走 `LocalStatusRunning` 而不是硬编码 `Color(0xFF4ADE80)`：R017 已经把这个绿
    // 收成主题 token（深色 #4ADE80 / 浅色 #15803D），正因为原绿压在 #FFFFFF 上会发灰。
    // 这里硬编码等于把 R017 收掉的洞重新打开。
    val accent = if (probe.ok) LocalStatusRunning.current else MaterialTheme.colorScheme.error
    Surface(
        color = accent.copy(alpha = 0.1f),
        shape = chipShape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.45f), chipShape),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = probe.headline,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
            // 回显被探测的地址：`clearProbe()` 只在「改地址」时清卡，挡不住在途请求——
            // 地址 A 慢、B 快时 A 的结论会后到并把 B 的绿卡盖掉。把结论对应的地址一起
            // 显示出来，用户至少能看见「这张卡说的不是我刚填的那个地址」。
            if (probe.baseUrl.isNotBlank()) {
                Text(
                    text = hostOf(probe.baseUrl),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            probe.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
