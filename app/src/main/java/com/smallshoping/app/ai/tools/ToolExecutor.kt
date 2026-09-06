package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentSchemaValidator
import com.smallshoping.app.ai.orchestrator.SchemaValidationException
import com.smallshoping.app.ai.risk.ConfirmationGate
import com.smallshoping.app.ai.risk.RiskDecision
import com.smallshoping.app.ai.risk.RiskGate

/** 工具执行结果：只包含事实与明确状态，绝不虚构成功（spec 07 §4）。 */
sealed interface ToolResult {

    data class Success(val data: Map<String, String>) : ToolResult

    data class Failure(val errorCode: String, val message: String) : ToolResult

    data class NeedsConfirmation(val requestId: String, val question: String) : ToolResult

    data class Rejected(val requestId: String) : ToolResult
}

/** 单个 Tool 的执行处理器：只通过 Domain 端口执行业务，不得触碰数据层。 */
fun interface ToolHandler {

    fun execute(entities: Map<String, String>): Map<String, String>
}

/**
 * Tool Executor（AI 宪法执行链：Intent → Schema Validate → Risk → Tool → Domain）。
 *
 * 流程：
 * 1. 查 Tool 契约（未知工具拒绝）；
 * 2. Intent Schema 校验（失败抛 [SchemaValidationException]，绝不猜测补全）；
 * 3. 风险门：自动执行 / 待确认（挂起，不执行）/ 拒绝；
 * 4. 分发到 [ToolHandler]，结果必须覆盖契约声明的 successResultFields，
 *    否则视为内部错误（防止「应该成功了」式假成功）。
 *
 * AI 只能经由本执行器调用业务能力，不得绕过（AI 宪法 #3）。
 */
class ToolExecutor(
    private val catalog: ToolCatalog,
    private val riskGate: RiskGate,
    private val confirmationGate: ConfirmationGate,
    private val handlers: Map<ToolRef, ToolHandler>
) {

    fun execute(intent: Intent): ToolResult {
        val contract = catalog.tool(ToolRef(intent.type.tool))
            ?: return ToolResult.Failure("UNKNOWN_TOOL", "未注册的 Tool：${intent.type.tool}")
        try {
            IntentSchemaValidator.validate(intent)
        } catch (e: SchemaValidationException) {
            return ToolResult.Failure("SCHEMA_INVALID", e.message ?: "意图 Schema 校验失败")
        }
        return when (val decision = riskGate.decide(contract, intent)) {
            is RiskDecision.Reject ->
                ToolResult.Failure("RISK_REJECTED", decision.reason)

            is RiskDecision.NeedConfirm -> {
                val pending = confirmationGate.propose(intent)
                ToolResult.NeedsConfirmation(
                    requestId = pending.requestId,
                    question = intent.clarification ?: decision.reason
                )
            }

            is RiskDecision.AutoExecute -> dispatch(contract, intent)
        }
    }

    /**
     * 老板确认（approved=true）后执行挂起的意图；
     * 只能确认最近一个待确认请求（由 [ConfirmationGate] 保证）。
     */
    fun confirm(requestId: String, approved: Boolean): ToolResult {
        if (!approved) {
            return if (confirmationGate.reject(requestId)) {
                ToolResult.Rejected(requestId)
            } else {
                ToolResult.Failure("CONFIRMATION_INVALID", "待确认请求不存在或已过期：$requestId")
            }
        }
        val intent = confirmationGate.confirm(requestId)
            ?: return ToolResult.Failure(
                "CONFIRMATION_INVALID",
                "只能确认最近一个待确认请求，且 requestId 必须匹配：$requestId"
            )
        val contract = catalog.tool(ToolRef(intent.type.tool))
            ?: return ToolResult.Failure("UNKNOWN_TOOL", "未注册的 Tool：${intent.type.tool}")
        return dispatch(contract, intent)
    }

    private fun dispatch(contract: ToolContract, intent: Intent): ToolResult {
        val handler = handlers[ToolRef(intent.type.tool)]
            ?: return ToolResult.Failure(
                "NO_HANDLER",
                "${contract.ref.fullName} 尚无处理器（待后续 Task 接入 Domain）"
            )
        return try {
            val data = handler.execute(intent.entities)
            val missing = contract.successResultFields - data.keys
            if (missing.isNotEmpty()) {
                ToolResult.Failure(
                    "INTERNAL_ERROR",
                    "${contract.ref.fullName} 返回缺字段 ${missing.joinToString()}，拒绝当作成功"
                )
            } else {
                ToolResult.Success(data)
            }
        } catch (e: Exception) {
            ToolResult.Failure("TOOL_ERROR", "${contract.ref.fullName} 执行失败：${e.message}")
        }
    }
}
