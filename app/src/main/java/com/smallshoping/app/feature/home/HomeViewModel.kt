package com.smallshoping.app.feature.home

import com.smallshoping.app.ai.orchestrator.AiOrchestrator
import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.AddSaleItemResult
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleResult
import com.smallshoping.app.domain.sales.PaymentMethod

/**
 * UI 状态类别：界面据此决定渲染样式（Task 042）。
 */
enum class UiKind { NORMAL, QUESTION, CONFIRM, ERROR }

/**
 * 极简主界面的 UI 状态。
 *
 * @param kind 渲染类别：CONFIRM=确认卡片、ERROR=异常提示（红）、QUESTION=追问
 * @param reply 给老板的回复
 * @param pendingConfirmId 待确认请求 id；非 null 时界面显示确认/取消
 * @param currentTask 当前任务摘要（草稿单内容），只读展示
 */
data class HomeUiState(
    val kind: UiKind,
    val reply: String,
    val pendingConfirmId: String? = null,
    val currentTask: String = ""
)

/**
 * 极简主界面 ViewModel（Task 041/042）：
 * UI → ViewModel → AiOrchestrator → Tool/Domain（唯一链路，不绕过）。
 *
 * 语音与文本输入汇入 [handleInput]；确认/拒绝走 [confirm]；
 * AI 不可用时 [manualAddItem]/[manualCheckout] 提供人工兜底——
 * 直接调同一批 Domain UseCase，与 AI 路径写入相同业务事实（Gate A）。
 * 纯 Kotlin 无 Android 依赖，可在 JVM 单元测试中完整验证。
 */
class HomeViewModel(
    private val root: CompositionRoot,
    private val orchestrator: AiOrchestrator = root.orchestrator
) {

    /** 语音/扫码/文本统一入口：一句输入 → 编排器 → 状态。 */
    fun handleInput(text: String): HomeUiState {
        if (text.isBlank()) return HomeUiState(UiKind.NORMAL, "请输入或按住语音说话")
        val reply = orchestrator.handle(root.inputAdapter.fromText(text))
        return toState(reply)
    }

    /** 确认/拒绝最近一个待确认请求（spec 08 §9）。 */
    fun confirm(approved: Boolean): HomeUiState {
        val pending = root.executor.pendingConfirmationId()
            ?: return HomeUiState(UiKind.NORMAL, "现在没有待确认的操作", currentTask = currentTask())
        return toState(orchestrator.confirm(pending, approved))
    }

    /** 人工兜底：按商品名手动加商品（不依赖 AI，同一 Domain，Gate A）。 */
    fun manualAddItem(productName: String, quantityText: String): HomeUiState {
        val product = root.products.findByNormalizedName(normalize(productName))
            ?: return HomeUiState(UiKind.ERROR, "没找到「$productName」，先建一个？", currentTask = currentTask())
        val parsed = QuantityParser.parse(quantityText)
            ?: return HomeUiState(UiKind.ERROR, "数量看不懂：$quantityText", currentTask = currentTask())
        val draftId = root.contexts.load(root.session.deviceId)?.activeSaleOrderId
        return when (val result = root.addSaleItemUseCase(
            AddSaleItemRequest(root.session.storeId, draftId, product.id, parsed.quantity)
        )) {
            is AddSaleItemResult.Success -> {
                // 与 AI 路径一致：记住当前草稿单与最近商品（spec 06 §1）
                val context = root.contexts.load(root.session.deviceId)
                val updated = context?.copy(
                    activeSaleOrderId = result.sale.id,
                    lastProductId = product.id
                )
                    ?: com.smallshoping.app.ai.context.SessionContext(
                        deviceSessionId = root.session.deviceId,
                        activeSaleOrderId = result.sale.id,
                        lastProductId = product.id,
                        expiresAtMillis = System.currentTimeMillis() + 30 * 60 * 1000L
                    )
                root.contexts.save(updated)
                HomeUiState(
                    UiKind.NORMAL,
                    "已加入：${product.name} ${parsed.displayText}（人工）",
                    currentTask = currentTask()
                )
            }

            is AddSaleItemResult.UnitMismatch -> HomeUiState(
                UiKind.ERROR, "数量单位要用「${result.expected.name}」", currentTask = currentTask()
            )

            AddSaleItemResult.ProductNotFound ->
                HomeUiState(UiKind.ERROR, "商品不存在", currentTask = currentTask())
        }
    }

    /** 人工兜底：手动结账当前草稿单（现金，不依赖 AI，同一 Domain）。 */
    fun manualCheckout(): HomeUiState {
        val draftId = root.contexts.load(root.session.deviceId)?.activeSaleOrderId
            ?: return HomeUiState(UiKind.ERROR, "还没有未结账的单子")
        return when (val result = root.checkoutSaleUseCase(
            CheckoutSaleRequest(draftId, PaymentMethod.CASH, "checkout:$draftId:manual")
        )) {
            is CheckoutSaleResult.Success -> {
                clearActiveSale()
                HomeUiState(
                    UiKind.NORMAL,
                    "人工结账完成：共 ${result.sale.total.minor} 分，请确认到账",
                    currentTask = currentTask()
                )
            }

            is CheckoutSaleResult.AlreadyCompleted -> {
                clearActiveSale()
                HomeUiState(UiKind.NORMAL, "这单已经结过账了", currentTask = currentTask())
            }

            is CheckoutSaleResult.InsufficientStock ->
                HomeUiState(UiKind.ERROR, "库存不够：${result.productIds.joinToString()}", currentTask = currentTask())

            CheckoutSaleResult.EmptyOrder ->
                HomeUiState(UiKind.ERROR, "单子是空的，先加商品", currentTask = currentTask())

            CheckoutSaleResult.SaleNotFound ->
                HomeUiState(UiKind.ERROR, "单子不存在", currentTask = currentTask())

            CheckoutSaleResult.Conflict ->
                HomeUiState(UiKind.ERROR, "重复提交冲突，请重试", currentTask = currentTask())

            CheckoutSaleResult.MemberNotFound ->
                HomeUiState(UiKind.ERROR, "会员不存在", currentTask = currentTask())

            is CheckoutSaleResult.InsufficientBalance ->
                HomeUiState(
                    UiKind.ERROR,
                    "会员余额只有 ${result.balanceMinor} 分，这单要 ${result.requiredMinor} 分",
                    currentTask = currentTask()
                )
        }
    }

    private fun toState(reply: OrchestratorReply): HomeUiState = when (reply) {
        is OrchestratorReply.Text -> HomeUiState(
            kind = if (reply.text.contains("暂时不可用")) UiKind.ERROR else UiKind.NORMAL,
            reply = reply.text,
            currentTask = currentTask()
        )

        is OrchestratorReply.Question ->
            HomeUiState(UiKind.QUESTION, reply.question, currentTask = currentTask())

        is OrchestratorReply.NeedsConfirm -> HomeUiState(
            kind = UiKind.CONFIRM,
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

    private fun clearActiveSale() {
        root.contexts.load(root.session.deviceId)?.let { context ->
            root.contexts.save(context.copy(activeSaleOrderId = null))
        }
    }
}
