package com.revxzxcx.remotewebapp.core.events

import com.revxzxcx.remotewebapp.core.session.AskQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
 *  - `user-questions/request`（waterfall）——模型向用户提问。
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

    /**
     * waterfall 提问在拿不到 `callId` 时，卡片身份与草稿键用的合成前缀。
     *
     * **必须与 `ChatViewModel.WATERFALL_PREFIX` 是同一个值**：`ChatViewModel` 用它拼卡片
     * 身份，[RemoteEventsClient.answerQuestions] 用它剥前缀找条目。两边各写一份字面量
     * 就是下一次「一边改了另一边没改」的静默失败（本工程已出现过三次同类问题）。
     */
    const val WATERFALL_PREFIX: String = "waterfall:"
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
    /** 成功送出的审批回答数。 */
    val approvalAnswers: Long = 0L,
    /** 成功送出的提问回答数。 */
    val questionAnswers: Long = 0L,
    /**
     * 收到的 `user-questions/request` 帧数。
     *
     * 计数放在这里是为了让「帧在涨但没有提问」与「帧不涨」在诊断页上可区分。
     */
    val questionsSeen: Long = 0L,
)

/**
 * 回答的种类——只用来在诊断页上把两类回答分开计数。
 *
 * 审批与提问共用同一条 `$events/result` unary（见 [RemoteEventsClient.submit]），
 * 但用户在诊断页上需要知道「我到底答过哪种」，所以计数器必须分两个。
 */
enum class AnswerKind {
    APPROVAL,
    QUESTION,
}

/** `$events` 流上的服务端帧。 */
sealed interface RemoteEventFrame {
    /** 首帧。`clientId` 必须记住——回答 waterfall 时要带回去。 */
    data class Ready(val clientId: String) : RemoteEventFrame

    /** 服务端撤回了某个待回答请求（模型自己放弃了、轮次结束了…）。 */
    data class Cancelled(val eventId: String) : RemoteEventFrame

    /**
     * 一条只读转发事件。
     *
     * `args` 是**数组**：Host 侧 `ctx.emit(name, a, b)` 的参数表摊平后原样转发。
     * 曾经这里写的是 `JsonObject?`，因为 waterfall 的 `request` 是对象而顺手对齐了——
     * 结果是一个永远不会被构造的类型（`handle` 里 emit 分支直接忽略），
     * 谁照着它去解析都会取不到东西。
     */
    data class Emit(val event: String, val args: JsonArray?) : RemoteEventFrame

    /** 一条需要回答的转发事件。 */
    data class Waterfall(
        val event: String,
        val eventId: String,
        val agentId: String?,
        val request: JsonObject?,
    ) : RemoteEventFrame
}

/**
 * 会话列表相关的转发事件（`emit` 型）。
 *
 * ## 为什么需要它
 * 会话列表原来只在**订阅建立那一刻**拉一次 `session/list`（unary，实测 1.78 MB、2.6s）。
 * 于是桌面端新建的会话在手机上永远不出现，会话跑完了手机上那颗「进行中」小柱也一直转
 * （R039 用户报的两个现象）。整表轮询不可行——1.78 MB 的响应没人愿意每几秒拉一次。
 *
 * `API_REMOTE_FORWARDED_EVENTS`（asar 里逐条列过）中这五条由 Host 在会话生命周期上主动推，
 * 每条都声明了 `resources.session`，带得动增量。
 *
 * ## 参数形状（`$events` 探针实测）
 * 帧是 `{"type":"emit","event":"api-session/added","args":[…]}`，`args` 是数组：
 * - `api-session/added` → `[summary]`，`summary` 与 `session/list` 的条目**同形状**
 *   （实测含 `sessionId`/`updatedAt`/`running`/`blank`/`cwd`/`projections` 全套）；
 * - `api-session/removed` / `status` / `activity` / `error` → `[sessionId, …]`
 *   （`error` 的第二项是失败原因文本，见 [SessionNotification.Error]）。
 *
 * ## 为什么 `activity` 也要接
 * 它是**唯一**能覆盖「标题变了 / 排序位置变了」的信号：标题在
 * `projections.values.title` 里，而白名单没有单独的标题事件。所以收到 `activity` 时
 * 必须重拉一次全量，否则列表里的名字会一直停在旧值。
 */
sealed interface SessionNotification {

    /** 会话被创建、或 agent 可用性变化。`summary` 与 `session/list` 的条目同形状。 */
    data class Added(val summary: JsonObject) : SessionNotification

    /** 会话被销毁（页签关掉且持久化层回收）。 */
    data class Removed(val sessionId: String) : SessionNotification

    /** 运行态翻转：`running = true` 表示这一轮正在跑。 */
    data class Status(val sessionId: String, val running: Boolean) : SessionNotification

    /** 会话有新活动（`updatedAt` 变化）：标题与排位可能一起变，需要重拉全量。 */
    data class Activity(val sessionId: String, val updatedAt: Long) : SessionNotification

    /**
     * 一轮跑挂了（R049）。
     *
     * 签名 `'api-session/error'(sessionId, message)`，`message` 是服务端拼好的
     * 「user-safe failure chain」。为什么要接它：`session/list` 的顶层没有 outcome 字段，
     * `turnOutline` 每项也只有 `turn`/`seq`/`prompt`/`response`——**列表侧拿不到「这一轮失败了」**，
     * 而这是列表行画红点的唯一来源。
     */
    data class Error(val sessionId: String, val message: String) : SessionNotification

    companion object {
        /**
         * 把一帧 `emit` 翻译成 [SessionNotification]；不关心的 `event` 返回 null。
         *
         * 宽容解析：`args` 缺项、类型不符一律返回 null 而不是抛异常。理由与本工程其它
         * 解析器一致——列表少更新一次远好过因为一个字段让整条流过不去。
         */
        fun parse(event: String?, args: JsonArray?): SessionNotification? {
            if (event == null || args == null) return null
            return when (event) {
                "api-session/added" -> (args.firstOrNull() as? JsonObject)?.let(::Added)
                "api-session/removed" -> idOf(args)?.let(::Removed)
                "api-session/status" -> {
                    val id = idOf(args) ?: return null
                    val running = (args.getOrNull(1) as? JsonPrimitive)
                        ?.takeIf { it.isString.not() || it.content in setOf("true", "false") }
                        ?.content?.toBooleanStrictOrNull() ?: return null
                    Status(id, running)
                }
                "api-session/activity" -> {
                    val id = idOf(args) ?: return null
                    val at = (args.getOrNull(1) as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
                    Activity(id, at)
                }
                "api-session/error" -> {
                    val id = idOf(args) ?: return null
                    // 消息缺失也要认：红点该亮就得亮，文案只是提示。空串比丢掉整条事件安全。
                    Error(id, (args.getOrNull(1) as? JsonPrimitive)?.content.orEmpty())
                }
                else -> null
            }
        }

        private fun idOf(args: JsonArray): String? =
            (args.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
    }
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
 * `user-questions/request` 的回答结果。
 *
 * ## 与审批的区别
 * 审批只有四种 `ApprovalOutcome`，提问的回答是**业务值**：服务端把 `value`
 * 原样交给等着的 `ask_user_question` 工具，形状是
 * `{answers:[{id, selected:string[], custom?}]}`（`userQuestions/answer` 的
 * `answer` 参数 schema 逐字抄来）。
 *
 * 所以这里不用枚举，而是两个构造函数：
 *  - [answered] 正常回答；
 *  - [cancelled] 用户关掉/放弃——按桌面端 `questionError('ASK_CANCELLED')`
 *    的形状拒绝，消息文案逐字一致（`the user cancelled ask_user_question`），
 *    这样模型在桌面端与手机上看到的失败原因完全相同。
 */
sealed interface QuestionOutcome {

    /** 序列化成 `$events/result` 的 `outcome` 字段。 */
    fun wire(): JsonObject

    /** 用户给了一组答案。 */
    data class Answered(val answers: List<JsonObject>) : QuestionOutcome {
        override fun wire(): JsonObject = buildJsonObject {
            put("kind", "result")
            put("value", buildJsonObject {
                put("answers", JsonArray(answers))
            })
        }
    }

    /** 用户取消了（对应桌面端的 `ASK_CANCELLED`）。 */
    data object Cancelled : QuestionOutcome {
        override fun wire(): JsonObject = buildJsonObject {
            put("kind", "rejected")
            put("error", buildJsonObject {
                put("name", "UserQuestionError")
                put("message", "the user cancelled ask_user_question")
                put("code", "ASK_CANCELLED")
            })
        }
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

/**
 * 一条 `user-questions/request` 的 waterfall 帧携带的提问。
 *
 * ## 为什么投影之外还要接 waterfall
 * `userQuestions` 投影的 `active` 只包含**已经开始等待回答**的提问组。当
 * `agent.status === 'awaiting_user'` 时提问组已经在那儿了，投影刷新足够；
 * 但投影是轮询出来的（[SessionProjectionRepository] 有一条 2.5s 的兜底循环），
 * 而 waterfall 是**服务端主动推送**——模型刚发问的那一刻就该弹卡片。
 * 所以两条路都接：waterfall 负责「立刻弹」，投影负责「权威同步」。
 *
 * 桌面端就是这么做的（`dsh-client-ui-user-questions/lib/client.js` 的
 * `ctx.remote.$on("user-questions/request", …)`），而且它同样把
 * 「Host 投影是权威来源」写在 README 里。
 *
 * ## `wait?: {callId, timed?}`
 * 帧上的 `wait` 是**可选**的（schema 里带 `?`）。`callId` 的作用不是「能不能回答」——
 * waterfall 卡片的回答一律走 `$events/result`（见 [RemoteEventsClient.submit]），
 * 只需要 `eventId`。`callId` 的真正用途是**跨路去重**：投影里同一个提问组也会带这个
 * `callId`，两边对上就说明是同一个问题，草稿与「已回答」记账才能合到一处。
 * 所以 `callId` 允许为 null，取不到时两条路各弹一张卡（用户可能答两遍），
 * 这是已知且可接受的降级——服务端会以先到者为准。
 *
 * ## 线上形状（`dsh-api-remotes/lib/index.js` 的 `API_REMOTE_FORWARDED_EVENTS`）
 * ```
 * {"type":"waterfall","event":"user-questions/request",
 *  "eventId":"…","agentId":"session-…",
 *  "request":{"questions":[{id,question,detail?,header?,options?,multiSelect?,intent?}],
 *            "wait":{"callId":"call_…"}}}
 * ```
 */
data class QuestionRequest(
    val eventId: String,
    val agentId: String?,
    val callId: String?,
    val questions: List<AskQuestion>,
) {

    /** 这个提问是不是属于 [sessionId] 这个顶层会话（同 [ApprovalRequest.belongsTo]）。 */
    fun belongsTo(sessionId: String): Boolean = agentId == sessionId

    companion object {

        /**
         * 从 waterfall 帧的 `request` 解析。
         *
         * 宽容：`questions` 缺失/为空、或全部解析失败时返回 null——那就不弹卡片，
         * 只让投影刷新去兜底。
         *
         * 两条上限都是**故意**的：
         * - [MAX_QUESTIONS]：`handle()` 跑在 `$events` 流的唯一收集协程上，
         *   超长数组会把后面的审批帧堵住（头阻塞）。
         * - 题内 `id` 去重：草稿以 `id` 为键，重复 id 会让两道题共用一份草稿。
         */
        fun parse(eventId: String, agentId: String?, request: JsonObject?): QuestionRequest? {
            val obj = request ?: return null
            val raw = (obj["questions"] as? kotlinx.serialization.json.JsonArray) ?: return null
            val questions = raw.take(MAX_QUESTIONS).mapNotNull { element ->
                (element as? JsonObject)?.let { AskQuestion.parse(it) }
            }.distinctBy { it.id }
            if (questions.isEmpty()) return null
            val wait = obj["wait"] as? JsonObject
            val callId = (wait?.get("callId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (obj["callId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return QuestionRequest(
                eventId = eventId,
                agentId = agentId,
                callId = callId,
                questions = questions,
            )
        }

        /** 单条 waterfall 帧最多接住的题数。超出部分丢弃（投影路仍是权威来源）。 */
        const val MAX_QUESTIONS = 16
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
