package com.revxzxcx.remotewebapp.core.protocol

import kotlinx.serialization.json.Json

/**
 * 全局共享的 JSON 配置。
 *
 * 两个必须的设置：
 * - `ignoreUnknownKeys = true`：服务端 `projections` 这类结构会随版本加 key，不能因为多一个
 *   字段就抛异常（需求文档 §7.3 第 6 条）。
 * - `encodeDefaults = true`：**关键**。[ClientRequestEnvelope.type] 有默认值 `"client-request"`，
 *   而 kotlinx.serialization 默认**不序列化等于默认值的字段**，那样发出去的请求会缺 `type`，
 *   服务端 `clientRequestSchema` 直接判为非法。必须显式打开。
 */
val DshJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
    prettyPrint = false
}
