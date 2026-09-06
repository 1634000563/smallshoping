package com.smallshoping.app.domain.sales

import com.smallshoping.app.domain.ledger.LedgerEntry

/** 结账完成结果（事务边界在 Data 层实现内保证原子性）。 */
sealed interface CheckoutOutcome {

    data class Completed(val sale: SaleOrder) : CheckoutOutcome

    /** 同幂等键已结账：返回原销售，不重复扣库存 */
    data class AlreadyCompleted(val sale: SaleOrder) : CheckoutOutcome

    /** 库存不足且未开启负库存：未发生任何写入 */
    data class StockConflict(val productIds: List<String>) : CheckoutOutcome

    /** 幂等键冲突（非本单的重复提交） */
    data object Conflict : CheckoutOutcome
}

/**
 * 销售单端口（Repository = 事务边界，ARCHITECTURE §1）。
 *
 * [completeSale] 必须在单个事务内完成：
 * 销售单落库 + 全部库存流水追加，任一步失败整体回滚（spec 04 §2）。
 * AI 与 UI 都只能通过 Domain UseCase 到达本端口。
 */
interface SaleRepository {

    /** 保存/更新草稿单（未结账，不扣库存）。 */
    fun saveDraft(order: SaleOrder)

    fun findDraft(id: String): SaleOrder?

    fun findById(id: String): SaleOrder?

    /** 全部销售单（报表聚合用）。 */
    fun allSales(): List<SaleOrder>

    /**
     * 原子结账：校验幂等键与库存后，同事务写入销售单与库存流水。
     * 重复提交（同 checkoutIdempotencyKey）返回 [CheckoutOutcome.AlreadyCompleted]。
     */
    fun completeSale(
        sale: SaleOrder,
        stockEntries: List<LedgerEntry>,
        allowNegativeStock: Boolean
    ): CheckoutOutcome
}
