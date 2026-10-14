package com.revxzxcx.remotewebapp.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一个「provider + model (+ 推理档)」三元组，跨层通用。
 *
 * 服务端在三个地方用同一形状：`modelCatalog.default`、`modelSelection.lastUsed` /
 * `modelSelection.next`、以及 `selectModel` 的返回值 `selected`。所以它既是「当前选的是谁」
 * 的载体，也是「用户刚选了谁」的返回值——两者形状一致意味着 UI 不需要在两处之间翻译。
 */
data class ModelRef(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
) {
    /**
     * 纯文本一行展示：`provider/model`，带推理档时追加 `·high`。
     *
     * 注意：聊天页的模型胶囊（`ModelBar`，R017）**不用这个 getter** —— 它把推理档拆成
     * 独立徽标，需要自己拼 `${provider}/${model}`。
     *
     * R018 P3-3：KDoc 原来写「留给日志、无障碍描述」，但**那两个场景都不存在**——
     * grep 全 main 源集，`ModelRef.label` 零调用，只有 `ModelCatalogParserTest` 钉着。
     * 现在如实标注为「暂无生产调用方」。若将来给 `ModelBar` 补 `contentDescription`
     * （读屏用户现在听到的是两个独立文本节点「provider/model」与「high」，拼不成
     * 一句完整的话），就在这里用——那时它才真的有调用方。
     */
    val label: String
        get() = if (reasoningEffort.isNullOrBlank()) {
            "$provider/$model"
        } else {
            "$provider/$model · $reasoningEffort"
        }
}

/** 一个模型支持的推理档（`reasoning.efforts[]`）。 */
data class ReasoningOption(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** 目录里的一个模型。 */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String? = null,
    /** 该模型支持的推理档；为空表示这个模型没有推理档可调。 */
    val efforts: List<ReasoningOption> = emptyList(),
    /** 服务端建议的默认档（`reasoning.defaultEffort`）。 */
    val defaultEffort: String? = null,
)

/** 目录里的一组模型（按 provider 分组，如 `DeepSeek`）。 */
data class CatalogGroup(
    val id: String,
    val name: String,
    val models: List<CatalogModel> = emptyList(),
)

/** 目录里某个 provider 拉取失败的原因（`failures[]`）。 */
data class CatalogFailure(
    val id: String,
    val name: String,
    val message: String,
)

/**
 * `session/modelCatalog` 的结果。
 *
 * 实测形状（`F:\bot\_model_probe.mjs` 打真实 127.0.0.1:19387）：
 * `{default:{provider,model,reasoningEffort?}, routableProviders:[…],
 *   groups:[{id,name,models:[{id,name,description?,reasoning?:{efforts:[…],defaultEffort?}}]}],
 *   failures:[{id,name,message}]}`。
 *
 * [routableProviders] 与 [groups] 是两份信息：前者是「有哪些 provider 能路由」，
 * 后者才是「有哪些模型能选」。UI 只读 [groups]——一个 provider 拉不到模型时不该出现在
 * 候选列表里，但它仍可能出现在 [routableProviders] 里。
 */
data class ModelCatalog(
    val default: ModelRef? = null,
    val routableProviders: List<String> = emptyList(),
    val groups: List<CatalogGroup> = emptyList(),
    val failures: List<CatalogFailure> = emptyList(),
) {
    /** 扁平化的全部模型，按分组顺序。 */
    val allModels: List<CatalogModel> get() = groups.flatMap { it.models }

    /** 全部模型都带上自己的 provider，便于 UI 用 `ModelRef` 比较选中项。 */
    fun refs(): List<Pair<String, CatalogModel>> =
        groups.flatMap { group -> group.models.map { group.id to it } }

    fun groupOf(provider: String): CatalogGroup? = groups.firstOrNull { it.id == provider }

    fun modelOf(provider: String, model: String): CatalogModel? =
        groupOf(provider)?.models?.firstOrNull { it.id == model }
}

/**
 * `session/modelCatalog` 与 `modelSelection` 投影的宽容解析器。
 *
 * 为什么手写而不是 `@Serializable`：与 [SessionListParser] 同理由——服务端会往这两个结构里
 * 加字段（`reasoningEffort` 就是可选追加的），严格 schema 意味着每次服务端演进客户端都要发版。
 * 这里只取 UI 真正要显示的值，其余原样忽略。
 *
 * 策略与其它解析器一致：任何字段缺失、类型不符都退化成默认值或丢弃该条目，**绝不抛异常**。
 * 目录拉失败时整个选择器可以不显示，但不能让聊天页崩掉。
 */
object ModelCatalogParser {

    /** 解析 `session/modelCatalog` 的 `result.value`。 */
    fun parseCatalog(element: JsonElement?): ModelCatalog {
        val obj = element as? JsonObject ?: return ModelCatalog()
        return ModelCatalog(
            default = obj["default"].asObject()?.let(::parseRef),
            routableProviders = obj.strings("routableProviders"),
            groups = obj.groups("groups"),
            failures = obj.failures("failures"),
        )
    }

    /**
     * 解析 `projections.values.modelSelection`，取 [which] 那一边。
     *
     * 实测该投影有两个键：`lastUsed`（这个会话上次实际用的）与 `next`（下次轮次要用的）。
     * UI 显示 `next` 更准确——用户改了模型但还没开始下一轮时，`lastUsed` 还是旧值，
     * 那时按钮上显示旧模型会让用户以为「没改上」。
     */
    fun parseSelection(element: JsonElement?, which: String = "next"): ModelRef? {
        val obj = element as? JsonObject ?: return null
        return obj[which].asObject()?.let(::parseRef)
    }

    /**
     * 解析**裸的**选择对象 `{provider, model, reasoningEffort?}`。
     *
     * 这是 `model/selection` 事件的 `data` 形状——桌面端或手机端任何一侧改模型，
     * session controller 都会往会话日志里追加这样一条事件
     * （`model-selection-projection.js:22-27`：`apply` 收到 `model/selection` 就把
     * `event.data` 记成 `pending`）。不收它就只能等下一次快照才同步，
     * 用户看到的就是「桌面端改了模型，手机端不动，退出重进才对」。
     */
    fun parseSelectionValue(element: JsonElement?): ModelRef? =
        (element as? JsonObject)?.let(::parseRef)

    private fun parseRef(obj: JsonObject): ModelRef? {
        val provider = obj.string("provider")?.takeIf { it.isNotBlank() } ?: return null
        val model = obj.string("model")?.takeIf { it.isNotBlank() } ?: return null
        return ModelRef(
            provider = provider,
            model = model,
            reasoningEffort = obj.string("reasoningEffort"),
        )
    }

    private fun JsonObject.groups(key: String): List<CatalogGroup> =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogGroup(
                id = id,
                name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id,
                models = obj.models("models"),
            )
        }.orEmpty()

    private fun JsonObject.models(key: String): List<CatalogModel> =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val reasoning = obj["reasoning"].asObject()
            CatalogModel(
                id = id,
                name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id,
                description = obj.string("description"),
                efforts = reasoning?.efforts("efforts").orEmpty(),
                defaultEffort = reasoning?.string("defaultEffort"),
            )
        }.orEmpty()

    private fun JsonObject.efforts(key: String): List<ReasoningOption> =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReasoningOption(
                id = id,
                name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id,
                description = obj.string("description"),
            )
        }.orEmpty()

    private fun JsonObject.failures(key: String): List<CatalogFailure> =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogFailure(
                id = id,
                name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id,
                message = obj.string("message").orEmpty(),
            )
        }.orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
