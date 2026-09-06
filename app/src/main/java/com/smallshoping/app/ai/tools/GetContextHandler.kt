package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContextStore

/**
 * get_context：读当前会话上下文（read，LOW）。
 * 上下文只是引用，事实一律回查数据库（spec 06 §2）。
 */
class GetContextHandler(
    private val contexts: SessionContextStore,
    private val defaultDeviceSessionId: String
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val sessionId = entities["device_session_id"] ?: defaultDeviceSessionId
        val context = contexts.load(sessionId)
        return if (context == null) {
            mapOf("status" to "NOT_FOUND", "context" to "")
        } else {
            mapOf(
                "status" to "OK",
                "context" to buildString {
                    context.activeSaleOrderId?.let { append("sale=$it;") }
                    context.lastProductId?.let { append("product=$it;") }
                    context.lastMemberId?.let { append("member=$it;") }
                    context.lastCustomerId?.let { append("customer=$it;") }
                    context.lastIntent?.let { append("intent=$it;") }
                }
            )
        }
    }
}
