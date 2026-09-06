package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.ai.providers.AiProvider
import com.smallshoping.app.ai.providers.AiResponse
import com.smallshoping.app.ai.providers.GatewayRequest
import com.smallshoping.app.ai.tools.ToolExecutor
import com.smallshoping.app.ai.tools.ToolResult

/** 编排层给界面/老板的回复。 */
sealed interface OrchestratorReply {

    data class Text(val text: String) : OrchestratorReply

    data class Question(val question: String) : OrchestratorReply

    data class NeedsConfirm(val requestId: String, val question: String) : OrchestratorReply
}

/**
 * AI 编排器（spec 05 §2 Agent Loop 的客户端侧实现）：
 * Provider 产出（文本/工具调用/澄清）→ Tool 执行链（Schema→Risk→Domain）。
 *
 * - 模型只能选择请求中授权的工具（最小权限）；
 * - AI 失败不改变任何事实，只降级为文本提示（离线宪法 #6）；
 * - 需要确认的操作挂起，由 [confirm] 在老板确认后继续执行。
 */
class AiOrchestrator(
    private val provider: AiProvider,
    private val executor: ToolExecutor
) {

    fun handle(request: GatewayRequest): OrchestratorReply {
        val response = provider.complete(request)
        return when (response) {
            is AiResponse.FinalText -> OrchestratorReply.Text(response.text)

            is AiResponse.Clarification -> OrchestratorReply.Question(response.question)

            AiResponse.ConfirmationRequired ->
                OrchestratorReply.Question("这个操作需要你确认后我才会执行")

            is AiResponse.ModelError -> OrchestratorReply.Text(
                "AI 服务暂时不可用：${response.message}。账务未受任何影响，可用键盘/扫码继续营业。"
            )

            is AiResponse.RateLimited -> OrchestratorReply.Text("请求太频繁，稍等一下再说")

            is AiResponse.ToolCall -> {
                if (response.toolName !in request.allowedTools) {
                    OrchestratorReply.Question("这个操作暂时没有开放")
                } else {
                    executeToolCall(response.toolName, response.entities)
                }
            }
        }
    }

    /** 老板对挂起请求的确认/拒绝（spec 08 §9）。 */
    fun confirm(requestId: String, approved: Boolean): OrchestratorReply =
        toReply(executor.confirm(requestId, approved))

    private fun executeToolCall(toolName: String, entities: Map<String, String>): OrchestratorReply {
        val type = IntentType.fromTool(toolName)
            ?: return OrchestratorReply.Question("不认识这个操作")
        return toReply(
            executor.execute(
                Intent(type = type, entities = entities)
            )
        )
    }

    private fun toReply(result: ToolResult): OrchestratorReply = when (result) {
        is ToolResult.Success ->
            OrchestratorReply.Text(result.data["message"] ?: result.data.toString())

        is ToolResult.Failure ->
            OrchestratorReply.Text("${result.errorCode}：${result.message}")

        is ToolResult.NeedsConfirmation ->
            OrchestratorReply.NeedsConfirm(result.requestId, result.question)

        is ToolResult.Rejected -> OrchestratorReply.Text("已取消")
    }
}
