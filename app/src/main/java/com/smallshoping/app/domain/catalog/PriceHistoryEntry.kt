package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit

/** 价格类型。 */
enum class PriceType { SALE, COST }

/**
 * 价格历史（spec 03 price_history 表）：只追加，不覆盖。
 * 首次定价时 [oldPrice] 为 null；改价产生新记录，商品表只保留当前价。
 */
data class PriceHistoryEntry(
    val id: String,
    val productId: String,
    val priceType: PriceType,
    val oldPrice: Money?,
    val newPrice: Money,
    val unit: Unit,
    val source: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(!newPrice.isNegative) { "价格不能为负：$newPrice" }
        require(oldPrice == null || !oldPrice.isNegative) { "旧价格不能为负：$oldPrice" }
    }
}
