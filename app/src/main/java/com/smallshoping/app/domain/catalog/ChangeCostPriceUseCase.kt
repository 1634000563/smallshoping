package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.money.Money
import java.util.UUID

data class ChangeCostRequest(
    val productId: String,
    /** 新进价（分）。 */
    val newCost: Money,
    /** 变更来源（如 manual / voice），写入价格历史供审计。 */
    val source: String
)

sealed interface ChangeCostResult {
    data class Success(
        val product: Product,
        val oldCostMinor: Long?,
        val newCostMinor: Long,
        val history: PriceHistoryEntry
    ) : ChangeCostResult

    /** 当前进价已是目标价：不追加历史（天然幂等）。 */
    data class Unchanged(val product: Product) : ChangeCostResult

    data object ProductNotFound : ChangeCostResult
}

/**
 * 改进货价（Task 059 真机验收：「土豆的进价改成两块五」）。
 * 一次改价 = 更新商品当前成本 + 追加一条 COST 价格历史，
 * 二者由 [ProductRepository.applyCostChange] 原子完成（只追加不覆盖）。
 */
class ChangeCostPriceUseCase(private val products: ProductRepository) {

    operator fun invoke(request: ChangeCostRequest): ChangeCostResult {
        require(!request.newCost.isNegative) { "进价不能为负：${request.newCost}" }
        val product = products.findProductById(request.productId)
            ?: return ChangeCostResult.ProductNotFound
        if (product.currentCostPrice == request.newCost) {
            return ChangeCostResult.Unchanged(product)
        }
        val history = PriceHistoryEntry(
            id = UUID.randomUUID().toString(),
            productId = product.id,
            priceType = PriceType.COST,
            oldPrice = product.currentCostPrice,
            newPrice = request.newCost,
            unit = product.purchaseUnit,
            source = request.source
        )
        val updated = products.applyCostChange(product.id, request.newCost, history)
            ?: return ChangeCostResult.ProductNotFound
        return ChangeCostResult.Success(
            product = updated,
            oldCostMinor = product.currentCostPrice?.minor,
            newCostMinor = request.newCost.minor,
            history = history
        )
    }
}
