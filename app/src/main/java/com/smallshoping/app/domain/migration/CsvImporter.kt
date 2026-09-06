package com.smallshoping.app.domain.migration

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType

/**
 * CSV 导入（旧系统迁移，Task 049）：
 * 商品格式 `name,sale_unit,sale_price_minor,cost_price_minor,stock`
 * （与 [CsvExporter.exportProducts] 同构，可往返）。
 *
 * - 同名商品已存在 → 跳过（幂等，不覆盖既有价格）；
 * - 非法行跳过并统计，不中断整批导入；
 * - 库存以 PURCHASE_IN 流水入库（幂等键 `migrate:{productId}:{stock}`，
 *   重复导入不重复入库）。
 */
class CsvImporter(
    private val products: ProductRepository,
    private val ledger: Ledger
) {

    data class ImportResult(
        val imported: Int,
        val skippedExisting: Int,
        val invalidRows: Int
    )

    fun importProducts(csv: String, storeId: String): ImportResult {
        var imported = 0
        var skipped = 0
        var invalid = 0
        for ((index, line) in csv.lines().withIndex()) {
            if (index == 0 || line.isBlank()) continue // 跳过表头
            val fields = CsvIo.decodeRow(line)
            if (fields.size < 3) {
                invalid++
                continue
            }
            val name = fields[0].trim()
            val salePrice = fields[2].toLongOrNull()
            if (name.isBlank() || salePrice == null || salePrice < 0) {
                invalid++
                continue
            }
            if (products.findByNormalizedName(normalize(name)) != null) {
                skipped++
                continue
            }
            val costPrice = fields.getOrNull(3)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { Money(it) }
            val product = Product(
                id = "MIG-$index-$name",
                storeId = storeId,
                name = name,
                normalizedName = normalize(name),
                saleUnit = unitByName(fields.getOrNull(1)?.trim()) ?: Unit.JIN,
                purchaseUnit = unitByName(fields.getOrNull(1)?.trim()) ?: Unit.JIN,
                currentSalePrice = Money(salePrice),
                currentCostPrice = costPrice
            )
            products.saveProduct(product)
            val stockScaled = fields.getOrNull(4)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: 0L
            if (stockScaled > 0) {
                ledger.append(
                    LedgerEntry(
                        scope = LedgerScope(LedgerScopeType.STOCK, product.id),
                        movementType = MovementType.PURCHASE_IN,
                        delta = stockScaled,
                        idempotencyKey = IdempotencyKey("migrate:${product.id}:$stockScaled"),
                        note = "旧系统迁移入库"
                    )
                )
            }
            imported++
        }
        return ImportResult(imported, skipped, invalid)
    }

    private fun unitByName(name: String?): Unit? = when (name?.trim()) {
        "斤" -> Unit.JIN
        "公斤", "千克" -> Unit.KILOGRAM
        "克" -> Unit.GRAM
        "个" -> Unit.PIECE
        "盒" -> Unit.BOX
        "米" -> Unit.METER
        else -> null
    }
}
