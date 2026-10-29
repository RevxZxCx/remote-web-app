package com.revxzxcx.remotewebapp.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「写凭证簿的地方必须经过同一条连接判据」的结构契约（B004 第六轮审查）。
 *
 * ## 为什么要用源码结构断言
 *
 * `AppContainer` 需要 `Context` 才能在 JVM 上构造（唯一构造点是
 * `DshApplication.kt:53` 的 `AppContainer(this)`），所以它的编排逻辑**永远跑不进单测**。
 * 而 B004 的整个教训就是：判据写对了、但真实执行走的是另一条路径，单测照样全绿。
 *
 * 第六轮审查时抓到的正是这种形状：[addHostLocked] 一手 `credentialStore.saveBook(plan.book)`、
 * 一手 `linkStore.publish(book = plan.book, connection = 旧连接)`，把 `planFor` 整个绕过去了。
 * 后果与 B004 同形：重复添加**当前正在用那台**的地址时，`withUpsert` 会把那一行刷成新
 * `deviceId`（桌面端每次 accept 都发新 id），而连接里嵌的还是旧的 —— 簿说新的、连接用旧的，
 * 之后每个请求都 403 unpaired，且 `Paired → Paired` 不发射、没有任何事件纠正它。
 *
 * 这种「常量/纯函数写对了、但没接上线」的缺陷，只能靠读源码守住。同一手法在
 * `ui/home/DeviceBarRowMenuAnchorTest.kt` 里已用过（B006 的菜单锚点也是语义树测不出来）。
 */
class HostWritePathContractTest {

    private fun appContainerSource(): String {
        val relative = "src/main/java/com/revxzxcx/remotewebapp/core/AppContainer.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 AppContainer.kt，试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * 去掉整行注释。
     *
     * 结构断言要判断「源码里有没有这段调用」，而**注释里提到代码**是正常的（本轮审查就在
     * 注释里写了「早先是 `linkStore.current().connection?.close()`」）。不剥注释，这类
     * 说明性文字会被当成真实调用——第一版断言就是这么误报的。
     */
    private fun codeOnly(body: String): String =
        body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** 把某个函数体抠出来（从函数签名到下一个顶格 `private`/`suspend`/`}` 之前）。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        // 用大括号配对找出函数体，避免被内部的 lambda 打断。
        val open = source.indexOf('{', start)
        assertTrue("`$signature` 后面没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("`$signature` 的大括号没有配对")
    }

    @Test
    fun `添加主机的写路径必须经过 installConnection 而不是自己发布`() {
        val body = codeOnly(bodyOf(appContainerSource(), "private suspend fun addHostLocked("))
        assertTrue(
            "addHostLocked 必须调用 installConnection(plan.book)：绕过它就是不判连接，" +
                "重复添加当前那台会把 deviceId 刷新掉而连接仍拿旧的（B004 同形）",
            body.contains("installConnection(plan.book)"),
        )
        assertTrue(
            "addHostLocked 不能自己调 linkStore.publish —— 那会把连接原样带过、跳过 planFor",
            !body.contains("linkStore.publish"),
        )
    }

    @Test
    fun `写凭证簿的每个入口都必须经过 installConnection`() {
        val source = appContainerSource()
        // 会改「当前是哪台 / 那台的端点」的入口：必须判连接。
        val mustInstall = listOf(
            "suspend fun onPaired(",
            "private suspend fun addHostLocked(",
            "suspend fun switchHost(",
            "suspend fun updateEndpoint(",
            "suspend fun removeHost(",
        )
        mustInstall.forEach { signature ->
            val body = codeOnly(bodyOf(source, signature))
            assertTrue("`$signature` 必须调用 installConnection", body.contains("installConnection("))
        }
    }

    @Test
    fun `只改显示名不许重建连接 它必须单独发布而不走 installConnection`() {
        // 反例方向：renameHost 若也走 installConnection，会命中 Reuse 分支等于空转，
        // 语义上还容易让人以为「改名会换连接」。这里钉住它只看连接不动连接。
        val body = codeOnly(bodyOf(appContainerSource(), "suspend fun renameHost("))
        assertTrue(
            "renameHost 只该发布簿、连接原样带过（label 不进 socket）",
            body.contains("linkStore.publish(book = next, connection = linkStore.current().connection)"),
        )
    }

    @Test
    fun `新连接发布之后才允许关掉旧连接`() {
        // 第二轮审查抓到的：早先 installRebuilt 一上来就 `close()` 旧连接，之后要跨过
        // `withContext(Dispatchers.IO) { cacheLazy }` 这个挂起点才发布新连接。中间任何一步
        // 抛异常，`link` 就永远停在一个**已经关掉的**旧连接上，而 `_state` 还是 `Paired`——
        // 界面不报错、不重试、也不回配对页。
        val body = codeOnly(bodyOf(appContainerSource(), "private suspend fun installRebuilt("))
        val publishAt = body.indexOf("linkStore.publish(book = book, connection = rebuilt)")
        val closeAt = body.indexOf("previous?.close()")
        assertTrue("installRebuilt 里必须发布新连接", publishAt >= 0)
        assertTrue("installRebuilt 里必须关掉旧连接", closeAt >= 0)
        assertTrue(
            "必须先发布新连接、再关旧连接：反过来的话挂起点上抛异常就卡在死连接上",
            publishAt < closeAt,
        )
        assertTrue(
            "不该在发布之前直接 close linkStore.current().connection",
            !body.contains("linkStore.current().connection?.close()"),
        )
    }

    @Test
    fun `掉回未配对时也是先发布空连接再关旧的`() {
        val body = codeOnly(bodyOf(appContainerSource(), "private suspend fun installConnection("))
        val publishAt = body.indexOf("linkStore.publish(book = book, connection = null)")
        val closeAt = body.indexOf("previous?.close()")
        assertTrue("Drop 分支必须发布空连接", publishAt >= 0)
        assertTrue("Drop 分支必须关掉旧连接", closeAt >= 0)
        assertTrue(
            "Drop 分支也要先发布再关：观察者不该看到一条已经关掉的连接",
            publishAt < closeAt,
        )
    }

    @Test
    fun `LinkStore 是唯一发布点 别处不许直接改 flow`() {
        val source = appContainerSource()
        // `_link` 是 LinkStore 私有字段，AppContainer 里只应出现 linkStore.publish/link/current。
        assertTrue(
            "AppContainer 里不该出现对 _link 的直接赋值",
            !source.contains("_link.value"),
        )
    }
}
