package com.smallshoping.app.domain.payment

import com.smallshoping.app.domain.sales.PaymentMethod

/** 支付记录写入结果。 */
sealed interface RecordPaymentResult {
    data class Recorded(val payment: Payment) : RecordPaymentResult

    /** 同幂等键重复提交：返回原记录，不重复记账。 */
    data class AlreadyRecorded(val payment: Payment) : RecordPaymentResult
}

/**
 * 支付记录端口（Domain 侧契约，spec 03 payment 表）。
 * 支付记录是追加式事实：状态迁移（PENDING→CONFIRMED）只改本行状态，
 * 不删除历史（spec 08 §4）。
 */
interface PaymentRepository {

    /** 记录支付；同幂等键返回原记录。 */
    fun record(payment: Payment): RecordPaymentResult

    /** 某销售单的全部支付记录（按追加顺序）。 */
    fun bySale(saleOrderId: String): List<Payment>

    /** 全部支付记录（日结核对用，Task 048）。 */
    fun all(): List<Payment>

    /** 状态迁移：PENDING → CONFIRMED（老板确认到账）；非 PENDING 返回 null。 */
    fun confirm(paymentId: String): Payment?

    /** 状态迁移：PENDING → FAILED / REVERSED（收款失败/撤销）。 */
    fun mark(paymentId: String, status: PaymentStatus): Payment?
}
