package com.smallshoping.app.domain.purchase

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import java.util.UUID

data class PurchaseInRequest(
    val storeId: String,
    val productId: String,
    val quantity: Quantity,
    /** 进价（每 1 数量单位）；null 表示只入库不改成本 */
    val unitCost: Money?,
    val idempotencyKey: String
)

sealed interface PurchaseInResult {
    data class Success(val order: PurchaseOrder, val newCostMinor: Long?) : PurchaseInResult
    data object ProductNotFound : PurchaseInResult
    data class UnitMismatch(val expected: Unit, val actual: Unit) : PurchaseInResult
    data class AlreadyCompleted(val order: PurchaseOrder) : PurchaseInResult
    data object Conflict : PurchaseInResult
}

/**
 * 采购入库（spec 04）：库存流水 PURCHASE_IN + 加权平均成本更新 + 采购单，
 * 三者同事务；幂等键重复提交返回原结果。
 */
class PurchaseInUseCase(
    private val purchases: PurchaseRepository,
    private val products: ProductRepository,
    private val stock: StockQuery
) {

    operator fun invoke(request: PurchaseInRequest): PurchaseInResult {
        val product = products.findProductById(request.productId)
            ?: return PurchaseInResult.ProductNotFound
        // 数量必须为采购单位维度的基本单位刻度（MASS→克）
        val baseUnit = Unit.baseUnitFor(product.purchaseUnit.dimension)
        if (request.quantity.unit != baseUnit) {
            return PurchaseInResult.UnitMismatch(baseUnit, request.quantity.unit)
        }
        request.unitCost?.let { require(!it.isNegative) { "进价不能为负" } }

        // 加权平均成本（Domain 计算，数据层只负责持久化）
        val newCostMinor = request.unitCost?.let { cost ->
            WeightedAverageCost.compute(
                oldStock = stock.stockOf(product.id),
                oldCostMinor = product.currentCostPrice?.minor,
                inQuantity = request.quantity.scaled,
                inCostMinor = cost.minor
            )
        }
        val updatedProduct = newCostMinor?.let { product.copy(currentCostPrice = Money(it)) }

        val subtotalCost = request.unitCost?.let { it * request.quantity.scaled }
        val order = PurchaseOrder(
            id = UUID.randomUUID().toString(),
            storeId = request.storeId,
            items = listOf(
                PurchaseItem(
                    productId = product.id,
                    productName = product.name,
                    quantity = request.quantity,
                    unitCost = request.unitCost,
                    subtotalCost = subtotalCost
                )
            ),
            idempotencyKey = request.idempotencyKey
        )
        val stockEntry = LedgerEntry(
            scope = LedgerScope(LedgerScopeType.STOCK, product.id),
            movementType = MovementType.PURCHASE_IN,
            delta = request.quantity.scaled,
            referenceType = "purchase_order",
            referenceId = order.id,
            idempotencyKey = IdempotencyKey("${request.idempotencyKey}:stock"),
            note = "采购入库"
        )
        return when (val outcome = purchases.completePurchase(order, stockEntry, updatedProduct)) {
            is PurchaseOutcome.Completed -> PurchaseInResult.Success(outcome.order, newCostMinor)
            is PurchaseOutcome.AlreadyCompleted ->
                PurchaseInResult.AlreadyCompleted(outcome.order)
            PurchaseOutcome.Conflict -> PurchaseInResult.Conflict
        }
    }
}
