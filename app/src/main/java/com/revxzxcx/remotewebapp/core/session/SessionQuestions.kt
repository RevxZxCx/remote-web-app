package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模型向用户提问（`userQuestions`）与权限预设（`permissions`）两个投影的线级模型。
 *
 * 这两个投影只通过两条路可读：
 * 1. unary `session/projections {request:{sessionId}}` → `{asOfSeq, values}`（或 `null`）；
 * 2. stream `session/control` 的 `projection` 帧（baseline 只含桌面端当前选中会话，所以不能只靠它）。
 *
 * wire 形状由 `dsh-api-user-questions/lib/types/record.js` 与 `typert.remote-client.js:92-150`
 * 逐字抄来，字段全部可选（服务端会加字段，客户端不能崩）。
 */
data class AskOption(
    val label: String,
    val description: String? = null,
)

data class AskIntent(
    /** `'plan-review'` 表示这是「计划确认」类提问，UI 走紧凑审批卡片。 */
    val kind: String,
    /** plan-review 的批准文案（「同意执行」）。 */
    val approve: String? = null,
    val callId: String? = null,
)

data class AskQuestion(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    /** 多选。缺省视为单选：单选点一下即前进。 */
    val multiSelect: Boolean = false,
    val intent: AskIntent? = null,
) {
    val isPlanReview: Boolean get() = intent?.kind == INTENT_PLAN_REVIEW

    /**
     * plan-review 的「同意执行」按钮文案；缺省给桌面端的默认值。
     *
     * **已知缺口**：桌面端规格里 plan-review 还有第二个动作「要求修改」（以
     * `ASK_CANCELLED` 拒绝等待），本 App 没有实现它——投影路（`userQuestions/answer`）
     * 只有「给答案」一个入口，没有拒绝语义，而 waterfall 路的拒绝形状尚未在真机验证过。
     * 与其发一个猜出来的 outcome，不如先只提供「跳过」（发空答案）与「同意执行」。
     */
    val approveLabel: String get() = intent?.approve?.takeIf { it.isNotBlank() } ?: DEFAULT_APPROVE_LABEL

    companion object {
        const val INTENT_PLAN_REVIEW = "plan-review"
        const val DEFAULT_APPROVE_LABEL = "同意执行"

        /**
         * 从一段 JSON 解析一道题；解析不出来返回 null。
         *
         * 给 `core/events/RemoteEvents.kt` 的 `QuestionRequest.parse` 用——waterfall 帧
         * 里的 `questions` 与投影里的 `active[].questions` 是同一套 wire 形状，
         * 解析规则只能有一份。
         */
        fun parse(element: JsonElement?): AskQuestion? = SessionProjectionParser.questionPublic(element)
    }
}

data class AskAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

/** 一次提问请求 = 一个 `callId` 下的若干道题。 */
data class QuestionSet(
    val callId: String,
    val questions: List<AskQuestion>,
    /** `'open'` / `'continued'`。 */
    val state: String = STATE_OPEN,
) {
    val isOpen: Boolean get() = state != STATE_CLOSED

    companion object {
        const val STATE_OPEN = "open"
        const val STATE_CONTINUED = "continued"
        const val STATE_CLOSED = "closed"
    }
}

data class UserQuestionsSnapshot(
    val active: List<QuestionSet> = emptyList(),
    /** 已落定的 `callId` 集合，用来判断「这一组已经被别人答掉了」。 */
    val settledCallIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = UserQuestionsSnapshot()
    }
}

/**
 * 一道题的**未提交草稿**。
 *
 * 桌面端 `dsh-client-ui-user-questions` 的行为（`README.md` 逐条对应）：
 * 首个带推荐标记的选项预先选中；单选点一下即前进；多选草稿保留已选标签；自定义答案
 * 与选项互斥（单选）/ 可同时带（多选）。草稿不写进服务端，只在客户端内存里。
 */
data class QuestionDraft(
    val selected: Set<String> = emptySet(),
    val custom: String = "",
) {
    val hasCustom: Boolean get() = custom.isNotBlank()
    val isAnswered: Boolean get() = selected.isNotEmpty() || hasCustom
}

/**
 * 输入框被「需要用户回应」的请求占用时的三种形态。
 *
 * 桌面端的规格是**卡片暂时占用编辑器位置**（`README.md` 原话），所以这里做成
 * 「同一个输入区换内容」而不是「在消息列表里插一条横幅 + 保留输入框」：
 * 后者会让用户在还有权限请求没答的时候把新消息发出去。
 */
sealed interface InteractiveComposerRequest {
    /**
     * 同一时刻只可能有一个。
     *
     * 优先级是**审批 > 提问**（见 `ChatViewModel.interactive` 的 KDoc）：审批卡住的是
     * 正在执行的工具，是安全闸门；提问只是等一个输入。
     */
    val token: String

    /** 敏感工具的权限审批（`approval/request`，走 `$events` 流）。 */
    data class Approval(
        val eventId: String,
        val toolName: String,
        val reason: String?,
    ) : InteractiveComposerRequest {
        override val token: String get() = eventId
    }

    /**
     * 模型提问（`userQuestions` 投影的 open 组）。
     *
     * [drafts] 的键是题目 id，值是未提交草稿。空表表示还没初始化——UI 见到空表就用
     * [QuestionDraft.default]（第一个选项预选）铺一层。
     */
    data class Question(
        val callId: String,
        val questions: List<AskQuestion>,
        val drafts: Map<String, QuestionDraft> = emptyMap(),
    ) : InteractiveComposerRequest {
        override val token: String get() = callId

        /** 当前题：第一道没有草稿的题；全部有草稿就停在最后一道。 */
        fun currentIndex(): Int {
            val index = questions.indexOfFirst { drafts[it.id]?.isAnswered != true }
            return if (index < 0) questions.lastIndex.coerceAtLeast(0) else index
        }

        fun draftOf(questionId: String): QuestionDraft =
            drafts[questionId] ?: InteractiveComposerRequest.defaultOf(
                questions.firstOrNull { it.id == questionId } ?: AskQuestion(id = questionId, question = ""),
            )
    }

    companion object {
        /** 桌面端的「推荐标记」就是第一个选项，所以预选第一个。 */
        fun defaultOf(question: AskQuestion): QuestionDraft =
            QuestionDraft(selected = setOfNotNull(question.options.firstOrNull()?.label))
    }
}

data class PermissionSnapshot(
    /** 桌面端的预设名（`danger-full-access` / `workspace-write` / `custom` / …），未知时 null。 */
    val preset: String? = null,
) {
    val isUnknown: Boolean get() = preset == null

    companion object {
        val EMPTY = PermissionSnapshot()

        /** 预设名 → 给人看的中文短标签。 */
        fun labelOf(preset: String?): String? = when (preset) {
            "danger-full-access" -> "完全访问"
            "workspace-write" -> "可写工作区"
            "workspace-readonly", "read-only" -> "只读"
            "custom" -> "自定义"
            else -> preset
        }
    }
}

/**
 * 宽容解析 `session/projections` / `session/control` 帧里的两个投影。
 *
 * 与全工程一致：任何字段缺失都退回默认值，绝不抛异常——服务端随时会加字段。
 */
internal object SessionProjectionParser {

    fun questions(element: JsonElement?): UserQuestionsSnapshot {
        val obj = element as? JsonObject ?: return UserQuestionsSnapshot.EMPTY
        val active = obj.array("active").mapNotNull { entry ->
            val set = entry as? JsonObject ?: return@mapNotNull null
            val callId = set.string("callId") ?: return@mapNotNull null
            val questions = set.array("questions").mapNotNull { question(it) }
            QuestionSet(
                callId = callId,
                questions = questions,
                state = set.string("state") ?: QuestionSet.STATE_OPEN,
            )
        }
        val settled = obj.array("settled").mapNotNull { entry ->
            (entry as? JsonObject)?.string("callId")
        }.toSet()
        return UserQuestionsSnapshot(active = active, settledCallIds = settled)
    }

    fun permission(element: JsonElement?): PermissionSnapshot {
        val obj = element as? JsonObject ?: return PermissionSnapshot.EMPTY
        return PermissionSnapshot(preset = obj.string("currentValue")?.takeIf { it.isNotBlank() })
    }

    private fun question(element: JsonElement?): AskQuestion? = questionPublic(element)

    /** [question] 的公开入口（给 `core/events` 的 waterfall 解析用）。 */
    internal fun questionPublic(element: JsonElement?): AskQuestion? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        val text = obj.string("question") ?: return null
        val options = obj.array("options").mapNotNull { option(it) }
        return AskQuestion(
            id = id,
            question = text,
            detail = obj.string("detail"),
            header = obj.string("header"),
            options = options,
            multiSelect = obj.bool("multiSelect") ?: false,
            intent = intent(obj["intent"]),
        )
    }

    private fun option(element: JsonElement?): AskOption? {
        val obj = element as? JsonObject ?: return null
        val label = obj.string("label") ?: return null
        return AskOption(label = label, description = obj.string("description"))
    }

    private fun intent(element: JsonElement?): AskIntent? {
        val obj = element as? JsonObject ?: return null
        val kind = obj.string("kind") ?: return null
        return AskIntent(
            kind = kind,
            approve = obj.string("approve"),
            callId = obj.string("callId"),
        )
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value.content == "null") return null
        return value.content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}

/**
 * [AskAnswer] → wire JSON。`outcome.value` 的 `answers` 数组就是这个形状
 * （桌面端 `client.js:936`）。
 *
 * `custom` 空白时不发这个键——服务端 schema 是 `custom:string().optional()`，
 * 发空串会被当成「用户写了一串空白」而不是「没写自定义答案」。
 */
internal fun List<AskAnswer>.wire(): List<JsonObject> = map { answer ->
    buildJsonObject {
        put("id", JsonPrimitive(answer.id))
        put("selected", JsonArray(answer.selected.map { JsonPrimitive(it) }))
        answer.custom?.takeIf { it.isNotBlank() }?.let { put("custom", JsonPrimitive(it)) }
    }
}

/** `session/follow` 快照里也带 `userQuestions` 投影，用同一个解析器。 */
fun userQuestionsOf(projections: JsonObject?): UserQuestionsSnapshot? {
    if (projections == null) return null
    if (!projections.containsKey(KEY_USER_QUESTIONS)) return null
    return SessionProjectionParser.questions(projections[KEY_USER_QUESTIONS])
}

fun permissionOf(projections: JsonObject?): PermissionSnapshot? {
    if (projections == null) return null
    if (!projections.containsKey(KEY_PERMISSIONS)) return null
    return SessionProjectionParser.permission(projections[KEY_PERMISSIONS])
}

/** 投影键名。 */
const val KEY_USER_QUESTIONS = "userQuestions"
const val KEY_PERMISSIONS = "permissions"
