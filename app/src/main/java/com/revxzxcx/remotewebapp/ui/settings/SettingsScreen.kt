package com.revxzxcx.remotewebapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.BuildConfig
import com.revxzxcx.remotewebapp.core.prefs.ThemeMode
import com.revxzxcx.remotewebapp.core.prefs.ThemePreferences
import com.revxzxcx.remotewebapp.ui.common.AppIconButton

/**
 * 设置页（原型 `#set`，需求文档 M5「打磨：主题」）。
 *
 * 本期只有两件真设置：**主题**与**解除配对**。原型的其它开关（Markdown 渲染、代码高亮、
 * 长文件分页、摘要失效降级）都是「已实现且没有关闭理由」的能力说明，做成开关只会让用户
 * 把 App 切进一个从未测试过的组合——所以这里用只读文本呈现，不做成可点的开关。
 *
 * 主题用 [SingleChoiceSegmentedButtonRow] 而不是下拉菜单：只有三档，一眼看完比多点一次
 * 更省事，也避免 Dialog 遮住当前界面的配色效果（切主题的反馈必须是即时的）。
 *
 * 「解除配对」的二次确认框由 AppRoot 层统一渲染（R007 P2-5 → R018 P1-1）：
 * M5-1 给工作区页顶栏的「退出」加了确认，这里若直接执行，同一个破坏性操作就有
 * 两档保护强度。R018 发现真正的问题是**入口有五个、确认只有两处**——工作区页错误
 * 横幅与两屏 ConnectionBanner 的「重新配对」传的是不带确认的同一个 onUnpair，
 * 所以确认框上移到 AppRoot，三屏共用唯一一处。
 *
 * R017：顶栏导航从文字「返回」改成图标（[AppIconButton] + `Icons.Default.ArrowBack`），
 * 并把「连接与缓存诊断」搬进「高级」段——诊断原本只存在于工作区页顶栏，从设置页进来的
 * 用户没有入口回去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themePreferences: ThemePreferences,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
    onOpenDebug: (() -> Unit)? = null,
) {
    val mode by themePreferences.mode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("外观")

            Text(
                text = "主题",
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = ThemeMode.entries
                modes.forEachIndexed { index, candidate ->
                    SegmentedButton(
                        selected = candidate == mode,
                        onClick = { themePreferences.setMode(candidate) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        label = { Text(candidate.label) },
                    )
                }
            }
            Text(
                text = "跟随系统 = 用手机当前的深浅色设置；浅色 / 深色 = 固定不变。" +
                    "代码高亮会同时准备两套配色，所以两种模式下都能读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionLabel("能力")

            ReadOnlyRow("Markdown 渲染", "标题 / 列表 / 表格 / 代码块 / 链接，自绘解析")
            ReadOnlyRow("代码高亮", "深色浅色各一套配色，超长代码块自动跳过")
            ReadOnlyRow("长文件分页", "每次 5000 行，滚到底自动续读")
            ReadOnlyRow("改动文件卡片", "轮次结束时显示已编辑文件与行数，可看 diff")
            ReadOnlyRow("模型切换", "输入区上方的模型条")

            HorizontalDivider()

            SectionLabel("高级")

            if (onOpenDebug != null) {
                TextButton(onClick = onOpenDebug) { Text("连接与缓存诊断") }
                Text(
                    text = "查看当前连接的地址与设备标识、连接状态、收发帧计数，以及本地缓存" +
                        "（工作区 / 会话 / 消息）的行数。排查「连不上」「不刷新」时先看这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReadOnlyRow("版本", "远程 Web ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

            HorizontalDivider()

            SectionLabel("危险操作")

            Text(
                text = "解除配对会清掉本机凭证，之后必须回桌面端重新发起配对。" +
                    "本地缓存的会话与消息会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onUnpair) {
                Text("解除配对", color = MaterialTheme.colorScheme.error)
            }
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
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ReadOnlyRow(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
