package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 029：属性与包装换算模型（跨行业通用，五金 304/M8x30 属性化表达）。 */
class ProductAttributeModelTest {

    private val products = InMemoryProductRepository()

    private fun seedBolt() {
        products.saveProduct(
            Product(
                id = "P-10", storeId = "STORE-1", name = "304 M8x30 外六角螺栓",
                normalizedName = normalize("304 M8x30 外六角螺栓"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(50), currentCostPrice = null
            )
        )
    }

    private fun attr(name: String, value: String) = ProductAttribute(
        productId = "P-10", name = name, normalizedName = normalize(name),
        value = value, normalizedValue = normalize(value)
    )

    @Test
    fun `模型校验：空字段拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProductAttribute(
                productId = "P-10", name = "", normalizedName = "",
                value = "304", normalizedValue = "304"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductAttribute(
                productId = "P-10", name = "材质", normalizedName = "材质",
                value = "", normalizedValue = ""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductAttribute(
                productId = "", name = "材质", normalizedName = "材质",
                value = "304", normalizedValue = "304"
            )
        }
    }

    @Test
    fun `属性写入与查询：按商品隔离、按归一化名精确查找`() {
        seedBolt()
        products.addAttribute(attr("材质", "304"))
        products.addAttribute(attr("规格", "M8x30"))
        products.addAttribute(attr("类型", "外六角螺栓"))
        assertEquals(3, products.attributes("P-10").size)
        assertEquals("M8x30", products.findAttribute("P-10", normalize("规格"))?.value)
        assertEquals("304", products.findAttribute("P-10", normalize("材质"))?.value)
        assertNull(products.findAttribute("P-10", normalize("颜色")))
        assertTrue(products.attributes("P-404").isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            products.addAttribute(
                ProductAttribute(
                    productId = "P-404", name = "材质", normalizedName = "材质",
                    value = "304", normalizedValue = "304"
                )
            )
        }
    }

    @Test
    fun `换算模型：有理数比例校验与查找`() {
        seedBolt()
        val c = UnitConversion(
            productId = "P-10", fromUnit = Unit.BOX, toUnit = Unit.PIECE,
            ratioNumerator = 50, ratioDenominator = 1
        )
        products.addConversion(c)
        assertEquals(1, products.conversions("P-10").size)
        assertEquals(
            50L,
            products.findConversion("P-10", Unit.BOX, Unit.PIECE)?.ratioNumerator
        )
        assertNull(products.findConversion("P-10", Unit.PIECE, Unit.BOX))
        // 模型校验：比例必须为正
        assertThrows(IllegalArgumentException::class.java) {
            UnitConversion(
                productId = "P-10", fromUnit = Unit.BOX, toUnit = Unit.PIECE,
                ratioNumerator = 0, ratioDenominator = 1
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnitConversion(
                productId = "P-10", fromUnit = Unit.BOX, toUnit = Unit.PIECE,
                ratioNumerator = 1, ratioDenominator = 0
            )
        }
        // 换算指向不存在商品拒绝
        assertThrows(IllegalArgumentException::class.java) {
            products.addConversion(
                UnitConversion(
                    productId = "P-404", fromUnit = Unit.BOX, toUnit = Unit.PIECE,
                    ratioNumerator = 50, ratioDenominator = 1
                )
            )
        }
    }
}
