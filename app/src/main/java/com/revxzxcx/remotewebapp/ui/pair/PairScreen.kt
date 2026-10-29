package com.revxzxcx.remotewebapp.ui.pair

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.pairing.PairLink
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairParse
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.common.AppMark
import com.revxzxcx.remotewebapp.ui.common.maskDeviceId
import com.revxzxcx.remotewebapp.ui.common.rememberAnimationsEnabled
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.groupShape
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/**
 * 配对页（原型 `docs/R033-效果图.html` 的 s2）。
 *
 * 三条入口，**分段互斥**：
 * 1. **扫码** —— 桌面端生成二维码（`<base>/pair-accept?pair=<token>`），App 扫完拿 `token`；
 * 2. **粘贴链接** —— 同一段文本手输 / 从剪贴板读，边输边解析出 host 与 token 首尾；
 * 3. **手工登记** —— 地址 + 设备 id（调试 / 恢复），拆成「填 1/2」「填 2/2」两步。
 *
 * 为什么从「三张卡平铺」改成互斥分段（R033 ③）：改造前三件事挤在同一张卡里，用户
 * 不知道该填哪一个——真机上踩过把 token 填进「服务器地址」导致配对失败。分段之后
 * 任一时刻只有一条路径可见，每个输入框都带一句它要什么。
 *
 * R036 按原型重排：顶部 hero（应用标志 + 随入口变化的标题/副标题）、分段控件带图标、
 * 取景框换成近黑底 + 网格 + 圆角角标 + 底部渐隐提示、输入框改成「上方独立标签 + 圆角框」、
 * 手工两张卡带图标与右侧等宽步骤号、探测卡带 12dp 图标与等宽正文。
 *
 * ⚠️ App 不能发起配对：`POST /api/pair/issue` 是 loopback-only（`routes.ts:688`），
 * 二维码只能在桌面端生成（需求文档 Q7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    viewModel: PairViewModel,
    /**
     * 是否处于「添加远程」模式（R054）。
     *
     * 首次配对与添加第二台走的是**同一个页面**（扫码 / 链接 / 手工三条入口全部复用），
     * 差别只有三处，且都是「因为已经有一台在跑」才成立：
     *
     * 1. 顶部有返回按钮——首次配对时退无可退，而添加时退回去就是原来那一台；
     * 2. 副标题声明「不会覆盖当前」——否则用户会担心配对会把现有连接顶掉；
     * 3. 成功后不自动切走，而是问一句「现在切过去吗」（原型 s3 定的）。
     */
    addMode: Boolean = false,
    /** 返回上一屏；仅 [addMode] 下有意义（首次配对时为 `null`，不画返回按钮）。 */
    onCancel: (() -> Unit)? = null,
    /** 配对成功且已写入凭证簿后回调（参数是新主机）。 */
    onAdded: (DeviceCredential) -> Unit = {},
    /** 「现在切过去」：直接切到新加的那台。 */
    onSwitchToAdded: (DeviceCredential) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    // ⚠️ 这四个都必须是 rememberSaveable：`MainActivity` 没有 `configChanges`，转屏会重建
    // Activity。用普通 `remember` 的话「填好地址 + 设备 id → 转屏 → 回到扫码入口、两个框
    // 全空」；`LinkCard` 里那份链接文本更糟——它连转屏都不用，**切一次入口 tab** 就
    // 被销毁重建（`when (entry)` 换了分支），刚粘贴的链接直接丢。
    var entry by rememberSaveable { mutableStateOf(PairEntry.Scan) }
    // 扫码通道（B002）：`decodeSingle` 只回调一次，扫到之后控件会自己 `stopDecoding()`。
    // 这里把「闸门」与「控件」分开：重新扫只复位闸门并让同一个控件重新 `decodeSingle()`，
    // 不再用 `key(epoch)` 拆掉重建（重建会与旧控件抢摄像头，是 B002 的成因）。
    // 必须提到页面这一层：`when (entry)` 切一次 tab 就会让 `ScanCard` 整个重建，
    // 状态留在卡片里会丢。
    val scanChannel = remember { ScanChannel() }
    var linkText by rememberSaveable { mutableStateOf("") }
    // 手工登记的两个字段必须同时可见（登记要 (地址, 设备 id) 一对），所以状态提到这里，
    // 两张卡只是它的两个视觉分组。
    var manualBaseUrl by rememberSaveable { mutableStateOf("") }
    var manualDeviceId by rememberSaveable { mutableStateOf("") }
    // 成功卡不再画在整页最底部（见下方注释）：手工入口那两张卡很高，成功卡会整个
    // 落在屏幕之外，用户以为没成功就再点一次——每次点击都真的写进凭证簿，实测连成
    // 11 台同地址主机。改成插在入口选择器下方，出现即在视野内。

    // `lastBaseUrl` 是异步读出来的（`init` 里 `credentialStore.load()`），不能拿它当
    // `remember` 的键：那样从 null 变成值时会把用户已经敲进去的地址**重置**掉。改成
    // 「只在仍为空时回填一次」。
    // 「已加入列表」是一次性事件：页面离开组合时消费掉，否则同一台 VM 在下次进入时
    // 还会把上一轮的成功卡画出来（key 改稳定后 VM 不再被重建，见 AppRoot 的注释）。
    DisposableEffect(Unit) {
        onDispose { viewModel.clearAddedHost() }
    }

    LaunchedEffect(state.lastBaseUrl) {
        val remembered = state.lastBaseUrl
        if (manualBaseUrl.isBlank() && !remembered.isNullOrBlank()) manualBaseUrl = remembered
    }

    Scaffold(
        topBar = {
            // 仅添加模式有顶栏：首次配对时这一屏就是全屏路由，
            // 多一条顶栏只会把 hero 往下挤。
            if (addMode) {
                TopAppBar(
                    navigationIcon = {
                        if (onCancel != null) {
                            AppIconButton(onClick = onCancel, contentDescription = "返回") {
                                Icon(Icons.Default.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "添加远程",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "配对成功后加入列表，不会覆盖当前主机",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 原型 `.pairbody{padding:6px 16px 12px; gap:11px}`。
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PairHero(entry = entry)

            PairEntryPicker(selected = entry, onSelect = { entry = it })

            // 添加模式的成功卡（原型 s3 的「现在切过去吗」）。
            //
            // 为什么要问而不是直接切：用户可能只是想把第二台存进列表（例如在办公室配好家里
            // 的那台，之后回家才用）。直接切过去会把他正在做的事打断，而且新主机
            // 刚配对、很可能还没数据，落地就是空列表。
            state.addedHost?.let { added ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = LocalStatusRunning.current,
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = "已加入列表",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = added.displayName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = added.endpointLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Text(
                            text = "当前仍在原来那台，随时可以在设备栏里切过去。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { onAdded(added) }) { Text("留在当前主机") }
                            TextButton(onClick = { onSwitchToAdded(added) }) { Text("现在切过去") }
                        }
                    }
                }
            }

            when (entry) {
                PairEntry.Scan -> ScanCard(
                    enabled = !state.busy,
                    channel = scanChannel,
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

            PairFootnote(entry = entry)
        }
    }
}

/**
 * 页面顶部：应用标志 + 标题 + 一句副标题。
 *
 * 标题随入口变（原型 s2 的 `.pairhero`）：扫码与手工都是「配对桌面端」，链接入口换成
 * 「粘贴配对链接」——用户切到粘贴时最需要确认的是「我现在做的事叫粘贴」，而不是再读一遍
 * 「配对桌面端」。副标题同理：每一支只说自己那一步的关键前提。
 */
@Composable
private fun PairHero(entry: PairEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMark(size = 44.dp, corner = 12.dp, glyphSize = 22.dp)
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = when (entry) {
                    PairEntry.Link -> "粘贴配对链接"
                    PairEntry.Scan, PairEntry.Manual -> "配对桌面端"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (entry) {
                    PairEntry.Scan -> "扫二维码，或手输链接 / 设备 id"
                    PairEntry.Link -> "从桌面端的二维码页面复制"
                    PairEntry.Manual -> "跳过 accept，直接用已有 deviceId"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** 页脚说明：每支一句，且都带锁图标（原型 `.footnote`）。 */
@Composable
private fun PairFootnote(entry: PairEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text = when (entry) {
                PairEntry.Manual ->
                    "手工登记不会在桌面端新建设备记录，适合从旧手机迁移凭证。"

                PairEntry.Scan, PairEntry.Link ->
                    "配对失效（HTTP 403 unpaired）通常意味着桌面端撤销了这台设备，重新扫一次即可。"
            },
            fontSize = 10.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
    }
}

/** 配对页的三条入口。互斥，任一时刻只展开一条。 */
internal enum class PairEntry(val label: String) {
    Scan("扫码"),
    Link("链接"),
    Manual("手工"),
}

/**
 * 三选一的分段控件（R033 ③，R036 按原型加图标）。
 *
 * 用分段而不是三张并排的卡：这里的三个选项是**互斥的流程**（扫码 / 粘贴链接 / 手工），
 * 不是三个并列功能，用户不需要同时看到全部输入框。
 *
 * 每格带 18dp 线稿图标：纯文字的分段控件在中文里三格宽度接近、「扫码」与「手工」字形
 * 密度相似，扫视时只能逐格读字；图标给了一个不需要读就能定位的锚点。
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
            // 原型 `.seg{gap:4px;background:var(--surfaceVariant);border:1px solid var(--outlineVariant)}`。
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = pillShape,
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, pillShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                // 出现 0 次，而 `Surface(onClick)` 里有）。视觉仍是 30dp 的胶囊，
                // 用 `heightIn(min = 48.dp)` 把可点区域补足——`height` 与 `heightIn`
                // 同向时后者不会反过来压小前者，两者共存得到「48dp 可点、30dp 可见」。
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
                Row(
                    modifier = Modifier.height(30.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (item) {
                            PairEntry.Scan -> ScanFrameIcon
                            PairEntry.Link -> PairLinkIcon
                            PairEntry.Manual -> ManualEntryIcon
                        },
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
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
 * 自己画的角标（[ViewfinderBrackets]）与扫描线（[ScanLine]），两层叠在一起会在屏幕下方
 * 露出它那句状态字，看起来像渲染错位（R033 ③ 设备验收实录）。
 *
 * 取景框是**正方形**（`aspectRatio(1f)`）。改造前是 `fillMaxWidth().height(220.dp)`，
 * 在 360dp 屏上得到 296×220 的横矩形（360 − 页面横向 20×2 − 卡片内 12×2）：页面里
 * 唯一一个非正方形的大色块（R033 ③）。R036 起底色用近黑 `#05080D` 而不是纯黑——纯黑
 * 在深色主题里会和页面的 `#0B1420` 形成一块硬边，近黑能让取景框融进页面。
 *
 * ⚠️ 扫码是**一次性**的：`decodeSingle` 只回调一次，加上 [ScanChannel] 的闸门之后，配对
 * 失败（token 过期 / 桌面端不可达）时继续扫不会有任何反应，而相机预览还在动——从界面
 * 完全看不出已经失效。所以失效后给一个显式的「重新扫描」入口，点它就复位闸门。
 *
 * ⚠️ 复位闸门时**只对同一个控件重新 `decodeSingle()`，绝不重建 `AndroidView`**（B002）：
 * 重建会与旧控件的相机关闭抢同一个摄像头，新控件在 `isPreviewActive()` 还是 false 时
 * `startDecoderThread()` 直接 return 且无人重试，于是「扫过一次、配对失败之后扫码框再也
 * 不识别二维码，只有退出本页重进才好」。改造前（M58 及更早）正是靠 `key(scanEpoch)` 重建，
 * 才踩中这条。
 *
 * 可见性是 `internal`：没有权限时的分支（授权入口 / 二次拒绝后跳系统设置）只能靠渲染
 * 层测试守——真机上「拒绝一次」之后的状态要复现得先清数据，而这条分支的出入口就只有
 * 这一个界面（`AppState.Unpaired` 下没有别的页面可去）。
 */
@Composable
internal fun ScanCard(
    enabled: Boolean,
    channel: ScanChannel,
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
    // 的 `factory` 里注册一次，之后再换 callback 也不会重新注册（除非显式重新武装）。
    // 若这里写 `remember(enabled) { … }`，配对开始（`enabled` false）后控件手里还是
    // 那个捕获了 `enabled = true` 的旧对象，于是「正在配对…」期间再扫一下就又发一次
    // 配对请求（拿已被消费的 token 再 accept 一次）。所以用 `rememberUpdatedState`
    // 让同一个 callback 每次读最新值，同时用 `remember(channel)` 保证实例恒定。
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnScanned by rememberUpdatedState(onScanned)
    val callback = remember(channel) {
        object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = channel.onResult(result?.text, latestEnabled) ?: return
                latestOnScanned(text)
            }
        }
    }
    var scanner by remember { mutableStateOf<BarcodeView?>(null) }

    // 「重新武装」的唯一落点：对**正在用的那个**控件重新 `decodeSingle()`。
    // 这里刻意不新建控件——新控件在 `isPreviewActive()` 还是 false 时
    // `startDecoderThread()` 直接 return 且无人重试，而旧控件还占着摄像头，
    // 结果就是扫码框永久失效（B002）。
    //
    // ⚠️ 只 `decodeSingle()` 还不够：若控件此刻没有预览（例如刚被 `pause()` 过），
    // `startDecoderThread()` 仍会直接 return。所以先确认预览在跑，不在就 `resume()`；
    // `resume()` 起预览后 zxing 自己的 `previewStarted()` 会再调一次
    // `startDecoderThread()`，两条路都能把解码线拉起来。
    //
    // ⚠️ 只有在**真的能用**的时候才动相机：`resume()` 会打开摄像头。用户在配对进行中
    // 把 App 切到后台、配对随后失败时，`enabled` 会在后台变回 true 并触发补武装；若无
    // 条件 `resume()`，等于在用户看不见的时候又把摄像头打开，抵消 R035「退到后台必须
    // 暂停相机」。这里返回 false 让 `ScanChannel` 保留欠账，回前台由 `onActive()` 补做。
    val lifecycleOwner = LocalLifecycleOwner.current
    // 页面此刻是否在前台。
    //
    // ⚠️ 必须自己用 `mutableStateOf` 记：`lifecycle.currentState` **不是** State，
    // 读它不会建立订阅，从后台回到前台时这里会一直停在建组合那一刻的旧快照上。
    // 那样 `rearmScan()` 会在后台误判为「可用」而打开摄像头，回前台又可能误判为
    // 「不可用」把欠账继续挂着。
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val rearmer = remember(channel) {
        object : ScanRearmer {
            override fun rearmScan(): Boolean {
                // `hasPermission` / `resumed` 都在这里读最新值：本对象是 `remember(channel)`
                // 的，只会建一次，直接捕获会拿到建对象那一刻的快照。
                if (!hasPermission || !resumed) return false
                val view = scanner ?: return false
                view.decodeSingle(callback)
                if (!view.isPreviewActive) view.resume()
                return true
            }
        }
    }

    // 控件生命周期与 `channel` 绑定：挂上就补做欠账，释放就解绑（只解绑自己那次）。
    DisposableEffect(channel, rearmer) {
        channel.attach(rearmer)
        onDispose { channel.detach(rearmer) }
    }

    // 配对进行状态变化时把欠着的武装补上：`enabled` 变回 true 说明这一轮配对结束了。
    // 扫到码的那一刻 `enabled` 还是 true，随后 VM 置 busy，回调里的
    // `channel.onResult(…, false)` 分支可能来不及走到，所以这里也补一道。
    LaunchedEffect(enabled) { channel.onEnabled(enabled) }

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
    DisposableEffect(lifecycleOwner, scanner, hasPermission) {
        val view = scanner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    // 从系统设置授权之后回到本页要能自己发现（见下面的权限复检）。
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission && view != null) {
                        deniedOnce = false
                        view.resume()
                        // 回前台：把「在后台时攒下的重新武装」补上。后台不做，
                        // 是因为那时 resume() 会在用户看不见时打开摄像头。
                        channel.onActive()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    view?.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PairCard {
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
                    .background(ViewfinderBackground, groupShape)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        groupShape,
                    ),
            ) {
                ViewfinderGrid()
                // ⚠️ 这里**没有** `key(scanEpoch)`：控件只建一次，重新扫描靠对同一个控件
                // 重新 `decodeSingle()`（B002）。一旦加回 key 式重建，就会回到「配对失败
                // 后扫码框再也不识别二维码」的老毛病。
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        BarcodeView(ctx).also { view ->
                            view.decodeSingle(callback)
                            scanner = view
                            // 新控件已经武装，把可能的欠账销掉（见 ScanChannel.onControlArmed）。
                            channel.onControlArmed()
                        }
                    },
                    onRelease = { view ->
                        view.pauseAndWait()
                        if (scanner === view) scanner = null
                    },
                )
                ViewfinderBrackets()
                ScanLine()
                // 提示压在取景框底部：原型 `.hintc` 是一条 60px 高的渐隐遮罩 + 居中 11px 文案。
                // 放在框内而不是框下，是为了让「往框里放」这句话与框的边界贴在一起。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, ViewfinderBackground),
                            ),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        text = "把桌面端的二维码放进取景框内",
                        fontSize = 11.sp,
                        color = Color(0xFF9FB4CC),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            if (channel.isConsumed && enabled) {
                // `isConsumed && enabled` 的唯一含义是「扫到过，且现在已经不在配对中」
                // ——即这次配对失败回来了（成功会直接导航离开）。此刻控件的
                // `decodeSingle` 已经用掉、再扫不会有任何反应，必须显式给一条回去的路。
                TextButton(onClick = { channel.rescan() }) { Text("重新扫描") }
            }
            StepList(
                steps = listOf(
                    "桌面端点「生成配对二维码」（入口在桌面端网页里，只监听本机，App 无法自己发起）",
                    "App 扫到后立刻配对，成功后凭证用 Keystore 加密存在本机",
                ),
            )
        }
    }
}

/** 取景框底色：原型 `.scan{background:#05080d}`。 */
private val ViewfinderBackground = Color(0xFF05080D)

/**
 * 取景框里的细网格（原型 `.grid`，24px 一格、主色 6% 透明度）。
 *
 * 作用是给出「框的边界在哪」的空间线索：相机预览对着白墙时整块都是同色，
 * 没有网格就看不出取景框的四个角落在屏幕上哪个位置。
 */
@Composable
private fun ViewfinderGrid(modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        val step = 24.dp.toPx()
        var x = step
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

/**
 * 取景框四角的主色角标（原型 `.cn`：26×26px、2.5px 描边、单角圆角 8px）。
 *
 * 画四个 L 形短线而不是一整圈边框：整圈会把相机预览压成「相框里的照片」，
 * 角标只提示对焦区域，不遮挡画面。用主色而不是白色——白色角标在浅色二维码/白纸上
 * 会直接消失在预览里。
 */
@Composable
private fun ViewfinderBrackets(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        val arm = 26.dp.toPx()
        val stroke = 2.5.dp.toPx()
        val inset = 14.dp.toPx()
        val radius = 8.dp.toPx()
        val w = size.width
        val h = size.height
        // 每个角是「一条横臂 + 一条竖臂」，两臂在角点处用圆角相接——用一段
        // `Path` 的弧线画圆角，比两条独立 `drawLine` 更接近原型的 rounded corner。
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x + dx * radius, y)
                lineTo(x + dx * (arm - radius), y)
                // 角点圆角：从横臂折向竖臂。
                quadraticTo(x, y, x, y + dy * radius)
                lineTo(x, y + dy * (arm - radius))
            }
            drawPath(
                path = path,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                ),
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
            // 原型 `.line` 是 2400ms 往复；R033 用的是 2200ms，这里对齐原型。
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pairScanLineOffset",
    )
    // 关掉动画时停在取景框正中（progress = 0.5 的观感），而不是停在顶边。
    val progress = if (animated) animatedProgress else 0.5f
    // 颜色要在组合里取出来：`Canvas` 的 draw lambda 不是 @Composable，读不到 MaterialTheme。
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        // 原型 `.line{left:8%;right:8%;height:2px}`：两端各留 8%，比角标的 14dp 更靠内，
        // 让线看起来是在框内滑行而不是擦着边。
        val inset = size.width * 0.08f
        val y = 14.dp.toPx() + (size.height - 28.dp.toPx()) * progress
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
            strokeWidth = 2.dp.toPx(),
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
        // 成功态的正文按原型压成**一行**等宽字：`host … · token …`。
        // 分成两行（一行 host、一行 token）时用户要上下扫两次才能确认「贴的是不是那台机器、
        // 是不是那个 token」，而这一步存在的意义就是让人一眼核对。
        is PairLink.Accept -> ParsePreview(
            ok = true,
            headline = "已识别",
            detail = "host ${hostOf(link.baseUrl)} · token ${maskToken(link.token)}",
        )

        is PairLink.Device -> ParsePreview(
            ok = true,
            headline = "已识别",
            detail = "host ${hostOf(link.baseUrl)} · 设备 ${maskDeviceId(link.deviceId)}",
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
                headline = "已识别为裸 token",
                detail = "host ${hostOf(fallbackBaseUrl)} · token ${maskToken(link.token)}",
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
    val context = LocalContext.current

    PairCard {
        PairField(
            label = "配对链接或 token",
            value = text,
            onValueChange = onTextChange,
            // 原型 `.finput{min-height:56px}`：链接很长，一行放不下，给三行起。
            minHeight = 56.dp,
            singleLine = false,
            maxLines = 4,
            monospace = true,
        )
        Text(
            text = "整条链接、只贴 token、或从剪贴板粘贴都可以",
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
        preview?.let { ParsePreviewCard(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { readClipboard(context)?.let(onTextChange) },
                // 剪贴板里没东西时点了也没反应，不如直接灰掉——这枚按钮的存在意义就是
                // 「我不想去桌面端再复制一遍」。
                enabled = !busy,
                modifier = Modifier.height(40.dp),
            ) { Text("从剪贴板读", fontSize = 12.sp) }
            Button(
                enabled = !busy && text.isNotBlank() && preview?.ok == true,
                onClick = { onPair(text) },
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("开始配对", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 解析结论卡：解析成功主色描边，失败错误色描边。 */
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
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (preview.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = accent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = preview.headline,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preview.detail,
                fontSize = 10.5.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
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
    val context = LocalContext.current
    PairCard {
        StepTitle(
            step = "填 1/2",
            title = "服务器",
            icon = {
                // 地球：地址是「哪台机器」，语言上比「服务器」这个词更直观。
                Icon(
                    imageVector = GlobeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        PairField(
            label = "地址",
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            singleLine = true,
            monospace = true,
            leading = {
                Icon(
                    imageVector = GlobeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
        )
        Text(
            text = "可以省略 http://，例如 192.168.1.5:19387。",
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { readClipboard(context)?.let(onBaseUrlChange) },
                enabled = !busy,
                modifier = Modifier.height(40.dp),
            ) { Text("粘贴", fontSize = 12.sp) }
            OutlinedButton(
                // 探测只受「探测中」与「配对中」两个条件约束；`probing` 期间禁用是为了
                // 不让用户连点发出多个请求（晚回的会盖掉早回的结论）。
                enabled = !busy && !probing && baseUrl.isNotBlank(),
                onClick = { onProbe(baseUrl) },
                modifier = Modifier.height(40.dp),
            ) { Text(if (probing) "探测中…" else "探测地址", fontSize = 12.sp) }
        }
        probe?.let { ProbeResultCard(it) }
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
    PairCard {
        StepTitle(
            step = "填 2/2",
            title = "设备 id",
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        PairField(
            label = "设备 id",
            value = deviceId,
            onValueChange = onDeviceIdChange,
            singleLine = true,
            monospace = true,
        )
        Text(
            // 真机验收时这一格被留空过——给出真实长度区间比说「填 id」有用。
            text = "32 位十六进制，桌面端「已配对设备」里能看到完整值。",
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
        Text(
            text = "登记不会经过桌面端确认，凭证会直接写进本机。",
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Button(
            enabled = !busy && !probing && baseUrl.isNotBlank() && deviceId.isNotBlank(),
            onClick = { onManual(baseUrl, deviceId) },
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text("登记", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 卡片外壳：原型 `.card{background:var(--surface);border:1px solid var(--outlineVariant);
 * border-radius:14px;padding:12px;gap:10px}`。
 */
@Composable
private fun PairCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = groupShape,
        modifier = modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, groupShape),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * 卡片标题：图标 → 标题 → 右侧等宽步骤号（原型 `.ch`）。
 *
 * 步骤号放在右侧且用 `outline` 色（最暗的一档）：它是导航信息，不该和标题抢注意力，
 * 但必须在「用户现在只填第一格」时也能看见「还有第二格」。
 */
@Composable
private fun StepTitle(
    step: String,
    title: String,
    icon: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = step,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 带独立标签的输入框（原型 `.fgroup` / `.flabel` / `.finput`）。
 *
 * 不用 `OutlinedTextField`：它的 label 会浮在边框上，中文标签「配对链接或 token」浮起来
 * 之后横切一条描边，在窄屏上读起来像被截断了半句。原型把标签放在框**上方**独立一行，
 * 需要的信息一直在原位。
 *
 * 焦点态用主色描边 + 底色不变（原型 `.finput.focus`），这是唯一的「我现在在这一格」信号。
 */
@Composable
private fun PairField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    minHeight: Dp = 40.dp,
    monospace: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val fieldShape = RoundedCornerShape(10.dp)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(MaterialTheme.colorScheme.background, fieldShape)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, fieldShape)
                .padding(horizontal = 11.dp, vertical = if (singleLine) 0.dp else 9.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = if (label == "地址") "192.168.1.5:19387" else "粘贴或输入",
                        fontSize = 11.5.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = singleLine,
                    maxLines = maxLines,
                    interactionSource = interactionSource,
                    textStyle = TextStyle(
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
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
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = accent.copy(alpha = 0.07f),
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.42f), shape),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (probe.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = accent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = probe.headline,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            // 回显被探测的地址：`clearProbe()` 只在「改地址」时清卡，挡不住在途请求——
            // 地址 A 慢、B 快时 A 的结论会后到并把 B 的绿卡盖掉。把结论对应的地址一起
            // 显示出来，用户至少能看见「这张卡说的不是我刚填的那个地址」。
            if (probe.baseUrl.isNotBlank()) {
                Text(
                    text = hostOf(probe.baseUrl),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            probe.lines.forEach { line ->
                Text(
                    text = line,
                    fontSize = 10.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 步骤序号里那个数字的排版样式。
 *
 * 必须自己钉死行高，不能靠主题继承：`Box(contentAlignment = Center)` 居中的是「行框」
 * 而不是数字墨迹。主题里 `bodyLarge` 的行高是 24sp，远大于 9.5sp 的字号，行框上下留白
 * 又不对称，数字会被压到圆心以下。1080×2400 / 480dpi 实测：圆框 y 1450–1500、墨迹
 * y 1473–1493，圆心 1475 而墨迹中心 1483，**偏低 8px**；把行高收成与字号相同、再用
 * `LineHeightStyle` 居中裁掉两端行距后，两者差 1px（字形末端的亚像素）。
 *
 * 单独提成常量是为了让渲染层测试能直接断言这几个字段——`StepList` 是 private，
 * 从外面只能通过像素比对才知道有没有被改回「继承行高」，那条路在 Robolectric 下
 * 没有真字体，办不到。
 */
internal val StepNumberStyle = TextStyle(
    fontSize = 9.5.sp,
    lineHeight = 9.5.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * 带圆形数字序号的步骤清单（原型扫码支的 `.stepline`）。
 *
 * 为什么值得占垂直空间：扫码这条路的前置条件（二维码只能在桌面端生成）是 App 里唯一
 * 一处「做不到」的说明。写在页脚会被当成免责声明划过去，做成带序号的清单才会被读到。
 */
@Composable
internal fun StepList(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        steps.forEachIndexed { index, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(17.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = StepNumberStyle,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = text,
                    fontSize = 11.sp,
                    lineHeight = 17.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 读剪贴板里的第一段文本。
 *
 * 直接用平台 `ClipboardManager` 而不是 Compose 的 `LocalClipboardManager`：
 * 后者在较新的 Compose 版本里已标记废弃（迁移到 `LocalClipboard`），而这个工程同时
 * 依赖 compose BOM 与 material3，跟随哪一版剪贴板 API 都会在升级时再改一次。
 * 平台 API 在这里够用，且不引入版本耦合。
 *
 * Android 12+ 在用户可以主动粘贴的前提下（本函数只在按钮点击里调用）允许读取。
 */
private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    return text?.takeIf { it.isNotBlank() }
}
