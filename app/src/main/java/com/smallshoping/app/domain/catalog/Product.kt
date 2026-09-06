package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit

/**
 * 商品（spec 02 §2）：不假设「一个商品=一个条码=一个单位」。
 *
 * 价格均指「每 1 个 [saleUnit]/[purchaseUnit]」的金额；
 * 历史价格走 [PriceHistoryEntry]，本类只持有当前价。
 */
data class Product(
    val id: String,
    val storeId: String,
    val name: String,
    val normalizedName: String,
    val saleUnit: Unit,
    val purchaseUnit: Unit,
    val currentSalePrice: Money,
    val currentCostPrice: Money?,
    val active: Boolean = true
) {

    init {
        require(name.isNotBlank()) { "商品名不能为空" }
        require(storeId.isNotBlank()) { "store_id 不能为空" }
        require(!currentSalePrice.isNegative) { "售价不能为负：$currentSalePrice" }
        require(currentCostPrice == null || !currentCostPrice.isNegative) {
            "成本不能为负：$currentCostPrice"
        }
    }
}
