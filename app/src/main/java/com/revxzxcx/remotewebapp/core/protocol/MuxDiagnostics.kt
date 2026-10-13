package com.revxzxcx.remotewebapp.core.protocol

/**
 * mux 载波的当前状态。
 *
 * 为什么把它放在协议层而不是 UI 层：**载波只有一条**（这是 mux 的定义），所以「连没连上」
 * 天然是全局事实。工作区页与聊天页各自跑着自己的 `retryWhen`，如果各自记一份状态，就会出现
 * 「聊天页显示已连接、工作区页显示重连中」这种自相矛盾的界面。让唯一的载波自己持有状态，
 * 重试计划由发起重试的一方通过 [RemoteStreamMux.noteRetry] 汇报回来，就永远不会矛盾。
 */
sealed interface CarrierState {

    /** 从未尝试连接（或已主动关闭）。 */
    data object Idle : CarrierState

    /** 正在握手（`waitForSocket` 已经开始一次尝试）。 */
    data object Connecting : CarrierState

    /** 已建立。 */
    data class Connected(val sinceMillis: Long) : CarrierState

    /**
     * 断线后正在等待下一次重试。
     *
     * @param attempt 已经失败了几次（从 1 开始）。
     * @param retryAtMillis 预计重试的墙钟时刻（epoch 毫秒）。
     */
    data class Reconnecting(
        val attempt: Long,
        val retryAtMillis: Long,
        val reason: String,
    ) : CarrierState

    /**
     * 配对凭证已失效（插件回 403 → `unpaired`）。
     *
     * 这是**终态**：再重试多少次都不会成功，必须由用户重新配对（见 M1 R002 的 P1-1）。
     * 单独一个状态而不是复用 [Idle]，是为了让全局状态条能给出「重新配对」的入口，
     * 而不是一直说「重连中…」。
     */
    data class Unpaired(val reason: String) : CarrierState
}

/**
 * mux 的收发计数与最近一次故障。
 *
 * 这些数字是需求文档「可观测：内置调试页：连接地址、配对状态、mux 帧收发计数、最近 N 条错误」
 * 的数据来源，同时也是排查「到底卡在握手还是卡在帧」的唯一手段——M3 的冷启动缺陷就是靠
 * 「`onOpen` 计数为 0」定位到 `readTimeout(0)` 让握手无限等待的。
 */
data class MuxStats(
    /** 已建立的 WebSocket 次数。 */
    val opens: Long = 0L,
    /** 失败的连接尝试次数（含握手 403、超时、异常关闭）。 */
    val failures: Long = 0L,
    /** 收到的服务端文本帧总数。 */
    val framesIn: Long = 0L,
    /** 发出的客户端文本帧总数（open / item / end / cancel）。 */
    val framesOut: Long = 0L,
    /** 因**形状非法**被拒绝的服务端帧数（这些帧会让整条连接以 4002 关闭）。 */
    val invalidFrames: Long = 0L,
    /** 收到的、streamId 在本机不认识而被静默忽略的帧数（合法但无用）。 */
    val orphanFrames: Long = 0L,
    /** 当前载波状态。 */
    val carrier: CarrierState = CarrierState.Idle,
    /** 最近一次故障的简述；成功连接一次后清空。 */
    val lastFailure: String? = null,
    /** 连续失败次数（成功连上后归零）；[CarrierState.Reconnecting.attempt] 的来源。 */
    val consecutiveFailures: Long = 0L,
)

/** 一条故障记录（调试页按时间倒序展示）。 */
data class MuxError(
    val atMillis: Long,
    val summary: String,
    /** `unpaired` / `transport` / … 与 [TypertException.code] 同义，便于分组。 */
    val code: String,
)

/**
 * 把状态折算成一行中文（需求 FR-4.4 的全局状态条）。
 *
 * 放在这里而不是 Composable 里，是为了让文案能被 JVM 单测钉住——「已连接 / 重连中 /
 * 已断开 / 配对失效」是需求文档写死的四种，不能因为 UI 重构而漂移。
 */
fun CarrierState.describe(nowMillis: Long): String = when (this) {
    CarrierState.Idle -> "未连接"
    CarrierState.Connecting -> "连接中…"
    is CarrierState.Connected -> "已连接"
    is CarrierState.Reconnecting -> {
        val seconds = ((retryAtMillis - nowMillis) / 1000L).coerceAtLeast(0L)
        "重连中…（第 $attempt 次失败，${seconds}s 后重试）"
    }

    is CarrierState.Unpaired -> "配对已失效：$reason"
}

/** 连接是否健康（状态条用它决定颜色）。 */
val CarrierState.isHealthy: Boolean
    get() = this is CarrierState.Connected
