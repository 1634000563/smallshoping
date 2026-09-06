package com.smallshoping.app.ai.tools

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.catalog.ProductRepository

/**
 * find_product：按名称/别名查找商品（read，LOW，无确认）。
 * 命中多个时不猜测，返回全部候选由上层消歧（产品宪法 #9）。
 */
class FindProductHandler(private val products: ProductRepository) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = normalize(entities.getValue("query"))
        val byName = products.findByNormalizedName(query)
        val byAlias = products.findByAlias(query)
        val hits = listOfNotNull(byName, byAlias).distinctBy { it.id }
        return if (hits.isEmpty()) {
            mapOf("status" to "NOT_FOUND", "products" to "")
        } else {
            mapOf(
                "status" to "OK",
                "products" to hits.joinToString("|") {
                    "${it.id}=${it.name}@${it.saleUnit.name}/${it.currentSalePrice.minor}"
                }
            )
        }
    }
}
