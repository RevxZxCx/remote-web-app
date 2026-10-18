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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.revxzxcx.remotewebapp.core.pairing.PairLink
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairParse
import com.revxzxcx.remotewebapp.ui.common.maskDeviceId
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
    var entry by remember { mutableStateOf(PairEntry.Scan) }
    // 手工登记的两个字段必须同时可见（登记要 (地址, 设备 id) 一对），所以状态提到这里，
    // 两张卡只是它的两个视觉分组。
    var manualBaseUrl by remember(state.lastBaseUrl) {
        mutableStateOf(state.lastBaseUrl.orEmpty())
    }
    var manualDeviceId by remember { mutableStateOf("") }

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
                    onScanned = viewModel::pairWithText,
                )

                PairEntry.Link -> LinkCard(
                    busy = state.busy,
                    onPair = viewModel::pairWithText,
                )

                PairEntry.Manual -> ManualEntryCards(
                    busy = state.busy,
                    baseUrl = manualBaseUrl,
                    onBaseUrlChange = { manualBaseUrl = it },
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
                onClick = { onSelect(item) },
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                shape = pillShape,
                color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
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
 * 一层遮罩 + 中央方框 + 左下角状态字（「请将条码置于取景框内扫描。」）。我们的取景框
 * 是自己画的四角标（[ViewfinderBrackets]）与扫描线（[ScanLine]），两层叠在一起会在
 * 屏幕下方露出它那句状态字，看起来像渲染错位（R033 ③ 设备验收实录）。
 *
 * 取景框是**正方形**（`aspectRatio(1f)`）。改造前是 `fillMaxWidth().height(220.dp)`，
 * 在 360dp 屏上得到 336×220 的横矩形：页面里唯一一个非正方形的大色块，四角还露着
 * 黑色的直角边，观感最脏的一处（R033 ③）。
 */
@Composable
private fun ScanCard(
    enabled: Boolean,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // ⚠️ 回调必须是**稳定**的：`BarcodeView.decodeSingle(callback)` 只在 `AndroidView`
    // 的 `factory` 里调用一次，之后再换 callback 也不会重新注册。若这里写
    // `remember(enabled) { … }`，配对开始（`enabled` false）后控件手里还是那个捕获了
    // `enabled = true` 的旧对象，于是「正在配对…」期间再扫一下就又发一次配对请求
    // （拿已被消费的 token 再 accept 一次）。所以用 `rememberUpdatedState` 让同一个
    // callback 每次读最新值。
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnScanned by rememberUpdatedState(onScanned)
    // 扫到一次就停：decodeSingle 保证底层只回调一次；这里再按「已配对过」自己兜一层。
    var consumed by remember { mutableStateOf(false) }
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
    DisposableEffect(scanner, hasPermission) {
        val view = scanner
        if (view != null && hasPermission) view.resume() else view?.pause()
        onDispose { view?.pauseAndWait() }
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
                    text = "需要相机权限才能扫码。也可以换用「链接」或「手工」入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Black, RoundedCornerShape(14.dp)),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            BarcodeView(ctx).also { view ->
                                view.decodeSingle(callback)
                                scanner = view
                            }
                        },
                    )
                    ViewfinderBrackets()
                    ScanLine()
                }
                Text(
                    text = "把桌面端的二维码放进取景框内。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
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
    val transition = rememberInfiniteTransition(label = "pairScanLine")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pairScanLineOffset",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val inset = 12.dp.toPx()
        val y = inset + (size.height - inset * 2f) * progress
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF5AA2FF).copy(alpha = 0.9f),
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
 */
internal fun previewOf(parse: PairParse): ParsePreview = when (parse) {
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

        is PairLink.BareToken -> ParsePreview(
            ok = true,
            headline = "这是一个裸 token",
            detail = "需要配上一次用过的地址才能用",
        )
    }
}

/** 取出 `scheme://host:port` 里的 `host:port`，用于「配对到哪台机器」这一句。 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://", baseUrl).trimEnd('/')

/** token 只露首尾各 4 位；短到露首尾就全露时干脆整段打码。 */
internal fun maskToken(token: String): String = when {
    token.length <= 8 -> "…"
    else -> token.take(4) + "…" + token.takeLast(4)
}

/**
 * 粘贴链接 / 裸 token 的入口。
 *
 * 可见性是 `internal`：`开始配对` 的禁用判据（`preview?.ok == true`）只能靠渲染层
 * 测试守住——解析失败时按钮必须点不动，否则用户点下去只会拿到一次注定失败的配对请求。
 * 把这张卡抽出来单独测，不必为了它构造整个 `PairViewModel`。
 */
@Composable
internal fun LinkCard(
    busy: Boolean,
    onPair: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val preview = remember(text) {
        if (text.isBlank()) null else previewOf(PairLinkParser.parse(text))
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
                onValueChange = { text = it },
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
        shape = RoundedCornerShape(9.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(9.dp)),
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
            baseUrl = baseUrl,
            onBaseUrlChange = onBaseUrlChange,
            probe = probe,
            onProbe = onProbe,
        )
        DeviceIdCard(
            busy = busy,
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
                enabled = !busy && baseUrl.isNotBlank(),
                onClick = { onProbe(baseUrl) },
            ) { Text("探测地址") }
            probe?.let { ProbeResultCard(it) }
        }
    }
}

/** 手工登记第 2 步：设备 id + 登记。 */
@Composable
private fun DeviceIdCard(
    busy: Boolean,
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
                text = "64 位十六进制，桌面端「已配对设备」里能看到完整值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Text(
                text = "登记不会经过桌面端确认，凭证会直接写进本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Button(
                enabled = !busy && baseUrl.isNotBlank() && deviceId.isNotBlank(),
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
    val accent = if (probe.ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error
    Surface(
        color = accent.copy(alpha = 0.1f),
        shape = RoundedCornerShape(9.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
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
