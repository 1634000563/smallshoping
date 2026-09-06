package com.smallshoping.app.domain.sales

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesDomainTest {

    private val ledger = InMemoryLedger()
    private val products = InMemoryProductRepository()
    private val sales = InMemorySaleRepository(ledger)
    private val stock = StockQuery(ledger)

    private val addItem = AddSaleItemUseCase(sales, products)
    private val checkout = CheckoutSaleUseCase(sales)

    private val potato = Product(
        id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
        saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
        currentSalePrice = Money(380), currentCostPrice = Money(280)
    )
    private val tomato = Product(
        id = "P-2", storeId = "STORE-1", name = "西红柿", normalizedName = normalize("西红柿"),
        saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
        currentSalePrice = Money(500), currentCostPrice = Money(300)
    )

    private fun stockIn(productId: String, qty: Long, key: String) {
        ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, productId),
                movementType = MovementType.PURCHASE_IN,
                delta = qty,
                idempotencyKey = IdempotencyKey(key),
                note = "测试入库"
            )
        )
    }

    @Test
    fun `正常路径：加商品→结账→库存流水扣减，金额由 Domain 计算`() {
        products.saveProduct(potato)
        stockIn("P-1", 10, "IN-1")
        val added = addItem(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as AddSaleItemResult.Success
        assertEquals(Money(760), added.sale.computeTotal()) // 2斤 × 380分
        assertEquals(1, added.sale.items.size)

        val result = checkout(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "CK-1")
        ) as CheckoutSaleResult.Success
        assertEquals(SaleStatus.COMPLETED, result.sale.status)
        assertEquals(Money(760), result.sale.total)
        assertEquals(8L, stock.stockOf("P-1"))
        // 库存流水可追溯
        val entries = ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
        assertEquals(2, entries.size) // 入库 + 出库
        assertTrue(entries.any { it.movementType == MovementType.SALE_OUT && it.delta == -2L })
    }

    @Test
    fun `多商品：同商品多行合并为一条库存流水`() {
        products.saveProduct(potato)
        stockIn("P-1", 10, "IN-1")
        val a1 = addItem(AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))) as AddSaleItemResult.Success
        val a2 = addItem(AddSaleItemRequest("STORE-1", a1.sale.id, "P-1", Quantity(3, Unit.JIN))) as AddSaleItemResult.Success
        checkout(CheckoutSaleRequest(a2.sale.id, PaymentMethod.WECHAT, "CK-2"))
        val entries = ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
        val saleOut = entries.filter { it.movementType == MovementType.SALE_OUT }
        assertEquals(1, saleOut.size)
        assertEquals(-5L, saleOut[0].delta)
        assertEquals(5L, stock.stockOf("P-1"))
    }

    @Test
    fun `库存不足：拒绝且未发生任何写入，草稿保留`() {
        products.saveProduct(potato)
        stockIn("P-1", 1, "IN-1")
        val added = addItem(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as AddSaleItemResult.Success
        val result = checkout(CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "CK-3"))
        assertTrue(result is CheckoutSaleResult.InsufficientStock)
        assertEquals(listOf("P-1"), (result as CheckoutSaleResult.InsufficientStock).productIds)
        assertEquals(1L, stock.stockOf("P-1")) // 库存未动
        assertTrue(sales.findDraft(added.sale.id)?.status == SaleStatus.DRAFT)
    }

    @Test
    fun `负库存开关：显式开启才允许卖穿`() {
        products.saveProduct(potato)
        stockIn("P-1", 1, "IN-1")
        val added = addItem(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as AddSaleItemResult.Success
        val result = checkout(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "CK-4", allowNegativeStock = true)
        )
        assertTrue(result is CheckoutSaleResult.Success)
        assertEquals(-1L, stock.stockOf("P-1"))
    }

    @Test
    fun `幂等：重复结账同一幂等键不重复扣库存`() {
        products.saveProduct(potato)
        stockIn("P-1", 10, "IN-1")
        val added = addItem(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as AddSaleItemResult.Success
        val first = checkout(CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "CK-5"))
        assertTrue(first is CheckoutSaleResult.Success)
        val second = checkout(CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "CK-5"))
        assertTrue(second is CheckoutSaleResult.AlreadyCompleted)
        assertEquals(8L, stock.stockOf("P-1")) // 只扣一次
    }

    @Test
    fun `原子性：多商品结账其一不足则整体拒绝`() {
        products.saveProduct(potato)
        products.saveProduct(tomato)
        stockIn("P-1", 10, "IN-1")
        stockIn("P-2", 1, "IN-2")
        val a1 = addItem(AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))) as AddSaleItemResult.Success
        val a2 = addItem(AddSaleItemRequest("STORE-1", a1.sale.id, "P-2", Quantity(2, Unit.JIN))) as AddSaleItemResult.Success
        val result = checkout(CheckoutSaleRequest(a2.sale.id, PaymentMethod.CASH, "CK-6"))
        assertTrue(result is CheckoutSaleResult.InsufficientStock)
        // P-1 也未扣
        assertEquals(10L, stock.stockOf("P-1"))
        assertEquals(1L, stock.stockOf("P-2"))
    }

    @Test
    fun `空订单结账：拒绝`() {
        val draft = SaleOrder(id = "S-EMPTY", storeId = "STORE-1")
        sales.saveDraft(draft)
        assertTrue(checkout(CheckoutSaleRequest("S-EMPTY", PaymentMethod.CASH, "CK-7")) is CheckoutSaleResult.EmptyOrder)
    }

    @Test
    fun `商品不存在或单位不一致：拒绝`() {
        products.saveProduct(potato)
        assertTrue(
            addItem(AddSaleItemRequest("STORE-1", null, "P-X", Quantity(1, Unit.JIN)))
                is AddSaleItemResult.ProductNotFound
        )
        val unitMismatch = addItem(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(500, Unit.GRAM))
        )
        assertTrue(unitMismatch is AddSaleItemResult.UnitMismatch)
    }

    @Test
    fun `异常路径：金额溢出快速失败`() {
        products.saveProduct(potato)
        val huge = AddSaleItemRequest("STORE-1", null, "P-1", Quantity(Long.MAX_VALUE, Unit.JIN))
        org.junit.Assert.assertThrows(ArithmeticException::class.java) {
            addItem(huge)
        }
    }
}
