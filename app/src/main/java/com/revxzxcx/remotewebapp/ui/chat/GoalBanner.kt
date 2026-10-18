package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.session.Goal
import com.revxzxcx.remotewebapp.core.session.GoalPhase
import com.revxzxcx.remotewebapp.core.session.TodoItem
import com.revxzxcx.remotewebapp.core.session.TodoList
import com.revxzxcx.remotewebapp.core.session.TodoStatus
import com.revxzxcx.remotewebapp.ui.theme.LocalStatusRunning
import com.revxzxcx.remotewebapp.ui.theme.Motion
import com.revxzxcx.remotewebapp.ui.theme.groupShape

/** 目标横幅整条的测试标签。 */
internal const val GoalBannerTestTag = "chat-goal-banner"

/** 顶栏「目标已收起」圆点的测试标签。 */
internal const val GoalChipTestTag = "chat-goal-chip"

/** 任务胶囊的测试标签。 */
internal const val TaskPillTestTag = "chat-task-pill"

/** 任务列表面板的测试标签。 */
internal const val TaskPanelTestTag = "chat-task-panel"

/**
 * 会话目标横幅（用户 m45580 第 2 条：顶部圆角横幅 + 右侧暂停/编辑/删除三个纯图标按钮）。
 *
 * ## 状态色（三态）
 * - **进行中** → `primary`（蓝）：整条淡呼吸，提示「这个会话正在为一个目标跑」。
 * - **已暂停** → `tertiary`（琥珀）：停止动画。
 * - **受阻** → `error`：停止动画，tooltip 显示 `blockedReason`。
 *
 * ## 淡呼吸只动两样东西
 * 阴影外扩 0→2dp 与左侧竖线透明度 .35↔.95，周期 2600ms。**不动背景色**——整块闪会
 * 干扰读正文，而用户看这一屏的目的是读对话。
 *
 * ## 暂停/开始是同一个按钮的两态
 * [Goal.canPause] 决定显示「暂停」还是「开始」。这是 DSH 的 `showResume` 语义：
 * `phase === "paused"` 或 `phase === "active" && activation === "disarmed"` 时给恢复。
 * 本地拿不到 `activation`（进程本地状态），所以只在 paused/blocked 时给「开始」。
 *
 * ## 目标与任务**没有**联动
 * 暂停只影响「下一轮要不要自动继续」，**不中止当前轮、不冻结任务**（见 [Goal] 的 KDoc）。
 * 所以本组件**不接收** [TodoList]，避免任何「顺手联动」的诱惑。
 *
 * @param collapsed 是否已收起到顶栏。默认 false（展开），**不做持久化**——用户要求
 *   「重新打开对话依旧为展开状态」。
 * @param onToggleCollapsed 点横幅本体 = 收起。
 * @param onPauseResume 暂停 / 开始。
 * @param onEdit 保存编辑后的 objective。
 * @param onDelete 删除（调用方负责弹二次确认）。
 */
@Composable
internal fun GoalBanner(
    goal: Goal,
    collapsed: Boolean,
    editing: Boolean,
    onToggleCollapsed: () -> Unit,
    onPauseResume: () -> Unit,
    onRequestEdit: () -> Unit,
    onEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!goal.visible) return
    val accent = when (goal.phase) {
        GoalPhase.ACTIVE -> MaterialTheme.colorScheme.primary
        GoalPhase.PAUSED -> MaterialTheme.colorScheme.tertiary
        GoalPhase.BLOCKED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 淡呼吸：只在进行中跑。`rememberInfiniteTransition` 在动画关闭时也不会跑
    // （Compose 的 animateFloat 会跟随系统「移除动画」设置）。
    val pulse = if (goal.phase == GoalPhase.ACTIVE) {
        val transition = rememberInfiniteTransition(label = "goalPulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2600, easing = LinearEasing),
                // 用 Reverse 得到 0→1→0 的呼吸，而不是重头再来一次（会看到跳变）。
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "goalPulseValue",
        ).value
    } else {
        0f
    }

    AnimatedVisibility(
        visible = !collapsed,
        enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
    ) {
        val barAlpha = 0.35f + 0.6f * pulse
        val ringAlpha = 0.16f * pulse
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag(GoalBannerTestTag)
                // 淡呼吸的「外扩光」：画在背景之后，不占布局。
                .then(
                    if (goal.phase == GoalPhase.ACTIVE) {
                        Modifier.background(
                            color = accent.copy(alpha = ringAlpha),
                            shape = groupShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(groupShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, groupShape)
                .clickable(enabled = !editing, onClick = onToggleCollapsed)
                .padding(start = 0.dp, top = 9.dp, bottom = 9.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // 左侧 2dp 状态竖线（进行中时明暗呼吸）。
            Box(
                Modifier
                    .width(2.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accent.copy(alpha = if (goal.phase == GoalPhase.ACTIVE) barAlpha else 0.55f)),
            )

            // 目标图标（圆环靶心）。
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = GoalIcon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
            }

            if (editing) {
                // 行内编辑：单行输入 + ✓ / ×（用户 m45632：与排队编辑同一套交互）。
                GoalInlineEditor(
                    initial = goal.objective,
                    onSave = onEdit,
                    onCancel = onCancelEdit,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhaseDot(accent)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = goal.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        goal.maxGoalRounds?.let {
                            Text(
                                text = " · ${it} 轮上限",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Text(
                        text = goal.objective,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }

                // 右侧三个纯图标按钮，各 32dp 触控圆，合计 96dp。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BannerIconButton(
                        icon = if (goal.canPause) PauseIcon else Icons.Default.PlayArrow,
                        description = if (goal.canPause) "暂停目标" else "开始目标",
                        tint = if (goal.canPause) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        onClick = onPauseResume,
                    )
                    BannerIconButton(
                        icon = Icons.Default.Edit,
                        description = "编辑目标",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onRequestEdit,
                    )
                    BannerIconButton(
                        icon = Icons.Default.Delete,
                        description = "删除目标",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

/** 阶段色圆点（5dp）。 */
@Composable
private fun PhaseDot(color: Color) {
    Box(
        Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * 横幅右侧的一个纯图标按钮。
 *
 * 32dp 触控圆（图标 18dp + 四周留白），三个并排 96dp。不用 `IconButton`
 * （默认 48dp，三个就吃掉 144dp，把 objective 挤成两三个字）。
 */
@Composable
private fun BannerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 目标横幅的行内编辑器（单行输入 + ✓ / ×）。
 *
 * 用户 m45632 明确要求「目标编辑也采用这样的方式」（指排队那条：**内容限制在一行**、
 * 可左右滑动、编辑时只显示 ✓ 与 ×）。
 *
 * 因此这里用 `BasicTextField`（单行）+ `horizontalScroll` 让超长目标可以左右滑动，
 * **不换行**——换行会把横幅从 44dp 撑到三行，进而挤压消息区。
 *
 * 保存校验：空目标不允许保存（DSH 的 `handleEdit` 同样是 `trimmed === ""` 就 return）。
 */
@Composable
private fun GoalInlineEditor(
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val scroll = rememberScrollState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp)
                // 单行 + 横向滚动：内容超长时左右滑动，不换行。
                .horizontalScroll(scroll)
                .testTag(GoalEditFieldTestTag),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
        BannerIconButton(
            icon = Icons.Default.Check,
            description = "保存目标",
            tint = MaterialTheme.colorScheme.primary,
            onClick = { if (text.isNotBlank()) onSave(text.trim()) },
        )
        BannerIconButton(
            icon = Icons.Default.Clear,
            description = "取消编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onCancel,
        )
    }
}

/** 目标行内编辑输入框的测试标签。 */
internal const val GoalEditFieldTestTag = "chat-goal-edit-field"

/**
 * 顶栏的目标圆点（横幅收起后的入口）。
 *
 * 点它展开横幅。颜色跟随目标相位；进行中时外圈呼吸（表示「正在为一个目标跑」），
 * 暂停/受阻时静止。
 */
@Composable
internal fun GoalChip(
    goal: Goal,
    onClick: () -> Unit,
) {
    if (!goal.visible) return
    val accent = when (goal.phase) {
        GoalPhase.ACTIVE -> MaterialTheme.colorScheme.primary
        GoalPhase.PAUSED -> MaterialTheme.colorScheme.tertiary
        GoalPhase.BLOCKED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .testTag(GoalChipTestTag)
            .semantics { contentDescription = "${goal.label}（点击展开）" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (goal.phase == GoalPhase.ACTIVE) {
            val transition = rememberInfiniteTransition(label = "goalChipPulse")
            val p by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2600, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "goalChipAlpha",
            )
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer { alpha = p }
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
            )
        }
        Icon(
            imageVector = GoalIcon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 任务胶囊（用户 m45580 第 2 条）。
 *
 * - 有任务才出现，**没有任务时完全不占高度**（[TodoList.visible]）。
 * - 进行中：左侧旋转圈 + `n/m`（n 用 running 色高亮）。
 * - 全部完成：换成绿勾并**停止动画**（用户 m45632 第 2 条：保留绿勾，不自动消失）。
 * - 点击展开任务列表；与排队列表**互斥**（由调用方保证，见 ChatScreen）。
 *
 * ⚠️ 胶囊状态**只跟 todos**，不跟 goal。目标暂停时若仍有 in_progress 任务，胶囊照样转。
 */
@Composable
internal fun TaskPill(
    todos: TodoList,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!todos.visible) return
    val running = todos.running
    val allDone = todos.allDone
    val accent = when {
        allDone -> LocalStatusRunning.current
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .testTag(TaskPillTestTag)
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (allDone) {
                    LocalStatusRunning.current.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.outline
                },
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onToggle)
            .padding(start = 11.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TaskMarker(running = running, allDone = allDone, accent = accent)
        Text(
            text = when {
                allDone -> "任务全部完成"
                running -> "任务进行中"
                else -> "任务待处理"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (allDone) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            // n/m：n = 已完成，m = 总数。已完成用 running 色，让人一眼看到进度。
            text = todos.counter,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (allDone || running) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) ChevronUpIcon else ChevronDownIcon,
            contentDescription = if (expanded) "收起任务列表" else "展开任务列表",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * 胶囊左侧的状态标记。
 *
 * - 全部完成 → 实心绿圆 + 勾
 * - 有进行中 → 旋转圈（`primary`）
 * - 只有待办 → 空心灰圈
 *
 * 旋转用 `graphicsLayer { rotationZ }` 而不是 `rememberInfiniteTransition` 驱动的
 * 可组合状态：后者每帧重组整条胶囊，而这里只有一圈在动。实测 60fps 下重组开销不可忽略。
 */
@Composable
private fun TaskMarker(running: Boolean, allDone: Boolean, accent: Color) {
    if (allDone) {
        Box(
            Modifier
                .size(13.dp)
                .clip(CircleShape)
                .background(LocalStatusRunning.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(9.dp),
            )
        }
    } else if (running) {
        ArcSpinner(size = 13.dp, stroke = 1.8.dp, color = accent)
    } else {
        Box(
            Modifier
                .size(13.dp)
                .clip(CircleShape)
                .border(1.6.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/**
 * 「进行中」的缺口弧旋转圈。
 *
 * ## 为什么不能用 `border + rotationZ`
 * 首版是两层同心 `.border(1.8.dp, …, CircleShape)` 叠一个 `rotationZ`，注释还写着
 * 「半圆 border + 旋转得到缺口圈观感」——**实际代码画的是整圆**，旋转一个旋转对称的
 * 图形在视觉上等于静止（R031 独立审查 P2）。`border` 没有任何办法只画一段弧。
 *
 * 现在用 [Canvas] 的 `drawArc`：先铺一整圈 0.28 alpha 的底环，再在上面画一段 90°
 * 的实心弧（`useCenter = false` + 圆头笔帽），最后靠 `rotationZ` 让它转起来。这才是
 * 原型里 `.spin { border-top-color: … }` 的那个观感。
 *
 * @param size 直径。胶囊里 13dp、任务行里 15dp，与相邻的完成勾 / 待办圈对齐。
 * @param stroke 环宽（dp）。
 * @param color 主色。调用方传 primary 或 [LocalStatusRunning]。
 */
@Composable
private fun ArcSpinner(
    size: Dp,
    stroke: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "arcSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = LinearEasing),
        ),
        label = "arcSpinAngle",
    )
    Canvas(
        Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle },
    ) {
        val w = stroke.toPx()
        // 收缩半径一个笔宽的一半：`drawArc` 的描边以弧线为中心向两侧扩展，不减会把
        // 外沿画出画布裁掉。
        val inset = w / 2f
        val arcSize = Size(this.size.width - w, this.size.height - w)
        // 底环：让用户看出这是个完整的圈，只是缺了一大段。
        drawArc(
            color = color.copy(alpha = 0.28f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        // 高亮段：90° 的弧 + 圆头笔帽。
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

/**
 * 任务列表面板（点胶囊展开）。
 *
 * 行标记三态与 DSH 的 `StateDot`（idle / ongoing / done）同构：
 * - `completed` → 实心绿 + 勾，文字加删除线并压暗
 * - `in_progress` → 旋转圈（primary），文字加粗
 * - `pending` → 空心灰圈
 *
 * 限高 [TASK_PANEL_MAX_HEIGHT]（约 7 条）后内部滚动——用户 m45580 注意事项 1。
 */
@Composable
internal fun TaskPanel(
    todos: TodoList,
    modifier: Modifier = Modifier,
) {
    if (!todos.visible) return
    Column(
        modifier = modifier
            .testTag(TaskPanelTestTag)
            .fillMaxWidth()
            .clip(groupShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, groupShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "任务列表",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${todos.done} / ${todos.total} 已完成",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = TASK_PANEL_MAX_HEIGHT),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            // key 用 content：DSH 的 `todos.map(... key: item.content)` 同口径。
            items(todos.items, key = { it.content }) { item ->
                TaskRow(item)
            }
        }
    }
}

/** 任务面板的限高。约 7 条（每条 26dp + 内边距）。 */
internal val TASK_PANEL_MAX_HEIGHT = 196.dp

@Composable
private fun TaskRow(item: TodoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.5.dp)
                .size(15.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (item.status) {
                TodoStatus.COMPLETED -> Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(LocalStatusRunning.current),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(10.dp),
                    )
                }

                TodoStatus.IN_PROGRESS -> ArcSpinner(
                    size = 15.dp,
                    stroke = 1.6.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                TodoStatus.PENDING -> Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .border(1.6.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Text(
            text = item.content,
            // 三态的排版差异是原型明确要求的（`docs/R031-效果图.html` 第 857-858 行）：
            // 完成 → 删除线 + 压暗；进行中 → 加粗。只有颜色差异时，密排的 7 条任务
            // 一眼看不出哪条在跑、哪些已经完事（R031 独立审查 P2）。
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (item.status == TodoStatus.IN_PROGRESS) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                textDecoration = if (item.status == TodoStatus.COMPLETED) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
            ),
            color = when (item.status) {
                TodoStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                TodoStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (item.status) {
                TodoStatus.COMPLETED -> "完成"
                TodoStatus.IN_PROGRESS -> "进行中"
                TodoStatus.PENDING -> "待办"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = when (item.status) {
                TodoStatus.COMPLETED -> LocalStatusRunning.current
                TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                TodoStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 删除目标的二次确认对话框（用户 m45632 第 1 条明确要求）。
 *
 * 文案说明「进度会清空，任务列表不受影响」——后者是真实语义（目标与任务独立），
 * 不写清楚用户会以为删目标会连任务一起删。
 *
 * 🔴 重点词用 [buildAnnotatedString] 加粗，**不要把 `**` 写进字符串**：`Text` 不解析
 * Markdown，真机上会原样显示「目标与它的进度会被清空，\*\*任务列表不受影响\*\*。」
 * （R031 设备验收抓到的真实缺陷，单测只断言「删除目标？」标题所以没抓到）。
 */
@Composable
internal fun DeleteGoalDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除目标？") },
        text = {
            Text(
                buildAnnotatedString {
                    append("目标与它的进度会被清空，")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append("任务列表不受影响")
                    }
                    append("。")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
