package com.smallshoping.app.ai.risk

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.tools.ToolContract

/** 风险决策（spec 08 §2/§9）。 */
sealed interface RiskDecision {
    /** 低风险且意图明确、参数完整 → 自动执行 */
    data object AutoExecute : RiskDecision

    /** 需要老板确认或补充信息（携带原因/问题） */
    data class NeedConfirm(val reason: String) : RiskDecision

    /** 拒绝执行（如超出 V1 允许范围的操作） */
    data class Reject(val reason: String) : RiskDecision
}

/**
 * 风险门：V1 保守策略——
 * 仅 LOW 风险 + 无追问 + NONE 确认策略自动执行；
 * MEDIUM/HIGH 或带追问一律进入确认（spec 08：低风险自动、中风险确认、高风险强制确认）。
 * 更精细的自动执行/拒绝策略由 Task 040 强化。
 */
class RiskGate {

    fun decide(contract: ToolContract, intent: Intent): RiskDecision {
        if (intent.clarification != null) {
            return RiskDecision.NeedConfirm("需要用户补充信息：${intent.clarification}")
        }
        if (contract.riskLevel != RiskLevel.LOW) {
            return RiskDecision.NeedConfirm(
                "${contract.ref.fullName} 风险等级 ${contract.riskLevel.name}，需老板确认后执行"
            )
        }
        return RiskDecision.AutoExecute
    }
}
