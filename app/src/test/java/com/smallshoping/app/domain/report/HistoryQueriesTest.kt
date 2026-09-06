package com.smallshoping.app.domain.report

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.PriceType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.AddSaleItemUseCase
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleUseCase
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 035：客户/商品/价格三类历史查询（销售事实聚合，只读）。 */
class HistoryQueriesTest {

    private val ledger = InMemoryLedger()
    private val products = InMemoryProductRepository()
    private val sales = InMemorySaleRepository(ledger)
    private val addItem = AddSaleItemUseCase(sales, products)
    private val checkout = CheckoutSaleUseCase(sales)
    private val customerHistory = CustomerHistory(sales)
    private val productHistory = ProductHistory(sales)

    private fun seedProduct(id: String, name: String, price: Long) {
        products.saveProduct(
            Product(
                id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(price), currentCostPrice = null
            )
        )
        ledger.append(
            com.smallshoping.app.domain.ledger.LedgerEntry(
                scope = com.smallshoping.app.domain.ledger.LedgerScope(
                    com.smallshoping.app.domain.ledger.LedgerScopeType.STOCK, id
                ),
                movementType = com.smallshoping.app.domain.ledger.MovementType.PURCHASE_IN,
                delta = 10000,
                idempotencyKey = com.smallshoping.app.domain.ledger.IdempotencyKey("IN-$id"),
                note = "测试入库"
            )
        )
    }

    private fun completeSale(customerId: String?, items: List<Pair<String, Quantity>>) {
        var draftId: String? = null
        for ((productId, quantity) in items) {
            val added = addItem(
                AddSaleItemRequest("STORE-1", draftId, productId, quantity)
            ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
            draftId = added.sale.id
        }
        checkout(
            CheckoutSaleRequest(
                saleId = draftId!!, paymentMethod = PaymentMethod.CASH,
                idempotencyKey = "ck-$draftId-$customerId", customerId = customerId
            )
        )
    }

    @Test
    fun `客户历史：完成单、累计消费、常用商品聚合`() {
        seedProduct("P-1", "土豆", 380)
        seedProduct("P-2", "西红柿", 500)
        // 老张两单：土豆 2 斤、土豆 1 斤 + 西红柿 1 斤
        completeSale("C-1", listOf("P-1" to Quantity(1000, Unit.GRAM)))
        completeSale(
            "C-1",
            listOf("P-1" to Quantity(500, Unit.GRAM), "P-2" to Quantity(500, Unit.GRAM))
        )
        // 别人的单不混入
        completeSale("C-2", listOf("P-1" to Quantity(1000, Unit.GRAM)))

        val completed = customerHistory.completedSales("C-1")
        assertEquals(2, completed.size)
        // 累计消费：760（2斤）+ 380（1斤）+ 500（西红柿1斤）= 1640 分
        assertEquals(1640L, customerHistory.totalSpentMinor("C-1"))
        // 常用商品：土豆 2 次 > 西红柿 1 次
        val top = customerHistory.topProducts("C-1")
        assertEquals(2, top.size)
        assertEquals("土豆", top[0].productName)
        assertEquals(2, top[0].purchaseCount)
        assertEquals(Money(1140), top[0].totalAmount)
    }

    @Test
    fun `商品历史：销售事件、最近成交价、累计`() {
        seedProduct("P-1", "土豆", 380)
        completeSale("C-1", listOf("P-1" to Quantity(1000, Unit.GRAM)))
        // 改价后第二次成交
        products.applyPriceChange(
            "P-1", Money(400),
            PriceHistoryEntry(
                id = "H-1", productId = "P-1", priceType = PriceType.SALE,
                oldPrice = Money(380), newPrice = Money(400), unit = Unit.JIN, source = "test"
            )
        )
        completeSale(null, listOf("P-1" to Quantity(500, Unit.GRAM)))

        val events = productHistory.saleEvents("P-1")
        assertEquals(2, events.size)
        assertEquals(380L, events[0].unitPriceMinor)
        assertEquals(400L, events[1].unitPriceMinor)
        assertEquals(400L, productHistory.latestSalePriceMinor("P-1"))
        assertEquals(1500L, productHistory.totalSoldScaled("P-1"))
        // 760（2斤@380）+ 400（1斤@400）= 1160 分
        assertEquals(1160L, productHistory.totalRevenueMinor("P-1"))
        assertTrue(productHistory.saleEvents("P-404").isEmpty())
        assertNull(productHistory.latestSalePriceMinor("P-404"))
    }

    @Test
    fun `价格历史查询：最近售价与成本`() {
        seedProduct("P-1", "土豆", 380)
        products.appendPriceHistory(
            PriceHistoryEntry(
                id = "H-1", productId = "P-1", priceType = PriceType.SALE,
                oldPrice = Money(400), newPrice = Money(380), unit = Unit.JIN, source = "test"
            )
        )
        products.appendPriceHistory(
            PriceHistoryEntry(
                id = "H-2", productId = "P-1", priceType = PriceType.COST,
                oldPrice = null, newPrice = Money(280), unit = Unit.JIN, source = "test"
            )
        )
        val query = com.smallshoping.app.domain.catalog.PriceHistoryQuery(products)
        assertEquals(380L, query.latestSalePrice("P-1")?.newPrice?.minor)
        assertEquals(280L, query.latestCostPrice("P-1")?.newPrice?.minor)
        assertNull(query.latestSalePrice("P-404"))
    }
}
