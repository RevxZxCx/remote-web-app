package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.workspace.Workspace

/**
 * 工作区页（原型 `#home`）。
 *
 * 本期只展示工作区列表 + 每个工作区下的会话数量；点进会话列表在 M2 补齐。
 * 顶部有一条连接状态条，断线重连时**不清空**列表（只改状态文案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onUnpair: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作区") },
                actions = {
                    TextButton(onClick = viewModel::retry) { Text("刷新") }
                    TextButton(onClick = onUnpair) { Text("退出配对") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionBar(
                text = state.connection,
                ok = state.error == null,
            )

            state.error?.let { error ->
                Surface(
                    color = Color(0xFF3A1E22),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (state.needsRepair) {
                                "配对已失效：$error"
                            } else {
                                "连接失败：$error"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4AB),
                        )
                        if (state.needsRepair) {
                            Button(onClick = onUnpair) { Text("重新配对") }
                        } else {
                            Button(onClick = viewModel::retry) { Text("重试") }
                        }
                    }
                }
            }

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取工作区…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.snapshot.isEmpty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "这个桌面端还没有工作区。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = state.snapshot.items,
                        key = { it.workspaceId },
                    ) { workspace ->
                        WorkspaceRow(workspace)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }
    }
}

/** 顶部连接状态条。 */
@Composable
private fun ConnectionBar(text: String, ok: Boolean) {
    Surface(
        color = if (ok) Color(0xFF12301F) else Color(0xFF33280F),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (ok) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(7.dp),
            ) {}
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) Color(0xFF9BE7B8) else Color(0xFFF3D08A),
            )
        }
    }
}

/** 单个工作区行。 */
@Composable
private fun WorkspaceRow(workspace: Workspace) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false) { }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = workspace.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = workspace.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Text(
            text = "${workspace.sessionIds.size} 个会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}
