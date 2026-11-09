package com.revxzxcx.remotewebapp.core.capsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 用户从通知栏划掉了胶囊（R059）。
 *
 * ## 为什么必须处理
 * 官方文档明确要求实时更新类通知提供 `deleteIntent`：「用户可能临时隐藏它，
 * 应用应当知道」。系统设置里也有「在状态栏左右滑动可快速切换或隐藏实时活动胶囊」
 * ——真机实测滑到尽头整个胶囊会消失（调研文档 3.7-A）。
 *
 * 不知道的话会出两种问题：一是我们继续以为「用户看得见」而不再重发，
 * 二是用户等了一会儿发现再也没动静。
 *
 * ## 这里只记账，不做决定
 * 收到后把 [CapsuleDismissal] 标成「被手动划掉」，要不要重发由协调器按
 * 「是否有新的待处理事项」决定——新请求必须能顶掉用户的这次手动隐藏，
 * 否则一次误划会让后续审批全部静默。
 */
class CapsuleDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CapsuleCenter.ACTION_DISMISS) return
        CapsuleDismissal.markDismissed()
    }
}

/**
 * 胶囊是否被用户手动划掉了。
 *
 * 进程内可见即可：进程被杀之后本来也要重建状态，那时重新发一条是正确的
 * （用户划掉的意图不该跨越一次冷启动）。
 */
internal object CapsuleDismissal {

    @Volatile
    private var dismissed: Boolean = false

    /** 记一次「用户划掉了」。 */
    fun markDismissed() {
        dismissed = true
    }

    /** 是否处于「被划掉」状态。 */
    fun isDismissed(): Boolean = dismissed

    /**
     * 有新的待处理事项时清除该状态。
     *
     * 语义是「这次隐藏只对当时那条内容有效」：来了新的审批就必须重新出现，
     * 否则用户会以为系统坏了。
     */
    fun clear() {
        dismissed = false
    }
}
