package com.smallshoping.app.core.money

/**
 * 金额：最小货币单位整数（分），V1 单币种（人民币）。
 *
 * 数据宪法 #2：金额禁止 Float/Double。所有运算用 Math.*Exact，
 * Long 溢出时快速失败（ArithmeticException），不允许静默溢出。
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(minor, other.minor))

    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, other.minor))

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor))

    /** 数量乘数（整数），用于单价 × 数量等场景；不接受浮点乘数。 */
    operator fun times(multiplier: Int): Money = Money(Math.multiplyExact(minor, multiplier.toLong()))

    /** 单价 × 数量（最小刻度整数，如 380分/斤 × 2斤）。 */
    operator fun times(multiplier: Long): Money = Money(Math.multiplyExact(minor, multiplier))

    /**
     * 单价 × 有理数量比：minor × numerator / denominator，四舍五入（half-up）。
     * 用于单价（每 1 展示单位）× 基本单位数量：380分/斤 × 1180克 / 500 = 897分。
     */
    fun timesRatio(numerator: Long, denominator: Long): Money {
        require(denominator > 0) { "分母必须为正" }
        require(numerator >= 0) { "数量必须非负" }
        val product = Math.multiplyExact(minor, numerator)
        return Money((product + denominator / 2) / denominator)
    }

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    val isZero: Boolean get() = minor == 0L

    val isNegative: Boolean get() = minor < 0

    companion object {

        val ZERO = Money(0L)

        /** 从整数「元」构造；小数元必须由调用方先换算为分，禁止 Double 入参。 */
        fun fromYuan(yuan: Long): Money = Money(Math.multiplyExact(yuan, 100L))

        fun fromYuan(yuan: Int): Money = fromYuan(yuan.toLong())
    }
}
