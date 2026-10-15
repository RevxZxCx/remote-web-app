package com.revxzxcx.remotewebapp.core.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 远程**转发事件**（forwarded events）的端点与形状。
 *
 * ## 这是什么
 * 桌面端 Host 有一批「只在本进程内部广播」的事件。远程客户端（浏览器、以及本 App）可以
 * 订阅其中的一部分，收到的是 `emit` 帧；还有两条是 **waterfall**——需要回答，答完服务端
 * 才继续往下走。
 *
 * 权威定义在 `@deepseek-ai/dsh-api-remotes/lib/index.js:17-126` 的
 * `API_REMOTE_FORWARDED_EVENTS`。当前 **27 条**（`index.js:18-125` 逐条数过），本 App 只关心：
 *  - `approval/request`（waterfall）——**敏感工具的审批请求**；
 *  - `user-questions/request`（waterfall）——模型向用户提问（本期未实现，见缺口记录）。
 * 其余 `emit` 型里与本 App 有关的是 `api-session/status`、`api-session/activity`、
 * `api-session/added`（会话增删/运行态），但聊天页的 `session/follow` 已经覆盖了这些，
 * 所以本期也不接——多一条订阅就多一份状态要同步，没有收益。
 *
 * ## 与 `session/follow` 的关系
 * **同一条 mux WebSocket、同一种帧协议**：`open` 帧的 `endpoint` 写 `$events`、
 * `payload` 写 `{args:{}}`，之后收到的 `item` 帧的 `value` 就是下面这些形状。
 * 之所以能共用：`session/follow` 的 `item` 帧 value 是
 * `{type:'snapshot'|'event'|'assistant-stream', …}`，`$events` 的是
 * `{type:'ready'|'cancel'|'emit'|'waterfall', …}`，两者靠 `value.type` 区分。
 * 因此 `core/protocol/RemoteStreamMux` 一行都不用改（`_dual_mux` 探针实测确认）。
 */
object RemoteEvents {

    /**
     * 打开转发事件流的 stream endpoint。
     *
     * 名字里带 `$` 是官方的（`REMOTE_EVENT_STREAM_ENDPOINT`），不是本地约定。
     */
    const val EVENT_STREAM_ENDPOINT: String = "\$events"

    /**
     * 回答 waterfall 的 unary RPC 端点。
     *
     * 走普通 Typert unary（`POST {base}/remote/api/$events/result`），**不是**流。
     */
    const val EVENT_RESULT_ENDPOINT: String = "\$events/result"
}

/**
 * `$events` 流的收发计数（只用于诊断页）。
 *
 * ## 为什么单独计数而不看 `MuxStats`
 * 一条 WebSocket 上复用三条流（`workspace/follow`、`session/follow`、`$events`），
 * `MuxStats.framesIn` 是**三条流的总和**。聊天页照样在刷消息时，审批通道可能早就断了
 * ——从总帧数上完全看不出来。所以这里按 `$events` 自己数一遍，诊断页才能回答
 * 「审批通道通不通」这个具体问题。
 *
 * ## `emits` 会一直涨
 * 白名单里 27 条有 25 条是 `emit`，本 App 一律忽略（见 [RemoteEventsClient] 的 `emit`
 * 分支）。它们照样占带宽，这是服务端没有按事件类型过滤的既定事实，不是缺陷；
 * 计数放在这里是为了让「帧在涨但没有审批」与「帧不涨」在诊断页上可区分。
 */
data class RemoteEventsStats(
    /** 收到的帧总数（含 ready / cancel / emit / waterfall）。 */
    val framesIn: Long = 0L,
    /** 其中被忽略的 `emit` 帧数。 */
    val emits: Long = 0L,
    /** 其中 `waterfall` 帧数（审批请求 + 模型提问）。 */
    val waterfalls: Long = 0L,
    /** 真正认出来的审批请求数（去重后）。 */
    val approvalsSeen: Long = 0L,
    /** 成功送出的回答数。 */
    val answers: Long = 0L,
)

/** `$events` 流上的服务端帧。 */
sealed interface RemoteEventFrame {
    /** 首帧。`clientId` 必须记住——回答 waterfall 时要带回去。 */
    data class Ready(val clientId: String) : RemoteEventFrame

    /** 服务端撤回了某个待回答请求（模型自己放弃了、轮次结束了…）。 */
    data class Cancelled(val eventId: String) : RemoteEventFrame

    /** 一条只读转发事件。 */
    data class Emit(val event: String, val args: JsonObject?) : RemoteEventFrame

    /** 一条需要回答的转发事件。 */
    data class Waterfall(
        val event: String,
        val eventId: String,
        val agentId: String?,
        val request: JsonObject?,
    ) : RemoteEventFrame
}

/** 对 waterfall 的回答结果。 */
enum class ApprovalOutcome(val wire: String, val label: String) {
    /** 允许这一次（`allowed-once`）。服务端**没有**「永久允许」——只有 one-shot。 */
    ALLOW_ONCE("allowed-once", "允许一次"),

    /** 拒绝（`rejected`）。工具不会执行。 */
    REJECT("rejected", "拒绝"),
    ;

    companion object {
        fun fromWire(value: String?): ApprovalOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一个**等待回答**的审批请求。
 *
 * ## 线上形状（`_approval_e2e.mjs` 实测）
 * ```
 * {"type":"waterfall","event":"approval/request",
 *  "eventId":"127e307a-9600-4319-8f0c-898951286715",
 *  "agentId":"session-3d4ee1a7-341a-4fdd-ad25-885dbf4e1fae",
 *  "request":{"toolName":"pwsh",
 *             "callId":"call_00_AxvQeP3MN8uRgy93w0gk4095",
 *             "reason":"escalate sandbox to danger-full-access: …",
 *             "displayReason":{"en":"…","zh":"…"}}}
 * ```
 *
 * ## `agentId`
 * **顶层会话的 `agentId` 就是 `sessionId`**——这一点是实测定案的，不是推的：
 * `F:\bot\_approval_e2e.mjs` 用 `session/create` 建出
 * `session-3d4ee1a7-341a-4fdd-ad25-885dbf4e1fae`，随后收到的 waterfall 帧
 * `agentId` 与它**逐字相同**。桌面端自己也用同一条判据
 * （`dsh-api-remotes/client.js:1020` 按 `agent.sessionId` 匹配）。
 *
 * 因此 [belongsTo] 用 `agentId == sessionId` 过滤是对的。子代理的 agentId 是另一套
 * （不相等），本 App 的聊天页只展示顶层会话，所以那些请求会被过滤掉——这是有意的：
 * 手机上看不到子代理的审批，桌面端仍然能批。
 *
 * **`session/inspect` 拿不到 agentId**：实测 `POST /remote/api/session/inspect`
 * `{address:{kind:'session',sessionId}}` 回纯文本 `not found`（不是 JSON 信封），
 * 所以别想着「先 inspect 再过滤」——直接用上面这条相等关系。
 *
 * ## `displayReason` 是多语种字典
 * 键是 locale（实测有 `en` / `zh`，可能还有别的）。**必须取 `zh`**，取不到再回落 `en`，
 * 再回落 `reason`。直接显示 `en` 在中文用户眼里就是「没翻译」。
 */
data class ApprovalRequest(
    val eventId: String,
    val agentId: String?,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val displayReason: Map<String, String> = emptyMap(),
) {

    /** 这个请求是不是属于 [sessionId] 这个顶层会话。 */
    fun belongsTo(sessionId: String): Boolean = agentId == sessionId

    /**
     * 给人看的理由：中文优先，再英文，再原始 `reason`，最后工具名。
     *
     * 四级回落而不是两级：实测某些请求只有 `en` 没有 `zh`（桌面端按浏览器语言填充），
     * 而 `reason` 是给模型看的英文技术说明，不该出现在 UI 上——但总比什么都没有强。
     */
    fun reasonText(): String =
        displayReason["zh"]?.takeIf { it.isNotBlank() }
            ?: displayReason["en"]?.takeIf { it.isNotBlank() }
            ?: reason?.takeIf { it.isNotBlank() }
            ?: "工具 $toolName 需要你的授权"

    companion object {

        /**
         * 从 waterfall 帧的 `request` 解析。
         *
         * 宽容：任何字段缺失都返回 null 而不是抛。返回 null 时调用方**不能**回答
         * （没有 `eventId` 就答不了），只能忽略——总比崩溃好。
         */
        fun parse(eventId: String, agentId: String?, request: JsonObject?): ApprovalRequest? {
            val obj = request ?: return null
            val tool = (obj["toolName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null
            return ApprovalRequest(
                eventId = eventId,
                agentId = agentId,
                toolName = tool,
                callId = (obj["callId"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                displayReason = (obj["displayReason"] as? JsonObject)?.entries
                    ?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                    }
                    ?.toMap()
                    ?: emptyMap(),
            )
        }
    }
}

/** 沙箱模式（`sandbox/mode` 事件与 `permission/preset` 共用这套取值）。 */
enum class SandboxMode(val wire: String, val label: String) {
    READ_ONLY("read-only", "只读"),
    WORKSPACE_WRITE("workspace-write", "工作区内可写"),
    DANGER_FULL_ACCESS("danger-full-access", "完全权限"),
    ;

    companion object {
        fun fromWire(value: String?): SandboxMode? = entries.firstOrNull { it.wire == value }
    }
}

/** 审批策略（`approval/policy` 事件）。 */
enum class ApprovalPolicy(val wire: String, val label: String) {
    /** 每次敏感工具都问。 */
    ASK("ask", "每次询问"),

    /** 不再问，直接放行。 */
    NEVER("never", "自动允许"),
    ;

    companion object {
        fun fromWire(value: String?): ApprovalPolicy? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一个会话的**当前权限状态**（只读展示）。
 *
 * ## 为什么是三个独立字段而不是一个枚举
 * 桌面端有三种互不相同的写入路径，事件也不同：
 *  1. `permission/preset` —— 一次性设置「沙箱模式 + 审批策略」的组合；
 *  2. `sandbox/mode` —— 只改沙箱模式；
 *  3. `approval/policy` —— 只改审批策略。
 *
 * 三者都会往会话日志里追加事件（`applySandboxMode` / `applyPolicy` 都有
 * `this.#appendPolicy`，源码注释写明「记录进日志」）。所以正确的展示状态是
 * **各自记住最后一次**，而不是试图合成一个「档位」——合出来的档位在三条路径
 * 交叉时必然与桌面端显示的不一致。
 *
 * ## 只读
 * 服务端**没有**暴露给远程客户端的「设置当前会话权限」入口（`session` 命名空间 24 个
 * 方法里没有 setApprovalPolicy / setPermission；`ctx.remote.approval` 只有 `ask`）。
 * 所以这三个字段只能从事件流读出来展示，UI 上不得做成开关——做成开关就会有用户
 * 点了没反应，比没有开关更糟。
 */
data class SessionPermission(
    val sandboxMode: SandboxMode? = null,
    val approvalPolicy: ApprovalPolicy? = null,
    val preset: SandboxMode? = null,
    /** 最后一次权限事件的 seq；-1 表示还没收到过。 */
    val asOfSeq: Long = -1L,
) {

    /** 有没有任何一个维度收到过。全 null 时 UI 该显示「未知」而不是空白。 */
    val known: Boolean get() = sandboxMode != null || approvalPolicy != null || preset != null

    /** 三者都没拿到时为 true（会话日志里没有任何权限事件）。 */
    val isUnknown: Boolean get() = !known

    /**
     * 一行摘要，例如「权限 完全权限 · 审批 每次询问」。
     *
     * 沙箱模式取 `sandboxMode`，缺席时回落 `preset`（`permission/preset` 会同时设两者，
     * 但只有 preset 事件能告诉我们「用户选的是哪个档位」）。
     */
    fun summary(): String {
        if (!known) return "权限未知"
        val mode = (sandboxMode ?: preset)?.label ?: "未知"
        val policy = approvalPolicy?.label ?: "未知"
        return "权限 $mode · 审批 $policy"
    }

    companion object {

        /** 三种会改变权限状态的日志事件类型（都不占行）。 */
        val EVENT_TYPES: Set<String> = setOf("sandbox/mode", "approval/policy", "permission/preset")

        /**
         * 折叠一条权限相关事件；不认识的事件类型原样返回 [current]。
         *
         * @param seq 该事件的 seq，写进 [asOfSeq] 供 UI 标注「这条是什么时候定的」。
         *   传 -1 表示不知道（实时路径一定知道，快照路径从信封里取）。
         */
        fun reduce(
            current: SessionPermission,
            eventType: String,
            data: JsonObject?,
            seq: Long = -1L,
        ): SessionPermission {
            val obj = data ?: return current
            return when (eventType) {
                "sandbox/mode" -> {
                    val mode = SandboxMode.fromWire(obj.text("mode"))
                    if (mode == null) current else current.copy(sandboxMode = mode, asOfSeq = seq)
                }

                "approval/policy" -> {
                    val policy = ApprovalPolicy.fromWire(obj.text("policy"))
                    if (policy == null) current else current.copy(approvalPolicy = policy, asOfSeq = seq)
                }

                "permission/preset" -> {
                    val preset = SandboxMode.fromWire(obj.text("preset"))
                    if (preset == null) current else current.copy(preset = preset, asOfSeq = seq)
                }

                else -> current
            }
        }
    }
}

/** `JsonObject` 里取一个字符串字段；不是字符串或缺失时返回 null。 */
internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * `JsonObject` 里取一个整数字段。
 *
 * **同时接受 JSON 数字与数字字符串**：seq / 时间戳由服务端序列化决定，
 * `session/follow` 的 `updatedAt` 就实测过两种形态（ISO 字符串 / 纯数字）。
 * 这是全仓唯一约定，见 `core/session/SessionEventParser.kt` 的 `long()`
 * 与 `core/cache/DshCache.kt` 的 `seqOf` KDoc。
 */
internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
