package com.smallshoping.app.ai.providers

/**
 * AI 版本事实源（Task 053）：Prompt / Tool Schema / 模型标识的单一版本定义。
 *
 * 任何 Prompt（docs/prompts/system.md）、Schema（docs/schemas/tool-catalog.json）
 * 或行为策略变更必须：
 * 1. 提升这里对应的版本号；
 * 2. 同步更新对应文档文件的版本标记（system.md 头部 / json 的 schema_version 字段）；
 * 3. 重跑黄金语句集回归（EvalRunnerTest，spec 15 §6）。
 *
 * 一致性由 AiVersioningTest / ToolCatalogTest 自动化守护：
 * 改了代码版本忘了同步文档（或反之）会直接测试失败。
 */
object AiVersions {

    /** docs/prompts/system.md 头部标记，如 `<!-- prompt-version: v1 -->`。 */
    const val PROMPT_VERSION = "v1"

    /** docs/schemas/tool-catalog.json 顶层 schema_version 字段。 */
    const val TOOL_SCHEMA_VERSION = "v1"

    /**
     * model_id（spec 05 §9 每次 AI 写操作记录四元组之一）。
     * V1 无云端模型：本地规则解析器（LocalRuleParser）充当识别层，
     * 接入云模型后由 Gateway 路由结果替换此值。
     */
    const val MODEL_ID = "local-rule-parser-v1"
}
