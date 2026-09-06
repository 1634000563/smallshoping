package com.smallshoping.app.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParserTest {

    @Test
    fun `正常路径：整数、小数、块毛元各形态`() {
        assertEquals(20000L, MoneyParser.parseYuanToMinor("200"))
        assertEquals(280L, MoneyParser.parseYuanToMinor("2.8"))
        assertEquals(280L, MoneyParser.parseYuanToMinor("2块8"))
        assertEquals(300L, MoneyParser.parseYuanToMinor("3块"))
        assertEquals(20000L, MoneyParser.parseYuanToMinor("200元"))
        assertEquals(280L, MoneyParser.parseYuanToMinor("2块8毛"))
    }

    @Test
    fun `边界输入：零、两位小数、前后空白`() {
        assertEquals(0L, MoneyParser.parseYuanToMinor("0"))
        assertEquals(5L, MoneyParser.parseYuanToMinor("0.05"))
        assertEquals(280L, MoneyParser.parseYuanToMinor(" 2.8 "))
    }

    @Test
    fun `异常路径：非法输入返回 null`() {
        assertNull(MoneyParser.parseYuanToMinor("2.555"))
        assertNull(MoneyParser.parseYuanToMinor("abc"))
        assertNull(MoneyParser.parseYuanToMinor(""))
        assertNull(MoneyParser.parseYuanToMinor("2块88"))
        assertNull(MoneyParser.parseYuanToMinor("-3"))
    }
}
