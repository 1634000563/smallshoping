package com.smallshoping.app.domain.inventory

import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.ledger.AppendResult
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import java.util.UUID

/** 损耗写入端口：损耗单与库存流水必须同事务落库。 */
interface LossRepository {

    /** 原子完成：追加 LOSS_OUT 流水 + 保存损耗记录；幂等键重复返回 [LossOutcome.AlreadyCompleted]。 */
    fun completeLoss(loss: LossRecord, entry: LedgerEntry): LossOutcome
}

sealed interface LossOutcome {
    data class Completed(val loss: LossRecord) : LossOutcome
    data class AlreadyCompleted(val loss: LossRecord) : LossOutcome
    data object Conflict : LossOutcome
}

data class RecordLossRequest(
    val productId: String,
    /** 损耗数量（维度基本单位：克/个/米）。 */
    val quantity: Quantity,
    val reason: String,
    val idempotencyKey: String
)

sealed interface RecordLossResult {
    data class Success(val loss: LossRecord, val stockAfter: Long) : RecordLossResult
    data object ProductNotFound : RecordLossResult
    data class UnitMismatch(val expected: Unit, val actual: Unit) : RecordLossResult
    data class InsufficientStock(val stock: Long, val requested: Long) : RecordLossResult
    data class AlreadyCompleted(val loss: LossRecord) : RecordLossResult
    data object Conflict : RecordLossResult
}

/**
 * 损耗（spec 02 LossRecord、spec 04 §8 损耗单独统计）：
 * 一条 LOSS_OUT 流水 + 一张损耗记录（成本快照），同事务原子完成。
 * 默认禁止损耗导致负库存（spec 04 §11 保守一致）。
 */
class RecordLossUseCase(
    private val losses: LossRepository,
    private val products: ProductRepository,
    private val stock: StockQuery
) {

    operator fun invoke(request: RecordLossRequest): RecordLossResult {
        val product = products.findProductById(request.productId)
            ?: return RecordLossResult.ProductNotFound
        val baseUnit = Unit.baseUnitFor(product.purchaseUnit.dimension)
        if (request.quantity.unit != baseUnit) {
            return RecordLossResult.UnitMismatch(baseUnit, request.quantity.unit)
        }
        val current = stock.stockOf(product.id)
        if (request.quantity.scaled > current) {
            return RecordLossResult.InsufficientStock(current, request.quantity.scaled)
        }

        // 损耗成本快照：当前加权平均成本 × 数量（无成本记 0）
        val costMinor = product.currentCostPrice
            ?.timesRatio(request.quantity.scaled, product.purchaseUnit.scale)
            ?.minor ?: 0L

        val entry = LedgerEntry(
            scope = LedgerScope(LedgerScopeType.STOCK, product.id),
            movementType = MovementType.LOSS_OUT,
            delta = -request.quantity.scaled,
            referenceType = "loss_record",
            referenceId = UUID.randomUUID().toString(),
            idempotencyKey = IdempotencyKey(request.idempotencyKey),
            note = "损耗：${request.reason}"
        )
        val loss = LossRecord(
            productId = product.id,
            quantity = request.quantity,
            reason = request.reason,
            costAmountMinor = costMinor,
            stockLedgerEntryId = entry.id
        )
        return when (val outcome = losses.completeLoss(loss, entry)) {
            is LossOutcome.Completed ->
                RecordLossResult.Success(outcome.loss, current - request.quantity.scaled)
            is LossOutcome.AlreadyCompleted ->
                RecordLossResult.AlreadyCompleted(outcome.loss)
            LossOutcome.Conflict -> RecordLossResult.Conflict
        }
    }
}

data class AdjustStockRequest(
    val productId: String,
    /** 目标数量（维度基本单位）。盘点：实际数量；差额自动走 ADJUST_IN/OUT。 */
    val targetQuantity: Quantity,
    val reason: String,
    val idempotencyKey: String
)

sealed interface AdjustStockResult {
    data class Success(val delta: Long, val stockAfter: Long) : AdjustStockResult
    /** 目标与当前一致：不产生流水（天然幂等）。 */
    data object Unchanged : AdjustStockResult
    data object ProductNotFound : AdjustStockResult
    data class UnitMismatch(val expected: Unit, val actual: Unit) : AdjustStockResult
    data class InsufficientStock(val stock: Long, val requested: Long) : AdjustStockResult
    data class AlreadyCompleted(val delta: Long) : AdjustStockResult
}

/**
 * 库存调整/盘点（spec 04 §5：不得直接改库存字段，只许追加流水）。
 *
 * 盘点场景：把实际数量设为 [AdjustStockRequest.targetQuantity]，
 * 差异 = 目标 - 当前，正差 ADJUST_IN、负差 ADJUST_OUT；
 * 调整必须留审计流水（spec 13）。默认禁止调整导致负库存。
 */
class AdjustStockUseCase(
    private val ledger: Ledger,
    private val products: ProductRepository,
    private val stock: StockQuery
) {

    operator fun invoke(request: AdjustStockRequest): AdjustStockResult {
        val product = products.findProductById(request.productId)
            ?: return AdjustStockResult.ProductNotFound
        val baseUnit = Unit.baseUnitFor(product.purchaseUnit.dimension)
        if (request.targetQuantity.unit != baseUnit) {
            return AdjustStockResult.UnitMismatch(baseUnit, request.targetQuantity.unit)
        }
        val current = stock.stockOf(product.id)
        val delta = request.targetQuantity.scaled - current
        if (delta == 0L) return AdjustStockResult.Unchanged
        if (delta < 0 && -delta > current) {
            return AdjustStockResult.InsufficientStock(current, -delta)
        }

        val entry = LedgerEntry(
            scope = LedgerScope(LedgerScopeType.STOCK, product.id),
            movementType = if (delta > 0) MovementType.ADJUST_IN else MovementType.ADJUST_OUT,
            delta = delta,
            referenceType = "stock_adjust",
            referenceId = UUID.randomUUID().toString(),
            idempotencyKey = IdempotencyKey(request.idempotencyKey),
            note = "库存调整：${request.reason}"
        )
        return when (ledger.append(entry)) {
            is AppendResult.Appended -> AdjustStockResult.Success(delta, current + delta)
            is AppendResult.Duplicate -> AdjustStockResult.AlreadyCompleted(delta)
        }
    }
}
