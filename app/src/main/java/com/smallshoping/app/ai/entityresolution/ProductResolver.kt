package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository

/**
 * 商品实体解析基础引擎。
 *
 * 打分规则（V1 最小，模糊搜索由 Task 032 强化）：
 * - 归一化名精确命中：100
 * - 归一化别名精确命中：别名置信度（0-100）
 * - 名称前缀命中：60；名称包含：40；别名包含：20
 *
 * 多个候选一律返回 [Resolution.Ambiguous]，由上层决定追问/确认，
 * 本引擎绝不替老板猜（产品宪法 #9）。
 */
class ProductResolver(private val products: ProductRepository) {

    fun resolve(query: String): Resolution<Product> {
        val q = normalize(query)
        if (q.isBlank()) return Resolution.NotFound

        // 1) 归一化名精确命中
        products.findByNormalizedName(q)?.let { return Resolution.Resolved(it, 100, "name_exact") }

        // 2) 别名精确命中（按置信度计分）
        products.findByAlias(q)?.let { product ->
            val alias = products.aliases(product.id).firstOrNull { it.normalizedAlias == q }
            return Resolution.Resolved(product, alias?.confidence ?: 80, "alias_exact")
        }

        // 3) 全目录候选扫描：按分排序、同商品取最高分去重
        val scored = LinkedHashMap<String, ScoredCandidate<Product>>()
        fun offer(product: Product, score: Int, reason: String) {
            val existing = scored[product.id]
            if (existing == null || score > existing.score) {
                scored[product.id] = ScoredCandidate(product, score, reason)
            }
        }
        for (product in products.allProducts()) {
            val name = product.normalizedName
            when {
                name.startsWith(q) -> offer(product, 60, "name_prefix")
                name.contains(q) -> offer(product, 40, "name_contains")
            }
            for (alias in products.aliases(product.id)) {
                val a = alias.normalizedAlias
                when {
                    a.startsWith(q) -> offer(product, 30, "alias_prefix")
                    a.contains(q) -> offer(product, 20, "alias_contains")
                }
            }
        }

        return when (scored.size) {
            0 -> Resolution.NotFound
            1 -> {
                val c = scored.values.first()
                Resolution.Resolved(c.value, c.score, c.reason)
            }
            else -> Resolution.Ambiguous(scored.values.sortedByDescending { it.score })
        }
    }
}
