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
