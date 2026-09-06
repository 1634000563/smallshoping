package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerRepository

/**
 * 客户实体解析（spec 06 §4、产品宪法 #9）。
 *
 * 打分规则与 [MemberResolver] 同一套 V1 最小规则：
 * - 归一化名精确命中：100
 * - 归一化别名/电话精确命中：80
 * - 名称前缀命中：60；名称包含：40；别名前缀：30；别名包含：20
 *
 * 多个候选一律返回 [Resolution.Ambiguous]，由上层追问。
 */
class CustomerResolver(private val customers: CustomerRepository) {

    fun resolve(query: String): Resolution<Customer> {
        val q = normalize(query)
        if (q.isBlank()) return Resolution.NotFound

        // 1) 归一化名精确命中
        customers.findByNormalizedName(q)?.let { return Resolution.Resolved(it, 100, "name_exact") }

        // 2) 全目录候选扫描：按分排序、同客户取最高分去重
        val scored = LinkedHashMap<String, ScoredCandidate<Customer>>()
        fun offer(customer: Customer, score: Int, reason: String) {
            val existing = scored[customer.id]
            if (existing == null || score > existing.score) {
                scored[customer.id] = ScoredCandidate(customer, score, reason)
            }
        }
        for (customer in customers.allCustomers()) {
            val name = customer.normalizedName
            when {
                name.startsWith(q) -> offer(customer, 60, "name_prefix")
                name.contains(q) -> offer(customer, 40, "name_contains")
            }
            customer.alias?.let { alias ->
                val a = normalize(alias)
                when {
                    a == q -> offer(customer, 80, "alias_exact")
                    a.startsWith(q) -> offer(customer, 30, "alias_prefix")
                    a.contains(q) -> offer(customer, 20, "alias_contains")
                }
            }
            if (customer.phone == q) offer(customer, 80, "phone_exact")
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
