package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.AskQuestion
import com.revxzxcx.remotewebapp.core.session.InteractiveComposerRequest
import com.revxzxcx.remotewebapp.core.events.SessionPermission

/**
 * 输入框位置上的「待回答」窗口（用户 m29539，与 DSH 桌面端 `dsh-client-ui-user-questions`
 * 的行为对齐：**卡片暂时占用编辑器位置**）。
 *
 * 三个形态共用同一个外壳 [ComposerShell]，只有内容区不同：
 * 1. `Approval` —— 敏感工具的权限审批，允许一次 / 拒绝两个动作；
 * 2. `Question` —— 模型提问，选项 + 自定义答案 + 提交/跳过；
 * 3. `plan-review` —— 走 [Question]，但 UI 换成紧凑审批卡片（跳过 / 同意执行）。
 *
 * 为什么不并排堆两个卡片：桌面端同一时刻也只有一个卡片占编辑器。两个一起来时
 * **审批优先**（它卡住的是正在执行的工具，漏答会让整轮停在这里；提问等下一个回合）。
 * 分派点在 `ChatViewModel.interactive`，这里是结果不是规则。
 */
@Composable
internal fun InteractiveWindow(
    request: InteractiveComposerRequest?,
    error: String?,
    onAnswerApproval: (String, Boolean) -> Unit,
    onToggleOption: (String, AskQuestion, String) -> Unit,
    onCustomAnswer: (String, AskQuestion, String) -> Unit,
    onSubmit: (String) -> Unit,
    onSkip: (String) -> Unit,
) {
    when (request) {
        null -> ComposerShell(title = request.headline(), accent = request.accent(), actions = {}) {}

        is InteractiveComposerRequest.Approval -> ComposerShell(
            title = request.headline(),
            accent = request.accent(),
            actions = {
                ApprovalActions(
                    onAllow = { onAnswerApproval(request.eventId, true) },
                    onReject = { onAnswerApproval(request.eventId, false) },
                )
            },
        ) {
            ApprovalBody(request = request, error = error)
        }

        is InteractiveComposerRequest.Question -> ComposerShell(
            title = request.headline(),
            accent = request.accent(),
            bodyMaxHeight = INTERACTIVE_QUESTION_BODY_MAX_HEIGHT,
            actions = {
                QuestionActions(
                    request = request,
                    onSubmit = { onSubmit(request.callId) },
                    onSkip = { onSkip(request.callId) },
                )
            },
            pinned = {
                QuestionPinned(
                    request = request,
                    error = error,
                    onCustomAnswer = onCustomAnswer,
                )
            },
        ) {
            QuestionBody(
                request = request,
                onToggleOption = onToggleOption,
            )
        }
    }
}

/**
 * 输入区外壳：与 [Composer] 同一个视觉容器（实底 + 1dp 描边 + 大圆角）。
 *
 * 内容区必须能滚、动作区必须钉住：
 * - 提问卡的高度不固定（多选项 + 长 detail + 三行自定义输入），而它渲染在 Scaffold 的
 *   `bottomBar` 里，`bottomBar` 自己不滚动，超高时会被 `Surface` 裁掉——用户拿不到
 *   「提交」按钮（R022 P1-2 修过一次，当时只包了滚动，按钮仍然在滚动区里）。
 * - 所以内容包 `verticalScroll + heightIn(max=…)`（与 `ModelPickerDialog` 同一套），
 *   **不用 LazyColumn**：卡里的选项数量是个位数，`forEach` 足够，而 LazyColumn 在
 *   `bottomBar` 里会被测量成 0 高。
 * - [actions] 渲染在滚动区**外面**，永远可见。把「提交」放进滚动区等于让用户先滚动再
 *   能确认，桌面端也从不这么干。
 * - [pinned] 渲染在滚动区与动作区**之间**，同样不随内容滚动。给「其他答案」输入框用：
 *   选项多的时候滚动区会被选项占满，自定义答案一旦跟着滚，用户就得先发现「这里能滚」
 *   再滚下去才写得成字——桌面上有鼠标滚轮和视觉渐隐，手机上两样都没有。
 *
 * `INTERACTIVE_BODY_MAX_HEIGHT` 取 220dp 是有意的：卡片总高 = 12 + 36 + 6 + 220 + 8 +
 * 52 + 12 ≈ 346dp，必须留在小窗口（渲染测试的 Robolectric 窗口只有 386px 高）之内，
 * 否则连钉住的动作区都会被窗口裁掉。真机上 930dp 高的屏也远够用。
 *
 * 提问卡多一段钉住的「其他答案」（约 56dp），所以它传更小的 [bodyMaxHeight]：
 * 12 + 36 + 6 + 160 + 56 + 8 + 52 + 12 ≈ 342dp，同样留在 386px 内。
 */
@Composable
private fun ComposerShell(
    title: String,
    accent: Color,
    actions: @Composable () -> Unit,
    bodyMaxHeight: Dp = INTERACTIVE_BODY_MAX_HEIGHT,
    pinned: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            pinned()
            Spacer(Modifier.height(8.dp))
            actions()
        }
    }
}

/** 交互窗口正文的滚动高度上限：再高就改为滚动，避免把消息列表挤干净。 */
private val INTERACTIVE_BODY_MAX_HEIGHT = 220.dp

/**
 * 提问卡的滚动高度上限：比 [INTERACTIVE_BODY_MAX_HEIGHT] 小，给钉住的「其他答案」留地方。
 *
 * 两个值都必须让「卡片总高 < Robolectric 窗口高（386px）」，否则钉住的动作区会被裁掉，
 * 而 `performClick` 往窗口外注入触摸不抛异常也不触发回调——这条已经害过两轮静默失败。
 */
private val INTERACTIVE_QUESTION_BODY_MAX_HEIGHT = 160.dp

/** 权限审批正文：工具名 + 服务端给的理由。动作区见 [ApprovalActions]。 */
@Composable
private fun ApprovalBody(
    request: InteractiveComposerRequest.Approval,
    error: String?,
) {
    Text(
        text = request.toolName,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (!request.reason.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = request.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "允许后这一步会立刻执行；拒绝后本轮会停在这里。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (error != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 提问正文的上半段（会滚动）：题号 + 题目 + 详情 + 选项。
 *
 * 一次只显示一道题（桌面端同规格）。
 */
@Composable
private fun QuestionBody(
    request: InteractiveComposerRequest.Question,
    onToggleOption: (String, AskQuestion, String) -> Unit,
) {
    val index = request.currentIndex()
    val question = request.questions.getOrNull(index) ?: return
    val draft = request.draftOf(question.id)

    // 多题时告诉用户「这是第几道 / 共几道」：桌面端也是一道一道来，不并排铺开。
    if (request.questions.size > 1) {
        Text(
            text = "第 ${index + 1} / ${request.questions.size} 题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
    Text(
        text = question.question,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    if (!question.detail.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = question.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    question.options.forEach { option ->
        OptionRow(
            label = option.label,
            description = option.description,
            selected = option.label in draft.selected,
            multiSelect = question.multiSelect,
            onClick = { onToggleOption(request.callId, question, option.label) },
        )
    }
    if (question.options.isEmpty()) {
        Text(
            text = "这题没有预设选项，直接在下面写答案。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 提问正文的下半段（钉住不滚）：自定义答案 + 错误。
 *
 * 拆出来的原因见 [ComposerShell] 的 [pinned] 说明：选项多于 4 个时滚动区会被占满，
 * 自定义答案跟着滚就意味着用户必须先发现「这里能滚」。
 */
@Composable
private fun QuestionPinned(
    request: InteractiveComposerRequest.Question,
    error: String?,
    onCustomAnswer: (String, AskQuestion, String) -> Unit,
) {
    val index = request.currentIndex()
    val question = request.questions.getOrNull(index) ?: return
    val draft = request.draftOf(question.id)
    OutlinedTextField(
        value = draft.custom,
        onValueChange = { onCustomAnswer(request.callId, question, it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("其他答案（可留空）") },
        maxLines = 3,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    if (error != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 权限审批动作：允许一次 / 拒绝。渲染在滚动区外，永远可见。 */
@Composable
private fun ApprovalActions(onAllow: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onAllow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("允许一次")
        }
        Button(
            onClick = onReject,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("拒绝")
        }
    }
}

/** 提问动作：跳过 / 提交。渲染在滚动区外，永远可见。 */
@Composable
private fun QuestionActions(
    request: InteractiveComposerRequest.Question,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onSkip, shape = RoundedCornerShape(14.dp)) { Text("跳过") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSubmit, shape = RoundedCornerShape(14.dp)) {
            Text(
                when {
                    request.questions.size > 1 -> "提交整组"
                    // 桌面端规格：计划确认用提问方给的批准标签（可能是「同意执行」之类），
                    // 而不是千篇一律的「提交」。
                    request.questions.first().isPlanReview -> request.questions.first().approveLabel
                    else -> "提交"
                },
            )
        }
    }
}

/** 一个选项。单选/多选都用同一个「已选打勾」的可视语言，靠文案区分。 */
@Composable
private fun OptionRow(
    label: String,
    description: String?,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(1.dp, border, RoundedCornerShape(14.dp))
            // 单选/多选必须告诉读屏用户「这是单选还是多选」，否则 TalkBack 只会念
            // 「可选择」，用户分不清点一下会不会把别的选项顶掉。
            .selectable(
                selected = selected,
                role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(if (multiSelect) RoundedCornerShape(5.dp) else CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                )
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    if (multiSelect) RoundedCornerShape(5.dp) else CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 配置行里的审批权限徽标（只读信息 + 面板入口）。 */
@Composable
internal fun PermissionChip(
    permission: SessionPermission,
    pendingCount: Int,
    onClick: () -> Unit,
) {
    val urgent = pendingCount > 0
    val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (urgent) "待批 $pendingCount" else permission.summary()
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(50),
            )
            .semantics { contentDescription = "审批权限" }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 审批权限面板：只读展示当前策略，并说明为什么手机端改不了。 */
@Composable
internal fun PermissionSheet(
    permission: SessionPermission,
    pendingCount: Int,
    error: String?,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("审批权限") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = permission.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                val mode = permission.sandboxMode ?: permission.preset
                if (mode != null || permission.approvalPolicy != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            mode?.let { append(it.label) }
                            permission.approvalPolicy?.let {
                                if (isNotEmpty()) append(" · ")
                                append(it.label)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (pendingCount > 0) {
                        "现在有 $pendingCount 个请求等你处理，已经占住输入框。"
                    } else {
                        "现在没有等待授权的请求。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "策略只能在桌面端改，手机端只读。改完之后这里会自动跟上" +
                        "（会话日志里会记一条事件）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("知道了") } },
    )
}

// ---- 内部小工具 --------------------------------------------------------------

@Composable
private fun InteractiveComposerRequest?.headline(): String = when (this) {
    is InteractiveComposerRequest.Approval -> "需要授权"
    is InteractiveComposerRequest.Question ->
        if (questions.size == 1 && questions.first().isPlanReview) "计划确认" else "需要你回答"
    null -> "需要你回应"
}

@Composable
private fun InteractiveComposerRequest?.accent(): Color = when (this) {
    is InteractiveComposerRequest.Approval -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
