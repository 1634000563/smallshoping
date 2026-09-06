package com.smallshoping.app.domain.inventory

import com.smallshoping.app.core.quantity.Quantity
import java.util.UUID

/**
 * 损耗记录（spec 03 loss_record 表）：
 * id, product_id, quantity_scaled, unit_id, reason, cost_amount_minor,
 * stock_ledger_id, created_at。
 *
 * 数量一律为维度基本单位刻度（克/个/米）；[costAmountMinor] 为损耗成本快照
 * （当前加权平均成本 × 数量，写入时定格，历史成本变动不影响已有记录）。
 */
data class LossRecord(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val quantity: Quantity,
    val reason: String,
    val costAmountMinor: Long,
    /** 对应库存流水 id（audit：流水与损耗单互相引用）。 */
    val stockLedgerEntryId: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(productId.isNotBlank()) { "productId 不能为空" }
        require(reason.isNotBlank()) { "损耗原因不能为空" }
        require(!quantity.isNegative && !quantity.isZero) { "损耗数量必须为正：$quantity" }
        require(costAmountMinor >= 0) { "损耗成本不能为负：$costAmountMinor" }
    }
}
