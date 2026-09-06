package com.smallshoping.app.domain.purchase

import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.ledger.LedgerEntry

/** 采购入库完成结果（事务边界在 Data 实现内）。 */
sealed interface PurchaseOutcome {

    data class Completed(val order: PurchaseOrder) : PurchaseOutcome

    data class AlreadyCompleted(val order: PurchaseOrder) : PurchaseOutcome

    data object Conflict : PurchaseOutcome
}

/**
 * 采购端口：Repository = 事务边界。
 *
 * [completePurchase] 必须在单个事务内完成：
 * 采购单落库 + 库存入库流水 + 商品成本更新（加权平均），
 * 任一步失败整体回滚（spec 04 §2 同构约束）。
 */
interface PurchaseRepository {

    fun findById(id: String): PurchaseOrder?

    fun completePurchase(
        order: PurchaseOrder,
        stockEntry: LedgerEntry,
        updatedProduct: Product?
    ): PurchaseOutcome
}
