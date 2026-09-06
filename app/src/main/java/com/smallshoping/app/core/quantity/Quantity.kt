package com.smallshoping.app.core.quantity

/**
 * 数量：Long 最小刻度整数（spec 03）+ 必须携带 [Unit]（数据宪法 #3）。
 *
 * 本类型只保证同单位运算；跨单位换算由 Task 030 单位换算引擎提供，
 * 未换算就跨单位运算属于编程错误，直接快速失败。
 */
data class Quantity(val scaled: Long, val unit: Unit) : Comparable<Quantity> {

    operator fun plus(other: Quantity): Quantity {
        requireSameUnit(other)
        return Quantity(Math.addExact(scaled, other.scaled), unit)
    }

    operator fun minus(other: Quantity): Quantity {
        requireSameUnit(other)
        return Quantity(Math.subtractExact(scaled, other.scaled), unit)
    }

    operator fun times(multiplier: Int): Quantity =
        Quantity(Math.multiplyExact(scaled, multiplier.toLong()), unit)

    override fun compareTo(other: Quantity): Int {
        requireSameUnit(other)
        return scaled.compareTo(other.scaled)
    }

    val isZero: Boolean get() = scaled == 0L

    val isNegative: Boolean get() = scaled < 0

    private fun requireSameUnit(other: Quantity) {
        require(unit == other.unit) {
            "数量单位不一致：${unit.code} vs ${other.unit.code}（应先经单位换算引擎换算）"
        }
    }
}
