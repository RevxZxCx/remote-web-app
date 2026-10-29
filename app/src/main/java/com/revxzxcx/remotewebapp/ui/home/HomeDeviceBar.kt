package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential

/**
 * 首页顶栏下方的**设备栏**（R054）：列出所有已配对的远程，点一行即切换当前主机。
 *
 * 形态（`docs/R054-多主机效果图.html` s1/s2）：
 * ```
 * 我的远程        3    ✕
 * ────────────────────────
 * ● 家里的主机  当前   192.168.1.5:19387        ⋯
 * ○ 公司服务器  离线   office.example.com       ⋯
 * ＋ 添加远程 Web
 * 点一行即切换。切走后原来的会话不会中断，在桌面端照常跑。
 * ```
 *
 * ## 为什么是「顶栏下拉」而不是抽屉或独立页
 * 它是**顶栏标题的一部分**：标题现在显示当前主机名，点它就该展开「为什么是这一台、
 * 还能换成哪几台」。做成左侧抽屉会与已有的「会话列表」抽屉抢同一个手势；做成独立页
 * 则切换主机要跨两屏，而切换是这个功能最高频的动作。
 *
 * ## 「切换」与「管理」刻意分开
 * 点行 = 切换，点行末 `⋯` = 管理（重命名 / 编辑地址 / 测试连接 / 从列表移除）。
 * 若把管理项塞进点击行，用户想切过去却可能点进「从列表移除」——行末那一枚图标是唯一
 * 的破坏性入口，且删除还要二次确认。
 *
 * 状态点语义与工作区/会话行一致：`LocalStatusRunning` 表示这台正连着且在跑，
 * `onSurfaceVariant` 表示空闲，`error` 表示当前这台连接出过错（由 [activeFailed] 传入）。
 */
@Composable
internal fun DeviceBar(
    book: CredentialBook,
    expanded: Boolean,
    activeFailed: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onRename: (DeviceCredential, String) -> Unit,
    onEditEndpoint: (DeviceCredential, String) -> Unit,
    onTestConnection: (DeviceCredential) -> Unit,
    onRemove: (DeviceCredential) -> Unit,
    onAddRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        // 展开时每一行都可能有自己的弹出层（菜单 / 对话框），状态都挂在这一层，
        // 而不是在行内 remember：行会在切换主机后重排，行内状态会跟着错位。
        var menuHostId by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<DeviceCredential?>(null) }
        var editing by remember { mutableStateOf<DeviceCredential?>(null) }
        var removing by remember { mutableStateOf<DeviceCredential?>(null) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            // 浅色下靠投影分层会糊成灰边（原型 s6 的说明），所以只用描边 + 表面色。
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "我的远程",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${book.hosts.size}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    AppIconButtonSmall(onClick = onDismiss, contentDescription = "收起远程列表") {
                        CloseIcon()
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )

                book.hosts.forEach { host ->
                    val isActive = host.hostId == book.activeHostId ||
                        (book.activeHostId == null && host == book.hosts.firstOrNull())
                    DeviceRow(
                        host = host,
                        active = isActive,
                        failed = isActive && activeFailed,
                        onSwitch = { onSwitch(host.hostId) },
                        onMenu = { menuHostId = host.hostId },
                    )
                    // 菜单锚在这一行下方：把它写在行内 Box 里会跟着滚动裁剪，
                    // 而设备栏本身不是可滚容器，这里用独立 DropdownMenu 更稳。
                    DropdownMenu(
                        expanded = menuHostId == host.hostId,
                        onDismissRequest = { menuHostId = null },
                        shape = MenuShape,
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                    ) {
                        Column(modifier = Modifier.padding(5.dp)) {
                            MenuRow(
                                icon = Icons.Default.Edit,
                                label = "重命名",
                                enabled = true,
                            ) {
                                menuHostId = null
                                renaming = host
                            }
                            MenuRow(
                                icon = Icons.Default.LocationOn,
                                label = "编辑地址",
                                enabled = true,
                            ) {
                                menuHostId = null
                                editing = host
                            }
                            MenuRow(
                                icon = Icons.Default.Refresh,
                                label = "测试连接",
                                enabled = true,
                            ) {
                                menuHostId = null
                                onTestConnection(host)
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                            )
                            MenuRow(
                                icon = Icons.Default.Delete,
                                label = "从列表移除",
                                enabled = true,
                            ) {
                                menuHostId = null
                                removing = host
                            }
                        }
                    }
                }

                // 「＋ 添加远程 Web」——与顶栏右侧的 + 等价的两个入口。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .height(40.dp)
                        .clip(MenuRowShape)
                        .clickable(role = Role.Button, onClick = onAddRemote)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // 加号用主色：这是这一栏里唯一的「新建」动作。
                    PlusIcon(tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "添加远程 Web",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = "点一行即切换。切走后原来的会话不会中断，在桌面端照常跑。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                )
            }
        }

        // 对话框放在 AnimatedVisibility 内部：它们跟随设备栏一起出现/消失，
        // 收起设备栏时不该留一个孤儿对话框。
        renaming?.let { host ->
            HostRenameDialog(
                host = host,
                onDismiss = { renaming = null },
                onConfirm = { label ->
                    renaming = null
                    onRename(host, label)
                },
            )
        }
        editing?.let { host ->
            HostEndpointDialog(
                host = host,
                onDismiss = { editing = null },
                onConfirm = { baseUrl ->
                    editing = null
                    onEditEndpoint(host, baseUrl)
                },
            )
        }
        removing?.let { host ->
            HostRemoveDialog(
                host = host,
                total = book.hosts.size,
                onDismiss = { removing = null },
                onConfirm = {
                    removing = null
                    onRemove(host)
                },
            )
        }
    }
}

/**
 * 设备栏里的一行。
 *
 * 三列的职责：状态点（这台通不通）/ 名字 + 地址（这一台是谁）/ 当前徽标 + `⋯`（管理）。
 * 点整行切换，点 `⋯` 才进管理菜单——两者的可点区域不重叠。
 */
@Composable
private fun DeviceRow(
    host: DeviceCredential,
    active: Boolean,
    failed: Boolean,
    onSwitch: () -> Unit,
    onMenu: () -> Unit,
) {
    val dotColor = when {
        failed -> MaterialTheme.colorScheme.error
        active -> LocalStatusRunningForDeviceBar()
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MenuRowShape)
            .clickable(enabled = !active, role = Role.Button, onClick = onSwitch)
            .padding(start = 10.dp, end = 2.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = host.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    // 「当前」用小胶囊而不是纯文字：它表达的是一种状态，不是一个词。
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = "当前",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = host.endpointLabel,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        AppIconButtonSmall(onClick = onMenu, contentDescription = "管理 ${host.displayName}") {
            MoreIcon()
        }
    }
}

/**
 * 播放中的绿（与首页工作区行的均衡器同色）。
 *
 * 直接读 `MaterialTheme.colorScheme` 里没有这个语义色，而 R017 定过「运行中状态色只能
 * 有一个来源」。这里与 `LocalStatusRunning` 保持一致，但设备栏是「当前这台连着」而不是
 * 「正在生成」——语义不同，颜色相同，所以单独起一个名字并在此说明。
 */
@Composable
private fun LocalStatusRunningForDeviceBar() = com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning.current

/**
 * 重命名对话框。
 *
 * 空输入是合法的：表示「清掉自定义名」，行上回退显示地址（[DeviceCredential.displayName]
 * 的既有行为）。所以确认按钮不因空输入而禁用，但文案要说明这一点。
 */
@Composable
private fun HostRenameDialog(
    host: DeviceCredential,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(host.hostId) { mutableStateOf(host.label.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "重命名这台远程",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = "只改手机列表里显示的名字，桌面端不受影响。留空则显示地址。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    singleLine = true,
                    placeholder = { Text("例如：家里的主机", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 编辑地址对话框。
 *
 * 地址是 socket 的一部分：改动会让连接**重建**（缓存分区也随之换，因为分区键含 baseUrl）。
 * 所以对话框里要写清代价，而不是假装它和改名一样轻。
 */
@Composable
private fun HostEndpointDialog(
    host: DeviceCredential,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(host.hostId) { mutableStateOf(host.baseUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "编辑服务器地址",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = "改地址会重新连接这台桌面端，当前会话的进行中内容需要重新拉取。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    singleLine = true,
                    placeholder = { Text("https://47.114.41.61", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text.trimEnd('/') != host.baseUrl.trimEnd('/'),
            ) { Text("保存并重连") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 同一个确认框的两种用途（M57 真机验收发现的缺陷）。
 *
 * 背景：[HostRemoveDialog] 被「从列表移除」和「重新配对」两个入口复用。复用本身是对的
 * ——两者对用户而言都是「这台不要了，桌面端不受影响」，形态也应该一致。但第二处在
 * M57 之前直接传了同一份文案，于是点「重新配对」弹出的是**「从列表移除？」**，图标还是
 * 红色垃圾桶、按钮写「确认移除」。用户点的是「修好它」，看到的却是「删掉它」，而且
 * 确认之后会被送去配对页——行为和标题对不上。
 *
 * 所以把**文案**参数化、形态不动。三种用途的差别只在说了什么，不在后果轻重，
 * 因此仍然共用 [HostRemoveDialog] 而不是各写一个 AlertDialog。
 */
internal enum class HostRemovePurpose {
    /** 设备栏 `⋯` / 溢出菜单 / 设置页：把这台从本机列表里移除。 */
    Remove,

    /** 错误横幅、ConnectionBanner：把这台删掉并回配对页重新配。 */
    Repair,
    ;

    /** 这枚图标画在标题左边。 */
    val icon: androidx.compose.ui.graphics.vector.ImageVector
        get() = when (this) {
            Remove -> androidx.compose.material.icons.Icons.Default.Delete
            Repair -> androidx.compose.material.icons.Icons.Default.Refresh
        }

    /** 标题。 */
    val title: String
        get() = when (this) {
            Remove -> "从列表移除？"
            Repair -> "重新配对这台？"
        }

    /** 确认按钮文字。 */
    val confirm: String
        get() = when (this) {
            Remove -> "确认移除"
            Repair -> "删掉重配"
        }

    /**
     * 正文。说清代价与可逆性——两种用途都要交代「桌面端不受影响」与「本机缓存会被清掉」，
     * 因为这两件事在两种用途下都成立。
     */
    /** 结合当前列表长度，把文案一次性定下来，供 [HostRemoveDialog] 直接取用。 */
    fun resolve(total: Int): HostRemoveCopy = HostRemoveCopy(
        icon = icon,
        title = title,
        body = body(total),
        confirm = confirm,
    )

    fun body(total: Int): String = when (this) {
        Remove -> "桌面端不受影响，这台在本机缓存的会话与草稿会被清掉。重新配对就能加回来" +
            if (total <= 1) "；它是最后一台，移除后会回到配对页。" else "。"
        Repair -> "会先把它从本机列表里删掉，再送你回配对页。桌面端不受影响，重新配一次" +
            "就能加回来；本机缓存的会话与草稿会被清掉" +
            if (total <= 1) "。它是最后一台，删掉后列表会清空。" else "；其余 ${total - 1} 台保留。"
    }
}

/** [HostRemovePurpose] 结合列表长度展开后的最终文案。 */
internal data class HostRemoveCopy(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val body: String,
    val confirm: String,
)

/**
 * 「从列表移除」的二次确认。
 *
 * 用**琥珀色**（tertiary）而不是红色（原型 s2 的说明）：这台只是从手机列表里移除，
 * 桌面端完全不受影响，重配一次就能加回来——代价没有「断开当前远程」那么大。
 * 用红色会把它和真正的破坏性操作混为一谈。
 *
 * 正文必须说清三件事：① 桌面端不受影响；② 这台在本机缓存的会话与草稿会被清掉；
 * ③ 重配就能加回来。缺第 ② 条会让用户以为只是「隐藏起来」。
 */
@Composable
internal fun HostRemoveDialog(
    host: DeviceCredential,
    total: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    purpose: HostRemovePurpose = HostRemovePurpose.Remove,
) {
    val copy = purpose.resolve(total)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = copy.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                        Text(
                            text = host.displayName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = host.endpointLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                Text(
                    text = copy.body,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(copy.confirm, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ------------------------------------------------------------------ 小图标

/**
 * 设备栏用到的几枚小图标。
 *
 * 全部复用 `material-icons-core` 里已有的字形（`Edit` / `Delete` / `Add` / `Close` /
 * `MoreVert` / `Refresh`）。R033 起就有的约束是「只有现有 49 枚里真没有的语义才自绘」
 * （见 `HomeIcons.kt` 的 ArchiveIcon / PinIcon）——这几枚恰好都有，自绘只会多出
 * 一份没人审过的路径数据。
 */
@Composable
private fun PlusIcon(tint: androidx.compose.ui.graphics.Color) {
    Icon(
        imageVector = Icons.Default.Add,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

@Composable
private fun CloseIcon() {
    Icon(
        imageVector = Icons.Default.Close,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoreIcon() {
    Icon(
        imageVector = Icons.Default.MoreVert,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 设备栏里的小号图标按钮：36dp 触控区。
 *
 * 不复用顶栏的 `AppIconButton`：那个是 48dp，放进 44dp 的设备行里会把行高撑开。
 * 36dp 仍大于 Material 的 24dp 最小可点区，也不会让两枚图标挤在一起。
 */
@Composable
private fun AppIconButtonSmall(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
