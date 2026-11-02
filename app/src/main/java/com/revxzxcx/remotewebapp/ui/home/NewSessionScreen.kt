package com.revxzxcx.remotewebapp.ui.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.core.session.AgentPreset
import com.revxzxcx.remotewebapp.core.session.CreatedSession
import com.revxzxcx.remotewebapp.core.session.ImageMeasure
import com.revxzxcx.remotewebapp.core.session.PromptAttachment
import com.revxzxcx.remotewebapp.core.workspace.Workspace
import com.revxzxcx.remotewebapp.ui.chat.Composer
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.theme.pillShape

/** 新增会话页里两个胶囊的测试标签（渲染测试要按标签找，而不是按文案——文案会变）。 */
internal const val NewSessionWorkspaceTag = "new-session-workspace"
internal const val NewSessionPresetTag = "new-session-preset"

/**
 * 「新增会话」页（R055）。
 *
 * ## 这个页面为什么「就是对话页」
 *
 * 用户 m06896 的原话：「新增会话界面可以直接使用对话界面，但是要增加两个选择栏」。
 * 所以它不是另造一套表单，而是把 [Composer] 原样搬过来，**只在它上面加两颗胶囊**。
 * 好处不只是省代码：输入区的所有行为（附件、命令、引用、键盘处理、展开/收起）在
 * 两个页面天然一致，不会出现「对话页能贴图、新建页不能」这种割裂。
 *
 * ## 会话什么时候建（Q2：发送时）
 *
 * 进页面**不建**会话。见 [NewSessionState] 的说明：进页面就建会在桌面端留下用户
 * 根本没用过的空会话，而手机端没有删除入口（服务端无删除 API）。
 * 这里只在用户按下发送时调 [NewSessionViewModel.send]，成功后把 `created` 交给调用方
 * 跳转；跳转前显示「正在创建…」，避免用户以为没反应又点一次。
 *
 * ## 附件为什么先禁用
 *
 * 附件要上传到**某个会话**（`session/upload` 需要 sessionId），而此刻会话还不存在。
 * 与其让用户贴完图再在发送时失败，不如在新建页把「＋」隐藏——`Composer` 的附件
 * 回调传空实现即可，它不会主动显示入口。这一取舍写在 [Composer] 的调用处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    /** 当前主机的工作区列表（来自首页已有的 `workspace/follow`，零新接口）。 */
    workspaces: List<Workspace>,
    /** 工作区列表是否还在读——未就绪时胶囊画成骨架并禁用（原型第 4 节 ②）。 */
    workspacesLoading: Boolean,
    onBack: () -> Unit,
    /** 建好会话后跳转。参数是新建的会话与**此刻的草稿**（对话页要把它接着发出去）。 */
    onCreated: (CreatedSession, String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // 工作区列表一到就把目标 id 落到具体那一台上。胶囊此刻显示的是列表第一项，
    // 而 `state.workspaceId` 还是 null —— 不这一手，界面写着「1」却带着 null 出去，
    // 会话会建到服务端的默认工作区（R055 真机验收发现的那个缺陷）。
    // 键用列表本身：同一台主机内工作区增删都会换一个新 List 实例。
    LaunchedEffect(workspaces) {
        viewModel.updateWorkspaces(workspaces)
    }

    // 建好就跳走。
    //
    // 这里**必须**先 `clearCreated()` 再 `onCreated(...)`：ViewModel 挂在
    // `new-session-$scopeToken` 这个 key 上，退出页面时不会 clear（`viewModel(key=...)`
    // 用的是 Activity 的库，`newSessionOpen` 变假只是不再组合它）。留着 `created`
    // 非空的后果是——用户回到首页再点一次「＋」，VM 被原样交回来，`created` 还在，
    // 这一屏刚组合就立刻再跳一次，**从此再也进不了新增会话页**。
    //
    // 先清再回调的顺序也保证「消费一次」：`onCreated` 里若抛异常，至少不会留下一个
    // 会被重复消费的事件。
    LaunchedEffect(state.created?.sessionId) {
        val created = state.created ?: return@LaunchedEffect
        viewModel.clearCreated()
        onCreated(created, state.draft)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("新增会话", style = MaterialTheme.typography.titleMedium)
                        // 副标题必须说清「现在还没有产生任何东西」，否则用户会以为
                        // 点开就已经在这台桌面端上建好了会话（原型第 2 节）。
                        Text(
                            text = "发第一条消息才会建出会话",
                            style = MaterialTheme.typography.labelSmall,
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 空态引导。占满剩余高度，把输入区压到底部，与对话页的布局同构。
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyNewSessionHint()
                }

                // ── 创建中的遮罩式进度：盖住输入区，防止用户在请求在途时继续敲字。
                if (state.creating) {
                    CreatingBanner()
                }

                // ── 失败横幅：草稿保留，用户改完能直接再发。
                state.error?.let { error ->
                    ErrorBanner(text = error, onDismiss = viewModel::dismissError)
                }

                // ── 两颗胶囊 + 输入区。与对话页的 dock 一样贴底、无底色。
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    PickRow(
                        workspaces = workspaces,
                        loading = workspacesLoading,
                        state = state,
                        onSelectWorkspace = viewModel::selectWorkspace,
                        onSelectPreset = viewModel::selectPreset,
                    )
                    Spacer(Modifier.height(8.dp))
                    Composer(
                        draft = state.draft,
                        generating = false,
                        sending = state.creating,
                        // 新建页没有附件（会话还不存在，见页面 KDoc），所以待发附件恒空、
                        // 上传回调给空实现；`Composer` 只在有附件时渲染附件行。
                        pending = emptyList(),
                        attachNotice = null,
                        modelChip = { },
                        permissionChip = { },
                        onDraftChange = viewModel::onDraftChange,
                        onSend = viewModel::send,
                        // 没有进行中的轮次可停，但 `Composer` 的发送键在「generating 且草稿为空」
                        // 时会变成停止键；这里 generating 恒 false，所以它始终是发送键。
                        onStop = { },
                        onRemoveAttachment = { },
                        onAttachImage = { _, _, _, _ -> null },
                        onAttachFile = { _, _ -> },
                        onAttachNotice = { },
                    )
                }
            }
        }
    }
}

/** 空会话区的中段引导（原型第 2 节 `.emptyhint`）。 */
@Composable
private fun EmptyNewSessionHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Create,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "还没有内容",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "在下面输入第一条消息，就会在这台桌面端上建出会话并开始工作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 「正在创建…」条（原型第 3 节的中间态）。 */
@Composable
private fun CreatingBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = "正在创建会话…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 创建失败横幅。草稿不动，用户可以直接再点发送。 */
@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * 两颗胶囊：左工作区、右模式（原型第 2 节 `.pickrow`）。
 *
 * 工作区列表未就绪时画骨架并禁用（原型第 4 节 ②），但**输入框照常可用**——
 * 用户此刻就可以开始打字，列表读回来只影响胶囊显示。
 */
@Composable
private fun PickRow(
    workspaces: List<Workspace>,
    loading: Boolean,
    state: NewSessionState,
    onSelectWorkspace: (String?) -> Unit,
    onSelectPreset: (AgentPreset) -> Unit,
) {
    var workspaceMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            PickChip(
                icon = Icons.Default.List,
                label = if (loading) {
                    "读取中…"
                } else {
                    workspaceChipLabel(state.workspaceId, workspaces, creating = state.creating)
                },
                enabled = !loading && workspaces.isNotEmpty() && state.created == null,
                modifier = Modifier.testTag(NewSessionWorkspaceTag),
                onClick = { workspaceMenu = true },
            )
            DropdownMenu(
                expanded = workspaceMenu,
                onDismissRequest = { workspaceMenu = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(5.dp)) {
                    workspaces.forEach { ws ->
                        PickMenuRow(
                            label = ws.displayName,
                            selected = ws.workspaceId == state.workspaceId,
                            onClick = {
                                onSelectWorkspace(ws.workspaceId)
                                workspaceMenu = false
                            },
                        )
                    }
                    // Q1：新建工作区这一轮不做（`directoryPicker/list` 实测在手机上不可用，
                    // 详见原型第 6 节）。这里放一个明确禁用的项并说明原因，比什么都不显示好：
                    // 用户知道「这个入口存在但目前不可用」，而不是误以为 App 忘了做。
                    if (workspaces.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        DisabledHint()
                    }
                }
            }
        }
        Box {
            PickChip(
                icon = Icons.Default.Refresh,
                label = state.preset.label,
                enabled = state.created == null,
                modifier = Modifier.testTag(NewSessionPresetTag),
                onClick = { presetMenu = true },
            )
            DropdownMenu(
                expanded = presetMenu,
                onDismissRequest = { presetMenu = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(5.dp)) {
                    AgentPreset.entries.forEach { preset ->
                        PickMenuRow(
                            label = preset.label,
                            detail = preset.blurb,
                            selected = preset == state.preset,
                            onClick = {
                                onSelectPreset(preset)
                                presetMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 一颗胶囊。白底、1dp 描边、999dp 圆角（与 [pillShape] 同源）。 */
@Composable
private fun PickChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        shape = pillShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.5f),
        ),
        modifier = modifier
            .height(32.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text("▾", style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/** 下拉里的一行。[detail] 非空时画成两行（模式列表带说明）。 */
@Composable
private fun PickMenuRow(
    label: String,
    selected: Boolean,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** 新建工作区的禁用说明（Q1）。 */
@Composable
private fun DisabledHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = "新建工作区",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "暂不支持",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
    Text(
        text = "手机端拿不到目录列表，请在桌面端新建。",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
