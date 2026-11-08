package com.revxzxcx.remotewebapp.core.notify

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 「能给出退出历史」的东西。
 *
 * 抽成接口只有一个理由：`ExitHistoryReader` 依赖 `ActivityManager`，在 JVM 单测里是空桩，
 * 而 [DeathWatcher] 的编排逻辑（先写基线再发布、首次也写基线）必须能测。生产代码里
 * 唯一的实现是 [ExitHistoryReader]。
 */
fun interface ExitSource {
    fun read(): List<ExitFacts>
}

/**
 * 从系统读取「上一次进程是怎么没的」（R056 / Q3）。
 *
 * 判定逻辑在同包的 `ProcessDeath.kt`（纯函数、可单测）；这里只负责**取数据**，
 * 因为 `ApplicationExitInfo` 只有在真机上才有真实内容，JVM 单测里跑不了。
 *
 * ## 为什么用 `getHistoricalProcessExitReasons` 而不是自己记标记
 * `Application.onTerminate()` 在真机上**根本不调用**（只有模拟器调），所以「退出时写一个
 * 干净标记」这条路拿不到可靠数据。系统这个 API（API 30+）是唯一不需要我们猜的来源。
 *
 * ## 为什么要在 `onCreate` 一开始就读、读完立刻写基线
 * `getHistoricalProcessExitReasons` 会**把我们自己这一次启动之前的记录**都给出来，
 * 包括「几天前那次」和「刚才那次」。若在读完之前 UI 已经显示了提示、用户点了一下，
 * 而基线还没更新，下一次冷启动会再弹一遍同一条——他刚看完的东西又出现，会以为没生效。
 *
 * 所以调用顺序固定为：读 → 挑 ([pickDeathNotice]) → **立刻写基线** → 才把结果交给 UI。
 */
class ExitHistoryReader(context: Context) : ExitSource {

    private val appContext = context.applicationContext

    /**
     * 取最近 [LIMIT] 条退出记录。
     *
     * API 30 以下直接返回空列表：那之前没有这个 API，判定自然落到「没有基线」→ 不提示。
     * 这是刻意的——**宁可少提示，也不要靠猜**（在 30 以下我们分不清「系统杀的」和
     * 「用户自己划掉的」，乱说比不说更伤）。
     *
     * 异常全部吞掉返回空列表：这里失败最多是少一次提示，而它跑在 `onCreate` 的主线程上，
     * 抛出去就是启动崩溃。
     */
    override fun read(): List<ExitFacts> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val raw = try {
            manager.getHistoricalProcessExitReasons(null, 0, LIMIT)
        } catch (_: Exception) {
            return emptyList()
        }
        return raw.mapNotNull { info -> info.toFacts() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.toFacts(): ExitFacts? {
        val reason = try {
            classifySystemReason(this.reason)
        } catch (_: Exception) {
            return null
        }
        val timestamp = try {
            this.timestamp
        } catch (_: Exception) {
            return null
        }
        if (timestamp <= 0L) return null
        val description = try {
            this.description
        } catch (_: Exception) {
            null
        }
        return ExitFacts(reason = reason, timestamp = timestamp, description = description)
    }

    private companion object {
        /**
         * 20 条足够：我们要的只是「比基线新的那些里最新的一条」。
         * 取太多会在启动关键路径上多花时间，而这个 API 每次都走 binder。
         */
        const val LIMIT = 20
    }
}

/**
 * 系统常量 → [ExitReason]。
 *
 * 抽成独立函数是为了能在单测里直接喂常量值：`ApplicationExitInfo.REASON_*` 都是 `Int`
 * 常量，不需要真的构造一个 `ApplicationExitInfo`（那个类在 JVM 上是空的桩）。
 *
 * `REASON_OTHER` 归到 [ExitReason.Other] 而不是 [ExitReason.Unknown]——见 [classifyExit]
 * 的 KDoc：一加的电池应用杀进程常落在这个「没说法的原因」里，排除它等于这个功能没做。
 * 真正「我们还不认识」的情况（系统新增了常量、我们没跟）落到 `else` 分支。
 */
fun classifySystemReason(systemReason: Int): ExitReason = when (systemReason) {
    ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LowMemory
    ApplicationExitInfo.REASON_SIGNALED -> ExitReason.Signaled
    ApplicationExitInfo.REASON_FREEZER -> ExitReason.Freezer
    ApplicationExitInfo.REASON_OTHER -> ExitReason.Other
    ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.UserRequested
    // REASON_USER_STOPPED 与 REASON_USER_REQUESTED 是同一族（都是「不是我们自己退的，
    // 也不是系统省电策略直接杀的」），归到一起走 DeathCause.Stopped 的措辞。
    // 11 这个常量 API 30 起就有，无需版本判断。
    ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.UserRequested
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    -> ExitReason.Crash
    ApplicationExitInfo.REASON_EXIT_SELF,
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
    ApplicationExitInfo.REASON_PACKAGE_UPDATED,
    ApplicationExitInfo.REASON_PERMISSION_CHANGE,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    -> ExitReason.SystemHousekeeping
    else -> ExitReason.Unknown
}
