package com.revxzxcx.remotewebapp.ui.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revxzxcx.remotewebapp.BuildConfig
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode
import com.revxzxcx.remotewebapp.core.prefs.ThemePreferences
import com.revxzxcx.remotewebapp.ui.common.AppIconButton
import com.revxzxcx.remotewebapp.ui.theme.groupShape

/**
 * 设置页（原型 `docs/R054-多主机效果图.html` 第 5 节「改后」）。
 *
 * ## 两件事，一件是纯重排、一件是新增
 *
 * **纯重排**：R054 之前是「标题 + 说明文字平铺一列，中间用 `HorizontalDivider` 硬切」，
 * 没有容器、没有图标，信息层级靠字号硬撑，和首页卡片、R040 侧栏卡片完全两个世界。
 * 现在统一成全 App 的**卡片分组**：每组一张 surface 卡、组内行与行用 1px 分隔、
 * 行首一枚 26dp 图标方块（按语义着色）。**组里的内容不增不减**——用户没有要求加开关，
 * 而「已实现且没有关闭理由」的能力做成开关只会让人把 App 切进从未测试过的组合，
 * 所以能力段仍是只读（R054 Q2 定案）。
 *
 * **新增**：多了「远程」段与「危险操作」里的一项。
 * - 「我的远程」把此前只存在于首页设备栏的主机管理暴露到设置页，因为用户在「设置」
 *   里找「换一台主机」是很自然的动作，让他回首页点标题是反直觉的。
 * - 危险操作从原来的一项拆成两项，且**语义必须分开**（这是 R054 的一个核心纠正）：
 *   「断开当前远程」只移除当前那一台，其余保留；「移除全部远程」才回到配对页。
 *   旧的单一「解除配对」在多主机语境下会被读成「清空所有」而实际只影响一台。
 *
 * 「解除配对」的二次确认框由 AppRoot 层统一渲染（R007 P2-5 → R018 P1-1）；
 * 「移除当前这台」的确认走首页设备栏那套 `HostRemoveDialog`（琥珀色，非红色——
 * 只是从手机列表里移除，桌面端不受影响，重配就能加回来）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themePreferences: ThemePreferences,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
    onOpenDebug: (() -> Unit)? = null,
    /** 当前凭证簿；决定「远程」段显示几台、当前是哪台（R054）。 */
    book: CredentialBook = CredentialBook.EMPTY,
    /** 点「我的远程」——回到首页并展开设备栏。 */
    onManageHosts: (() -> Unit)? = null,
    /**
     * 「断开当前远程」：只移除当前那一台，其余保留。
     *
     * 必须与 [onUnpair] 分开（R054）：旧版只有单一「解除配对」入口，多主机语境下
     * 它会被读成「清空所有远程」，而实际只影响一台；反过来把「移除全部」也接到
     * 同一个回调上，则会让用户以为只删一台却回到配对页。
     */
    onRemoveActiveHost: (() -> Unit)? = null,
) {
    val mode by themePreferences.mode.collectAsState()
    val active = book.active

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置")
                        Text(
                            text = "远程 Web ${BuildConfig.VERSION_NAME}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    AppIconButton(
                        onClick = onBack,
                        contentDescription = "返回",
                    ) {
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
            SectionLabel("外观")

            CardGroup {
                SettingRow(
                    icon = Icons.Default.Settings,
                    tint = MaterialTheme.colorScheme.primary,
                    title = "主题",
                    detail = "代码高亮两套配色，两种模式都能读",
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, candidate ->
                            SegmentedButton(
                                selected = candidate == mode,
                                onClick = { themePreferences.setMode(candidate) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size,
                                ),
                                label = { Text(candidate.label) },
                            )
                        }
                    }
                    Text(
                        text = "跟随系统 = 用手机当前的深浅色设置；浅色 / 深色 = 固定不变。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SectionLabel("能力")

            CardGroup {
                ReadOnlyRow(
                    icon = Icons.Default.List,
                    title = "Markdown 渲染",
                    detail = "标题 / 列表 / 表格 / 代码块 / 链接，自绘解析",
                )
                RowDivider()
                ReadOnlyRow(
                    icon = Icons.Default.Edit,
                    title = "代码高亮",
                    detail = "深色浅色各一套，超长代码块自动跳过",
                )
                RowDivider()
                ReadOnlyRow(
                    icon = Icons.Default.KeyboardArrowDown,
                    title = "长文件分页",
                    detail = "每次 5000 行，滚到底自动续读",
                )
                RowDivider()
                ReadOnlyRow(
                    icon = Icons.Default.CheckCircle,
                    title = "改动文件卡片",
                    detail = "轮次结束时显示已编辑文件与行数",
                )
                RowDivider()
                ReadOnlyRow(
                    icon = Icons.Default.Menu,
                    title = "模型切换",
                    detail = "输入区上方的模型条",
                )
            }

            SectionLabel("远程")

            CardGroup {
                SettingRow(
                    icon = Icons.Default.Lock,
                    tint = MaterialTheme.colorScheme.primary,
                    title = "我的远程",
                    detail = if (active == null) {
                        "还没有添加过远程"
                    } else {
                        "${book.hosts.size} 台 · 当前「${active.displayName}」"
                    },
                    onClick = onManageHosts,
                ) {
                    // 明细已经在 detail 里，这里只放跳转说明。
                    Text(
                        text = "切换、重命名、改地址、测试连接都在这里。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("高级")

            CardGroup {
                if (onOpenDebug != null) {
                    SettingRow(
                        icon = Icons.Default.Info,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = "连接与缓存诊断",
                        detail = "连不上 / 不刷新时先看这里",
                        onClick = onOpenDebug,
                    )
                    RowDivider()
                }
                ReadOnlyRow(
                    icon = Icons.Default.Star,
                    title = "版本",
                    detail = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
            }

            SectionLabel("危险操作")

            CardGroup {
                SettingRow(
                    icon = Icons.Default.ExitToApp,
                    tint = MaterialTheme.colorScheme.error,
                    title = "断开当前远程",
                    detail = if (active == null) {
                        "当前没有连接"
                    } else if (book.hosts.size <= 1) {
                        "这是最后一台，会回到配对页"
                    } else {
                        "只移除「${active.displayName}」，其余 ${book.hosts.size - 1} 台保留"
                    },
                    // 「断开当前远程」只删这一台：必须走 onRemoveActiveHost，
                    // 不能与下面「移除全部远程」共用 onUnpair（R054）。
                    enabled = active != null && onRemoveActiveHost != null,
                    onClick = onRemoveActiveHost,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Default.Delete,
                    tint = MaterialTheme.colorScheme.error,
                    title = "移除全部远程",
                    detail = "清空本机凭证，回到配对页",
                    onClick = onUnpair,
                )
            }

            Text(
                text = "移除后必须回桌面端重新发起配对（二维码或链接）才能再次连接。" +
                    "本地缓存的会话与消息会一并清掉。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp),
            )
        }
    }
    // 确认框由 AppRoot 层统一渲染（R018 P1-1）：本屏、工作区页的「退出」、
    // 两屏 ConnectionBanner 的「重新配对」共用同一个出口，不能只给其中几处加确认。
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** 一组设置 = 一张卡。组内用 [RowDivider] 分隔，与首页卡片同一套规格（[groupShape]）。 */
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

/**
 * 行首图标方块：26dp 圆角格 + 18dp 图标。
 *
 * 为什么用「有色方块」而不是裸图标：设置页的行高只有 44dp 上下，裸图标和文字的视觉重量
 * 太接近，扫视时两者会糊成一条灰带；给图标一块浅色底把它的「块感」立起来，行与行之间
 * 才有节奏（这是原型第 5 节最明显的观感差别）。
 */
@Composable
private fun IconBlock(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(
                color = tint.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = tint,
        )
    }
}

/** 可点的一行：图标 + 标题 + 说明 + 右侧箭头。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    } else {
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        IconBlock(icon = icon, tint = tint.copy(alpha = contentAlpha))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.padding(top = 1.dp),
            )
            extra?.let {
                // 必须是 Column 而不是 Box：extra 是一个槽位，但调用方经常往里放
                // 多个 composable（主题那行 = 三档分段条 + 一行说明）。用 Box 的话两个
                // 子节点会**层叠在同一位置**绘制，分段条直接压住说明文字——R054 真机验收
                // 就是这样：说明文字糊在按钮上，选中态看不清。
                Column(modifier = Modifier.padding(top = 8.dp)) { it() }
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * contentAlpha),
            )
        }
    }
}

/** 只读的一行：没有箭头、不可点——它们是「已经实现了什么」的说明，不是设置项。 */
@Composable
private fun ReadOnlyRow(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBlock(icon = icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
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
