package com.smallshoping.app.core.quantity

/** 解析结果：规范数量（基本单位刻度）+ 原始展示文本 + 来源单位（Task 059 建商品用）。 */
data class ParsedQuantity(
    val quantity: Quantity,
    val displayText: String,
    val sourceUnit: Unit? = null
)

/**
 * 数量文本解析（称重精度规则，Task 018）：
 * 「2斤」「两斤」「2.36斤」「500克」「1.25kg」「3个」「半斤」。
 *
 * - 一律换算为维度基本单位（克/个/米）的整数刻度，全程无浮点：
 *   「2.36斤」→ 236/100 × 500 = 1180 克（有理数精确换算，除不尽则拒绝）；
 * - 小数位不得超过单位声明的精度（斤 2 位、公斤 3 位、克/个/盒 0 位）；
 * - 解析失败返回 null，调用方走澄清，绝不猜测。
 */
object QuantityParser {

    private val UNIT_MAP = mapOf(
        "斤" to Unit.JIN, "公斤" to Unit.KILOGRAM, "kg" to Unit.KILOGRAM,
        "克" to Unit.GRAM, "g" to Unit.GRAM,
        "个" to Unit.PIECE, "盒" to Unit.BOX, "米" to Unit.METER
    )

    /** 半斤=1/2 斤 等有理数表示 */
    private val RATIONAL_NUMERALS = mapOf(
        "一" to (1L to 1L), "两" to (2L to 1L), "二" to (2L to 1L), "三" to (3L to 1L),
        "四" to (4L to 1L), "五" to (5L to 1L), "六" to (6L to 1L), "七" to (7L to 1L),
        "八" to (8L to 1L), "九" to (9L to 1L), "十" to (10L to 1L), "半" to (1L to 2L)
    )

    private val PATTERN = Regex("^(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|g|个|盒|米)$")

    fun parse(text: String): ParsedQuantity? {
        val match = PATTERN.find(text.trim()) ?: return null
        val rawNumber = match.groupValues[1]
        val unit = UNIT_MAP[match.groupValues[2]] ?: return null

        // 数字 → 有理数 numerator/denominator
        val (numerator, denominator) = when {
            rawNumber.all { it.isDigit() } -> rawNumber.toLongOrNull()?.let { it to 1L } ?: return null
            rawNumber.contains('.') -> {
                val parts = rawNumber.split(".")
                val frac = parts[1]
                if (frac.length > unit.decimalPlaces) return null // 超出称重精度
                val intPart = parts[0].toLongOrNull() ?: return null
                val den = powerOf10(frac.length)
                val fracValue = frac.toLongOrNull() ?: return null
                Math.addExact(Math.multiplyExact(intPart, den), fracValue) to den
            }
            else -> RATIONAL_NUMERALS[rawNumber] ?: return null
        }

        // 换算为基本单位：numerator × scale / denominator，必须整除
        val product = Math.multiplyExact(numerator, unit.scale)
        if (product % denominator != 0L) return null
        val baseScaled = product / denominator
        val baseUnit = Unit.baseUnitFor(unit.dimension)
        return ParsedQuantity(Quantity(baseScaled, baseUnit), text.trim(), unit)
    }

    private fun powerOf10(n: Int): Long {
        var result = 1L
        repeat(n) { result = Math.multiplyExact(result, 10L) }
        return result
    }
}
