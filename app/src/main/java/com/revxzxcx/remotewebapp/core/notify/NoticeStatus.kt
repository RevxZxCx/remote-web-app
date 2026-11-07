package com.revxzxcx.remotewebapp.core.notify

/**
 * 设置页「通知」一段要显示的全部状态（R056）。
 *
 * ## 为什么是一个纯数据类
 * 判断「现在能不能收到通知」涉及**四层**互相独立的开关：
 *
 * | 层 | 谁控制 | 能否读 |
 * | --- | --- | --- |
 * | 系统总开关 | 用户在系统设置里关 | `areNotificationsEnabled()` |
 * | 运行时权限（API 33+） | 用户在权限弹窗里拒 | `checkSelfPermission` |
 * | 单个频道 | 用户长按通知 → 关掉某一类 | `getNotificationChannel(id).importance` |
 * | App 内开关 | 我们自己的设置页 | `NoticeSettings` |
 *
 * 四层任意一层关掉，用户都收不到通知，但**表现完全一样**（什么都不响）。
 * 设置页必须能指出「到底卡在哪一层」，否则用户只会觉得「这 App 的通知坏了」。 *
 * 收成数据类之后，文案判定就是一组纯函数，可以逐条断言（见 `NoticeStatusTest`），
 * 不用在 Robolectric 里伪造四层系统状态。
 *
 * @param systemEnabled 系统总开关（`NotificationManagerCompat.areNotificationsEnabled()`）。
 * @param permissionGranted API 33+ 的 `POST_NOTIFICATIONS` 运行时权限；33 以下恒为 true。
 * @param requested 是否已经主动请求过一次（见 [NoticeSettings.permissionRequested]）。
 * @param doneEnabled App 内「任务结束」开关。
 * @param askEnabled App 内「需要确认」开关。
 * @param lockScreenDetail 锁屏是否显示详细内容。
 * @param askChannelEnabled 「需要确认」这个系统频道是否还被允许。
 * @param doneChannelEnabled 「任务结束」这个系统频道是否还被允许。
 */
data class NoticeStatus(
    val systemEnabled: Boolean = true,
    val permissionGranted: Boolean = true,
    val requested: Boolean = false,
    val doneEnabled: Boolean = true,
    val askEnabled: Boolean = true,
    val lockScreenDetail: Boolean = true,
    val askChannelEnabled: Boolean = true,
    val doneChannelEnabled: Boolean = true,
) {

    /** 此刻是否真的能收到「任务结束」通知（四层全通）。 */
    val doneUsable: Boolean
        get() = systemEnabled && permissionGranted && doneEnabled && doneChannelEnabled

    /** 此刻是否真的能收到「需要确认」通知（四层全通）。 */
    val askUsable: Boolean
        get() = systemEnabled && permissionGranted && askEnabled && askChannelEnabled

    /** 任意一类能收到就算「通知是通的」。 */
    val anyUsable: Boolean get() = doneUsable || askUsable
}

/**
 * 设置页顶行（「系统通知」）右侧那行说明文字。
 *
 * 只说**最该先解决的那一层**：四层里任意一层没通，用户要做的事都不同，
 * 一次列四条等于什么都不说。优先级从「根本收不到」到「能用但被裁」排。
 */
fun noticeStatusDetail(status: NoticeStatus): String = when {
    !status.permissionGranted && !status.requested -> "还没授权，点这里开启"
    !status.permissionGranted -> "已拒绝，去系统设置里打开"
    !status.systemEnabled -> "系统里被关掉了"
    !status.askChannelEnabled && !status.doneChannelEnabled -> "两类通知都被关掉了"
    !status.askChannelEnabled -> "「需要确认」被关掉了"
    !status.doneChannelEnabled -> "「任务结束」被关掉了"
    !status.askEnabled && !status.doneEnabled -> "两类都被你关掉了"
    !status.askEnabled -> "「需要确认」被你关掉了，任务会一直等你"
    !status.doneEnabled -> "「任务结束」被你关掉了"
    else -> "任务跑完、需要你确认时提醒"
};

/**
 * 现在该不该弹系统的权限请求框。
 *
 * **只该在用户明确表达了「我想要通知」之后弹**（比如他点开了设置页的通知一行，
 * 或点了一颗「开启通知」按钮）。启动时就弹是业内最招人烦的做法：
 * 用户此刻还不知道这 App 要通知干什么，随手一拒，之后就只能去系统设置里找。
 *
 * 而「永久拒绝」之后系统**不再显示弹窗、直接回调拒绝**，所以问过一次就不再问
 * （见 [NoticeSettings.permissionRequested]）。
 */
fun shouldRequestPermission(status: NoticeStatus): Boolean =
    !status.permissionGranted && !status.requested

/**
 * 权限被永久拒绝后，只能引导用户去系统设置页。
 *
 * 判据是「问过了但还没授予」：问过一次之后再没有别的办法（系统不会再弹框）。
 */
fun shouldGuideToSystemSettings(status: NoticeStatus): Boolean =
    !status.permissionGranted && status.requested

/**
 * 这件事该去哪儿解决（决定诊断卡上那颗按钮带用户去哪儿）。
 *
 * ## 为什么必须单独判，而不是「有问题就送去系统设置」
 * 四层里有一层是**本页自己的开关**。当「任务结束」和「需要确认」都被用户在下面关掉时，
 * 诊断卡写着「两类都被你关掉了」，按钮却是「去系统设置」——点过去一看，系统里一切正常，
 * 用户只能原路返回。把用户送到一个没有问题的页面，比不给按钮更糟：他会以为自己找错了地方。
 */
enum class NoticeFix {
    /** 拨本页下面的开关就行。 */
    AppSwitch,

    /** 弹系统权限框（还没问过）。 */
    Permission,

    /** 只能去系统设置（已拒绝过 / 总开关关了 / 某个频道被关）。 */
    SystemSettings,
}

/** 见 [NoticeFix]。优先级与 [noticeStatusDetail] 一致，保证「说的」与「让你做的」是同一件事。 */
fun noticeFixLocation(status: NoticeStatus): NoticeFix = when {
    !status.systemEnabled -> NoticeFix.SystemSettings
    !status.permissionGranted && !status.requested -> NoticeFix.Permission
    !status.permissionGranted -> NoticeFix.SystemSettings
    !status.askChannelEnabled || !status.doneChannelEnabled -> NoticeFix.SystemSettings
    else -> NoticeFix.AppSwitch
}

/**
 * 一加 / ColorOS 上那些**没有 API 可查**的保活设置。
 *
 * ## 为什么必须引导而不是自动设置
 * 自启动、关联启动、后台运行、多任务加锁这四项在一加/ColorOS 上由厂商的电池应用
 * （`com.oplus.battery`）独立管控，**不属于 AOSP 白名单**。`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * 只操作 AOSP 那一套，`isIgnoringBatteryOptimizations()` 返回 true 时照样被杀。
 * donc kill my app 对一加的评分是 5/5（最差档），官方「开发者解法」一栏写的是
 * **No known solution on the developer end**。
 *
 * 所以这一项的产品口径必须是「引导用户手动开」，而不是「我们已经帮你优化了」——
 * 后者是假承诺，用户下次收不到通知时会更生气。
 *
 * @param key 稳定标识（存「已确认过」用）。
 * @param title 一项一句话。
 * @param detail 具体路径；用户照着点就能找到。
 */
data class OemGuideStep(
    val key: String,
    val title: String,
    val detail: String,
)

/**
 * 一加 Ace 5 / ColorOS 15 实际需要用户手动开的项。
 *
 * 路径来自 dontkillmyapp.com 与一加社区实测，ColorOS 各版本菜单位置有差异，
 * 所以 detail 里写的是「在哪一层下面」而不是精确的菜单文案。
 */
val OemGuideSteps: List<OemGuideStep> = listOf(
    OemGuideStep(
        key = "battery",
        title = "电池：不优化",
        detail = "设置 › 电池 › 电池优化 › 全部应用 › 远程 Web › 不优化",
    ),
    OemGuideStep(
        key = "advanced",
        title = "关掉「深度优化」与「睡眠待机优化」",
        detail = "设置 › 电池 › 电池优化 › 右上角 ⋮ › 高级优化（两项默认都开着，是主要的杀后台原因）",
    ),
    OemGuideStep(
        key = "autostart",
        title = "允许自启动 / 后台运行",
        detail = "设置 › 应用 › 应用管理 › 远程 Web › 耗电管理（打开「允许后台活动」与「允许自启动」）",
    ),
    OemGuideStep(
        key = "lock",
        title = "在多任务里锁定本 App",
        detail = "打开最近任务 › 长按本 App 的卡片 › 点右上角锁图标。不锁定的话，一加会把上面几项随机改回默认",
    ),
)
