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
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?"))
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
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?"))
        // 早退有两种合法形状：单行 `return`，或块（退配对要顺手丢欠账，见 B019 P1-2）。
        // 取两者中先出现的那个。
        val earlyReturn = listOf(
            body.indexOf("if (client == null) return"),
            body.indexOf("if (client == null) {"),
        ).filter { it >= 0 }.minOrNull() ?: -1
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

    @Test
    fun `必须订阅 questions 流 否则模型提问永远不通知`() {
        // 缺口2（m11638）：`user-questions/request` 与 `approval/request` 在
        // `API_REMOTE_FORWARDED_EVENTS` 里**都是 waterfall**，服务端抛给第一个愿意回答的
        // 远程监听者然后**等**。没人答就是 `NO_PROVIDER`，任务直接失败。
        // 只订阅 approvals 而不订阅 questions，等于「模型要授权时打断你、
        // 模型要信息时一声不吭让任务挂掉」——这两种情况对用户的意义完全一样。
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?"))
        assertTrue("attach 里必须订阅 client.questions", body.contains("client.questions.collect"))
        assertTrue("订阅要挂在 syncQuestions 上", body.contains("syncQuestions("))
        assertTrue(
            "换连接时要取消提问订阅，否则切过主机之后有两条订阅都在发",
            body.contains("questionJob?.cancel()"),
        )
    }

    @Test
    fun `提问也要双向对账 不能只听新增`() {
        // `questions` 是 StateFlow，重连后可能整体换一批（旧的被清空）。
        // 只判新增会留下一排永远撤不掉的旧通知——点进去那个问题早就不存在了。
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun syncQuestions("))
        assertTrue("要算出「已经不在了」的那批", body.contains("notifiedQuestionsSet - alive"))
        assertTrue("消失的要撤通知", body.contains("cancelQuestionByEventId("))
        assertTrue(
            "撤了通知还要销账，否则下次同 eventId 又来会被当成通知过",
            body.contains("forgetNotifiedQuestion("),
        )
        assertTrue(
            "发过要记账，否则同一条提问会被反复通知",
            body.contains("markNotifiedQuestion("),
        )
        assertTrue("同样要过闸门（权限/频道/前后台）", body.contains("NoticeGate.decide("))
        assertTrue(
            "闸门说 Skip 就必须真的跳过：只算出来不判，等于前台正看着、用户没授权、" +
                "频道被关掉时照样弹通知",
            body.contains("if (delivery == NoticeDelivery.Skip) continue"),
        )
    }

    @Test
    fun `换连接时要清掉提问的账`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun forgetAllNotified()"))
        assertTrue("审批的账要清", body.contains("notifiedApprovals.clear()"))
        assertTrue(
            "提问的账也要清：不清的话新主机上同 eventId 的提问被当成通知过而跳过",
            body.contains("notifiedQuestions.clear()"),
        )
        assertTrue(body.contains("notifiedQuestionsSet.clear()"))
    }

    @Test
    fun `换连接要复位连接判定器 否则那条连不上会永远挂着`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach(client: RemoteEventsClient?"))
        assertTrue(
            "判定器记着上一台断多久、提醒过没有；不复位的话切到一台连得好好的主机后，" +
                "那条「连不上」永远撤不掉（新连接第一帧被刻意跳过，判定器永远不知道自己恢复了）",
            body.contains("link.reset()"),
        )
    }

    @Test
    fun `恢复时必须补账 否则断线期间跑完的任务永远没人告诉你`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun onCarrier("))
        assertTrue("要判恢复", body.contains("phase == LinkPhase.Up"))
        assertTrue("要真的去补，不是记个标记", body.contains("settleMissedOutcomes()"))
        assertTrue("补完要销账，否则同一次恢复补两遍", body.contains("link.takeSettle()"))
    }

    @Test
    fun `配对失效要发'需要重新配对'而不是'会自己重连'`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun deliverLinkWarning("))
        assertTrue(
            "退配对之后不会再重连，说「等一下会好」等于一句永远兑现不了的承诺",
            body.contains("NoticeText.unpairedTitle()"),
        )
        assertTrue(body.contains("NoticeText.unpairedText()"))
        assertTrue(body.contains("NoticeText.offlineTitle()"))
    }

    @Test
    fun `换主机必须退订旧连接的载波状态 否则旧连接最后一帧会一直说连不上`() {
        val body = codeOnly(bodyOf(appContainerSource(), "private fun watchCarrier("))
        assertTrue("先取消旧的", body.contains("carrierJob?.cancel()"))
        assertTrue("没有连接时要复位判定器", body.contains("notifier.onCarrier(CarrierState.Idle"))
        assertTrue("只把真的变化喂下去", body.contains("distinctUntilChanged()"))
    }

    @Test
    fun `恢复后不能只是撤掉通知 要明说已经连回来了`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun onCarrier("))
        // 直接 `cancelLink()` 的观感是「通知自己消失了」：用户分不清是恢复了、
        // 还是自己不小心划掉了，而这两件事对他接下来做什么完全不同
        // （前者可以继续等结果，后者得去检查网络/重新配对）。
        assertTrue(
            "Clear 这一支要发一条「已经连回来了」",
            body.contains("is LinkAction.Clear -> center.postLink("),
        )
        assertTrue(body.contains("NoticeText.onlineTitle()"))
        assertTrue(body.contains("NoticeText.onlineText()"))
        assertTrue(
            "不能又退回去只撤不说明",
            !body.contains("is LinkAction.Clear -> center.cancelLink()"),
        )
    }

    @Test
    fun `订阅载波状态时必须跳过第一帧 否则每次切主机都当成一次连上了`() {
        val body = codeOnly(bodyOf(appContainerSource(), "private fun watchCarrier("))
        val guard = body.indexOf("if (last == null) {")
        assertTrue("要有「第一帧」的判断", guard >= 0)
        val close = body.indexOf("}", guard)
        val block = body.substring(guard, close)
        assertTrue(
            "判断里必须真的 return，只赋值不返回等于没跳过——" +
                "切主机后的第一帧 Connected 会被当成「刚恢复」，去给上一台主机的会话补一遍账",
            block.contains("return@collect"),
        )
    }

    @Test
    fun `即时路径必须看 settled 否则会把正在跑的一轮说成跑完了`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun postOutcome("))
        assertTrue(
            "running 翻成 false 时新一轮可能已经开始（api-session/status 是 agent.status",
            body.contains("if (probe != null && probe.settled) probe.lastEnd else null"),
        )
        assertTrue(
            "不能无条件用 probe.lastEnd",
            !body.contains("val found = probe?.lastEnd"),
        )
    }

    @Test
    fun `跳过投递时也要把错误记下来 否则切后台后这次失败永久丢失`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun postOutcome("))
        val note = body.indexOf("outcomes.noteError(")
        val skip = body.indexOf("if (delivery == NoticeDelivery.Skip) return@withLock null")
        assertTrue("要有 noteError", note >= 0)
        assertTrue("要有 Skip 早退", skip >= 0)
        assertTrue(
            "noteError 必须在 Skip 早退之前——反过来等于「前台看着时不记账」，" +
                "用户随后切后台 running 不会再翻转，这次结局就再也没人报了",
            note < skip,
        )
        assertTrue(
            "Skip 时不能调 decide：没查到结局的 decide 会记 pendingEnd，把之后该发的那条压掉",
            skip < body.indexOf("outcomes.decide("),
        )
    }

    @Test
    fun `补账路径查不到结局就不报 不能凭空编一条任务结束`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun postSettledOutcome("))
        // 只断「有没有 `probe == null`」是不够的：把那一支改成照发一条结局不确定的通知，
        // 字符串还在、变异照样活。必须断**那一支做什么**。
        val guard = body.indexOf("if (probe == null) {")
        assertTrue(
            "查不到 probe 要 Skip——那段时间的 Removed 事件也丢了，会话可能已经不在了",
            guard >= 0,
        )
        val block = body.substring(guard, body.indexOf("}", guard))
        assertTrue(
            "那一支必须直接 Skip，不能调 decide（decide(sessionId, null, …) 会发一条「任务结束·不知道怎么了」，" +
                "用户点进去是一个不存在的会话）",
            block.contains("OutcomeDecision.Skip") && !block.contains("outcomes.decide("),
        )
        val next = body.indexOf("else if (!probe.settled) {")
        assertTrue("没结束也要 Skip", next >= 0)
        // 从 `else if` 的 `{` 之后开始找收尾的 `}`：直接 indexOf("}", next) 会撞上
        // `} else if` 那个前导花括号，截出空串，断言恒假（写这条断言时踩过）。
        val block2 = body.substring(next, body.indexOf("}", next))
        assertTrue(
            "还有一轮开着时同样只能 Skip",
            block2.contains("OutcomeDecision.Skip") && !block2.contains("outcomes.decide("),
        )
    }

    @Test
    fun `补账路径必须先判门控再记账 否则 Skip 会留下已报的痕迹`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun postSettledOutcome("))
        val skip = body.indexOf("if (delivery == NoticeDelivery.Skip) return")
        val decide = body.indexOf("outcomes.decide(")
        assertTrue("要有门控早退", skip >= 0)
        assertTrue(
            "门控必须在 decide 之前：decide 会写 postedSeq，Skip 时没发却记成已报，" +
                "用户切后台后这次结局永久丢失",
            skip < decide,
        )
    }

    @Test
    fun `三条账号事件都要接上 少一条就等于这个缺口没做`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle("))
        assertTrue(
            "授权失效要提醒",
            body.contains("is SessionNotification.AccountExpired ->") &&
                body.contains("onAccountProblem(AccountProblem.SessionExpired)"),
        )
        assertTrue(
            "模型要重新登录要提醒",
            body.contains("is SessionNotification.AccountSignIn ->") &&
                body.contains("onAccountProblem(AccountProblem.ModelSignIn)"),
        )
        assertTrue(
            "凭据变了要能复位，否则重新登录后再失效就不会再提醒",
            body.contains("is SessionNotification.CredentialsChanged ->") &&
                body.contains("accounts.onCredentialsChanged()"),
        )
    }

    @Test
    fun `账号恢复要真的发出「已经恢复了」不能只是撤掉状态`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle("))
        assertTrue(
            "复位时要有 resolved = true 的投递",
            body.contains("resolved = true") && body.contains("NoticeText.accountFixedTitle()"),
        )
        // 上面两条只证明「字符串在」，给条件加 `&& false` 就能让这段永不执行而断言照过。
        // 精确匹配带花括号的条件行，条件被加料就断。
        assertTrue(
            "条件行必须是原样的 `if (accounts.onCredentialsChanged()) {`",
            body.contains("if (accounts.onCredentialsChanged()) {"),
        )
    }

    @Test
    fun `账号提醒不走会话维度门控 账号掉了与正在看哪个会话无关`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun onAccountProblem("))
        assertTrue(
            "只看前后台，不能传 viewingSessionId / completedSessionId——" +
                "账号是全局的，塞一个会话进去会让「前台看着 A，B 也一起失败」被静默",
            body.contains("NoticeVisibility(foreground = foreground)"),
        )
        assertTrue("不能带 completedSessionId", !body.contains("completedSessionId = "))
        // 门控结果必须真的生效。只断「有没有调 decide」是不够的：把
        // `if (delivery == Skip) return` 加上 `&& false`，判了但没用，断言照过。
        assertTrue(
            "门控 Skip 必须真的早退",
            body.contains("if (delivery == NoticeDelivery.Skip) return"),
        )
    }

    @Test
    fun `换主机要清掉账号提醒的账 账号是每台机器各自的`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach("))
        assertTrue(body.contains("accounts.reset()"))
    }
}
