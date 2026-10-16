package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Lock
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
            hasPinned = true,
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
 * ## 高度上限是**运行时算出来的**，不是常量（用户 m42277 需求 ③）
 *
 * 原来正文上限写死成 220dp（提问卡 160dp），那两个数字的来历是「让卡片总高留在
 * Robolectric 渲染测试的 386px 窗口内」——一个**测试窗口**的尺寸。后果是用户
 * m42277 的原话：「当前的提问回答界面虽然占据了输入面板，但是展开的高度太小了，
 * 如果选项比较多（超过两个），基本上只能通过滚轮看下面的选项了」。
 *
 * 现在改成：正文先按内容自然展开，**超过上限才滚**，而上限由 [interactiveBodyMaxHeight]
 * 从**本组件拿到的可用高度**减去固定开销算出来（见该函数的推导与 [BODY_MIN]/[BODY_MAX]）。
 * 用 `BoxWithConstraints` 取 `maxHeight`，不引入 material3 windowsizeclass，
 * 也**不猜键盘高度**（MuMu + 搜狗 IME 高度恒 0，猜它会让面板在真机上突然被压扁）。
 */
@Composable
private fun ComposerShell(
    title: String,
    accent: Color,
    actions: @Composable () -> Unit,
    /** 是否有钉住区（提问卡的「其他答案」）。算上限时必须把它的高度扣掉。 */
    hasPinned: Boolean = false,
    pinned: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bodyMaxHeight = interactiveBodyMaxHeight(maxHeight, hasPinned)
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
}

/** 正文区下限：至少要能同时看到两三个选项，不然「能滚」这件事本身都发现不了。 */
internal val INTERACTIVE_BODY_MIN_HEIGHT = 120.dp

/**
 * 正文区上限：再高就把消息列表挤没了——面板占住整个输入区，用户还是要看得见上下文。
 * 380dp 在 640dp 以上的屏上等于「六七个选项不用滚」。
 */
internal val INTERACTIVE_BODY_MAX_HEIGHT = 380.dp

/**
 * 外壳里除正文以外的固定开销，用来从可用高度反推正文能占多少。
 *
 * 逐项（与上面的 `Column` 一一对应，改布局必须同步改这里）：
 * 12(上内边距) + 36(标题行) + 6(标题后间距) + [56(钉住区，仅提问卡)] +
 * 8(正文后间距) + 52(动作区，两个 40dp 按钮) + 12(下内边距) = 126dp / 182dp
 *
 * 另外多扣 [SAFE_GAP]：`maxHeight` 是**上限**而不是实际可用高度（Scaffold 的 bottomBar
 * 拿到的是整屏高度），贴着上限算出来的卡片会正好等于窗口高，再有任何 1px 的取整误差
 * 就会把动作区挤出窗口——而 `performClick` 往窗口外注入触摸**不抛异常也不触发回调**，
 * 这条静默失败已经害过两轮（见 `InteractiveWindowGeometryTest`）。
 */
private val SHELL_OVERHEAD_WITHOUT_PINNED = 126.dp
private val SHELL_OVERHEAD_WITH_PINNED = 182.dp
private val SHELL_SAFE_GAP = 12.dp

/**
 * 算出正文区的滚动高度上限（纯函数，便于 JVM 单测钉住）。
 *
 * [availableHeight] 传 `BoxWithConstraints` 的 `maxHeight`。上界/下界见
 * [INTERACTIVE_BODY_MIN_HEIGHT] / [INTERACTIVE_BODY_MAX_HEIGHT]。
 */
internal fun interactiveBodyMaxHeight(availableHeight: Dp, hasPinned: Boolean): Dp {
    val overhead = if (hasPinned) SHELL_OVERHEAD_WITH_PINNED else SHELL_OVERHEAD_WITHOUT_PINNED
    val spare = availableHeight - overhead - SHELL_SAFE_GAP
    return spare.coerceIn(INTERACTIVE_BODY_MIN_HEIGHT, INTERACTIVE_BODY_MAX_HEIGHT)
}

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

/**
 * 底排上的审批权限入口：**纯图标**（用户 m42063 定案）。
 *
 * 原话：「对于排布于下面一排的方法，可以将权限只用图标表示，因为目前也不能修改，展示实际的
 * 文字也没有很大的作用，主要起到一个显示的作用。如果仅展示图标，我认为宽度应该够了」。
 * 于是 32dp 圆钮 + 锁图标，**不再显示 `permission.summary()` 的文字**——那个改动换来的正是
 * 「权限 + 模型 + ＋ + 发送 能回到同一排」（用户 m42248/m42277 要的「操作按钮排布在输入框
 * 的最下面一排」）。**唯一的信息损失**是级别默认不再直接可见，完整策略在点开后见
 * [PermissionSheet]，桌面端也仍是权威来源（D54：手机端只读）。
 *
 * 「待批 N」这个**数量**不能一并砍掉：它是「现在有东西卡住了，去点它」的唯一信号，
 * 而抽屉/横幅都已经收走（R028 ①）。所以改成图标右上角的 error 色角标（与原实现
 * 「urgent 时整枚变 error 色」等价，只是把数字挪到角上腾出宽度）。
 *
 * 语义：`contentDescription = "审批权限"` 必须保留且**恒定**——M5 的设备验收与多条渲染
 * 测试都靠它定位；待批状态由角标的数字承担。
 */
@Composable
internal fun PermissionChip(
    permission: SessionPermission,
    pendingCount: Int,
    onClick: () -> Unit,
) {
    val urgent = pendingCount > 0
    val accent = if (urgent) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .border(
                1.dp,
                if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .semantics { contentDescription = "审批权限" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // `Lock` 是 material-icons-core 1.7.8 里**唯一**可用的锁形图标：
            // Shield / Security / VerifiedUser / Key 都不在这 49 枚里（已用 `jar tf` 核实）。
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        if (urgent) {
            // 角标用 `offset` 挂在右上角，不参与 32dp 的测量，因此不会把圆钮撑大
            // （撑大就会把底排又挤回两行，正是这一轮要修掉的东西）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .heightIn(min = 15.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = pendingCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
        }
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
