package com.revxzxcx.remotewebapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.revxzxcx.remotewebapp.ui.AppRoot

/**
 * 唯一 Activity。
 *
 * 屏幕切换用 [AppRoot] 内部的 Compose 状态机（配对 → 工作区 → …），不引入 navigation-compose：
 * 本期只有 4~6 个屏、且没有深链接需求，`when` + `rememberSaveable` 足够，还少一个依赖。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DshApplication).container
        setContent {
            DshAssistantTheme {
                AppRoot(container = container)
            }
        }
    }
}
