package com.revxzxcx.remotewebapp.core.protocol

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 服务端时间字段的解析。
 *
 * 为什么要单独一个对象：生成类型声明里 `createdAt` / `updatedAt` 写的是 `number`，
 * 但 M1 真机探针实测线上返回的是 **ISO-8601 字符串**（`"2026-10-12T15:52:26.030Z"`）。
 * 两种形态都可能出现（不同方法/不同版本），所以这里同时接受数字与字符串。
 *
 * minSdk 26，`java.time` 可直接用，不需要 desugaring。
 */
object DshTime {

    /**
     * 把服务端时间折算成 epoch 毫秒。
     *
     * 接受：ISO-8601 字符串（带 `Z`、带 `+08:00` 偏移、或本地无偏移）、以及已经是数字的
     * epoch 毫秒/秒。无法识别时返回 `0L`——时间只用于排序与展示，不值得为它抛异常。
     */
    fun epochMillis(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L

        // 纯数字：可能是 epoch 毫秒（13 位）或 epoch 秒（10 位）。
        text.toLongOrNull()?.let { numeric ->
            return if (numeric in 1..9_999_999_999L) numeric * 1000L else numeric
        }

        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            }
        }
    }
}
