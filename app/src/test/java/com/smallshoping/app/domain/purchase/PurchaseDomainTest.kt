package com.smallshoping.app.domain.purchase

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemoryPurchaseRepository
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.inventory.StockQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数量统一为基本单位刻度（MASS→克）：100斤=50000克（Task 018）。 */
class PurchaseDomainTest {

    private val ledger = InMemoryLedger()
    private val products = InMemoryProductRepository()
    private val purchases = InMemoryPurchaseRepository(ledger, products)
    private val stock = StockQuery(ledger)
    private val purchaseIn = PurchaseInUseCase(purchases, products, stock)

    private val potato = Product(
        id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
        saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
        currentSalePrice = Money(380), currentCostPrice = Money(280)
    )

    @Test
    fun `正常路径：入库加库存并按加权平均更新成本`() {
        products.saveProduct(potato)
        val result = purchaseIn(
            PurchaseInRequest("STORE-1", "P-1", Quantity(50000, Unit.GRAM), Money(300), "PO-1")
        ) as PurchaseInResult.Success
        assertEquals(50000L, stock.stockOf("P-1")) // 100斤
        // 无库存时直接采进价
        assertEquals(300L, result.newCostMinor)
        assertEquals(Money(300), products.findProductById("P-1")!!.currentCostPrice)
    }

    @Test
    fun `加权平均：两次入库成本取整正确（整数运算）`() {
        products.saveProduct(potato.copy(currentCostPrice = Money(300)))
        purchaseIn(PurchaseInRequest("STORE-1", "P-1", Quantity(50000, Unit.GRAM), Money(300), "PO-1"))
        val second = purchaseIn(
            PurchaseInRequest("STORE-1", "P-1", Quantity(50000, Unit.GRAM), Money(200), "PO-2")
        ) as PurchaseInResult.Success
        // (50000*300 + 50000*200) / 100000 = 250
        assertEquals(250L, second.newCostMinor)
        assertEquals(Money(250), products.findProductById("P-1")!!.currentCostPrice)
        assertEquals(100000L, stock.stockOf("P-1"))
    }

    @Test
    fun `幂等：同键重复入库只生效一次`() {
        products.saveProduct(potato)
        val first = purchaseIn(PurchaseInRequest("STORE-1", "P-1", Quantity(5000, Unit.GRAM), Money(300), "PO-1"))
        assertTrue(first is PurchaseInResult.Success)
        val second = purchaseIn(PurchaseInRequest("STORE-1", "P-1", Quantity(5000, Unit.GRAM), Money(300), "PO-1"))
        assertTrue(second is PurchaseInResult.AlreadyCompleted)
        assertEquals(5000L, stock.stockOf("P-1"))
    }

    @Test
    fun `不给进价：只入库不改成本`() {
        products.saveProduct(potato)
        val result = purchaseIn(
            PurchaseInRequest("STORE-1", "P-1", Quantity(5000, Unit.GRAM), null, "PO-1")
        ) as PurchaseInResult.Success
        assertEquals(5000L, stock.stockOf("P-1"))
        assertEquals(null, result.newCostMinor)
        assertEquals(Money(280), products.findProductById("P-1")!!.currentCostPrice)
    }

    @Test
    fun `异常路径：商品不存在、维度不符、负进价被拒绝`() {
        products.saveProduct(potato)
        assertTrue(
            purchaseIn(PurchaseInRequest("STORE-1", "P-X", Quantity(500, Unit.GRAM), Money(1), "PO-1"))
                is PurchaseInResult.ProductNotFound
        )
        assertTrue(
            purchaseIn(PurchaseInRequest("STORE-1", "P-1", Quantity(3, Unit.PIECE), Money(1), "PO-2"))
                is PurchaseInResult.UnitMismatch
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            purchaseIn(PurchaseInRequest("STORE-1", "P-1", Quantity(500, Unit.GRAM), Money(-1), "PO-3"))
        }
    }

    @Test
    fun `边界输入：加权平均计算边界`() {
        // 无历史成本或库存 → 直接采进价
        assertEquals(280L, WeightedAverageCost.compute(0, 280, 10, 280))
        assertEquals(280L, WeightedAverageCost.compute(5, null, 10, 280))
        // 向下取整
        assertEquals(3L, WeightedAverageCost.compute(1, 2, 1, 5)) // (2+5)/2=3.5 → 3
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WeightedAverageCost.compute(1, 2, 0, 5)
        }
    }
}
