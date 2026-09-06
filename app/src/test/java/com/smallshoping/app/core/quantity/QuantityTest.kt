package com.smallshoping.app.core.quantity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityTest {

    private val jin = Unit.JIN
    private val kg = Unit.KILOGRAM

    @Test
    fun `正常路径：同单位加减乘与比较`() {
        assertEquals(Quantity(500, jin), Quantity(200, jin) + Quantity(300, jin))
        assertEquals(Quantity(100, jin), Quantity(300, jin) - Quantity(200, jin))
        assertEquals(Quantity(600, jin), Quantity(200, jin) * 3)
        assertTrue(Quantity(300, jin) > Quantity(200, jin))
        assertFalse(Quantity(200, jin).isZero)
        assertTrue(Quantity(0, jin).isZero)
    }

    @Test
    fun `边界输入：零与负数`() {
        assertEquals(Quantity(0, kg), Quantity(100, kg) - Quantity(100, kg))
        assertTrue(Quantity(-1, kg).isNegative)
        assertTrue((Quantity(-1, kg) - Quantity(2, kg)).isNegative)
    }

    @Test
    fun `异常路径：跨单位运算快速失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            Quantity(500, jin) + Quantity(1, kg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Quantity(500, jin) - Quantity(1, kg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Quantity(500, jin).compareTo(Quantity(1, kg))
        }
    }

    @Test
    fun `异常路径：Long 溢出快速失败`() {
        assertThrows(ArithmeticException::class.java) {
            Quantity(Long.MAX_VALUE, jin) + Quantity(1, jin)
        }
    }
}
