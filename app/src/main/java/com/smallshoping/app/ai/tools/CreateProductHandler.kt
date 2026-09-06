package com.smallshoping.app.ai.tools

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import java.util.UUID

/**
 * create_product：新建商品（write，MEDIUM，歧义或新品需确认——由风险门处理确认）。
 * 价格可选；未给价格时记 0 元并在成功后由老板改价（绝不猜测价格）。
 */
class CreateProductHandler(
    private val products: ProductRepository,
    private val storeId: String
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val name = entities.getValue("name")
        val priceMinor = entities["price"]?.toLongOrNull() ?: 0L
        if (priceMinor < 0) {
            return mapOf("status" to "INVALID_ARGUMENT", "product_id" to "")
        }
        val product = Product(
            id = UUID.randomUUID().toString(),
            storeId = storeId,
            name = name,
            normalizedName = normalize(name),
            saleUnit = Unit.PIECE,
            purchaseUnit = Unit.PIECE,
            currentSalePrice = Money(priceMinor),
            currentCostPrice = null
        )
        products.saveProduct(product)
        return mapOf("status" to "OK", "product_id" to product.id)
    }
}
