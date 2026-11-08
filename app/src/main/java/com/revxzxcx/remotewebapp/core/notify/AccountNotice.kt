package com.revxzxcx.remotewebapp.core.notify

/**
 * 账号侧的两条提醒（R056 缺口 4）。
 *
 * ## 为什么要接这两条
 * 桌面端的模型凭据失效时，服务端不是「某一轮失败」——是**所有**用这个 provider 的任务
 * 一起开始失败，而且失败原因在 App 里的表现是「模型报了一句看不懂的错」。
 * 用户当时的直觉是「这模型是不是坏了」，而真实原因是「去桌面上重新登录一下就好」。
 *
 * asar 实证两条事件都是**无负载 emit**：
 * ```js
 * this.ctx.emit("deepseek-account/session-expired");
 * scope.on("agent/error", ({ error }) => {
 *   if (error instanceof LlmError && error.code === "ACCOUNT_SIGN_IN_REQUIRED")
 *     scope.emit("deepseek-account/model-sign-in-required");
 * });
 * ```
 * 所以判定只需要「哪一条」+「是不是已经提醒过」，不需要解析任何字段。
 *
 * ## 两条为什么要分开说
 * - `session-expired`：授权**已经被撤销/过期**，后台登录页都用不了了。
 * - `model-sign-in-required`：某次请求被拒（`ACCOUNT_SIGN_IN_REQUIRED`），账号本身可能还在。
 * 前者要用户重新登录桌面端账号，后者只要重新登录一下模型 provider。
 * 合成一句话会让人去错地方。
 *
 * ## 为什么复位要靠 `credentials/record-updated`
 * 这两条事件**只报坏消息，不报好消息**：用户回到桌面端登录成功了，服务端不会为此
 * 再发一条「好了」。而白名单里恰好有一条 `credentials/record-updated`——凭据记录变了。
 * 它比坏消息更频繁（每次登录/换 key 都会发），但作为**复位信号**足够：
 * 收到它就把「已提醒过」清掉，下次真失效时用户还能再被提醒一次。
 *
 * 反过来不这么做的话：用户重新登录、过几天又失效，App 因为「这个 eventId/key 已经
 * 提醒过」而一声不吭——那正是这条提醒存在的意义所在。
 */
enum class AccountProblem {
    /** 桌面端账号授权失效（`deepseek-account/session-expired`）。 */
    SessionExpired,

    /** 模型 provider 要求重新登录（`deepseek-account/model-sign-in-required`）。 */
    ModelSignIn,
}

/**
 * 账号事件的判定器。
 *
 * **有状态、非线程安全**：只在协调器的单个事件收集协程里用。
 */
class AccountNoticeGate {

    private var warned: AccountProblem? = null

    /**
     * 收到一条账号事件：要不要提醒。
     *
     * ## 为什么同一族问题只提醒一次，哪怕类型在变
     * 原先的判据是 `warned == problem`，于是 `session-expired` 与
     * `model-sign-in-required` **交替出现**时会来回各响一次：凭据失效期间，每个用不同
     * provider 的请求都会失败，服务端可能先发 `session-expired`（授权没了），紧接着
     * 因为仍有在飞的请求被拒而发 `model-sign-in-required`，再过一会儿又发
     * `session-expired`……用户收到一串针对同一件事的通知。
     *
     * 这两条的真实含义是「桌面端凭据出问题了」，区别只在**去哪修**。所以第一次提醒
     * 之后，除非收到 `credentials/record-updated`（用户动手了），否则不再打扰。
     * 而先报的那条通常就是更根本的那条（授权都没了，provider 自然也登不上）。
     */
    fun onProblem(problem: AccountProblem): Boolean {
        if (warned != null) return false
        warned = problem
        return true
    }

    /**
     * 收到 `credentials/record-updated`：凭据变了，把「已提醒过」清掉。
     *
     * @return 之前是否提醒过——为 true 时调用方应该撤掉那条通知（问题已经解决了）。
     */
    fun onCredentialsChanged(): Boolean {
        if (warned == null) return false
        warned = null
        return true
    }

    /** 换主机时清空（账号是每台桌面端机器各自的）。 */
    fun reset() {
        warned = null
    }
}
