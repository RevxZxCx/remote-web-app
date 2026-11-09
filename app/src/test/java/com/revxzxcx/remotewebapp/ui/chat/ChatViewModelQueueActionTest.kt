package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import com.revxzxcx.remotewebapp.core.session.INBOX_NEXT_TURN
import com.revxzxcx.remotewebapp.core.session.QueueSteer
import com.revxzxcx.remotewebapp.core.session.QueuedMessage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 钉住排队消息三个动作（插队 / 编辑 / 删除）的**在途闸**（R058 第二轮审查 P2）。
 *
 * ## 为什么需要这一组
 *
 * B009 把 `queueJob?.cancel()` 换成了 `queueMutex` 串行。取消没有了，连点就会真的
 * 发两个请求：第一个成功、第二个必然被服务端拒绝，又渲染出带「重试」的英文错误条
 * —— 正是 B009 要消灭的体验，只是从插队换到了删除。所以「有 `updateQueue` 在飞」
 * 这件事必须对三个动作都成立，且必须**记得清掉**。
 *
 * 三处都只有 ViewModel 层才看得见，`ChatReducer` / `QueueSteer` 的纯函数单测覆盖不到：
 * - 闸是否只挡插队（M22 变异体：`steering && ...`）
 * - `finally` 是否清掉在途标记（M20：不清则按钮永久置灰）
 * - 插队成功后是否收掉过期的「没能插队」提示（M21：不收则与行内文案自相矛盾）
 *
 * ## 怎么测
 *
 * [ChatViewModel] 的构造参数只有 connection，但 `steerQueued` / `editQueued` /
 * `removeQueued` 都直接收 [QueuedMessage]，不必先从流里造出排队条目：手工构造一条
 * `confirmed = true` 的消息喂进去即可，请求本身走 `session/updateQueue` 这条 unary。
 * 服务端起一个裸 TCP：unary 回 `ok:true` 的信封，可以配置前 N 次插队回
 * `session/steer-unavailable`（用来造「被拒之后又成功」这个序列），WebSocket 一律 403。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQueueActionTest {

    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-vm-queue-main").apply { isDaemon = true }
    }

    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    private val scopes = mutableListOf<CoroutineScope>()

    private var server: ServerSocket? = null

    /** 还要拒绝几次插队；每拒绝一次递减。 */
    private val steerRejectionsLeft = AtomicInteger(0)

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

    /**
     * 闸必须对**所有** `updateQueue` 动作生效，不能只挡插队。
     *
     * 变异体 M22 把判断写成 `steering && message.id in pendingQueueIds`，
     * 那样删除 / 编辑连点照样会发第二个必然被拒的请求。
     */
    @Test
    fun `插队在途时编辑与删除也被拦下`() = runBlocking {
        val connection = connectionFor(startServer())
        val viewModel = ChatViewModel(connection, SESSION)
        val message = queued("item-1")

        viewModel.steerQueued(message)
        assertTrue(
            "发起插队后必须立刻登记在途标记，否则连点挡不住",
            message.id in viewModel.state.value.pendingQueueIds,
        )

        // 同一个 id 的编辑与删除此刻都在飞，必须被同一道闸拦下并给一句人话。
        viewModel.removeQueued(message)
        assertEquals(
            "删除应被在途闸拦下并提示「正在处理」，而不是发第二个请求",
            QueueSteer.NOTICE_QUEUE_BUSY,
            viewModel.attachNotice.value,
        )

        awaitPendingCleared(viewModel, "插队结束后在途标记必须清掉")
    }

    /**
     * `finally` 里必须清掉在途标记。
     *
     * 变异体 M20 只留 `sending = false`，于是 `pendingQueueIds` 永远含这个 id，
     * 插队按钮就此永久置灰，用户再也插不了队。
     */
    @Test
    fun `插队结束后在途标记被清掉`() = runBlocking {
        val connection = connectionFor(startServer())
        val viewModel = ChatViewModel(connection, SESSION)
        val message = queued("item-2")

        viewModel.steerQueued(message)
        assertTrue(
            "发起插队后必须有在途标记",
            message.id in viewModel.state.value.pendingQueueIds,
        )

        awaitPendingCleared(viewModel, "插队请求结束后 pendingQueueIds 必须回到空，否则按钮永久置灰")

        // 清掉之后应该能再点一次（这里服务端一律回成功，不代表真实插队语义）。
        viewModel.steerQueued(message)
        awaitPendingCleared(viewModel, "第二次插队同样要能走完并清掉标记")
    }

    /**
     * 插队被拒留下的提示，在**下一次插队成功**时要收掉。
     *
     * 变异体 M21 不收：行内已经显示「已插队 · 在当前轮执行」，输入区上方还挂着
     * 「这条没能插队，排队状态可能已经变了。」，两句话互相矛盾，用户不知道该信哪个。
     */
    @Test
    fun `插队成功后收掉过期的失败提示`() = runBlocking {
        steerRejectionsLeft.set(1)
        val connection = connectionFor(startServer())
        val viewModel = ChatViewModel(connection, SESSION)
        val message = queued("item-3")

        viewModel.steerQueued(message)
        awaitPendingCleared(viewModel, "第一次插队（被拒）也要清掉在途标记")
        assertEquals(
            "被拒之后要给一句中性提醒，且不能挂到 sendError 上",
            QueueSteer.NOTICE_REJECTED,
            viewModel.attachNotice.value,
        )
        assertNull("被拒不是故障，不该产生错误横幅", viewModel.state.value.sendError)

        // 第二次插队成功：过期提示必须收掉。
        viewModel.steerQueued(message)
        awaitPendingCleared(viewModel, "第二次插队要能走完")
        assertNull(
            "插队成功之后不能再挂着「没能插队」，否则与行内「已插队」自相矛盾",
            viewModel.attachNotice.value,
        )
    }

    private suspend fun awaitPendingCleared(viewModel: ChatViewModel, message: String) {
        val ok = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (viewModel.state.value.pendingQueueIds.isEmpty()) return@withContext true
                Thread.sleep(25)
            }
            false
        }
        assertTrue("$message（当前=${viewModel.state.value.pendingQueueIds}）", ok)
    }

    private fun queued(id: String): QueuedMessage = QueuedMessage(
        id = id,
        rpcId = "rpc-$id",
        text = "排队的一条",
        enqueuedAt = 1_000L,
        target = INBOX_NEXT_TURN,
        confirmed = true,
    )

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

    /** 起一个 unary 服务端：默认回成功，可配置前若干次插队回 `session/steer-unavailable`。 */
    private fun startServer(): Int {
        val serverSocket = ServerSocket(0)
        server = serverSocket
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
        return serverSocket.localPort
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val header = readHeader(input)
            if (Regex("Sec-WebSocket-Key:\\s*(\\S+)").find(header) != null) {
                // WebSocket 一律拒绝：这组用例不依赖流。
                writeHttp(client, 403, "")
                return
            }
            val body = readBody(input, header)
            val rpcId = Regex("\"rpcId\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?.groupValues?.get(1).orEmpty()
            val steering = body.contains("session/updateQueue") && body.contains("\"steer\"")
            if (steering && steerRejectionsLeft.getAndDecrement() > 0) {
                writeHttp(client, 200, errorEnvelope(rpcId))
            } else {
                writeHttp(client, 200, okEnvelope(rpcId))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun okEnvelope(rpcId: String): String =
        """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"values":{}}}}"""

    private fun errorEnvelope(rpcId: String): String =
        """{"type":"server-response","rpcId":"$rpcId","result":{"ok":false,"error":""" +
            """{"code":"session/steer-unavailable","message":"steer is not available"}}}"""

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
    }
}
