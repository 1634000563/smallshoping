package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.sales.CheckoutOutcome
import com.smallshoping.app.domain.sales.SaleOrder
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus

/**
 * 内存销售单实现：completeSale 为事务边界（单锁内完成销售落库 + 库存流水），
 * Room/SQLite 实现必须用 withTransaction 保持同一语义。
 */
class InMemorySaleRepository(private val ledger: Ledger) : SaleRepository {

    private val lock = Any()
    private val orders = LinkedHashMap<String, SaleOrder>()
    private val byCheckoutKey = LinkedHashMap<String, SaleOrder>()

    override fun saveDraft(order: SaleOrder) {
        synchronized(lock) {
            orders[order.id] = order
        }
    }

    override fun findDraft(id: String): SaleOrder? = synchronized(lock) {
        orders[id]
    }

    override fun findById(id: String): SaleOrder? = synchronized(lock) {
        orders[id]
    }

    override fun allSales(): List<SaleOrder> = synchronized(lock) {
        orders.values.toList()
    }

    override fun completeSale(
        sale: SaleOrder,
        stockEntries: List<LedgerEntry>,
        allowNegativeStock: Boolean
    ): CheckoutOutcome = synchronized(lock) {
        val key = sale.checkoutIdempotencyKey
        if (key != null) {
            byCheckoutKey[key]?.let { return CheckoutOutcome.AlreadyCompleted(it) }
        }
        // 幂等键预检：任一库存流水键已存在则整单拒绝
        for (entry in stockEntries) {
            val existing = ledger.entries(entry.scope).any { it.idempotencyKey == entry.idempotencyKey }
            if (existing) return CheckoutOutcome.Conflict
        }
        // 库存预检（同锁内，与写入原子）
        if (!allowNegativeStock) {
            val conflicts = stockEntries
                .filter { entry -> ledger.balance(entry.scope) + entry.delta < 0 }
                .map { it.scope.scopeId }
                .distinct()
            if (conflicts.isNotEmpty()) return CheckoutOutcome.StockConflict(conflicts)
        }
        // 同事务写入：先销售单后库存流水；任一步异常由调用方整体失败
        orders[sale.id] = sale.copy(status = SaleStatus.COMPLETED)
        ledger.transact(stockEntries)
        key?.let { byCheckoutKey[it] = sale }
        CheckoutOutcome.Completed(sale)
    }

    /** 数据擦除（spec 13 §5，仅 DataWipeService 调用）。 */
    fun wipe() = synchronized(lock) {
        orders.clear()
        byCheckoutKey.clear()
    }
}
