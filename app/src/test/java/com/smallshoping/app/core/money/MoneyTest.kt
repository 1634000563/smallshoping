package com.smallshoping.app.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `正常路径：加减乘与比较`() {
        assertEquals(Money(500), Money(200) + Money(300))
        assertEquals(Money(100), Money(300) - Money(200))
        assertEquals(Money(760), Money(380) * 2)
        assertEquals(Money(-380), -(Money(380)))
        assertTrue(Money(200) > Money(199))
        assertTrue(Money(200) >= Money(200))
        assertEquals(Money.ZERO, Money(100) - Money(100))
    }

    @Test
    fun `边界输入：零与负数`() {
        assertTrue(Money.ZERO.isZero)
        assertFalse(Money.ZERO.isNegative)
        assertTrue(Money(-1).isNegative)
        assertEquals(Money.ZERO, Money.ZERO + Money.ZERO)
    }

    @Test
    fun `异常路径：Long 溢出快速失败`() {
        assertThrows(ArithmeticException::class.java) {
            Money(Long.MAX_VALUE) + Money(1)
        }
        assertThrows(ArithmeticException::class.java) {
            Money(Long.MIN_VALUE) - Money(1)
        }
        assertThrows(ArithmeticException::class.java) {
            Money(Long.MAX_VALUE / 2) * 3
        }
        assertThrows(ArithmeticException::class.java) {
            Money.fromYuan(Long.MAX_VALUE)
        }
    }

    @Test
    fun `从元构造：整数元换算为分`() {
        assertEquals(Money(300), Money.fromYuan(3))
        assertEquals(Money(20000), Money.fromYuan(200))
        assertEquals(Money(0), Money.fromYuan(0))
    }
}
