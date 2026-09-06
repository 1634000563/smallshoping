package com.smallshoping.app.domain.catalog

import java.util.UUID

/**
 * 商品属性（spec 03 product_attribute 表）：
 * 通用属性化表达，菜店与五金复用同一模型（跨行业约束）。
 *
 * 例：`304 M8x30 外六角螺栓` → 材质=304、规格=M8x30、类型=外六角螺栓。
 * 名称与值都保留归一化副本，供模糊搜索（Task 032）使用。
 */
data class ProductAttribute(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val name: String,
    val normalizedName: String,
    val value: String,
    val normalizedValue: String
) {

    init {
        require(productId.isNotBlank()) { "productId 不能为空" }
        require(name.isNotBlank()) { "属性名不能为空" }
        require(normalizedName.isNotBlank()) { "归一化属性名不能为空" }
        require(value.isNotBlank()) { "属性值不能为空" }
        require(normalizedValue.isNotBlank()) { "归一化属性值不能为空" }
    }
}
