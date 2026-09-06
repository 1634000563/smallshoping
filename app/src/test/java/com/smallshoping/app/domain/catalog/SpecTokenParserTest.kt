package com.smallshoping.app.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 032：规格 token 提取（通用，不绑五金）。 */
class SpecTokenParserTest {

    @Test
    fun `字母数字组合与多位数字`() {
        assertEquals(listOf("m8x30"), SpecTokenParser.extract("M8x30"))
        assertEquals(listOf("304", "m8x30"), SpecTokenParser.extract("304 M8x30 外六角螺栓"))
        assertEquals(listOf("2.5mm"), SpecTokenParser.extract("2.5mm 钻头"))
        assertEquals(listOf("gb5782"), SpecTokenParser.extract("GB5782"))
    }

    @Test
    fun `单数字与量词噪声不提取`() {
        assertTrue(SpecTokenParser.extract("卖8个螺丝").isEmpty())
        assertTrue(SpecTokenParser.extract("土豆").isEmpty())
        assertTrue(SpecTokenParser.extract("").isEmpty())
    }

    @Test
    fun `去重保持顺序`() {
        assertEquals(listOf("304", "m8x30"), SpecTokenParser.extract("304 M8x30 304"))
    }
}
