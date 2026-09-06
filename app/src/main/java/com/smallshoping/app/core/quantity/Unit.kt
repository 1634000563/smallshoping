package com.smallshoping.app.core.quantity

/** 单位维度（spec 03：COUNT / MASS / LENGTH / VOLUME / OTHER）。 */
enum class UnitDimension { COUNT, MASS, LENGTH, VOLUME, OTHER }

/**
 * 计量单位。
 *
 * [scale] 表示相对该维度基本单位的整数倍数：克=1、千克=1000、斤=500。
 * 商品级换算（一盒=几个）由 unit_conversion 按商品定义（Task 029/030），
 * 本类只承载通用单位事实。
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
    val scale: Long
) {

    companion object {

        val GRAM = Unit("G", "克", UnitDimension.MASS, 1)
        val KILOGRAM = Unit("KG", "千克", UnitDimension.MASS, 1000)
        val JIN = Unit("JIN", "斤", UnitDimension.MASS, 500)
        val PIECE = Unit("PCS", "个", UnitDimension.COUNT, 1)
        /** 盒对个的换算依赖商品包装定义，scale 暂记 1，不做通用换算 */
        val BOX = Unit("BOX", "盒", UnitDimension.COUNT, 1)
        val METER = Unit("M", "米", UnitDimension.LENGTH, 1)
    }
}
