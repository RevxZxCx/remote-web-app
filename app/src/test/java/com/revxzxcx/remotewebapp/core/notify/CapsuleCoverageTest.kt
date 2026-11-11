package com.revxzxcx.remotewebapp.core.notify

import com.revxzxcx.remotewebapp.core.capsule.CapsulePhase
import com.revxzxcx.remotewebapp.core.capsule.PendingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 开了流体云之后不该再推重复的普通通知（R062-B019）。
 *
 * 用户原话（m07102）：「开启流体云通知的时候，基础的普通通知有些功能已经被流体云
 * 替代了，普通通知却还在推送，这里属于重复通知了」。
 *
 * ## 这组用例要守住的三件事
 * 1. **判定要保守**：任何一项不满足就不压。漏压一条重复通知用户顶多被多提醒一次；
 *    错压一条，任务跑完了却什么都收不到——后者是这条需求里唯一的真正风险。
 * 2. **压掉要记账**：胶囊只有一个展示位，下一秒可能被别的会话的审批抢走。
 *    那时这条会话从胶囊上消失，而它的通知从头到尾没发过。
 * 3. **两条投递路径都要过这道闸**：即时路径（`running` 翻转）与断线补账路径
 *    各有一处 `deliver(...)`。少改一处，那半边的重复通知还在，而真机上表现为
 *    「有时重复、有时不重复」——最难查的一类。
 */
class CapsuleCoverageTest {

    // ---------- 第一部分：纯判定 ----------

    @Test
    fun `胶囊正在显示这条会话的已完成时 普通通知该被压掉`() {
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            phase = CapsulePhase.Done,
            posting = true,
        )

        assertTrue(coverage.coversDone("s1"))
    }

    @Test
    fun `出错也算已经说过 否则用户只看到一个出错却点不进原因`() {
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            phase = CapsulePhase.Failed,
            posting = true,
        )

        assertTrue(coverage.coversDone("s1"))
    }

    @Test
    fun `正在显示的是别的会话时 不能压这条`() {
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            phase = CapsulePhase.Done,
            posting = true,
        )

        assertFalse(coverage.coversDone("s2"))
    }

    @Test
    fun `胶囊其实发不出来的时候不能压 否则用户彻底收不到提醒`() {
        // 用户没开「流体云显示实时活动」：canPost() 为 false，胶囊根本不在屏幕上。
        // 这种状态下若还压掉普通通知，任务跑完就真的没有任何提示了。
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            phase = CapsulePhase.Done,
            posting = false,
        )

        assertFalse(coverage.coversDone("s1"))
    }

    @Test
    fun `胶囊正在显示待批准的时候不能压完成通知`() {
        // 待处理在焦点选举里压过已完成（CapsuleFocus 第一层），所以「待批准 + 另一条跑完」
        // 是常态。此刻卡片上没有任何「已完成」的痕迹，压掉就是让它彻底消失。
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            pending = PendingKind.Approval,
            phase = CapsulePhase.Done,
            posting = true,
        )

        assertFalse(coverage.coversDone("s1"))
    }

    @Test
    fun `胶囊显示待回答的时候同样不能压`() {
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            pending = PendingKind.Question,
            phase = CapsulePhase.Done,
            posting = true,
        )

        assertFalse(coverage.coversDone("s1"))
    }

    @Test
    fun `还在跑的时候不能压 那时候没有已完成可说`() {
        val coverage = CapsuleCoverage(
            sessionId = "s1",
            phase = CapsulePhase.Running,
            posting = true,
        )

        assertFalse(coverage.coversDone("s1"))
    }

    @Test
    fun `空覆盖什么都不压 这是没有胶囊时的默认值`() {
        assertFalse(CapsuleCoverage.NONE.coversDone("s1"))
        assertNull(CapsuleCoverage.NONE.sessionId)
        assertFalse(CapsuleCoverage.NONE.posting)
    }

    // ---------- 第二部分：欠账 ----------

    @Test
    fun `压掉的完成通知要记账 否则胶囊让位之后它永远不会补发`() {
        val registry = CapsuleCoverageRegistry()

        registry.deferDone("s1", DeferredDone(failed = false, reason = null))

        assertEquals(1, registry.deferredCount)
        assertEquals(listOf("s1"), registry.deferredIds())
    }

    @Test
    fun `取出欠账后账上就没有了 不能补发两次`() {
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = true, reason = "boom"))

        val first = registry.takeDeferred("s1")
        val second = registry.takeDeferred("s1")

        assertEquals(DeferredDone(failed = true, reason = "boom"), first)
        assertNull(second)
        assertEquals(0, registry.deferredCount)
    }

    @Test
    fun `没被欠过的会话取出来是 null`() {
        val registry = CapsuleCoverageRegistry()

        assertNull(registry.takeDeferred("never"))
    }

    @Test
    fun `同一会话被压两次只留最后一次 因为通知 id 本来就只保留一条`() {
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = false, reason = null))
        registry.deferDone("s1", DeferredDone(failed = true, reason = "later"))

        assertEquals(1, registry.deferredCount)
        assertEquals(DeferredDone(failed = true, reason = "later"), registry.takeDeferred("s1"))
    }

    @Test
    fun `丢掉欠账之后不再补发 用于会话已删或用户已经看过`() {
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = false, reason = null))

        registry.dropDeferred("s1")

        assertNull(registry.takeDeferred("s1"))
    }

    @Test
    fun `deferredIds 返回的是快照 边遍历边补发不会并发修改`() {
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = false, reason = null))

        val ids = registry.deferredIds()
        // 模拟补发过程中账本被事件协程改写：若 deferredIds 返回的是视图，
        // 这里会 ConcurrentModificationException（同 B008 RemoteStreamMux.close 的坑）。
        registry.takeDeferred("s1")
        registry.deferDone("s2", DeferredDone(failed = false, reason = null))

        assertEquals(listOf("s1"), ids)
    }

    @Test
    fun `账本的每一个出口都要加锁 单线程用例堆不出这个保证`() {
        // 补发是由 `coverage.state` 那条协程驱动的，而 `deferDone` / `dropDeferred`
        // 在别的地方调用：`HashMap` 在「一边遍历 keys 一边写入」时会抛
        // `ConcurrentModificationException`，表现为「偶尔少补一条通知」或直接崩，
        // 都不在单线程用例的射程内。
        //
        // 所以这里不看行为，看源码契约：**声明了锁，就必须每个出口都真的用它**。
        val body = codeOnlyStrict(bodyOf(coverageSource(), "class CapsuleCoverageRegistry"))

        listOf(
            "fun deferDone(",
            "fun takeDeferred(",
            "fun dropDeferred(",
            "val deferredCount",
            "fun deferredIds(",
            // resetCoverage 不碰 HashMap（只给一条 StateFlow 赋值），
            // 它不需要锁，也不该为了通过这条断言而套一把。
            "fun clearDeferred(",
        ).forEach { member ->
            val at = body.indexOf(member)
            assertTrue("账本里找不到 $member", at >= 0)
            val tail = body.substring(at)
            // 取到下一个成员声明为止，避免把后面方法的 synchronized 算进来。
            val end = listOf("\n    fun ", "\n    val ", "\n    private fun ")
                .map { tail.indexOf(it, 1) }
                .filter { it > 0 }
                .minOrNull() ?: tail.length
            val narrow = tail.substring(0, end)
            assertTrue("$member 必须加锁，否则并发下会抛 ConcurrentModificationException", narrow.contains("synchronized(lock)"))
        }
    }

    // ---------- 「划掉胶囊」必须叫醒协调器（R062-B019 第一轮审查 P1-1） ----------
    //
    // 这一条没有行为用例可达：`BroadcastReceiver.onReceive` 在纯 JVM 单测里
    // 只能靠 Robolectric，而这里的缺陷形状是「少接一根线」—— 三段代码分处
    // 三个文件，任何一段被删都不会让现有任何一条用例变红。所以看源码契约。

    @Test
    fun `划掉胶囊必须叫醒协调器 否则被压掉的通知永远不补发`() {
        val receiver = codeOnlyStrict(
            source("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleDismissReceiver.kt"),
        )

        assertTrue(
            "收到 ACTION_DISMISS 后必须调 CapsuleBridge.nudge()：" +
                "划掉不是会话事件，协调器没有任何理由自己重算，覆盖账会一直停在 " +
                "coversDone=true，那条完成通知就永久丢了",
            receiver.contains("CapsuleBridge.nudge()"),
        )
        // 顺序也要对：先记账（决定「要不要重发」的依据）再叫醒（重算的触发）。
        val markAt = receiver.indexOf("CapsuleDismissal.markDismissed()")
        val nudgeAt = receiver.indexOf("CapsuleBridge.nudge()")
        assertTrue("先 markDismissed 再 nudge，否则重算时读到的还是旧状态", markAt in 0 until nudgeAt)
    }

    @Test
    fun `nudge 是一路独立的计数器 不能复用会话状态流`() {
        val bridge = codeOnlyStrict(
            source("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleBridge.kt"),
        )

        assertTrue(
            "信箱要提供 nudges 这路信号（StateFlow<Int>）",
            bridge.contains("val nudges: StateFlow<Int>"),
        )
        assertTrue(
            "nudge() 必须是递增而不是置位：" +
                "用 Boolean 的话连续划两次第二次不变，协调器就少算一次",
            bridge.contains("_nudges.value = _nudges.value + 1"),
        )
    }

    @Test
    fun `协调器必须订阅 nudges 且走的是去抖入口`() {
        val container = codeOnlyStrict(
            source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"),
        )
        val coordinator = codeOnlyStrict(
            source("src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCoordinator.kt"),
        )

        assertTrue(
            "watchCapsule 里要订阅 CapsuleBridge.nudges",
            container.contains("CapsuleBridge.nudges.collect { capsule.recomputeNow() }"),
        )
        // 必须走去抖：划掉可能连着发生，每次都真的 notify()/cancel() 一遍太吵。
        // 取 recomputeNow 的函数体来断言，避免在断言里内嵌换行（那正是上一次写坏文件的原因）。
        val nowAt = coordinator.indexOf("internal fun recomputeNow()")
        assertTrue("协调器要暴露 recomputeNow()", nowAt >= 0)
        val nowBody = coordinator.substring(nowAt, coordinator.indexOf("}", nowAt) + 1)
        assertTrue(
            "recomputeNow 应当转发给 scheduleRecompute（去抖），不是直接 recompute()：" +
                "划掉可能连着发生，每次都真的 notify()/cancel() 一遍太吵",
            nowBody.contains("scheduleRecompute()") && !nowBody.contains("recompute()"),
        )
    }

    // ---------- 第二轮审查：换主机与原子性 ----------

    @Test
    fun `胶囊让位与记账之间不能有窗口 否则这条通知永久丢失`() {
        // 第二轮审查 P2-2。分开两步的时序：读 coversDone=true → 胶囊 publish(NONE)
        // → flushDeferred 在 deferredIds() 还空时跑完 → 才 deferDone 落地。
        // 此后胶囊每轮只再发 NONE，StateFlow 去重不再 emit，于是既没胶囊也没通知。
        // 必须「读判定 + 写欠账」在同一把锁里一次做完。
        val registry = CapsuleCoverageRegistry()
        registry.publish(CapsuleCoverage(sessionId = "s1", phase = CapsulePhase.Done, posting = true))

        val taken = registry.deferUnlessCovered("s1", DeferredDone(failed = false, reason = null))

        assertTrue("被覆盖时必须记账并返回 true，调用方据此不投递", taken)
        assertEquals(1, registry.deferredCount)
    }

    @Test
    fun `判定必须写在锁里面 否则两步之间胶囊就能让位`() {
        // 第二轮审查 P2-2 的原子性在单线程用例里**根本观察不到**：
        // 变异体「把 coversDone 移到 synchronized 外面、只把写欠账放进去」
        // 跑满 39 条全绿。这不是用例不够，是这类缺陷必须靠源码结构钉住。
        //
        // 判据：`coversDone(` 的位置必须晚于 `synchronized(lock) {`，
        // 也就是判定发生在临界区**内**。移到外面就立刻变红。
        // 不能用 `bodyOf`：这个函数的签名和函数体之间隔着 `=`，`bodyOf` 会把
        // 外层大括号定位到 `synchronized(lock) {` 那个开括号上，截出来的片段
        // 正好从锁里面开始，`synchronized` 一词根本不在里面（第一版就栽在这）。
        // 改按「本函数声明到下一个函数声明」切片。
        val source = codeOnlyStrict(coverageSource())
        val start = source.indexOf("fun deferUnlessCovered(")
        val end = source.indexOf("fun deferDone(", start)
        assertTrue("要先找到这个函数", start > 0 && end > start)
        val body = source.substring(start, end)
        val lockAt = body.indexOf("synchronized(lock) {")
        val checkAt = body.indexOf("coversDone(")

        assertTrue("要先拿到锁", lockAt > 0)
        assertTrue("判定与记账都得在里面", checkAt > 0)
        assertTrue(
            "判定必须在临界区内：`coversDone` 出现在 `synchronized(lock) {` 之后",
            checkAt > lockAt,
        )
    }

    @Test
    fun `没被覆盖时 deferUnlessCovered 什么都不写`() {
        val registry = CapsuleCoverageRegistry()
        registry.publish(CapsuleCoverage(sessionId = "other", phase = CapsulePhase.Done, posting = true))

        val taken = registry.deferUnlessCovered("s1", DeferredDone(failed = false, reason = null))

        assertFalse("别的会话被覆盖不等于这条被覆盖", taken)
        assertEquals(0, registry.deferredCount)
    }

    @Test
    fun `deliverUnlessCovered 只能调账本的原子入口 不能自己先读 coversDone`() {
        // 契约断言：源码里这个函数不该再出现 `coversDone`，判定只在账本内部做。
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "private fun deliverUnlessCovered("))
        assertTrue(
            "判定必须走 deferUnlessCovered 这个原子入口",
            body.contains("deferUnlessCovered("),
        )
        assertFalse(
            "函数体里不该再自己读 coversDone —— 那就是两处判定，也就又有了窗口",
            body.contains("coversDone("),
        )
    }

    @Test
    fun `换主机必须连欠账一起丢 普通重连必须保留`() {
        // 第二轮审查 P2-1：换主机走的是 Rebuild → attach(新 client)，
        // 原先「client == null 才丢」把这条路漏了。
        val notify = codeOnlyStrict(source("src/main/java/com/revxzxcx/remotewebapp/core/notify/NotifyCoordinator.kt"))
        val container = codeOnlyStrict(source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"))

        assertTrue(
            "attach 要接受 dropDeferred 参数",
            notify.contains("fun attach(client: RemoteEventsClient?, dropDeferred: Boolean = client == null)"),
        )
        assertTrue(
            "clearDeferred 的判据要挂在 dropDeferred 上，不是挂在 client == null 上",
            notify.contains("if (dropDeferred) coverage?.clearDeferred()"),
        )
        // 第三轮审查把判据从「走没走 Rebuild」收紧到「hostId 有没有变」，
        // 形状改成 `dropDeferred = !sameHost`；具体断言见下面那条用例。
        assertTrue(
            "换主机那条调用点必须显式声明是否丢账",
            container.contains("notifier.attach(rebuilt.remoteEvents, dropDeferred ="),
        )
    }

    @Test
    fun `补发循环里每条都要重读覆盖态 不能用循环外的快照`() {
        // 第二轮审查 P3-1：循环体里的 postDone 是 binder 调用，期间胶囊可能
        // 重新覆盖了后面某条。用旧快照会把本不该补发的也发出去。
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "private suspend fun flushDeferred("))
        assertTrue(
            "coversDone 的判定要读 registry.state.value（每轮重读）",
            body.contains("registry.state.value.coversDone(sessionId)"),
        )
        assertFalse(
            "不能再把 state.value 取到循环外面存成局部变量",
            body.contains("val current = registry.state.value"),
        )
    }

    @Test
    fun `补发收集器要吞异常 否则一次失败就永久停摆`() {
        // 第二轮审查 P3-2：这是常驻 collect，不像另外三条事件协程崩了只损失一次。
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "fun attach("))
        // 第四轮 P3-1 之后收集器里先撤后补，runCatching 从单行变成块，
        // 所以断言改成「flushDeferred 出现在 runCatching 块内」而不是死抠一行形状。
        val catchAt = body.indexOf("runCatching {")
        val flushAt = body.indexOf("flushDeferred()")
        assertTrue("要包一层 runCatching", catchAt > 0)
        assertTrue("flushDeferred 必须在 runCatching 块里", flushAt > catchAt)
        assertTrue(
            "失败要记日志而不是静默",
            body.contains("Log.w(TAG, \"coverage reconcile failed\", it)"),
        )
    }

    // ---------- 第三轮审查：丢账风险与换机判据 ----------

    @Test
    fun `补发投递失败要把账放回去 否则这条彻底没有信息`() {
        // 第三轮审查 P2-1。`takeDeferred` 先摘账、`postDone` 后投递，而后者末尾是
        // 裸 `nm.notify`：权限在 `canPost()` 之后被收回就抛 `SecurityException`，
        // 被外层 runCatching 吞掉之后，这条账已经摘了却没发出去。
        // 胶囊早已让位、通知栏也没有 —— 是唯一「彻底无信息」的路径。
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "private suspend fun flushDeferred("))
        assertTrue("postDone 必须包在 runCatching 里", body.contains("runCatching {"))
        assertTrue("失败要放回账本", body.contains("registry.deferDone(sessionId, pending)"))
        // 放回必须挂在实际结果上，不能无条件放（那会变成无限重复补发）
        assertTrue("要按投递结果决定是否放回", body.contains("if (!ok) registry.deferDone("))
    }

    @Test
    fun `换机判据必须是 hostId 不能是走没走 Rebuild`() {
        // 第三轮审查 P2-2。`planFor` 比 `(baseUrl, deviceId)`：同一台机器上重新扫码
        // 配对时 deviceId 会变 → 判成 Rebuild，但它还是同一台机器，欠账照样该补。
        // 无条件 dropDeferred = true 会把它们全清掉，正是 B019 要修的症状。
        val container = codeOnlyStrict(source("src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"))
        assertTrue(
            "要用 hostId 比较新旧主机",
            container.contains("previous?.credential?.hostId == credential.hostId"),
        )
        assertTrue(
            "同一台机器不能丢欠账",
            container.contains("dropDeferred = !sameHost"),
        )
        assertFalse(
            "不能再无条件丢",
            container.contains("notifier.attach(rebuilt.remoteEvents, dropDeferred = true)"),
        )
    }

    @Test
    fun `用户点开会话时要丢掉它的欠账 人已经看到了`() {
        // 第三轮审查 P3。点开时若胶囊还覆盖着，flush 会被 coversDone 跳过；
        // 等胶囊让位后照样补发，而用户其实早就看过了。
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "fun onSessionOpened("))
        assertTrue("点开就要丢账", body.contains("coverage?.dropDeferred(sessionId)"))
    }

    @Test
    fun `退配对时才丢欠账 那些 id 属于上一台机器`() {
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = false, reason = null))
        registry.publish(CapsuleCoverage(sessionId = "s1", phase = CapsulePhase.Done, posting = true))

        registry.clearDeferred()

        assertEquals(0, registry.deferredCount)
        // 欠账丢了，但「替谁说话」这件事还在：两者本来就不是一件事，
        // 拆开正是为了不让 `attach` 顺手把它们一起清掉（P1-2）。
        assertEquals("s1", registry.state.value.sessionId)
    }

    @Test
    fun `普通重连只复位覆盖 不能把欠账一起丢掉`() {
        // 这是 P1-2 的核心：网络抖一下重连，那几条「胶囊替我说过了所以还没发」的
        // 完成通知必须还能补发。合成一个 reset() 时它们在 attach 里被静默清空，
        // 用户永远收不到 —— 正是 B019 想消灭的症状。
        val registry = CapsuleCoverageRegistry()
        registry.deferDone("s1", DeferredDone(failed = false, reason = null))
        registry.publish(CapsuleCoverage(sessionId = "s1", phase = CapsulePhase.Done, posting = true))

        registry.resetCoverage()

        assertEquals(CapsuleCoverage.NONE, registry.state.value)
        assertEquals(1, registry.deferredCount)
        assertNotNull(registry.takeDeferred("s1"))
    }

    @Test
    fun `发布之后 state 立刻可读 订阅方不需要额外拉一次`() {
        val registry = CapsuleCoverageRegistry()
        val next = CapsuleCoverage(sessionId = "s9", phase = CapsulePhase.Done, posting = true)

        registry.publish(next)

        assertEquals(next, registry.state.value)
    }

    // ---------- 第三部分：接线契约（源码断言） ----------

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun coverageSource(): String = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/notify/CapsuleCoverage.kt",
    )

    private fun coordinatorSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/notify/NotifyCoordinator.kt",
    )

    private fun capsuleSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleCoordinator.kt",
    )

    private fun containerSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt",
    )

    private fun bridgeSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/core/capsule/CapsuleBridge.kt",
    )

    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /**
     * 连块注释（KDoc）一起去掉的版本。
     *
     * 「数某个调用点出现了几次」这类断言必须用它：KDoc 里为解释约束正好会提到
     * 那个名字，只去 `//` 的话会把自己写的说明数进去，断言永远差一。
     */
    private fun codeOnlyStrict(body: String): String =
        codeOnly(body)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

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
    fun `两条投递路径都必须过覆盖检查 少改一处那半边还在重复`() {
        val text = coordinatorSource()

        // 即时路径（running 翻转）与断线补账路径各有一处 deliver(...)。
        // 只改一处的话，另一条路径上的重复通知照样发得出去。
        assertEquals(
            "两处 OutcomeDecision.Notify 都要走 deliverUnlessCovered",
            2,
            Regex("is OutcomeDecision\\.Notify -> deliverUnlessCovered\\(").findAll(text).count(),
        )
        assertEquals(
            "不该再有绕过覆盖检查的裸 deliver 调用",
            0,
            Regex("is OutcomeDecision\\.Notify -> deliver\\(").findAll(text).count(),
        )
    }

    @Test
    fun `只有完成通知会被压 被截断与被打断的通知必须照发`() {
        val body = codeOnlyStrict(bodyOf(coordinatorSource(), "private fun deliverUnlessCovered("))

        // 那几条讲的是「这一轮没跑成」的具体原因，胶囊上只有一个「出错」词装不下。
        assertTrue(
            "抑制条件必须只认 OutcomeNotice.Done",
            body.contains("notice is OutcomeNotice.Done &&"),
        )

        // 而且**只能有这样一处**抑制判断。
        //
        // 只断言「出现了 Done」挡不住「另外又加一个 Attention 分支」这种改法：
        // 那种改动让被截断/被中断的提醒也消失，而 Done 那半句仍在，断言照绿。
        // 数次数才能把「只有一处闸门」这件事钉住。
        //
        // 第二轮审查之后判定搬进了账本（`deferUnlessCovered` 一把锁里做「读判定 +
        // 写欠账」），所以这里改成：调用方只许调一次原子入口，**不许再自己读
        // `coversDone`** —— 自己读就又变回两步，也就又有了那个丢通知的窗口。
        assertEquals(
            "deliverUnlessCovered 里只该有一处原子入口调用",
            1,
            Regex("deferUnlessCovered\\(").findAll(body).count(),
        )
        assertEquals(
            "判定不能在调用方做，否则又回到 check-then-act",
            0,
            Regex("coversDone\\(").findAll(body).count(),
        )
    }

    @Test
    fun `压掉之前必须先记账 否则就是丢通知`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun deliverUnlessCovered("))
        val deferAt = body.indexOf("deferUnlessCovered(")
        val returnAt = body.indexOf("return")

        assertTrue("压掉的分支里必须调记账入口", deferAt > 0)
        assertTrue("必须先记账再返回，不能记在 return 之后", deferAt < returnAt)

        // 记账与投递必须互斥：`if (deferUnlessCovered(...)) { ...; return }`，
        // 而不是「先 deferDone 再无条件 deliver」—— 后者等于既记账又照发。
        assertTrue(
            "记账要作为投递的前置分支，不能记账后继续往下走",
            body.contains("if (registry.deferUnlessCovered("),
        )
    }

    @Test
    fun `压掉时还要撤掉通知栏里那条旧的 否则卡片和通知栏各说一次已完成`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun deliverUnlessCovered("))

        assertTrue("被压掉时要撤掉同一会话的旧完成通知", body.contains("cancelDone(sessionId)"))
    }

    @Test
    fun `补发之前要重新过一遍门控 不能照搬压掉时的结论`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun flushDeferred("))

        assertTrue("补发前必须重新算门控", body.contains("NoticeGate.decide("))
        assertTrue("Skip 时不再补发", body.contains("if (delivery == NoticeDelivery.Skip) continue"))
    }

    @Test
    fun `补发时还处于覆盖中的会话要跳过 否则刚压完又发一条`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private suspend fun flushDeferred("))

        // 第二轮审查 P3-1：判定要**每轮重读**。循环体里的 postDone 是 binder 调用，
        // 期间胶囊可能重新覆盖了后面某条；用循环外的快照会把它们也发出去。
        assertTrue(
            "要每轮重读覆盖态",
            body.contains("if (registry.state.value.coversDone(sessionId)) continue"),
        )
        assertFalse(
            "不能把 state.value 取到循环外存成局部变量",
            body.contains("val current = registry.state.value"),
        )
    }

    @Test
    fun `attach 要重置覆盖账 否则新机器上同名 id 会被误判成已说过`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "fun attach("))
        val resetAt = body.indexOf("coverage?.resetCoverage()")
        // 早退从单行 `return` 变成了块（欠账要在块内丢），两种形状都认。
        val earlyReturn = body.indexOf("if (client == null) {")
            .let { if (it >= 0) it else body.indexOf("if (client == null) return") }

        assertTrue("attach 里必须重置覆盖账", resetAt > 0)
        assertTrue("重置要在早退之前，断连那次也要清", resetAt < earlyReturn)

        // 欠账什么时候丢：判据是「换了一台机器」，不是「client 是不是 null」
        // （第二轮审查 P2-1）。换主机走 `Rebuild` → `attach(新 client)`，原先
        // 挂在 `client == null` 上把这条路漏了，上一台的欠账会活下来并被下面
        // 新建的 `state.collect` 当场重放出去，变成新主机的僵尸通知。
        val clearAt = body.indexOf("if (dropDeferred) coverage?.clearDeferred()")
        assertTrue("丢欠账要挂在 dropDeferred 上", clearAt > 0)
        assertTrue("丢欠账要在 push 订阅之前，否则订阅一上来就把它们发出去", clearAt < body.indexOf("coverageJob = scope.launch"))
    }

    @Test
    fun `会话被删时要丢掉欠账 补出去只会是点不开的僵尸通知`() {
        val body = codeOnly(bodyOf(coordinatorSource(), "private fun handle(notification: SessionNotification)"))

        assertTrue(body.contains("coverage?.dropDeferred("))
    }

    @Test
    fun `胶囊的每一条退出路径都要把覆盖状态清掉`() {
        val text = codeOnlyStrict(capsuleSource())

        // 出口有四处（选举不出焦点 / 内容不值得显示 / 被用户划掉 / detach），
        // 任何一条漏了发布覆盖账，通知层就会一直以为胶囊在替那条会话说话，
        // 于是永久压掉它的完成通知——用户既看不到胶囊也收不到通知。
        //
        // 判据取「全文件只有一处 center.cancel()，且它在 cancelCard 里」：
        // 这比逐个数出口更耐改——将来加第五个出口时，只要走了 cancelCard
        // 就不用动这条断言，若绕过它则立刻红。
        assertEquals(
            "全文件只该有一处 center.cancel()，就是 cancelCard 里那一处",
            1,
            Regex("center\\.cancel\\(\\)").findAll(text).count(),
        )

        val cancelCard = codeOnly(bodyOf(capsuleSource(), "private fun cancelCard()"))
        assertTrue("cancelCard 里必须发布空覆盖", cancelCard.contains("coverage?.publish("))
        assertTrue(cancelCard.contains("CapsuleCoverage.NONE"))
    }

    @Test
    fun `发布覆盖状态时不能只在 post 成功时才发`() {
        val text = capsuleSource()
        val body = codeOnly(bodyOf(text, "internal fun recompute()"))
        val publishAt = body.indexOf("coverage?.publish(")
        val ifPosted = body.indexOf("if (posted)")

        assertTrue("recompute 里必须发布覆盖状态", publishAt > 0)
        // post() 返回 false 有两种原因：用户没开实时活动（长期）、notify 抛异常（瞬时）。
        // 返回值分不出来，所以发布不能放进 if (posted)。
        assertTrue(
            "覆盖状态要无条件发布，判据是「意图显示什么」而不是 post 的返回值",
            ifPosted < 0 || publishAt < ifPosted,
        )
    }

    @Test
    fun `覆盖状态里必须带上 posting 否则用户没开实时活动时会被静默压掉`() {
        val body = codeOnly(bodyOf(capsuleSource(), "internal fun recompute()"))

        assertTrue(body.contains("posting = center.canPost()"))
    }

    @Test
    fun `容器必须给两层同一份账本`() {
        val text = containerSource()
        val notifierAt = text.indexOf("val notifier: NotifyCoordinator")
        val capsuleAt = text.indexOf("internal val capsule: CapsuleCoordinator")
        val declareAt = text.indexOf("private val capsuleCoverage = CapsuleCoverageRegistry()")

        assertTrue("容器要建一份账本", declareAt > 0)
        assertTrue("账本要早于通知层，否则初始化顺序错", declareAt < notifierAt)
        assertTrue("账本要早于胶囊层", declareAt < capsuleAt)
        assertEquals(
            "通知层与胶囊层必须传同一份",
            2,
            Regex("coverage = capsuleCoverage").findAll(text).count(),
        )
    }
    // ---- 第四轮审查的 4 条 P3（审查判定不影响收敛，仍照修） ----

    @Test
    fun `覆盖变成已完成时要撤掉已躺在通知栏里的那条 否则先发后接管会重复`() {
        val text = codeOnly(coordinatorSource())
        val withdrawAt = text.indexOf("private fun withdrawCoveredDone(")
        assertTrue("要有撤通知这一步", withdrawAt > 0)

        val body = codeOnly(bodyOf(text, "private fun withdrawCoveredDone("))
        assertTrue(
            "必须先判 coversDone，否则会误撤用户还没看的完成通知",
            body.contains("coversDone("),
        )
        assertTrue("要真的撤", body.contains("center.cancelDone("))

        // 撤与补都要在收集器里，且撤在补之前：反过来会先补发一条、再被下一次重算
        // 撤掉，通知栏闪一下。
        val collectAt = text.indexOf("registry.state.collect")
        val callWithdraw = text.indexOf("withdrawCoveredDone(coverageNow)")
        val callFlush = text.indexOf("flushDeferred()", collectAt)
        assertTrue("收集器里要调撤通知", callWithdraw > collectAt)
        assertTrue("先撤后补", callWithdraw < callFlush)
    }

    @Test
    fun `欠账要带上记账时的会话名 否则同机重连补发时会退化成未命名会话`() {
        // data class 是圆括号不是大括号体，bodyOf 会一路找到下一个 class 的花括号，
        // 所以这里按位置切一段。
        // 顺带：这里必须用 codeOnlyStrict。KDoc 里写了 `names.clear()`，
        // 第一个右括号是它的，切出来的声明里就不会有 sessionName。
        val coverageText = codeOnlyStrict(coverageSource())
        val declStart = coverageText.indexOf("data class DeferredDone(")
        assertTrue("要有 DeferredDone 声明", declStart > 0)
        val decl = coverageText.substring(declStart, coverageText.indexOf(")", declStart) + 1)
        assertTrue("记账时要存下标题", decl.contains("val sessionName: String?"))

        val text = codeOnly(coordinatorSource())
        val flush = bodyOf(text, "private suspend fun flushDeferred()")
        assertTrue(
            "补发时要优先用最新的标题、回落记账时那个",
            flush.contains("names.titleOf(sessionId) ?: pending.sessionName"),
        )
        assertTrue(
            "不能只用 titleOf —— attach 先 names.clear() 再订阅，那一刻标题表是空的",
            !flush.contains("NoticeText.sessionName(names.titleOf(sessionId), sessionId)"),
        )

        // 构造点只有一个：压制那条走 `DeferredDone(failed, reason, sessionName)`；
        // 投递失败放回时复用现成的 `pending` 对象（它本来就带着 sessionName），
        // 不该、也确实没有再构造一次。
        assertEquals(
            "构造欠账时必须把 sessionName 传进去",
            1,
            Regex("DeferredDone\\(" + "[" + "^" + ")]*sessionName")
                .findAll(text).count(),
        )
    }

    @Test
    fun `Bridge 的 reset 要清三路真状态 但不能清 nudges`() {
        val body = codeOnly(bodyOf(codeOnly(bridgeSource()), "internal fun reset()"))

        assertTrue(body.contains("_sessions.value = emptyList()"))
        assertTrue(body.contains("_viewing.value = null"))
        assertTrue(body.contains("_live.value = null"))
        assertTrue("nudges 是计数器不是状态，不该被 reset 清掉", !body.contains("_nudges.value"))

        // KDoc 说的路数必须与实际一致（第四轮审查 P3-3：原写「三个」）
        val kdoc = bridgeSource()
        assertTrue("要说四个 StateFlow", kdoc.contains("四个 `StateFlow`"))
        assertTrue("不能还留着「三个」", !kdoc.contains("三个 `StateFlow`"))
    }
}
