package com.smallshoping.app.domain.ledger

/** 原子批量追加时发现重复幂等键：整个批次未生效（事务回滚语义）。 */
class IdempotencyConflictException(val keys: Set<IdempotencyKey>) :
    IllegalStateException("批量追加含重复幂等键，批次已整体拒绝：${keys.joinToString()}")

/** 账本重建结果与缓存不一致（spec 04 §13）：禁止静默覆盖，需人工排查。 */
class DataIntegrityException(val scope: LedgerScope, val rebuilt: Long, val cached: Long) :
    IllegalStateException(
        "账本重建不一致：${scope} 重建=$rebuilt 缓存=$cached，已停止，不允许静默覆盖"
    )
