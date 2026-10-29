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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
            modifier = Modifier
                .fillMaxWidth()
                .drawDeviceBarEdges(),
            // B001（用户 m03969 第 1 条）：这里原本是 `RoundedCornerShape(0.dp)`，
            // 通条直角。原型 s1 的 `.devbar` 确实也是通条，但真机上它紧贴顶栏下方、
            // 悬在内容之上，四个直角与全 App 其它容器（会话行 16dp、抽屉 20dp、
            // 菜单 14dp、按钮 pill）格格不入——用户原话「和实际的设计风格有割裂感」。
            //
            // 采用方案 B：**只圆下两角**。上两角必须留直角，因为这一条是顶栏被展开
            // 出来的那部分，上沿与 TopAppBar 直接相接；一旦圆上去，两角会露出顶栏
            // 底下的背景，看着像裂了一道缝。
            shape = DeviceBarShape,
            // 浅色下靠投影分层会糊成灰边（原型 s6 的说明），所以只用描边 + 表面色。
            color = MaterialTheme.colorScheme.surface,
            // B007（用户 m05374 第 1 条）：描边**不能**用 BorderStroke——它画整圈四边，
            // 上沿会在顶栏下留一条 1dp 亮线，把「从顶栏连续展开」的观感切断。
            // 改由下面的 Modifier.drawOutlineEdges 只画两侧与下沿。
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
                        // B006：按钮与菜单必须同在**这一行**的 Box 里。早先菜单是这一行的兄弟
                        // 节点、挂在设备栏那个 Column 下，Popup 的锚点落在整栏布局流上，
                        // 于是点任何一行的「⋯」都从卡片底部弹出来（用户 m05374 第 3 条）。
                        menuButton = {
                            Box {
                                AppIconButtonSmall(
                                    onClick = { menuHostId = host.hostId },
                                    contentDescription = "管理 ${host.displayName}",
                                ) {
                                    MoreIcon()
                                }
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
                        },
                    )
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
 * 设备栏的下拉容器形状：**只圆下两角**，上两角保持直角。
 *
 * 为什么不是四面圆角：设备栏是顶栏被展开出来的那部分，上沿与 `TopAppBar` 直接相接。
 * 圆上去之后两角会露出顶栏底下的背景，在深色主题下看是两块黑缺口。
 *
 * 为什么不是直角：这一条悬在内容区之上，四个直角与全 App 其它容器
 * （会话行 16dp / 抽屉 20dp / 菜单 14dp / 按钮 pill）风格不一致，用户原话是
 * 「和实际的设计风格有割裂感」（B001，m03969 第 1 条）。
 *
 * 数值取 16dp 与首页会话行的 `rowShape` 对齐——两者在屏幕上上下相邻，
 * 用同一档圆角看起来才是同一套语言。
 */
internal val DeviceBarCornerRadius = 16.dp

/** 设备栏描边宽度（B007：只画两侧与下沿）。 */
internal val DeviceBarEdgeWidth = 1.dp

internal val DeviceBarShape = RoundedCornerShape(
    bottomStart = DeviceBarCornerRadius,
    bottomEnd = DeviceBarCornerRadius,
)

/**
 * 设备栏描边的**几何计划**：只含左右两侧 + 下沿，上沿不封口（B007）。
 *
 * 为什么不用 `BorderStroke`：它只能画整圈四边。设备栏上沿紧贴 `TopAppBar`，
 * 那条 1dp 线会让「从顶栏连续展开」看起来像两块拼起来的板子——用户原话
 * 「卡片上边界还会显示一条线，这样和我希望的从上边展开还差一些」（m05374 第 1 条）。
 *
 * 为什么抽成纯数据而不是直接给 `Path`：`androidx.compose.ui.graphics.Path` 在
 * Android 上转发给 `android.graphics.Path`，Robolectric 里 `PathMeasure.length()`
 * 恒返回 0，长度断言全是假红。所以几何用坐标描述出来，`Path` 由
 * [OutlineEdgePlan.buildPath] 生成，测试只断纯数据。
 *
 * 四段坐标的顺序即绘制顺序：左竖 → 左下弧 → 下横 → 右下弧 → 右竖。
 * **首尾都在 y = 0 但不连着**，这就是上沿的开口。
 */
internal data class OutlineEdgePlan(
    /** 左竖线：从 (left, 0) 到 (left, bottom - radius)。 */
    val left: Float,
    /** 右竖线：从 (right, 0) 到 (right, bottom - radius)。 */
    val right: Float,
    /** 下沿所在 y（已按半宽内缩）。 */
    val bottom: Float,
    /** 实际生效的下两角半径（夹取后）。 */
    val radius: Float,
) {
    /** 未夹取时的角半径是否已被夹小——夹取说明传入半径超过了一半高度。 */
    fun wasClamped(requested: Float): Boolean = radius < requested

    /** 只有左右两侧 + 下沿的长度；上沿若补上会多出 [right] - [left]。 */
    val edgeLength: Float
        get() = if (radius > 0f) {
            (bottom - radius) * 2f + (right - left - radius * 2f) + 2f * (Math.PI.toFloat() / 2f * radius)
        } else {
            bottom * 2f + (right - left)
        }

    /** 补上上沿之后会多出的长度——用来证明「上沿真的没画」。 */
    val topEdgeLength: Float
        get() = right - left

    /**
     * 描边的绘制命令序列。**这是几何的唯一真相**，[buildPath] 只负责把它喂给 `Path`。
     *
     * 为什么单独暴露命令而不是直接返回 `Path`：`android.graphics.Path` 在 Robolectric
     * 里没有真实实现（`PathMeasure.length()` 恒为 0），改 `buildPath()` 里画什么
     * 在测试里完全看不出来——B007 的变异 M1「把上沿连起来」、M2「去掉左侧竖线」
     * 就是这么活下来的。命令是纯数据，可以逐条断言。
     *
     * 顺序：MoveTo(左上) → 左竖 → 左下弧 → 下横 → 右下弧 → LineTo(右上)。
     * 首尾都在 y = 0 但**不连线**，这就是上沿的开口。
     */
    val commands: List<EdgeCommand>
        get() {
            val out = mutableListOf<EdgeCommand>(EdgeCommand.MoveTo(left, 0f))
            if (radius > 0f) {
                out += EdgeCommand.LineTo(left, bottom - radius)
                out += EdgeCommand.ArcTo(
                    left = left,
                    top = bottom - radius,
                    right = left + radius,
                    bottom = bottom,
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                )
                out += EdgeCommand.LineTo(right - radius, bottom)
                out += EdgeCommand.ArcTo(
                    left = right - radius,
                    top = bottom - radius,
                    right = right,
                    bottom = bottom,
                    startAngleDegrees = 270f,
                    sweepAngleDegrees = 90f,
                )
            } else {
                out += EdgeCommand.LineTo(left, bottom)
                out += EdgeCommand.LineTo(right, bottom)
            }
            out += EdgeCommand.LineTo(right, 0f)
            return out
        }

    fun buildPath(): Path = Path().apply {
        commands.forEach { command ->
            when (command) {
                is EdgeCommand.MoveTo -> moveTo(command.x, command.y)
                is EdgeCommand.LineTo -> lineTo(command.x, command.y)
                is EdgeCommand.ArcTo -> arcTo(
                    rect = Rect(
                        left = command.left,
                        top = command.top,
                        right = command.right,
                        bottom = command.bottom,
                    ),
                    startAngleDegrees = command.startAngleDegrees,
                    sweepAngleDegrees = command.sweepAngleDegrees,
                    forceMoveTo = false,
                )
            }
        }
    }
}

/**
 * 一条描边绘制命令。纯数据，不依赖 `Path`，因此可以在 JVM 单测里逐条断言。
 */
internal sealed interface EdgeCommand {
    /** 起笔（抬笔移动到某点，不画线）。 */
    data class MoveTo(val x: Float, val y: Float) : EdgeCommand

    /** 画直线到某点。 */
    data class LineTo(val x: Float, val y: Float) : EdgeCommand

    /** 画圆弧（矩形为椭圆的包围盒）。 */
    data class ArcTo(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val startAngleDegrees: Float,
        val sweepAngleDegrees: Float,
    ) : EdgeCommand
}

/**
 * 算出 [OutlineEdgePlan]。纯算术，不碰任何 Android 类。
 *
 * 路径按半宽内缩，避免 1dp 描边有一半画到节点外面被裁掉。
 */
internal fun deviceBarEdgePlan(
    width: Float,
    height: Float,
    radius: Float,
    strokeWidth: Float,
): OutlineEdgePlan {
    val inset = strokeWidth / 2f
    val left = inset
    val right = (width - inset).coerceAtLeast(left)
    val bottom = (height - inset).coerceAtLeast(0f)
    val maxRadius = minOf(right - left, bottom) / 2f
    return OutlineEdgePlan(
        left = left,
        right = right,
        bottom = bottom,
        radius = radius.coerceIn(0f, maxRadius.coerceAtLeast(0f)),
    )
}

/**
 * 把 [deviceBarEdgePlan] 画到当前节点上，颜色取 `outlineVariant`。
 *
 * 用 `drawWithContent` 而不是 `drawBehind`：[Surface] 的背景是它**自己**画的，
 * 而 `modifier` 挂在 Surface 节点链的最前端——`drawBehind` 会先画、随后被背景
 * 整片盖掉（第一版就是这么失效的）。`drawWithContent` 先画完内容再描边，
 * 1dp 发丝线叠在最上层正是想要的。
 */
@Composable
private fun Modifier.drawDeviceBarEdges(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    return drawWithContent {
        drawContent()
        val strokeWidth = with(density) { DeviceBarEdgeWidth.toPx() }
        val radius = with(density) { DeviceBarCornerRadius.toPx() }
        drawPath(
            path = deviceBarEdgePlan(size.width, size.height, radius, strokeWidth).buildPath(),
            color = color,
            style = Stroke(width = strokeWidth),
        )
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
    /**
     * 渲染「⋯」按钮，并把菜单作为**它的兄弟节点**塞进同一个 [Box]（B006）。
     *
     * 为什么让调用方来画这个按钮：`DropdownMenu` 用的是 `Popup`，位置由它自己的父布局
     * 节点决定。早先菜单是 [DeviceRow] 之后的兄弟节点、直接挂在设备栏那个 `Column` 里，
     * 于是锚点落在整栏布局流上——点哪一行的「⋯」都从卡片底部弹出来（用户 m05374 第 3 条）。
     * 菜单必须与按钮共享父节点才能贴着按钮弹，而按钮在行内、菜单不该进行的点击区，
     * 所以由这里画出 `Box { 按钮; 菜单 }`，把「位置」这件事一次定死。
     */
    menuButton: @Composable () -> Unit,
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
        // 「⋯」按钮 + 它自己的菜单，由调用方塞进这个槽；两者必须是同一个 Box 的兄弟（B006）。
        menuButton()
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
