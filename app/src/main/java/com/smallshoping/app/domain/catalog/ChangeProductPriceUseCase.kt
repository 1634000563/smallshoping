package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.money.Money
import java.util.UUID

data class ChangePriceRequest(
    val productId: String,
    /** 新售价（分）。 */
    val newPrice: Money,
    /** 变更来源（如 yesterday_price / manual），写入价格历史供审计。 */
    val source: String
)

sealed interface ChangePriceResult {
    data class Success(
        val product: Product,
        val oldPriceMinor: Long,
        val newPriceMinor: Long,
        val history: PriceHistoryEntry
    ) : ChangePriceResult

    /** 当前价已是目标价：不追加历史（天然幂等）。 */
    data class Unchanged(val product: Product) : ChangePriceResult

    data object ProductNotFound : ChangePriceResult
}

/**
 * 改价（spec 02 价格历史只追加不覆盖；spec 13 价格修改须留痕）。
 *
 * 一次改价 = 更新商品当前售价 + 追加一条价格历史，二者由
 * [ProductRepository.applyPriceChange] 原子完成。
 */
class ChangeProductPriceUseCase(private val products: ProductRepository) {

    operator fun invoke(request: ChangePriceRequest): ChangePriceResult {
        require(!request.newPrice.isNegative) { "售价不能为负：${request.newPrice}" }
        val product = products.findProductById(request.productId)
            ?: return ChangePriceResult.ProductNotFound
        if (product.currentSalePrice == request.newPrice) {
            return ChangePriceResult.Unchanged(product)
        }

        val history = PriceHistoryEntry(
            id = UUID.randomUUID().toString(),
            productId = product.id,
            priceType = PriceType.SALE,
            oldPrice = product.currentSalePrice,
            newPrice = request.newPrice,
            unit = product.saleUnit,
            source = request.source
        )
        val updated = products.applyPriceChange(product.id, request.newPrice, history)
            ?: return ChangePriceResult.ProductNotFound
        return ChangePriceResult.Success(
            product = updated,
            oldPriceMinor = product.currentSalePrice.minor,
            newPriceMinor = request.newPrice.minor,
            history = history
        )
    }
}
