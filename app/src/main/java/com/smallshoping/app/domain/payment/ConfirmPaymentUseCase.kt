package com.smallshoping.app.domain.payment

/**
 * 老板确认到账（spec 11 §2）：手工微信/支付宝支付
 * PENDING → CONFIRMED；已确认/失败的记录不被改动（状态机，spec 08 §4）。
 */
class ConfirmPaymentUseCase(private val payments: PaymentRepository) {

    data class ConfirmResult(val confirmed: List<Payment>, val skipped: Int)

    /** 确认某销售单的全部待确认支付；返回确认后的记录与跳过数。 */
    fun confirmBySale(saleOrderId: String): ConfirmResult {
        val confirmed = ArrayList<Payment>()
        var skipped = 0
        for (payment in payments.bySale(saleOrderId)) {
            if (payment.status == PaymentStatus.PENDING) {
                payments.confirm(payment.id)?.let { confirmed.add(it) } ?: skipped++
            } else {
                skipped++
            }
        }
        return ConfirmResult(confirmed, skipped)
    }

    /** 标记支付失败/撤销（PENDING 才可迁移）。 */
    fun markFailed(paymentId: String): Payment? = payments.mark(paymentId, PaymentStatus.FAILED)
}
