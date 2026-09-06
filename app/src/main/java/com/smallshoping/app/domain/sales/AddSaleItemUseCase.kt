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
        // 数量必须为销售单位维度的基本单位刻度（如 MASS→克），
        // 单价按「每 1 个 saleUnit」折算（380分/斤 × 1180克 / 500）
        val baseUnit = Unit.baseUnitFor(product.saleUnit.dimension)
        if (request.quantity.unit != baseUnit) {
            return AddSaleItemResult.UnitMismatch(baseUnit, request.quantity.unit)
        }
        val unitPrice = product.currentSalePrice
        val subtotal = unitPrice.timesRatio(request.quantity.scaled, product.saleUnit.scale)
        val item = SaleItem(
            productId = product.id,
            productName = product.name,
            quantity = request.quantity,
            unitPrice = unitPrice,
            subtotal = subtotal
        )
        // 只有 DRAFT 单可继续加项；引用已结账/取消的单则另起新草稿
        //（追加式：完成单是不可再改的历史事实）
        val draft = request.draftSaleId?.let { sales.findDraft(it) }
            ?.takeIf { it.status == SaleStatus.DRAFT }
            ?: SaleOrder(
                id = UUID.randomUUID().toString(),
                storeId = request.storeId
            )
        val updated = draft.copy(items = draft.items + item, total = draft.computeTotal() + subtotal)
        sales.saveDraft(updated)
        return AddSaleItemResult.Success(updated)
    }
}
