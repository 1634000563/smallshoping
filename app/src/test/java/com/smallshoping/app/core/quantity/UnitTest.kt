package com.smallshoping.app.core.quantity

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitTest {

    @Test
    fun `预定义单位：维度与刻度符合 spec 03`() {
        assertEquals(UnitDimension.MASS, Unit.GRAM.dimension)
        assertEquals(1L, Unit.GRAM.scale)
        assertEquals(1000L, Unit.KILOGRAM.scale)
        assertEquals(500L, Unit.JIN.scale)
        assertEquals(UnitDimension.COUNT, Unit.PIECE.dimension)
        assertEquals(UnitDimension.LENGTH, Unit.METER.dimension)
    }

    @Test
    fun `称重精度：小数位按单位声明`() {
        assertEquals(2, Unit.JIN.decimalPlaces)
        assertEquals(3, Unit.KILOGRAM.decimalPlaces)
        assertEquals(0, Unit.GRAM.decimalPlaces)
        assertEquals(0, Unit.PIECE.decimalPlaces)
    }

    @Test
    fun `基本单位：维度映射正确`() {
        assertEquals(Unit.GRAM, Unit.baseUnitFor(UnitDimension.MASS))
        assertEquals(Unit.PIECE, Unit.baseUnitFor(UnitDimension.COUNT))
        assertEquals(Unit.METER, Unit.baseUnitFor(UnitDimension.LENGTH))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Unit.baseUnitFor(UnitDimension.VOLUME)
        }
    }

    @Test
    fun `相同 code 视为同一单位`() {
        assertEquals(Unit.JIN, Unit("JIN", "斤", UnitDimension.MASS, 500, decimalPlaces = 2))
    }
}
