package com.smallshoping.app.data.repository

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.AliasSource
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.PriceType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryProductRepositoryTest {

    private val repo = InMemoryProductRepository()

    private val product = Product(
        id = "P-1",
        storeId = "STORE-1",
        name = "土豆",
        normalizedName = normalize("土豆"),
        saleUnit = Unit.JIN,
        purchaseUnit = Unit.JIN,
        currentSalePrice = Money(380),
        currentCostPrice = Money(280)
    )

    @Test
    fun `正常路径：保存后按 id 与归一化名查找`() {
        repo.saveProduct(product)
        assertEquals(product, repo.findProductById("P-1"))
        assertEquals(product, repo.findByNormalizedName("土豆"))
        assertNull(repo.findByNormalizedName("土豆 "))
    }

    @Test
    fun `别名：归一化别名命中商品`() {
        repo.saveProduct(product)
        repo.addAlias(ProductAlias("A-1", "P-1", "洋芋", normalize("洋芋"), AliasSource.BOSS_SPEECH, 90))
        assertEquals(product, repo.findByAlias("洋芋"))
        assertEquals(product, repo.findByAlias("洋芋"))
        assertEquals(1, repo.aliases("P-1").size)
        assertEquals(90, repo.aliases("P-1")[0].confidence)
    }

    @Test
    fun `异常路径：别名指向不存在商品被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            repo.addAlias(ProductAlias("A-1", "P-X", "洋芋", "洋芋", AliasSource.MANUAL, 90))
        }
    }

    @Test
    fun `价格历史：只追加且保持顺序，不存在商品被拒绝`() {
        repo.saveProduct(product)
        repo.appendPriceHistory(
            PriceHistoryEntry("H-1", "P-1", PriceType.SALE, null, Money(380), Unit.JIN, "test", 1000L))
        repo.appendPriceHistory(
            PriceHistoryEntry("H-2", "P-1", PriceType.SALE, Money(380), Money(400), Unit.JIN, "test", 2000L))
        val history = repo.priceHistory("P-1")
        assertEquals(2, history.size)
        assertEquals(listOf(1000L, 2000L), history.map { it.createdAtMillis })
        assertEquals(Money(380), history[1].oldPrice)
        assertThrows(IllegalArgumentException::class.java) {
            repo.appendPriceHistory(
                PriceHistoryEntry("H-3", "P-X", PriceType.SALE, null, Money(1), Unit.JIN, "test"))
        }
    }

    @Test
    fun `边界输入：空仓库查询返回 null 与空列表`() {
        assertNull(repo.findProductById("P-X"))
        assertTrue(repo.aliases("P-X").isEmpty())
        assertTrue(repo.priceHistory("P-X").isEmpty())
    }
}
