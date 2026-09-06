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
    fun `相同 code 视为同一单位`() {
        assertEquals(Unit.JIN, Unit("JIN", "斤", UnitDimension.MASS, 500))
    }
}
