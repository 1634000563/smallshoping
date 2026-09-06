package com.smallshoping.app.domain.catalog

/** 别名来源。 */
enum class AliasSource { BOSS_SPEECH, MANUAL, IMPORT, SYSTEM }

/**
 * 商品别名：老板怎么说，系统就记什么（如「土豆」的别名「洋芋」）。
 * 来源与置信度保留，供 Entity Resolution（Task 012）与记忆学习（Task 023）使用。
 */
data class ProductAlias(
    val id: String,
    val productId: String,
    val alias: String,
    val normalizedAlias: String,
    val source: AliasSource,
    /** 0-100，越高越可信 */
    val confidence: Int,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(alias.isNotBlank()) { "别名不能为空" }
        require(confidence in 0..100) { "置信度须在 0-100：$confidence" }
    }
}
