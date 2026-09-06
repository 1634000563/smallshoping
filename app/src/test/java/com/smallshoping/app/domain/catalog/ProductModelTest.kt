package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductModelTest {

    private fun product(name: String = "土豆", price: Money = Money(380)) = Product(
        id = "P-1",
        storeId = "STORE-1",
        name = name,
        normalizedName = normalize(name),
        saleUnit = Unit.JIN,
        purchaseUnit = Unit.JIN,
        currentSalePrice = price,
        currentCostPrice = Money(280)
    )

    @Test
    fun `正常路径：商品模型构造与归一化`() {
        val p = product()
        assertEquals("土豆", p.name)
        assertEquals("土豆", p.normalizedName)
        assertEquals(Money(380), p.currentSalePrice)
        assertTrue(p.active)
    }

    @Test
    fun `边界输入：空名、空 store、负价格被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { product(name = "  ") }
        assertThrows(IllegalArgumentException::class.java) {
            product().copy(storeId = "")
        }
        assertThrows(IllegalArgumentException::class.java) { product(price = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) {
            product().copy(currentCostPrice = Money(-1))
        }
    }

    @Test
    fun `归一化：大小写、空白、中英文混合`() {
        assertEquals("土豆", normalize(" 土豆 "))
        assertEquals("土豆", normalize("土豆\n"))
        assertEquals("304 m8x30 螺栓", normalize(" 304  M8X30  螺栓 "))
        assertEquals("apple", normalize("Apple"))
        assertEquals("", normalize("   "))
    }

    @Test
    fun `别名：校验置信度范围与空别名`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProductAlias("A-1", "P-1", "  ", " ", AliasSource.BOSS_SPEECH, 80)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductAlias("A-1", "P-1", "洋芋", "洋芋", AliasSource.BOSS_SPEECH, 101)
        }
    }

    @Test
    fun `价格历史：负价格被拒绝，首次定价旧价为 null`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceHistoryEntry("H-1", "P-1", PriceType.SALE, null, Money(-1), Unit.JIN, "test")
        }
        val h = PriceHistoryEntry("H-1", "P-1", PriceType.SALE, null, Money(380), Unit.JIN, "test")
        assertEquals(null, h.oldPrice)
    }
}
