package com.smallshoping.app.ai.providers

/**
 * AI 响应（spec 21 §3）：六类必须可区分。
 *
 * TOOL_CALL 只给出结构化工具名与参数；业务事实由 Domain 计算后返回，
 * AI 的文本结论不直接落库。
 */
sealed interface AiResponse {

    data class FinalText(val text: String) : AiResponse

    /** 模型选择调用某个已授权工具（name.v1 之外的裸名即可，执行层统一补版本） */
    data class ToolCall(val toolName: String, val entities: Map<String, String>) : AiResponse

    data class Clarification(val question: String) : AiResponse

    data object ConfirmationRequired : AiResponse

    data class ModelError(val code: String, val message: String) : AiResponse

    data class RateLimited(val retryAfterSeconds: Long?) : AiResponse
}
