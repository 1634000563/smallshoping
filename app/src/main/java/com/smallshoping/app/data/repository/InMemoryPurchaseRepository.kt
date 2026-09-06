package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.purchase.PurchaseOrder
import com.smallshoping.app.domain.purchase.PurchaseOutcome
import com.smallshoping.app.domain.purchase.PurchaseRepository

/**
 * 内存采购实现：completePurchase 为事务边界
 * （单锁内：采购单落库 + 入库流水 + 成本更新，全部或全不）。
 */
class InMemoryPurchaseRepository(
    private val ledger: Ledger,
    private val products: ProductRepository
) : PurchaseRepository {

    private val lock = Any()
    private val orders = LinkedHashMap<String, PurchaseOrder>()
    private val byIdempotencyKey = LinkedHashMap<String, PurchaseOrder>()

    override fun findById(id: String): PurchaseOrder? = synchronized(lock) {
        orders[id]
    }

    override fun completePurchase(
        order: PurchaseOrder,
        stockEntry: LedgerEntry,
        updatedProduct: Product?
    ): PurchaseOutcome = synchronized(lock) {
        byIdempotencyKey[order.idempotencyKey]?.let { return PurchaseOutcome.AlreadyCompleted(it) }
        val keyExists = ledger.entries(stockEntry.scope)
            .any { it.idempotencyKey == stockEntry.idempotencyKey }
        if (keyExists) return PurchaseOutcome.Conflict
        // 事务体：全部写入（内存实现不抛异常；Room 版 withTransaction 同语义）
        orders[order.id] = order
        ledger.append(stockEntry)
        updatedProduct?.let { products.saveProduct(it) }
        byIdempotencyKey[order.idempotencyKey] = order
        PurchaseOutcome.Completed(order)
    }
}
