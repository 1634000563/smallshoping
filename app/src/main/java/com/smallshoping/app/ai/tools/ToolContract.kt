package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.risk.RiskLevel

/** 确认策略（spec 07 §2 required_confirmation）。 */
enum class ConfirmationPolicy {
    /** 意图明确即自动执行 */
    NONE,

    /** 实体有歧义或信息不足时确认 */
    WHEN_AMBIGUOUS,

    /** 一律确认 */
    REQUIRED,

    /** 外部支付需老板手工确认到账 */
    PAYMENT_CONFIRMATION,

    /** 价格大幅变化时确认 */
    LARGE_DELTA
}

/** Tool 引用：name + major_version（spec 07 §8，破坏性变更不得悄悄替换旧契约）。 */
data class ToolRef(val name: String, val majorVersion: Int = 1) {
    val fullName: String get() = "$name.v$majorVersion"

    init {
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "Tool 名不合法：$name" }
        require(majorVersion > 0) { "majorVersion 必须为正" }
    }
}

/**
 * Tool 契约（spec 07 §2 统一字段）。
 *
 * 每个 Tool 必须声明：名称/描述/入参 Schema/风险级别/确认策略/
 * 是否需要幂等键/是否允许离线/审计动作/成功结果字段/错误码。
 */
data class ToolContract(
    val ref: ToolRef,
    val description: String,
    /** 入参必填键（V1 最小 Schema：必填键集合 + 可选键集合） */
    val inputRequired: Set<String> = emptySet(),
    val inputOptional: Set<String> = emptySet(),
    val riskLevel: RiskLevel,
    val confirmationPolicy: ConfirmationPolicy,
    val idempotencyRequired: Boolean,
    val allowedOffline: Boolean,
    val auditAction: String,
    /** 成功结果必须包含的字段（spec 07 §4：返回事实，不返回“应该成功了”） */
    val successResultFields: Set<String> = emptySet(),
    val errorCodes: Set<String> = emptySet()
) {

    init {
        require(description.isNotBlank()) { "Tool 描述不能为空" }
        // 入参 Schema 允许为空集（无参工具）；必填键不得与可选键重叠
        require(inputRequired.intersect(inputOptional).isEmpty()) {
            "${ref.fullName} 必填键与可选键重叠：${inputRequired.intersect(inputOptional)}"
        }
        require(successResultFields.isNotEmpty()) { "${ref.fullName} 必须声明成功结果字段" }
    }
}
