package com.smallshoping.app.ai.providers

/**
 * AI Gateway 客户端：生产环境由 Gateway 持有 Provider 密钥（安全宪法 #1/#2，
 * spec 21 §4）。V1 开发阶段可经 DevApiKeyProvider 直连，但密钥绝不进 APK。
 */
fun interface AiGatewayClient {

    fun send(request: GatewayRequest): AiResponse
}

/**
 * 云端 Provider：把 [GatewayRequest] 交给 [AiGatewayClient]，
 * 超时/网络/协议异常一律映射为 MODEL_ERROR（离线宪法 #6：超时、重试上限与降级路径）。
 */
class CloudAiProvider(private val client: AiGatewayClient) : AiProvider {

    override fun complete(request: GatewayRequest): AiResponse = try {
        client.send(request)
    } catch (e: Exception) {
        AiResponse.ModelError("MODEL_ERROR", "AI 网关调用失败：${e.message}")
    }
}
