package com.revxzxcx.remotewebapp.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.cache.DshCache
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.MuxError
import com.revxzxcx.remotewebapp.ui.home.rememberNow
import com.revxzxcx.remotewebapp.ui.common.formatWallClock

/**
 * 调试页（需求文档非功能需求「可观测」）。
 *
 * 目标不是「功能」，而是**当界面说错话时能立刻找到它为什么说错**。M3 的冷启动缺陷
 * （工作区列表偶发为空却谎报「已连接」）就是靠 mux 的 `onOpen` 计数为 0、而服务端
 * `onlineCount` 也一直为 0 才定位到 `readTimeout(0)` 让握手永不超时的——所以这里展示的
 * 每一项都对应一个真实排查过的问题，而不是「有比没有好」的仪表盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: DebugViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // 「已连上（53s 前）」必须自己走秒：读普通的 `System.currentTimeMillis()` 只在重组时
    // 求值，而调试页静止时没有任何状态变化会触发重组，那个秒数会一直冻在打开页面的那一刻
    // （R006 P3-5）。
    val now = rememberNow()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接与缓存诊断") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = viewModel::refresh) { Text("刷新") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "sec-conn") { SectionTitle("连接") }
            item(key = "row-base") { InfoRow("地址", state.baseUrl) }
            item(key = "row-device") { InfoRow("设备 id", state.maskedDeviceId) }
            item(key = "row-label") {
                InfoRow("设备", state.deviceLabel.ifBlank { "（未命名）" })
            }
            item(key = "row-paired") {
                InfoRow("配对时间", if (state.pairedAt > 0L) formatWallClock(state.pairedAt) else "—")
            }
            item(key = "row-owner") {
                InfoRow("缓存分区", state.ownerId.ifBlank { "（未配对）" })
            }

            item(key = "sec-carrier") { SectionTitle("载波状态") }
            item(key = "row-carrier") {
                InfoRow("状态", state.stats.carrier.explain())
            }
            item(key = "row-since") {
                val connected = state.stats.carrier as? CarrierState.Connected
                InfoRow(
                    label = "已连上",
                    value = connected?.let {
                        "${formatWallClock(it.sinceMillis)}（${(now - it.sinceMillis) / 1000}s 前）"
                    } ?: "—",
                )
            }
            item(key = "row-lastfail") {
                InfoRow("最近一次故障", state.stats.lastFailure ?: "无")
            }
            item(key = "row-consec") {
                InfoRow("连续失败", "${state.stats.consecutiveFailures} 次")
            }

            item(key = "sec-frames") { SectionTitle("mux 帧计数") }
            item(key = "row-opens") { InfoRow("建连成功", "${state.stats.opens} 次") }
            item(key = "row-failures") { InfoRow("建连失败", "${state.stats.failures} 次") }
            item(key = "row-in") { InfoRow("收到帧", "${state.stats.framesIn}") }
            item(key = "row-out") { InfoRow("发出帧", "${state.stats.framesOut}") }
            item(key = "row-invalid") {
                InfoRow("非法帧（会以 4002 断开）", "${state.stats.invalidFrames}")
            }
            item(key = "row-orphan") {
                InfoRow("孤儿帧（未知 streamId，已忽略）", "${state.stats.orphanFrames}")
            }

            item(key = "sec-cache") { SectionTitle("本地缓存") }
            item(key = "row-cache") {
                InfoRow(
                    label = "行数",
                    value = when (val rows = state.cacheRows) {
                        is CacheRowsState.Rows ->
                            "工作区 ${rows.value.workspaces} · 会话 ${rows.value.sessions} · 消息 ${rows.value.messages}"

                        // 未配对时缓存功能本来就不启用——显示成「读库失败」是在报一个不存在的错。
                        CacheRowsState.Unknown -> "未启用（未配对或尚未读取）"
                        is CacheRowsState.Failed -> "读库失败（当作没有缓存运行）：${rows.reason}"
                    },
                )
            }
            item(key = "row-cachelimit") {
                InfoRow(
                    label = "单会话上限",
                    value = "${DshCache.MESSAGE_CACHE_LIMIT} 条事件",
                )
            }

            item(key = "sec-errors") { SectionTitle("最近故障（${state.errors.size} 条）") }
            if (state.errors.isEmpty()) {
                item(key = "err-empty") {
                    Text(
                        text = "还没有记录到故障。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.errors, key = { "err-${it.atMillis}-${it.code}-${it.summary.hashCode()}" }) { error ->
                    ErrorRow(error)
                }
            }

            item(key = "sec-note") {
                Text(
                    text = "帧计数是累计值，切换地址或重新配对后归零。故障列表只保留最近若干条，重启 App 后清空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 一行「标签 : 值」。
 *
 * 值可能很长（地址、故障描述），所以整行横向可滚而不是截断——诊断信息截断了就没用了。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ErrorRow(error: MuxError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatWallClock(error.atMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  ${error.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = error.summary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
