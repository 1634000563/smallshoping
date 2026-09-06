package com.smallshoping.app.domain.ledger

import java.util.UUID

/**
 * 一条追加式账务流水（历史事实，禁止修改/删除，spec 04 §5）。
 *
 * @param delta 有符号变化量（最小刻度整数：分/克等），符号必须与 [movementType] 一致
 * @param referenceType/referenceId 业务来源引用（如 sale_order / SALE-123）
 * @param idempotencyKey 幂等键，同范围重复追加返回原流水而非重复记账
 */
data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val scope: LedgerScope,
    val movementType: MovementType,
    val delta: Long,
    val referenceType: String? = null,
    val referenceId: String? = null,
    val idempotencyKey: IdempotencyKey,
    val note: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        val positive = delta > 0
        val negative = delta < 0
        require((movementType.expectedSign > 0 && positive) ||
            (movementType.expectedSign < 0 && negative) ||
            delta == 0L) {
            "delta 符号与变动类型不一致：${movementType.name} delta=$delta"
        }
    }
}
