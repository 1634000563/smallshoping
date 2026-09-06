package com.smallshoping.app.core.quantity

/** 单位维度（spec 03：COUNT / MASS / LENGTH / VOLUME / OTHER）。 */
enum class UnitDimension { COUNT, MASS, LENGTH, VOLUME, OTHER }

/**
 * 计量单位。
 *
 * [scale] 表示相对该维度基本单位的整数倍数：克=1、千克=1000、斤=500。
 * [decimalPlaces] 为该单位允许的最大小数位（称重精度规则，Task 018）：
 * 斤 2 位、公斤 3 位、克/个/盒 0 位。
 * 商品级换算（一盒=几个）由 unit_conversion 按商品定义（Task 029/030）。
 *
 * 注意：本类名为 Unit，与 kotlin.Unit 同名；本包内代码默认解析到本类，
 * 包外使用请显式 import com.smallshoping.app.core.quantity.Unit。
 */
data class Unit(
    /** 稳定机器码，如 G / KG / JIN / PCS / BOX / M */
    val code: String,
    /** 展示名，如 克 / 千克 / 斤 / 个 / 盒 / 米 */
    val name: String,
    val dimension: UnitDimension,
    /** 相对该维度基本单位的整数倍数 */
    val scale: Long,
    /** 允许的最大小数位（称重精度规则） */
    val decimalPlaces: Int = 0
) {

    init {
        require(scale > 0) { "scale 必须为正" }
        require(decimalPlaces in 0..3) { "decimalPlaces 须在 0-3" }
    }

    companion object {

        val GRAM = Unit("G", "克", UnitDimension.MASS, 1, decimalPlaces = 0)
        val KILOGRAM = Unit("KG", "千克", UnitDimension.MASS, 1000, decimalPlaces = 3)
        val JIN = Unit("JIN", "斤", UnitDimension.MASS, 500, decimalPlaces = 2)
        val PIECE = Unit("PCS", "个", UnitDimension.COUNT, 1)
        /** 盒对个的换算依赖商品包装定义，scale 暂记 1，不做通用换算 */
        val BOX = Unit("BOX", "盒", UnitDimension.COUNT, 1)
        val METER = Unit("M", "米", UnitDimension.LENGTH, 1, decimalPlaces = 2)

        /**
         * 维度基本单位：库存/账本的规范刻度（spec 03 base_unit_id）。
         * 所有数量在入库/出库前必须换算为本单位。
         */
        fun baseUnitFor(dimension: UnitDimension): Unit = when (dimension) {
            UnitDimension.MASS -> GRAM
            UnitDimension.COUNT -> PIECE
            UnitDimension.LENGTH -> METER
            else -> throw IllegalArgumentException("V1 暂不支持 ${dimension} 维度的基本单位")
        }
    }
}
