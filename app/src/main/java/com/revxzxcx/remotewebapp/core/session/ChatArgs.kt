package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 聊天相关 RPC 的**参数构造**，与网络无关，因此可以脱离 [ChatRepository] 单独测。
 *
 * 为什么要把它们抽出来：参数 wire 名是本项目最容易出错的地方。Typert 是严格 schema，
 * 名字拼错不会被宽容处理，而是直接
 * `gateway/arguments-invalid: args fields do not match the descriptor: missing "x"; unexpected "y"`。
 * M1/M2 已踩过两次（`session/list` 用 `_request` 而非 `request`；`assistantStream` 只接受
 * 字面量 `true`）。把它们做成纯函数，`ChatArgsTest` 就能不建网络连接直接钉死形状。
 *
 * 依据（`dsh-api-session-controller/lib/typert.remote-client.js` 的 descriptor）：
 * | 方法 | 参数 name/wire | 参数 schema |
 * | --- | --- | --- |
 * | `session/follow` | `request` | `{address, assistantStream?: literal(true), maxMessages?, turnWindow?}` |
 * | `session/page` | `request` | `{address, throughSeq, beforeSeq?, maxMessages?, turnWindow?}` |
 * | `session/prompt` | `request` | `{requestId, sessionId, mode:'queue'\|'steer', content, clientTimeZone?}` |
 * | `session/cancel` | `request` | `{sessionId}` |
 * | `session/selectModel` | `request` | `{sessionId, provider, model, reasoningEffort?}` |
 * | `session/modelCatalog` | **无参数** | `{}` |
 * | `session/attachment` | 两个参数 | `{sessionId, attachmentId}` |
 * | `fileUploads/upload` | `agentId` + `request` | `{agentId, request:{data, name?}}` |
 */
object ChatArgs {

    /** 服务端默认窗口（`history.js:59` `DEFAULT_MAX_MESSAGES = 50`）。 */
    const val DEFAULT_MAX_MESSAGES: Int = 50

    /** `session/prompt` 的投递方式。 */
    enum class PromptMode(val wire: String) {
        /** 排到当前轮之后（`mode:'queue'`）。 */
        Queue("queue"),

        /** 插进当前轮（`mode:'steer'`）。 */
        Steer("steer"),
    }

    /**
     * `session/follow` 的 args。
     *
     * `assistantStream` 是 `z.literal(true)`——只能传 `true` 或不传，传 `false` 会被拒，
     * 所以这里**恒为 true** 而不是做成参数。
     */
    fun follow(sessionId: String, maxMessages: Int = DEFAULT_MAX_MESSAGES): JsonObject = request {
        put("address", sessionAddress(sessionId))
        put("assistantStream", JsonPrimitive(true))
        put("maxMessages", JsonPrimitive(maxMessages))
    }

    /**
     * `session/page` 的 args。
     *
     * @param throughSeq 日志**尾部** seq（`session/follow` 快照的 `cursor`）。
     *   ⚠️ 实测传 `-1` 得到的是**空页**，不是「到日志头」：服务端
     *   `paginate` 里 `end = min(throughSeq + 1, beforeSeq ?? throughSeq + 1)`，
     *   `-1` 算出 `end = 0`，`slice(cut, 0)` 恒为空。`validatePageRequest` 允许 `>= -1`
     *   只是说它不报错，不代表语义是「从头开始」。**必须传真实游标。**
     * @param beforeSeq 只返回**严格早于**它的那一页。
     */
    fun page(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
    ): JsonObject = request {
        put("address", sessionAddress(sessionId))
        put("throughSeq", JsonPrimitive(throughSeq))
        put("beforeSeq", JsonPrimitive(beforeSeq))
        put("maxMessages", JsonPrimitive(maxMessages))
    }

    /**
     * `session/prompt` 的 args。
     *
     * @param attachments 追加在文字**之后**的内容块（图片/文件）。为 null 表示不带附件，
     *   此时 `content` 只含一个 `{type:'text'}` 块；空列表等价于 null。
     * @param clientTimeZone IANA 时区；为 null 时**不发送该键**（未定义的可选参数不能出现，
     *   否则服务端报 `unexpected "clientTimeZone"`）。
     */
    fun prompt(
        requestId: String,
        sessionId: String,
        text: String,
        mode: PromptMode = PromptMode.Queue,
        attachments: List<PromptAttachment>? = null,
        clientTimeZone: String? = null,
    ): JsonObject = request {
        put("requestId", JsonPrimitive(requestId))
        put("sessionId", JsonPrimitive(sessionId))
        put("mode", JsonPrimitive(mode.wire))
        put("content", promptContent(text, attachments))
        if (clientTimeZone != null) put("clientTimeZone", JsonPrimitive(clientTimeZone))
    }

    /**
     * `session/prompt` 的 `content` 数组。
     *
     * 文字块**在有附件时省略空文本**：服务端 `hasPromptContent`
     * （`dsh-api-session-controller/lib/index.js:649-651`）要求的是「非空白文本**或**
     * 一个附件」，所以「只发图不发字」合法；此时再塞一个 `{type:'text',text:""}`
     * 只会让这条用户消息在聊天里渲染成一个空气泡。附件块按 [attachments] 的顺序跟在后面。
     * 抽成独立函数是为了让「附件块长什么样」能被单测直接钉住——[PromptAttachment.toBlock]
     * 的 wire 形状是本项目新引入的出错面。
     *
     * 没有 `receiptId` 的文件块被**整块丢掉**：上传失败时它不该进待发送列表（调用方已经
     * 保证了），万一漏进来，发一个 `{type:'file',receiptId:""}` 只会换来服务端一条
     * 看不出原因的错误，比不发更糟。
     */
    fun promptContent(text: String, attachments: List<PromptAttachment>?): JsonArray {
        val attachmentBlocks = mutableListOf<JsonObject>()
        attachments?.forEach { attachment ->
            if (attachment.kind == PromptAttachment.Kind.FILE &&
                attachment.receiptId.isNullOrBlank()
            ) {
                return@forEach
            }
            attachmentBlocks.add(attachment.toBlock())
        }
        val blocks = mutableListOf<JsonObject>()
        // 一个附件都没有时仍然保留（可能为空的）文本块：`content: []` 会被服务端
        // 以 `gateway/bad-request` 拒掉，而那条路本该由调用方提前挡下。
        if (text.isNotBlank() || attachmentBlocks.isEmpty()) {
            blocks.add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                },
            )
        }
        blocks.addAll(attachmentBlocks)
        return JsonArray(blocks)
    }

    /** `session/cancel` 的 args（参数只有 `sessionId`）。 */
    fun cancel(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /** 空参数对象：`session/modelCatalog` 不接受任何键。 */
    val emptyArgs: JsonObject = buildJsonObject { }

    /**
     * `session/selectModel` 的 args。
     *
     * `reasoningEffort` 是**可选**字段，所以为 null 时**不能发送该键**——多一个键服务端
     * 一样报 `gateway/arguments-invalid: unexpected "reasoningEffort"`。实测形状（真机探针
     * `F:\bot\_model_probe.mjs`）：`{sessionId, provider, model, reasoningEffort?}`。
     */
    fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
        put("provider", JsonPrimitive(provider))
        put("model", JsonPrimitive(model))
        if (!reasoningEffort.isNullOrBlank()) {
            put("reasoningEffort", JsonPrimitive(reasoningEffort))
        }
    }

    /**
     * `session/updateQueue` 的 args——对一条**排队中**的消息做操作。
     *
     * 参数（`typert.host.js:843-862` + `_inboxp4.mjs` 实测）：`{sessionId, itemId, action}`，
     * 结果 `{accepted:true}`。`itemId` 就是 `inbox` 投影里 `UserMessage.id`
     * （服务端按 `m.id === request.itemId` 查找，`index.js:955-956`）。
     *
     * [action] 三选一：
     *  - `edit`：`{kind:'edit', content:[{type:'text',text}]}`——**只接受 text 块**，
     *    服务端 `index.js:939` 对非 text 块抛 `session/attachment-invalid` + reason
     *    `QUEUE_EDIT_NON_TEXT`，`:940` 对空白内容抛 `gateway/bad-request`。
     *  - `remove`：`{kind:'remove'}`——同时回收该消息的文件回执
     *    （`index.js:977` `fileUploads.retirePrompt(agent, source.rpcId)`）。
     *  - `steer`：`{kind:'steer'}`——**插队**，要求该条目在 `next-turn` 且会话正在运行，
     *    否则抛 `session/steer-unavailable`（`index.js:966`）。
     */
    fun updateQueue(sessionId: String, itemId: String, action: QueueAction): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
        put("itemId", JsonPrimitive(itemId))
        put(
            "action",
            buildJsonObject {
                put("kind", JsonPrimitive(action.kind))
                if (action is QueueAction.Edit) {
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(action.text))
                                },
                            ),
                        ),
                    )
                }
            },
        )
    }

    /** `session/updateQueue` 的三种操作（`QueueAction` 联合，`index.js:967-986`）。 */
    sealed interface QueueAction {
        val kind: String

        /** 改成 [text]（只支持纯文本，附件不能跟着改）。 */
        data class Edit(val text: String) : QueueAction {
            override val kind: String get() = "edit"
        }

        /** 从队列里撤掉。 */
        data object Remove : QueueAction {
            override val kind: String get() = "remove"
        }

        /** 插队：不等当前轮结束，立刻进当前轮。 */
        data object Steer : QueueAction {
            override val kind: String get() = "steer"
        }
    }

    /**
     * `session/projections` 的 args——一次性读某个会话的全部投影。
     *
     * 实测（`F:\bot\_projprobe.mjs`）：参数只有 `sessionId`，**不是** address 联合；
     * 返回 `{asOfSeq, values}`，会话不存在时返回 `null`。
     * 这是唯一能给「非桌面端当前选中会话」读投影的路（`session/control` 的 baseline
     * 只含当前选中会话，见 [SessionProjectionRepository.control] 的 KDoc）。
     */
    fun projections(sessionId: String): JsonObject = request {
        put("sessionId", JsonPrimitive(sessionId))
    }

    /**
     * `userQuestions/answer` 的 args——回答一组提问。
     *
     * 实测（`F:\bot\_uqprobe.mjs`）：args 键集**恰好**是 `{agentId, callId, answer}`
     * （`typert.remote-client.js:1680` 的 exactKeys 校验，多一个键都报
     * `gateway/arguments-invalid`）。`agentId` 在顶层会话上就是 `sessionId`。
     *
     * `answer` 是 `{answers:[{id, selected, custom?}]}`，一次提交覆盖该 `callId` 下的
     * **所有**问题；`custom` 未定义时不能出现该键（`z.string().optional()`）。
     */
    fun answerQuestions(
        sessionId: String,
        callId: String,
        answers: List<AskAnswer>,
    ): JsonObject = buildJsonObject {
        put("agentId", JsonPrimitive(sessionId))
        put("callId", JsonPrimitive(callId))
        put(
            "answer",
            buildJsonObject {
                put(
                    "answers",
                    JsonArray(
                        answers.map { answer ->
                            buildJsonObject {
                                put("id", JsonPrimitive(answer.id))
                                put("selected", JsonArray(answer.selected.map { JsonPrimitive(it) }))
                                if (!answer.custom.isNullOrBlank()) {
                                    put("custom", JsonPrimitive(answer.custom))
                                }
                            }
                        },
                    ),
                )
            },
        )
    }

    /**
     * 包一层 `{request: {...}}`。
     *
     * 五个 chat 方法的参数 wire 名**都是 `request`**（只有 `session/list` 是 `_request`）。
     * 集中在这里是为了让这条实测结论只有一个落点。
     */
    private fun request(body: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("request", buildJsonObject(body))
        }

    /** `address` 的会话形态（`{kind:'session', sessionId}`）。 */
    private fun sessionAddress(sessionId: String): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("session"))
        put("sessionId", JsonPrimitive(sessionId))
    }
}
