package com.smallshoping.app.core.quantity

/**
 * 数量文本解析：「2斤」「两斤」「500克」「1kg」「3个」「2盒」「5米」。
 *
 * V1 最小：仅整数与单个中文数字；小数（2.5斤）由 Task 018 称重精度规则处理。
 * 解析失败返回 null，调用方走澄清，绝不猜测。
 */
object QuantityParser {

    private val UNIT_MAP = mapOf(
        "斤" to Unit.JIN, "公斤" to Unit.KILOGRAM, "kg" to Unit.KILOGRAM,
        "克" to Unit.GRAM, "g" to Unit.GRAM,
        "个" to Unit.PIECE, "盒" to Unit.BOX, "米" to Unit.METER
    )

    private val CHINESE_NUMERALS = mapOf(
        "一" to 1L, "两" to 2L, "二" to 2L, "三" to 3L, "四" to 4L, "五" to 5L,
        "六" to 6L, "七" to 7L, "八" to 8L, "九" to 9L, "十" to 10L
    )

    private val PATTERN = Regex("^(\\d+|[一两二三四五六七八九十])\\s*(斤|公斤|kg|克|g|个|盒|米)$")

    fun parse(text: String): Quantity? {
        val match = PATTERN.find(text.trim()) ?: return null
        val number = match.groupValues[1]
        val unitCode = match.groupValues[2]
        val scaled = when {
            number.all { it.isDigit() } -> number.toLongOrNull() ?: return null
            else -> CHINESE_NUMERALS[number] ?: return null
        }
        val unit = UNIT_MAP[unitCode] ?: return null
        return Quantity(scaled, unit)
    }
}
