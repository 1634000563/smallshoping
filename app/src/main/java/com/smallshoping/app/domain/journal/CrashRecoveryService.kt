package com.smallshoping.app.domain.journal

import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerScope

/**
 * 崩溃恢复（Task 045，V1 内存版）：
 * 重启后对全部账本范围执行重建校验（spec 04 §13）——
 * 缓存与流水不一致时抛 [DataIntegrityException]，绝不静默覆盖，
 * 收集进报告供维护处理。
 */
class CrashRecoveryService(private val ledger: Ledger) {

    data class RecoveryReport(
        val checkedScopes: Int,
        val corruptedScopes: List<LedgerScope>
    ) {
        val healthy: Boolean get() = corruptedScopes.isEmpty()
    }

    /** 重启校验：逐一 rebuildBalance，损坏范围不中断其余校验。 */
    fun verifyAfterRestart(): RecoveryReport {
        val corrupted = ArrayList<LedgerScope>()
        var checked = 0
        for (scope in ledger.allScopes()) {
            checked++
            try {
                ledger.rebuildBalance(scope)
            } catch (e: DataIntegrityException) {
                corrupted.add(scope)
            }
        }
        return RecoveryReport(checked, corrupted)
    }
}
