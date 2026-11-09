package com.revxzxcx.remotewebapp.ui.settings

import android.content.ActivityNotFoundException
import android.os.Build
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

/**
 * 跳到流体云开关那一页（R059）。
 *
 * ## 为什么不直接用 `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`
 * 那是 AOSP 为 Android 16 的 Live Updates 提供的标准入口，但**厂商可以完全不实现**。
 * 真机实测（OnePlus PKG110 / ColorOS 17 / API 37）：
 * `cmd package query-activities -a android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS`
 * 返回 `No activities found`——这台机器上那个设置页根本不存在，
 * 而 `ACTION_APP_NOTIFICATION_SETTINGS` 会落到
 * `com.oplus.notificationmanager/.AppNotificationSettingsActivity`，
 * 「流体云显示实时活动」开关就在那一页上（实测打开后 AppOp 从 `ignore` 变 `allow`）。
 *
 * 所以顺序是「先试标准入口，落了空再退到应用通知页」：别的 ROM 将来实现了标准入口时，
 * 这里能自动用上更精确的那一个，不必再改代码。
 *
 * ## 为什么不做 `resolveActivity` 预检
 * 与 [openNoticeSystemSettings] 同一个理由（API 30+ 包可见性会让预检给出假阴性）。
 */
fun Context.openCapsuleSettings() {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // 字符串常量而不是 `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`：
            // 后者在 compileSdk 36 上存在，但写成常量能让 `minSdk 26` 的调用点
            // 不必整段包 `@RequiresApi`——这个 Intent 在任何版本上都可以安全构造。
            add(Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS))
        }
        add(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra("android.provider.extra.APP_PACKAGE", packageName),
        )
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
        add(Intent(Settings.ACTION_SETTINGS))
    }

    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
    }
    Toast.makeText(this, "没能打开系统设置，请手动到「设置 → 通知与状态栏 → 流体云」里打开", Toast.LENGTH_LONG).show()
}

/** `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 的字面值（API 36 才有这个常量）。 */
private const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS =
    "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
