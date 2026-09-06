package com.smallshoping.app.domain.catalog

/**
 * 价格历史查询（Task 035）：基于只追加的价格历史（spec 02），
 * 提供最近一次改价/定价查询；历史只追加不覆盖。
 */
class PriceHistoryQuery(private val products: ProductRepository) {

    /** 某商品最近的售价历史条目；无历史返回 null。 */
    fun latestSalePrice(productId: String): PriceHistoryEntry? =
        products.priceHistory(productId).lastOrNull { it.priceType == PriceType.SALE }

    /** 某商品最近的成本历史条目；无历史返回 null。 */
    fun latestCostPrice(productId: String): PriceHistoryEntry? =
        products.priceHistory(productId).lastOrNull { it.priceType == PriceType.COST }
}
