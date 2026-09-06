package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.MemberRepository

/**
 * 会员实体解析（spec 06 §4、产品宪法 #9）。
 *
 * 打分规则（与 [ProductResolver] 同一套 V1 最小规则）：
 * - 归一化名精确命中：100
 * - 归一化别名/电话精确命中：80
 * - 名称前缀命中：60；名称包含：40；别名前缀：30；别名包含：20
 *
 * 多个候选一律返回 [Resolution.Ambiguous]，由上层追问，
 * 本引擎绝不替老板猜——尤其充值是写资金账的操作。
 */
class MemberResolver(private val members: MemberRepository) {

    fun resolve(query: String): Resolution<Member> {
        val q = normalize(query)
        if (q.isBlank()) return Resolution.NotFound

        // 1) 归一化名精确命中
        members.findByNormalizedName(q)?.let { return Resolution.Resolved(it, 100, "name_exact") }

        // 2) 全目录候选扫描：按分排序、同会员取最高分去重
        val scored = LinkedHashMap<String, ScoredCandidate<Member>>()
        fun offer(member: Member, score: Int, reason: String) {
            val existing = scored[member.id]
            if (existing == null || score > existing.score) {
                scored[member.id] = ScoredCandidate(member, score, reason)
            }
        }
        for (member in members.allMembers()) {
            val name = member.normalizedName
            when {
                name.startsWith(q) -> offer(member, 60, "name_prefix")
                name.contains(q) -> offer(member, 40, "name_contains")
            }
            member.alias?.let { alias ->
                val a = normalize(alias)
                when {
                    a == q -> offer(member, 80, "alias_exact")
                    a.startsWith(q) -> offer(member, 30, "alias_prefix")
                    a.contains(q) -> offer(member, 20, "alias_contains")
                }
            }
            if (member.phone == q) offer(member, 80, "phone_exact")
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
