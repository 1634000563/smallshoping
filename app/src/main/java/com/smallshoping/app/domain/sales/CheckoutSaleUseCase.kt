package com.smallshoping.app.domain.sales

import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.MemberRepository
import com.smallshoping.app.domain.payment.Payment
import com.smallshoping.app.domain.payment.PaymentRepository
import com.smallshoping.app.domain.payment.PaymentStatus

data class CheckoutSaleRequest(
    val saleId: String,
    val paymentMethod: PaymentMethod,
    val idempotencyKey: String,
    /** spec 04 §11：默认禁止销售导致负库存；显式开启才允许并记录审计 */
    val allowNegativeStock: Boolean = false,
    /** 结账时绑定客户/会员（spec 03 sale_order，客户历史查询用）。 */
    val customerId: String? = null,
    val memberId: String? = null
)

sealed interface CheckoutSaleResult {
    data class Success(val sale: SaleOrder) : CheckoutSaleResult
    data class InsufficientStock(val productIds: List<String>) : CheckoutSaleResult
    data object EmptyOrder : CheckoutSaleResult
    data object SaleNotFound : CheckoutSaleResult
    data class AlreadyCompleted(val sale: SaleOrder) : CheckoutSaleResult
    data object Conflict : CheckoutSaleResult

    /** 会员支付：会员不存在（Task 047）。 */
    data object MemberNotFound : CheckoutSaleResult

    /** 会员支付：余额不足，拒绝消费（spec 04 §6）。 */
    data class InsufficientBalance(val memberId: String, val balanceMinor: Long, val requiredMinor: Long) :
        CheckoutSaleResult
}

/**
 * 结账：销售单 + 库存流水同事务落库（spec 04 §2 最小闭环）。
 *
 * - 金额由 items 重算，不信任草稿冗余字段；
 * - 同商品多行合并为一条库存流水；
 * - 幂等键：checkoutKey:productId，重复提交返回原结果不重复扣库存。
 */
class CheckoutSaleUseCase(
    private val sales: SaleRepository,
    /** 会员支付支持（Task 047）：账本（余额与消费流水）+ 会员目录 + 支付记录。 */
    private val ledger: Ledger? = null,
    private val members: MemberRepository? = null,
    private val payments: PaymentRepository? = null
) {

    operator fun invoke(request: CheckoutSaleRequest): CheckoutSaleResult {
        val draft = sales.findDraft(request.saleId) ?: return CheckoutSaleResult.SaleNotFound
        if (draft.items.isEmpty()) return CheckoutSaleResult.EmptyOrder
        if (draft.status != SaleStatus.DRAFT) {
            return CheckoutSaleResult.AlreadyCompleted(draft)
        }

        val total = draft.computeTotal()
        val grouped = draft.items.groupBy { it.productId }
        val stockEntries = grouped.map { (productId, items) ->
            val totalScaled = items.fold(0L) { acc, item -> Math.addExact(acc, item.quantity.scaled) }
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, productId),
                movementType = MovementType.SALE_OUT,
                delta = Math.negateExact(totalScaled),
                referenceType = "sale_order",
                referenceId = draft.id,
                idempotencyKey = IdempotencyKey("${request.idempotencyKey}:$productId"),
                note = "销售出库"
            )
        }

        // 会员支付（spec 04 §6）：消费必须通过 member_ledger，余额不足默认拒绝
        val memberEntries = if (request.paymentMethod == PaymentMethod.MEMBER) {
            val memberId = request.memberId ?: return CheckoutSaleResult.MemberNotFound
            val member = members?.findMemberById(memberId)
                ?: return CheckoutSaleResult.MemberNotFound
            val balance = ledger?.balance(LedgerScope(LedgerScopeType.MEMBER, memberId)) ?: 0L
            if (balance < total.minor) {
                return CheckoutSaleResult.InsufficientBalance(memberId, balance, total.minor)
            }
            listOf(
                LedgerEntry(
                    scope = LedgerScope(LedgerScopeType.MEMBER, memberId),
                    movementType = MovementType.MEMBER_CONSUME,
                    delta = Math.negateExact(total.minor),
                    referenceType = "sale_order",
                    referenceId = draft.id,
                    idempotencyKey = IdempotencyKey("${request.idempotencyKey}:member"),
                    note = "会员消费：${member.name}"
                )
            )
        } else {
            emptyList()
        }

        val completed = draft.copy(
            status = SaleStatus.COMPLETED,
            paymentMethod = request.paymentMethod,
            total = total,
            checkoutIdempotencyKey = request.idempotencyKey,
            customerId = request.customerId,
            memberId = request.memberId,
            completedAtMillis = System.currentTimeMillis()
        )
        return when (
            val outcome = sales.completeSale(
                completed, stockEntries + memberEntries, request.allowNegativeStock
            )
        ) {
            is CheckoutOutcome.Completed -> {
                // 支付记录（Task 047）：现金/会员余额即刻 CONFIRMED，
                // 手工微信/支付宝 PENDING 待老板确认到账（spec 11）
                payments?.let { repo ->
                    val paymentStatus = when (request.paymentMethod) {
                        PaymentMethod.WECHAT, PaymentMethod.ALIPAY -> PaymentStatus.PENDING
                        else -> PaymentStatus.CONFIRMED
                    }
                    repo.record(
                        Payment(
                            saleOrderId = outcome.sale.id,
                            method = request.paymentMethod,
                            status = paymentStatus,
                            amountMinor = total.minor,
                            idempotencyKey = "payment:${outcome.sale.id}:${request.paymentMethod.name}",
                            confirmedAtMillis = if (paymentStatus == PaymentStatus.CONFIRMED) {
                                System.currentTimeMillis()
                            } else {
                                null
                            }
                        )
                    )
                }
                CheckoutSaleResult.Success(outcome.sale)
            }

            is CheckoutOutcome.AlreadyCompleted -> CheckoutSaleResult.AlreadyCompleted(outcome.sale)
            is CheckoutOutcome.StockConflict ->
                CheckoutSaleResult.InsufficientStock(outcome.productIds)
            CheckoutOutcome.Conflict -> CheckoutSaleResult.Conflict
        }
    }
}
