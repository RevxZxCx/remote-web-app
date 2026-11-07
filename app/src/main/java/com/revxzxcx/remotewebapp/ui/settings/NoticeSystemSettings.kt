package com.revxzxcx.remotewebapp.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * 跳到系统里本应用的通知设置页（R056）。
 *
 * ## 为什么要三层回落
 * `ACTION_APP_NOTIFICATION_SETTINGS`（API 26+）是**厂商可裁剪**的：一加 / ColorOS 上
 * 有的版本把通知页并进「应用通知管理」，收到 `EXTRA_APP_PACKAGE` 后直接
 * `ActivityNotFoundException`；更老或更定制化的 ROM 甚至不认这个 action。
 * 用户点了「去系统设置」却什么都没发生，是最容易被判定为「这 App 坏了」的一种失败。
 *
 * 所以按「最精确 → 最兜底」三级：
 * 1. `ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`：直达本应用的通知页，能改频道；
 * 2. `ACTION_APPLICATION_DETAILS_SETTINGS`：无论如何都在的应用详情页，用户从那里点「通知」；
 * 3. `ACTION_SETTINGS`：系统设置首页，最兜底。
 *
 * ## 为什么不做 `resolveActivity` 预检
 * 早先的实现在 `startActivity` 前先 `resolveActivity(packageManager) != null`，想提前
 * 排除「这个 action 在 ROM 上不存在」。这条路是错的：
 * ① API 30+ 的**包可见性**规则下，系统设置这类外部 Activity 未必对我们可见，预检会
 *    返回 null 而其实能启动——于是三级全被跳过，用户点一下直接跳到 Toast，比不预检还差；
 * ② 它挡不住真正的问题：一加 / ColorOS 上 `ACTION_APP_NOTIFICATION_SETTINGS` 被改成
 *    需要不同 extra，预检通过、`startActivity` 照样 `ActivityNotFoundException`。
 * 所以直接启动 + 逐个 catch（`ActivityNotFoundException` / `SecurityException`），
 * 失败就换下一级；三级全失败才 Toast 告诉用户自己去点。
 *
 * 注意 `NotificationManagerCompat.ACTION_APP_NOTIFICATION_SETTINGS` 在旧版本上不存在，
 * 这里用字符串常量，避免 API 级别判断散落。
 */
fun Context.openNoticeSystemSettings() {
    val candidates = listOf(
        // 1. 本应用通知页（API 26+）。
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra("android.provider.extra.APP_PACKAGE", packageName),
        // 2. 应用详情页（所有版本都有）。
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null)),
        // 3. 系统设置首页。
        Intent(Settings.ACTION_SETTINGS),
    )

    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // 这个 action 在本 ROM 上不存在，换下一级，不要就此放弃。
            continue
        } catch (_: SecurityException) {
            // 部分 ROM 会抛 SecurityException 而不是 ActivityNotFoundException。
            continue
        }
    }

    Toast.makeText(
        this,
        "打开系统设置失败，请手动到 设置 › 通知 里找到本应用",
        Toast.LENGTH_LONG,
    ).show()
}

/**
 * 本应用的通知是否在系统层面被禁（R056，供设置页摘要用）。
 *
 * 这只是四层里的一层（见 `NoticeStatus`）；单独抽出来是因为设置主页那行摘要
 * 需要一个**不依赖容器**的快速判断，而它恰好只需要系统信息。
 */
fun Context.notificationsBlockedBySystem(): Boolean =
    !NotificationManagerCompat.from(this).areNotificationsEnabled()
