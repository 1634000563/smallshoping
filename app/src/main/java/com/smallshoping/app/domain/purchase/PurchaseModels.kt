package com.smallshoping.app.domain.purchase

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity

/** 采购单状态（spec 08 §6 最小子集）。 */
enum class PurchaseStatus { COMPLETED, CANCELLED }

/** 采购行：成本可选（未给进价时只入库不改成本）。 */
data class PurchaseItem(
    val productId: String,
    val productName: String,
    val quantity: Quantity,
    val unitCost: Money?,
    val subtotalCost: Money?
)

/** 采购单：V1 一次入库一张单（多商品采购单由 Task 033 扩展）。 */
data class PurchaseOrder(
    val id: String,
    val storeId: String,
    val supplierId: String? = null,
    val items: List<PurchaseItem>,
    val status: PurchaseStatus = PurchaseStatus.COMPLETED,
    val idempotencyKey: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(storeId.isNotBlank()) { "store_id 不能为空" }
        require(idempotencyKey.isNotBlank()) { "幂等键不能为空" }
    }
}
