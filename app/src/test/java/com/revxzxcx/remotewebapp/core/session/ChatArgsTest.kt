package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatArgs] 的单测 —— **本项目最容易出错的地方**。
 *
 * 为什么这些断言值得写：Typert 是严格 schema，参数 wire 名拼错不会被「宽容处理」，而是直接
 * `gateway/arguments-invalid: args fields do not match the descriptor: missing "x"; unexpected "y"`。
 * M1/M2 已踩过两次（`session/list` 用 `_request` 而非 `request`；时间字段线上是 ISO 字符串）。
 * 这里把四个 chat 方法的形状钉死，改错立刻红灯。
 *
 * 期望值来自 `dsh-api-session-controller/lib/typert.remote-client.js` 的 descriptor
 * （每个参数的 `name` 与 `wire`）以及各参数的 zod schema。
 *
 * 注意这些测试打的是**生产代码** [ChatArgs]（不是测试里复刻的一份），所以它真能拦住回归。
 */
class ChatArgsTest {

    @Test
    fun `follow 的顶层键恰好是 request`() {
        // 多一个键服务端就报 unexpected；少一个就报 missing。
        assertEquals(setOf("request"), ChatArgs.follow("session-1").keys)
    }

    @Test
    fun `follow 的 request 形状`() {
        val request = ChatArgs.follow("session-abc").obj("request")
        assertEquals(setOf("address", "assistantStream", "maxMessages"), request.keys)
        val address = request.obj("address")
        assertEquals("session", address.str("kind"))
        assertEquals("session-abc", address.str("sessionId"))
        // `assistantStream` 是 z.literal(true)：必须是布尔 true，不是字符串 "true"。
        assertEquals(JsonPrimitive(true), request["assistantStream"])
        assertEquals(50, request.int("maxMessages"))
    }

    @Test
    fun `follow 的 maxMessages 可覆盖`() {
        val request = ChatArgs.follow("s", maxMessages = 200).obj("request")
        assertEquals(200, request.int("maxMessages"))
    }

    @Test
    fun `page 的 request 形状`() {
        val request = ChatArgs.page("session-1", throughSeq = 42, beforeSeq = 7).obj("request")
        assertEquals(setOf("address", "throughSeq", "beforeSeq", "maxMessages"), request.keys)
        assertEquals(42L, request.long("throughSeq"))
        assertEquals(7L, request.long("beforeSeq"))
        assertEquals("session-1", request.obj("address").str("sessionId"))
    }

    @Test
    fun `page 的 throughSeq 允许 -1 表示到日志头`() {
        // 官方 `validatePageRequest`：throughSeq 必须 >= -1（-1 = 到日志头）。
        val request = ChatArgs.page("s", throughSeq = -1, beforeSeq = 0).obj("request")
        assertEquals(-1L, request.long("throughSeq"))
    }

    @Test
    fun `prompt 的 request 形状`() {
        val request = ChatArgs.prompt("req-1", "session-1", "你好").obj("request")
        assertEquals(setOf("requestId", "sessionId", "mode", "content"), request.keys)
        assertEquals("req-1", request.str("requestId"))
        assertEquals("session-1", request.str("sessionId"))
        assertEquals("queue", request.str("mode"))
    }

    @Test
    fun `prompt 的 content 是单个文本块数组`() {
        val content = ChatArgs.prompt("r", "s", "你好")["request"].let { it as JsonObject }["content"] as JsonArray
        assertEquals(1, content.size)
        val block = content[0] as JsonObject
        assertEquals("text", block.str("type"))
        assertEquals("你好", block.str("text"))
    }

    @Test
    fun `prompt 默认是 queue 模式`() {
        assertEquals("queue", ChatArgs.prompt("r", "s", "x").obj("request").str("mode"))
    }

    @Test
    fun `prompt 的 steer 模式`() {
        val request = ChatArgs.prompt("r", "s", "插话", ChatArgs.PromptMode.Steer).obj("request")
        assertEquals("steer", request.str("mode"))
    }

    @Test
    fun `prompt 不带 clientTimeZone 时不出现该键`() {
        // 未定义的可选参数不能发送，否则服务端报 unexpected。
        val request = ChatArgs.prompt("r", "s", "x", clientTimeZone = null).obj("request")
        assertFalse(request.containsKey("clientTimeZone"))
    }

    @Test
    fun `prompt 带 clientTimeZone 时出现该键`() {
        val request = ChatArgs.prompt("r", "s", "x", clientTimeZone = "Asia/Shanghai").obj("request")
        assertEquals("Asia/Shanghai", request.str("clientTimeZone"))
    }

    @Test
    fun `prompt 带图片附件时 content 追加 image 块`() {
        // 内联图片：data 是 base64，mediaType 与 name 按桌面端 descriptor 逐字传递。
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "看这张",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    name = "a.png",
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val content = args.obj("request").arr("content")
        assertEquals(2, content.size)
        val image = content[1] as JsonObject
        assertEquals("image", image.str("type"))
        assertEquals("AAAA", image.str("data"))
        assertEquals("image/png", image.str("mediaType"))
        assertEquals("a.png", image.str("name"))
    }

    @Test
    fun `prompt 带文件附件时 content 追加 file 块且只有 receiptId`() {
        // 文件是两步：先 `attachment/upload` 换回执，prompt 只带 receiptId。
        // 多带 name/mediaType 会让服务端 `assertExactArguments` 报 unexpected。
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.FILE,
                    name = "notes.md",
                    receiptId = "rcpt-1",
                ),
            ),
        )
        val content = args.obj("request").arr("content")
        assertEquals(1, content.size)
        val file = content[0] as JsonObject
        assertEquals(setOf("type", "receiptId"), file.keys)
        assertEquals("rcpt-1", file.str("receiptId"))
    }

    @Test
    fun `prompt 的图片附件缺 name 时不出现该键`() {
        // `mediaType` 在 descriptor 里是必需键，所以始终发送；只有 `name` 可缺席
        // （空白名也按缺席处理，省得服务端收到一个空文件名）。
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    name = null,
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val image = args.obj("request").arr("content")[0] as JsonObject
        assertTrue(image.containsKey("mediaType"))
        assertEquals("image/png", image.str("mediaType"))
        assertFalse(image.containsKey("name"))
        assertEquals("AAAA", image.str("data"))
    }

    @Test
    fun `prompt 的图片附件名是空白时也按缺席处理`() {
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    name = "   ",
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val image = args.obj("request").arr("content")[0] as JsonObject
        assertFalse(image.containsKey("name"))
    }

    @Test
    fun `prompt 的文件附件缺 receiptId 时整块被跳过`() {
        // 上传失败时它不会进待发送列表；万一进来了，发一个没有 receiptId 的 file 块
        // 只会换来服务端报错，不如直接不发。
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "",
            attachments = listOf(
                PromptAttachment(kind = PromptAttachment.Kind.FILE, receiptId = null),
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val content = args.obj("request").arr("content")
        // 空文本块被省略：服务端 `hasPromptContent` 认「附件」这一条，
        // 塞一个空 text 只会在用户消息里渲染成空气泡。
        assertEquals(1, content.size)
        assertEquals("image", (content[0] as JsonObject).str("type"))
    }

    @Test
    fun `prompt 只有附件没有文字时 content 里没有空文本块`() {
        // 「只发图不发字」是常见用法，服务端 `hasPromptContent`
        // （dsh-api-session-controller/lib/index.js:649-651）明确接受。
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "   ",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val content = args.obj("request").arr("content")
        assertEquals(1, content.size)
        assertEquals("image", (content[0] as JsonObject).str("type"))
    }

    @Test
    fun `prompt 有文字也有附件时文本块仍在最前`() {
        val args = ChatArgs.prompt(
            requestId = "r",
            sessionId = "s",
            text = "看这张",
            attachments = listOf(
                PromptAttachment(
                    kind = PromptAttachment.Kind.IMAGE,
                    mediaType = "image/png",
                    data = "AAAA",
                ),
            ),
        )
        val content = args.obj("request").arr("content")
        assertEquals(2, content.size)
        assertEquals("text", (content[0] as JsonObject).str("type"))
        assertEquals("image", (content[1] as JsonObject).str("type"))
    }

    @Test
    fun `cancel 的 request 只有 sessionId`() {
        val args = ChatArgs.cancel("session-xyz")
        assertEquals(setOf("request"), args.keys)
        val request = args.obj("request")
        assertEquals(setOf("sessionId"), request.keys)
        assertEquals("session-xyz", request.str("sessionId"))
    }

    @Test
    fun `四个 chat 方法都用 request 而不是 _request`() {
        // 与 `session/list` 的关键区别：只有 list 用下划线前缀（M2 实测）。
        listOf(
            ChatArgs.follow("s"),
            ChatArgs.page("s", 1, 0),
            ChatArgs.prompt("r", "s", "x"),
            ChatArgs.cancel("s"),
        ).forEach { args ->
            assertTrue("顶层键应含 request：$args", args.containsKey("request"))
            assertFalse("不应含 _request：$args", args.containsKey("_request"))
        }
    }

    @Test
    fun `PromptMode 的 wire 值与服务端字面量一致`() {
        // 官方 schema：z.union([z.literal("queue"), z.literal("steer")])。
        assertEquals("queue", ChatArgs.PromptMode.Queue.wire)
        assertEquals("steer", ChatArgs.PromptMode.Steer.wire)
    }

    @Test
    fun `默认 maxMessages 与服务端默认值一致`() {
        // history.js:59 DEFAULT_MAX_MESSAGES = 50。
        assertEquals(50, ChatArgs.DEFAULT_MAX_MESSAGES)
    }

    @Test
    fun `address 用 session 形态而不是 subagent`() {
        // 子代理地址需要 parentSessionId/childSessionId/mode，M3 不支持；
        // 用错会被服务端回 session/agent-busy 或 subagent/unauthorized。
        val address = ChatArgs.follow("s").obj("request").obj("address")
        assertEquals(setOf("kind", "sessionId"), address.keys)
    }

    @Test
    fun `selectModel 的 request 形状`() {
        // 实测（F:\bot\_model_probe.mjs）：{sessionId, provider, model, reasoningEffort?}
        val request = ChatArgs.selectModel(
            sessionId = "s1",
            provider = "deepseek",
            model = "deepseek-reasoner",
            reasoningEffort = "high",
        ).obj("request")
        assertEquals(setOf("sessionId", "provider", "model", "reasoningEffort"), request.keys)
        assertEquals("s1", request.str("sessionId"))
        assertEquals("deepseek", request.str("provider"))
        assertEquals("deepseek-reasoner", request.str("model"))
        assertEquals("high", request.str("reasoningEffort"))
    }

    @Test
    fun `selectModel 不传推理档时整个键都不出现`() {
        // 服务端是严格 schema：多一个键就是 `unexpected "reasoningEffort"`。
        // 没有 reasoning.efforts 的模型必须走这条路径。
        val request = ChatArgs.selectModel("s1", "p", "m").obj("request")
        assertEquals(setOf("sessionId", "provider", "model"), request.keys)
        assertFalse(request.containsKey("reasoningEffort"))
    }

    @Test
    fun `selectModel 的空白推理档等同于没传`() {
        // 服务端可能回空字符串；传上去不如不传（省得踩 unexpected）。
        val request = ChatArgs.selectModel("s1", "p", "m", "  ").obj("request")
        assertFalse(request.containsKey("reasoningEffort"))
    }

    @Test
    fun `modelCatalog 没有任何参数`() {
        // 源码签名 modelCatalog(): Promise<ModelCatalog> —— args 恒为空对象。
        assertEquals(emptySet<String>(), ChatArgs.emptyArgs.keys)
    }

    /**
     * 目标族（`goals` 下的三个动词）的参数形状与 chat 族**不同**（R031 设备验收抓到的缺陷）。
     *
     * 首版照 chat 惯例套了 `{request:{sessionId, ref}}`，服务端原文回：
     * `goals/pause: args fields do not match the descriptor: missing "agentId", "ref";
     * unexpected "request"`。即：会话 id 叫 `agentId`、ref 在顶层、没有 `request` 外层。
     * 名字写错在这里就红，不必等真机。
     */
    @Test
    fun `目标动词用顶层 agentId 与 ref 而不是 request`() {
        val args = ChatArgs.goalVerb("session-1", "goal-9", 3L)
        assertEquals(setOf("agentId", "ref"), args.keys)
        assertEquals("session-1", args.str("agentId"))
        assertFalse("不能有 request 外层", args.containsKey("request"))
        assertFalse("不能有 sessionId", args.containsKey("sessionId"))
        assertEquals(setOf("id", "revision"), args.obj("ref").keys)
        assertEquals("goal-9", args.obj("ref").str("id"))
        assertEquals(3L, args.obj("ref").long("revision"))
    }

    @Test
    fun `编辑目标多一层 request 只装 objective`() {
        val args = ChatArgs.editGoal("session-1", "goal-9", 4L, "新目标")
        assertEquals(setOf("agentId", "ref", "request"), args.keys)
        assertEquals("session-1", args.str("agentId"))
        assertEquals("goal-9", args.obj("ref").str("id"))
        // 只有 objective 可改：maxGoalRounds 是创建时参数，DSH 编辑接口不收。
        assertEquals(setOf("objective"), args.obj("request").keys)
        assertEquals("新目标", args.obj("request").str("objective"))
    }

    @Test
    fun `chat 方法都用 request 包裹`() {
        listOf(
            ChatArgs.follow("s"),
            ChatArgs.page("s", 1, 0),
            ChatArgs.prompt("r", "s", "x"),
            ChatArgs.cancel("s"),
            ChatArgs.selectModel("s", "p", "m"),
            ChatArgs.selectModel("s", "p", "m", "low"),
        ).forEach { args ->
            assertTrue("顶层键应含 request：$args", args.containsKey("request"))
            assertFalse("不应含 _request：$args", args.containsKey("_request"))
        }
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject

    private fun JsonObject.arr(key: String): JsonArray = this[key] as JsonArray

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}
