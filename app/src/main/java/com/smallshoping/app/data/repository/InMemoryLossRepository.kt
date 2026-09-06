package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.inventory.LossOutcome
import com.smallshoping.app.domain.inventory.LossRecord
import com.smallshoping.app.domain.inventory.LossRepository
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry

/**
 * 内存损耗仓库：损耗单与库存流水同锁原子落库（事务边界）。
 *
 * 语义基线同 [com.smallshoping.app.data.ledger.InMemoryLedger]：
 * 真实持久化实现（Room/SQLite）须通过同一组测试。
 */
class InMemoryLossRepository(private val ledger: Ledger) : LossRepository {

    private val lock = Any()
    private val byId = LinkedHashMap<String, LossRecord>()

    override fun completeLoss(loss: LossRecord, entry: LedgerEntry): LossOutcome =
        synchronized(lock) {
            when (val result = ledger.append(entry)) {
                is com.smallshoping.app.domain.ledger.AppendResult.Appended -> {
                    byId[loss.id] = loss
                    LossOutcome.Completed(loss)
                }

                is com.smallshoping.app.domain.ledger.AppendResult.Duplicate -> {
                    // 幂等重试：按原流水的 id 找回首次落库的损耗单
                    val original = byId.values.firstOrNull {
                        it.stockLedgerEntryId == result.original.id
                    } ?: loss
                    LossOutcome.AlreadyCompleted(original)
                }
            }
        }

    /** 全部损耗记录（按写入顺序）。 */
    fun all(): List<LossRecord> = synchronized(lock) { byId.values.toList() }
}
