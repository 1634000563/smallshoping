package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleResult
import com.smallshoping.app.domain.sales.CheckoutSaleUseCase
import com.smallshoping.app.domain.sales.PaymentMethod

/**
 * checkout_sale：对当前草稿单结账（草稿单取自会话上下文，spec 06）。
 *
 * - 支付方式仅接受现金/微信/支付宝（V1 手工确认，spec 04/ADR-008）；
 * - 幂等键按「单号+支付方式」确定性生成，AI 重试不重复扣库存；
 * - 结账属 MEDIUM 风险，由 Risk Gate 确认后才会到达本处理器。
 */
class CheckoutSaleHandler(
    private val checkout: CheckoutSaleUseCase,
    private val contexts: SessionContextStore,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val method = parseMethod(entities.getValue("payment_method"))
            ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "sale_id" to "", "paid_minor" to "",
                "message" to "支付方式只支持：现金 / 微信 / 支付宝 / 会员余额"
            )
        val context = contexts.load(session.deviceId)
        val saleId = context?.activeSaleOrderId
            ?: return mapOf(
                "status" to "NO_ACTIVE_SALE", "sale_id" to "", "paid_minor" to "",
                "message" to "还没有未结账的单子，先说「卖两斤土豆」这样的句子吧"
            )
        // 会员余额支付：会员取会话上下文最近会员（Task 047）
        val memberId = if (method == PaymentMethod.MEMBER) {
            context.lastMemberId ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "sale_id" to "", "paid_minor" to "",
                "message" to "用会员余额结账要先报会员（如：给张姐充200）"
            )
        } else {
            null
        }
        val result = checkout(
            CheckoutSaleRequest(
                saleId = saleId,
                paymentMethod = method,
                idempotencyKey = "checkout:$saleId",
                memberId = memberId
            )
        )
        return when (result) {
            is CheckoutSaleResult.Success -> {
                clearActiveSale()
                mapOf(
                    "status" to "OK",
                    "sale_id" to result.sale.id,
                    "paid_minor" to result.sale.total.minor.toString(),
                    "message" to "结账完成：共 ${result.sale.total.minor} 分，已按「${result.sale.paymentMethod?.name}」记录，请确认到账"
                )
            }

            is CheckoutSaleResult.AlreadyCompleted -> {
                clearActiveSale()
                mapOf(
                    "status" to "OK",
                    "sale_id" to result.sale.id,
                    "paid_minor" to result.sale.total.minor.toString(),
                    "message" to "这单已经结过账了：${result.sale.total.minor} 分"
                )
            }

            is CheckoutSaleResult.InsufficientStock -> mapOf(
                "status" to "OUT_OF_STOCK", "sale_id" to "", "paid_minor" to "",
                "message" to "库存不够：${result.productIds.joinToString()}，没动账"
            )

            CheckoutSaleResult.EmptyOrder -> mapOf(
                "status" to "INVALID_ARGUMENT", "sale_id" to "", "paid_minor" to "",
                "message" to "单子是空的，先加商品"
            )

            CheckoutSaleResult.SaleNotFound -> mapOf(
                "status" to "NOT_FOUND", "sale_id" to "", "paid_minor" to "", "message" to "单子不存在"
            )

            CheckoutSaleResult.Conflict -> mapOf(
                "status" to "CONFLICT", "sale_id" to "", "paid_minor" to "",
                "message" to "重复提交冲突，请确认后重试"
            )

            CheckoutSaleResult.MemberNotFound -> mapOf(
                "status" to "NOT_FOUND", "sale_id" to "", "paid_minor" to "",
                "message" to "会员不存在"
            )

            is CheckoutSaleResult.InsufficientBalance -> mapOf(
                "status" to "INVALID_ARGUMENT", "sale_id" to "", "paid_minor" to "",
                "message" to "会员余额只有 ${result.balanceMinor} 分，这单要 ${result.requiredMinor} 分，请换支付方式"
            )
        }
    }

    private fun parseMethod(raw: String): PaymentMethod? = when (raw.trim()) {
        "cash", "现金" -> PaymentMethod.CASH
        "wechat", "微信" -> PaymentMethod.WECHAT
        "alipay", "支付宝" -> PaymentMethod.ALIPAY
        "member", "会员", "会员余额" -> PaymentMethod.MEMBER
        else -> null
    }

    /** 结账后清空当前订单上下文（保留最近商品，spec 06 §1）。 */
    private fun clearActiveSale() {
        contexts.load(session.deviceId)?.let { context ->
            contexts.save(context.copy(activeSaleOrderId = null))
        }
    }
}
