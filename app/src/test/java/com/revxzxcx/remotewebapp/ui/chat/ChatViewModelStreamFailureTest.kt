package com.revxzxcx.remotewebapp.ui.chat

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import com.revxzxcx.remotewebapp.core.protocol.CarrierState
import com.revxzxcx.remotewebapp.core.protocol.DshJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 钉住「mux 流断掉时异常不许逃出 [ChatViewModel] 的收集协程」这条不变式。
 *
 * ## 为什么需要这个文件
 *
 * 真机崩溃（OnePlus PKG110 / Android 16，用户原话「点开一个对话之后软件会自动退出」）：
 * 桌面端进程退出 → TCP 断开且**没有关闭帧** → OkHttp 读循环抛 `java.io.EOFException`
 * → `MuxListener.onFailure` → `RemoteStreamMux.failCarrier` 用**同一个 cause** 关闭所有流的
 * Channel → `callbackFlow` 把它当作**流的终止原因**抛给收集方。而 `viewModelScope` 跑在
 * `Dispatchers.Main.immediate` 上，[ChatViewModel.watchQuestions] 里的 `controlJob` 原本
 * 既没有 `.catch` 也没有 `.retryWhen` → 异常逃出协程 → `FATAL EXCEPTION: main` → 进程死。
 *
 * `RemoteStreamMuxTest` 里那条裸 socket 用例**守护不了这一层**：它把 `.catch` 写在测试
 * 自己的收集链上，删掉生产代码的 `.catch` 它照样绿。真正会杀进程的那一层是
 * **收集方**，而收集方此前零覆盖（仓库里没有 `ChatViewModelTest`）。
 *
 * ## 怎么做到「删掉生产的 `.catch` 就失败」
 *
 * `viewModelScope` 的上下文是 `SupervisorJob() + Dispatchers.Main.immediate`，里面
 * **没有** `CoroutineExceptionHandler`，于是未捕获异常会一路落到
 * `handleCoroutineException` 的最后一档：**当前线程的 `uncaughtExceptionHandler`**
 * ——在真机上就是 `RuntimeInit$KillApplicationHandler`（打出 FATAL 并杀进程）。
 *
 * 所以本用例把 Main 调度器换成**自建的单线程**调度器，并在**创建该线程时**就给它装上
 * 收集用的 `uncaughtExceptionHandler`：协程恢复都发生在这个线程上，异常一旦没被
 * `.catch` 接住就一定落进收集器 → 断言失败。`RemoteStreamMuxTest` 里那条用例是
 * 「mux 记了故障」，这条是「进程没死」，两者不能互相替代。
 *
 * ## 为什么用 [runBlocking] + 真实时间，而不是 `runTest` 虚拟时间
 *
 * 首版用了 `runTest(main)` + `advanceUntilIdle()`，**整个测试套件挂死**：
 * `retryWhen` 的 `delay(backoffMillis)` 在虚拟时间里是「立刻到期、再排一个任务」，
 * 而这条流会一直重试 → `advanceUntilIdle()` 永远推不完。本工程早有同类教训
 * （`RemoteStreamMuxTest` 的 KDoc：「涉及真实线程帧序/网络回调的测试必须 `runBlocking`」）。
 *
 * 两条用例的**分辨力**不同，都要留着：
 *  - [handshakeRejectionDoesNotEscapeTheViewModelCollectors]：`unpaired` 会让
 *    `retryWhen` 返回 `false`，异常**必然**流到 `.catch`。删掉 `.catch` 这条立刻失败。
 *  - [droppingTheMuxSocketDoesNotEscapeTheViewModelCollectors]：普通断线由
 *    `retryWhen` 退避重试接住（`EOFException` 到不了 `.catch`）。它钉的是
 *    「两层都没有时进程会死」这条底线，即真实崩溃场景。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStreamFailureTest {

    /** 收集「逃出协程、落到线程处理器」的异常。跨线程写，必须同步。 */
    private val escaped = Collections.synchronizedList(mutableListOf<Throwable>())

    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-vm-main").apply {
            isDaemon = true
            // 与真机 `RuntimeInit$KillApplicationHandler` 对应的位置：未捕获异常在这里现身。
            setUncaughtExceptionHandler { _, cause -> escaped += cause }
        }
    }

    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    private val scopes = mutableListOf<CoroutineScope>()

    private var server: ServerSocket? = null

    @Before
    fun setUp() {
        escaped.clear()
        scopes.clear()
        // `ChatViewModel.init` 就会 `viewModelScope.launch`，没有 Main 调度器会直接抛
        // `Module with the Main dispatcher had failed to initialize`。
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
        // D8：服务端 socket 必须在 tearDown 关掉，断言失败时也不泄漏 FD。
        runCatching { server?.close() }
    }

    /**
     * 握手被拒（HTTP 403 → `unpaired`）时，异常必须被 `.catch` 接住。
     *
     * 这条用例是 `.catch` 的**专属守护**：`retryWhen` 对 `unpaired` 返回 `false`，
     * 异常一定会到达 `.catch`；把 `.catch` 删掉，异常就落到 [escaped]，用例失败。
     */
    @Test
    fun handshakeRejectionDoesNotEscapeTheViewModelCollectors() = runBlocking {
        val (socket, port) = rawServer(rejectStatus = 403) { _, _ -> }
        server = socket

        val connection = connectionFor(port)
        val viewModel = ChatViewModel(connection, "session-1")

        val rejected = awaitOn(8_000) {
            (connection.mux.stats.value.carrier is CarrierState.Unpaired).takeIf { it }
        } ?: false

        assertTrue(
            "8 秒内没有观察到握手被拒，测试前提不成立（carrier=${connection.mux.stats.value.carrier}）",
            rejected,
        )
        // 给收集协程留出恢复的时间，异常若没被接住一定已经落到线程处理器上。
        delay(500)

        assertEquals(
            "异常逃出了 ChatViewModel 的收集协程 —— 真机上这就是 FATAL EXCEPTION: main（进程被杀）",
            emptyList<Throwable>(),
            escaped.toList(),
        )
        assertTrue("state 流读不到，视图模型可能已经死了", viewModel.state.value.items.isEmpty())
    }

    /**
     * 裸 TCP 断流（无关闭帧）时异常同样不许逃出收集协程。
     *
     * 这条对应**真机崩溃的原始场景**。普通断线由 `retryWhen` 的退避重试接住，
     * 所以它守护的是「`retryWhen` 与 `.catch` 两层都在」这条底线。
     */
    @Test
    fun droppingTheMuxSocketDoesNotEscapeTheViewModelCollectors() = runBlocking {
        val (socket, port) = rawServer(rejectStatus = null) { client, input ->
            // 等客户端把 open 帧发过来（说明 onOpen 已跑、socket 已登记），再断。
            runCatching { input.read(ByteArray(1024)) }
            // 不发关闭帧，直接断 TCP。
            client.close()
        }
        server = socket

        val connection = connectionFor(port)
        val viewModel = ChatViewModel(connection, "session-1")

        // 等断流真的被记成故障：此时 failCarrier 已经用该 cause 关掉了所有 Channel，
        // 收集协程也已经（或即将）拿到终止原因。
        val dropped = awaitOn(8_000) {
            connection.mux.errors.value.isNotEmpty().takeIf { it }
        } ?: false

        assertTrue(
            "8 秒内没有观察到断流，测试前提不成立（carrier=${connection.mux.stats.value.carrier}）",
            dropped,
        )
        delay(500)

        assertEquals(
            "异常逃出了 ChatViewModel 的收集协程 —— 真机上这就是 FATAL EXCEPTION: main（进程被杀）",
            emptyList<Throwable>(),
            escaped.toList(),
        )
        assertTrue("state 流读不到，视图模型可能已经死了", viewModel.state.value.items.isEmpty())
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

    /** 轮询等待，`block` 返回 null 表示还没到；契约与 `RemoteStreamMuxTest` 的同名 helper 一致。 */
    private suspend fun <T : Any> awaitOn(timeoutMs: Long, block: () -> T?): T? =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                block()?.let { return@withContext it }
                Thread.sleep(25)
            }
            null
        }

    /**
     * 起一个**裸 TCP** 服务端，accept 循环 + 每连接一个处理线程。
     *
     * ## 为什么必须是 accept 循环
     *
     * `ChatViewModel.init` 会同时发起三条 mux 流（共用**一条** WebSocket）**与**若干 unary
     * 调用（`session/projections` 首读等）。unary 走 OkHttp 的另一条 TCP 连接，而
     * 谁先连上是**竞态**的。首版只 `accept()` 一次：如果 unary 抢在前面，WebSocket
     * 那条连接永远没人接，测试前提直接不成立。
     *
     * 所以这里按请求内容分流：
     *  - 带 `Sec-WebSocket-Key` → 走 WebSocket 分支（[rejectStatus] 非 null 就回该状态码，
     *    否则回 101 再交给 [afterUpgrade]）；
     *  - 其余（unary POST）→ 回一个空信封并关闭，别让 ViewModel 挂在 30s 读超时上。
     *
     * ## 为什么不用 `mockwebserver3`
     *
     * 实测它的 `WebSocket.cancel()` / `close(1001, …)` 影响不到客户端（收不到任何回调），
     * 复现不出「桌面端进程退出」这条路径。
     *
     * 与 `RemoteStreamMuxTest.rawWebSocketServerThatDropsTheSocket` 是同一套逻辑；
     * 那边是 `private` 且只 accept 一次（它不构造 ViewModel，没有 unary 竞态），
     * 这里保留一份**有意为之**的增强副本，改动任一处时另一处要跟着看。
     */
    private fun rawServer(
        rejectStatus: Int?,
        afterUpgrade: (Socket, InputStream) -> Unit,
    ): Pair<ServerSocket, Int> {
        val serverSocket = ServerSocket(0)
        val acceptor = Thread {
            runCatching {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val handler = Thread { handleConnection(client, rejectStatus, afterUpgrade) }
                    handler.isDaemon = true
                    handler.start()
                }
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        return serverSocket to serverSocket.localPort
    }

    private fun handleConnection(
        client: Socket,
        rejectStatus: Int?,
        afterUpgrade: (Socket, InputStream) -> Unit,
    ) {
        runCatching {
            // 超时必须在 accept 之后**立刻**设：读握手头是无超时循环，客户端连上不发数据
            // 会让这个线程永久阻塞并占住一个 FD。
            client.soTimeout = 5_000
            try {
                val input = client.getInputStream()
                val header = readHeader(input)
                val key = Regex("Sec-WebSocket-Key:\\s*(\\S+)")
                    .find(header)?.groupValues?.get(1)
                if (key == null) {
                    // 普通 unary 请求：回一个合法信封，避免 30s 读超时拖慢用例。
                    writeHttp(
                        client,
                        200,
                        """{"type":"server-response","result":{"ok":true,"value":null}}""",
                    )
                    return@runCatching
                }
                if (rejectStatus != null) {
                    writeHttp(client, rejectStatus, "")
                    return@runCatching
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                client.getOutputStream().write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                        ).toByteArray(),
                )
                client.getOutputStream().flush()
                afterUpgrade(client, input)
            } finally {
                runCatching { client.close() }
            }
        }
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
}
