package com.smallshoping.app.domain.memory

import java.util.UUID

/**
 * 记忆作用域（spec 06 §1：Store Memory 长期 + Customer Memory 长期但可撤销）。
 * 商品/会员也作为可挂事实的主体，但不复制其账务事实。
 */
enum class MemoryScopeType { STORE, CUSTOMER, MEMBER, PRODUCT }

/** 记忆来源（spec 06 §3：只有两类允许写入长期记忆）。 */
enum class MemorySource {
    /** 用户明确说「以后都这样」或确认了系统建议的规则 */
    USER_CONFIRMED,

    /** 可重复观察且达到阈值的稳定模式（保留 confidence） */
    OBSERVED_PATTERN
}

/**
 * 一条店铺长期记忆事实（spec 03 §4 memory_fact 表）。
 *
 * 只保存经过验证的偏好、别名、规则和引用；
 * 不得替代 Ledger/DB 事实，也不得把全库复制进记忆（Task 023 约束）。
 *
 * @param valueJson 事实值（JSON 字符串），由写入方保证可反序列化
 * @param confidence 置信度 0-100；推断记忆低于确认记忆（spec 06 §2 优先级）
 * @param lastConfirmedAtMillis 最近一次被确认的时间；观察型记忆可能从未被确认
 */
data class MemoryFact(
    val id: String = UUID.randomUUID().toString(),
    val scopeType: MemoryScopeType,
    val scopeId: String,
    val factType: String,
    val key: String,
    val valueJson: String,
    val confidence: Int,
    val source: MemorySource,
    val lastConfirmedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val active: Boolean = true
) {

    init {
        require(scopeId.isNotBlank()) { "scope_id 不能为空" }
        require(factType.isNotBlank()) { "fact_type 不能为空" }
        require(key.isNotBlank()) { "key 不能为空" }
        require(valueJson.isNotBlank()) { "value_json 不能为空" }
        require(confidence in 0..100) { "置信度必须在 0-100：$confidence" }
    }
}
