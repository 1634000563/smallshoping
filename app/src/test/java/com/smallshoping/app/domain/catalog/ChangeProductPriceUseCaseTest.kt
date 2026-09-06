package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 026：改价用例——当前价更新与价格历史只追加不覆盖。 */
class ChangeProductPriceUseCaseTest {

    private val products = InMemoryProductRepository()
    private val changePrice = ChangeProductPriceUseCase(products)

    private fun seedProduct(price: Long = 400L): Product {
        val product = Product(
            id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
            saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
            currentSalePrice = Money(price), currentCostPrice = null
        )
        products.saveProduct(product)
        return product
    }

    @Test
    fun `正常路径：改价更新当前价并追加历史（oldPrice 为改前价）`() {
        seedProduct()
        val result = changePrice(ChangePriceRequest("P-1", Money(380), "yesterday_price"))
        assertTrue(result is ChangePriceResult.Success)
        val success = result as ChangePriceResult.Success
        assertEquals(400L, success.oldPriceMinor)
        assertEquals(380L, success.newPriceMinor)
        assertEquals(Money(380), products.findProductById("P-1")!!.currentSalePrice)
        val history = products.priceHistory("P-1")
        assertEquals(1, history.size)
        assertEquals(Money(400), history[0].oldPrice)
        assertEquals(PriceType.SALE, history[0].priceType)
    }

    @Test
    fun `幂等：同价改价不追加历史`() {
        seedProduct(price = 380L)
        val result = changePrice(ChangePriceRequest("P-1", Money(380), "yesterday_price"))
        assertTrue(result is ChangePriceResult.Unchanged)
        assertTrue(products.priceHistory("P-1").isEmpty())
    }

    @Test
    fun `异常路径：商品不存在与负价`() {
        seedProduct()
        assertTrue(
            changePrice(ChangePriceRequest("P-404", Money(380), "test"))
                is ChangePriceResult.ProductNotFound
        )
        assertThrows(IllegalArgumentException::class.java) {
            changePrice(ChangePriceRequest("P-1", Money(-1), "test"))
        }
    }
}
