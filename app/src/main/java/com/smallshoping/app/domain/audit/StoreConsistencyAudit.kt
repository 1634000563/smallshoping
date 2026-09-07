package com.smallshoping.app.domain.audit

import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerScope

/** 一处账本范围的重建差异明细（维护定位依据，spec 04 §13）。 */
data class ScopeMismatch(
    val scope: LedgerScope,
    val rebuiltMinor: Long,
    val cachedMinor: Long
)

/** 全店一致性审计报告。 */
data class AuditReport(
    val checkedScopes: Int,
    val mismatches: List<ScopeMismatch>
) {
    val healthy: Boolean get() = mismatches.isEmpty()
}

/**
 * 全店账务一致性审计（Task 055，spec 04 §13 维护能力）：
 * 对全部账本范围（库存账/会员资金账/客户应收账）逐次重建核对，
 * 差异处记录「流水重建值 vs 缓存值」明细，绝不静默覆盖；
 * 一处损坏不中断其余范围检查。
 *
 * 与 CrashRecoveryService（Task 045 重启校验）的关系：
 * 重启校验只列损坏范围，本审计供日常维护/灾难演练定位差异金额。
 */
class StoreConsistencyAudit(private val ledger: Ledger) {

    fun audit(): AuditReport {
        val mismatches = ArrayList<ScopeMismatch>()
        var checked = 0
        for (scope in ledger.allScopes()) {
            checked++
            try {
                ledger.rebuildBalance(scope)
            } catch (e: DataIntegrityException) {
                mismatches.add(ScopeMismatch(scope, e.rebuilt, e.cached))
            }
        }
        return AuditReport(checked, mismatches)
    }
}
