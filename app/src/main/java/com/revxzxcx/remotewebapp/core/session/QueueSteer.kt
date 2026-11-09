package com.revxzxcx.remotewebapp.core.session

/**
 * 排队消息「插队」的**点击闸**（B009）。
 *
 * 用户原话：「排队消息在发出的时候没有做锁，发送排队消息后，重复点击插队会报错……
 * 应该改为提醒而不是报错。」
 *
 * 插队这个动作在服务端是**一次性**的：条目第一次被 steer 就从 `next-turn` 挪进当前轮，
 * 第二次再 steer 必然抛 `session/steer-unavailable`。所以这里要回答的不是「能不能插队」，
 * 而是「这一次点击该不该真的发 RPC」——不该发的时候给一句人话提醒，而不是让一个必然
 * 失败的请求把英文错误码写到错误条上。
 *
 * 做成纯函数是为了能直接断言：它不碰状态、不碰网络，ViewModel 只负责把
 * [ChatUiState.pendingQueueIds] 传进来。
 */
object QueueSteer {

    /** 请求还在飞：再点一下是重复提交。 */
    const val NOTICE_IN_FLIGHT: String = "这条正在插队，稍等一下。"

    /**
     * 编辑 / 删除的请求还在飞：再点一下是重复提交。
     *
     * R058 第二轮审查 P2：串行化掉 `queueJob?.cancel()` 之后，删除连点会连着发两个请求，
     * 第二个必然被拒。文案要说清「正在处理」而不是「插队」，因为这个闸也管编辑与删除。
     */
    const val NOTICE_QUEUE_BUSY: String = "这条正在处理，稍等一下。"

    /** 已经插过了（本地就能看出来：条目已不在 `next-turn`）。 */
    const val NOTICE_ALREADY: String = "这条已经插过队了，不用再点。"

    /**
     * 这次插队点击应该被拦下吗？
     *
     * @param message 目标条目。
     * @param pendingIds 当前有 `updateQueue` 请求在飞的条目 id（[ChatUiState.pendingQueueIds]）。
     * @return 需要拦下时返回给用户看的一句话，放行返回 `null`。
     */
    fun blockReason(message: QueuedMessage, pendingIds: Set<String>): String? = when {
        // 同一条已经在飞：再发一个请求只会换来一个必然的拒绝。
        message.id in pendingIds -> NOTICE_IN_FLIGHT
        // 条目已经不在 `next-turn` 了，服务端一定会拒绝；本地就能判出来，不必往返一次。
        !message.isAwaitingTurn -> NOTICE_ALREADY
        else -> null
    }

    /**
     * 服务端拒绝插队时给用户的提醒。
     *
     * 与 [blockReason] 分开：那条是**发请求前**的本地判断，这条是**收到拒绝后**的降级。
     * 措辞必须是中性的（R058 审查 P2）：`session/steer-unavailable` 不只表示
     * 「已经插过队」，它还覆盖「会话已经不在运行」这类情况。
     * 断言「已经插过了」会让用户以为操作**成功**了，于是不再重试，转而去别处找。
     */
    const val NOTICE_REJECTED: String = "这条没能插队，排队状态可能已经变了。"
}
