package com.revxzxcx.remotewebapp.core.changes

import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.protocol.TypertException

/**
 * 「本轮改了什么」的读取器。
 *
 * 数据源是插件 `@deepseek-ai/dsh-client-ui-deliverables` 注册的三个**普通鉴权 HTTP 路由**
 * （不是 Typert RPC，见 `lib/index.js:4-19`）：摘要随 Session 存在 Host 内存里，路由只做
 * 坐标翻译。
 *
 * 三条限制（UI 必须如实告知用户，见 [ChangesLimitations]）：
 * 1. 桌面端重启后旧轮次返回 404（摘要在 Host 进程内存里）；
 * 2. 非 git 仓库里只能看到文件工具（`write`/`edit`/`str_replace_editor`）的改动；
 * 3. 单文件超过 2 MiB 不做 diff。
 */
class ChangesRepository(private val connection: DshConnection) {

    /**
     * 取某个 `workspace/changes` 事件（`seq`）对应轮次的改动摘要。
     *
     * 返回**三态** [ChangesEntry] 而不是可空摘要（R005 P1-2）：
     *  - [ChangesEntry.Unavailable] = 服务端 404，记录随 Host 进程消失，**终态**、不该重试；
     *  - [ChangesEntry.Failed] = 解析不出来，**可重试**；
     *  - [ChangesEntry.Loaded] = 拿到摘要（`total == 0` 才是「这一轮没改文件」）。
     *
     * 传输层失败不进返回值——直接抛 [TypertException]，让调用方能把它和 404 分开，
     * 既不会把网络抖动说成「记录已不可用」，也保住了重试的机会。
     */
    suspend fun summary(sessionId: String, seq: Long): ChangesEntry {
        val element = get(PATH_SUMMARY, mapOf("sessionId" to sessionId, "seq" to seq.toString()))
            ?: return ChangesEntry.Unavailable
        val parsed = ChangesParser.summary(element) ?: return ChangesEntry.Failed("改动摘要无法解析")
        return ChangesEntry.Loaded(parsed)
    }

    /**
     * 取摘要里第 [index] 个文件的 diff。
     *
     * 返回 `null` **只表示服务端 404**（记录随 Host 进程消失，终态）。解析不出来会抛
     * [TypertException]（`bad-envelope`）——把「服务端换了 diff 格式」和「记录没了」
     * 混成同一个 null，就会把一次真实故障说成「桌面端重启了」（R005 P2）。
     */
    suspend fun diff(sessionId: String, seq: Long, index: Int): ChangesDiff? {
        val element = get(
            PATH_DIFF,
            mapOf("sessionId" to sessionId, "seq" to seq.toString(), "index" to index.toString()),
        ) ?: return null
        return ChangesParser.diff(element)
            ?: throw TypertException(TypertException.CODE_BAD_ENVELOPE, "差异内容无法解析")
    }

    /**
     * 让桌面端打开（或定位到）这个文件。
     *
     * 只在用户明确点「在电脑上打开」时调用：它会在桌面端弹出东西。失败（桌面端不可用、路径
     * 没法映射）抛 [TypertException]。
     */
    suspend fun openOnDesktop(sessionId: String, seq: Long, index: Int) {
        connection.routes.post(
            PATH_OPEN,
            mapOf("sessionId" to sessionId, "seq" to seq.toString(), "index" to index.toString()),
        )
    }

    /**
     * GET 一个路由，把「摘要在内存里已过期」折叠成 null。
     *
     * 为什么按 404 判定而不是看文本：`dsh-client-ui-deliverables/lib/index.js:105-108` 的
     * `failureStatus` 把 `session/not-found`、`SESSION_QUERY_EVENT_NOT_FOUND`、
     * `ENOENT` 等一律映射成 404，文本只有一种。所以 404 就等价于「这个轮次的记录没了」。
     */
    private suspend fun get(path: String, query: Map<String, String?>) =
        try {
            connection.routes.getJson(path, query)
        } catch (e: TypertException) {
            if (e.code == CODE_HTTP_NOT_FOUND) null else throw e
        }

    companion object {
        /** `dsh-client-ui-deliverables/lib/index.js:4`。 */
        const val PATH_SUMMARY = "changes.summary"

        /** `dsh-client-ui-deliverables/lib/index.js:6`。 */
        const val PATH_DIFF = "changes.diff"

        /** `dsh-client-ui-deliverables/lib/index.js:8`。 */
        const val PATH_OPEN = "changes.open"

        /** [com.revxzxcx.remotewebapp.core.protocol.RemoteRoutesClient] 对非 2xx 的编码。 */
        const val CODE_HTTP_NOT_FOUND = "http-404"
    }
}
