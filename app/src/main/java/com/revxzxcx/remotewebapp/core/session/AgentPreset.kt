package com.revxzxcx.remotewebapp.core.session

/**
 * 新会话的 Agent 模式（R055）。
 *
 * 四档**不是**我们自己定的：`session/create` 收到非法值时回
 * `agent-preset/not-found`，`details.available` 逐字给出
 * `["standard","ptc","minimal","cordis"]`（2026-11-02 真实探针，见 `测试记录.md` T086-1）。
 * 中文名与说明同样逐字取自桌面端 asar
 * `@deepseek-ai/dsh-client-ui-agent-preset/lib/client.js` 的 `zh` 表——
 * 客户端自己编文案会和桌面端说的不一样，用户在两端口径对不上。
 *
 * [wire] 是发给服务端的唯一事实，**必须等于**枚举名的小写形式；[label] 只给人看。
 * 故意不写成「靠 `name.lowercase()` 现算」：一旦有人给枚举改个名，wire 值会跟着
 * 静默变化，而服务端只认这四个固定字符串。
 */
enum class AgentPreset(val wire: String, val label: String, val blurb: String) {
    STANDARD(
        wire = "standard",
        label = "标准模式",
        blurb = "处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。",
    ),
    PTC(
        wire = "ptc",
        label = "PTC 模式",
        blurb = "包含标准模式的所有能力，更适合批量调用工具，并对结果进行筛选、整理、去重、统计或汇总的任务。",
    ),
    MINIMAL(
        wire = "minimal",
        label = "极简模式",
        blurb = "Agent 使用终端和图片读取工具，并支持上下文压缩，适合测试和对比其基础表现。",
    ),
    CORDIS(
        wire = "cordis",
        label = "创造模式",
        blurb = "用对话定制 DSH：让 Agent 编写插件，添加新功能或界面；也能组合工具和提示词，创建自己的模式。",
    ),
    ;

    companion object {
        /** 新会话的默认模式。桌面端自己的默认也是它。 */
        val DEFAULT: AgentPreset = STANDARD

        /**
         * 从 wire 值反查；未知值退回 [DEFAULT]。
         *
         * 退回而不是抛异常：服务端将来可能加第五档，旧版 App 读到不认识的字符串时
         * 应该仍然能建会话（用默认档），而不是整个新建页面崩掉。
         */
        fun fromWire(wire: String?): AgentPreset =
            entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}
