package com.revxzxcx.remotewebapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 应用唯一 Activity。
 *
 * M0 阶段只承载一个占位界面，用于验证 Gradle + Compose 骨架可编译可运行。
 * M1 起会在此挂载导航图（配对 → 工作区 → 会话 → 聊天 → 文件查看 → 设置）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DshAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SkeletonPlaceholder(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun SkeletonPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "DSH 助手", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "M0 骨架 · 协议层尚未接入",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SkeletonPlaceholderPreview() {
    DshAssistantTheme { SkeletonPlaceholder() }
}
