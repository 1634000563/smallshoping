package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Task 030：单位换算引擎（有理数精确，除不尽/超精度拒绝）。 */
class UnitConversionEngineTest {

    private val products = InMemoryProductRepository()
    private val engine = UnitConversionEngine(products)

    private fun seedBolt() {
        products.saveProduct(
            Product(
                id = "P-10", storeId = "STORE-1", name = "304 M8x30 外六角螺栓",
                normalizedName = normalize("304 M8x30 外六角螺栓"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.BOX,
                currentSalePrice = Money(50), currentCostPrice = Money(2000)
            )
        )
    }

    @Test
    fun `同维度通用换算：斤 kg 克 互转（含小数精度）`() {
        // 2 斤 = 1000 克
        assertEquals(1000L, engine.convertScaled("P-10", Quantity(1000, Unit.GRAM), Unit.GRAM))
        assertEquals(1000L, engine.convertScaled("P-10", Quantity(1000, Unit.GRAM), Unit.GRAM))
        assertEquals(236L, engine.convertScaled("P-10", Quantity(1180, Unit.GRAM), Unit.JIN)) // 2.36 斤
        assertEquals(500L, engine.convertScaled("P-10", Quantity(2500, Unit.GRAM), Unit.JIN)) // 2.5kg → 5 斤
        assertEquals(2500L, engine.convertScaled("P-10", Quantity(2500, Unit.GRAM), Unit.KILOGRAM)) // 2.5kg
        // 除不尽/超精度 → null（不猜）
        assertNull(engine.convertScaled("P-10", Quantity(1181, Unit.GRAM), Unit.JIN))
        // 0.1kg 在 3 位精度内：100×10^3/1000=100 → 100（0.100kg）
        assertEquals(100L, engine.convertScaled("P-10", Quantity(100, Unit.GRAM), Unit.KILOGRAM))
    }

    @Test
    fun `同单位恒等与跨维度无商品换算拒绝`() {
        assertEquals(5L, engine.convertScaled("P-10", Quantity(5, Unit.PIECE), Unit.PIECE))
        assertEquals(200L, engine.convertScaled("P-10", Quantity(2, Unit.METER), Unit.METER)) // 2.00 米
        // 克 → 个：跨维度且无商品级换算 → null
        assertNull(engine.convertScaled("P-10", Quantity(500, Unit.GRAM), Unit.PIECE))
    }

    @Test
    fun `商品级换算：盒 ↔ 个 正反两向`() {
        seedBolt()
        products.addConversion(
            UnitConversion(
                productId = "P-10", fromUnit = Unit.BOX, toUnit = Unit.PIECE,
                ratioNumerator = 50, ratioDenominator = 1
            )
        )
        // 2 盒 = 100 个
        assertEquals(100L, engine.convertScaled("P-10", Quantity(2, Unit.BOX), Unit.PIECE))
        assertEquals(100L, engine.convert("P-10", Quantity(2, Unit.BOX), Unit.PIECE)?.scaled)
        // 反向：50 个 = 1 盒；49 个不足一盒 → null
        assertEquals(1L, engine.convertScaled("P-10", Quantity(50, Unit.PIECE), Unit.BOX))
        assertNull(engine.convertScaled("P-10", Quantity(49, Unit.PIECE), Unit.BOX))
    }

    @Test
    fun `商品级跨维度换算：盒 → 克（一盒螺丝粉 500 克）`() {
        seedBolt()
        products.addConversion(
            UnitConversion(
                productId = "P-10", fromUnit = Unit.BOX, toUnit = Unit.GRAM,
                ratioNumerator = 500, ratioDenominator = 1
            )
        )
        assertEquals(1500L, engine.convertScaled("P-10", Quantity(3, Unit.BOX), Unit.GRAM))
        // 反向：500 克 = 1 盒
        assertEquals(1L, engine.convertScaled("P-10", Quantity(500, Unit.GRAM), Unit.BOX))
    }

    @Test
    fun `整数刻度目标便捷版：非整数刻度目标拒绝`() {
        seedBolt()
        assertThrows(IllegalArgumentException::class.java) {
            engine.convert("P-10", Quantity(1000, Unit.GRAM), Unit.JIN)
        }
    }
}
