package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.quantity.Unit
import java.util.UUID

/**
 * 商品级包装换算（spec 03 unit_conversion 表）：
 * 一盒螺丝 = 50 个 → fromUnit=盒, toUnit=个, numerator=50, denominator=1。
 *
 * 换算为有理数比例 `from 数量 × numerator / denominator = to 数量`，
 * 禁止浮点比例（数据宪法 #2）；换算算法由 Task 030 引擎实现，
 * 本类只建模换算关系。
 */
data class UnitConversion(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val fromUnit: Unit,
    val toUnit: Unit,
    val ratioNumerator: Long,
    val ratioDenominator: Long
) {

    init {
        require(productId.isNotBlank()) { "productId 不能为空" }
        require(ratioNumerator > 0) { "换算分子必须为正" }
        require(ratioDenominator > 0) { "换算分母必须为正" }
    }
}
