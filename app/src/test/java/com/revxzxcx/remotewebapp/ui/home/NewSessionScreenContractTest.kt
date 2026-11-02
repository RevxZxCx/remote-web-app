package com.revxzxcx.remotewebapp.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R055「新增会话页」的结构契约（源码结构断言）。
 *
 * ## 为什么这一页要用源码断言而不是渲染断言
 *
 * 三件事在 Robolectric 里断不出来，但任何一件错都会让这一页「看起来像坏的」：
 *
 * 1. **输入区必须复用对话页的 `Composer`**。用户 m06896 的原话是「新增会话界面
 *    可以直接使用对话界面」。如果哪天有人图省事在这一页另写一个 `TextField`，
 *    渲染测试照样全绿（都有输入框、都能打字），但附件、命令、引用、键盘处理
 *    这些行为会立刻与对话页分叉——这正是 R055 要避免的割裂。
 * 2. **两颗胶囊必须都在**（工作区 + 模式，用户明确要求「增加两个选择栏」）。
 * 3. **`onCreated` 必须把草稿一起交出去**。新建会话只在发送时才建（Q2），
 *    交出去的草稿就是「用户刚打的那句话」——丢了它，用户点发送却看到一条空会话，
 *    与 B005 那次「没有反馈的成功被当成失败」是同一种伤害。
 *
 * 渲染层的用例在别处（`NewSessionViewModelTest` 的 `WorkspaceChipLabelTest`）；
 * 这里只钉「结构没被拆掉」。
 */
class NewSessionScreenContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun screenSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/ui/home/NewSessionScreen.kt",
    )

    private fun appRootSource() = source(
        "src/main/java/com/revxzxcx/remotewebapp/ui/AppRoot.kt",
    )

    /** 抠出顶层函数体（从签名所在行起，大括号配对到闭合）。 */
    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) error("源码里找不到：$signature")
        var depth = 0
        var i = text.indexOf('{', start)
        if (i < 0) error("$signature 后面没有函数体")
        val begin = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
            i++
        }
        error("$signature 的函数体没有闭合")
    }

    @Test
    fun `新增会话页必须复用对话页的 Composer 而不是另写一个输入框`() {
        val body = bodyOf(screenSource(), "fun NewSessionScreen(")
        assertTrue(
            "NewSessionScreen 必须调用 Composer(...)：用户要求「新增会话界面可以直接使用对话界面」，" +
                "另写输入框会让附件/命令/引用/键盘行为与对话页分叉",
            body.contains("Composer("),
        )
        assertTrue(
            "不能自己写 BasicTextField / OutlinedTextField 顶替 Composer",
            !body.contains("BasicTextField(") && !body.contains("OutlinedTextField("),
        )
    }

    @Test
    fun `两颗胶囊都必须渲染 少一个就等于没实现选择栏`() {
        val body = bodyOf(screenSource(), "fun NewSessionScreen(")
        assertTrue(
            "工作区胶囊必须挂在页面上（NewSessionWorkspaceTag）",
            body.contains("PickRow("),
        )
        val pickRow = bodyOf(screenSource(), "private fun PickRow(")
        assertTrue(
            "工作区胶囊必须带 NewSessionWorkspaceTag，渲染测试靠标签找它",
            pickRow.contains("NewSessionWorkspaceTag"),
        )
        assertTrue(
            "模式胶囊必须带 NewSessionPresetTag",
            pickRow.contains("NewSessionPresetTag"),
        )
    }

    @Test
    fun `建好会话后必须把草稿一起交出去 不能只交会话 id`() {
        val body = bodyOf(screenSource(), "fun NewSessionScreen(")
        assertTrue(
            "onCreated 必须传 state.draft：新建页只在发送时建会话（Q2），" +
                "草稿没交出去用户就会看到一条空会话",
            body.contains("onCreated(created, state.draft)"),
        )
    }

    @Test
    fun `创建中的进度条与失败横幅都要有 否则用户以为没反应`() {
        val body = bodyOf(screenSource(), "fun NewSessionScreen(")
        assertTrue("创建中必须有可见反馈（CreatingBanner）", body.contains("CreatingBanner()"))
        assertTrue("失败必须有横幅（ErrorBanner）", body.contains("ErrorBanner("))
    }

    @Test
    fun `建好之后必须先清掉 created 再跳走 否则再也进不了这一页`() {
        // ViewModel 挂在 `new-session-$scopeToken` 上，退出页面不会 clear。
        // `created` 留着非空，用户下次点「＋」时这一屏刚组合就立刻再跳一次。
        val source = screenSource()
        val body = bodyOf(source, "LaunchedEffect(state.created?.sessionId)")
        assertTrue(
            "必须先 clearCreated() 再 onCreated(...)，实际是：$body",
            body.indexOf("clearCreated()") in 0 until body.indexOf("onCreated("),
        )
    }

    @Test
    fun `交接草稿必须校验归属会话 否则进程重建后会发错地方`() {
        // `pendingNewSessionPrompt` 是 rememberSaveable 的，正是为了跨越进程重建。
        // 重建后用户点开的未必是刚建那条会话；不校验归属就会把那段话发进不相关的会话。
        val root = appRootSource()
        val body = bodyOf(root, "LaunchedEffect(pendingNewSessionPrompt")
        assertTrue(
            "消费前必须判 target != session.sessionId 并丢弃，实际是：$body",
            body.contains("target != session.sessionId") && body.contains("return@LaunchedEffect"),
        )
        assertTrue(
            "交接时必须记下归属会话 id",
            root.contains("pendingNewSessionSessionId = created.sessionId"),
        )
    }

    @Test
    fun `副标题必须说明还没有建出会话 避免用户误以为已经建好`() {
        val body = bodyOf(screenSource(), "fun NewSessionScreen(")
        assertTrue(
            "顶部副标题要写清「发第一条消息才会建出会话」",
            body.contains("发第一条消息才会建出会话"),
        )
    }
}
