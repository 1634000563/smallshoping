package com.smallshoping.app.domain.payment

import com.smallshoping.app.domain.sales.PaymentMethod
import java.util.UUID

/** 支付状态（spec 08 §4：PENDING → CONFIRMED / FAILED / REVERSED）。 */
enum class PaymentStatus { PENDING, CONFIRMED, FAILED, REVERSED }

/**
 * 支付记录（spec 03 payment 表）：
 * id, sale_order_id, method, status, amount_minor, external_reference,
 * idempotency_key, created_at, confirmed_at。
 *
 * 手工微信/支付宝：记录时为 PENDING，老板确认到账后 CONFIRMED
 * （spec 11：老板确认与官方 API 验证是两个状态来源，V1 只有前者）。
 */
data class Payment(
    val id: String = UUID.randomUUID().toString(),
    val saleOrderId: String,
    val method: PaymentMethod,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val amountMinor: Long,
    val externalReference: String? = null,
    val idempotencyKey: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val confirmedAtMillis: Long? = null
) {

    init {
        require(saleOrderId.isNotBlank()) { "sale_order_id 不能为空" }
        require(amountMinor > 0) { "支付金额必须为正：$amountMinor" }
        require(idempotencyKey.isNotBlank()) { "幂等键不能为空" }
    }
}
