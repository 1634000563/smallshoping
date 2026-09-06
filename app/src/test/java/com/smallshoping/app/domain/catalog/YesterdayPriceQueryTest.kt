package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Task 026：昨日售价查询（业务日边界）。 */
class YesterdayPriceQueryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    // 固定「今天」：2026-09-06 中午（上海时区）
    private val todayNoon = Instant.parse("2026-09-06T04:00:00Z").toEpochMilli()
    private val products = InMemoryProductRepository()
    private val query = YesterdayPriceQuery(products, "Asia/Shanghai") { todayNoon }

    private fun seedProduct(id: String = "P-1"): Product {
        val product = Product(
            id = id, storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
            saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
            currentSalePrice = Money(400), currentCostPrice = null
        )
        products.saveProduct(product)
        return product
    }

    private fun millisOf(date: String, hour: Int = 12): Long =
        Instant.parse("${date}T${hour.toString().padStart(2, '0')}:00:00Z").toEpochMilli()

    private fun history(
        productId: String,
        price: Long,
        priceType: PriceType = PriceType.SALE,
        createdAt: Long
    ) = PriceHistoryEntry(
        id = "H-$price-$createdAt", productId = productId, priceType = priceType,
        oldPrice = Money(price), newPrice = Money(price), unit = Unit.JIN,
        source = "test", createdAtMillis = createdAt
    )

    @Test
    fun `只取昨天业务日的 SALE 价格（按时间序）`() {
        seedProduct()
        products.appendPriceHistory(history("P-1", 380, createdAt = millisOf("2026-09-05", 10)))
        products.appendPriceHistory(history("P-1", 390, createdAt = millisOf("2026-09-05", 11)))
        // 干扰项：今天、前天、昨天改成本
        products.appendPriceHistory(history("P-1", 500, createdAt = millisOf("2026-09-06", 10)))
        products.appendPriceHistory(history("P-1", 370, createdAt = millisOf("2026-09-04", 10)))
        products.appendPriceHistory(
            history("P-1", 350, priceType = PriceType.COST, createdAt = millisOf("2026-09-05", 15))
        )
        val result = query.yesterdaySalePrices("P-1")
        assertEquals(listOf(380L, 390L), result.map { it.newPrice.minor })
        assertTrue(query.yesterdaySalePrices("P-404").isEmpty())
    }

    @Test
    fun `昨日边界：昨天本地零点含、今天本地零点不含`() {
        seedProduct()
        // 上海时区（UTC+8）：Sep 5 本地 00:00 = Sep 4 16:00Z；Sep 6 本地 00:00 = Sep 5 16:00Z
        val yesterdayStartLocal = Instant.parse("2026-09-04T16:00:00Z").toEpochMilli()
        val todayStartLocal = Instant.parse("2026-09-05T16:00:00Z").toEpochMilli()
        products.appendPriceHistory(history("P-1", 381, createdAt = yesterdayStartLocal))
        products.appendPriceHistory(history("P-1", 382, createdAt = todayStartLocal - 1))
        products.appendPriceHistory(history("P-1", 383, createdAt = todayStartLocal))
        assertEquals(listOf(381L, 382L), query.yesterdaySalePrices("P-1").map { it.newPrice.minor })
    }
}
