package com.smallshoping.app.domain.sales

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.ProductRepository
import java.util.UUID

/** 加商品入当前草稿单的请求。 */
data class AddSaleItemRequest(
    val storeId: String,
    val draftSaleId: String?,
    val productId: String,
    val quantity: Quantity
)

sealed interface AddSaleItemResult {
    data class Success(val sale: SaleOrder) : AddSaleItemResult
    data object ProductNotFound : AddSaleItemResult

    /** 数量单位与商品销售单位不一致（换算由 Task 030 提供，此处拒绝） */
    data class UnitMismatch(val expected: Unit, val actual: Unit) : AddSaleItemResult
}

/**
 * 加商品入草稿单：单价取商品目录当前价快照，小计由 Domain 计算，
 * 绝不采用外部（AI）传入的金额。
 */
class AddSaleItemUseCase(
    private val sales: SaleRepository,
    private val products: ProductRepository
) {

    operator fun invoke(request: AddSaleItemRequest): AddSaleItemResult {
        val product = products.findProductById(request.productId)
            ?: return AddSaleItemResult.ProductNotFound
        if (request.quantity.unit != product.saleUnit) {
            return AddSaleItemResult.UnitMismatch(product.saleUnit, request.quantity.unit)
        }
        val unitPrice = product.currentSalePrice
        val subtotal = unitPrice * request.quantity.scaled
        val item = SaleItem(
            productId = product.id,
            productName = product.name,
            quantity = request.quantity,
            unitPrice = unitPrice,
            subtotal = subtotal
        )
        val draft = request.draftSaleId?.let { sales.findDraft(it) }
            ?: SaleOrder(
                id = UUID.randomUUID().toString(),
                storeId = request.storeId
            )
        val updated = draft.copy(items = draft.items + item, total = draft.computeTotal() + subtotal)
        sales.saveDraft(updated)
        return AddSaleItemResult.Success(updated)
    }
}
