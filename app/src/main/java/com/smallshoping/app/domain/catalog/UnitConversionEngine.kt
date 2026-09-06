package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit

/**
 * 单位换算引擎（Task 030）：斤/kg/个/盒/米 与商品级包装换算。
 *
 * 规则：
 * 1. 商品级包装换算优先（spec 03 unit_conversion，如 一盒=50个），支持正反两向；
 * 2. 同维度通用 scale 换算兜底（斤↔克↔kg，Unit.scale）；
 * 3. 跨维度（如 盒→克）只能经商品级换算，否则视为不可换算；
 * 4. 全程有理数精确换算（数据宪法 #2）：除不尽或超出目标单位精度返回 null，
 *    绝不四舍五入猜（称重精度规则，Task 018）；
 * 5. 溢出走 Math.*Exact 快速失败。
 */
class UnitConversionEngine(private val products: ProductRepository) {

    /**
     * 换算到目标单位，返回目标单位刻度整数：
     * `数量 × 10^toUnit.decimalPlaces`（如 1180 克 → 斤 返回 236，表示 2.36 斤）。
     * 除不尽、超精度或不可换算返回 null。
     */
    fun convertScaled(productId: String, quantity: Quantity, toUnit: Unit): Long? {
        val from = quantity.unit
        val factor = powerOf10(toUnit.decimalPlaces)

        // 1) 商品级换算（正向 from→to）
        products.findConversion(productId, from, toUnit)?.let { c ->
            val v = Math.multiplyExact(quantity.scaled, c.ratioNumerator)
            if (v % c.ratioDenominator != 0L) return null
            return Math.multiplyExact(v / c.ratioDenominator, factor)
        }
        // 2) 商品级换算（反向 to→from，取反比例）
        products.findConversion(productId, toUnit, from)?.let { c ->
            val v = Math.multiplyExact(quantity.scaled, c.ratioDenominator)
            if (v % c.ratioNumerator != 0L) return null
            return Math.multiplyExact(v / c.ratioNumerator, factor)
        }
        // 3) 同维度通用 scale 换算（含同单位恒等）；跨维度不可换算
        if (from.dimension != toUnit.dimension) return null
        val v = Math.multiplyExact(Math.multiplyExact(quantity.scaled, from.scale), factor)
        if (v % toUnit.scale != 0L) return null
        return v / toUnit.scale
    }

    /** 便捷版：目标单位为整数刻度单位（克/个/米）时返回 [Quantity]，否则抛异常。 */
    fun convert(productId: String, quantity: Quantity, toUnit: Unit): Quantity? {
        require(toUnit.decimalPlaces == 0) { "目标单位非整数刻度：${toUnit.code}，请用 convertScaled" }
        return convertScaled(productId, quantity, toUnit)?.let { Quantity(it, toUnit) }
    }

    private fun powerOf10(n: Int): Long {
        var result = 1L
        repeat(n) { result = Math.multiplyExact(result, 10L) }
        return result
    }
}
