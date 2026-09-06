package com.smallshoping.app.domain.sales

import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType

data class CheckoutSaleRequest(
    val saleId: String,
    val paymentMethod: PaymentMethod,
    val idempotencyKey: String,
    /** spec 04 §11：默认禁止销售导致负库存；显式开启才允许并记录审计 */
    val allowNegativeStock: Boolean = false
)

sealed interface CheckoutSaleResult {
    data class Success(val sale: SaleOrder) : CheckoutSaleResult
    data class InsufficientStock(val productIds: List<String>) : CheckoutSaleResult
    data object EmptyOrder : CheckoutSaleResult
    data object SaleNotFound : CheckoutSaleResult
    data class AlreadyCompleted(val sale: SaleOrder) : CheckoutSaleResult
    data object Conflict : CheckoutSaleResult
}

/**
 * 结账：销售单 + 库存流水同事务落库（spec 04 §2 最小闭环）。
 *
 * - 金额由 items 重算，不信任草稿冗余字段；
 * - 同商品多行合并为一条库存流水；
 * - 幂等键：checkoutKey:productId，重复提交返回原结果不重复扣库存。
 */
class CheckoutSaleUseCase(private val sales: SaleRepository) {

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
        val completed = draft.copy(
            status = SaleStatus.COMPLETED,
            paymentMethod = request.paymentMethod,
            total = total,
            checkoutIdempotencyKey = request.idempotencyKey,
            completedAtMillis = System.currentTimeMillis()
        )
        return when (val outcome = sales.completeSale(completed, stockEntries, request.allowNegativeStock)) {
            is CheckoutOutcome.Completed -> CheckoutSaleResult.Success(outcome.sale)
            is CheckoutOutcome.AlreadyCompleted -> CheckoutSaleResult.AlreadyCompleted(outcome.sale)
            is CheckoutOutcome.StockConflict ->
                CheckoutSaleResult.InsufficientStock(outcome.productIds)
            CheckoutOutcome.Conflict -> CheckoutSaleResult.Conflict
        }
    }
}
