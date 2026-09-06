package com.smallshoping.app.ai.entityresolution

/** 带分数的候选实体。 */
data class ScoredCandidate<T>(val value: T, val score: Int, val reason: String)

/**
 * 实体解析结果（spec 06 §4、产品宪法 #9）：
 * - 唯一命中 → [Resolved]
 * - 多个候选 → [Ambiguous]：低风险查询可展示候选，钱/库存/客户写操作必须消歧或确认
 * - 无候选 → [NotFound]：绝不猜测
 */
sealed interface Resolution<out T> {

    data class Resolved<T>(val value: T, val score: Int = 100, val reason: String = "exact") :
        Resolution<T>

    data class Ambiguous<T>(val candidates: List<ScoredCandidate<T>>) : Resolution<T>

    data object NotFound : Resolution<Nothing>
}
