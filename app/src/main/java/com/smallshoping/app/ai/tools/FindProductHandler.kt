package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution

/**
 * find_product：按名称/别名查找商品（read，LOW，无确认）。
 * 经 Entity Resolution 引擎解析；多候选不猜测，返回 AMBIGUOUS 由上层消歧
 * （产品宪法 #9）。
 */
class FindProductHandler(private val resolver: ProductResolver) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("query")
        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf("status" to "NOT_FOUND", "products" to "")

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS",
                "products" to resolution.candidates.joinToString("|") {
                    "${it.value.id}=${it.value.name}@${it.value.saleUnit.name}/${it.value.currentSalePrice.minor}"
                },
                "ambiguous_key" to "query",
                "ambiguous_tool" to "find_product",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val p = resolution.value
                mapOf(
                    "status" to "OK",
                    "products" to "${p.id}=${p.name}@${p.saleUnit.name}/${p.currentSalePrice.minor}"
                )
            }
        }
    }
}
