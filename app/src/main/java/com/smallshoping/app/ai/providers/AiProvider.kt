package com.smallshoping.app.ai.providers

/**
 * AI Provider 抽象（spec 05 §4）：
 * CloudProvider 走 AI Gateway（密钥在网关侧），LocalProvider 走本地规则解析。
 *
 * Provider 不接触数据库，只接收最小任务上下文；
 * 任何失败必须映射为 [AiResponse.ModelError]，不得伪造成功（AI 宪法 #5）。
 */
fun interface AiProvider {

    fun complete(request: GatewayRequest): AiResponse
}
