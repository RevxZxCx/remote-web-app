package com.revxzxcx.remotewebapp.core.service

/**
 * 「后台连接」那一段要显示的全部内容（R057）。
 *
 * ## 为什么打包成一个纯数据对象
 * 这一段有六项可变的东西：服务状态、三个开关、第一行失败原因、以及「确认框看过没」。
 * 如果把它们摊成 `NoticeSettingsScreen` 的六个参数，那个函数签名会长到看不清哪个
 * 是哪个的从属；更糟的是**任何一处判断改动都要动 UI 层**。
 *
 * 打包成数据 + 几个纯函数之后，文案与「什么时候置灰」「什么时候显示失败行」全部
 * 能在 JVM 单测里逐条断言，不用建 Service、不用伪造系统通知权限。
 */
data class LinkServiceUi(
    /** 服务此刻的状态（由 `LinkServiceController.status` 来）。 */
    val status: ServiceStatus = ServiceStatus(),
    /** 「保持后台连接」总开关。 */
    val enabled: Boolean = false,
    /** 「服务停了自动恢复」。只在 [enabled] 为真时有意义。 */
    val autoRecover: Boolean = true,
    /** 「开机后自动连接」。只在 [enabled] 为真时有意义。 */
    val bootStart: Boolean = false,
    /** 首次打开前的确认框看过没。 */
    val confirmSeen: Boolean = false,
    /** 上一次启动失败的原因；空串表示没有。 */
    val lastFailure: String = "",
)

/**
 * [LinkServiceUi] 里**不含状态**的那一半：三个开关 + 确认标记 + 失败原因。
 *
 * ## 为什么要拆出来（R057 审查第三轮 P3-2）
 * 两个来源的变化频率差了三个数量级：
 * - [ServiceStatus] 每秒 `copy` 一次（`ServiceStatusTracker.tick` 推进时长），
 * - 这几项只在用户拨开关、或服务启动失败时变。
 *
 * 混在一个对象里，设置页只能拿「状态」当 `remember` 的 key，于是**停在设置页的每一秒
 * 都重建一次 [LinkServiceUi]、做 5 次 `SharedPreferences` 同步读**（全在主线程）。
 * 拆开之后状态走 `collectAsState`，这几项按自己的 epoch 读。
 */
data class LinkServicePrefs(
    /** 「保持后台连接」总开关。 */
    val enabled: Boolean = false,
    /** 「服务停了自动恢复」。只在 [enabled] 为真时有意义。 */
    val autoRecover: Boolean = true,
    /** 「开机后自动连接」。只在 [enabled] 为真时有意义。 */
    val bootStart: Boolean = false,
    /** 首次打开前的确认框看过没。 */
    val confirmSeen: Boolean = false,
    /** 上一次启动失败的原因；空串表示没有。 */
    val lastFailure: String = "",
) {
    /** 补上某一刻的状态，还原成 [LinkServiceUi]。 */
    fun withStatus(status: ServiceStatus): LinkServiceUi = LinkServiceUi(
        status = status,
        enabled = enabled,
        autoRecover = autoRecover,
        bootStart = bootStart,
        confirmSeen = confirmSeen,
        lastFailure = lastFailure,
    )
}

/**
 * 「保持后台连接」开关下面那行说明。
 *
 * 打开之后才提「多一条常驻通知」与「耗电略增」：关着的时候说这些只会让用户
 * 以为已经付出代价了。这两句不能省——常驻通知是用户打开之后**立刻就能看见的
 * 变化**，不预告会让他以为出了 bug。
 */
fun keepAliveDetail(ui: LinkServiceUi): String = when {
    ui.lastFailure.isNotBlank() -> "上次启动失败：${ui.lastFailure}"
    ui.enabled ->
        "退到后台也不断开。会多一条不会响的常驻通知，耗电略增——这是 Android 对前台服务的硬性要求。"
    else -> "退到后台也不断开，任务跑完能立刻收到。需要一条常驻通知，Android 的硬性要求。"
}

/**
 * 「服务停了自动恢复」开关下面那行说明。
 *
 * 原型第 6 节的两支是**互斥**的：开了就静默拉起，关了才发通知。这里把这句话
 * 直接写进说明，因为用户最可能的疑问正是「关了还会不会连」。
 */
fun autoRecoverDetail(ui: LinkServiceUi): String =
    if (ui.autoRecover) {
        "被系统停掉后自动拉回来，不打扰你。"
    } else {
        "被系统停掉后不自动拉，只发一条通知提醒你。"
    }

/** 「开机后自动连接」开关下面那行说明。 */
fun bootStartDetail(): String = "重启手机后自动把后台连接拉起来，不用先打开 App。"
