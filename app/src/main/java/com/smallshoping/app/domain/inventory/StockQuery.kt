package com.smallshoping.app.domain.inventory

import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType

/**
 * 库存查询：余额一律由库存账流水派生（数据宪法 #4/#11，
 * 禁止直接覆盖库存字段）。
 */
class StockQuery(private val ledger: Ledger) {

    fun stockOf(productId: String): Long =
        ledger.balance(LedgerScope(LedgerScopeType.STOCK, productId))
}
