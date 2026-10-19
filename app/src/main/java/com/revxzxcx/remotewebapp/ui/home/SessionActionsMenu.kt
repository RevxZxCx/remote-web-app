package com.revxzxcx.remotewebapp.ui.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.ui.chat.ChevronDownIcon
import com.revxzxcx.remotewebapp.ui.theme.bubbleShape

/** 菜单圆角：原型 `.menu{border-radius:14px}`。 */
private val MenuShape = RoundedCornerShape(14.dp)

/** 菜单项圆角：原型 `.mitem{border-radius:8px}`。 */
private val MenuRowShape = RoundedCornerShape(8.dp)

/**
 * 会话行的长按菜单：移入归档 / 移出归档 + 置顶 / 取消置顶。
 *
 * **为什么没有「删除」**：服务端没有删除会话的 API。已把 SDK dump（`F:\bot\_sdk`）
 * 全量 grep 过 `deleteSession` / `session\/delete` / `removeSession`，零命中；
 * session-controller 的 21 个 invocation 里与「移除」相关的只有 archive/unarchive。
 * 桌面端 Web UI 提供的同样是「归档」。所以这里也只做归档，文案不说「删除」——
 * 承诺一个做不到的操作比少一个功能更糟（需求文档 Q14 同类的诚实原则）。
 *
 * 菜单用 `DropdownMenu` 而不是 `ModalBottomSheet`：行在 `LazyColumn` 里，
 * 底部弹窗会盖住整列、丢失「我在操作哪一行」的上下文；下拉菜单天然锚定在被按的那一行上。
 *
 * 外形按原型（`docs/R033-效果图.html` s1）逐项对齐：
 * - 容器：14dp 圆角 + 1dp `outline` 描边 + 12dp 投影 + **5dp 内边距**（菜单项自己带圆角，
 *   需要一圈底让圆角看得出来；不加这圈的话 40dp 行贴到边上，圆角等于没有）。
 * - 上下文头：`8/10/7` 三段内边距 + 底部细分割线。
 * - 菜单项：**40dp 高、8dp 圆角、图标与文字间距 9dp、文字 12.5sp**。
 *   这里不用 `DropdownMenuItem`：它的水波纹是矩形（不带圆角），而且没有 `shape` 参数，
 *   40dp 行贴边时波纹会溢出菜单的 14dp 圆角。自己用 `clip + clickable` 才能让涟漪
 *   正好落在 8dp 圆角里。
 *
 * R033 起的三处语义改动：
 * 1. **上下文头**（[title] + [subtitle]）：菜单浮在列表上方时，用户仍然要能确认自己在
 *    操作哪一条会话。之前只有两行纯文字，长按后菜单盖住了被按的那一行，等于没有上下文。
 * 2. **每项带图标**：纯文字菜单要逐字读完才知道两个选项是什么。
 * 3. **移入归档走二次确认**：归档会让会话从列表里消失（[ArchiveConfirmDialog]），
 *    而这个动作在 App 里没有「撤销」，只有「去已归档里找回」这一条回收路径——
 *    一次误触就少一条可见的会话。置顶/取消置顶可逆且无破坏性，不打扰用户。
 */
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    archived: Boolean,
    pinned: Boolean,
    busy: Boolean,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    /** 该会话所在工作区已有的归档条数，用于确认框里「已归档 N 条」。 */
    archivedInWorkspace: Int? = null,
) {
    // 二次确认：点「移入归档」时先 `onDismiss()` 收起菜单，再弹 `AlertDialog`。
    // 两个 Popup 重叠时层级由最后请求者决定，先收再弹是唯一不依赖实现细节的顺序。
    //
    // 用 `remember` 而不是 `rememberSaveable`：`menuOpen` 是 saveable（旋屏后菜单要留在
    // 原地），但确认框不该在重建后自己冒出来——那会把「用户按到一半」变成「App 替我
    // 决定要归档」。两者生命周期不一致是刻意的。
    var confirmingArchive by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 200.dp, max = 260.dp),
        shape = MenuShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(5.dp)) {
            if (title != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 7.dp),
                ) {
                    Text(
                        text = title,
                        // 12.5sp：原型的 `.m1{font-size:12.5px}`。用 sp 而不是 bodyMedium(14sp)，
                        // 是因为这块是「我在操作哪一条」的旁注，不该与列表行标题同权重。
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            MenuRow(
                icon = ArchiveIcon,
                // 「移入 / 移出」而不是「归档 / 取消归档」：移入表达的是搬走，不是销毁。
                label = if (archived) "移出归档" else "移入归档",
                enabled = !busy,
                onClick = {
                    if (archived) {
                        onDismiss()
                        onUnarchive()
                    } else {
                        onDismiss()
                        confirmingArchive = true
                    }
                },
            )
            MenuRow(
                icon = PinIcon,
                label = if (pinned) "取消置顶" else "置顶",
                enabled = !busy,
                onClick = {
                    onDismiss()
                    if (pinned) onUnpin() else onPin()
                },
            )
        }
    }

    if (confirmingArchive) {
        ArchiveConfirmDialog(
            title = title,
            subtitle = subtitle,
            archivedCount = archivedInWorkspace,
            onDismiss = { confirmingArchive = false },
            onConfirm = {
                confirmingArchive = false
                onArchive()
            },
        )
    }
}

/**
 * 菜单里的一行：18dp 图标 + 12.5sp 文字，40dp 高、8dp 圆角。
 *
 * `clickable(enabled = false)` 会挂上 `Disabled` 语义，`assertIsNotEnabled()` 照常生效
 * （R032 的教训：`clickable(enabled = false)` 仍保留 `OnClick` 动作，判「被禁用」必须用
 * `assertIsNotEnabled`，不能用 `assertHasClickAction`）。
 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp)
            .height(40.dp)
            .clip(MenuRowShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                // 禁用态不能只靠「点了没反应」表达：菜单项要明说自己现在不可用。
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

/**
 * 「移入归档？」的二次确认（原型 s1 下半）。
 *
 * 四块内容缺一不可：
 * 1. **标题带文件夹图标**——先把「这一次做的是归档」和「不是删除」区分开；
 * 2. **会话上下文卡**（会话名 + 工作区 · 时间 · 已归档 N 条）——确认框盖住了整页，
 *    没有这张卡用户无法确认自己在归档哪一条；
 * 3. **正文说清去哪了**：「从「最近」移到归档」+ 加粗的「可以在抽屉底部的「已归档」里
 *    找回」，把回收路径写在动作旁边，而不是等用户事后去找；
 * 4. **why 提示**（三级色）：「App 没有删除会话的权限，归档是唯一可逆的『移走』方式」。
 *    这一条是整套设计里唯一说明「为什么只能这样」的地方，删了它就只剩一个没有依据的
 *    流程，用户会继续找删除按钮。
 *
 * 为什么不用 `Snackbar` 的「撤销」：归档要发一次 `workspace/archiveSession`，
 * 而且是**按工作区批量**语义的动作（见 `SessionRepository` 的 KDoc），撤销要再发一次
 * 反向请求；网络失败时「撤销」本身也会失败，反而制造第二个不确定状态。
 * 事前确认一次，比事后承诺一个可能失败的撤销更诚实。
 */
@Composable
internal fun ArchiveConfirmDialog(
    title: String?,
    subtitle: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    archivedCount: Int? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 原型 `.dlg{border-radius:28px}`（= AppShapes.extraLarge）；M3 默认是 28dp，
        // 这里显式写出来是为了避免主题日后调整时悄悄跑偏。
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ArchiveIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "移入归档？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                if (title != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 消息气泡：与「会话」这个对象呼应，比再放一个归档图标准确
                            // （归档图标已经出现在标题里了）。
                            Icon(
                                imageVector = ConversationBubbleIcon,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val context = listOfNotNull(
                                    subtitle,
                                    archivedCount?.let { "已归档 $it 条" },
                                ).joinToString(" · ")
                                if (context.isNotBlank()) {
                                    Text(
                                        text = context,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                }
                // 用 `buildAnnotatedString` 而不是两段 Text：这句话是一个整句，中间那段
                // 加粗用的是 `onSurface`（比正文亮一档）——拆成两个 Text 会丢失行内混排，
                // 换行位置也会变得生硬。
                Text(
                    text = archiveDialogBody(),
                    fontSize = 12.5.sp,
                    lineHeight = 21.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = WarningTriangleIcon,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 1.dp).size(15.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "App 没有删除会话的权限，归档是唯一可逆的「移走」方式。",
                        fontSize = 11.sp,
                        lineHeight = 17.6.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            // 按钮文案与菜单项同名是**刻意**的：本条只允许在菜单收起之后弹（见上面的
            // `onDismiss(); confirmingArchive = true`），此时语义树里只有这一个「移入归档」，
            // 不存在 R033 首跑那次「一个文案命中两个节点、点击注入失败」的情况。
            // 更重要的是：动作名必须与用户刚点的那个词一致，写成「确认移入」等于让用户
            // 再翻译一次。
            TextButton(onClick = onConfirm) { Text("移入归档") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 确认框的正文，中间一段加粗。
 *
 * 单独抽出来是为了能被 `buildAnnotatedString` 的调用点与渲染层测试同时引用——
 * 文案里「可以在抽屉底部的「已归档」里找回」这一段加粗是设计的一部分（它标记了
 * 唯一的回收路径），散落在组合代码里容易被后续改动悄悄抹平。
 */
private fun archiveDialogBody() = androidx.compose.ui.text.buildAnnotatedString {
    append("它会从「最近」里移到归档，")
    pushStyle(
        androidx.compose.ui.text.SpanStyle(
            fontWeight = FontWeight.Medium,
            color = Color.Unspecified,
        ),
    )
    append("之后可以在抽屉底部的「已归档」里找回")
    pop()
    append("。桌面端与其它设备的列表会同步这一变化。")
}

/**
 * 抽屉底部的「已归档 N」入口行。
 *
 * 为什么需要它：归档会话在主区 `WorkspaceRow` 里仍有一段，但抽屉（跨工作区的全局视图）
 * 完全看不到它们——归档在 App 里等于「消失」。这一行给出唯一的回收入口。
 */
@Composable
internal fun DrawerArchivedEntry(
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用 `Surface(onClick)` 而不是 `Modifier.clickable`：前者会挂 Material 的
    // `minimumInteractiveComponentSize`（48dp 保底）并带 `Role.Button`，后者两样都没有。
    // 抽屉里这是唯一的归档入口，还长在列表末尾，触达面积不该比旁边的会话行更小。
    // 顺带修掉涟漪层级：`clickable` 若排在 `clip` 之后、`Surface` 的 background 之前，
    // 涟漪会画在底色下层（几乎看不见）。
    Surface(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = bubbleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ArchiveIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已归档 $count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = ChevronDownIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 确认框上下文卡里的消息气泡（原型 `.dcard` 左侧那个图标）。 */
private val ConversationBubbleIcon: ImageVector = ImageVector.Builder(
    name = "ConversationBubble",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(21f, 15f)
        curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
        horizontalLineTo(7f)
        lineTo(3f, 21f)
        verticalLineTo(5f)
        curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
        horizontalLineTo(19f)
        curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        close()
    }
}.build()

/** 感叹号三角（原型 `.dhint` 左侧的 why 图标）。 */
private val WarningTriangleIcon: ImageVector = ImageVector.Builder(
    name = "WarningTriangle",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12f, 9f); verticalLineTo(13f)
        moveTo(12f, 17f); lineTo(12.01f, 17f)
        moveTo(10.3f, 3.9f)
        lineTo(1.8f, 18f)
        curveTo(1.3f, 18.9f, 1.9f, 20f, 3f, 20f)
        horizontalLineTo(21f)
        curveTo(22.1f, 20f, 22.7f, 18.9f, 22.2f, 18f)
        lineTo(13.7f, 3.9f)
        curveTo(13.1f, 2.9f, 10.9f, 2.9f, 10.3f, 3.9f)
        close()
    }
}.build()
