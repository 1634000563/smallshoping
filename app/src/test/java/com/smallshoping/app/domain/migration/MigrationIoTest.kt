package com.smallshoping.app.domain.migration

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 049 验收：CSV 往返、转义、旧系统迁移幂等。 */
class MigrationIoTest {

    private val root = CompositionRoot()

    @Test
    fun `CSV 转义：逗号引号换行字段往返一致`() {
        val fields = listOf("土豆", "带,逗号", "带\"引号\"", "带\n换行")
        val line = CsvIo.encodeRow(fields)
        assertEquals(fields, CsvIo.decodeRow(line))
    }

    @Test
    fun `商品导出→导入：往返一致，重复导入幂等跳过`() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = IdempotencyKey("IN-049"),
                note = "测试入库"
            )
        )
        val csv = root.csvExporter.exportProducts()
        assertTrue(csv.contains("name,sale_unit,sale_price_minor"))
        assertTrue(csv.contains("土豆,斤,380,280,5000"))

        // 导入到「新店」
        val target = CompositionRoot()
        val result = target.csvImporter.importProducts(csv, storeId = "STORE-2")
        assertEquals(1, result.imported)
        assertEquals(0, result.skippedExisting)
        val migrated = target.products.findByNormalizedName(normalize("土豆"))!!
        assertEquals(Money(380), migrated.currentSalePrice)
        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(target.ledger).stockOf(migrated.id))

        // 重复导入：同名跳过、库存不重复入库
        val again = target.csvImporter.importProducts(csv, storeId = "STORE-2")
        assertEquals(0, again.imported)
        assertEquals(1, again.skippedExisting)
        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(target.ledger).stockOf(migrated.id))
    }

    @Test
    fun `非法行跳过统计：不中断整批导入`() {
        val badCsv = """
            name,sale_unit,sale_price_minor,cost_price_minor,stock
            土豆,斤,380,280,5000
            ,斤,100,,
            西红柿,斤,abc,,
        """.trimIndent()
        val result = root.csvImporter.importProducts(badCsv, storeId = "STORE-1")
        assertEquals(1, result.imported)
        assertEquals(2, result.invalidRows)
        assertTrue(root.products.findByNormalizedName(normalize("土豆")) != null)
        assertTrue(root.products.findByNormalizedName(normalize("西红柿")) == null)
    }

    @Test
    fun `销售明细与会员流水导出：事实可读`() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = IdempotencyKey("IN-049b"),
                note = "测试入库"
            )
        )
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(1000, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "ck-049")
        )

        val salesCsv = root.csvExporter.exportSales()
        assertTrue(salesCsv.contains("sale_id,completed_at,product_name"))
        assertTrue(salesCsv.contains("土豆,1000,380,760,CASH"))

        val purchaseCsv = root.csvExporter.exportPurchases()
        assertTrue(purchaseCsv.contains("P-1,5000"))
        val paymentCsv = root.csvExporter.exportPayments()
        assertTrue(paymentCsv.contains("CASH,CONFIRMED,760"))
    }
}
