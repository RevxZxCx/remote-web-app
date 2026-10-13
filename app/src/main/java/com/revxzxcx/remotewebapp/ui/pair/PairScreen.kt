package com.revxzxcx.remotewebapp.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 配对页（原型 `#pair`）。
 *
 * 三条入口：
 * 1. **扫码** —— 桌面端生成二维码（`<base>/pair-accept?pair=<token>`），App 扫完拿 `token`；
 * 2. **粘贴链接** —— 同一段文本手输；
 * 3. **手工登记** —— 地址 + 设备 id（调试 / 恢复）。
 *
 * ⚠️ App 不能发起配对：`POST /api/pair/issue` 是 loopback-only（`routes.ts:688`），
 * 二维码只能在桌面端生成（需求文档 Q7）。
 */
@Composable
fun PairScreen(
    viewModel: PairViewModel,
) {
    val state by viewModel.state.collectAsState()
    var manualMode by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "配对桌面端",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "在桌面端的 DSH 里生成配对二维码，然后用这里扫码。二维码内容形如 " +
                    "https://<地址>/pair-accept?pair=<token>。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            QrScanCard(
                enabled = !state.busy,
                onScanned = viewModel::pairWithText,
            )

            if (!manualMode) {
                TextButton(onClick = { manualMode = true }) {
                    Text("手输链接 / 设备 id")
                }
            } else {
                ManualEntryCard(
                    busy = state.busy,
                    initialBaseUrl = state.lastBaseUrl.orEmpty(),
                    onPair = viewModel::pairWithText,
                    onManual = viewModel::pairManually,
                    onProbe = viewModel::probe,
                    onCancel = { manualMode = false },
                )
            }

            state.probe?.let { probe ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = probe,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            state.error?.let { error ->
                Surface(
                    color = Color(0xFF3A1E22),
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
                            color = Color(0xFFFFB4AB),
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
                    Spacer(Modifier.size(10.dp))
                    Text("正在配对…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "提示：配对成功后凭证会用 Android Keystore 加密保存在本机。" +
                    "如果桌面端撤销了这台设备，任何请求都会返回 403 unpaired，需要重新配对。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 扫码卡片。
 *
 * 用 zxing-android-embedded 的 [DecoratedBarcodeView] 直接嵌进 Compose（`AndroidView`），
 * **不用**它自带的 `CaptureActivity` —— 那个 Activity 强制 `sensorLandscape` 且主题不受控。
 *
 * 相机权限走运行时申请；没有权限或没有相机时，本卡片显示一行提示，用户仍可用手输入口。
 */
@Composable
private fun QrScanCard(
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

    // 扫到一次就停：decodeSingle 保证只回调一次。
    val callback = remember {
        object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text
                if (!text.isNullOrBlank()) onScanned(text)
            }
        }
    }
    var scanner by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    DisposableEffect(scanner) {
        val view = scanner
        if (view != null && hasPermission) view.resume() else view?.pause()
        onDispose { view?.pauseAndWait() }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("扫码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)

            if (!hasPermission) {
                Text(
                    text = "需要相机权限才能扫码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            DecoratedBarcodeView(ctx).also { view ->
                                view.decodeSingle(callback)
                                scanner = view
                            }
                        },
                    )
                }
                Text(
                    text = "把桌面端的二维码放进取景框内。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 手输链接 / 地址 + 设备 id / 地址探测。 */
@Composable
private fun ManualEntryCard(
    busy: Boolean,
    initialBaseUrl: String,
    onPair: (String) -> Unit,
    onManual: (String, String) -> Unit,
    onProbe: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var baseUrl by remember(initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var deviceId by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("手输", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("配对链接或 token") },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Button(
                enabled = !busy && link.isNotBlank(),
                onClick = { onPair(link) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用链接配对") }

            Text(
                text = "或者手工登记（跳过 accept，直接使用已有 deviceId）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text("设备 id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && baseUrl.isNotBlank(),
                    onClick = { onProbe(baseUrl) },
                ) { Text("探测地址") }
                Button(
                    enabled = !busy && baseUrl.isNotBlank() && deviceId.isNotBlank(),
                    onClick = { onManual(baseUrl, deviceId) },
                ) { Text("登记") }
            }
            TextButton(enabled = !busy, onClick = onCancel) { Text("收起") }
        }
    }
}
