package com.smallshoping.app.feature.home

import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot

/**
 * 极简主界面的 UI 状态。
 *
 * @param reply 给老板的回复（文本/追问/待确认问题）
 * @param pendingConfirmId 待确认请求 id；非 null 时界面显示确认/取消按钮
 * @param currentTask 当前任务摘要（草稿单内容），只读展示，不提供编辑入口
 */
data class HomeUiState(
    val reply: String,
    val pendingConfirmId: String? = null,
    val currentTask: String = ""
)

/**
 * 极简主界面 ViewModel（Task 041）：
 * UI → ViewModel → AiOrchestrator → Tool/Domain（唯一链路，不绕过）。
 *
 * 语音与文本输入在这里汇入同一 [handleInput]；确认/拒绝走 [confirm]。
 * 纯 Kotlin 无 Android 依赖，可在 JVM 单元测试中完整验证。
 */
class HomeViewModel(private val root: CompositionRoot) {

    /** 语音/扫码/文本统一入口：一句输入 → 编排器 → 状态。 */
    fun handleInput(text: String): HomeUiState {
        if (text.isBlank()) return HomeUiState(reply = "请输入或按住语音说话")
        val reply = root.orchestrator.handle(root.inputAdapter.fromText(text))
        return toState(reply)
    }

    /** 确认/拒绝最近一个待确认请求（spec 08 §9）。 */
    fun confirm(approved: Boolean): HomeUiState {
        val pending = root.executor.pendingConfirmationId()
            ?: return HomeUiState(reply = "现在没有待确认的操作", currentTask = currentTask())
        return toState(root.orchestrator.confirm(pending, approved))
    }

    private fun toState(reply: OrchestratorReply): HomeUiState = when (reply) {
        is OrchestratorReply.Text -> HomeUiState(reply = reply.text, currentTask = currentTask())
        is OrchestratorReply.Question ->
            HomeUiState(reply = reply.question, currentTask = currentTask())

        is OrchestratorReply.NeedsConfirm -> HomeUiState(
            reply = reply.question,
            pendingConfirmId = reply.requestId,
            currentTask = currentTask()
        )
    }

    /** 当前任务摘要：草稿单明细与合计（只读，从销售事实取）。 */
    private fun currentTask(): String {
        val draftId = root.contexts.load(root.session.deviceId)?.activeSaleOrderId
            ?: return "当前没有待结账的单子"
        val sale = root.sales.findById(draftId) ?: return "当前没有待结账的单子"
        if (sale.items.isEmpty()) return "当前没有待结账的单子"
        val lines = sale.items.map {
            "${it.productName} ×${it.quantity.scaled}（${it.subtotal.minor} 分）"
        }
        return lines.joinToString("\n") + "\n合计 ${sale.computeTotal().minor} 分"
    }
}
