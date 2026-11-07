package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知接线的结构契约（`NotifyCoordinator.attach` 与 `AppContainer` 的撤通知时机）。
 *
 * ## 为什么这两处只能用源码断言
 *
 * `NotifyCoordinator` 要真实 `RemoteEventsClient`（内含 `RemoteStreamMux` + `TypertClient`，
 * 都绑着活着的 WebSocket）；`AppContainer` 要 `Context` 才能在 JVM 上构造。两者都**跑不进单测**。
 * 而 R056 六轮审查在这里抓到的两个缺陷，恰恰是「每一块零件都对、只有接线的时序错」：
 *
 * 1. **`attach()` 不清三本账**：`notifiedApprovals` / `edge` / `names` 都按会话或请求 id 记账，
 *    而会话 id 与 eventId 都是**每台主机各自**的。换主机不清，就会出现三种互不相干的事故：
 *    新主机上同 eventId 的审批被当成「通知过了」永远不发；旧账里消失的 eventId 去撤一批
 *    撤不掉的通知；新主机第一次看到 `running=false` 撞上旧账的 `true` 而凭空发一条
 *    「任务结束」——**而那个会话其实正在跑**。第三种最坏：用户以为跑完了就不去看了。
 * 2. **切主机/退配对不撤旧通知**：旧主机已经卸载的连接还留着一串通知，点进去是空会话
 *    （或者更糟，点进新主机上 id 恰好相同的另一个会话）。
 *
 * 纯逻辑本身（`CompletionEdge.reset` / `SessionNameTable.clear` / `NotificationCenter.cancelAll`）
 * 各有自己的单测；这里只钉「**调用发生了、而且顺序对**」。
 */
class NotifyWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun coordinatorSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/notify/NotifyCoordinator.kt",
    )

    private fun appContainerSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt",
    )

    /** 去掉整行注释：注释里提到代码是正常的（本轮就在注释里写了这三行），不剥会误报。 */
    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** 抠函数体（从签名后的第一个 `{` 起，大括号配对到闭合）。 */
    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) error("源码里找不到：$signature")
        val open = text.indexOf('{', start)
        if (open < 0) error("$signature 后面没有函数体")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的大括号没有配对")
    }

    @Test
    fun `换连接必须先清掉三本账 否则旧主机的账会压掉新主机的通知`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?)"))
        assertTrue(
            "attach 必须清 approval 通知账：不清的话新主机上同 eventId 的请求会被当成通知过而跳过",
            body.contains("forgetAllNotified()"),
        )
        assertTrue(
            "attach 必须清跑没跑完的边沿账：不清会凭空发一条「任务结束」，而那个会话可能正在跑",
            body.contains("edge.reset()"),
        )
        assertTrue(
            "attach 必须清标题表：否则 A 主机的 s-1 标题会挂到 B 主机 s-1 的通知上",
            body.contains("names.clear()"),
        )
    }

    @Test
    fun `清账必须发生在提前返回之前 否则切到空连接时账就留下了`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?)"))
        val earlyReturn = body.indexOf("if (client == null) return")
        assertTrue("attach 里应有 `if (client == null) return` 的早退", earlyReturn >= 0)
        for (call in listOf("forgetAllNotified()", "edge.reset()", "names.clear()")) {
            val at = body.indexOf(call)
            assertTrue(
                "$call 必须在 `if (client == null) return` 之前——" +
                    "退配对就是 attach(null)，正是最该清账的那一次；放在后面等于最需要时不清",
                at in 0 until earlyReturn,
            )
        }
    }

    @Test
    fun `重建连接前要撤掉旧通知 否则旧主机的通知还挂着`() {
        val body = codeOnly(bodyOf(appContainerSource(), "private suspend fun installRebuilt("))
        val cancel = body.indexOf("notificationCenter.cancelAll()")
        val attach = body.indexOf("notifier.attach(")
        assertTrue("installRebuilt 里要调 cancelAll()", cancel >= 0)
        assertTrue("installRebuilt 里要调 notifier.attach()", attach >= 0)
        assertTrue(
            "cancelAll() 必须在 attach 之前——先撤旧账再接手新连接，" +
                "反过来的话新连接刚发的一条通知会被自己撤掉",
            cancel < attach,
        )
    }

    @Test
    fun `回答失败必须把原因写回通知 不能静默吞掉`() {
        // `answerFromNotification` 要真实连接才能跑（`linkStore.current().connection`），
        // 单测进不去。但它有三种「用户点了按钮却什么都没发生」的写法，必须逐条钉住：
        // 通知栏里一条带按钮的通知，点下去没反应，用户只会得出「这按钮坏了」，
        // 而服务端还在 waterfall 上等着——任务就那样卡住了。
        val body = codeOnly(bodyOf(appContainerSource(), "fun answerFromNotification("))
        assertTrue(
            "连接为空时必须 postAskFailed：静默 return 的话通知还挂着、按钮还能点，点了永远没反应",
            body.contains("postAskFailed(eventId, DISCONNECTED_HINT)"),
        )
        assertTrue(
            "回答抛异常时必须 postAskFailed(describeAnswerFailure)：否则同样是点了没反应",
            body.contains("postAskFailed(eventId, describeAnswerFailure(e))"),
        )
        assertTrue(
            "作用域被取消要原样上抛，不能谎报「授权没能送出」——进程都要退了，那不是发送失败",
            body.contains("catch (e: kotlinx.coroutines.CancellationException)"),
        )
    }

    @Test
    fun `退配对时要撤通知并清掉待跳转的会话 否则点通知会进一个不存在的会话`() {
        // Drop 分支写在 `installConnection` 的 when 里，不是独立函数，所以粗到那个分支为止。
        val body = codeOnly(bodyOf(appContainerSource(), "ConnectionPlan.Drop -> {"))
        assertTrue(
            "Drop 分支要 cancelAll()：连接没了，通知点进去必然是空会话",
            body.contains("notificationCenter.cancelAll()"),
        )
        assertTrue(
            "Drop 分支还要 NoticeIntents.clear()：否则下次启动会把用户送进那台主机的旧会话",
            body.contains("NoticeIntents.clear()"),
        )
    }
}
