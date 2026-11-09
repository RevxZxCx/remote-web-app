package com.revxzxcx.remotewebapp.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台连接的接线契约（R057）。
 *
 * ## 为什么这一层必须用源码断言
 * 前台服务的**真正行为只有系统能触发**：`startForeground` 的时机、`START_STICKY`
 * 重启、开机广播、通知按钮的 PendingIntent 派发。这些在 JVM 里跑不了，
 * Robolectric 也只能模拟一小部分，而真机验收每个场景都要手动等（等 LMK 杀进程、
 * 等重启手机）。所以「声明对不对」「有没有接上」这两类问题只能靠读源码钉住。
 *
 * 这里**只钉「有没有」和「顺序」**，不钉任何行为细节——行为细节在
 * [ServiceStatusTrackerTest] / [PlanRecoveryTest] / [ServiceTextTest] 里。
 *
 * ## 钉的这几条，每一条漏了都会让功能静默失效
 * 1. Manifest 没声明 `specialUse` 与属性 → Android 14+ 直接 `MissingForegroundServiceTypeException`。
 * 2. 没声明 `BOOT_COMPLETED` → 开机自启永远不触发（这条最隐蔽：开关是开的，但机器重启后
 *    一次都没起过，用户只会觉得「这功能时好时坏」）。
 * 3. 接收器没接「退出」action → 用户点通知栏那颗按钮，通知消失、服务还在跑，
 *    下一帧又画回来（比按钮不存在更糟）。
 * 4. `AppRoot` 没消费 `pendingOpenSettings` → Q5 承诺的「点通知落设置页」变成落在首页。
 */
class LinkServiceWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun manifest() = source("src/main/AndroidManifest.xml")

    /** 去掉整行注释：KDoc 里提到代码是正常的，不剥会误报。 */
    /** 去掉 XML 注释；给「只看真实声明」的断言用。 */
    private fun stripXmlComments(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val open = raw.startsWith("<!--", i)
            if (open) {
                val close = raw.indexOf("-->", i + 4)
                if (close < 0) break
                i = close + 3
            } else {
                sb.append(raw[i])
                i += 1
            }
        }
        return sb.toString()
    }

    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

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
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        error("$signature 的大括号没配平")
    }

    /**
     * 取表达式体函数（`fun f(...): T = ...`）的右值。
     *
     * [bodyOf] 只能取大括号体；`noticeUsable()` 是 `= a && b` 这种写法，
     * 用 bodyOf 会一路吃到下一个 `{`（companion object），断言就变成假的了。
     */
    private fun exprOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) error("源码里找不到：$signature")
        val eq = text.indexOf('=', start)
        if (eq < 0) error("$signature 不是表达式体")
        val rest = text.substring(eq + 1).lineSequence()
            .dropWhile { it.isBlank() }
            .takeWhile { it.isNotBlank() }
            .joinToString("\n")
        return rest
    }

    // ---------------------------------------------------------------- Manifest

    @Test
    fun `服务必须声明 specialUse 类型 否则 Android 14 加会直接抛异常`() {
        val text = manifest()
        assertTrue(
            "少了 android:foregroundServiceType=\"specialUse\"，startForeground 会抛 " +
                "MissingForegroundServiceTypeException",
            text.contains("android:foregroundServiceType=\"specialUse\""),
        )
    }

    @Test
    fun `specialUse 必须带 subtype 属性 否则系统不知道该服务干什么`() {
        // 官方要求：specialUse 要配 <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>。
        val text = manifest()
        assertTrue(text.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(text.contains("android:value="))
    }

    @Test
    fun `服务不能被系统当成可导出的`() {
        val block = manifest().substringAfter("ForegroundLinkService")
            .substringBefore("/>")
        assertTrue("前台服务必须 android:exported=\"false\"", block.contains("android:exported=\"false\""))
    }

    @Test
    fun `从最近任务划掉 App 时不要顺手停服务`() {
        // stopWithTask 默认 false，但显式写出来是因为「这正是它存在的理由」：
        // 用户划掉 App 恰恰是最需要连接继续活着的场景。
        val block = manifest().substringAfter("ForegroundLinkService").substringBefore("/>")
        assertTrue(block.contains("android:stopWithTask=\"false\""))
    }

    @Test
    fun `三个权限一个都不能少`() {
        val text = manifest()
        assertTrue("前台服务本体", text.contains("android.permission.FOREGROUND_SERVICE\""))
        assertTrue("specialUse 类型专属", text.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(
            "少了它开机自启永远不触发，而开关还是开的——用户只会觉得这功能时好时坏",
            text.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
    }

    @Test
    fun `偏好变化的信号要由控制器统一推高 界面才有机会重读`() {
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        // 第四轮审查 P2：`onStartFailed` 会自己关开关 + 写失败原因，完全不走用户回调。
        // 若界面只靠用户回调驱动的本地计数，就会「磁盘上已关、界面还亮着开」。
        assertTrue(
            "控制器要暴露一个偏好版本号供界面订阅",
            controller.contains("val prefsRevision: StateFlow<Int>"),
        )
        assertTrue(
            "写盘必须集中到一个出口，否则总有路径漏掉版本号",
            controller.contains("private fun writePrefs(block: () -> Unit)"),
        )
        assertTrue(
            "界面要订阅这个版本号，否则后台改偏好时界面纹丝不动",
            source("src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt")
                .contains("container.servicePrefsRevision.collectAsState()"),
        )
    }

    @Test
    fun `退出时必须连恢复提示一起撤掉 否则通知栏留残影`() {
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val stop = controller.substringAfter("fun requestStop()").substringBefore("fun startOnBoot")
        assertTrue(
            "requestStop 里必须撤掉恢复提示卡片（SERVICE_STOPPED_ID）",
            stop.contains("cancelServiceStopped()"),
        )
        // 🔴 这里**不能**要求 `cancelLink()`（第五轮审查 P3）。
        //
        // 这条断言曾经要求它，理由是按「通知栏留残影」推的。但 `requestStop()` 有两个
        // 调用方：`exitFromNotification()`（用户点通知上的「退出」）与
        // `setForegroundEnabled(false)`（用户在设置里关掉开关）。后者连接本身可能
        // 仍是断的，用户还在用 App，那条「连不上桌面端了」对他依然有效；一旦在这里
        // 撤掉，`LinkNoticeGate.warnedPhase` 仍占着位，直到真正重连成功才会再发——
        // 等于永久丢失提醒。撤 `LINK_ID` 的只能是「用户明确表示这次不要」的退出路径。
        assertFalse(
            "requestStop 是共用的，不能替用户撤掉断连提醒",
            stop.contains("cancelLink()"),
        )
    }

    @Test
    fun `只有用户点退出才撤断连提醒 关开关不算`() {
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val exit = bodyOf(controller, "fun exitFromNotification()")
        assertTrue("退出路径要撤掉 LINK_ID", exit.contains("notificationCenter.cancelLink()"))
    }

    @Test
    fun `恢复提示必须真的能被撤掉 否则 cancel 是空接`() {
        val center = codeOnly(source("src/main/java/com/revxzxcx/remotewebapp/core/notify/NotificationCenter.kt"))
        assertTrue("缺少 cancelServiceStopped 的实现", center.contains("fun cancelServiceStopped()"))
    }

    @Test
    fun `开机接收器要收两种广播 且不能声明永不送达的那种`() {
        val text = manifest()
        // MY_PACKAGE_REPLACED：升级后系统不会补发 BOOT_COMPLETED，不接这条就会「升级一次掉一次」。
        listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")
            .forEach { action -> assertTrue("少了 $action", text.contains(action)) }
        // LOCKED_BOOT_COMPLETED 只在 directBootAware 组件上送达，而 receiver 与
        // Application 都不是（凭证在 Keystore + 文件里，直接启动阶段读不到）。
        // 登记一条永远不触发的 action 只会让后来者以为这里真有直接启动阶段的逻辑。
        // 只看真实声明，不看注释：清单里那段注释正是在解释「为什么不登记它」，
        // 把注释也算进去会让这条断言自相矛盾。
        // 只看真实声明，不看注释：清单里那段注释正是在解释「为什么不登记它」，
        // 把注释也算进去会让这条断言自相矛盾。剥离用下面这个手写函数，
        // 不用正则——避免反斜杠在脚本链里被吞（本仓库踩过多次）。
        val receiverBlock = stripXmlComments(
            text.substringAfter("<receiver").substringBefore("</receiver>"),
        )
        assertFalse(
            "LOCKED_BOOT_COMPLETED 永远送不到，不该登记",
            receiverBlock.contains("LOCKED_BOOT_COMPLETED"),
        )
    }

    @Test
    fun `开机接收器必须对外导出 否则系统广播送不进来`() {
        // 🔴 真机实测缺陷（R057-8）：这里原本写的是「开机接收器不可导出」，
        // 断言 exported="false"，理由是「否则任何 App 都能拉起我们的前台服务」。
        // 那个理由听起来成立，实际把功能写死了：exported="false" 的接收器只接受
        // **同 UID** 的广播，而 BOOT_COMPLETED 由 system_server（uid 1000）发出，
        // 于是它开机后永远不会被调用——进程都不启动，logcat 里一行我们的日志都没有。
        // 这条断言当时通过了 4 轮子代理审查，因为审查读的就是它。
        //
        // 安全性靠的是接收器本身不处理外部数据：它只读本地开关、不解析 intent extra、
        // 不做任何跨进程调用，被别的 App 触发最多也就是「按用户的开关决定要不要连」，
        // 没有可利用的暴露面。
        val block = stripXmlComments(
            manifest().substringAfter("BootReceiver").substringBefore("</receiver>"),
        )
        assertTrue(
            "开机接收器必须 exported=true，否则 BOOT_COMPLETED 一律被丢掉",
            block.contains("android:exported=\"true\""),
        )
    }

    // ------------------------------------------------------------- 接收器与宿主

    @Test
    fun `通知栏的退出按钮必须真的接上 否则点了只是通知消失`() {
        val receiver = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/notify/NoticeActionReceiver.kt"),
        )
        assertTrue("接收器要认 ACTION_EXIT_SERVICE", receiver.contains("NotificationCenter.ACTION_EXIT_SERVICE"))
        assertTrue("要调宿主的退出入口", receiver.contains("exitServiceFromNotification()"))
    }

    @Test
    fun `DshApplication 必须实现退出入口 否则接收器拿不到宿主`() {
        // 与 R056 的 answerFromNotification 是同一类坑：`as? NoticeActionHost` 拿 null 时
        // 兜底只撤通知，用户看到「通知消失、服务还在跑」。
        val app = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/DshApplication.kt"),
        )
        assertTrue(app.contains("override fun exitServiceFromNotification()"))
        assertTrue(app.contains("container.exitLinkService()"))
    }

    @Test
    fun `退出动作走的是只影响本次的那条路 不能改开关`() {
        // Q4 的口径：退出只影响这一次。改开关会让「退出一次」变成「永久关闭」。
        val container = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val body = bodyOf(container, "fun exitLinkService()")
        assertTrue("要走 linkService.exitFromNotification()", body.contains("linkService.exitFromNotification()"))
        assertTrue("退出不能顺手把开关关掉", !body.contains("setForegroundEnabled"))
    }

    @Test
    fun `Q5 承诺的落点必须被消费 否则点通知只落在首页`() {
        val root = codeOnly(source("src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt"))
        assertTrue("要订阅这个信箱", root.contains("NoticeIntents.pendingOpenSettings"))
        assertTrue("要真的设成打开通知设置页", root.contains("openNoticeSettings = true"))
        val body = bodyOf(root, "LaunchedEffect(noticeWantsSettings)")
        assertTrue("消费即清，否则每次重组都会跳一次", body.contains("consumeSettings()"))
        assertTrue(
            "必须在跳设置页前把别的路由清掉，否则 when 会先命中会话分支",
            body.indexOf("openSession = null") in 0 until body.indexOf("openNoticeSettings = true"),
        )
    }

    @Test
    fun `设置页三个开关都要接到容器 少接一个就等于没实现`() {
        val root = codeOnly(source("src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt"))
        listOf(
            "container.setServiceForegroundEnabled(",
            "container.setServiceAutoRecover(",
            "container.setServiceBootStart(",
            "container.markServiceConfirmSeen(",
        ).forEach { call ->
            assertTrue("AppRoot 没接 $call", root.contains(call))
        }
    }

    // ------------------------------------------------------- 服务自身的两处硬约束

    @Test
    fun `START_STICKY 重启时要重新校验开关`() {
        // 系统只记得「这个服务值得重起」，不知道用户后来把开关关了。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val body = bodyOf(service, "override fun onStartCommand(")
        val guard = body.indexOf("serviceWanted()")
        val raise = body.indexOf("raiseForeground(")
        assertTrue("没校验开关，用户关了它还会被系统重起", guard >= 0)
        assertTrue(
            "校验必须在 startForeground 之前，否则已经进前台了再停会闪一条通知",
            guard in 0 until raise,
        )
    }

    @Test
    fun `停服务必须先撤通知再 stopSelf`() {
        // 反过来的话系统会在服务已经不存在时仍留着那条常驻通知，
        // 而它 `setOngoing(true)`，用户划不掉——只能重启手机。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val body = bodyOf(service, "private fun requestStop(")
        val cancel = body.indexOf("notice.cancel()")
        val stop = body.indexOf("stopSelf()")
        assertTrue("要先撤通知", cancel >= 0)
        assertTrue("再 stopSelf", stop >= 0)
        assertTrue("顺序反了会留一条划不掉的常驻通知", cancel < stop)
    }

    @Test
    fun `服务不自己建连接 否则审批的 waterfall 会被两处订阅`() {
        // 审批是 waterfall：服务端抛给**第一个**愿意回答的监听者然后等。
        // 服务自己再建一条连接，就会出现「通知发出去了、App 里那条抢先答了」，
        // 或者更糟「两条都答、第二条报 unavailable」。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        assertTrue("服务里不该出现建连接的代码", !service.contains("DshConnection("))
        assertTrue("服务只是画状态", service.contains("container.serviceStatus"))
    }

    // ------------------------------------------- 开机自启/系统重起必须能自己建连接

    @Test
    fun `bootstrap 的触发点必须在进程级 不能只挂在界面上`() {
        // R057 子代理审查 P1：`AppRoot` 的 `LaunchedEffect(Unit)` 是**唯一**的
        // `bootstrap()` 调用点。可开机自启与 START_STICKY 重起都**不经过任何界面**
        // （用户根本没打开 App），于是服务起来了却没有连接、`linkService.onCarrier`
        // 永远收不到帧、常驻通知永远写「正在连接」、时长永不出现。
        // 这条测试守的就是「那个触发点不许退回到只挂在界面上」。
        val app = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/DshApplication.kt"),
        )
        assertTrue(
            "进程创建时必须拉起 bootstrap，否则开机自启/系统重起没有连接可用",
            app.contains("bootstrapOnce()"),
        )

        val container = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val once = bodyOf(container, "fun bootstrapOnce()")
        assertTrue(
            "闸门必须是 CAS：`if (bootstrapped) return` 在多入口并发下会跑两遍 bootstrap",
            once.contains("compareAndSet("),
        )
        assertTrue("bootstrap 本身不能在这里丢掉", once.contains("bootstrap()"))

        // 界面侧也得走同一个闸门，否则进程入口与界面入口会各跑一次。
        val root = codeOnly(source("src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt"))
        assertTrue("界面不能绕过闸门直接调 bootstrap()", root.contains("bootstrapOnce()"))
    }

    // --------------------------------------------- startForeground 失败必须就地返回

    @Test
    fun `前台化失败必须就地返回 不能继续走启动成功那段`() {
        // R057 子代理审查 P1：早先 `raiseForeground` 失败后只记了一条 bug，返回后
        // `onStartCommand` 照旧 `started = true; container.onServiceStarted()`，
        // 而 `onStarted()` 里有「起来了就把上次失败原因清掉」——刚写的失败原因被自己抹掉，
        // 开关被关、设置页因 `!enabled` 连状态行都不画，用户看到「开关自己弹回去且没有任何解释」。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val body = bodyOf(service, "override fun onStartCommand(")
        val raised = body.indexOf("raiseForeground(")
        val success = body.indexOf("started = true")
        assertTrue("必须检查前台化的返回值", body.contains("if (!raiseForeground("))
        assertTrue("失败时不能宣布启动成功", raised in 0 until success)
        assertTrue(
            "失败要返回 START_NOT_STICKY：此刻并不在前台，让系统照原样再来一次只会重放同一个失败",
            body.contains("return START_NOT_STICKY"),
        )

        // 返回值本身也得是真的：失败两个分支返回 false，成功分支（try 表达式的值）是 true。
        // 成功分支漏了或写成 false，每次前台化都会被判成失败，服务永远起不来，
        // 而用户看到的还是「开关自己关掉了」。
        val raise = bodyOf(service, "private fun raiseForeground(")
        assertTrue(
            "raiseForeground 必须返回 Boolean",
            service.contains("raiseForeground(status: ServiceStatus): Boolean"),
        )
        assertTrue(
            "画不出通知时必须提前返回失败",
            Regex("""return false""").containsMatchIn(raise),
        )
        assertTrue(
            "抛异常那个 catch 也要落到 false，不能吞掉后当成成功",
            Regex("""catch\s*\([^)]*\)\s*\{[\s\S]*?\bfalse\b""").containsMatchIn(raise),
        )
        assertTrue(
            "成功分支的 try 表达式必须求值为 true",
            Regex("""true\s*}\s*catch""").containsMatchIn(raise),
        )
    }

    // ------------------------------------------- 可修失败不能永久挡住自动恢复

    @Test
    fun `按原因区分失败 可修的不能永久挡住自动恢复`() {
        // R057 审查 P2-2：`ensureRunning` 早先只看「有没有 lastFailure」，不区分原因。
        // 而「通知未授权」正是最要命的可修失败：用户去系统里授权后回来，服务仍然起不来，
        // 只能靠关掉开关再打开这种没人想得到的动作。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val ensure = bodyOf(controller, "fun ensureRunning()")
        assertTrue(
            "必须按原因判断，不能只看 lastFailure 是否为空",
            ensure.contains("lastFailureBlocksRetry("),
        )
        assertFalse(
            "回到「有失败记录就挡住」等于把可修失败变成永久失败",
            ensure.contains("prefs.lastFailure(key).isNotEmpty()"),
        )

        val failed = bodyOf(controller, "fun onStartFailed(")
        assertTrue(
            "开关关不关、要不要挡重试，必须交给 startFailurePolicy 分类决定",
            failed.contains("startFailurePolicy(") && failed.contains("noticeUsable()"),
        )
        assertTrue(
            "时机性失败（开机时系统不许起前台服务）必须被单独识别出来",
            failed.contains("isTemporaryStartFailure("),
        )
        // 🔴 真机验收缺陷：改成分类之前这里是 `setLastFailureBlocksRetry(key, usable)` +
        // `if (usable) setForegroundEnabled(key, false)`，也就是「只要通知可用就当永久失败」。
        // 开机时撞一次时机性失败就把开关永久关掉，之后每次重启都静默什么都不做。
        assertFalse(
            "不能再退回「通知可用就等于永久失败」那一刀切",
            failed.contains("if (usable)"),
        )
        assertTrue(
            "阻断与关开关都得读分类结果，不能自己另算一遍",
            failed.contains("policy.blocksRetry") && failed.contains("policy.turnsSwitchOff"),
        )

        // 开关被用户动过一次就要重新允许重试，否则那条记录会一直挡着。
        val enabled = bodyOf(controller, "fun setForegroundEnabled(")
        assertTrue(
            "打开开关要一并解除阻断",
            enabled.contains("setLastFailureBlocksRetry("),
        )
    }

    // ------------------------------------------- Idle ≠ 服务已停

    @Test
    fun `没有主机才真停 Idle 只是连接中`() {
        // R057 审查 P2-1：换主机时新 mux 的首帧就是 Idle，旧语义把它映射成 Stopped，
        // `render()` 一见 Stopped 就 stopSelf —— 常驻通知闪一下消失、服务被杀。
        // 分界点在 `AppContainer`：`connection == null`（一台主机都没有）走 onNoConnection，
        // 其余全部走 onCarrier。
        val container = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val watch = bodyOf(container, "private fun watchCarrier(")
        assertTrue(
            "一台主机都没有时要走 onNoConnection",
            watch.contains("linkService.onNoConnection()"),
        )
        assertFalse(
            "这条路径不能再退回 onCarrier(Idle)：换主机时 Idle 会让通知闪一下消失",
            watch.contains("linkService.onCarrier(CarrierState.Idle)"),
        )

        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val noConn = bodyOf(controller, "fun onNoConnection()")
        assertTrue("没有主机时服务要真的停掉", noConn.contains("ForegroundLinkService.stop("))
        assertTrue("相位先置停止", noConn.contains("onNoConnection()"))
    }

    @Test
    fun `可修的失败不能永久挡住自动恢复`() {
        // R057 审查第三轮 P2-1：`noticeUsable()` 原来只等于 `canPost()`，
        // 而 `canPost()` 的 KDoc 自己写着「用户单独关掉某个频道时仍返回 true」。
        // 用户把「后台连接」频道关掉之后，服务照跑照耗电，状态条却被系统静默丢弃。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val usable = exprOf(controller, "private fun noticeUsable(")
        assertTrue(
            "必须查后台连接频道本身，光查 canPost() 挡不住「用户关了频道」",
            usable.contains("isChannelEnabled(NoticeChannels.SERVICE)"),
        )

        // 同一个 P2 的另一半：`ensureRunning()` 的闸门必须看「这次失败用户能不能自己修」，
        // 否则「没授权通知 → 开关 ON + lastFailure 有值 → 去授权 → 回来还是起不来」。
        val ensure = bodyOf(controller, "fun ensureRunning(")
        assertTrue(
            "闸门要按原因区分，不能见 lastFailure 就永久不重试",
            ensure.contains("lastFailureBlocksRetry("),
        )
        assertFalse(
            "旧的「只要有失败原因就返回」会让授权后的恢复永远发生不了",
            ensure.contains("prefs.lastFailure(key).isNotEmpty()"),
        )
    }

    @Test
    fun `退出服务要一并撤掉那条断连通知`() {
        // R057 审查第三轮 P2-3 提出的原始问题是真的：`postLink(offline = true)` 发的是
        // setOngoing(true) 的 LINK_ID，只有 cancelLink() 能撤；不撤用户点「退出」后
        // 通知栏还挂着「后台连接断了」的常驻卡片——违反 Q4「退出即真退干净、不再显示」。
        //
        // 但第三轮把撤的地方错放进了 `requestStop()`，而它同时服务「关开关」。
        // 第五轮审查 P3 纠正：撤 `LINK_ID` 只该发生在退出路径（见上一条用例）。
        // 这条保留下来，守的是「退出路径确实撤了」这件事本身。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val exit = bodyOf(controller, "fun exitFromNotification()")
        assertTrue("退出路径必须撤掉 LINK_ID", exit.contains("cancelLink()"))
    }

    @Test
    fun `恢复通知必须走后台连接频道 不能走任务结束频道`() {
        // R057 审查第三轮 P2-2：`postLink` 固定用 NoticeChannels.DONE（任务结束），
        // 用户关掉「任务结束」频道后这条恢复通知静默丢失；而且它跟「后台连接」是
        // 同一个功能，混进任务结束频道会让通知分组对不上。
        val center = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/notify/NotificationCenter.kt"),
        )
        val post = bodyOf(center, "fun postServiceStopped(")
        assertTrue(
            "必须落在后台连接频道",
            post.contains("NotificationCompat.Builder(context, NoticeChannels.SERVICE)"),
        )
        assertTrue(
            "发送前要查频道没被关掉，否则用户只会觉得「说好的通知呢」",
            post.contains("isChannelEnabled(NoticeChannels.SERVICE)"),
        )
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        // 第六轮 P2 之后这中间隔了一层去重入口 `notifyServiceStoppedOnce()`，
        // 所以这里断「链路上两个环节都接上」，而不是断 `ensureRunning` 里直接出现调用。
        val ensure = bodyOf(controller, "fun ensureRunning(")
        assertTrue(
            "恢复通知要走去重入口",
            ensure.contains("RecoveryAction.NotifyUser -> notifyServiceStoppedOnce()"),
        )
        val once = bodyOf(controller, "private fun notifyServiceStoppedOnce()")
        assertTrue("去重入口要真的把通知发出去", once.contains("postServiceStopped("))
    }

    @Test
    fun `提前退出前必须先把前台化契约兑掉 否则系统抛超时异常`() {
        // R057 审查第三轮 P2-4：onStartCommand 有四条路径在没调过 startForeground 的
        // 情况下就走 stopSelf，Android 12+ 抛 ForegroundServiceDidNotStartInTimeException，
        // 用户看到的是 App 闪退。最容易命中的是「通知权限被撤销」那条。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val stop = bodyOf(service, "private fun requestStop(tellUser: Boolean)")
        val firstLine = stop.trimStart().lineSequence().first { it.isNotBlank() }
        assertTrue(
            "补前台化必须是 requestStop 的第一件事，放后面就来不及了",
            firstLine.trimStart().startsWith("satisfyForegroundContract()"),
        )
        val satisfy = bodyOf(service, "private fun satisfyForegroundContract()")
        assertTrue("要真的调 startForeground", satisfy.contains("startForeground("))
        assertTrue("已经起来了就别重复调", satisfy.contains("if (started) return"))
    }

    @Test
    fun `载波要在闸门之前缓存 否则服务起来时补不回来`() {
        // R057 审查第三轮 P1-2：`onCarrier` 的首行是 `if (!serviceRunning) return`，
        // 而 Connected 只在 socket 建好那一刻发一次。用户「先连着、后开开关」时
        // 唯一那帧就在闸门外面被丢了，服务起来后永远等不到新帧。
        val tracker = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ServiceStatusTracker.kt"),
        )
        val onCarrier = bodyOf(tracker, "fun onCarrier(state: CarrierState?)")
        val cacheAt = onCarrier.indexOf("lastCarrier = state")
        val gateAt = onCarrier.indexOf("if (!serviceRunning) return")
        assertTrue("必须缓存最后一帧载波", cacheAt >= 0)
        assertTrue("闸门必须还在（服务没起来时不能改相位）", gateAt > 0)
        assertTrue(
            "缓存必须发生在闸门之前，写在后面等于没写",
            cacheAt < gateAt,
        )
        val starting = bodyOf(tracker, "fun onStarting()")
        assertTrue(
            "服务起来时要把那帧补上",
            starting.contains("lastCarrier?.let"),
        )
    }

    @Test
    fun `退出标记要按主机区分 换主机不能一起锁死`() {
        // R057 审查第三轮 P2-5：exitMarker 原来是一个裸布尔，用户点过「退出」之后
        // 换主机 / 重新配对，`ensureRunning()` 仍然在 `if (exitMarker.isExited) return`
        // 上永久返回——开关亮着、服务再也不起、设置页毫无提示。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val ensure = bodyOf(controller, "fun ensureRunning(")
        // ⚠️ 这里必须断言**完整那一行**，不能只断言 `isExitedFor(` 出现。
        // 该函数调 `planRecovery(... userExited = exitMarker.isExitedFor(key))`，
        // 源码里 `isExitedFor(` 一共出现两次；只判「字符串存在」时，
        // 把闸门改回裸 `isExited` 的变异体照样能通过（M64 就是这样 SURVIVED 的）。
        assertTrue(
            "闸门那一行要按主机判，不能退回裸布尔",
            ensure.contains("if (exitMarker.isExitedFor(key)) return"),
        )
        val exit = bodyOf(controller, "fun exitFromNotification()")
        assertTrue("退出时要记下是哪台主机", exit.contains("markExited(hostKey)"))
    }

    @Test
    fun `hostKey 空窗口要回退到落盘的上一台主机`() {
        // R057 审查第三轮 P1-1：开机路径上 `Application.onCreate` 只是 appScope.launch，
        // `BootReceiver.onReceive` 紧接着在主线程问「要不要起服务」——那一刻 hostKey
        // 还是空串，偏好全落到 NO_HOST 后缀，读到三个默认值。
        // 「开机自启」和 START_STICKY 的「被杀后自动恢复」整条链路都是死的。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        assertTrue(
            "空 hostKey 必须回退读落盘值",
            controller.contains("ifBlank { prefs.lastHostKey() }"),
        )

        val container = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val remember = bodyOf(container, "private fun rememberServiceHost(book: CredentialBook)")
        assertTrue("有主机就记下来", remember.contains("setLastHostKey("))
        assertTrue(
            "一台都没有要清掉，否则退配对之后开机还想连",
            remember.contains("clearLastHostKey()"),
        )
        assertTrue(
            "三个发布点（复用 / 重建 / 退配对）都要记，漏一个就会出现「换了主机还按旧的判定」",
            container.split("rememberServiceHost(book)").size - 1 >= 3,
        )
    }

    @Test
    fun `设置页的开关快照不能拿状态当 key 否则每秒重读五次磁盘`() {
        // R057 审查第三轮 P3-2：`serviceStatus` 每秒 copy 一次，原来
        // `remember(serviceStatus, serviceUiEpoch)` 让停在设置页的每一秒都重建
        // LinkServiceUi、做 5 次 SharedPreferences 同步读（全在主线程）。
        //
        // 第四轮 P2 又把「只按用户回调递增的本地 epoch」换成了控制器推高的
        // `servicePrefsRevision`：否则后台触发的 `onStartFailed` 改完偏好界面不重读。
        val root = codeOnly(source("src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt"))
        assertFalse(
            "状态不能进开关快照的 key",
            root.contains("remember(serviceStatus, "),
        )
        assertTrue(
            "开关快照要按控制器推高的偏好版本读盘",
            root.contains("remember(prefsRevision)"),
        )
        assertTrue(
            "版本号必须来自容器，不能是界面自己的本地计数",
            root.contains("container.servicePrefsRevision.collectAsState()"),
        )
        assertTrue(
            "状态在还原时才合并进去",
            root.contains(".withStatus(serviceStatus)"),
        )
    }

    @Test
    fun `常驻通知的频道必须是静音的 它不该响`() {
        val channels = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/notify/NoticeChannels.kt"),
        )
        assertTrue("要有一个独立频道", channels.contains("SERVICE"))
        val notice = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ServiceNotice.kt"),
        )
        assertTrue("常驻通知必须 setSilent(true)", notice.contains("setSilent(true)"))
        assertTrue("而且不能被划掉", notice.contains("setOngoing(true)"))
    }

    @Test
    fun `常驻通知要按看得见的文案去重 不能每帧都发一次`() {
        // 真机验收才暴露的一条：控制器每秒 `tick()` 推进时长，`ServiceStatus` 因此每秒
        // 都是新对象，`render()` 也就每秒 `notify()` 一次。而 `formatDuration` 只精确到
        // 「分钟」，一分钟里 59 帧画出来的通知一模一样。
        //
        // 后果不是「稍微费点电」：通知栏每秒被重发，系统**永远进不了静止态**，
        // `uiautomator dump` 直接报 `ERROR: could not get idle state`，
        // 整台设备上的自动化点按全都没法做。这条断言守的就是「别退回去」。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val render = bodyOf(service, "private suspend fun render(")
        assertTrue(
            "去重键必须是画出来的文案，而不是相位或时长本身",
            render.contains("ServiceText.title(current)") && render.contains("ServiceText.text(current)"),
        )
        val skipAt = render.indexOf("if (key == lastKey) return@collect")
        assertTrue("没变就直接跳过", skipAt > 0)
        val notifyAt = render.indexOf("manager?.notify(ServiceNotice.NOTICE_ID, built)")
        assertTrue("通知发得出去", notifyAt > 0)
        assertTrue("跳过必须发生在 notify 之前，否则等于没跳过", skipAt < notifyAt)
    }

    @Test
    fun `拿到通知权限后必须重试起服务 否则开关亮着但永远不跑`() {
        // 第五轮审查 P1。Android 13+ 新装默认没有 POST_NOTIFICATIONS：
        // 用户首次打开「保持后台连接」时 `startService()` 因 `noticeUsable()` 为假失败，
        // 而 `startFailurePolicy(temporary = false, noticeUsable = false)` 明确
        // 「不关开关、不挡重试」——开关亮着、服务没起来，这是**正确**的中间态。
        //
        // 但「能好」得有人把它变好：`ensureRunning()` 原来只有载波变化与意外停止
        // 两个调用点，连接稳定之后不再产生新载波帧。用户去系统里授权回来，
        // 服务永远起不来，设置页长期停在「开但未运行」——正是用户第 1 条约束
        // 「通知必须如实写状态」要否定的东西。
        val container = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val result = bodyOf(container, "fun onNoticePermissionResult(granted: Boolean)")
        assertTrue("授权了才继续", result.contains("if (!granted) return"))
        assertTrue("频道照旧要补", result.contains("ensureChannels()"))
        assertTrue("必须重试起服务", result.contains("linkService.ensureRunning()"))

        val foreground = bodyOf(container, "fun onAppForeground()")
        assertTrue(
            "回到前台也要补一次：用户很可能是去系统设置开的权限，那条路径没有回调",
            foreground.contains("linkService.ensureRunning()"),
        )

        val application = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/DshApplication.kt"),
        )
        val watch = bodyOf(application, "private fun watchForeground()")
        val startAt = watch.indexOf("override fun onStart(")
        assertTrue("生命周期观察者还在", startAt > 0)
        assertTrue(
            "onStart 里必须转给容器，否则 onAppForeground 是死代码",
            watch.indexOf("container.onAppForeground()") > startAt,
        )
    }

    @Test
    fun `停服务要用 stopService 不能 startService 否则会闪一条通知`() {
        // 第五轮审查 P3。`startService(ACTION_STOP)` 在服务**本来没跑**的时候也会
        // 把进程拉起来走一遍 `onCreate` → `onStartCommand`：于是先
        // `satisfyForegroundContract()` 真的 `startForeground` 出一条
        // 「正在连接桌面端」，紧接着才 `cancel + stopSelf`。用户看到的是
        // 「刚关掉的功能先闪一条通知再消失」，与 Q4「退出即退干净」观感相反。
        //
        // `stopService` 没这个副作用：服务没跑就什么都不发生，在跑就直接走 onDestroy
        // （那里已有 `notice.cancel()` 与 `container.onServiceStopped()`，善后一样不少）。
        val service = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/ForegroundLinkService.kt"),
        )
        val stop = bodyOf(service, "fun stop(context: Context)")
        assertTrue("必须用 stopService", stop.contains("context.stopService(intent)"))
        assertFalse(
            "不能用 startService，否则会先起一遍服务再停",
            stop.contains("context.startService(intent)"),
        )
        // 改用 stopService 之后 `onStartCommand` 里的 `ACTION_STOP` 分支再没有任何
        // 发送方（第六轮审查 P3）：留着会让人以为「外部还能发指令停服务」。
        assertFalse(
            "ACTION_STOP 已无发送方，分支与常量都该删掉",
            service.contains("ACTION_STOP"),
        )
    }

    @Test
    fun `同一次死亡只能提醒一次 否则切回前台就重弹`() {
        // 第六轮审查 P2。第六轮把 `ensureRunning()` 接进了「每次回前台」与「拿到通知权限」，
        // 调用频率从「连接状态变化时」变成「每次切回 App」；而「自动恢复关着」时它会
        // 走 `RecoveryAction.NotifyUser` 分支发提醒。没有账本的话，用户划掉那条提醒、
        // 切出去再切回来，同一条就被**重新投递**一次。
        //
        // `postServiceStopped` 里的 `setOnlyAlertOnce(true)` 只压声音与弹出，
        // 挡不住划掉之后的重新出现（`notify()` 是按 id 覆盖或新建，划掉等于删了记录）。
        val controller = codeOnly(
            source("src/main/java/com/revxzxcx/remotewebapp/core/service/LinkServiceController.kt"),
        )
        val once = bodyOf(controller, "private fun notifyServiceStoppedOnce()")
        assertTrue("必须先查账本", once.contains("if (stoppedNotified) return"))
        // 位置要紧：先记「已提醒」再发，反过来的话并发重入会发两次。
        val markAt = once.indexOf("stoppedNotified = true")
        val postAt = once.indexOf("postServiceStopped(")
        assertTrue("账本要记上", markAt > 0)
        assertTrue("要真的发", postAt > 0)
        assertTrue("先记账再发，否则重入会发两条", markAt < postAt)

        // NotifyUser 分支必须走这个去重入口，不能直接调 notificationCenter。
        val ensure = bodyOf(controller, "fun ensureRunning()")
        assertTrue("要走去重入口", ensure.contains("RecoveryAction.NotifyUser -> notifyServiceStoppedOnce()"))
        assertFalse(
            "不能绕过账本直接发",
            ensure.contains("RecoveryAction.NotifyUser -> notificationCenter.postServiceStopped("),
        )

        // 账本要在两处复位：服务真的活了（下一次死是新的一次）、用户主动收工。
        val started = bodyOf(controller, "fun onStarted()")
        assertTrue("服务活了要重置账本", started.contains("stoppedNotified = false"))
        val stop = bodyOf(controller, "fun requestStop()")
        assertTrue("主动收工要重置账本", stop.contains("stoppedNotified = false"))
    }
}
