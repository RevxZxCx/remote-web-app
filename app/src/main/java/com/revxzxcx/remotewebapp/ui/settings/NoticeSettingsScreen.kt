package com.revxzxcx.remotewebapp.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.notify.NoticeFix
import com.revxzxcx.remotewebapp.core.notify.NoticeStatus
import com.revxzxcx.remotewebapp.core.notify.noticeFixLocation
import com.revxzxcx.remotewebapp.core.notify.OemGuideSteps
import com.revxzxcx.remotewebapp.core.notify.noticeStatusDetail
import com.revxzxcx.remotewebapp.core.notify.shouldGuideToSystemSettings
import com.revxzxcx.remotewebapp.core.notify.shouldRequestPermission
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.theme.groupShape

/**
 * 「通知与后台保活」二级页（R056，原型 `docs/R056-系统通知.html` 第 5 节）。
 *
 * ## 为什么是二级页而不是塞进设置主页
 * 通知这块要放三样东西：三个开关、一层「卡在哪」的诊断、四条一加保活路径。
 * 塞进设置主页会把「危险操作」挤到两屏之外，而那里是最需要一眼看见的。
 *
 * ## 为什么全程只收纯状态 + 回调
 * 与 [SettingsScreen] 同一条纪律：这个页面**不读 `DshConnection`、不读 `AppContainer`**。
 * 状态由容器侧的检测器算好传进来（[NoticeStatus]），动作全是回调。
 * 好处是整页可以用传假的 `NoticeStatus` 直接渲染出十种组合，不用伪造系统服务。
 *
 * ## 请求权限的时机
 * **只在这个页面里请求**，App 启动时不弹（见 [shouldRequestPermission] 的 KDoc）。
 * 用户主动点进「通知」这一页，才是他表达了「我想收通知」的时刻。
 *
 * @param status 四层开关的当前状态（系统总开关 / 运行时权限 / 系统频道 / App 内开关）。
 * @param onBack 返回设置主页。
 * @param onToggleDone App 内「任务结束」开关。
 * @param onToggleAsk App 内「需要确认」开关。
 * @param onToggleLockScreenDetail 锁屏是否显示详细内容。
 * @param onPermissionAsked 已经弹过系统权限框（立刻记账，不等结果——永久拒绝时系统
 *   直接回调 false 且不再弹框，不记账的话我们会永远以为「还没问过」）。
 * @param onPermissionResult 权限弹窗的结果；调用方需要据此重新检测一遍状态。
 * @param onOpenSystemSettings 跳到系统设置里本应用的通知页（频道级开关只在那里能改）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeSettingsScreen(
    status: NoticeStatus,
    onBack: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onToggleAsk: (Boolean) -> Unit,
    onToggleLockScreenDetail: (Boolean) -> Unit,
    onPermissionAsked: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> onPermissionResult(granted) }

    // 「问题就在本页开关上」时那颗按钮是「知道了」——点一下把卡片收起来。
    // 状态放页面内而不是往上层传：收起只是一次本页的视图动作，与通知状态无关，
    // 而且用户把开关拨回去之后卡片本来就该消失。`anyUsable` 一旦变真就复位，
    // 否则他再把开关关掉时会看不到这张卡（一次「知道了」不该永久静音诊断）。
    var problemDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(status.anyUsable) {
        if (status.anyUsable) problemDismissed = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("通知与后台保活")
                        Text(
                            text = "任务跑完、需要你确认时提醒",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    AppIconButton(onClick = onBack, contentDescription = "返回") {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 顶部诊断条：只在确实有问题时出现。四层开关里任意一层没通，用户看到的现象
            // 都是「什么都不响」，所以这里必须替他说出是哪一层。
            //
            // 按钮去哪儿由 `noticeFixLocation` 决定，**不写死「去系统设置」**：四层里有一层
            // 就在本页（下面那两个开关）。写死的话，用户看到「两类都被你关掉了」配一颗
            // 「去系统设置」，点过去发现系统里一切正常，只能原路返回——比不给按钮更糟。
            if (!status.anyUsable && !problemDismissed) {
                when (noticeFixLocation(status)) {
                    NoticeFix.Permission -> NoticeProblemCard(
                        text = noticeStatusDetail(status),
                        actionLabel = "开启通知",
                        onAction = {
                            // 记账要在调起之前：永久拒绝时系统直接回调 false 且不再弹框，
                            // 不记账的话我们会永远以为「还没问过」，下次又给一颗「开启通知」。
                            onPermissionAsked()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                // API 33 以下没有这个运行时权限，「没拿到」只可能是
                                // 总开关被关，只能去系统设置。
                                onOpenSystemSettings()
                            }
                        },
                    )

                    // 权限被永久拒绝过：再调 requestPermissions 系统直接回 false 且不弹框，
                    // 用户会以为「点了没反应」，所以只能引导去系统设置。
                    NoticeFix.SystemSettings -> NoticeProblemCard(
                        text = noticeStatusDetail(status),
                        actionLabel = "去系统设置",
                        onAction = onOpenSystemSettings,
                    )

                    // 问题就在本页下面那两个开关上：给一颗「知道了」把卡片收起来，
                    // 而不是把用户送到一个没有问题的页面。
                    NoticeFix.AppSwitch -> NoticeProblemCard(
                        text = noticeStatusDetail(status),
                        actionLabel = "知道了",
                        onAction = { problemDismissed = true },
                    )
                }
            } else if (shouldGuideToSystemSettings(status)) {
                // 一类能用、另一类因为权限被拒而不可用：仍要提醒，但按钮只能去系统设置。
                NoticeProblemCard(
                    text = noticeStatusDetail(status),
                    actionLabel = "去系统设置",
                    onAction = onOpenSystemSettings,
                )
            }

            SectionLabel("提醒我")

            CardGroup {
                SwitchRow(
                    icon = Icons.Default.Star,
                    title = "任务结束",
                    detail = "一轮任务跑完或跑挂时提醒你。同一个会话连跑多轮只响一次。",
                    checked = status.doneEnabled,
                    onCheckedChange = onToggleDone,
                )
                // 系统频道被用户单独关掉时必须单独说：App 内开关还是「开」的，
                // 但 notify() 会被系统静默丢弃——不指出来就是「设置里明明开着却不响」。
                if (!status.doneChannelEnabled) {
                    RowDivider()
                    ChannelBlockedRow(detail = "「任务结束」这个系统频道被关掉了，去系统设置里打开，或直接关掉上面的开关。")
                }
                RowDivider()
                SwitchRow(
                    icon = Icons.Default.Warning,
                    title = "需要确认",
                    detail = "模型要使用工具时请你批准或拒绝。关掉会错过请求，任务会一直等着。",
                    checked = status.askEnabled,
                    onCheckedChange = onToggleAsk,
                )
                if (!status.askChannelEnabled) {
                    RowDivider()
                    ChannelBlockedRow(detail = "「需要确认」这个系统频道被关掉了，去系统设置里打开，或直接关掉上面的开关。")
                }
                RowDivider()
                SwitchRow(
                    icon = Icons.Default.Lock,
                    title = "锁屏显示详细内容",
                    detail = "关闭时锁屏只显示「远程 Web 有新提醒」，不显示会话名与失败原因。",
                    checked = status.lockScreenDetail,
                    onCheckedChange = onToggleLockScreenDetail,
                )
            }

            SectionLabel("后台保活")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = "下面四项没有接口可以代劳，只能你手动开一次。",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "不开的话，一加会在锁屏几分钟后回收本应用，连接断开，通知也就收不到了。" +
                            "系统更新后这些设置会被重置，那时需要再开一次。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            CardGroup {
                OemGuideSteps.forEachIndexed { index, step ->
                    if (index > 0) RowDivider()
                    GuideStepRow(
                        index = index + 1,
                        title = step.title,
                        detail = step.detail,
                    )
                }
            }

            CardGroup {
                SettingRow(
                    icon = Icons.Default.Info,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "系统通知设置",
                    detail = "改每个频道的响铃、震动、锁屏样式",
                    onClick = onOpenSystemSettings,
                )
            }

            Text(
                text = "说明：本应用没有接入厂商推送通道，通知完全依赖本机与桌面端的连接。" +
                    "连接在后台被系统断开时，通知也不会到达——这时回到 App 会看到「已断开」。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * 顶部诊断卡：琥珀色（tertiary），不是红色——通知不可用不影响数据，只是少了一次提醒。
 * 用红色会让人以为出了故障，反而去查网络。
 */
@Composable
private fun NoticeProblemCard(text: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = groupShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "通知现在不会响",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = text,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** 系统频道被关掉时的警示行。文案里带「或直接关掉上面的开关」，避免开关与真相不一致。 */
@Composable
private fun ChannelBlockedRow(detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = detail,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** 一条保活路径：序号 + 标题 + 具体路径。序号是必要的，一加的设置项分散在四个不同页面。 */
@Composable
private fun GuideStepRow(index: Int, title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * 开关行：与设置主页的 [SettingRow] 同一套几何（26dp 图标块 + 13sp 标题 + 11sp 说明），
 * 右侧换成 Switch。**整行可点**——只让 Switch 可点会让一半的用户点在这行文字上没反应。
 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 与设置主页同名同形的分组标签。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** 一组设置 = 一张卡，与设置主页同规格（[groupShape]）。 */
@Composable
private fun CardGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = groupShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 50.dp, end = 12.dp),
    )
}

/** 可点一行：图标块 + 标题 + 说明 + 右箭头（与设置主页的 SettingRow 一致）。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(color = tint.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = tint,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
