package com.smallshoping.app.ai.providers

/**
 * AI Gateway 请求（spec 21 §2）。
 *
 * 只携带最小必要上下文（spec 05 §4/安全宪法 #6）：
 * 完整数据库、会员隐私、支付凭证一律不进请求体。
 */
data class GatewayRequest(
    val storeId: String,
    val deviceId: String,
    val appVersion: String,
    val promptVersion: String = "v1",
    val toolSchemaVersion: String = "v1",
    /** 老板原话 */
    val inputText: String,
    /** 当前会话最小引用 */
    val currentSaleId: String? = null,
    val recentEntities: List<String> = emptyList(),
    /** 只允许模型选择这些工具（最小权限） */
    val allowedTools: List<String>
) {

    init {
        require(inputText.isNotBlank()) { "inputText 不能为空" }
        require(allowedTools.isNotEmpty()) { "allowedTools 不能为空（最小权限）" }
    }
}
