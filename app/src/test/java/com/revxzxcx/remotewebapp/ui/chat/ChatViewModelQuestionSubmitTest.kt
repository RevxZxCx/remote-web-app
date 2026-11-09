package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 钉住「提交被挡下时游标回到第一道没答案的题」这条行为（R058 审查 P1）。
 *
 * ## 为什么必须在 ViewModel 层测
 *
 * 这条逻辑在 [ChatViewModel.submitQuestionGroup] 里：算完 `unansweredCount` 发现缺答案，
 * 先调 `ChatReducer.placeQuestionCursor(index = firstUnansweredIndex(...))` 把游标挪回去，
 * 再报错。`ChatReducer` 那两个函数各自有单测，但**把它们接起来的那一行**在 ViewModel 里，
 * 删掉它 reducer 的单测一条都不会红——变异验证实测正是如此（M17 SURVIVED）。
 *
 * 旧实现里「跳到第一道未答」是 `currentIndex()` 用 `indexOfFirst { !isAnswered }` 顺带
 * 得到的副作用；B010 把游标改成显式状态之后，这个能力必须由提交入口主动补上。删掉它，
 * 用户滑到第 3 题点提交，看到的是已经答好的第 3 题 + 一句「还有 1 道题没有答案」，
 * 既不知道是哪道、也没有跳转入口，只能自己一题题翻回去找。
 *
 * ## 怎么搭出「真的有待答问题」的 ViewModel
 *
 * [ChatViewModel] 的构造参数只有 connection，投影走 `session/projections` 这条 unary。
 * 所以这里起一个裸 TCP 服务端：unary 请求回一份固定的投影信封（三道题），
 * WebSocket 一律 403（与 `ChatViewModelStreamFailureTest` 同一套 harness，
 * 那边已经证明 403 不会让用例挂死）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQuestionSubmitTest {

    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-vm-question-main").apply { isDaemon = true }
    }

    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    private val scopes = mutableListOf<CoroutineScope>()

    private var server: ServerSocket? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
        runCatching { server?.close() }
    }

    @Test
    fun `提交被挡下时游标回到第一道没答案的题`() = runBlocking {
        val (socket, port) = projectionServer()
        server = socket

        val connection = connectionFor(port)
        val viewModel = ChatViewModel(connection, SESSION)

        val group = awaitOn(8_000) { viewModel.questions.value.firstOrNull() }
            ?: error("8 秒内没读到提问投影，测试前提不成立（questionError=${viewModel.questionError.value}）")
        assertEquals("投影里应该是三道题", 3, group.questions.size)

        // 只答后两道，把第一道留空——这样「第一道没答案」的下标是 0，
        // 而用户当前所在的第 3 题下标是 2，两者不同才能分辨游标有没有被挪回去。
        viewModel.toggleQuestionOption(SESSION, group.questions[1], "红")
        viewModel.toggleQuestionOption(SESSION, group.questions[2], "是")
        assertEquals(
            "第一道没有预设选项，铺过默认草稿后它仍然是未答，实际=${viewModel.state.value.questionDrafts[group.callId]?.get("q1")}",
            true,
            viewModel.state.value.questionDrafts[group.callId]?.get("q1")?.isAnswered != true,
        )

        // 导航到第 3 题。
        viewModel.stepQuestion(group.callId, 1)
        viewModel.stepQuestion(group.callId, 1)
        assertEquals(
            "导航两下之后游标应该在第 3 题",
            2,
            viewModel.state.value.questionIndex[group.callId],
        )

        viewModel.submitQuestionGroup(group.callId)

        assertTrue(
            "缺答案时必须报错并说明还差几道，实际是 ${viewModel.questionError.value}；" +
                "草稿=${viewModel.state.value.questionDrafts[group.callId]}",
            viewModel.questionError.value.orEmpty().contains("还有 1 道题没有答案"),
        )
        assertEquals(
            "提交被挡下后游标必须回到第一道没答案的题（索引 0），否则用户不知道是哪道",
            0,
            viewModel.state.value.questionIndex[group.callId],
        )
    }

    private fun connectionFor(port: Int): DshConnection {
        val http = OkHttpClient.Builder().build()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DshConnection(
            initialCredential = DeviceCredential(
                baseUrl = "http://127.0.0.1:$port",
                deviceId = "dev-42",
                pairedAt = 0L,
                label = "test",
            ),
            unaryHttp = http,
            streamHttp = http,
            json = DshJson,
            scope = appScope,
        )
    }

    private suspend fun <T : Any> awaitOn(timeoutMs: Long, block: () -> T?): T? =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                block()?.let { return@withContext it }
                Thread.sleep(25)
            }
            null
        }

    /** 起一个只回投影的裸 TCP 服务端（unary 回信封，WebSocket 一律 403）。 */
    private fun projectionServer(): Pair<ServerSocket, Int> {
        val serverSocket = ServerSocket(0)
        val acceptor = Thread {
            runCatching {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        return serverSocket to serverSocket.localPort
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val header = readHeader(input)
            if (Regex("Sec-WebSocket-Key:\\s*(\\S+)").find(header) == null) {
                val body = readBody(input, header)
                val rpcId = Regex("\"rpcId\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    ?.groupValues?.get(1).orEmpty()
                writeHttp(client, 200, envelope(rpcId))
            } else {
                writeHttp(client, 403, "")
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun envelope(rpcId: String): String =
        """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"values":""" +
            """{"userQuestions":{"active":[{"callId":"$CALL_ID","state":"open",""" +
            """"questions":[$Q1,$Q2,$Q3]}]}}}}}"""

    private fun readBody(input: InputStream, header: String): String {
        val length = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun readHeader(input: InputStream): String {
        val header = StringBuilder()
        while (!header.takeLast(4).toString().endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            header.append(b.toChar())
        }
        return header.toString()
    }

    private fun writeHttp(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        client.getOutputStream().write(
            (
                "HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
    }

    private companion object {
        const val SESSION = "session-1"
        const val CALL_ID = "call-1"

        // 第一道**刻意不给预设选项**：`defaultOf` 会预选第一个选项，而「铺过默认草稿」
        // 就等于「已答」（B010 的病灶语义），那样第一道从一开始就算答过了，
        // 就造不出「第一道没答案、用户在第三道」这个前置条件。
        const val Q1 = """{"id":"q1","question":"第一题","options":[]}"""
        const val Q2 = """{"id":"q2","question":"第二题","options":[{"label":"红"},{"label":"蓝"}]}"""
        const val Q3 = """{"id":"q3","question":"第三题","options":[{"label":"是"},{"label":"否"}]}"""
    }
}
