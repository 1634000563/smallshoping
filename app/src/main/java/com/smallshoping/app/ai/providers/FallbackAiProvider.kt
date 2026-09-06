package com.smallshoping.app.ai.providers

/**
 * 降级 Provider 链（离线优先，spec 05 §5、离线宪法）：
 * 1. 本地规则解析优先：确定性句式零网络零密钥，永远可用；
 * 2. 本地无法识别 → 云端模型兜底（复杂表达）；
 * 3. 云端失败（断网/超时）→ MODEL_ERROR 透传，编排层降级为文本提示，
 *    业务事实不受影响，键盘/扫码/人工兜底继续营业（离线宪法 #6）。
 *
 * 无云端 Provider（V1 默认，密钥未配置）时退化为纯本地。
 */
class FallbackAiProvider(
    private val local: AiProvider,
    private val cloud: AiProvider? = null
) : AiProvider {

    override fun complete(request: GatewayRequest): AiResponse {
        val localResponse = local.complete(request)
        if (localResponse !is AiResponse.Clarification) return localResponse
        val cloudProvider = cloud ?: return localResponse
        return cloudProvider.complete(request)
    }
}
